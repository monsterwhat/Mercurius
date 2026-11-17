"""
Genera el CSV para crear los articulos que se vendieron pero no estan en el
catalogo de Mercurius.

Entrada:
  codigos_sin_articulo.csv  (una fila por codigo de barras)
  DBF: detafact.dbf, detafactbk27022026.DBF, detafact_respaldo_respaldo.dbf
       maestro.DBF (catalogo activo 2026: marca los que siguen vigentes)

Salida:
  articulos_faltantes.csv
    codigo,nombre,cabys,exento,precio,activo

El nombre sale de la descripcion mas frecuente en el detalle de ventas; el CAByS
y el precio, del mismo detalle. activo = 1 solo si el codigo existe en el
maestro vigente.
"""
import csv
import struct
import sys
from collections import Counter, defaultdict
from pathlib import Path

FILES = ["detafact.dbf", "detafactbk27022026.DBF", "detafact_respaldo_respaldo.dbf"]


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
            length = raw[16]
            if name:
                fields.append((name, length))
        return n, hs, rs, fields


def rows(path):
    n, hs, rs, fields = header(path)
    with path.open("rb") as f:
        f.seek(hs)
        for _ in range(n):
            rec = f.read(rs)
            if len(rec) < rs:
                break
            if rec[0:1] == b"*":
                continue
            pos = 1
            row = {}
            for name, length in fields:
                row[name] = rec[pos:pos + length].decode("latin-1", "replace").strip()
                pos += length
            yield row


def main(faltantes_csv, dbf_dir, out_csv):
    base = Path(dbf_dir)
    faltan = {r[0].strip() for r in csv.reader(open(faltantes_csv, encoding="utf-8")) if r}
    print("codigos a crear:", len(faltan))

    desc = defaultdict(Counter)
    cabys = defaultdict(Counter)
    exento = defaultdict(Counter)
    precio = defaultdict(Counter)
    for fn in FILES:
        p = base / fn
        if not p.exists():
            print(f"AVISO: no existe {p}")
            continue
        for r in rows(p):
            cod = (r.get("CODIGO") or "").strip()
            if cod not in faltan:
                continue
            if r.get("DESCRIP"):
                desc[cod][r["DESCRIP"]] += 1
            if r.get("CODIGOCABY"):
                cabys[cod][r["CODIGOCABY"]] += 1
            if r.get("EXENTO"):
                exento[cod][r["EXENTO"].upper()] += 1
            if r.get("VALORUNITA") and r["VALORUNITA"] not in ("0", "0.00"):
                precio[cod][r["VALORUNITA"]] += 1

    activos = set()
    maestro = base / "maestro.DBF"
    if maestro.exists():
        for r in rows(maestro):
            activos.add((r.get("CODIGO") or "").strip())
    print("maestro:", len(activos), "codigos")

    filas = []
    sin_nombre = 0
    for cod in sorted(faltan):
        nombre = desc[cod].most_common(1)[0][0] if desc[cod] else ""
        if not nombre:
            # Sin descripcion en el detalle de ventas: son codigos de actividad
            # (CACTIVIDAD) que algun import viejo guardo como codigo comercial,
            # no articulos reales. No se crean.
            sin_nombre += 1
            continue
        cb = cabys[cod].most_common(1)[0][0] if cabys[cod] else ""
        ex = 1 if (exento[cod] and exento[cod].most_common(1)[0][0] == "S") else 0
        pr = precio[cod].most_common(1)[0][0] if precio[cod] else ""
        filas.append((cod, nombre[:255], cb, ex, pr, 1 if cod in activos else 0))

    with open(out_csv, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["codigo", "nombre", "cabys", "exento", "precio", "activo"])
        w.writerows(filas)

    print(f"sin descripcion: {sin_nombre}")
    print(f"activos: {sum(f[5] for f in filas)}  retirados: {sum(1 for f in filas if not f[5])}")
    print(f"escrito: {out_csv}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2], sys.argv[3])
