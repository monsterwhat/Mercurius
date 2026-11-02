"""Extrae las claves de los XML ya extraídos (nombre de archivo) y las lista."""
import re
from pathlib import Path

RAIZ = Path.home() / "AppData" / "Local" / "Temp" / "opencode" / "facturas_xml" / "extraido"
SALIDA = Path(r"F:\Documents\GitHub\Mercurius-clean\build\dbf_reconciliacion\claves_xml_extraidos.txt")

claves = set()
xmls = 0
for ruta in RAIZ.rglob("*.xml"):
    xmls += 1
    for c in re.findall(r"\d{40,50}", ruta.stem):
        claves.add(c)

SALIDA.parent.mkdir(parents=True, exist_ok=True)
SALIDA.write_text("\n".join(sorted(claves)), encoding="utf-8")
print(f"xml={xmls} claves_unicas={len(claves)} -> {SALIDA}")
