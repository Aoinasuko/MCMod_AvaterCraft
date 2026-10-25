"""Train real float32 weights (~4 MiB); developer-only PyTorch, Java-only game inference."""
import argparse, hashlib, json, math, os, random
from pathlib import Path
os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
import numpy as np
import torch
from torch import nn
FEATURES = ['urgent', 'goal', 'ready', 'setup', 'verify', 'scan', 'approach', 'work', 'distance', 'failures', 'blocked', 'replan', 'idle', 'food_need', 'progress', 'repeat', 'target_missing', 'tool_missing', 'bag_free', 'candidate_reachable', 'candidate_loaded', 'unvisited', 'depth_gain', 'exploration_fraction', 'owner_distance', 'health', 'inventory_fraction', 'ore_target', 'diamond_target', 'ingredients_ready', 'chest_source', 'remaining_budget']
FEATURES += ['combat', 'target_health', 'hostile_distance', 'weapon_ready', 'ranged', 'ammunition', 'needs_aim', 'use_charge', 'smelt_ready', 'fuel_available', 'team_waiting', 'delegated', 'dimension_travel', 'portal_visible', 'structure_observed', 'danger']
SEED = 47925
DIMS = [48, 1000, 1000, 1]

def vector(kind, rng, stage='ready', urgent=False):
    d = dict.fromkeys(FEATURES, 0.0)
    d.update(urgent=float(urgent), goal=float(kind != 'idle'), ready=float(kind not in ('idle', 'replan')), setup=float(kind in ('equip', 'prepare')), verify=float(kind == 'verify'), scan=float(kind == 'scan'), approach=float(kind in ('approach', 'explore')), work=float(kind in ('execute', 'eat', 'excavate')), distance=rng.random(), failures=rng.random(), blocked=float(kind == 'replan'), replan=float(kind == 'replan'), idle=float(kind == 'idle'), food_need=rng.random(), progress=rng.random(), target_missing=float(stage in ('search', 'explore')), tool_missing=float(stage == 'prepare'), bag_free=rng.random(), candidate_reachable=1.0, candidate_loaded=1.0, unvisited=rng.random(), depth_gain=rng.choice([-1.0, 0.0, 1.0]) if stage == 'explore' else 0.0, exploration_fraction=rng.random(), owner_distance=rng.random(), health=rng.uniform(0.3, 1), inventory_fraction=rng.random(), ore_target=float(stage in ('search', 'explore', 'prepare')), diamond_target=float(rng.random() < 0.5), ingredients_ready=float(stage == 'prepare' and rng.random() < 0.5), chest_source=float(stage == 'prepare' and rng.random() < 0.5), remaining_budget=rng.random())
    d.update(combat=float(stage == 'combat'), target_health=rng.random(), hostile_distance=rng.random(), weapon_ready=float(rng.random() < .8), ranged=float(stage == 'ranged'), ammunition=float(rng.random() < .8), needs_aim=float(rng.random() < .5), use_charge=rng.random(), smelt_ready=float(stage == 'smelt'), fuel_available=float(rng.random() < .8), team_waiting=float(stage == 'team'), delegated=float(kind == 'prepare' and stage == 'team'), dimension_travel=float(stage == 'portal'), portal_visible=float(rng.random() < .8), structure_observed=float(stage == 'structure'), danger=rng.random())
    if stage == 'ranged' and kind == 'execute' and (not d['ammunition'] or d['needs_aim']): d['candidate_reachable'] = 0.0
    if stage == 'smelt' and kind == 'execute' and not d['fuel_available']: d['candidate_reachable'] = 0.0
    if stage == 'portal' and kind == 'execute' and not d['portal_visible']: d['candidate_loaded'] = 0.0
    return [d[n] for n in FEATURES]

def teacher(x):
    """Initial demonstration labels only; never executed by the Java game policy."""
    d = dict(zip(FEATURES, x))
    if d['urgent']:
        return 20 + d['food_need']
    if d['idle']:
        return -10.0
    if d['replan']:
        return -4.0
    if d['repeat'] > 0.5 or not d['candidate_reachable'] or (not d['candidate_loaded']):
        return -14.0
    if d['verify']:
        return 12.0
    v = 7 * d['work'] + 6 * d['setup'] + 5 * d['scan'] + 4 * d['approach']
    v += d['tool_missing'] * (3 * d['ingredients_ready'] + 1.5 * d['chest_source']) - 2 * d['work'] * d['target_missing']
    v += d['target_missing'] * d['ore_target'] * d['depth_gain'] * (1.2 + 1.8 * d['diamond_target'])
    v += 0.5 * d['unvisited'] + 0.3 * d['remaining_budget'] + 0.2 * d['progress']
    v -= 0.6 * d['distance'] + 0.8 * d['owner_distance'] + 0.5 * d['failures']
    v += 2.0*d['combat']*d['weapon_ready']*d['work'] + 2.0*d['needs_aim']*d['ranged']*d['setup'] + 2.0*d['smelt_ready']*d['fuel_available']*d['work'] + 2.0*d['team_waiting']*d['delegated'] - 2.5*d['danger']*(1-d['health'])*d['work']
    return v - d['target_missing'] * (0.3 * (1 - d['health']) + (1 - d['bag_free']) * 0.4)

