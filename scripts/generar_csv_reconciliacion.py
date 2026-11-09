"""Genera los CSV de reconciliación desde los DBF de vSuper (solo lectura).

No toca la base de datos: deja archivos en build/dbf_reconciliacion/ para que el
servidor los cargue en tablas de staging y compare contra la base viva.

Claves naturales usadas (verificables, no ids internos):
  articulos   -> codigobarra (MAESTRO.CODIGO)
  stock       -> codigobarra
  inventario  -> (articulo, fecha, tipo, documento, cantidad)
  compras     -> clave de Hacienda (fe_encabezado.NUMEROFACT, 50 digitos)
"""
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vsuper_dbf_to_csv import read_dbf

BASE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004")
OUT = Path(__file__).resolve().parent.parent / "build" / "dbf_reconciliacion"
OUT.mkdir(parents=True, exist_ok=True)


def limpio(valor):
    if valor is None:
        return ""
    if isinstance(valor, bytes):
        return ""
    texto = str(valor).strip()
    return "" if texto in ("None",) else texto


def escribir(nombre, campos, filas):
    destino = OUT / nombre
    with destino.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(campos)
        for fila in filas:
            writer.writerow([limpio(fila.get(c)) for c in campos])
    print(f"{nombre}: {len(filas)} filas -> {destino}")


def main():
    # ── ARTÍCULOS ────────────────────────────────────────────────────────
    _, maestro = read_dbf(BASE / "MAESTRO.DBF")
    escribir("maestro.csv",
             ["CODIGO", "NOMBRE", "CODIGOCABY", "FAMILIA", "CANTIDAD",
              "COSTO", "MARGENUTIL", "VENTA", "EXENTO", "PORCEIMP",
              "ULTIMAENTR", "ULTIMASALI", "STOCKMIN", "STOCKMAX"],
             maestro)

    # ── MOVIMIENTOS ──────────────────────────────────────────────────────
    _, kardex = read_dbf(BASE / "KARDEX.DBF")
    escribir("kardex.csv",
             ["CODIGO", "FECHA", "MOVIMIENTO", "DOCUMENTO", "CANTIDAD",
              "USUARIO", "COSTO", "VENTA", "TIPODOCUME", "CONTROL",
              "PROVEEDOR", "SALDO"],
             kardex)

    _, entradas = read_dbf(BASE / "ENTRADAS.DBF")
    escribir("entradas.csv",
             ["CODIGO", "FECHA", "DOCUMENTO", "CANTIDAD", "COSTO",
              "PROV", "CODPROV", "DETALLE", "USUARIO"],
             entradas)

    _, ajustes = read_dbf(BASE / "AJUSTESINV.DBF")
    escribir("ajustes.csv",
             ["DOCUMENTO", "FECHA", "USUARIO", "TIPOAJUSTE", "MONTO"],
             ajustes)

    # ── FACTURAS DE COMPRA ELECTRÓNICAS (clave de Hacienda) ──────────────
    _, fe_enc = read_dbf(BASE / "fe_encabezado.DBF")
    escribir("fe_encabezado.csv",
             ["NUMEROFACT", "NOMBREEMIS", "CEDULAEMIS", "SUBTOTAL",
              "TOTALDESC", "TOTALEXENT", "TOTALIVA13", "TOTALIVA8",
              "TOTALIVA2", "TOTALIVA4", "TOTALIVA1", "TOTALDOC"],
             fe_enc)

    _, fe_det = read_dbf(BASE / "fe_detalle.dbf")
    escribir("fe_detalle.csv",
             ["NUMEROFACT", "EMISOR", "CODIGO", "CODIGOCABY", "DESCRIPCIO",
              "CANTIDAD", "PRECIO_UNI", "MONTOTOTAL", "SUBTOTAL",
              "CODIGOIMPU", "CODIGOTARI", "TARIFA", "MONTOIMPUE"],
             fe_det)

    # ── FACTURAS DE COMPRA LEGADAS (cuentas por pagar, sin clave) ────────
    _, compras = read_dbf(BASE / "facturascompras.dbf")
    escribir("facturascompras.csv",
             ["PROVEEDOR", "NOMBRE", "FECHA", "FACTURA", "SUBTOTAL",
              "DESCUENTO", "TOTAL", "SALDOACTUA", "ESTADO", "USUARIO"],
             compras)


if __name__ == "__main__":
    main()
