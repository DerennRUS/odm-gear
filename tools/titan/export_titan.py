"""Assemble AoTTG2 titan parts in Blender and export a compact skinned model for the Minecraft mod.

Output (Minecraft axes: x, y up, z = facing direction):
  titan_model.json  - bones (rest head/tail), meshes (rest positions, uv, triangles, 4 bone weights), anim index
  titan_anims.bin   - big-endian float32 3x4 skin matrices per animation/frame/bone
"""
import bpy, sys, json, struct, math, mathutils

args = sys.argv[sys.argv.index('--') + 1:]
IN = args[0]
OUT = args[1]
STEP = 3  # 60 fps source -> 20 fps (one Minecraft tick)

ANIMS = [
    'idle', 'run.walk', 'run.abnormal', 'sit_down', 'sit_idle', 'sit_getup', 'turnaround.L', 'turnaround.R',
    'attack.slap.face', 'attack.slap.back', 'attack.swing.l', 'attack.swing.r', 'attack.stomp', 'attack.kick',
    'attack.comboPunch', 'attack.combo', 'attack.front.ground',
    'attack.anti.AE.l', 'attack.anti.AE.r', 'attack.anti.AE.low.l', 'attack.anti.AE.low.r', 'attack.anti.AE.high.l', 'attack.anti.AE.high.r',
    'grab.ground.front.l', 'grab.ground.front.r', 'grab.ground.back.l', 'grab.ground.back.r', 'grab.core.L', 'grab.core.R',
    'grab.air.far.l', 'grab.air.far.r', 'grab.air.short.l', 'grab.air.short.r', 'grab.high.l', 'grab.high.r',
    'grab.head.front.r', 'grab.head.front.1', 'grab.head.back.l', 'grab.head.back.r', 'grab.back.l', 'grab.back.r', 'grab.stomach.l', 'grab.stomach.r',
    'eat.fast.L', 'eat.fast.R', 'die.front', 'die.back', 'die.headOff', 'handhurt.l', 'handhurt.r', 'sit_hit_eye', 'hit.eye',
    'laugh', 'T-pose', 'sit_hunt_down', 'sit_die', 'die.ground', 'eat.l', 'eat.r', 'hit.eren.L', 'hit.eren.R',
]

# Blender (x, y, z) -> Minecraft (x, z, -y); titan faces -Y in Blender -> +Z in Minecraft
R = mathutils.Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1)))
RI = R.inverted()