def synthetic(seed, count):
    rng = random.Random(seed)
    out = []
    for _ in range(count):
        stage = rng.choice(['ready', 'verify', 'search', 'explore', 'prepare', 'blocked', 'transfer', 'combat', 'ranged', 'smelt', 'team', 'portal', 'structure'])
        c = [vector('idle', rng, stage), vector('replan', rng, stage)]
        for _ in range(4):
            kind = {'combat': 'execute', 'ranged': rng.choice(['equip', 'execute']), 'smelt': 'execute', 'team': rng.choice(['prepare', 'verify']), 'portal': rng.choice(['approach','execute']), 'structure': rng.choice(['scan','verify']), 'ready': 'execute', 'verify': 'verify', 'search': 'scan', 'prepare': 'prepare', 'blocked': 'explore', 'transfer': rng.choice(['approach', 'execute']), 'explore': rng.choice(['explore', 'excavate'])}[stage]
            x = vector(kind, rng, stage)
            if stage == 'blocked':
                x[19] = 0.0
                x[31] = 0.0
            c.append(x)
        if rng.random() < 0.25:
            c[-1] = vector('eat', rng, stage, True)
        rng.shuffle(c)
        out.append(dict(modelVersion=3, candidates=c, chosen=max(range(6), key=lambda i: teacher(c[i]))))
    return out

def validate(examples):
    for e in examples:
        c = e['candidates']
        if e.get('modelVersion') != 3 or not 2 <= len(c) <= 32 or type(e['chosen']) is not int or (not 0 <= e['chosen'] < len(c)):
            raise ValueError('Expected version 3 candidate labels')
        for x in c:
            if len(x) != 48 or any((type(n) not in (float, int) or not math.isfinite(n) or abs(n) > 1 for n in x)):
                raise ValueError('Expected 48 bounded finite features')

def tensors(examples, device):
    n = max((len(e['candidates']) for e in examples))
    x = np.zeros((len(examples), n, 48), np.float32)
    mask = np.zeros((len(examples), n), bool)
    scores = np.zeros((len(examples), n), np.float32)
    for (i, e) in enumerate(examples):
        k = len(e['candidates'])
        x[i, :k] = e['candidates']
        mask[i, :k] = True
        scores[i, :k] = [teacher(v) / 4 for v in e['candidates']]
    return (torch.from_numpy(x).to(device), torch.from_numpy(mask).to(device), torch.tensor([e['chosen'] for e in examples], device=device), torch.from_numpy(scores).to(device))

