"""¿Las ventas de 2015 en el respaldo 2019 tienen duplicados?"""
import struct
from collections import Counter, defaultdict
from pathlib import Path

RUTA = Path(r"E:\VPALMERAS\facturasdiarias.dbf")


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


por_anio = Counter()
numeros = defaultdict(set)
for fila in filas(RUTA):
    fecha = fila.get("FECHA", "")
    if len(fecha) < 4:
        continue
    anio = fecha[:4]
    por_anio[anio] += 1
    numeros[anio].add(fila.get("NUMERO", ""))

print("anio  filas  numeros_distintos  duplicados")
for anio in sorted(por_anio):
    filas_n = por_anio[anio]
    distintos = len(numeros[anio])
    print(f"{anio}  {filas_n:7d}  {distintos:7d}  {filas_n - distintos:7d}")
