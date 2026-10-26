"""Archive finished natural campaign worlds without altering their raw journals or outcomes."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parent.parent
SEEDS=[61279,61280,61281,61282,61283]

def main():
    target=ROOT/'build/npc-campaign-archive';target.mkdir(parents=True,exist_ok=True)
    manifest=[]
    for seed in SEEDS:
        result=ROOT/f'build/npc-campaign-results/{seed}.json'
        outcome=json.loads(result.read_text(encoding='utf-8'))
        if outcome['state']=='running':raise RuntimeError(f'World {seed} is still running; archive only after server exit')
        dest=target/str(seed);dest.mkdir(parents=True,exist_ok=True)
        shutil.copy2(result,dest/'result.json')
        journals=ROOT/f'run-npc-natural-qa-{seed}/gametestserver/gametestworld/bep_3davater_companion/journal'
        files=[]
        for path in sorted(journals.rglob('*.jsonl')):
            out=dest/'journal'/path.relative_to(journals);out.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(path,out)
            files.append(dict(path=str(out.relative_to(target)),bytes=out.stat().st_size,
                sha256=hashlib.sha256(out.read_bytes()).hexdigest()))
        if not files:raise RuntimeError(f'World {seed} has no journals')
        shutil.copy2(ROOT/f'build/npc-campaign-{seed}.log',dest/'server.log')
        manifest.append(dict(seed=seed,state=outcome['state'],outcomes=[dict(mission=o['mission'],state=o['state'],reason=o['reason'])
            for o in outcome['outcomes']],journals=files,
            skipped=outcome['remainingMissionsNotAttempted']))
    (target/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(manifest,ensure_ascii=True))

if __name__=='__main__':main()
