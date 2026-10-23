"""Author AvatarCraft's original humanoid keyframe library (no Minecraft animation data)."""
import copy
import json
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / 'unity/Packages/dev.avatarcraft.exporter/Editor/Resources/AvatarCraft/motions.json'
clips = {}
actions = {}

def frame(t, rotations=None, positions=None):
    return {'time': round(t, 5), 'rotations': rotations or {}, 'positions': positions or {}}

def add(key, label, duration, loop, frames, layer='base'):
    clips[key] = dict(name=label, duration=duration, loop=loop, layer=layer, frames=frames)

def action(key, label, clip, layer='base'):
    actions[key] = dict(label=label, clip=clip, layer=layer)

add('idle_relaxed', '待機・リラックス', 4, True, [
    frame(0, {'Spine':[1,0,0], 'LeftLowerArm':[-8,0,-2], 'RightLowerArm':[-8,0,2]}),
    frame(1, {'Spine':[.2,0,.4], 'Chest':[-.7,0,0], 'LeftLowerArm':[-9,0,-2], 'RightLowerArm':[-9,0,2]}, {'Chest':[0,.0012,0]}),
    frame(2, {'Spine':[-.5,0,0], 'Chest':[-1.2,0,0], 'LeftLowerArm':[-10,0,-2], 'RightLowerArm':[-10,0,2]}, {'Chest':[0,.002,0]}),
    frame(3, {'Spine':[.2,0,-.4], 'Chest':[-.7,0,0], 'LeftLowerArm':[-9,0,-2], 'RightLowerArm':[-9,0,2]}, {'Chest':[0,.0012,0]}),
    frame(4, {'Spine':[1,0,0], 'LeftLowerArm':[-8,0,-2], 'RightLowerArm':[-8,0,2]})])
add('idle_attentive', '待機・姿勢を正す', 3.2, True, [frame(0, {'Spine':[-1,0,0], 'LeftLowerArm':[-5,0,0], 'RightLowerArm':[-5,0,0]}), frame(1.6, {'Spine':[-1.6,0,0], 'Chest':[-.8,0,0], 'LeftLowerArm':[-6,0,0], 'RightLowerArm':[-6,0,0]}, {'Chest':[0,.0018,0]}), frame(3.2, {'Spine':[-1,0,0], 'LeftLowerArm':[-5,0,0], 'RightLowerArm':[-5,0,0]})])

def gait(key, label, duration, strength=1, running=False, crouched=False, backward=False, strafe=0):
    hips = [-38,-25,0,30,36,0,-30,-45] if running else [-24,-12,4,20,24,8,-12,-25]
    knees = [12,18,35,62,110,100,45,8] if running else [5,7,9,15,35,58,42,10]
    feet = [-8,1,8,-15,-23,10,12,-4]
    fs = []
    for i in range(9):
        p=i%8; q=(p+4)%8
        r = {'Hips':[0,[-5,-2,2,5,5,2,-2,-5][p]*strength, [2,1,0,-1,-2,-1,0,1][p]*strength],
             'Spine':[18 if crouched else 7 if running else 2, [3,1,-1,-3,-3,-1,1,3][p]*strength,0],
             'Chest':[0,[3,1,-1,-3,-3,-1,1,3][p]*strength,0]}
        for side, phase in [('Left',p),('Right',q)]:
            pitch=hips[phase]*strength*(-.6 if backward else .5 if strafe else .55 if crouched else 1)
            r[side+'UpperLeg']=[pitch-(28 if crouched else 0),0,strafe*hips[phase]*strength*.55]
            r[side+'LowerLeg']=[knees[phase]*strength+(48 if crouched else 0),0,0]
            r[side+'Foot']=[feet[phase]*strength-(18 if crouched else 0),0,0]
            r[side+'UpperArm']=[-hips[phase]*strength*(.75 if running else .42),0,(-3 if side=='Left' else 3)]
            r[side+'LowerArm']=[-(66 if running else 12)-abs(hips[phase])*strength*.25,0,0]
        y=[-.004,0,.006,0,-.004,0,.006,0][p]*strength*(2 if running else 1)
        if crouched:y-=.095
        fs.append(frame(i*duration/8,r,{'Hips':[[.004,.002,0,-.002,-.004,-.002,0,.002][p]*strength,y,0]}))
    add(key,label,duration,True,fs)

