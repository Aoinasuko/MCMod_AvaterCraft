"""Forty-MiB candidate policy trained on five-seed gameplay with world-held-out outcome labels."""
import argparse
import collections
import copy
import gzip
import hashlib
import json
import math
from pathlib import Path
import random
import numpy as np
import torch
from torch import nn
import train_npc_policy_v5 as base

HIDDEN = 1419
DIMS = [56, HIDDEN, HIDDEN, 1]
SEEDS = [61279, 61280, 61281, 61282, 61283]
SPLIT = {61279:'train',61280:'train',61281:'train',61282:'validation',61283:'test'}
VERSION = 6
INITIALIZATION = 'Function-preserving hidden-layer widening plus 1e-6 symmetry breaking; all parameters trained'
DEFAULT_LOGS = 'build/npc-campaign-archive'

def pad_observed(row):
    while len(row['candidates']) < base.SLOTS:
        x=[0.]*56
        x[12]=x[19]=x[20]=1.
        row['candidates'].append(x); row['targets'].append(-3.5);row['mask'].append(False)
    return row

def runtime(folder, seed):
    """Join per-actor policy decisions to exact dispatched arguments and actionId results."""
    for path in sorted(folder.rglob('*.jsonl')):
        raw=path.read_bytes()
        records=[json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]
        results={r['details'].get('actionId'):r for r in records if r['kind']=='tool_result'}
        mission=-1
        for index,record in enumerate(records):
            if record['kind']=='campaign_mission':mission=record['details']['mission']
            if record['kind']!='local_policy':continue
            d=record['details']; chosen=d['chosen']; action=d['actions'][chosen]
            if not action['tool']:continue
            start=None
            for following in records[index+1:]:
                if following['kind']=='local_policy':break
                if following['kind']=='tool_start' and following['text']==action['tool']:
                    if following['details'].get('args') == action['args'] or following['details'].get('arguments') == action['args']:
                        start=following;break
                    # Older runtime records use the argument object directly under details.
                    if all(following['details'].get(k)==v for k,v in action['args'].items()):
                        start=following;break
            if start is None:continue
            result=results.get(start['details'].get('actionId'))
            if result is None:continue
            detail=result['details']
            if any(s in detail.get('error','').lower() for s in ('cancel','offline','disappeared')):continue
            candidates=d['candidates']
            if not all(len(v)==56 for v in candidates):raise ValueError('Campaign requires exact recorded 56-feature inputs')
            labels=[0.]*len(candidates);mask=[False]*len(candidates);mask[chosen]=True
            labels[chosen]=base.old_score(candidates[chosen][:48])/4 if detail.get('ok') else -2.
            yield pad_observed(dict(source='observed-runtime',group=f'world-{seed}-mission-{mission}',worldSeed=seed,
                mission=mission,sourceSha256=base.digest(raw),sourceFile=path.name,policyLine=index+1,
                actionId=start['details']['actionId'],outcome=detail.get('ok',False),outcomeCode=detail.get('code',''),
                candidates=candidates,targets=labels,mask=mask))

