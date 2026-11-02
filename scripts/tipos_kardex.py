"""Que significa cada codigo MOVIMIENTO del kardex y como se liga a la factura."""
import datetime
import struct
from collections import Counter, defaultdict
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


def registros(ruta):
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
                br = r[pos:pos + largo]
                pos += largo
                if tipo == "D":
                    s = br.decode("latin-1").strip()
                    fila[nom] = f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s) == 8 and s.isdigit() else ""
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila


por_tipo = defaultdict(lambda: {"filas": 0, "docs": set(), "neg": 0, "pos": 0, "cero": 0, "muestra": []})
for f in registros(Path(r"E:\VPALMERAS\kardex.DBF")):
    a = f["FECHA"][:4]
    if a not in ("2015", "2016"):
        continue
    clave = (a, f.get("MOVIMIENTO", ""))
    d = por_tipo[clave]
    d["filas"] += 1
    d["docs"].add(f.get("DOCUMENTO", "").lstrip("0") or "0")
    try:
        c = float(f.get("CANTIDAD") or 0)
        if c < 0:
            d["neg"] += 1
        elif c > 0:
            d["pos"] += 1
        else:
            d["cero"] += 1
    except ValueError:
        d["cero"] += 1
    if len(d["muestra"]) < 2:
        d["muestra"].append({k: f.get(k) for k in ("FECHA", "MOVIMIENTO", "DOCUMENTO", "CODIGO", "CANTIDAD", "VENTA", "COSTO", "SALDO", "USUARIO")})

for clave in sorted(por_tipo):
    d = por_tipo[clave]
    print(f"{clave}: filas={d['filas']} docs={len(d['docs'])} cant>0={d['pos']} cant<0={d['neg']} cant=0={d['cero']}")
    for m in d["muestra"]:
        print("     ", m)

# cuantas FE de 2016 tienen documento dentro del rango de facturas 2016
print("\n== FE 2016: rango de documentos")
docs = sorted(int(x) for x in por_tipo[("2016", "FE")]["docs"] if x.isdigit())
print("   min", docs[0], "max", docs[-1], "total", len(docs))
docs15 = sorted(int(x) for x in por_tipo[("2015", "FE")]["docs"] if x.isdigit())
print("== FE 2015: min", docs15[0], "max", docs15[-1], "total", len(docs15))
