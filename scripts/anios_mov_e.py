import struct, datetime
from collections import Counter
from pathlib import Path
import sys
sys.path.insert(0, "scripts")

def cab(fh):
    h = fh.read(32)
    n, hs, rs = struct.unpack("<IHH", h[4:12])
    cs = []
    fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00:
            break
        nom = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        t = raw[11:12].decode("latin-1"); l = raw[16]
        if nom: cs.append((nom, t, l))
    return n, hs, rs, cs

for ruta in [Path(r"E:\VPALMERAS\kardex.DBF"), Path(r"E:\VPALMERAS\salidas.dbf"), Path(r"E:\VPALMERAS\entradas.dbf")]:
    print("==", ruta.name, round(ruta.stat().st_size/1024/1024,1), "MB")
    with ruta.open("rb") as fh:
        n, hs, rs, cs = cab(fh)
        print("   campos:", ", ".join(f"{a}({b})" for a,b,_ in cs))
        pos = 1; pf = None; tf = None
        for a,b,l in cs:
            if a.upper() in ("FECHA","FECHA_MOV","FECHAMOV"):
                pf, tf = pos, b
            pos += l
        c = Counter()
        fh.seek(hs)
        for _ in range(n):
            r = fh.read(rs)
            if len(r) != rs: break
            if r[0:1] == b"\x2a": continue
            if pf is None: continue
            br = r[pf:pf+8]
            if tf == "D":
                s = br.decode("latin-1").strip()
                if len(s) >= 4 and s[:4].isdigit(): c[s[:4]] += 1
            elif tf == "T":
                j = struct.unpack("<I", br[:4])[0]
                if j > 0:
                    try: c[str(datetime.date.fromordinal(j-1721425).year)] += 1
                    except ValueError: pass
        print("   filas:", n, "anios:", dict(sorted(c.items())))
    print()
