"""Resumen de las ventas del respaldo 2019 (E:\\VPALMERAS)."""
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vsuper_dbf_to_csv import read_dbf

RUTA = Path(r"E:\VPALMERAS\facturasdiarias.dbf")
print(f"leyendo {RUTA} ...")
fields, rows = read_dbf(RUTA)
print(f"filas={len(rows)} campos={len(fields)}")
print("campos: " + ", ".join(f[0] for f in fields))
por_anio = Counter()
for r in rows:
    fecha = (r.get("FECHA") or "").strip()
    if len(fecha) >= 4:
        por_anio[fecha[:4]] += 1
for anio in sorted(por_anio):
    print(f"  {anio}: {por_anio[anio]}")
if rows:
    print("muestra: " + str({k: v for k, v in rows[0].items() if v not in (None, '', 0, '0')})[:400])
