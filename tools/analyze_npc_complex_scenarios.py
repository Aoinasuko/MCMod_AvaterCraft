"""Aggregate synthetic complex GameTest outcomes and their per-NPC execution journals."""
from pathlib import Path
import collections
import json
import hashlib
import sys

ROOT = Path(__file__).resolve().parent.parent
CONTROL = '--controls' in sys.argv
LIVE = '--live' in sys.argv
RESULTS = ROOT / ('build/npc-complex-live-results' if LIVE else 'build/npc-complex-controls' if CONTROL else 'build/npc-complex-results')
JOURNALS = ROOT / 'run-npc-complex-qa/gametestserver/gametestworld/bep_3davater_companion/journal'
EVIDENCE = ROOT / ('build/npc-complex-live-evidence' if LIVE else 'build/npc-complex-control-evidence' if CONTROL else 'build/npc-complex-evidence')
EVIDENCE.mkdir(parents=True, exist_ok=True)
catalog = json.loads((ROOT / 'src/test/resources/npc-complex-scenarios.json').read_text(encoding='utf-8'))
summaries = []
selectors = collections.Counter()
for spec in catalog:
    result_path = RESULTS / f'{spec["id"]}-{spec["key"]}.json'
    if not result_path.exists():
        continue
    result = json.loads(result_path.read_text(encoding='utf-8'))
    rows = []
    for journal in sorted((JOURNALS / result['owner'] / result['actor']).glob('*.jsonl')):
        rows.extend(json.loads(line) for line in journal.read_text(encoding='utf-8').splitlines())
    if not rows and (EVIDENCE / f'{spec["id"]}.jsonl').exists():
        rows = [json.loads(line) for line in (EVIDENCE / f'{spec["id"]}.jsonl').read_text(encoding='utf-8').splitlines()]
        if any(row.get('actor') != result['actor'] for row in rows):
            rows = [] # Never attribute evidence from an earlier fixture run to this actor.
    (EVIDENCE / f'{spec["id"]}.jsonl').write_text(''.join(json.dumps(e, ensure_ascii=False) + '\n' for e in rows), encoding='utf-8')
    policy = [r['details'] for r in rows if r.get('kind') == 'local_policy']
    counts = collections.Counter(p.get('selector', 'unknown') for p in policy)
    selectors.update(counts)
    failures = []
    for r in rows:
        if r.get('kind') != 'tool_result' or r.get('details', {}).get('ok', True):
            continue
        details = r['details']
        if details.get('error', '') in {'Acquisition stopped', 'Stopped', 'Test done', 'Action was cancelled'}:
            continue
        failures.append({'tool': r['text'], 'result': details})
    summary = {**spec, 'actor': result['actor'], 'status': result['status'], 'ticks': result['ticks'], 'error': result['error'],
               'liveAiExecutedInWorld': result.get('liveAiExecutedInWorld', False),
               'generatedPlans': result.get('generatedPlans', []), 'planningErrors': result.get('planningErrors', []),
               'livePlanningRequests': result.get('livePlanningRequests', 0), 'wallSeconds': result.get('wallSeconds'),
               'maxGuardPathDistance': result.get('maxGuardPathDistance'), 'maxDistanceFromStart': result.get('maxDistanceFromStart'),
               'oraclePlanningRequests': result['oraclePlanningRequests'],
               'selectors': dict(counts), 'toolCounts': dict(collections.Counter(r.get('text') for r in rows if r.get('kind') == 'tool_start')),
               'localBlocked': [r['details'] for r in rows if r.get('kind') == 'local_blocked'],
               'toolFailures': failures, 'referencePlans': result['referencePlans'],
               'inventory': result['observation'].get('item_counts'), 'hostiles': result.get('hostiles'),
               'evidence': (EVIDENCE / f'{spec["id"]}.jsonl').relative_to(ROOT).as_posix(),
               'portalCooldown': result.get('portalCooldown'), 'currentDimension': result.get('currentDimension'), 'blockAtFeet': result.get('blockAtFeet'), 'finalState': result['mindState']}
    summaries.append(summary)
policy_path = ROOT / 'src/npc/resources/data/bep_3davater_companion/ai/action-policy.bin'
output = {'scope': 'Live OAuth-generated plans executed in isolated Minecraft fixtures' if LIVE else 'Isolated physical control/diagnostic variants' if CONTROL else '20 isolated reference-plan physical scenarios, not live AI end-to-end',
          'results': dict(collections.Counter(s['status'] for s in summaries)),
          'selectors': dict(selectors), 'localModelBytes': policy_path.stat().st_size,
          'localModelSha256': hashlib.sha256(policy_path.read_bytes()).hexdigest(), 'scenarios': summaries}
(ROOT / ('build/npc-complex-live-analysis.json' if LIVE else 'build/npc-complex-control-analysis.json' if CONTROL else 'build/npc-complex-analysis.json')).write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in output.items() if k != 'scenarios'}, ensure_ascii=False, indent=2))