gait('walk_natural','歩行・標準',1.05)
gait('walk_soft','歩行・小さな歩幅',1.25,.68)
gait('walk_brisk','歩行・きびきび',.86,1.12)
gait('run_jog','走行・ジョギング',.74,.8,True)
gait('run_sprint','走行・スプリント',.62,1,True)
gait('walk_backward','後退',1.2,.85,backward=True)
gait('strafe_left','左へ横移動',1.1,.75,strafe=-1)
gait('strafe_right','右へ横移動',1.1,.75,strafe=1)
gait('crouch_walk','しゃがみ移動',1.3,.55,crouched=True)

add('crouch_idle','しゃがみ待機',3,True,[frame(0,{'Spine':[20,0,0],'Chest':[-5,0,0],'LeftUpperLeg':[-28,0,-4],'RightUpperLeg':[-28,0,4],'LeftLowerLeg':[50,0,0],'RightLowerLeg':[50,0,0],'LeftFoot':[-22,0,0],'RightFoot':[-22,0,0],'LeftLowerArm':[-15,0,0],'RightLowerArm':[-15,0,0]},{'Hips':[0,-.095,.015]}),frame(1.5,{'Spine':[19,0,0],'Chest':[-5,0,0],'LeftUpperLeg':[-28,0,-4],'RightUpperLeg':[-28,0,4],'LeftLowerLeg':[50,0,0],'RightLowerLeg':[50,0,0],'LeftFoot':[-22,0,0],'RightFoot':[-22,0,0],'LeftLowerArm':[-16,0,0],'RightLowerArm':[-16,0,0]},{'Hips':[0,-.094,.015]})])
# Include the closing pose explicitly for every loop; sampling never jumps across a missing endpoint.
clips['crouch_idle']['frames'].append(copy.deepcopy(clips['crouch_idle']['frames'][0]));clips['crouch_idle']['frames'][-1]['time']=3

def pose_clip(key,label,rot,pos=None,layer='base',duration=1,loop=True):
    add(key,label,duration,loop,[frame(0,rot,pos),frame(duration,copy.deepcopy(rot),copy.deepcopy(pos or {}))],layer)

add('jump','ジャンプ',.65,False,[frame(0,{'Spine':[8,0,0],'LeftUpperLeg':[-12,0,0],'RightUpperLeg':[-12,0,0],'LeftLowerLeg':[24,0,0],'RightLowerLeg':[24,0,0],'LeftUpperArm':[15,0,-8],'RightUpperArm':[15,0,8]}, {'Hips':[0,-.025,0]}),frame(.18,{'Spine':[-5,0,0],'LeftUpperLeg':[-26,0,0],'RightUpperLeg':[-10,0,0],'LeftLowerLeg':[52,0,0],'RightLowerLeg':[36,0,0],'LeftUpperArm':[-38,0,-12],'RightUpperArm':[-38,0,12],'LeftLowerArm':[-32,0,0],'RightLowerArm':[-32,0,0]}),frame(.65,{'Spine':[4,0,0],'LeftUpperLeg':[5,0,0],'RightUpperLeg':[-4,0,0],'LeftLowerLeg':[18,0,0],'RightLowerLeg':[24,0,0],'LeftUpperArm':[-12,0,-16],'RightUpperArm':[-12,0,16],'LeftLowerArm':[-18,0,0],'RightLowerArm':[-18,0,0]})])
pose_clip('fall','落下',{'Spine':[8,0,0],'LeftUpperArm':[-12,0,-25],'RightUpperArm':[-12,0,25],'LeftLowerArm':[-25,0,0],'RightLowerArm':[-25,0,0],'LeftUpperLeg':[8,0,-4],'RightUpperLeg':[-4,0,4],'LeftLowerLeg':[18,0,0],'RightLowerLeg':[24,0,0]})
add('land','着地の吸収',.32,False,[frame(0),frame(.09,{'Spine':[14,0,0],'LeftUpperLeg':[-24,0,0],'RightUpperLeg':[-24,0,0],'LeftLowerLeg':[46,0,0],'RightLowerLeg':[46,0,0],'LeftFoot':[-20,0,0],'RightFoot':[-20,0,0],'LeftLowerArm':[-20,0,0],'RightLowerArm':[-20,0,0]},{'Hips':[0,-.07,0]}),frame(.32)])

