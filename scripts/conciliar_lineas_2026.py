"""
Concilia las lineas de venta 2026 (enero-julio) contra el total de cada
comprobante ya importado en Mercurius.

Por que no basta filtrar por numero ni por numero de linea:
  * los numeros de factura se repiten entre 2025 y 2026;
  * NNUMELINEA se repite cuando el tiquete tiene lineas identicas;
  * detafact.dbf y detafactbk se solapan en febrero.

Estrategia por factura:
  1. se toman los candidatos de detafact.dbf y de detafactbk27022026.DBF;
  2. se descartan los que no caen cerca de la fecha del comprobante (separa 2025);
  3. se elige el conjunto (detafact / bk / union) cuya suma de TOTAL cuadre
     con el total del comprobante; empate -> detafact.dbf.

Entrada:
  export CSV: comprobante_id,clave,numero_consecutivo,fecha,total,iva_actual,neta_actual
  DBF: detafact.dbf, detafactbk27022026.DBF

Salida:
  lineas_2026.csv    comprobante_id,linea,fecha,codigo,cabys,cantidad,total,imp,descuento,exento,porceimp,cajero,usuario
  iva_2026_fix.csv   comprobante_id,iva,neta
  conciliacion_lineas.csv  comprobante_id,numero,fuente,lineas,suma_total,total_comprobante,diferencia

Uso:
  python scripts/conciliar_lineas_2026.py <export.csv> <dir_dbf> <salida_dir>
"""
import csv
import datetime
import struct
import sys
from collections import defaultdict
from pathlib import Path

FILES = ["detafact.dbf", "detafactbk27022026.DBF"]
JD_EPOCH = 2415019
VENTANA_DIAS = 75


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


def decode_date(raw, tipo):
    if tipo == "T" and len(raw) == 8:
        dias = struct.unpack("<i", raw[0:4])[0]
        if dias <= 0:
            return None
        try:
            return datetime.date(1900, 1, 1) + datetime.timedelta(days=dias - JD_EPOCH)
        except (ValueError, OverflowError):
            return None
    txt = raw.decode("latin-1").strip()
    if tipo == "D" and len(txt) == 8 and txt.isdigit():
        try:
            return datetime.date(int(txt[:4]), int(txt[4:6]), int(txt[6:]))
        except ValueError:
            return None
    return None


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
            for name, tipo, length in fields:
                raw = rec[pos:pos + length]
                row[name] = decode_date(raw, tipo) if name == "FECHA" else raw.decode("latin-1", "replace").strip()
                pos += length
            yield row


def number(v):
    try:
        return float((v or "0").replace(",", ""))
    except ValueError:
        return 0.0


def norm(cons):
    cons = (cons or "").strip().split("@", 1)[0]
    return "".join(ch for ch in cons if ch.isdigit()).lstrip("0")


def cargar(dbf_dir):
    por_archivo = {}
    for fn in FILES:
        p = Path(dbf_dir) / fn
        acc = defaultdict(list)
        if not p.exists():
            print(f"AVISO: no existe {p}")
            por_archivo[fn] = acc
            continue
        for r in rows(p):
            num = (r.get("FACTURA") or "").strip()
            if not num.isdigit():
                continue
            acc[num].append({
                "linea": (r.get("NNUMELINEA") or "0").strip(),
                "fecha": r.get("FECHA"),
                "codigo": (r.get("CODIGO") or "").strip(),
                "cabys": (r.get("CODIGOCABY") or "").strip(),
                "cantidad": (r.get("CANTIDAD") or "0").strip(),
                "total": number(r.get("TOTAL")),
                "imp": number(r.get("IMP")),
                "descuento": (r.get("DESCTO") or "0").strip(),
                "exento": (r.get("EXENTO") or "").strip(),
                "porceimp": (r.get("PORCEIMP") or "").strip(),
                "cajero": (r.get("CAJERO") or "").strip(),
                "usuario": (r.get("USUARIO") or "").strip(),
                "fuente": fn,
            })
        por_archivo[fn] = acc
        print(f"{fn}: {sum(len(v) for v in acc.values())} lineas, {len(acc)} facturas")
    return por_archivo


def main(export_csv, dbf_dir, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    datos = cargar(dbf_dir)
    det = datos["detafact.dbf"]
    bk = datos["detafactbk27022026.DBF"]

    comprobantes = []
    for r in csv.DictReader(open(export_csv, encoding="utf-8")):
        fecha = datetime.date.fromisoformat(r["fecha"])
        comprobantes.append((r["comprobante_id"], norm(r["numero_consecutivo"]), fecha,
                             number(r["total"]), number(r["iva_actual"]), number(r["neta_actual"])))

    lineas_out = []
    iva_out = []
    conc = []
    stats = defaultdict(int)
    suma_dif = 0.0

    for cid, num, fecha, total, iva_actual, neta_actual in comprobantes:
        a = [x for x in det.get(num, []) if x["fecha"] and abs((x["fecha"] - fecha).days) <= VENTANA_DIAS]
        b = [x for x in bk.get(num, []) if x["fecha"] and abs((x["fecha"] - fecha).days) <= VENTANA_DIAS]

        opciones = []
        if a:
            opciones.append(("detafact", a, sum(x["total"] for x in a)))
        if b:
            opciones.append(("bk", b, sum(x["total"] for x in b)))
        if a and b:
            union = a + b
            opciones.append(("union", union, sum(x["total"] for x in union)))

        if not opciones:
            stats["sin_lineas"] += 1
            conc.append((cid, num, "", 0, 0.0, total, total))
            continue

        mejor = min(opciones, key=lambda o: (abs(o[2] - total), 0 if o[0] == "detafact" else 1))
        fuente, elegidas, suma = mejor
        dif = round(suma - total, 2)
        stats[fuente] += 1
        if abs(dif) > 1:
            stats["con_diferencia"] += 1
            suma_dif += abs(dif)
        conc.append((cid, num, fuente, len(elegidas), round(suma, 2), total, dif))

        iva = round(sum(x["imp"] for x in elegidas), 5)
        neta = round(max(total - iva, 0.0), 5)
        iva_out.append((cid, iva, neta))
        for x in elegidas:
            lineas_out.append((
                cid, x["linea"], x["fecha"].isoformat() if x["fecha"] else "",
                x["codigo"], x["cabys"], x["cantidad"], f"{x['total']:.2f}",
                f"{x['imp']:.2f}", x["descuento"], x["exento"], x["porceimp"],
                x["cajero"], x["usuario"],
            ))

    with (out / "lineas_2026.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["comprobante_id", "linea", "fecha", "codigo", "cabys", "cantidad",
                    "total", "imp", "descuento", "exento", "porceimp", "cajero", "usuario"])
        w.writerows(lineas_out)

    with (out / "iva_2026_fix.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["comprobante_id", "iva", "neta"])
        w.writerows(iva_out)

    with (out / "conciliacion_lineas.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["comprobante_id", "numero", "fuente", "lineas", "suma_total",
                    "total_comprobante", "diferencia"])
        w.writerows(conc)

    total_iva = sum(x[1] for x in iva_out)
    print(f"\ncomprobantes: {len(comprobantes)}")
    print(f"fuente elegida: {dict(stats)}")
    print(f"lineas: {len(lineas_out)}")
    print(f"iva resultante: {total_iva:,.2f}")
    print(f"suma de |diferencias|: {suma_dif:,.2f}")
    print(f"escrito: {out}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2], sys.argv[3])
