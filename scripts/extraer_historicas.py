"""Extrae las ventas y lineas historicas para reconstruir la base.

Fuentes:
  E:\\VPALMERAS\\facturasdiarias.dbf  -> cabeceras 2013-2019
  E:\\VPALMERAS\\kardex.DBF            -> lineas y movimientos 2015-2019
  detafact_respaldo_respaldo.dbf      -> lineas de 2022 (sin cabeceras en ningun lado)
  salidas.DBF (snapshot)              -> movimientos de inventario 2022-2026
  entradas.dbf (E:)                   -> entradas de inventario 2010-2016

Salida: build/dbf_reconciliacion/*.csv listos para psql \\copy.
"""
import csv
import datetime
import struct
import sys
from pathlib import Path

SALIDA = Path(__file__).resolve().parent.parent / "build" / "dbf_reconciliacion"
SALIDA.mkdir(parents=True, exist_ok=True)

E = Path(r"E:\VPALMERAS")
SNAP = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido")


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


def escribir(nombre, encabezado, filas):
    destino = SALIDA / nombre
    with destino.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, quoting=csv.QUOTE_MINIMAL)
        w.writerow(encabezado)
        n = 0
        for fila in filas:
            w.writerow(fila)
            n += 1
    print(f"  {nombre}: {n} filas -> {destino}")


def cabeceras_ventas():
    campos = ["numero", "fecha", "nombre", "cliente", "subtotal", "descuento",
              "forma_pago", "estado", "usuario", "cajero", "vende"]
    for f in registros(E / "facturasdiarias.dbf"):
        anio = f["FECHA"][:4]
        if anio not in ("2013", "2014", "2015", "2016", "2018", "2019"):
            continue
        yield [f.get("NUMERO", ""), f["FECHA"], f.get("NOMBRE", ""), f.get("CLIENTE", ""),
               f.get("SUBTOTAL", ""), f.get("DESCUENTO", ""), f.get("FORMA_PAGO", ""),
               f.get("ESTADO", ""), f.get("USUARIO", ""), f.get("CAJERO", ""), f.get("VENDE", "")]


def lineas_kardex():
    campos = ["movimiento", "documento", "fecha", "codigo", "cantidad", "venta", "costo",
              "usuario", "saldo", "exento"]
    for f in registros(E / "kardex.DBF"):
        if f["FECHA"][:4] not in ("2015", "2016", "2018", "2019"):
            continue
        yield [f.get("MOVIMIENTO", ""), f.get("DOCUMENTO", ""), f["FECHA"], f.get("CODIGO", ""),
               f.get("CANTIDAD", ""), f.get("VENTA", ""), f.get("COSTO", ""),
               f.get("USUARIO", ""), f.get("SALDO", ""), f.get("EXENTO", "")]


def lineas_2022():
    campos = ["factura", "fecha", "codigo", "codigocaby", "descrip", "cantidad", "descuento",
              "valorunita", "total", "familia", "exento", "porceimp", "imp", "usuario",
              "nlinea", "cservicios", "unidad"]
    ruta = SNAP / "detafact_respaldo_respaldo.dbf"
    for f in registros(ruta):
        if f["FECHA"][:4] != "2022":
            continue
        yield [f.get("FACTURA", ""), f["FECHA"], f.get("CODIGO", ""), f.get("CODIGOCABY", ""),
               f.get("DESCRIP", ""), f.get("CANTIDAD", ""), f.get("DESCUENTO", ""),
               f.get("VALORUNITA", ""), f.get("TOTAL", ""), f.get("FAMILIA", ""),
               f.get("EXENTO", ""), f.get("PORCEIMP", ""), f.get("IMP", ""),
               f.get("USUARIO", ""), f.get("NNUMELINEA", ""), f.get("CSERVICIOS", ""),
               f.get("CUNIDADGTD", "")]


def movimientos_salidas():
    campos = ["documento", "fecha", "codigo", "cantidad", "ubicacion", "pventa", "venta_imp",
              "usuario", "exento", "detalle"]
    ruta = SNAP / "salidas.DBF"
    for f in registros(ruta):
        if f["FECHA"][:4] not in ("2022", "2023", "2024", "2025", "2026"):
            continue
        yield [f.get("DOCUMENTO", ""), f["FECHA"], f.get("CODIGO", ""), f.get("CANTIDAD", ""),
               f.get("UBICACION", ""), f.get("PVENTA1", ""), f.get("VENTAIMP", ""),
               f.get("USUARIO", ""), f.get("EXENTO", ""), f.get("DETALLE", "")]


def movimientos_entradas():
    campos = ["documento", "fecha", "codigo", "cantidad", "costo", "prov", "codprov",
              "usuario", "detalle"]
    for f in registros(E / "entradas.dbf"):
        yield [f.get("DOCUMENTO", ""), f["FECHA"], f.get("CODIGO", ""), f.get("CANTIDAD", ""),
               f.get("COSTO", ""), f.get("PROV", ""), f.get("CODPROV", ""),
               f.get("USUARIO", ""), f.get("DETALLE", "")]


