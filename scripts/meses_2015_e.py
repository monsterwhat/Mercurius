import struct
from collections import defaultdict
from pathlib import Path

def cabecera(fh):
    h = fh.read(32)
    n, hs, rs = struct.unpack("<IHH", h[4:12])
    campos = []
    fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00:
            break
        nom = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        tipo = raw[11:12].decode("latin-1")
        largo = raw[16]
        if nom:
            campos.append((nom, tipo, largo))
    return n, hs, rs, campos

def regs(ruta):
    with ruta.open("rb") as fh:
        n, hs, rs, campos = cabecera(fh)
        fh.seek(hs)
        for _ in range(n):
            r = fh.read(rs)
            if len(r) != rs:
                break
            if r[0:1] == b"\x2a":
                continue
            fila = {}
            pos = 1
            for nom, tipo, largo in campos:
                br = r[pos:pos+largo]; pos += largo
                if tipo == "D":
                    s = br.decode("latin-1").strip()
                    fila[nom] = f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s) == 8 and s.isdigit() else ""
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila

def num(v):
    try: return float(v)
    except (TypeError, ValueError): return 0.0

meses = defaultdict(lambda: [0, 0.0, 0.0])
for f in regs(Path(r"E:\VPALMERAS\facturasdiarias.dbf")):
    if f["FECHA"][:4] != "2015": continue
    m = f["FECHA"][5:7]
    sub = num(f.get("SUBTOTAL")); desc = num(f.get("DESCUENTO"))
    d = meses[m]; d[0] += 1; d[1] += sub; d[2] += sub - desc
print("E: 2015 por mes: filas  subtotal  neto")
tot = 0
for m in sorted(meses):
    d = meses[m]; tot += d[0]
    print(f"  {m}: {d[0]:7d}  {d[1]:15,.0f}  {d[2]:15,.0f}")
print("  total", tot)
