"""Años cubiertos por cualquier DBF de ventas (streaming), con sus campos."""
import datetime
import struct
import sys
from collections import Counter
from pathlib import Path

BASE = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido")

ARCHIVOS = [
    BASE / "facturasenero2026.DBF",
    BASE / "facturas.DBF",
    BASE / "facturasdiarias.dbf",
    BASE / "detafact.dbf",
    BASE / "facturas_backup.DBF",
    BASE / "facturas_unicas.DBF",
    BASE / "salidas.DBF",
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


def anio_de(bruto, tipo):
    if tipo == "D":
        intento = bruto.decode("latin-1").strip()
        return intento[:4] if len(intento) >= 4 and intento[:4].isdigit() else None
    if tipo == "T":
        if len(bruto) < 4:
            return None
        juliano = struct.unpack("<I", bruto[:4])[0]
        if juliano <= 0:
            return None
        try:
            return str(datetime.date.fromordinal(juliano - 1721425).year)
        except ValueError:
            return None
    return None


for ruta in ARCHIVOS:
    if not ruta.exists():
        print(f"== {ruta.name}: NO EXISTE")
        continue
    print(f"== {ruta.name} ({round(ruta.stat().st_size / 1024 / 1024, 1)} MB)")
    with ruta.open("rb") as fh:
        num, header_size, record_size, campos = leer_cabecera(fh)
        print("   campos: " + ", ".join(f"{n}({t})" for n, t, _ in campos))
        pos_fecha = tipo_fecha = None
        pos_numero = None
        pos = 1
        for nombre, tipo, largo in campos:
            if nombre.upper() in ("FECHA", "FECHAEMI", "FECHA_EMI"):
                pos_fecha, tipo_fecha = pos, tipo
            if nombre.upper() == "NUMERO":
                pos_numero = (pos, largo)
            pos += largo
        if pos_fecha is None:
            print(f"   filas={num}: sin campo FECHA")
            print()
            continue
        print(f"   filas={num} fecha={tipo_fecha}")
        por_anio = Counter()
        numeros = set()
        fh.seek(header_size)
        for _ in range(num):
            reg = fh.read(record_size)
            if len(reg) != record_size:
                break
            if reg[0:1] == b"\x2a":
                continue
            anio = anio_de(reg[pos_fecha:pos_fecha + (8 if tipo_fecha in ("D", "T") else 10)], tipo_fecha)
            if not anio:
                continue
            por_anio[anio] += 1
            if pos_numero:
                numeros.add((anio, reg[pos_numero[0]:pos_numero[0] + pos_numero[1]].decode("latin-1").strip()))
        distintos = Counter(a for a, _ in numeros)
        for anio in sorted(por_anio):
            print(f"   {anio}: filas={por_anio[anio]:7d}  numeros_distintos={distintos.get(anio, 0):7d}")
    print()
