"""Estructura y años de detafactbk27022026.DBF (campos por tipo)."""
import struct
from collections import Counter
from pathlib import Path

RUTA = Path(r"F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004\detafactbk27022026.DBF")

with RUTA.open("rb") as fh:
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
    print(f"filas={num} record_size={record_size}")
    print("campos: " + ", ".join(f"{n}({t},{l})" for n, t, l in campos))

    fh.seek(header_size)
    muestra = fh.read(record_size)
    pos = 1
    for nombre, tipo, largo in campos:
        bruto = muestra[pos:pos + largo]
        pos += largo
        if nombre in ("FECHA", "FECHAEMI", "FACTURA", "NUMERO", "CODIGO"):
            print(f"  {nombre}[{tipo}] = {bruto!r}")

    # recorrer contando años (fechas como texto YYYYMMDD)
    fh.seek(header_size)
    indice_fecha = None
    pos = 1
    for i, (nombre, tipo, largo) in enumerate(campos):
        if nombre == "FECHA":
            indice_fecha = pos
        pos += largo
    anios = Counter()
    if indice_fecha is not None:
        import datetime
        fh.seek(header_size)
        for _ in range(num):
            reg = fh.read(record_size)
            if len(reg) != record_size:
                break
            bruto = reg[indice_fecha:indice_fecha + 8]
            if len(bruto) < 8:
                continue
            juliano = struct.unpack("<I", bruto[:4])[0]
            if juliano <= 0:
                continue
            try:
                fecha = datetime.date.fromordinal(juliano - 1721425)
            except ValueError:
                continue
            anios[fecha.year] += 1
        print("años:", dict(sorted(anios.items())))
