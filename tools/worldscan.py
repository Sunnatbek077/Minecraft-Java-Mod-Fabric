"""Counts blocks by name in a box of a saved world, read straight from its region files.
   python3 tools/worldscan.py <region dir> x0 z0 x1 z1 y0 y1 name [name...]   (a name may end in * )
Used to check what the mod really put into the world without opening the game."""
import sys, struct, zlib, os, collections

def read_nbt(b):
    pos = [0]
    def rd(fmt, n):
        v = struct.unpack_from(fmt, b, pos[0]); pos[0] += n; return v[0]
    def s():
        n = rd('>H', 2); v = b[pos[0]:pos[0] + n].decode('utf-8', 'replace'); pos[0] += n; return v
    def payload(t):
        if t == 1: return rd('>b', 1)
        if t == 2: return rd('>h', 2)
        if t == 3: return rd('>i', 4)
        if t == 4: return rd('>q', 8)
        if t == 5: return rd('>f', 4)
        if t == 6: return rd('>d', 8)
        if t == 7:
            n = rd('>i', 4); pos[0] += n; return None
        if t == 8: return s()
        if t == 9:
            et = rd('>b', 1); n = rd('>i', 4); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = rd('>b', 1)
                if tt == 0: return d
                k = s(); d[k] = payload(tt)
        if t == 11:
            n = rd('>i', 4); pos[0] += 4 * n; return None
        if t == 12:
            n = rd('>i', 4); v = struct.unpack_from('>%dq' % n, b, pos[0]); pos[0] += 8 * n; return list(v)
        raise ValueError(t)
    t = rd('>b', 1); s(); return payload(t)

def chunk(regdir, cx, cz):
    f = os.path.join(regdir, 'r.%d.%d.mca' % (cx >> 5, cz >> 5))
    if not os.path.exists(f): return None
    data = open(f, 'rb').read()
    i = 4 * ((cx & 31) + (cz & 31) * 32)
    off = struct.unpack('>I', b'\x00' + data[i:i + 3])[0]
    if off == 0: return None
    p = off * 4096; n = struct.unpack('>I', data[p:p + 4])[0]
    return read_nbt(zlib.decompress(data[p + 5:p + 4 + n]))

if __name__ == "__main__":
    regdir = sys.argv[1]; x0, z0, x1, z1, y0, y1 = map(int, sys.argv[2:8]); names = sys.argv[8:]
    def wanted(name):
        return any(name == n or (n.endswith('*') and name.startswith(n[:-1])) or (n.startswith('*') and name.endswith(n[1:])) for n in names)
    tot = collections.Counter()
    for cx in range(x0 >> 4, (x1 >> 4) + 1):
        for cz in range(z0 >> 4, (z1 >> 4) + 1):
            c = chunk(regdir, cx, cz)
            if not c: continue
            for sec in c.get('sections', []):
                sy = sec.get('Y'); bs = sec.get('block_states')
                if bs is None or sy * 16 + 15 < y0 or sy * 16 > y1: continue
                pal = [(p if isinstance(p, str) else next((v for v in p.values() if isinstance(v, str)), '?')).replace('minecraft:', '') for p in bs['palette']]
                data = bs.get('data')
                if not data or not any(wanted(n) for n in pal): continue
                bits = max(4, (len(pal) - 1).bit_length()); per = 64 // bits; mask = (1 << bits) - 1
                for i in range(4096):
                    name = pal[(data[i // per] >> ((i % per) * bits)) & mask]
                    if wanted(name):
                        y = sy * 16 + (i >> 8); z = (cz << 4) + ((i >> 4) & 15); x = (cx << 4) + (i & 15)
                        if x0 <= x <= x1 and z0 <= z <= z1 and y0 <= y <= y1: tot[name] += 1
    print(dict(tot.most_common()))
