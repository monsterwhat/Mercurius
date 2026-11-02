"""Cruza las claves faltantes (de fe_encabezado) con los XML disponibles en disco."""
import csv
import re
from pathlib import Path

RAIZ = Path(r"F:\Desktop\WebDAV-Facturacion\extracted")
FALTANTES = Path(r"F:\Documents\GitHub\Mercurius-clean\build\dbf_reconciliacion\fe_encabezado.csv")

claves = []
with FALTANTES.open(encoding="utf-8") as fh:
    for fila in csv.DictReader(fh):
        clave = (fila.get("NUMEROFACT") or "").strip()
        if len(clave) >= 40:
            claves.append(clave)

print(f"claves de compra en el DBF: {len(claves)}")
objetivo = set(claves)

encontrados = {}
xml_total = 0
for ruta in RAIZ.rglob("*.xml"):
    xml_total += 1
    nombre = ruta.stem
    # el nombre puede ser la clave o traerla embebida
    for clave in re.findall(r"\d{40,50}", nombre):
        if clave in objetivo:
            encontrados.setdefault(clave, ruta)

print(f"xml en disco: {xml_total}")
print(f"claves de compra con XML disponible: {len(encontrados)} de {len(objetivo)}")
faltan_sin_xml = objetivo - set(encontrados)
print(f"claves de compra SIN XML: {len(faltan_sin_xml)}")

salida = Path(r"F:\Documents\GitHub\Mercurius-clean\build\dbf_reconciliacion\xml_de_compra.csv")
with salida.open("w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["clave", "archivo"])
    for clave, ruta in sorted(encontrados.items()):
        w.writerow([clave, str(ruta)])
print(f"lista -> {salida}")
for clave, ruta in list(encontrados.items())[:3]:
    print(f"  {clave} -> {ruta.name}")
