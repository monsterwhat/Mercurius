"""Detalle de las lineas borradas que SI aparecen en el kardex de su factura."""
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


facturas = {}
for f in regs(E / "facturasdiarias.dbf"):
    if f["FECHA"][:4] not in ("2015", "2016"):
        continue
    facturas[f["NUMERO"].lstrip("0")] = (f["FECHA"], num(f.get("SUBTOTAL")) - num(f.get("DESCUENTO")))

kardex = defaultdict(list)
for f in regs(E / "kardex.DBF"):
    if f.get("MOVIMIENTO") != "FE":
        continue
    kardex[f.get("DOCUMENTO", "").lstrip("0")].append(
        (f.get("CODIGO", ""), abs(num(f.get("CANTIDAD"))), num(f.get("VENTA"))))

mostrados = 0
for f in regs(E / "lineasborradas.dbf"):
    if f["FECHA"][:4] not in ("2015", "2016"):
        continue
    numero = f.get("DOCUMENTO", "").lstrip("0")
    if numero not in facturas or numero not in kardex:
        continue
    codigo = f.get("CODIGO", "")
    cantidad = abs(num(f.get("CANTIDAD")))
    venta = num(f.get("VENTA"))
    lineas = kardex[numero]
    if not any(l[0] == codigo and l[1] == cantidad for l in lineas):
        continue
    fecha, total_fac = facturas[numero]
    suma = sum(v * c for _, c, v in lineas)
    print(f"factura {numero} ({fecha}) total={total_fac:.2f} suma_kardex={suma:.2f}")
    print(f"   linea borrada: articulo={codigo} cant={cantidad} venta={venta:.2f} "
          f"(aporta {venta * cantidad:.2f})")
    print("   lineas del kardex:")
    for c, q, v in lineas:
        marca = " <-- misma linea" if c == codigo and q == cantidad else ""
        print(f"      {c} x{q} a {v:.2f}{marca}")
    mostrados += 1
    print()
    if mostrados >= 5:
        break