for key,label,body in [('swim','水泳・クロール',85),('crawl','ほふく',85),('climb','はしご',5)]:
    fs=[]
    for i in range(9):
        phase=i%8; swing=[-1,-.7,0,.7,1,.7,0,-.7][phase]
        r={'Hips':[body,0,0],'Chest':[0,4*swing,0]}
        if key in ('swim','crawl'):r.update({'Neck':[-25,0,0],'Head':[-55,0,0]})
        for side,sign in [('Left',1),('Right',-1)]:
            r[side+'UpperArm']=[(-105 if key=='climb' else -120 if key=='crawl' else -100)+sign*swing*(35 if key=='climb' else 65),0,sign*-8]
            r[side+'LowerArm']=[-35-sign*swing*20,0,0]
            r[side+'UpperLeg']=[sign*swing*(20 if key=='climb' else 16),0,0]
            r[side+'LowerLeg']=[(35 if key=='climb' else 18)+sign*swing*14,0,0]
        fs.append(frame(i*.15,r,{'Hips':[0,-.30 if key in ('crawl','swim') else 0,0]}))
    add(key,label,1.2,True,fs)
pose_clip('swim_idle','水中待機',{'Hips':[12,0,0],'LeftUpperArm':[-35,0,-30],'RightUpperArm':[-35,0,30],'LeftLowerArm':[-40,0,0],'RightLowerArm':[-40,0,0],'LeftUpperLeg':[0,0,-6],'RightUpperLeg':[0,0,6],'LeftLowerLeg':[22,0,0],'RightLowerLeg':[22,0,0]})
pose_clip('elytra','エリトラ滑空',{'Hips':[85,0,0],'Neck':[-25,0,0],'Head':[-55,0,0],'LeftUpperArm':[12,0,-16],'RightUpperArm':[12,0,16],'LeftLowerArm':[-6,0,0],'RightLowerArm':[-6,0,0],'LeftUpperLeg':[0,0,-3],'RightUpperLeg':[0,0,3],'LeftLowerLeg':[10,0,0],'RightLowerLeg':[10,0,0]}, {'Hips':[0,-.30,0]})
pose_clip('creative_fly','飛行・浮遊',{'Spine':[-4,0,0],'LeftUpperArm':[-8,0,-18],'RightUpperArm':[-8,0,18],'LeftLowerArm':[-15,0,0],'RightLowerArm':[-15,0,0],'LeftUpperLeg':[-8,0,-5],'RightUpperLeg':[4,0,5],'LeftLowerLeg':[20,0,0],'RightLowerLeg':[12,0,0]})
pose_clip('ride','搭乗・座り',{'Spine':[3,0,0],'LeftUpperLeg':[-82,0,-12],'RightUpperLeg':[-82,0,12],'LeftLowerLeg':[85,0,0],'RightLowerLeg':[85,0,0],'LeftUpperArm':[-30,0,0],'RightUpperArm':[-30,0,0],'LeftLowerArm':[-40,0,0]}, {'Hips':[0,-.04,0]})
pose_clip('sleep','睡眠',{'Hips':[90,0,0],'LeftLowerArm':[-12,0,-4],'RightLowerArm':[-12,0,4],'LeftLowerLeg':[8,0,0],'RightLowerLeg':[8,0,0]}, {'Hips':[0,-.30,0]})

def use_pose(key,label,right,other=None,spine=None):
    r={'RightUpperArm':right[0],'RightLowerArm':right[1],'RightHand':right[2]}
    if other:r.update({'LeftUpperArm':other[0],'LeftLowerArm':other[1],'LeftHand':other[2]})
    if spine:r['Spine']=spine
    pose_clip(key,label,r,layer='upper')

use_pose('hold','アイテムを持つ',([-18,0,4],[-35,0,0],[0,0,0]))
use_pose('block','盾で防御',([-40,-12,-15],[-65,0,0],[45,0,-12]),spine=[0,-8,0])
use_pose('bow','弓を引く',([-85,-20,8],[-8,0,0],[0,0,0]),([-65,40,-10],[-90,0,-20],[0,0,0]),[0,12,0])
use_pose('crossbow_charge','クロスボウ装填',([-48,-15,8],[-62,0,0],[0,0,0]),([-52,15,-12],[-78,0,0],[0,0,0]))
use_pose('crossbow_hold','クロスボウを構える',([-65,-15,8],[-30,0,0],[0,0,0]),([-65,12,-12],[-62,0,0],[0,0,0]))
use_pose('trident','槍を構える',([-145,8,12],[-45,0,0],[0,0,0]),spine=[-8,10,0])
use_pose('spyglass','望遠鏡',([-75,-18,-8],[-70,0,0],[55,0,0]))
use_pose('horn','角笛',([-55,-12,-6],[-100,0,0],[-12,0,0]))

