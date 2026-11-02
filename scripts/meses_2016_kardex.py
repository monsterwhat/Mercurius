import struct
from collections import Counter
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

meses = Counter(); docs = Counter(); maxf = ""
for f in regs(Path(r"E:\VPALMERAS\kardex.DBF")):
    if f.get("MOVIMIENTO") != "FE": continue
    if f["FECHA"][:4] != "2016": continue
    m = f["FECHA"][:7]
    meses[m] += 1
    docs[m] += 1
    maxf = max(maxf, f["FECHA"])
print("kardex FE 2016 por mes (lineas):")
for m in sorted(meses): print("  ", m, meses[m])
print("  fecha maxima:", maxf)

# contar facturas distintas por mes
por_mes = Counter()
vistos = {}
for f in regs(Path(r"E:\VPALMERAS\kardex.DBF")):
    if f.get("MOVIMIENTO") != "FE" or f["FECHA"][:4] != "2016": continue
    d = f.get("DOCUMENTO","").lstrip("0")
    if d not in vistos:
        vistos[d] = f["FECHA"][:7]
        por_mes[f["FECHA"][:7]] += 1
print("facturas distintas por mes:")
for m in sorted(por_mes): print("  ", m, por_mes[m])
