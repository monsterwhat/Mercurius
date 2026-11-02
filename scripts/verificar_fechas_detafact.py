"""Verifica la decodificación de fechas T (binarias) cruzando con facturas.DBF."""
import datetime
import struct
from pathlib import Path

DETALLE = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido\detafactbk27022026.DBF")
FACTURAS = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido\facturas.DBF")


def leer_cabecera(fh):
    header = fh.read(32)
    num, header_size, record_size = struct.unpack("<IHH", header[4:12])
    campos = []
    fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00:
            break
        nombre = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        tipo = raw[11:12].decode("latin-1")
        largo = raw[16]
        if nombre:
            campos.append((nombre, tipo, largo))
    return num, header_size, record_size, campos


def decodificar(reg, campos):
    fila = {}
    pos = 1
    for nombre, tipo, largo in campos:
        bruto = reg[pos:pos + largo]
        pos += largo
        if tipo == "T":
            juliano = struct.unpack("<I", bruto[:4])[0]
            try:
                fila[nombre] = (datetime.date.fromordinal(juliano - 1721425).isoformat()
                                if juliano > 0 else "")
            except ValueError:
                fila[nombre] = ""
        else:
            fila[nombre] = bruto.decode("latin-1").strip()
    return fila


# 1) muestra de líneas del respaldo con fecha decodificada
muestras = []
with DETALLE.open("rb") as fh:
    num, header_size, record_size, campos = leer_cabecera(fh)
    fh.seek(header_size)
    for _ in range(num):
        reg = fh.read(record_size)
        if len(reg) != record_size:
            break
        if reg[0:1] == b"\x2a":
            continue
        fila = decodificar(reg, campos)
        if fila.get("FECHA"):
            muestras.append(fila)
            if len(muestras) >= 5:
                break

print("== primeras 5 líneas de detafactbk27022026:")
for f in muestras:
    print(f"   factura={f.get('FACTURA')} fecha={f.get('FECHA')} articulo={f.get('DESCRIP')[:30]!r}")

# 2) buscar esas facturas en facturas.DBF y comparar la fecha
objetivo = {f.get("FACTURA") for f in muestras}
with FACTURAS.open("rb") as fh:
    num, header_size, record_size, campos = leer_cabecera(fh)
    fh.seek(header_size)
    encontradas = 0
    for _ in range(num):
        reg = fh.read(record_size)
        if len(reg) != record_size:
            break
        if reg[0:1] == b"\x2a":
            continue
        fila = decodificar(reg, campos)
        if fila.get("NUMERO") in objetivo:
            print(f"   facturas.DBF: numero={fila.get('NUMERO')} fecha={fila.get('FECHA')}")
            encontradas += 1
            if encontradas >= 5:
                break
