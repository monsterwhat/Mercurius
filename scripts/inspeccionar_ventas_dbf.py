"""Resumen de las fuentes de VENTAS del sistema viejo (solo lectura)."""
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vsuper_dbf_to_csv import read_dbf

BASE = Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004")
FUENTES = ["facturas.dbf", "facturasdiarias.dbf", "facturasenero2026.DBF", "detafact.dbf"]

for nombre in FUENTES:
    ruta = BASE / nombre
    if not ruta.exists():
        print(f"== {nombre}: NO EXISTE")
        continue
    fields, rows = read_dbf(ruta)
    print(f"== {nombre}: {len(rows)} filas, {len(fields)} campos")
    print("   campos: " + ", ".join(f[0] for f in fields[:20]))
    if rows:
        # rango de fechas sobre los campos que parezcan fecha
        for campo in ("FECHA", "FECHAEMI", "FECHAEMISION", "FECHA_FACT"):
            if campo in rows[0]:
                valores = sorted(r[campo] for r in rows if r.get(campo))
                if valores:
                    print(f"   {campo}: {valores[0]} .. {valores[-1]} ({len(valores)} con valor)")
                break
        print("   muestra: " + str({k: v for k, v in rows[0].items() if v not in (None, '', 0, '0')})[:500])
    print()
