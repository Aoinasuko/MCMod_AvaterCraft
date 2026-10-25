"""Reproducible bilingual instruction/state curriculum. Synthetic, not gameplay recordings."""
import argparse
import gzip
import hashlib
import json
import io
from pathlib import Path
import random
from train_npc_policy_v3 import FEATURES, vector, teacher as old_teacher

SEED = 61279
STAGES = ['ready', 'verify', 'search', 'explore', 'prepare', 'blocked', 'transfer',
          'combat', 'ranged', 'smelt', 'team', 'portal', 'structure', 'farm', 'immature', 'missing_seed']

def step(action, target='', block='', count=1, **extra):
    return dict(action=action, target=target, block=block, count=count, entityId=-1,
                slot=-1, hand='main', seconds=10, position=[], location='', arguments='', **extra)

def atomic(name, args):
    s = step('tool', 'avatarcraftnpc:' + name)
    s['arguments'] = json.dumps(args, separators=(',', ':'))
    return s

def catalog():
    out = []
    def add(group, ja, en, steps, stage, aliases=(), ambiguous=False, blocked=''):
        out.append(dict(id=f'{group}-{len(out):03}', category=group, ja=ja, en=en,
                        steps=steps, stage=stage, aliases=list(aliases), ambiguous=ambiguous, blocked=blocked))
    add('logging', '原木', 'logs', [step('collect', '#minecraft:logs', '#minecraft:logs')], 'search', ['木', '木材', 'wood', 'timber'], True)
    for wood, ja in [('oak','オーク'),('spruce','トウヒ'),('birch','シラカバ'),('jungle','ジャングル'),
                     ('acacia','アカシア'),('dark_oak','ダークオーク'),('mangrove','マングローブ'),
                     ('cherry','サクラ'),('pale_oak','ペールオーク'),('crimson','真紅'),('warped','歪んだ')]:
        item = 'minecraft:' + wood + ('_stem' if wood in ('crimson', 'warped') else '_log')
        add('logging', ja + 'の原木', wood.replace('_',' ') + ' logs', [step('collect', item, item)], 'search')
    add('crafting', '板材', 'planks', [step('acquire', '#minecraft:planks')], 'prepare', ['木の板', 'wooden boards'], True)
    resources = [('diamond','ダイヤモンド','diamond_ore'),('raw_iron','鉄の原石','iron_ore'),
        ('raw_gold','金の原石','gold_ore'),('raw_copper','銅の原石','copper_ore'),('coal','石炭','coal_ore'),
        ('redstone','レッドストーン','redstone_ore'),('lapis_lazuli','ラピスラズリ','lapis_ore'),
        ('emerald','エメラルド','emerald_ore'),('cobblestone','丸石','stone'),('flint','火打石','gravel'),
        ('obsidian','黒曜石','obsidian'),('sand','砂','sand'),('dirt','土','dirt'),
        ('gravel','砂利','gravel'),('netherrack','ネザーラック','netherrack'),('quartz','ネザークォーツ','nether_quartz_ore')]
    for item, ja, block in resources:
        add('mining', ja, item.replace('_',' '), [step('collect', 'minecraft:'+item, 'minecraft:'+block)], 'explore')
    crafted = [('stick','棒'),('crafting_table','作業台'),('furnace','かまど'),('chest','チェスト'),
        ('torch','たいまつ'),('charcoal','木炭'),('iron_ingot','鉄インゴット'),('gold_ingot','金インゴット'),
        ('copper_ingot','銅インゴット'),('bread','パン'),('cooked_beef','ステーキ'),('cooked_porkchop','焼き豚'),
        ('cooked_chicken','焼き鳥'),('baked_potato','ベイクドポテト'),('glass','ガラス'),('bucket','バケツ'),
        ('flint_and_steel','火打石と打ち金'),('shield','盾'),('bow','弓'),('arrow','矢'),('ender_eye','エンダーアイ'),
        ('blaze_powder','ブレイズパウダー'),('iron_helmet','鉄のヘルメット'),('iron_chestplate','鉄のチェストプレート'),
        ('iron_leggings','鉄のレギンス'),('iron_boots','鉄のブーツ'),('ladder','はしご'),('stone_bricks','石レンガ')]
    for material, ja in [('wooden','木'),('stone','石'),('iron','鉄'),('diamond','ダイヤモンド')]:
        for tool, jp in [('pickaxe','ツルハシ'),('axe','斧'),('shovel','シャベル'),('hoe','クワ'),('sword','剣')]:
            crafted.append((material+'_'+tool, ja+'の'+jp))
    for item, ja in crafted:
        add('crafting', ja, item.replace('_',' '), [step('acquire', 'minecraft:'+item)], 'smelt' if 'ingot' in item or 'cooked' in item else 'prepare')
    for mob, ja in [('zombie','ゾンビ'),('skeleton','スケルトン'),('creeper','クリーパー'),('spider','クモ'),
            ('enderman','エンダーマン'),('blaze','ブレイズ'),('witch','ウィッチ'),('slime','スライム'),
            ('magma_cube','マグマキューブ'),('ghast','ガスト'),('wither_skeleton','ウィザースケルトン'),
            ('drowned','ドラウンド'),('husk','ハスク'),('stray','ストレイ'),('phantom','ファントム'),
            ('silverfish','シルバーフィッシュ'),('endermite','エンダーマイト'),('shulker','シュルカー'),
            ('piglin_brute','ピグリンブルート'),('hoglin','ホグリン'),('zoglin','ゾグリン')]:
        # Entity type is an observed filter, never a made-up live entity ID.
        add('combat', ja, mob.replace('_',' '), [atomic('inspect_entities', dict(radius=16)),step('plan_next')], 'combat', [mob])
    for item, ja in [('ender_pearl','エンダーパール'),('blaze_rod','ブレイズロッド'),('bone','骨'),
                     ('gunpowder','火薬'),('string','糸'),('leather','革'),('feather','羽根'),('beef','生の牛肉')]:
        add('hunting', ja, item.replace('_',' '), [step('acquire','minecraft:'+item)], 'combat')
    crops = [('wheat','小麦','wheat_seeds'),('carrots','ニンジン','carrot'),('potatoes','ジャガイモ','potato'),
             ('beetroots','ビートルート','beetroot_seeds'),('nether_wart','ネザーウォート','nether_wart')]
    for crop, ja, seed in crops:
        add('farming', ja+'の収穫', 'harvest '+crop.replace('_',' '),
            [atomic('find_blocks', dict(block='minecraft:'+crop, radius=16)), step('plan_next')], 'farm',
            [ja, 'crop', 'farm', '畑', '収穫'], True)
        add('farming', ja+'を育てる準備', 'prepare to grow '+crop.replace('_',' '),
            [step('acquire','minecraft:'+seed), step('plan_next')], 'missing_seed', [ja,'plant','種','栽培'])
    for item, ja in [('wheat_seeds','小麦の種'),('carrot','ニンジン'),('potato','ジャガイモ'),
                     ('beetroot_seeds','ビートルートの種'),('sugar_cane','サトウキビ')]:
        # Missing crop sources must be observed; not claimed to be supported by a guessed recipe.
        add('farming_observe', ja, item.replace('_',' '), [atomic('inspect_block',dict(x=0,y=1,z=0)),step('plan_next')], 'farm', ['畑','farm'])
    for group, ja, en, steps, stage in [
        ('progression','採掘の準備','prepare for mining',[step('acquire','minecraft:iron_pickaxe'),step('acquire','minecraft:torch', count=16)],'prepare'),
        ('progression','冒険の準備','prepare for adventure',[step('acquire','minecraft:shield'),step('acquire','minecraft:bread',count=8)],'prepare'),
        ('progression','ネザーへ行く準備','prepare for the Nether',[step('acquire','minecraft:obsidian',count=10),step('acquire','minecraft:flint_and_steel'),step('plan_next')],'portal'),
        ('progression','エンダードラゴン討伐の準備','prepare to defeat the Ender Dragon',[step('acquire','minecraft:iron_pickaxe'),step('acquire','minecraft:shield'),step('plan_next')],'ranged'),
        ('progression','要塞を探す準備','prepare to find the stronghold',[step('acquire','minecraft:ender_eye',count=12),step('plan_next')],'structure'),
        ('progression','ダイヤを掘って片付ける','mine diamonds and store them',[step('acquire','minecraft:diamond',count=3),step('deposit','minecraft:diamond',count=3)],'explore'),
        ('transport','原木を片付ける','put away the logs',[step('deposit','#minecraft:logs',count=8)],'transfer'),
        ('transport','持っている原木を渡す','give me the carried logs',[step('give_item','#minecraft:logs',count=4)],'transfer'),
        ('navigation','ついてきて','follow me',[step('follow')],'transfer'),
        ('navigation','少し待って','wait here',[step('wait')],'ready'),
        ('cooperation','仲間と道具を用意する','prepare tools together',[step('delegate','minecraft:iron_ingot',count=3),step('acquire','minecraft:iron_pickaxe')],'team'),
    ]:
        add(group,ja,en,steps,stage,[ja,en],True)
    # Genuinely under-specified or dangerous references must not invent an entity or destination.
    for ja,en in [('あれを壊して','break that'),('あそこへ持っていって','take it over there'),
                  ('あいつを倒して','kill that one'),('全部片付けて','clear everything'),
                  ('いい感じにして','make it nice'),('さっきの続き','continue the previous task')]:
        add('clarification',ja,en,[],'blocked',[ja,en],True,
            '対象・場所・直前の依頼を観察から特定できません。必要な対象を確認してください。')
    return out