def evaluate(model, data, reviewed=False):
    (x, mask, y, target) = data
    with torch.no_grad():
        scores = torch.cat([model(p).squeeze(-1) for p in x.split(1024)]).masked_fill(~mask, -1000000000.0)
        chosen = scores.argmax(-1)
        urgent = x[:, :, 0].gather(1, y[:, None]).squeeze(1) > 0.5
        exact = (chosen == y).float().mean().item()
        valid = (target.gather(1, chosen[:, None]).squeeze(1) >= target.masked_fill(~mask, -1000000000.0).max(-1).values - 1e-06).float().mean().item()
        return dict(accuracy=exact if reviewed else valid, rawTop1Accuracy=exact, tiesAccepted=not reviewed, urgentAccuracy=(chosen[urgent] == y[urgent]).float().mean().item() if urgent.any() else 1.0)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data', type=Path)
    p.add_argument('--validation', type=Path)
    p.add_argument('--output', type=Path, default=Path('src/npc/resources/data/bep_3davater_companion/ai/action-policy.json'))
    p.add_argument('--epochs', type=int, default=100)
    p.add_argument('--device', default='cuda' if torch.cuda.is_available() else 'cpu')
    args = p.parse_args()
    torch.set_num_threads(4)
    torch.manual_seed(SEED)
    torch.use_deterministic_algorithms(True)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    if args.data:
        if not args.validation:
            p.error('Reviewed training requires a separate --validation file')
        train = [json.loads(l) for l in args.data.read_text(encoding='utf-8').splitlines() if l.strip()]
        val = [json.loads(l) for l in args.validation.read_text(encoding='utf-8').splitlines() if l.strip()]
        if any((e.get('reviewed') is not True for e in train + val)):
            raise ValueError('Every external example must be explicitly reviewed')
        source = 'reviewed-jsonl'
    else:
        train = synthetic(SEED, 96000)
        val = synthetic(SEED + 1, 6000)
        source = 'synthetic-survival-combat-smelting-team-exploration-demonstrations'
    if not train or not val:
        raise ValueError('Nonempty training and validation required')
    validate(train)
    validate(val)

    def fp(e):
        return hashlib.sha256(json.dumps(dict(candidates=e['candidates'], chosen=e['chosen']), sort_keys=True).encode()).hexdigest()
    hashes = {fp(e) for e in train}
    if any((fp(e) in hashes for e in val)):
        raise ValueError('Duplicate training/validation examples')
    device = torch.device(args.device)
    model = nn.Sequential(nn.Linear(48, 1000), nn.ReLU(), nn.Linear(1000, 1000), nn.ReLU(), nn.Linear(1000, 1)).to(device)
    data = tensors(train, device)
    validation = tensors(val, device)
    opt = torch.optim.AdamW(model.parameters(), lr=0.001, weight_decay=1e-05)
    print(json.dumps(dict(device=str(device), trainExamples=len(train), validationExamples=len(val), parameters=sum((v.numel() for v in model.parameters())))), flush=True)
    for epoch in range(args.epochs):
        for group in opt.param_groups:
            group['lr'] = 0.0005 * (0.5 ** (epoch // 30))
        for ids in torch.randperm(len(train), device=device).split(512):
            (x, mask, y, target) = (v[ids] for v in data)
            pred = model(x).squeeze(-1)
            best = target.masked_fill(~mask, -1000000000.0).max(-1, keepdim=True).values
            optimal = mask & (target >= best - 1e-06)
            ce = nn.functional.cross_entropy(pred.masked_fill(~mask, -1000000000.0), y) if args.data else (pred.masked_fill(~mask, -1000000000.0).logsumexp(-1) - pred.masked_fill(~optimal, -1000000000.0).logsumexp(-1)).mean()
            loss = ce if args.data else ((pred - target).square() * mask).sum() / mask.sum() + 0.01 * ce
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
        if (epoch + 1) % 5 == 0:
            print(json.dumps(dict(epoch=epoch + 1, **evaluate(model, validation, bool(args.data)))), flush=True)
    metrics = evaluate(model, validation, bool(args.data))
    if metrics['accuracy'] < 0.98 or metrics['urgentAccuracy'] < 0.999:
        raise ValueError(f'Validation gate failed: {metrics}')
    test = evaluate(model, tensors(synthetic(SEED + 2, 4000), device))
    if test['urgentAccuracy'] < 0.999:
        raise ValueError('Independent urgency gate failed')
    cpu = model.to('cpu').eval()
    weights = b''.join((t.detach().numpy().astype('<f4').tobytes() for t in cpu.parameters()))
    assert len(weights) == 4204004
    rng = random.Random(SEED + 3)
    probes = []
    with torch.no_grad():
        for _ in range(8):
            x = vector(rng.choice(['execute', 'explore', 'prepare', 'verify', 'eat']), rng, rng.choice(['ready', 'explore', 'prepare']))
            probes.append(dict(input=x, score=cpu(torch.tensor(x, dtype=torch.float32)).item()))
    training = dict(seed=SEED, source=source, trainExamples=len(train), validationExamples=len(val), validation=metrics, independentSyntheticTest=test, epochs=args.epochs, dataSha256=hashlib.sha256(''.join(sorted(hashes)).encode()).hexdigest(), scope='Synthetic candidate ranking; not unrestricted Minecraft task success.')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    wp = args.output.with_suffix('.bin')
    manifest = dict(version=3, features=FEATURES, dimensions=DIMS, activation='relu', format='float32-le', weights=wp.name, weightBytes=len(weights), parameters=len(weights) // 4, sha256=hashlib.sha256(weights).hexdigest(), probes=probes, training=training)
    wp.write_bytes(weights)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(file=str(args.output), weightBytes=len(weights), **training)), flush=True)
if __name__ == '__main__':
    main()
