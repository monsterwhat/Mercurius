"""Exporta las ventas del sistema viejo (cabeceras) para comparar mes a mes."""
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vsuper_dbf_to_csv import read_dbf

BASE = Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004")
OUT = Path(__file__).resolve().parent.parent / "build" / "dbf_reconciliacion"
OUT.mkdir(parents=True, exist_ok=True)

FUENTES = [
    ("facturas.dbf", "facturas"),
    ("facturasdiarias.dbf", "diarias"),
    ("facturasenero2026.DBF", "enero2026"),
]

filas = []
for nombre, origen in FUENTES:
    ruta = BASE / nombre
    if not ruta.exists():
        print(f"{nombre}: NO EXISTE")
        continue
    _, rows = read_dbf(ruta)
    n = 0
    for r in rows:
        fecha = (r.get("FECHA") or "").strip()
        if len(fecha) < 10:
            continue
        numero = (r.get("NUMERO") or "").strip()
        total = (r.get("TOTAL") or "0").strip() or "0"
        filas.append([origen, fecha[:10], numero, total])
        n += 1
    print(f"{nombre}: {n} filas exportadas")

destino = OUT / "ventas_fuente.csv"
with destino.open("w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["origen", "fecha", "numero", "total"])
    w.writerows(filas)
print(f"total {len(filas)} -> {destino}")