TEMPLATES = {
 'train': [('{}をお願い','Please handle {}'),('{}を集めて','Get some {}'),('近くで{}を用意して','Prepare {} nearby'),
           ('{}が欲しい','I need {}'),('{}を少し頼む','Could you get a little {}'),('{}をいい感じにお願い','Take care of {}'),
           ('手が空いたら{}を進めて','Work on {} when free'),('{}の作業を始めて','Start working on {}')],
 'validation': [('周りを見て{}を確保して','Check around and obtain {}'),('{}をやっておいて','Please sort out {}')],
 'test': [('できる範囲で{}を頼める','Can you manage {} with what is available'),('今は{}を優先したい','For now prioritize {}')]
}

def score(x):
    # Candidate legality takes precedence even for urgent actions.
    if x[19] < .5 or x[20] < .5 or x[15] > .5: return -14.
    return old_teacher(x)

def example(entry, split, index, rng):
    stage = rng.choice([entry['stage'], *STAGES])
    kinds = ['idle','replan', 'verify' if stage=='verify' else 'execute', 'scan','prepare','approach']
    if stage == 'explore': kinds[2:4] = ['excavate','explore']
    c = [vector(k,rng,stage) for k in kinds]
    # Match correlated runtime state across candidates, unlike independent random worlds.
    for j in [13,17,18,24,25,26,27,28,29,30,31,33,34,35,36,37,40,41,42,44,45,46,47]:
        value = c[2][j]
        for x in c: x[j] = value
    if stage in ('blocked','immature','missing_seed'):
        c[2][19] = 0.
    if rng.random() < .30:
        c[0] = vector('eat',rng,stage,True)
        # Sometimes even the food/defence option is no longer reachable/valid.
        if rng.random() < .12: c[0][19] = 0.
    if rng.random() < .25: c[2][15] = 1.
    rng.shuffle(c)
    a,b = rng.choice(TEMPLATES[split]); qty=rng.choice([1,2,3,4,8,16,32,64])
    ja_subject = rng.choice([entry['ja'], *[v for v in entry['aliases'] if any(ord(ch)>127 for ch in v)]])
    en_subject = rng.choice([entry['en'], *[v for v in entry['aliases'] if v.isascii()]])
    ja,en = a.format(ja_subject),b.format(en_subject)
    explicit = entry['category'] in ('logging','mining','crafting','hunting') and rng.random()<.65
    if explicit: ja += f'、{qty}個'; en += f', {qty} items'
    plan_steps=json.loads(json.dumps(entry['steps']))
    for s in plan_steps:
        if s['action'] in ('collect','acquire') and len(plan_steps)==1:
            nonstack = any(s['target'].endswith('_'+v) for v in ('pickaxe','axe','hoe','sword','shovel','helmet','chestplate','leggings','boots')) or s['target'] in ('minecraft:bow','minecraft:shield','minecraft:flint_and_steel')
            little = '少し' in ja or 'little' in en
            s['count']=1 if nonstack else qty if explicit else 4 if little else 8
    # Verb templates are category-specific to avoid "collect a harvest" or "get a follow" labels.
    if entry['category'] in ('combat',):
        ja={'train':f'近くの{entry["ja"]}を倒して','validation':f'{entry["ja"]}から周辺を守って','test':f'見えている{entry["ja"]}を退治できる'}[split]
        en={'train':f'Defeat nearby {entry["en"]}','validation':f'Protect this area from {entry["en"]}','test':f'Can you fight the visible {entry["en"]}'}[split]
        if not plan_steps or plan_steps[-1]['action']!='plan_next': plan_steps.append(step('plan_next'))
    elif entry['category'] in ('navigation','progression','transport','cooperation','clarification','farming','farming_observe'):
        ja = {'train':'できれば','validation':'今から','test':'次は'}[split] + entry['ja']
        en = {'train':'Please ','validation':'For now, ','test':'Next, '}[split] + entry['en']
    # Explicit state facts keep ambiguous defaults bounded and reversible.
    state = dict(stage=stage, entityIdsAreObservedOnly=True, seed=index, defaultQuantity=8,
                 noPreviousTask=entry['category']=='clarification', cropMature=stage!='immature',
                 hasSeeds=stage!='missing_seed', chestLocation='observed home chest only')
    if len(plan_steps)==1 and plan_steps[0]['action'] in ('collect','acquire'):
        # Contextual destinations are real fixture observations, never unknown coordinates.
        if rng.random()<.5:
            jp,eng,location=rng.choice([('家','home','home'),('倉庫','storehouse','store'),('作業場','workshop','work'),
                                      ('入口','entrance','entry'),('拠点','base','base'),('畑の横','farm','farm')])
            ja+=f'、{jp}のチェストに入れて';en+=f' and put them in the {eng} chest'
            resource=plan_steps[0]['target'];total=plan_steps[0]['count']
            plan_steps.append(step('deposit',resource,count=total));plan_steps[-1]['location']=location
            state['locations']=[dict(name=location,dimension='minecraft:overworld',x=4,y=1,z=4,kind='chest')]
        jp,eng=rng.choice([('',''),('。手持ちの分も数えて','; count what you already carry'),
                          ('。危険なら無理をしないで','; stop if it becomes unsafe'),('。道具がなければ先に用意して','; prepare a tool first if needed'),
                          ('。必要数になったら止めて','; stop once the requested quantity is reached')])
        ja+=jp;en+=eng
    if index%4==0:
        # Ambiguous references with contrasting observed contexts, including unresolved cases.
        case=rng.randrange(6);state['contextualReferenceCase']=case
        if case in (0,1):
            species=rng.choice(['spruce','birch','oak']);have=rng.randrange(0,8);total=rng.choice([8,16,32])
            state.update(inventory=[dict(item='minecraft:'+species+'_log',count=have,tags=['minecraft:logs'])],previousGoal=dict(target='#minecraft:logs',count=total))
            ja={'train':'さっきの木、足りない分をお願い','validation':'木材の続きを、必要な分だけ','test':'前に頼んだ木を不足分だけ揃えて'}[split]
            en={'train':'Get the rest of the wood I asked for','validation':'Continue the timber job, only what is missing','test':'Finish gathering the missing wood from my earlier request'}[split]
            plan_steps=[step('collect','#minecraft:logs','#minecraft:logs',total)]
            state.update(expectedRemaining=total-have,noPreviousTask=False)
        elif case==2:
            state.update(inventory=[dict(item='minecraft:spruce_log',count=4,tags=['minecraft:logs'])],locations=[dict(name='home',dimension='minecraft:overworld',x=4,y=1,z=4,kind='chest')])
            ja={'train':'その木をいつもの場所に片付けて','validation':'持っている木材を家の箱へ','test':'手元の原木は拠点の保管先へ戻して'}[split]
            en={'train':'Put that wood away in the usual place','validation':'Store the wood you carry in the home chest','test':'Return your carried logs to the base storage'}[split]
            plan_steps=[step('deposit','#minecraft:logs',count=4)];plan_steps[0]['location']='home'
        elif case==3:
            state.update(cropMature=False,hasSeeds=False,observedCrop=dict(block='minecraft:wheat',age=2,maxAge=7,x=4,y=1,z=4))
            ja={'train':'畑をちょっとお願い','validation':'畑の様子を見て世話して','test':'作物をいい感じに育てておいて'}[split]
            en={'train':'Take care of the farm for a bit','validation':'Check on and tend the field','test':'Keep an eye on the crops and help them grow'}[split]
            plan_steps=[atomic('inspect_block',dict(x=4,y=1,z=4)),step('wait'),step('plan_next')]
        elif case==4:
            ja={'train':'あれを片付けて','validation':'そこの物をお願い','test':'あっちのやつを処理して'}[split]
            en={'train':'Take care of that thing','validation':'Please handle the thing over there','test':'Deal with that one over there'}[split]
            state.update(noPreviousTask=True,nearbyTargets=[],locations=[])
            plan_steps=[]
        else:
            state.update(observedLogs=['minecraft:spruce_log','minecraft:birch_log'],excludedSpecies='spruce')
            ja={'train':'トウヒ以外の木を少し集めて','validation':'トウヒは残して別の原木を少量','test':'そこの木ならトウヒ以外を少しお願い'}[split]
            en={'train':'Get a little wood, but leave the spruce','validation':'Leave spruce trees and collect a few other logs','test':'Some nearby timber please, excluding spruce'}[split]
            plan_steps=[step('collect','minecraft:birch_log','minecraft:birch_log',4)]
        entry=dict(entry,category='contextual_ambiguity',ambiguous=True,blocked='対象を観察から特定できません。どの対象か確認してください。' if case==4 else '')
    return dict(modelVersion=4, source='synthetic-curriculum-v4', exampleId=f'{split}-{index:07}',
                scenario=entry['id'], category=entry['category'], ambiguous=entry['ambiguous'] or not explicit,
                instructionJa=ja,instructionEn=en, state=state,
                expectedPlan=dict(version=1,summary=ja[:512],blocked=entry['blocked'],steps=plan_steps),
                candidates=c, chosen=max(range(len(c)),key=lambda i:score(c[i])))