def corpus(folder, logs):
    folder.mkdir(parents=True,exist_ok=True)
    observed=[];raw_files=[]
    for seed in SEEDS:
        root=logs/str(seed)
        if not root.is_dir():raise ValueError(f'Missing archived world {seed}')
        observed.extend(runtime(root,seed))
        raw_files.extend(dict(seed=seed,path=str(p.relative_to(logs)),sha256=base.digest(p.read_bytes())) for p in root.rglob('*.jsonl'))
    if not observed:raise ValueError('No observed action outcomes; refusing synthetic-only campaign training')
    archive=folder/'observed-actions.jsonl.gz'
    archive.write_bytes(gzip.compress(('\n'.join(json.dumps(e,separators=(',',':')) for e in observed)+'\n').encode(),mtime=0))
    groups={split:[e for e in observed if SPLIT[e['worldSeed']]==split] for split in ('train','validation','test')}
    # Identical candidate tensors can occur across worlds (e.g. remembering a chest). Keep the held-out
    # occurrence and remove duplicates from earlier splits, without moving data between world groups.
    seen=set();removed={}
    for split in ('test','validation','train'):
        kept=[];removed[split]=0
        for e in groups[split]:
            key=base.digest(np.asarray(e['candidates'],dtype='<f4').tobytes())
            if key in seen:removed[split]+=1
            else:kept.append(e);seen.add(key)
        groups[split]=kept
    with gzip.open('TrainingData/npc-v5/observed-actions.jsonl.gz','rt',encoding='utf-8') as f:
        old=[json.loads(line) for line in f]
    # Old audit remains training-only; its reconstructed geometry is explicitly identified.
    for e in old:
        e['group']='neo-20261026-train-only'
        key=base.digest(np.asarray(e['candidates'],dtype='<f4').tobytes())
        if key not in seen:groups['train'].append(e);seen.add(key)
    manifest=dict(seed=base.SEED,features=base.FEATURES,worldSplits=SPLIT,rawJournals=raw_files,
        campaignOutcomes=dict(collections.Counter(str(e['outcome']) for e in observed)),
        campaignActionCount=len(observed),deduplicated=removed,splits={},
        labelPolicy='Only dispatched actions have observed outcomes. All unchosen alternatives and observed padding are masked. '
        'Worlds 61282 and 61283 are held out; exact candidate duplicates are excluded across splits. '
        'Synthetic curriculum accuracy is not gameplay or held-out action success rate.')
    for split,base_count,nav in [('train',48000,12000),('validation',6000,1500),('test',6000,1500)]:
        rows=list(base.legacy(split,base_count))+list(base.navigation(split,nav))+groups[split]
        random.Random(base.SEED+base_count).shuffle(rows)
        payload=gzip.compress(('\n'.join(json.dumps(e,separators=(',',':')) for e in rows)+'\n').encode(),mtime=0)
        (folder/(split+'.jsonl.gz')).write_bytes(payload)
        manifest['splits'][split]=dict(examples=len(rows),sha256=base.digest(payload),
            sources=dict(collections.Counter(e['source'] for e in rows)),
            observedOutcomes=dict(collections.Counter(str(e['outcome']) for e in groups[split])))
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    return manifest

class Model(nn.Module):
    def __init__(self):
        super().__init__()
        self.experts=nn.ModuleList([nn.Sequential(nn.Linear(56,HIDDEN),nn.ReLU(),
            nn.Linear(HIDDEN,HIDDEN),nn.ReLU(),nn.Linear(HIDDEN,1)) for _ in base.EXPERTS])
    forward=base.Model.forward

