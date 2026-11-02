"""Muestra filas completas de facturasdiarias y kardex para ver que campos traen datos."""
import datetime
import struct
from collections import Counter
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
                elif tipo == "T":
                    j = struct.unpack("<I", br[:4])[0] if len(br) >= 4 else 0
                    try:
                        fila[nom] = datetime.date.fromordinal(j - 1721425).isoformat() if j > 0 else ""
                    except ValueError:
                        fila[nom] = ""
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila


print("===== facturasdiarias: 3 filas de 2015")
n = 0
for f in registros(Path(r"E:\VPALMERAS\facturasdiarias.dbf")):
    if f["FECHA"][:4] == "2015":
        print(f)
        n += 1
        if n >= 3:
            break

print("\n===== facturasdiarias: cuantos tienen TOTAL/SUBTOTAL/I_VENTAS vacios (2015)")
vacios = Counter()
total_filas = 0
for f in registros(Path(r"E:\VPALMERAS\facturasdiarias.dbf")):
    if f["FECHA"][:4] != "2015":
        continue
    total_filas += 1
    for campo in ("TOTAL", "SUBTOTAL", "I_VENTAS", "TOTALEXENT", "TOTALGRABA", "NSALDO", "DESCUENTO"):
        if not f.get(campo):
            vacios[campo] += 1
print("   filas 2015:", total_filas, "vacios:", dict(vacios))

print("\n===== kardex: 5 filas de 2015 con movimiento S")
n = 0
for f in registros(Path(r"E:\VPALMERAS\kardex.DBF")):
    if f["FECHA"][:4] == "2015" and f.get("MOVIMIENTO") == "S":
        print(f)
        n += 1
        if n >= 5:
            break

print("\n===== kardex: distribucion MOVIMIENTO / TIPODOCUME por anio")
c = Counter()
vacios_k = Counter()
for f in registros(Path(r"E:\VPALMERAS\kardex.DBF")):
    a = f["FECHA"][:4]
    if not a:
        continue
    c[(a, f.get("MOVIMIENTO"), f.get("TIPODOCUME"))] += 1
    for campo in ("VENTA", "COSTO", "CANTIDAD", "DOCUMENTO"):
        if not f.get(campo):
            vacios_k[(a, campo)] += 1
for clave in sorted(c):
    print(f"   {clave}: {c[clave]}")
print("   vacios:", dict(sorted(vacios_k.items())))
