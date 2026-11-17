"""
Extrae los movimientos de inventario que nunca se importaron: entradas (IM) y
ajustes (AJ) del kardex del sistema viejo.

Fuentes:
  E:\\VPALMERAS\\kardex.DBF                          2015-2019 (instalacion 2019)
  F:\\Desktop\\Super\\New folder (3)\\Original DBF\\kardex.DBF  2022-2026 (respaldo ene-2026)

El kardex viejo tiene 843.552 filas (2022-2025) y 386.566 (2015-2019); el
KARDEX actual solo conserva 2026. Las ventas (FE) ya estan representadas por los
movimientos 'Venta' de las facturas, asi que aqui solo van IM/AJ.

Salida: codigo,fecha,cantidad,tipo,documento,usuario
  tipo: ENTRADA (IM) | Ajuste (AJ)

Uso:
  python scripts/extraer_movimientos_kardex.py <salida.csv> [--desde 2015] [--hasta 2025]
"""
import datetime
import struct
import sys
from pathlib import Path

FUENTES = [
    (r"E:\VPALMERAS\kardex.DBF", range(2015, 2020)),
    (r"F:\Desktop\Super\New folder (3)\Original DBF\kardex.DBF", range(2022, 2027)),
]
TIPOS = {"IM": "ENTRADA", "AJ": "Ajuste"}
JD_EPOCH = 2415019


def header(path):
    with path.open("rb") as f:
        h = f.read(32)
        n, hs, rs = struct.unpack("<IHH", h[4:12])
        fields = []
        f.seek(32)
        while True:
            raw = f.read(32)
            if not raw or raw[0:1] == b"\x0d":
                break
            name = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
            tipo = raw[11:12].decode("latin-1")
            length = raw[16]
            if name:
                fields.append((name, tipo, length))
        return n, hs, rs, fields


def fecha_de(raw, tipo):
    if tipo == "T" and len(raw) == 8:
        dias = struct.unpack("<i", raw[0:4])[0]
        if dias <= 0:
            return None
        try:
            return datetime.date(1900, 1, 1) + datetime.timedelta(days=dias - JD_EPOCH)
        except (ValueError, OverflowError):
            return None
    txt = raw.decode("latin-1").strip()
    if len(txt) == 8 and txt.isdigit():
        try:
            return datetime.date(int(txt[:4]), int(txt[4:6]), int(txt[6:]))
        except ValueError:
            return None
    return None


def main(out_csv, desde, hasta):
    filas = []
    for ruta, anios in FUENTES:
        p = Path(ruta)
        if not p.exists():
            print(f"AVISO: no existe {p}")
            continue
        n, hs, rs, fields = header(p)
        tipo_fecha = next((f[1] for f in fields if f[0] == "FECHA"), "D")
        usados = 0
        with p.open("rb") as fh:
            fh.seek(hs)
            for _ in range(n):
                rec = fh.read(rs)
                if len(rec) < rs:
                    break
                if rec[0:1] == b"*":
                    continue
                pos = 1
                row = {}
                raw_fecha = None
                for name, tipo, length in fields:
                    raw = rec[pos:pos + length]
                    pos += length
                    if name == "FECHA":
                        raw_fecha = raw
                    else:
                        row[name] = raw.decode("latin-1", "replace").strip()
                fecha = fecha_de(raw_fecha, tipo_fecha) if raw_fecha else None
                if not fecha or fecha.year not in anios or not (desde <= fecha.year <= hasta):
                    continue
                mov = (row.get("MOVIMIENTO") or "").strip()
                tipo_mov = TIPOS.get(mov)
                if not tipo_mov:
                    continue
                cod = (row.get("CODIGO") or "").strip()
                if not cod:
                    continue
                filas.append((
                    cod, fecha.isoformat(),
                    (row.get("CANTIDAD") or "0").strip(),
                    tipo_mov,
                    (row.get("DOCUMENTO") or "").strip(),
                    (row.get("USUARIO") or "").strip(),
                ))
                usados += 1
        print(f"{p}: {usados} movimientos IM/AJ en el rango")

    with open(out_csv, "w", newline="", encoding="utf-8") as fh:
        import csv
        w = csv.writer(fh)
        w.writerow(["codigo", "fecha", "cantidad", "tipo", "documento", "usuario"])
        w.writerows(filas)
    print(f"total: {len(filas)} -> {out_csv}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    desde, hasta = 2015, 2025
    for i, arg in enumerate(sys.argv):
        if arg == "--desde":
            desde = int(sys.argv[i + 1])
        if arg == "--hasta":
            hasta = int(sys.argv[i + 1])
    main(sys.argv[1], desde, hasta)
