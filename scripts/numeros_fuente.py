"""Lista todos los numeros de factura que aparecen en las fuentes 2023-2026."""
import csv
import datetime
import struct
from pathlib import Path

SNAP = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido")
SALIDA = Path(__file__).resolve().parent.parent / "build" / "dbf_reconciliacion" / "numeros_fuente.csv"

CABECERAS = [
    ("facturasbk22042024noborrar.DBF", "NUMERO"),
    ("facturasenero2026.DBF", "NUMERO"),
    ("facturas.DBF", "NUMERO"),
    ("facturasdiarias.dbf", "NUMERO"),
]
LINEAS = [
    ("detafact_respaldo_respaldo.dbf", "FACTURA"),
    ("detafactbk27022026.DBF", "FACTURA"),
    ("detafact.dbf", "FACTURA"),
]


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


def registros(ruta):
    with ruta.open("rb") as fh:
        n, hs, rs, campos = cabecera(fh)
        fh.seek(hs)
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
                    fila[nom] = f"{s[:4]}" if len(s) == 8 and s.isdigit() else ""
                elif tipo == "T":
                    j = struct.unpack("<I", br[:4])[0] if len(br) >= 4 else 0
                    try:
                        fila[nom] = str(datetime.date.fromordinal(j - 1721425).year) if j > 0 else ""
                    except ValueError:
                        fila[nom] = ""
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila


def limpio(valor):
    valor = (valor or "").strip()
    if not valor:
        return ""
    numero = valor.split("@")[0]
    return numero if numero.isdigit() else ""


filas = []
for nombre, campo in CABECERAS:
    ruta = SNAP / nombre
    if not ruta.exists():
        print(f"  {nombre}: NO EXISTE")
        continue
    n = 0
    for f in registros(ruta):
        anio = f.get("FECHA", "")
        numero = limpio(f.get(campo, ""))
        if not anio or not numero:
            continue
        filas.append((anio, numero, nombre))
        n += 1
    print(f"  {nombre}: {n}")

for nombre, campo in LINEAS:
    ruta = SNAP / nombre
    if not ruta.exists():
        print(f"  {nombre}: NO EXISTE")
        continue
    vistos = set()
    n = 0
    for f in registros(ruta):
        anio = f.get("FECHA", "")
        numero = limpio(f.get(campo, ""))
        if not anio or not numero:
            continue
        clave = (anio, numero)
        if clave in vistos:
            continue
        vistos.add(clave)
        filas.append((anio, numero, nombre))
        n += 1
    print(f"  {nombre}: {n} facturas distintas")

with SALIDA.open("w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["anio", "numero", "fuente"])
    w.writerows(filas)
print(f"total {len(filas)} -> {SALIDA}")
