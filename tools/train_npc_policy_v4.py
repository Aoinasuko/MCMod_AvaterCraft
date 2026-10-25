"""Train five routed FP32 experts (~20 MiB); raw language stays with the instruction AI."""
import argparse
import copy
import gzip
import hashlib
import json
import os
from pathlib import Path
os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
import numpy as np
import torch
from torch import nn
from train_npc_policy_v3 import FEATURES, DIMS
from generate_npc_curriculum import SEED, score

EXPERTS=['general-farming-transfer','crafting-smelting','combat','navigation-dimensions','underground']
WEIGHT_BYTES=4204004*len(EXPERTS)

def route(x):
    if x[32]>.5 or x[36]>.5: return 2
    if x[40]>.5 or x[17]>.5 or x[3]>.5: return 1
    if x[44]>.5 or x[46]>.5: return 3
    if x[27]>.5: return 4
    return 0

def load(path,device):
    with gzip.open(path,'rt',encoding='utf-8') as f: examples=[json.loads(line) for line in f]
    x=np.asarray([e['candidates'] for e in examples],dtype=np.float32)
    assert x.shape[1:]==(6,48) and np.isfinite(x).all() and np.max(np.abs(x))<=1
    y=np.asarray([e['chosen'] for e in examples],dtype=np.int64)
    target=np.asarray([[score(v)/4 for v in e['candidates']] for e in examples],dtype=np.float32)
    routes=np.asarray([[route(v) for v in e['candidates']] for e in examples],dtype=np.int64)
    fingerprints={hashlib.sha256(x[i].tobytes()).hexdigest() for i in range(len(x))}
    return tuple(torch.from_numpy(v).to(device) for v in (x,y,target,routes)),fingerprints

class Model(nn.Module):
    def __init__(self):
        super().__init__()
        self.experts=nn.ModuleList([nn.Sequential(nn.Linear(48,1000),nn.ReLU(),nn.Linear(1000,1000),nn.ReLU(),nn.Linear(1000,1)) for _ in EXPERTS])
    def forward(self,x,routes):
        shape=x.shape[:-1]; x=x.reshape(-1,48); routes=routes.reshape(-1)
        out=torch.zeros(len(x),device=x.device)
        for i,expert in enumerate(self.experts):
            mask=routes==i
            if mask.any(): out[mask]=expert(x[mask]).squeeze(-1)
        return out.reshape(shape)

