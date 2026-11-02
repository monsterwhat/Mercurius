"""Copia los ZIP de Z:\\Facturas a una carpeta temporal y los extrae ahí.

No escribe nada en Z:. Salida: %TEMP%\\opencode\\facturas_xml\\<nombre-zip>\\...
"""
import shutil
import zipfile
from pathlib import Path

ORIGEN = Path(r"Z:\Facturas")
DESTINO = Path.home() / "AppData" / "Local" / "Temp" / "opencode" / "facturas_xml"
COPIAS = DESTINO / "_zips"
EXTRAIDO = DESTINO / "extraido"
COPIAS.mkdir(parents=True, exist_ok=True)
EXTRAIDO.mkdir(parents=True, exist_ok=True)

zips = sorted(ORIGEN.glob("*.zip"))
print(f"zips en Z: {len(zips)} -> {DESTINO}")

total_xml = 0
for z in zips:
    copia = COPIAS / z.name
    if not copia.exists() or copia.stat().st_size != z.stat().st_size:
        try:
            shutil.copy2(z, copia)
        except Exception as e:  # noqa: BLE001
            print(f"  {z.name}: no se pudo copiar ({e})")
            continue
    carpeta = EXTRAIDO / z.stem
    carpeta.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(copia) as fh:
            fh.extractall(carpeta)
            xmls = sum(1 for n in fh.namelist() if n.lower().endswith(".xml"))
        total_xml += xmls
        print(f"  {z.name}: {xmls} xml -> {carpeta}")
    except Exception as e:  # noqa: BLE001
        print(f"  {z.name}: ERROR al extraer ({e})")

print(f"\nxml extraidos: {total_xml}")
