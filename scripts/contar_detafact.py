"""Cuenta líneas por año en los respaldos grandes de DETAFACT (streaming)."""
import struct
from collections import Counter
from pathlib import Path

ARCHIVOS = [
    Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004\detafact_respaldo_respaldo.dbf"),
    Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004\detafactbk27022026.DBF"),
    Path(r"E:\VPALMERAS\DETAFACT.DBF"),
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


def filas(ruta):
    with ruta.open("rb") as fh:
        num, header_size, record_size, campos = leer_cabecera(fh)
        fh.seek(header_size)
        for _ in range(num):
            registro = fh.read(record_size)
            if len(registro) != record_size:
                break
            if registro[0:1] == b"\x2a":
                continue
            fila = {}
            pos = 1
            for nombre, _tipo, largo in campos:
                fila[nombre] = registro[pos:pos + largo].decode("latin-1").strip()
                pos += largo
            yield fila


for ruta in ARCHIVOS:
    if not ruta.exists():
        print(f"== {ruta.name}: NO EXISTE")
        continue
    print(f"== {ruta.name} ({round(ruta.stat().st_size/1024/1024,1)} MB)")
    por_anio = Counter()
    total = 0
    for fila in filas(ruta):
        total += 1
        fecha = fila.get("FECHA", "")
        if len(fecha) >= 4:
            por_anio[fecha[:4]] += 1
    print(f"   filas leídas: {total}")
    for anio in sorted(por_anio):
        print(f"     {anio}: {por_anio[anio]}")
    print()
