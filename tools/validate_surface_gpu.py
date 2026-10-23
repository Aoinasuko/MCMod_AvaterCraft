"""Compile the shipped GLSL and verify feature pixels in an isolated, invisible OpenGL context.

Run tools/dev.ps1 -Task test first. Requires Python moderngl and Pillow; never controls Minecraft.
"""
from pathlib import Path
import json
import re
import struct
import zipfile

import moderngl
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / 'build/surface-qa'
SCHEMA = json.loads((ROOT / 'src/main/resources/assets/avatarcraft/surface.json').read_text())
FIXTURES = json.loads((QA / 'fixtures.json').read_text())
MC = ROOT / 'build/moddev/artifacts/minecraft-patched-26.3.0.95-sources.jar'


def source(stage, definitions):
    def expand(text):
        def include(match):
            namespace, name = match.group(1).split(':', 1)
            if namespace == 'minecraft':
                with zipfile.ZipFile(MC) as z:
                    code = z.read('assets/minecraft/shaders/include/' + name).decode()
            else:
                code = (ROOT / 'src/main/resources/assets' / namespace / 'shaders/include' / name).read_text()
            return expand(code)
        return re.sub(r'#include\s+<([^>]+)>', include, text)
    code = expand((ROOT / f'src/main/resources/assets/avatarcraft/shaders/core/avatar.{stage}').read_text(encoding='utf-8-sig'))
    return code.replace('#version 330', '#version 330\n' + '\n'.join(f'#define {k} {v}' for k, v in definitions.items()), 1)


def defines(fixture, name):
    d = {k: repr(float(v)) for k, v in fixture['defines'].items()}
    d.update({k: repr(float(v)) for k, v in fixture['atlas']['values'].items()})
    for slot in SCHEMA['slots']:
        key = 'T' + slot.upper()
        d[key + '_HAS'] = int(key in fixture['atlas']['present'])
    d.update(AVC_U_SCALE='1.0', AVC_V_SCALE='1.0', AVC_U_OFFSET='0.0', AVC_V_OFFSET='0.0',
             AVC_ALPHA_MASK_MODE=2 if name in ('alpha', 'cutout') else 0,
             AVC_ALPHA_MASK_SCALE='1.0', AVC_ALPHA_MASK_VALUE='0.0', AVC_CUTOFF='0.5' if name == 'cutout' else '0.001')
    if name != 'alpha': d['AVC_OPAQUE'] = 1
    if name == 'cutout': d['AVC_CUTOUT'] = 1
    if name == 'outline': d['AVC_OUTLINE'] = 1
    return d


def floats(*values):
    return struct.pack('<' + 'f' * len(values), *values)


