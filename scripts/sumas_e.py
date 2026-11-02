import struct
from collections import defaultdict
from pathlib import Path

def cab(fh):
    h = fh.read(32); n, hs, rs = struct.unpack("<IHH", h[4:12])
    campos = []; fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00: break
        nom = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        if nom: campos.append((nom, raw[11:12].decode("latin-1"), raw[16]))
    return n, hs, rs, campos

def regs(ruta):
    with ruta.open("rb") as fh:
        n, hs, rs, campos = cab(fh); fh.seek(hs)
        for _ in range(n):
            r = fh.read(rs)
            if len(r) != rs: break
            if r[0:1] == b"\x2a": continue
            fila = {}; pos = 1
            for nom, tipo, largo in campos:
                br = r[pos:pos+largo]; pos += largo
                if tipo == "D":
                    s = br.decode("latin-1").strip()
                    fila[nom] = f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s)==8 and s.isdigit() else ""
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila

def num(v):
    try: return float(v)
    except (TypeError, ValueError): return 0.0

d = defaultdict(lambda: [0, 0.0, 0.0])
for f in regs(Path(r"E:\VPALMERAS\facturasdiarias.dbf")):
    a = f["FECHA"][:4]
    if a not in ("2013","2014","2015","2016"): continue
    x = d[a]; x[0] += 1; x[1] += num(f.get("SUBTOTAL")); x[2] += num(f.get("DESCUENTO"))
print("E: por anio: filas  subtotal  descuento  neto")
for a in sorted(d):
    x = d[a]
    print(f"  {a}: {x[0]:7d} {x[1]:16,.0f} {x[2]:13,.0f} {x[1]-x[2]:16,.0f}")
