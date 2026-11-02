"""Escanea los ZIP de Z:\\Facturas y cuenta claves XML sin extraer nada."""
import re
import sys
import zipfile
from pathlib import Path

RAIZ = Path(r"Z:\Facturas")

total = 0
claves = set()
por_zip = []
for z in sorted(RAIZ.glob("*.zip")):
    try:
        with zipfile.ZipFile(z) as fh:
            nombres = fh.namelist()
    except Exception as e:  # noqa: BLE001
        print(f"{z.name}: ERROR {e}")
        continue
    xmls = [n for n in nombres if n.lower().endswith(".xml")]
    claves_zip = set()
    for n in xmls:
        for c in re.findall(r"\d{40,50}", Path(n).name):
            claves_zip.add(c)
    total += len(xmls)
    claves |= claves_zip
    por_zip.append((z.name, len(xmls), len(claves_zip), round(z.stat().st_size / 1024 / 1024, 1)))

for nombre, xmls, cl, mb in por_zip:
    print(f"{nombre:45s} xml={xmls:6d} claves={cl:6d} {mb:7.1f} MB")
print(f"\nTOTAL xml={total} claves unicas={len(claves)}")

salida = Path(r"F:\Documents\GitHub\Mercurius-clean\build\dbf_reconciliacion\claves_en_zips.txt")
salida.parent.mkdir(parents=True, exist_ok=True)
salida.write_text("\n".join(sorted(claves)), encoding="utf-8")
print(f"claves -> {salida}")
