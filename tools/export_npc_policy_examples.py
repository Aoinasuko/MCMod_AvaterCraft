"""Extract action-only review proposals locally, excluding names, chat and owner identifiers."""
import argparse
import hashlib
import json
from pathlib import Path

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('journal', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    examples = []
    for file in sorted(args.journal.rglob('*.jsonl')):
        if file.is_symlink():
            continue
        with file.open(encoding='utf-8') as stream:
            for line in stream:
                entry = json.loads(line)
                if entry.get('kind') != 'local_policy':
                    continue
                d = entry['details']
                if d.get('modelVersion') != 4 or not d.get('actions') or len(d['candidates']) < 2:
                    continue
                actions = json.loads(json.dumps(d['actions']))
                for action in actions:
                    if 'name' in action.get('args', {}):
                        action['args']['name'] = '<location>'
                    for private in ('text', 'plan_json'):
                        if private in action.get('args', {}):
                            action['args'][private] = '<omitted>'
                examples.append(dict(modelVersion=4, exampleId=hashlib.sha256(entry['id'].encode()).hexdigest()[:16], candidates=d['candidates'], actions=actions, chosen=d['chosen'], reviewed=False))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(''.join((json.dumps(e, ensure_ascii=False) + '\n' for e in examples)), encoding='utf-8')
    print(json.dumps(dict(examples=len(examples), output=str(args.output), reviewed=False)))
if __name__ == '__main__':
    main()
