"""Outcome-supervised runtime audit plus a held-out navigation curriculum; no invented live rewards."""
import argparse
import collections
import copy
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import random

os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
import numpy as np
import torch
from torch import nn
from train_npc_policy_v3 import FEATURES as OLD_FEATURES
from generate_npc_curriculum import score as old_score
from train_npc_policy_v4 import EXPERTS, route

FEATURES = OLD_FEATURES + ['height_delta', 'path_known', 'edit_fraction', 'target_failed',
                           'station_distance', 'team_stalled', 'supply_shortfall', 'travel_cost']
DIMS = [56, 1000, 1000, 1]
SEED = 20261026
SLOTS = 9

def digest(data):
    return hashlib.sha256(data).hexdigest()

def pad(example):
    while len(example['candidates']) < SLOTS:
        x = [0.] * 56
        x[12] = x[19] = x[20] = 1.
        example['candidates'].append(x)
        example['targets'].append(-3.5)
        example['mask'].append(True)
    return example

def legacy(split, limit):
    with gzip.open(Path('TrainingData/npc-v4') / (split + '.jsonl.gz'), 'rt', encoding='utf-8') as f:
        for i, line in enumerate(f):
            if i >= limit: break
            e = json.loads(line)
            yield pad(dict(source='synthetic-v4', group=split, candidates=[v + [0.] * 8 for v in e['candidates']],
                           targets=[old_score(v) / 4 for v in e['candidates']], mask=[True] * len(e['candidates'])))

def navigation(split, count):
    rng = random.Random(SEED + {'train': 0, 'validation': 1, 'test': 2}[split])
    for _ in range(count):
        candidates, targets = [], []
        for j in range(6):
            x = [0.] * 56
            x[1] = x[2] = x[3] = x[19] = x[20] = x[29] = x[31] = 1.
            x[8] = rng.random()
            x[48] = rng.uniform(-1, 1)
            x[49] = float(rng.random() < .5)
            if x[49] == 0: x[19] = .5
            x[50] = rng.random()
            x[51] = float(rng.random() < .15)
            x[55] = min(1., x[8] + abs(x[48]) * .3 + x[50] * .2)
            if rng.random() < .08: x[19] = 0.
            if rng.random() < .04: x[20] = 0.
            value = old_score(x[:48]) / 4
            if x[19] >= .5 and x[20] >= .5:
                value -= .55 * abs(x[48]) + .5 * x[55] + .25 * x[50] + 2. * x[51]
                value += .15 * x[49]
            candidates.append(x); targets.append(value)
        yield pad(dict(source='synthetic-navigation-v5', group=split, candidates=candidates,
                       targets=targets, mask=[True] * 6))

def runtime(folder):
    """Only the executed candidate gets an outcome label; unchosen outcomes remain unknown."""
    for path in sorted(folder.glob('*.jsonl')):
        raw = path.read_bytes()
        records = [json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]
        results = {r['details'].get('actionId'): r for r in records if r['kind'] == 'tool_result'}
        origin = None
        for index, record in enumerate(records):
            if record['kind'] == 'tool_start': origin = record['details'].get('actorPosition', origin)
            if record['kind'] != 'local_policy': continue
            d = record['details']; selected = d['actions'][d['chosen']]
            if not selected['tool'] or selected['tool'] in {'acquire', 'await_teammate', 'delegate'}: continue
            start = None
            for following in records[index + 1:]:
                if following['kind'] == 'local_policy': break
                if following['kind'] == 'tool_start' and following['text'] == selected['tool']:
                    start = following; break
            if start is None: continue
            result = results.get(start['details'].get('actionId'))
            if result is None or any(word in result['details'].get('error', '').lower()
                                     for word in ('cancel', 'disappeared', 'offline')): continue
            origin = start['details'].get('actorPosition', origin)
            if origin is None: continue
            candidates = []
            for old, action in zip(d['candidates'], d['actions']):
                x = old[:48] + [0.] * 8
                args = action['args']
                if all(a in args for a in ('x', 'y', 'z')):
                    dist = math.dist(origin, [args[a] for a in ('x', 'y', 'z')])
                    x[8] = min(1., dist / 16)
                    x[48] = max(-1., min(1., (args['y'] - origin[1]) / 20))
                    x[49] = float(action['tool'] in ('dig', 'place') and dist <= 4)
                    x[19] = 1. if x[49] else .5
                    x[50] = min(1., abs(args['y'] - origin[1]) / 32) if action['tool'] == 'work_route' else 0.
                    x[55] = min(1., dist / 48 + abs(args['y'] - origin[1]) / 40)
                candidates.append(x)
            chosen = d['chosen']
            targets = [0.] * len(candidates); mask = [False] * len(candidates)
            mask[chosen] = True
            targets[chosen] = old_score(candidates[chosen][:48]) / 4 if result['details'].get('ok') else -2.
            yield pad(dict(source='observed-runtime', group='neo-20261026-train-only',
                           sourceSha256=digest(raw), policyLine=index + 1,
                           actionId=start['details']['actionId'], outcome=result['details'].get('ok', False),
                           outcomeCode=result['details'].get('code', ''), candidates=candidates,
                           targets=targets, mask=mask))

