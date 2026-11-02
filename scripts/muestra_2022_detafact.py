import struct, datetime
from collections import defaultdict
from pathlib import Path

RUTA = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido\detafact_respaldo_respaldo.dbf")

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

muestra = []
por_factura = defaultdict(lambda: [0, 0.0, 0.0, 0.0])
for f in regs(RUTA):
    if f["FECHA"][:4] != "2022": continue
    if len(muestra) < 6: muestra.append(f)
    d = por_factura[f["FACTURA"]]
    d[0] += 1
    d[1] += num(f.get("TOTAL"))
    d[2] += num(f.get("VALORUNITA")) * num(f.get("CANTIDAD"))
    d[3] += num(f.get("NMONTDESCU"))

print("muestras:")
for m in muestra:
    print("  ", {k: v for k, v in m.items() if v not in ("", "0", "0.00", "0.000")})
print("\nfacturas:", len(por_factura))
iguales = sum(1 for d in por_factura.values() if abs(d[1]-d[2]) <= 1)
print("facturas donde sum(TOTAL) == sum(VALORUNITA*CANT):", iguales, "de", len(por_factura))
tot1 = sum(d[1] for d in por_factura.values()); tot2 = sum(d[2] for d in por_factura.values())
print(f"total suma TOTAL={tot1:,.0f}  suma VRUNITA*CANT={tot2:,.0f}")
