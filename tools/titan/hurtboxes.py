import re, sys, json, numpy as np
sys.path.append('/tmp/claude-0/titan/scripts')
from unityyaml import docs, field, vec
d = docs(sys.argv[1])
names = {fid: field(b, 'm_Name') for fid, (t, b) in d.items() if t == 1}
tr = {}
go2tr = {}
for fid, (t, b) in d.items():
    if t == 4:
        go = re.search(r'm_GameObject: \{fileID: (\d+)\}', b).group(1)
        tr[fid] = dict(go=go, name=names.get(go), pos=vec(field(b, 'm_LocalPosition')), rot=vec(field(b, 'm_LocalRotation')),
                       scale=vec(field(b, 'm_LocalScale')), parent=re.search(r'm_Father: \{fileID: (\d+)\}', b).group(1))
        go2tr[go] = fid
def qm(q):
    x, y, z, w = q['x'], q['y'], q['z'], q['w']
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
def world(fid):
    t = tr[fid]
    M = np.eye(4)
    M[:3, :3] = qm(t['rot']) @ np.diag([t['scale']['x'], t['scale']['y'], t['scale']['z']])
    M[:3, 3] = [t['pos']['x'], t['pos']['y'], t['pos']['z']]
    if t['parent'] != '0' and t['parent'] in tr:
        return world(t['parent']) @ M
    return M
def path(fid):
    t = tr[fid]; s = t['name']
    while t['parent'] != '0' and t['parent'] in tr:
        t = tr[t['parent']]; s = t['name'] + '/' + s
    return s
out = {}
for fid, (t, b) in d.items():
    if t in (135, 136, 65):
        go = re.search(r'm_GameObject: \{fileID: (\d+)\}', b).group(1)
        n = names.get(go)
        if not n or not ('box' in n.lower()): continue
        W = world(go2tr[go])
        c = vec(field(b, 'm_Center'))
        cw = W @ np.array([c['x'], c['y'], c['z'], 1])
        r = field(b, 'm_Radius'); h = field(b, 'm_Height'); sz = field(b, 'm_Size'); dr = field(b, 'm_Direction')
        sc = np.linalg.norm(W[:3, :3], axis=0)
        out[n] = dict(type=t, path=path(go2tr[go]), center=cw[:3].round(4).tolist(), radius=r, height=h, size=sz, dir=dr, scale=sc.round(4).tolist(),
                      axes=W[:3,:3].round(3).tolist())
bones = {}
for fid, t in tr.items():
    if t['name'] in ('neck', 'head', 'hand.L', 'hand.R', 'foot.L', 'foot.R', 'chest', 'hip', 'forearm.L', 'shin.L', 'thigh.L', 'upper_arm.L', 'Core'):
        bones[t['name']] = (world(fid) @ np.array([0, 0, 0, 1]))[:3].round(4).tolist()
print(json.dumps(dict(boxes=out, bones=bones), indent=1))
