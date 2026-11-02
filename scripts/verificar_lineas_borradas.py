"""¿Las lineas borradas son movimientos de inventario?

Compara, para facturas que existen en el archivo de cabeceras y en el kardex:
  * total de la factura (subtotal - descuento)
  * suma de las lineas del kardex (venta * cantidad)
  * la linea borrada (si aparece o no en el kardex)
"""
import struct
from collections import defaultdict
from pathlib import Path

E = Path(r"E:\VPALMERAS")


def cab(fh):
    h = fh.read(32)
    n, hs, rs = struct.unpack("<IHH", h[4:12])
    campos = []
    fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00:
            break
        nom = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        if nom:
            campos.append((nom, raw[11:12].decode("latin-1"), raw[16]))
    return n, hs, rs, campos


def regs(ruta):
    with ruta.open("rb") as fh:
        n, hs, rs, campos = cab(fh)
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


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


# facturas 2015-2016
facturas = {}
for f in regs(E / "facturasdiarias.dbf"):
    if f["FECHA"][:4] not in ("2015", "2016"):
        continue
    numero = f["NUMERO"].lstrip("0")
    facturas[numero] = (f["FECHA"], num(f.get("SUBTOTAL")) - num(f.get("DESCUENTO")))

# kardex FE por factura
kardex = defaultdict(list)
for f in regs(E / "kardex.DBF"):
    if f.get("MOVIMIENTO") != "FE":
        continue
    kardex[f.get("DOCUMENTO", "").lstrip("0")].append(
        (f.get("CODIGO", ""), abs(num(f.get("CANTIDAD"))), num(f.get("VENTA"))))

print("facturas 2015-2016:", len(facturas), " facturas con kardex:", len(kardex))

# lineas borradas 2015-2016
total = 0
con_factura = 0
en_kardex = 0
suma_borrada_en_kardex = 0.0
ejemplos = []
for f in regs(E / "lineasborradas.dbf"):
    if f["FECHA"][:4] not in ("2015", "2016"):
        continue
    total += 1
    numero = f.get("DOCUMENTO", "").lstrip("0")
    if numero not in facturas:
        continue
    con_factura += 1
    codigo = f.get("CODIGO", "")
    cantidad = abs(num(f.get("CANTIDAD")))
    venta = num(f.get("VENTA"))
    lineas = kardex.get(numero, [])
    # ¿la linea borrada aparece en el kardex de esa factura?
    coincide = [l for l in lineas if l[0] == codigo or (l[1] == cantidad and abs(l[2] - venta) <= 1)]
    if coincide:
        en_kardex += 1
        suma_borrada_en_kardex += venta * cantidad
    if len(ejemplos) < 8:
        fecha, total_fac = facturas[numero]
        suma_kardex = sum(v * c for _, c, v in lineas)
        ejemplos.append({
            "factura": numero, "fecha": fecha, "articulo": codigo,
            "cant": cantidad, "venta": venta,
            "total_factura": round(total_fac, 2),
            "suma_kardex": round(suma_kardex, 2),
            "lineas_kardex": len(lineas),
            "esta_en_kardex": bool(coincide),
        })

print(f"lineas borradas 2015-2016: {total}")
print(f"  con factura existente: {con_factura}")
print(f"  que aparecen en el kardex de esa factura: {en_kardex}")
for e in ejemplos:
    print("  ", e)