def corpus(folder, logs):
    folder.mkdir(parents=True, exist_ok=True)
    manifest = dict(seed=SEED, features=FEATURES, splits={},
                    labelPolicy='Only executed runtime actions have observed labels; unchosen alternatives are masked. '
                    'Navigation curriculum is synthetic. All original Neo runs stay in training; '
                    'independent gameplay is never used as a synthetic success label.')
    archive=folder/'observed-actions.jsonl.gz'
    if logs.is_dir() and any(logs.glob('*.jsonl')):
        observed = list(runtime(logs))
        archive.write_bytes(gzip.compress(('\n'.join(json.dumps(e,separators=(',',':')) for e in observed)+'\n').encode(),mtime=0))
    elif archive.exists():
        with gzip.open(archive,'rt',encoding='utf-8') as f: observed=[json.loads(line) for line in f]
    else:
        raise ValueError('Provide runtime JSONL with --logs, or retain the curated observed-actions.jsonl.gz')
    manifest['runtimeOutcomes'] = dict(collections.Counter(str(e['outcome']) for e in observed))
    for split, base, geo in [('train', 48000, 12000), ('validation', 6000, 1500), ('test', 6000, 1500)]:
        examples = list(legacy(split, base)) + list(navigation(split, geo))
        if split == 'train': examples += observed
        rng = random.Random(SEED + base); rng.shuffle(examples)
        data = '\n'.join(json.dumps(e, separators=(',', ':')) for e in examples).encode() + b'\n'
        payload = gzip.compress(data, mtime=0)
        (folder / (split + '.jsonl.gz')).write_bytes(payload)
        manifest['splits'][split] = dict(examples=len(examples), sha256=digest(payload),
                                        sources=dict(collections.Counter(e['source'] for e in examples)))
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return manifest

class Model(nn.Module):
    def __init__(self):
        super().__init__()
        self.experts = nn.ModuleList([nn.Sequential(nn.Linear(56, 1000), nn.ReLU(),
                            nn.Linear(1000, 1000), nn.ReLU(), nn.Linear(1000, 1)) for _ in EXPERTS])

    def forward(self, x, routes):
        shape = x.shape[:-1]; x = x.reshape(-1, 56); routes = routes.reshape(-1)
        result = torch.zeros(len(x), device=x.device)
        for i, expert in enumerate(self.experts):
            mask = routes == i
            if mask.any(): result[mask] = expert(x[mask]).squeeze(-1)
        return result.reshape(shape)

def load(path, device):
    with gzip.open(path, 'rt', encoding='utf-8') as f: rows = [json.loads(line) for line in f]
    x = np.array([e['candidates'] for e in rows], dtype=np.float32)
    y = np.array([e['targets'] for e in rows], dtype=np.float32)
    mask = np.array([e['mask'] for e in rows], dtype=np.bool_)
    routes = np.array([[route(v) for v in e['candidates']] for e in rows], dtype=np.int64)
    observed = np.array([e['source'] == 'observed-runtime' for e in rows], dtype=np.bool_)
    fingerprints = {digest(v.tobytes()) for v in x}
    return [torch.tensor(v, device=device) for v in (x, y, mask, routes, observed)], fingerprints

