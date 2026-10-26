"""Retrain the 40-MiB policy on the twenty recorded natural missions; keep worlds held out."""
import collections
import gzip
import json
import random
import sys
from pathlib import Path
import numpy as np
import torch
import train_npc_policy_v6 as trainer

def corpus(folder,logs):
    folder.mkdir(parents=True,exist_ok=True);observed=[];sources=[]
    for seed in trainer.SEEDS:
        root=logs/str(seed)
        for case in sorted(root.iterdir()):
            if not case.is_dir() or not (case/'journal').is_dir():continue
            for e in trainer.runtime(case/'journal',seed):
                e['group']=f'world-{seed}-{case.name}';e['missionName']=case.name;observed.append(e)
            for p in sorted((case/'journal').rglob('*.jsonl')):
                sources.append(dict(seed=seed,mission=case.name,path=str(p.relative_to(logs)),sha256=trainer.base.digest(p.read_bytes())))
    if len(sources)<20 or not observed:raise ValueError('All twenty real mission journals are required')
    (folder/'observed-actions.jsonl.gz').write_bytes(gzip.compress(('\n'.join(json.dumps(e,separators=(',',':')) for e in observed)+'\n').encode(),mtime=0))
    # Include the earlier audit without changing the original world partition.
    with gzip.open('TrainingData/npc-v6/observed-actions.jsonl.gz','rt',encoding='utf-8') as f:earlier=[json.loads(l) for l in f]
    with gzip.open('TrainingData/npc-v5/observed-actions.jsonl.gz','rt',encoding='utf-8') as f:neo=[json.loads(l) for l in f]
    for e in neo:e['worldSeed']=61279;e['group']='neo-audit-train-only'
    groups={s:[] for s in ('train','validation','test')}
    for e in observed+earlier+neo:groups[trainer.SPLIT[e['worldSeed']]].append(e)
    seen=set();removed={}
    for split in ('test','validation','train'):
        keep=[];removed[split]=0
        for e in groups[split]:
            key=trainer.base.digest(np.asarray(e['candidates'],dtype='<f4').tobytes())
            if key in seen:removed[split]+=1
            else:keep.append(e);seen.add(key)
        groups[split]=keep
    manifest=dict(seed=trainer.base.SEED,features=trainer.base.FEATURES,worldSplits=trainer.SPLIT,rawJournals=sources,
        observedActions=len(observed),observedOutcomes=dict(collections.Counter(str(e['outcome']) for e in observed)),
        deduplicated=removed,splits={},
        labelPolicy='Actual chosen-operation outcomes only. Unexecuted alternatives and observed padding are masked. '
        'Failed missions do not turn every successful operation into a negative label. Worlds remain separated. '
        'Gameplay revalidation after training is a separate experiment, not synthetic accuracy.')
    for split,n,nav in [('train',48000,12000),('validation',6000,1500),('test',6000,1500)]:
        rows=list(trainer.base.legacy(split,n))+list(trainer.base.navigation(split,nav))+groups[split]
        random.Random(trainer.base.SEED+n).shuffle(rows)
        payload=gzip.compress(('\n'.join(json.dumps(e,separators=(',',':')) for e in rows)+'\n').encode(),mtime=0)
        (folder/(split+'.jsonl.gz')).write_bytes(payload)
        manifest['splits'][split]=dict(examples=len(rows),sha256=trainer.base.digest(payload),
            sources=dict(collections.Counter(e['source'] for e in rows)),observedOutcomes=dict(collections.Counter(str(e['outcome']) for e in groups[split])))
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    return manifest

def restore(model,raw,device):
    if len(raw)!=41945660:raise ValueError('v7 starts from the verified full 40-MiB v6 network')
    if trainer.base.digest(raw)!='4531a762c01bb2c265c2e0b64295e1ec4ecaf5bfb63f1d4e1e031e2d89090877':
        raise ValueError('Provide the archived v6 weights with --initial; the installed v7 weights are not the v6 warm start')
    values=np.frombuffer(raw,dtype='<f4').copy();offset=0
    with torch.no_grad():
        for expert in model.experts:
            for p in expert.parameters():
                size=p.numel();p.copy_(torch.tensor(values[offset:offset+size].reshape(p.shape),device=device));offset+=size
    assert offset==len(values)

def main():
    trainer.VERSION=7;trainer.corpus=corpus;trainer.widen=restore
    trainer.INITIALIZATION='Restore all v6 FP32 parameters at unchanged 40-MiB dimensions; train every layer'
    trainer.DEFAULT_LOGS='build/npc-targeted-archive/20261026-targeted-v6'
    trainer.main()

if __name__=='__main__':main()