def cabeceras_modernas():
    """Cabeceras reales de 2023-2026 (todas las copias de facturas del snapshot)."""
    fuentes = [
        ("facturasbk22042024noborrar.DBF", "bk2024"),
        ("facturasenero2026.DBF", "enero2026"),
        ("facturas.DBF", "facturas_snap"),
        ("facturasdiarias.dbf", "diarias_snap"),
    ]
    for nombre, origen in fuentes:
        ruta = SNAP / nombre
        if not ruta.exists():
            print(f"  {nombre}: NO EXISTE")
            continue
        n = 0
        for f in registros(ruta):
            if f["FECHA"][:4] not in ("2023", "2024", "2025", "2026"):
                continue
            n += 1
            yield [n, f.get("NUMERO", ""), f["FECHA"], f.get("NOMBRE", ""), f.get("CLIENTE", ""),
                   f.get("SUBTOTAL", ""), f.get("DESCUENTO", ""), f.get("TOTAL", ""),
                   f.get("TOTALEXENT", ""), f.get("TOTALNOEXE", ""), f.get("FORMA_PAGO", ""),
                   f.get("ESTADO", ""), f.get("USUARIO", ""), origen]
        print(f"  {nombre}: {n} filas")


def lineas_modernas():
    """Lineas de detalle de 2023-2026 (union de todos los detafact del snapshot)."""
    fuentes = [
        ("detafact_respaldo_respaldo.dbf", ("2023", "2024")),
        ("detafactbk27022026.DBF", ("2024", "2025", "2026")),
        ("detafact.dbf", ("2026",)),
    ]
    for nombre, anios in fuentes:
        ruta = SNAP / nombre
        if not ruta.exists():
            print(f"  {nombre}: NO EXISTE")
            continue
        n = 0
        for f in registros(ruta):
            if f["FECHA"][:4] not in anios:
                continue
            n += 1
            yield [n, f.get("FACTURA", ""), f["FECHA"], f.get("CODIGO", ""), f.get("CODIGOCABY", ""),
                   f.get("DESCRIP", ""), f.get("CANTIDAD", ""), f.get("DESCUENTO", ""),
                   f.get("VALORUNITA", ""), f.get("TOTAL", ""), f.get("FAMILIA", ""),
                   f.get("EXENTO", ""), f.get("PORCEIMP", ""), f.get("IMP", ""),
                   f.get("USUARIO", ""), f.get("NNUMELINEA", ""), f.get("CSERVICIOS", ""),
                   f.get("CUNIDADGTD", ""), nombre]
        print(f"  {nombre}: {n} lineas")



if __name__ == "__main__":
    que = sys.argv[1] if len(sys.argv) > 1 else "todo"
    if que in ("todo", "ventas"):
        print("cabeceras E:")
        escribir("ventas_e.csv",
                 ["numero", "fecha", "nombre", "cliente", "subtotal", "descuento",
                  "forma_pago", "estado", "usuario", "cajero", "vende"], cabeceras_ventas())
    if que in ("todo", "kardex"):
        print("kardex E:")
        escribir("kardex_e.csv",
                 ["movimiento", "documento", "fecha", "codigo", "cantidad", "venta", "costo",
                  "usuario", "saldo", "exento"], lineas_kardex())
    if que in ("todo", "2022"):
        print("lineas 2022:")
        escribir("detafact_2022.csv",
                 ["factura", "fecha", "codigo", "codigocaby", "descrip", "cantidad", "descuento",
                  "valorunita", "total", "familia", "exento", "porceimp", "imp", "usuario",
                  "nlinea", "cservicios", "unidad"], lineas_2022())
    if que in ("todo", "salidas"):
        print("movimientos salidas:")
        escribir("salidas_mov.csv",
                 ["documento", "fecha", "codigo", "cantidad", "ubicacion", "pventa", "venta_imp",
                  "usuario", "exento", "detalle"], movimientos_salidas())
    if que in ("todo", "entradas"):
        print("entradas E:")
        escribir("entradas_mov.csv",
                 ["documento", "fecha", "codigo", "cantidad", "costo", "prov", "codprov",
                  "usuario", "detalle"], movimientos_entradas())
    if que in ("todo", "modernas"):
        print("cabeceras modernas:")
        escribir("ventas_mod.csv",
                 ["fila", "numero", "fecha", "nombre", "cliente", "subtotal", "descuento", "total",
                  "totalexent", "totalnoexe", "forma_pago", "estado", "usuario", "origen"],
                 cabeceras_modernas())
    if que in ("todo", "lineas_mod"):
        print("lineas modernas:")
        escribir("lineas_mod.csv",
                 ["fila", "factura", "fecha", "codigo", "codigocaby", "descrip", "cantidad", "descuento",
                  "valorunita", "total", "familia", "exento", "porceimp", "imp", "usuario",
                  "nlinea", "cservicios", "unidad", "origen"], lineas_modernas())