def widen(model, raw, device):
    if len(raw)!=21180020:raise ValueError('Warm start requires the verified 56-feature v5 weights')
    values=np.frombuffer(raw,dtype='<f4').copy();offset=0
    ids=torch.arange(HIDDEN,device=device)%1000
    multiplicity=torch.bincount(ids,minlength=1000)[ids].float()
    with torch.no_grad():
        for expert in model.experts:
            old=[]
            for shape in [(1000,56),(1000,),(1000,1000),(1000,),(1,1000),(1,)]:
                size=math.prod(shape);old.append(torch.tensor(values[offset:offset+size].reshape(shape),device=device));offset+=size
            expert[0].weight.copy_(old[0][ids]);expert[0].bias.copy_(old[1][ids])
            expert[2].weight.copy_(old[2][ids][:,ids]/multiplicity[None,:]);expert[2].bias.copy_(old[3][ids])
            expert[4].weight.copy_(old[4][:,ids]/multiplicity[None,:]);expert[4].bias.copy_(old[5])
            # Break replicated-neuron symmetry so the extra hidden units can learn distinct behavior.
            for param in expert.parameters():param.add_(torch.randn_like(param)*1e-6)
    assert offset==len(values)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--logs',type=Path,default=Path(DEFAULT_LOGS))
    p.add_argument('--data',type=Path,default=Path(f'TrainingData/npc-v{VERSION}'))
    p.add_argument('--output',type=Path,default=Path(f'build/npc-policy-v{VERSION}/action-policy.json'))
    p.add_argument('--initial',type=Path,default=Path('src/npc/resources/data/bep_3davater_companion/ai/action-policy.bin'))
    p.add_argument('--epochs',type=int,default=16)
    a=p.parse_args();manifest=corpus(a.data,a.logs)
    torch.set_num_threads(4);torch.manual_seed(base.SEED);torch.use_deterministic_algorithms(True)
    device=torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    checkpoint=a.data/'initial-policy.bin'
    if not checkpoint.exists():checkpoint.write_bytes(a.initial.read_bytes())
    raw=checkpoint.read_bytes();model=Model().to(device);widen(model,raw,device)
    data,th=base.load(a.data/'train.jsonl.gz',device)
    val,vh=base.load(a.data/'validation.jsonl.gz',device)
    test,eh=base.load(a.data/'test.jsonl.gz',device)
    assert not th&vh and not th&eh and not vh&eh,'Cross-split candidate leakage'
    baseline=base.evaluate(model,val)
    print(json.dumps(dict(device=str(device),dimensions=DIMS,parameters=sum(p.numel() for p in model.parameters()),baseline=baseline,corpus=manifest['splits'])),flush=True)
    optimizer=torch.optim.AdamW(model.parameters(),lr=.000025,weight_decay=.00001)
    best=None;best_quality=-1.;best_epoch=0
    for epoch in range(a.epochs):
        model.train()
        for ids in torch.randperm(len(data[0]),device=device).split(256):
            x,y,mask,routes,observed=[v[ids] for v in data];pred=model(x,routes)
            weights=torch.where(observed,4.,1.)[:,None]*mask
            loss=((pred-y).square()*weights).sum()/weights.sum()
            optimizer.zero_grad(set_to_none=True);loss.backward();optimizer.step()
        model.eval();metrics=base.evaluate(model,val)
        print(json.dumps(dict(epoch=epoch+1,loss=loss.item(),**metrics)),flush=True)
        if metrics['illegalSelectionRate']==0 and metrics['urgentAccuracy']>=.999 and metrics['optimalAccuracy']>best_quality:
            best_quality=metrics['optimalAccuracy'];best=copy.deepcopy(model.state_dict());best_epoch=epoch+1
    if best is None:raise RuntimeError('No trained checkpoint passed legality and urgency gates')
    model.load_state_dict(best);model.eval()
    validation,independent=base.evaluate(model,val),base.evaluate(model,test)
    passed=all(m['exactOptimalAccuracy']>=.98 and m['urgentAccuracy']>=.999 and m['illegalSelectionRate']==0 for m in (validation,independent))
    model.cpu()
    weights=b''.join(t.detach().numpy().astype('<f4').tobytes() for e in model.experts for t in e.parameters())
    probes=[]
    with torch.no_grad():
        for i in range(5):
            x=[0.]*56;x[19]=x[20]=1.
            if i:x[{1:3,2:32,3:44,4:27}[i]]=1.
            probes.append(dict(input=x,expert=i,score=model.experts[i](torch.tensor(x)).item()))
    out=dict(version=VERSION,features=base.FEATURES,dimensions=DIMS,experts=base.EXPERTS,routing=f'context-v{VERSION}',
        activation='relu',format='float32-le',weights=a.output.with_suffix('.bin').name,
        weightBytes=len(weights),parameters=len(weights)//4,sha256=base.digest(weights),probes=probes,
        training=dict(seed=base.SEED,source=f'five-seed-real-outcomes-and-synthetic-curriculum-v{VERSION}',epochs=a.epochs,selectedEpoch=best_epoch,
            initialWeightsSha256=base.digest(raw),initialization=INITIALIZATION,
            corpus=manifest,baseline=baseline,validation=validation,independentTest=independent,passed=passed,
            observedTraining=base.evaluate(model.to(device),data),
            scope='Candidate ranking; held-out observed-action MSE does not measure counterfactual ranking or complete-mission success.'))
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.with_suffix('.bin').write_bytes(weights)
    a.output.write_text(json.dumps(out,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(passed=passed,validation=validation,independent=independent,sha256=out['sha256'])),flush=True)
    if not passed:raise SystemExit('Validation failed; candidate retained, do not ship')

if __name__=='__main__':main()
