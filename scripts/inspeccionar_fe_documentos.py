"""Introspección de FE_DOCUMENTOS.DBF (los XML de Hacienda)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vsuper_dbf_to_csv import read_dbf

BASE = Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004")
for nombre in ["FE_DOCUMENTOS.DBF", "FE_LOG.DBF"]:
    ruta = BASE / nombre
    if not ruta.exists():
        print(f"== {nombre}: NO EXISTE")
        continue
    fields, rows = read_dbf(ruta)
    print(f"== {nombre}: {len(rows)} filas")
    print("   campos: " + ", ".join(f[0] for f in fields))
    for fila in rows[:2]:
        recorte = {}
        for k, v in fila.items():
            texto = "" if v is None else str(v)
            recorte[k] = (texto[:120] + "...") if len(texto) > 120 else texto
        print("   muestra: " + str(recorte)[:700])
    print()
