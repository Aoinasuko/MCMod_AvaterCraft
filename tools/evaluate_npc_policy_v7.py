"""Paired v6/v7 scores on the exact same world-held-out candidate corpus."""
import json
from pathlib import Path
import torch
import train_npc_policy_v7 as v7

torch.set_num_threads(4)
torch.use_deterministic_algorithms(True)
device=torch.device('cuda' if torch.cuda.is_available() else 'cpu')
datasets={s:v7.trainer.base.load(Path('TrainingData/npc-v7')/(s+'.jsonl.gz'),device)[0] for s in ('validation','test')}
result={}
for name,path in [('v6',Path('TrainingData/npc-v7/initial-policy.bin')),('v7',Path('build/npc-policy-v7/action-policy.bin'))]:
    model=v7.trainer.Model().to(device);raw=path.read_bytes();v7.restore(model,raw,device);model.eval()
    result[name]=dict(weightsSha256=v7.trainer.base.digest(raw),**{s:v7.trainer.base.evaluate(model,d) for s,d in datasets.items()})
Path('build/npc-policy-v7/paired-evaluation.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result))
