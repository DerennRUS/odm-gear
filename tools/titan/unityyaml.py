import re, sys, json
def docs(path):
    txt = open(path, encoding='utf-8', errors='ignore').read()
    parts = re.split(r'^--- !u!(\d+) &(\d+).*$', txt, flags=re.M)
    out = {}
    for i in range(1, len(parts), 3):
        out[parts[i+1]] = (int(parts[i]), parts[i+2])
    return out
def field(body, name):
    m = re.search(r'^\s*' + name + r': (.*)$', body, re.M)
    return m.group(1) if m else None
def vec(s):
    return {k: float(v) for k, v in re.findall(r'(\w): ([-0-9.eE]+)', s)}
def transforms(path):
    d = docs(path)
    names = {fid: field(b, 'm_Name') for fid, (t, b) in d.items() if t == 1}
    res = {}
    for fid, (t, b) in d.items():
        if t == 4:
            go = re.search(r'm_GameObject: \{fileID: (\d+)\}', b).group(1)
            res[names.get(go)] = dict(pos=vec(field(b, 'm_LocalPosition')), rot=vec(field(b, 'm_LocalRotation')), scale=vec(field(b, 'm_LocalScale')),
                                      parent=re.search(r'm_Father: \{fileID: (\d+)\}', b).group(1))
    return res
if __name__ == '__main__':
    for p in sys.argv[1:]:
        t = transforms(p)
        print(p.split('/')[-1], json.dumps({k: v for k, v in t.items() if k and ('Hair' in k or 'hair' in k or k in ('head',))}))