def main():
    ctx = moderngl.create_standalone_context(require=330)
    print('GPU:', ctx.info['GL_RENDERER'])
    identity = [1., 0, 0, 0, 0, 1., 0, 0, 0, 0, 1., 0, 0, 0, 0, 1.]
    blocks = {
        'DynamicTransforms': floats(*identity, *identity, 1, 1, 1, 1, 0, 0, 0, 0),
        'Projection': floats(*identity),
        'Lighting': floats(1, 0, 0, 0, 1, 0, 0, 0),
        'Fog': floats(0, 0, 0, 0, 100, 200, 100, 200, 100, 100, 0, 0),
        'Globals': bytes(64),
    }
    fbo = ctx.simple_framebuffer((64, 64), components=4, dtype='f4')
    fbo.use()
    atlas_image = Image.open(QA / 'atlas.png').convert('RGBA')
    atlas = ctx.texture(atlas_image.size, 4, atlas_image.tobytes())
    atlas.filter = (moderngl.LINEAR, moderngl.LINEAR)
    main_tex = ctx.texture((1, 1), 4, floats(.25, .5, .25, 1), dtype='f4')
    light_tex = ctx.texture((16, 16), 4, bytes([255] * 16 * 16 * 4))
    atlas.use(1); main_tex.use(0); light_tex.use(2)
    results, pictures = {}, []
    cases = dict(FIXTURES)
    cases['cutout'] = FIXTURES['baseline']
    combined = json.loads(json.dumps(FIXTURES['baseline']))
    for key in combined['defines']:
        if key.startswith('S_USE'): combined['defines'][key] = 1
    cases['combined'] = combined
    for name, fixture in cases.items():
        d = defines(fixture, name)
        program = ctx.program(vertex_shader=source('vsh', d), fragment_shader=source('fsh', d))
        owned = []
        for index, (key, data) in enumerate(blocks.items()):
            if key not in program: continue
            buffer = ctx.buffer(data + bytes(max(0, program[key].size - len(data))))
            program[key].binding = index; buffer.bind_to_uniform_block(index); owned.append(buffer)
        for i in range(3):
            if f'Sampler{i}' in program: program[f'Sampler{i}'].value = i
        vertices = []
        for x, y in [(-1, -1), (1, -1), (1, 1), (-1, -1), (1, 1), (-1, 1)]:
            vertices += [x, y, -.5, 1, 1, 1, .25 if name == 'alpha' else 1, (x+1)/2, (1-y)/2, 0, 0, 1]
        buffer = ctx.buffer(floats(*vertices))
        # UV1 is unused by the shipped shaders. Light coordinates default to zero: white test lightmap.
        format_parts, attrs = [], []
        for attribute, count in [('Position', 3), ('Color', 4), ('UV0', 2), ('Normal', 3)]:
            format_parts.append(f'{count}f' if attribute in program else f'{count*4}x')
            if attribute in program: attrs.append(attribute)
        vao = ctx.vertex_array(program, [(buffer, ' '.join(format_parts), *attrs)])
        fbo.clear(.03125, .0625, .09375, .125)
        vao.render(moderngl.TRIANGLES)
        raw = fbo.read(components=4, dtype='f4')
        values = struct.unpack('<'+'f'*(64*64*4), raw)
        assert all(abs(v) < 1e6 for v in values), (name, 'nonfinite output')
        results[name] = [values[(32*64+x)*4:(32*64+x+1)*4] for x in (16, 48)]
        image = Image.frombytes('RGBA', (64, 64), fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM).resize((128,128))
        tile = Image.new('RGB',(192,156),(35,35,35)); tile.paste(image,(32,22)); ImageDraw.Draw(tile).text((4,4),name,fill='white'); pictures.append(tile)
        vao.release(); buffer.release(); program.release()
        for obj in owned: obj.release()
        print('Compiled + rendered:', name)
    def close(a,b): return all(abs(x-y)<.025 for x,y in zip(a,b))
    baseline=results['baseline'][0]
    assert close(baseline,(.25,.5,.25,1)), baseline
    assert close(results['empty'][0],baseline)
    for name in ('layer2','layer3','matcap1','matcap2','emission1','emission2','shadow','rim'):
        assert close(results[name][0],baseline), (name,'masked area changed',results[name][0])
        assert not close(results[name][1],baseline), (name,'enabled area unchanged')
    assert close(results['layer2'][1],(1,0,0,1))
    assert close(results['matcap1'][1],(0,1,0,1))
    assert close(results['matcap2'][1],(0,0,1,1))
    assert close(results['normal1'][1],baseline), ('normal map did not restore lit side',results['normal1'][1])
    assert close(results['normal2'][1],baseline)
    assert close(results['normal2'][0],baseline)
    assert results['shadow'][1][0] < results['normal1'][1][0]-.02
    assert close(results['dissolve'][0],(.03125,.0625,.09375,.125))
    assert close(results['dissolve'][1],baseline)
    for name in ('alpha','cutout'):
        assert close(results[name][0],(.03125,.0625,.09375,.125))
    assert abs(results['alpha'][1][3]-.25)<.01
    sheet=Image.new('RGB',(192*4,156*((len(pictures)+3)//4)),(20,20,20))
    for i,picture in enumerate(pictures): sheet.paste(picture,((i%4)*192,(i//4)*156))
    sheet.save(QA/'gpu-results.png')
    (QA/'gpu-results.json').write_text(json.dumps({'gpu':ctx.info['GL_RENDERER'],'pixels':results},indent=2))
    print(f'PASS: {len(cases)} real GLSL variants, masked feature pixels, alpha/cutout, and normals')
    ctx.release()


if __name__ == '__main__': main()
