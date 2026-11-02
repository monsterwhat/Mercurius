"""Introspección de los DBF del sistema viejo (solo lectura)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vsuper_dbf_to_csv import read_dbf

BASE = Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004")
TABLAS = ["MAESTRO.DBF", "KARDEX.DBF", "ENTRADAS.DBF", "AJUSTESINV.DBF",
          "facturascompras.dbf", "fe_encabezado.DBF", "fe_detalle.dbf", "CLIENTES.DBF"]

for nombre in TABLAS:
    ruta = BASE / nombre
    if not ruta.exists():
        print(f"== {nombre}: NO EXISTE")
        continue
    try:
        fields, rows = read_dbf(ruta)
    except Exception as e:  # noqa: BLE001
        print(f"== {nombre}: ERROR {e}")
        continue
    print(f"== {nombre}: {len(rows)} filas, {len(fields)} campos")
    print("   campos: " + ", ".join(f[0] for f in fields))
    for fila in rows[:2]:
        recorte = {k: v for k, v in fila.items() if v not in (None, "", 0, "0")}
        print("   muestra: " + str(recorte)[:400])
    print()
