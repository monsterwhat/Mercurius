"""Valida que las lineas FE del kardex reconstruyan el total de la factura."""
import datetime
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


# facturas de E: (2015 y 2016)
facturas = {}
for f in registros(Path(r"E:\VPALMERAS\facturasdiarias.dbf")):
    a = f["FECHA"][:4]
    if a not in ("2015", "2016"):
        continue
    num = f["NUMERO"].lstrip("0")
    if not num:
        continue

    def num_(v):
        try:
            return float(v)
        except (TypeError, ValueError):
            return 0.0

    facturas[num] = dict(
        fecha=f["FECHA"],
        subtotal=num_(f.get("SUBTOTAL")),
        descuento=num_(f.get("DESCUENTO")),
        usuario=f.get("USUARIO", ""),
        nombre=f.get("NOMBRE", ""),
        forma_pago=f.get("FORMA_PAGO", ""),
    )
print("facturas E: 2015+2016 =", len(facturas))

# lineas FE del kardex
lineas = defaultdict(list)
for f in registros(Path(r"E:\VPALMERAS\kardex.DBF")):
    if f.get("MOVIMIENTO") != "FE":
        continue
    if f["FECHA"][:4] not in ("2015", "2016", "2018", "2019"):
        continue
    num = f.get("DOCUMENTO", "").lstrip("0")
    if not num:
        continue
    try:
        cant = abs(float(f.get("CANTIDAD") or 0))
        venta = float(f.get("VENTA") or 0)
    except ValueError:
        continue
    lineas[num].append((f["FECHA"], f.get("CODIGO", ""), cant, venta))

print("documentos FE en kardex =", len(lineas))

comunes = [n for n in lineas if n in facturas]
print("documentos en ambos =", len(comunes))

cuadran = 0
dif_min = 0
ejemplos = []
suma_kardex = 0.0
suma_factura = 0.0
for n in comunes:
    k = sum(c * v for _, _, c, v in lineas[n])
    fac = facturas[n]["subtotal"] - facturas[n]["descuento"]
    suma_kardex += k
    suma_factura += fac
    if abs(k - fac) <= 1.0:
        cuadran += 1
    else:
        dif_min += 1
        if len(ejemplos) < 8:
            ejemplos.append((n, facturas[n]["fecha"], round(k, 2), round(fac, 2), round(fac - k, 2), len(lineas[n])))

print(f"cuadran (±1) = {cuadran}  no cuadran = {dif_min}")
print(f"suma kardex = {suma_kardex:,.0f}   suma facturas = {suma_factura:,.0f}")
print("ejemplos que no cuadran (numero, fecha, kardex, factura, dif, lineas):")
for e in ejemplos:
    print("   ", e)

# cobertura: cuantas facturas de 2015/2016 tienen lineas
por_anio = defaultdict(lambda: [0, 0])
for n, datos in facturas.items():
    a = datos["fecha"][:4]
    por_anio[a][0] += 1
    if n in lineas:
        por_anio[a][1] += 1
print("\ncobertura por anio del archivo de facturas:")
for a in sorted(por_anio):
    print(f"   {a}: facturas={por_anio[a][0]:7d} con_lineas={por_anio[a][1]:7d}")
