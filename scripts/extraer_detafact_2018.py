"""Extrae las unicas 43 lineas que quedaron en E:\\VPALMERAS\\DETAFACT.DBF (2018-10-01)."""
import csv
import datetime
import struct
from pathlib import Path

RUTA = Path(r"E:\VPALMERAS\DETAFACT.DBF")
SALIDA = Path(__file__).resolve().parent.parent / "build" / "dbf_reconciliacion" / "lineas_2018_e.csv"


def cabecera(fh):
    h = fh.read(32)
    n, hs, rs = struct.unpack("<IHH", h[4:12])
    campos = []
    fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00:
            break
        nom = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        tipo = raw[11:12].decode("latin-1")
        largo = raw[16]
        if nom:
            campos.append((nom, tipo, largo))
    return n, hs, rs, campos


with RUTA.open("rb") as fh:
    n, hs, rs, campos = cabecera(fh)
    fh.seek(hs)
    filas = []
    for _ in range(n):
        r = fh.read(rs)
        if len(r) != rs:
            break
        if r[0:1] == b"\x2a":
            continue
        fila = {}
        pos = 1
        for nom, tipo, largo in campos:
            br = r[pos:pos + largo]
            pos += largo
            if tipo == "D":
                s = br.decode("latin-1").strip()
                fila[nom] = f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s) == 8 and s.isdigit() else ""
            else:
                fila[nom] = br.decode("latin-1").strip()
        filas.append(fila)

with SALIDA.open("w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["factura", "fecha", "codigo", "codigocaby", "descrip", "cantidad",
                "valorunita", "total", "exento", "imp", "nlinea", "unidad", "usuario"])
    for f in filas:
        w.writerow([f.get("FACTURA", ""), f.get("FECHA", ""), f.get("CODIGO", ""),
                    f.get("CODIGOCABY", ""), f.get("DESCRIP", ""), f.get("CANTIDAD", ""),
                    f.get("VALORUNITA", ""), f.get("TOTAL", ""), f.get("EXENTO", ""),
                    f.get("IMP", ""), f.get("NNUMELINEA", ""), f.get("CUNIDADGTD", ""),
                    f.get("USUARIO", "")])
print(f"{len(filas)} filas -> {SALIDA}")