def evaluate(model, data):
    x, target, mask, routes, observed = data
    with torch.no_grad():
        pred = torch.cat([model(x[i:i+256], routes[i:i+256]) for i in range(0, len(x), 256)])
        chosen = pred.argmax(-1)
        full = mask.all(-1)
        optimal = target.gather(1, chosen[:,None]).squeeze(1) >= target.max(-1).values - .04
        regret=(target.max(-1).values-target.gather(1,chosen[:,None]).squeeze(1)).clamp_min(0)
        selected = x[torch.arange(len(x), device=x.device), chosen]
        illegal = (selected[:,19] < .5) | (selected[:,20] < .5) | (selected[:,15] > .5)
        urgent = (x[:,:,0] > .5) & (x[:,:,19] > .5) & (x[:,:,20] > .5)
        uc = urgent.any(-1) & full
        return dict(optimalAccuracy=optimal[full].float().mean().item(),
                    scoreTolerance=.04, exactOptimalAccuracy=(regret[full]<=1e-6).float().mean().item(),
                    meanRegret=regret[full].mean().item(), p95Regret=regret[full].quantile(.95).item(),
                    urgentAccuracy=optimal[uc].float().mean().item() if uc.any() else 1.,
                    illegalSelectionRate=illegal[full].float().mean().item(), examples=int(full.sum()),
                    observedMse=((pred - target).square() * mask)[observed].sum().item() / max(1, int(observed.sum())))

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--logs', type=Path, default=Path('build/npc-neo-investigation-20261026'))
    p.add_argument('--data', type=Path, default=Path('TrainingData/npc-v5'))
    p.add_argument('--output', type=Path, default=Path('build/npc-policy-v5/action-policy.json'))
    p.add_argument('--initial', type=Path, default=Path('src/npc/resources/data/bep_3davater_companion/ai/action-policy.bin'))
    p.add_argument('--epochs', type=int, default=16)
    a = p.parse_args(); manifest = corpus(a.data, a.logs)
    torch.set_num_threads(4); torch.manual_seed(SEED); torch.use_deterministic_algorithms(True)
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    model = Model().to(device)
    checkpoint = a.data / 'initial-policy.bin'
    if not checkpoint.exists():
        raw = a.initial.read_bytes()
        if len(raw) != 21020020: raise ValueError('First run needs the original 48-feature v4 weights via --initial')
        checkpoint.write_bytes(raw)
    raw = checkpoint.read_bytes(); offset = 0
    values = np.frombuffer(raw, dtype='<f4').copy()
    with torch.no_grad():
        for expert in model.experts:
            for j, param in enumerate(expert.parameters()):
                shape = (1000, 48) if j == 0 else tuple(param.shape)
                count = math.prod(shape); old = torch.tensor(values[offset:offset+count].reshape(shape), device=device)
                if j == 0: param.zero_(); param[:,:48].copy_(old)
                else: param.copy_(old)
                offset += count
    assert offset == len(values)
    data, th = load(a.data/'train.jsonl.gz', device)
    val, vh = load(a.data/'validation.jsonl.gz', device)
    test, eh = load(a.data/'test.jsonl.gz', device)
    assert not th & vh and not th & eh and not vh & eh
    baseline = evaluate(model, val)
    print(json.dumps(dict(device=str(device), baseline=baseline, corpus=manifest['splits'])), flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=.00005, weight_decay=.00001)
    best, best_metric = None, -1.
    for epoch in range(a.epochs):
        model.train()
        for ids in torch.randperm(len(data[0]), device=device).split(256):
            x, target, mask, routes, observed = [v[ids] for v in data]
            pred = model(x, routes)
            weights = torch.where(observed, 8., 1.)[:,None] * mask
            loss = ((pred-target).square() * weights).sum() / weights.sum()
            opt.zero_grad(set_to_none=True); loss.backward(); opt.step()
        model.eval(); metrics = evaluate(model, val)
        print(json.dumps(dict(epoch=epoch+1, loss=loss.item(), **metrics)), flush=True)
        quality = metrics['optimalAccuracy']
        if quality > best_metric and metrics['illegalSelectionRate'] == 0 and metrics['urgentAccuracy'] >= .999:
            best_metric = quality; best = copy.deepcopy(model.state_dict())
    if best is None: raise RuntimeError('No checkpoint passed legality and urgency gates')
    model.load_state_dict(best); model.eval()
    validation, independent = evaluate(model, val), evaluate(model, test)
    passed = all(v['exactOptimalAccuracy'] >= .98 and v['urgentAccuracy'] >= .999 and v['illegalSelectionRate'] == 0
                 for v in (validation, independent))
    model.cpu()
    weights = b''.join(t.detach().numpy().astype('<f4').tobytes() for expert in model.experts for t in expert.parameters())
    probes = []
    with torch.no_grad():
        for i in range(5):
            v = [0.] * 56; v[19] = v[20] = 1.
            if i: v[{1:3,2:32,3:44,4:27}[i]] = 1.
            probes.append(dict(input=v, expert=i, score=model.experts[i](torch.tensor(v)).item()))
    out = dict(version=5, features=FEATURES, dimensions=DIMS, experts=EXPERTS, routing='context-v5',
               activation='relu', format='float32-le', weights=a.output.with_suffix('.bin').name,
               weightBytes=len(weights), parameters=len(weights)//4, sha256=digest(weights), probes=probes,
               training=dict(seed=SEED, source='synthetic-curriculum-and-observed-runtime-v5', epochs=a.epochs,
                             initialWeightsSha256=digest(raw), corpus=manifest, baseline=baseline,
                             validation=validation, independentTest=independent, passed=passed,
                             observedTraining=evaluate(model.to(device), data),
                             scope='Candidate ranking and observed action outcomes; not mission completion rate.'))
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.with_suffix('.bin').write_bytes(weights)
    a.output.write_text(json.dumps(out, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(passed=passed, validation=validation, independent=independent, sha256=out['sha256'])), flush=True)
    if not passed: raise SystemExit('Candidate retained for inspection; validation gate failed, do not ship')

if __name__ == '__main__': main()