def imp(path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.fbx(filepath=path, automatic_bone_orientation=False)
    return [o for o in bpy.data.objects if o not in before]


bpy.ops.wm.read_factory_settings(use_empty=True)
main = imp(IN + '/aottg_TitanMainModel.fbx')
A = [o for o in main if o.type == 'ARMATURE'][0]
meshes = {o.name: o for o in main if o.type == 'MESH'}


def adopt(path, keep_prefixes):
    objs = imp(path)
    for o in objs:
        if o.type == 'MESH' and any(o.name.startswith(p) for p in keep_prefixes):
            mw = o.matrix_world.copy()
            o.parent = A
            o.matrix_world = mw
            mod = next((m for m in o.modifiers if m.type == 'ARMATURE'), None)
            if mod is None:
                mod = o.modifiers.new('arm', 'ARMATURE')
            mod.object = A
            if len(o.vertex_groups) == 0:
                g = o.vertex_groups.new(name='head')
                g.add(list(range(len(o.data.vertices))), 1.0, 'REPLACE')
            meshes[o.name] = o
    for o in objs:
        if o.type == 'ARMATURE' or (o.type == 'MESH' and o.name not in meshes):
            bpy.data.objects.remove(o, do_unlink=True)


for i in range(1, 5):
    adopt(IN + f'/aottg_TitanBody{i}Model.fbx', ['body_'])
for h in (1, 3, 4):
    adopt(IN + f'/aottg_TitanHead{h}Model.fbx', ['head_', 'eyes_'])
hairs = [o for o in imp(IN + '/aottg_HairMasterModel.fbx') if o.type == 'MESH']

# eyes_0 etc. may have no weights either
for name, o in meshes.items():
    if len(o.vertex_groups) == 0:
        g = o.vertex_groups.new(name='head')
        g.add(list(range(len(o.data.vertices))), 1.0, 'REPLACE')

A.data.pose_position = 'REST'
bpy.context.view_layer.update()
dg = bpy.context.evaluated_depsgraph_get()
AWI = A.matrix_world.inverted()


def rest_positions(o):
    ev = o.evaluated_get(dg)
    me = ev.to_mesh()
    pts = [AWI @ (ev.matrix_world @ v.co) for v in me.vertices]
    ev.to_mesh_clear()
    return pts


# ---- fit hair onto each head (hairs share one template head) ----
head_info = {}
for name, o in meshes.items():
    if name.startswith('head_'):
        pts = rest_positions(o)
        xs = [p.x for p in pts]; ys = [p.y for p in pts]; zs = [p.z for p in pts]
        head_info[name] = dict(cx=(min(xs) + max(xs)) / 2, cy=(min(ys) + max(ys)) / 2, top=max(zs), w=max(xs) - min(xs),
                               d=max(ys) - min(ys), h=max(zs) - min(zs))
# hair template box (in hair file space, already Blender world)
hp = [h.matrix_world @ v.co for h in hairs if h.name.startswith(('hair_armin_0', 'hair_boy1_0', 'hair_eren_0')) for v in h.data.vertices]
tmpl = dict(cx=0.0, cy=sum(p.y for p in hp) / len(hp), top=max(p.z for p in hp), w=max(p.x for p in hp) - min(p.x for p in hp))
FIT = json.loads(args[2]) if len(args) > 2 else {}
fit_w = FIT.get('w', 1.12); fit_top = FIT.get('top', 0.06); fit_back = FIT.get('back', 0.05); fit_flip = FIT.get('flip', 1)

A.data.pose_position = 'POSE'
bones = [b for b in A.data.bones]
bone_index = {b.name: i for i, b in enumerate(bones)}


def v3(p):
    q = R @ mathutils.Vector((p.x, p.y, p.z, 1.0))
    return [round(q.x, 5), round(q.y, 5), round(q.z, 5)]


def mesh_entry(o, hair_fit=None):
    me = o.data
    me.calc_loop_triangles()
    if hair_fit is None:
        pts = rest_positions(o)
        groups = {g.index: g.name for g in o.vertex_groups}
        weights = []
        for v in me.vertices:
            ws = [(groups[g.group], g.weight) for g in v.groups if g.weight > 1e-4 and groups[g.group] in bone_index]
            ws.sort(key=lambda t: -t[1]); ws = ws[:4]
            tot = sum(w for _, w in ws) or 1.0
            if not ws:
                ws = [('head', 1.0)]; tot = 1.0
            weights.append([[bone_index[n], round(w / tot, 4)] for n, w in ws])
    else:
        hi, = hair_fit
        s = hi['w'] * fit_w / tmpl['w']
        pts = []
        for v in me.vertices:
            p = o.matrix_world @ v.co
            q = mathutils.Vector(((p.x - tmpl['cx']) * s + hi['cx'],
                                  (p.y - tmpl['cy']) * s * fit_flip + hi['cy'] + fit_back * hi['d'],
                                  (p.z - tmpl['top']) * s + hi['top'] + fit_top * hi['h']))
            pts.append(q)
        weights = [[[bone_index['head'], 1.0]] for _ in me.vertices]
    uv = me.uv_layers.active.data if me.uv_layers else None
    tris = []
    for t in me.loop_triangles:
        for li in t.loops:
            vi = me.loops[li].vertex_index
            u, vv = (uv[li].uv if uv else (0, 0))
            tris.append([vi, round(u, 5), round(vv, 5)])
    return dict(name=o.name, pos=[v3(p) for p in pts], weights=weights, tris=tris,
                material=(me.materials[0].name if me.materials else ''))


out_meshes = [mesh_entry(o) for n, o in sorted(meshes.items())]
for hname in sorted(head_info):
    for h in hairs:
        e = mesh_entry(h, hair_fit=(head_info[hname],))
        e['name'] = h.name + '@' + hname
        out_meshes.append(e)

# ---- animations ----
anim_meta = []
blob = bytearray()
scn = bpy.context.scene
A.animation_data_create()
for an in ANIMS:
    acts = [a for a in bpy.data.actions if a.name.split('|')[-1] == an]
    if not acts:
        print('MISSING', an); continue
    act = acts[0]
    A.animation_data.action = act
    try:
        A.animation_data.action_slot = act.slots[0]
    except Exception:
        pass
    f0, f1 = int(act.frame_range[0]), int(act.frame_range[1])
    frames = list(range(f0, f1 + 1, STEP))
    for f in frames:
        scn.frame_set(f)
        for b in bones:
            pb = A.pose.bones[b.name]
            m = R @ (pb.matrix @ b.matrix_local.inverted()) @ RI
            for r in range(3):
                for c in range(4):
                    blob += struct.pack('>f', m[r][c])
    anim_meta.append(dict(name=an, frames=len(frames)))
    print('ANIM', an, len(frames))

rest_pts = [p for m in out_meshes if m['name'].startswith('body_0') for p in m['pos']]
height = max(p[1] for p in rest_pts) - min(p[1] for p in rest_pts)
model = dict(
    height=height,
    bones=[dict(name=b.name, parent=(bone_index[b.parent.name] if b.parent else -1), head=v3(b.head_local), tail=v3(b.tail_local)) for b in bones],
    meshes=out_meshes,
    anims=anim_meta,
)
json.dump(model, open(OUT + '/titan_model.json', 'w'), separators=(',', ':'))
open(OUT + '/titan_anims.bin', 'wb').write(blob)
print('DONE height', height, 'meshes', len(out_meshes), 'bytes', len(blob))
