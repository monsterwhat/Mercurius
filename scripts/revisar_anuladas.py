"""¿Las ventas que no están en el vivo son facturas ANULADAS del origen?"""
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vsuper_dbf_to_csv import read_dbf

BASE = Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004")

for nombre in ("facturasenero2026.DBF", "facturas.dbf"):
    ruta = BASE / nombre
    _, rows = read_dbf(ruta)
    por_mes = Counter()
    por_estado = Counter()
    for r in rows:
        fecha = (r.get("FECHA") or "").strip()[:10]
        if len(fecha) < 10:
            continue
        mes = fecha[:7]
        estado = (r.get("ESTADO") or "").strip().upper() or "?"
        por_mes[mes] += 1
        if estado != "C":
            por_estado[(mes, estado)] += 1
    print(f"== {nombre}")
    for mes in sorted(por_mes):
        # solo meses 2025-06 en adelante para el primero
        if nombre.startswith("facturasenero") and mes < "2025-06":
            continue
        no_c = {e: c for (m, e), c in por_estado.items() if m == mes}
        print(f"   {mes}: {por_mes[mes]} filas, no-C={no_c}")
    print()