def generate(output, counts):
    output.mkdir(parents=True,exist_ok=True)
    entries=catalog(); report=dict(seed=SEED,source='synthetic templates and candidate demonstrations; no gameplay completion labels',
                                  scenarios=len(entries),categories=sorted({e['category'] for e in entries}),splits={})
    runtime=[]
    for entry in entries:
        runtime.append(dict(category=entry['category'],ja=entry['ja'],en=entry['en'],aliases=entry['aliases'],
                            steps=entry['steps'],blocked=entry['blocked']))
    contextual={}
    for i in range(100):
        e=example(entries[0],'train',i*4,random.Random(SEED+10000+i))
        key=e['state']['contextualReferenceCase']
        if key in contextual: continue
        contextual[key]=dict(category='contextual_ambiguity',ja=e['instructionJa'],en=e['instructionEn'],
            aliases=[['さっきの木','rest of the wood'],['不足分','missing wood'],['いつもの場所','usual place'],
                     ['畑を','farm','field'],['あれを','that thing'],['トウヒ以外','excluding spruce']][key],
            context=e['state'],steps=e['expectedPlan']['steps'],blocked=e['expectedPlan']['blocked'])
    runtime.extend(contextual.values())
    resource=Path('src/npc/resources/data/avatarcraftnpc/ai/instruction-examples.json')
    resource.write_text(json.dumps(runtime,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for offset,(split,count) in enumerate(counts.items()):
        rng=random.Random(SEED+offset); path=output/(split+'.jsonl.gz'); unique=set(); ambiguous=0
        with path.open('wb') as raw, gzip.GzipFile(fileobj=raw,filename='',mode='wb',mtime=0) as compressed, io.TextIOWrapper(compressed,encoding='utf-8',newline='\n') as f:
            for i in range(count):
                e=example(entries[i%len(entries)],split,i,rng)
                unique.update([e['instructionJa'],e['instructionEn']]); ambiguous+=e['ambiguous']
                f.write(json.dumps(e,ensure_ascii=False,separators=(',',':'))+'\n')
        report['splits'][split]=dict(examples=count,bilingualUtterances=count*2,uniqueUtterances=len(unique),
                                    ambiguous=ambiguous,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=path.stat().st_size)
    (output/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--output',type=Path,default=Path('TrainingData/npc-v4'))
    p.add_argument('--train',type=int,default=192000); p.add_argument('--validation',type=int,default=12000); p.add_argument('--test',type=int,default=12000)
    a=p.parse_args(); generate(a.output,dict(train=a.train,validation=a.validation,test=a.test))
