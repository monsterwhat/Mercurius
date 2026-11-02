"""Detalle de detafact_respaldo_respaldo.dbf por año: rango de facturas y muestra."""
import datetime
import struct
from collections import Counter, defaultdict
from pathlib import Path

RUTAS = [
    Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido\detafact_respaldo_respaldo.dbf"),
    Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido\detafactbk27022026.DBF"),
]


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


def fecha_de(bruto, tipo):
    if tipo == "D":
        intento = bruto.decode("latin-1").strip()
        if len(intento) == 8 and intento.isdigit():
            return datetime.date(int(intento[:4]), int(intento[4:6]), int(intento[6:8]))
        return None
    if tipo == "T":
        juliano = struct.unpack("<I", bruto[:4])[0]
        if juliano <= 0:
            return None
        try:
            return datetime.date.fromordinal(juliano - 1721425)
        except ValueError:
            return None
    return None


for ruta in RUTAS:
    if not ruta.exists():
        print(f"== {ruta.name}: NO EXISTE")
        continue
    print(f"== {ruta.name}")
    with ruta.open("rb") as fh:
        num, header_size, record_size, campos = leer_cabecera(fh)
        pos_fecha = pos_factura = None
        tipo_fecha = None
        pos = 1
        for nombre, tipo, largo in campos:
            if nombre == "FECHA":
                pos_fecha, tipo_fecha = pos, tipo
            if nombre == "FACTURA":
                pos_factura = (pos, largo)
            pos += largo
        print(f"   filas={num} fecha={tipo_fecha}")
        rangos = defaultdict(lambda: [None, None, set()])
        fh.seek(header_size)
        for _ in range(num):
            reg = fh.read(record_size)
            if len(reg) != record_size:
                break
            if reg[0:1] == b"\x2a":
                continue
            fecha = fecha_de(reg[pos_fecha:pos_fecha + 8], tipo_fecha)
            if fecha is None:
                continue
            factura = reg[pos_factura[0]:pos_factura[0] + pos_factura[1]].decode("latin-1").strip()
            if not factura:
                continue
            datos = rangos[fecha.year]
            numero = int(factura)
            datos[0] = numero if datos[0] is None else min(datos[0], numero)
            datos[1] = numero if datos[1] is None else max(datos[1], numero)
            datos[2].add(numero)
        for anio in sorted(rangos):
            minimo, maximo, conjunto = rangos[anio]
            print(f"   {anio}: facturas {minimo}..{maximo}  distintas={len(conjunto)}")
    print()
