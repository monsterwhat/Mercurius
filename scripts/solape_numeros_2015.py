"""¿Los numeros de factura se repiten entre anios? ¿Los faltantes de 2015 son de 2014?"""
import datetime
import struct
from collections import Counter, defaultdict
from pathlib import Path

RUTA = Path(r"E:\VPALMERAS\facturasdiarias.dbf")


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


numeros = defaultdict(set)
rangos = defaultdict(lambda: [None, None])
totales = defaultdict(float)
estados = defaultdict(Counter)
meses = defaultdict(Counter)
for f in registros(RUTA):
    anio = f["FECHA"][:4]
    if not anio:
        continue
    num = f["NUMERO"].lstrip("0") or "0"
    numeros[anio].add(num)
    n = int(num) if num.isdigit() else 0
    r = rangos[anio]
    r[0] = n if r[0] is None else min(r[0], n)
    r[1] = n if r[1] is None else max(r[1], n)
    try:
        totales[anio] += float(f.get("TOTAL") or 0)
    except ValueError:
        pass
    estados[anio][f.get("ESTADO", "")] += 1
    meses[anio][f["FECHA"][5:7]] += 1

print("anio  facturas  rango_numeros          suma_total     estados")
for a in sorted(numeros):
    print(f"{a}  {len(numeros[a]):7d}  {rangos[a][0]}..{rangos[a][1]}  {totales[a]:15,.0f}  {dict(estados[a])}")

print("\n== superposicion de numeros entre anios consecutivos")
anios = sorted(numeros)
for a, b in zip(anios, anios[1:]):
    inter = numeros[a] & numeros[b]
    print(f"   {a} vs {b}: {len(inter)} numeros en comun")

print("\n== facturas por mes (2014-2016)")
for a in ("2014", "2015", "2016"):
    print(f"   {a}: {dict(sorted(meses[a].items()))}")