for key,label,angle in [('eat','食事',-88),('drink','飲む',-108),('use','汎用アイテム使用',-45)]:
    fs=[]
    for i in range(5):
        lift=[0,4,0,-3,0][i]
        fs.append(frame(i*.25,{'RightUpperArm':[-45+lift,-8,-4],'RightLowerArm':[angle-lift,0,0],'RightHand':[(-15 if key=='drink' else 0)+lift,0,0],'Chest':[1-lift*.1,0,0]}))
    add(key,label,1,True,fs,'upper')

for key,label,amp in [('attack','攻撃・振り下ろし',1),('attack_light','攻撃・小さな振り',.65),('mine','採掘',.75)]:
    add(key,label,.55 if key!='mine' else .65,False,[
        frame(0,{'Spine':[0,8,0],'Chest':[-2,10,0],'RightUpperArm':[-40*amp,0,10],'RightLowerArm':[-35,0,0]}),
        frame(.12,{'Spine':[-4,14*amp,0],'Chest':[-4,16*amp,0],'RightUpperArm':[-140*amp,8,12],'RightLowerArm':[-65,0,0]}),
        frame(.28,{'Spine':[8,-10*amp,0],'Chest':[4,-14*amp,0],'RightUpperArm':[-25*amp,-8,-4],'RightLowerArm':[-18,0,0],'RightHand':[12,0,0]}),
        frame(.55 if key!='mine' else .65,{'RightUpperArm':[-18,0,4],'RightLowerArm':[-35,0,0]})],'upper')

add('hurt','被ダメージ・のけぞり',.4,False,[frame(0),frame(.08,{'Hips':[-5,0,0],'Spine':[-12,0,4],'Chest':[-8,0,0],'Head':[8,0,0],'LeftUpperArm':[8,0,-8],'RightUpperArm':[8,0,8]}),frame(.22,{'Spine':[-4,0,-2],'Chest':[-2,0,0],'Head':[3,0,0]}),frame(.4)],'additive')
add('death','死亡・崩れ落ちる',1.2,False,[frame(0),frame(.25,{'Hips':[0,0,15],'Spine':[16,0,12],'LeftUpperLeg':[-20,0,-4],'RightUpperLeg':[-16,0,4],'LeftLowerLeg':[45,0,0],'RightLowerLeg':[35,0,0]},{'Hips':[0,-.1,0]}),frame(.65,{'Hips':[10,0,60],'Spine':[12,0,12],'LeftUpperLeg':[-30,0,0],'RightUpperLeg':[-20,0,0],'LeftLowerLeg':[65,0,0],'RightLowerLeg':[45,0,0],'LeftLowerArm':[-45,0,0],'RightLowerArm':[-30,0,0]},{'Hips':[0,-.28,0]}),frame(1.2,{'Hips':[0,0,88],'Spine':[8,0,0],'LeftUpperLeg':[-20,0,0],'RightUpperLeg':[-10,0,0],'LeftLowerLeg':[40,0,0],'RightLowerLeg':[25,0,0],'LeftLowerArm':[-35,0,0],'RightLowerArm':[-20,0,0]},{'Hips':[0,-.38,0]})])

for key,label,clip in [
    ('idle','待機','idle_relaxed'),('walk','歩行','walk_natural'),('run','走行','run_jog'),
    ('backward','後退','walk_backward'),('strafe_left','左移動','strafe_left'),('strafe_right','右移動','strafe_right'),
    ('crouch_idle','しゃがみ待機','crouch_idle'),('crouch_walk','しゃがみ移動','crouch_walk'),
    ('jump','ジャンプ','jump'),('fall','落下','fall'),('land','着地','land'),('swim','水泳','swim'),
    ('swim_idle','水中待機','swim_idle'),('crawl','ほふく','crawl'),('climb','はしご','climb'),
    ('ride','搭乗','ride'),('sleep','睡眠','sleep'),('elytra','エリトラ','elytra'),('creative_fly','飛行','creative_fly')]:action(key,label,clip)
for key in ['hold','attack','mine','eat','drink','block','bow','crossbow_charge','crossbow_hold','trident','use','spyglass','horn']:
    action(key,clips[key]['name'],key,'upper')
action('hurt','被ダメージ','hurt','additive');action('death','死亡','death')

if __name__ == '__main__':
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps(dict(version=1,license='Original AvatarCraft motion library',actions=actions,clips=clips),ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    print(f'{len(actions)} actions, {len(clips)} clips -> {OUT}')