def evaluate(model,data):
    x,y,target,routes=data
    with torch.no_grad():
        scores=torch.cat([model(x[i:i+1024],routes[i:i+1024]) for i in range(0,len(x),1024)])
        chosen=scores.argmax(-1)
        expected=target.max(-1).values
        correct=target.gather(1,chosen[:,None]).squeeze(1)>=expected-1e-6
        urgent=(x[:,:,0]>.5)&(x[:,:,19]>.5)&(x[:,:,20]>.5)
        urgent_case=urgent.any(-1)
        selected=x.gather(1,chosen[:,None,None].expand(-1,1,48)).squeeze(1)
        illegal=(selected[:,19]<.5)|(selected[:,20]<.5)|(selected[:,15]>.5)
        return dict(optimalAccuracy=correct.float().mean().item(),
                    urgentAccuracy=correct[urgent_case].float().mean().item() if urgent_case.any() else 1.,
                    illegalSelectionRate=illegal.float().mean().item(),examples=len(x))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data',type=Path,default=Path('TrainingData/npc-v4'))
    p.add_argument('--output',type=Path,default=Path('src/npc/resources/data/bep_3davater_companion/ai/action-policy.json'))
    p.add_argument('--epochs',type=int,default=40)
    p.add_argument('--device',default='cuda' if torch.cuda.is_available() else 'cpu')
    a=p.parse_args();torch.set_num_threads(4);torch.manual_seed(SEED);torch.use_deterministic_algorithms(True)
    torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
    device=torch.device(a.device); model=Model().to(device)
    # Keep the actual warm-start weights so the run can be reproduced after replacing the model.
    checkpoint=a.data/'initial-policy.bin'
    if not checkpoint.exists(): checkpoint.write_bytes(a.output.with_suffix('.bin').read_bytes())
    old=checkpoint.read_bytes();initial_sha=hashlib.sha256(old).hexdigest()
    if len(old) in (4204004,WEIGHT_BYTES):
        full=len(old)==WEIGHT_BYTES
        old=np.frombuffer(old,dtype='<f4').copy(); offset=0
        with torch.no_grad():
            for expert in (model.experts if full else model.experts[:1]):
                for param in expert.parameters():
                    size=param.numel();param.copy_(torch.tensor(old[offset:offset+size].reshape(tuple(param.shape)),device=device));offset+=size
            if not full:
                for expert in model.experts[1:]: expert.load_state_dict(model.experts[0].state_dict())
    else: raise ValueError('Unknown initialization checkpoint size')
    data,train_hash=load(a.data/'train.jsonl.gz',device)
    val,val_hash=load(a.data/'validation.jsonl.gz',device)
    test,test_hash=load(a.data/'test.jsonl.gz',device)
    assert not train_hash&val_hash and not train_hash&test_hash and not val_hash&test_hash
    opt=torch.optim.AdamW(model.parameters(),lr=.0001,weight_decay=.00001)
    print(json.dumps(dict(device=str(device),experts=EXPERTS,trainExamples=len(data[0]),weightBytes=WEIGHT_BYTES,initial=evaluate(model,val))),flush=True)
    best=None; best_accuracy=-1
    for epoch in range(a.epochs):
        for group in opt.param_groups: group['lr']=.0001*(.5**(epoch//12))
        model.train()
        for ids in torch.randperm(len(data[0]),device=device).split(2048):
            x,y,target,routes=[v[ids] for v in data]
            pred=model(x,routes); optimal=target>=target.max(-1,keepdim=True).values-1e-6
            ce=(pred.logsumexp(-1)-pred.masked_fill(~optimal,-1e9).logsumexp(-1)).mean()
            loss=(pred-target).square().mean()+.01*ce
            opt.zero_grad(set_to_none=True);loss.backward();opt.step()
        if (epoch+1)%2==0:
            model.eval();metrics=evaluate(model,val)
            print(json.dumps(dict(epoch=epoch+1,loss=loss.item(),**metrics)),flush=True)
            if metrics['optimalAccuracy']>best_accuracy and metrics['illegalSelectionRate']==0:
                best_accuracy=metrics['optimalAccuracy'];best=copy.deepcopy(model.state_dict())
    if best is None: raise ValueError('No model passed legality gate')
    model.load_state_dict(best);model.eval(); validation=evaluate(model,val);independent=evaluate(model,test)
    for metrics in (validation,independent):
        if metrics['optimalAccuracy']<.98 or metrics['urgentAccuracy']<.999 or metrics['illegalSelectionRate']!=0:
            raise ValueError('Validation gate failed: '+str(metrics))
    cpu=model.cpu(); weights=b''.join(t.detach().numpy().astype('<f4').tobytes() for expert in cpu.experts for t in expert.parameters())
    assert len(weights)==WEIGHT_BYTES
    probes=[]
    with torch.no_grad():
        for i in range(5):
            # Include at least one probe per routed expert.
            v=np.zeros(48,dtype=np.float32);v[19]=v[20]=1
            if i==1:v[3]=1
            if i==2:v[32]=1
            if i==3:v[44]=1
            if i==4:v[27]=1
            assert route(v)==i
            probes.append(dict(input=v.tolist(),expert=i,score=cpu.experts[i](torch.tensor(v)).item()))
    corpus=json.loads((a.data/'manifest.json').read_text(encoding='utf-8'))
    training=dict(seed=SEED,source='bilingual-synthetic-curriculum-v4',epochs=a.epochs,initialWeightsSha256=initial_sha,corpus=corpus,
                  validation=validation,independentTest=independent,
                  scope='Language examples condition the instruction AI; learned policy ranks structured action candidates. Not a gameplay mission completion rate.')
    manifest=dict(version=4,features=FEATURES,dimensions=DIMS,experts=EXPERTS,routing='context-v4',activation='relu',
                  format='float32-le',weights=a.output.with_suffix('.bin').name,weightBytes=len(weights),parameters=len(weights)//4,
                  sha256=hashlib.sha256(weights).hexdigest(),probes=probes,training=training)
    a.output.with_suffix('.bin').write_bytes(weights)
    a.output.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(weightBytes=len(weights),sha256=manifest['sha256'],validation=validation,independentTest=independent)),flush=True)

if __name__=='__main__': main()
