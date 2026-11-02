"""¿Los DOCUMENTO de salidas/kardex cruzan con los NUMERO de facturasdiarias?"""
import datetime
import struct
from collections import Counter, defaultdict
from pathlib import Path

RUTAS = {
    "facturasdiarias": Path(r"E:\VPALMERAS\facturasdiarias.dbf"),
    "salidas": Path(r"E:\VPALMERAS\salidas.dbf"),
    "kardex": Path(r"E:\VPALMERAS\kardex.DBF"),
}


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
                    fila[nom] = f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s) == 8 and s.isdigit() else ""
                elif tipo == "T":
                    j = struct.unpack("<I", br[:4])[0] if len(br) >= 4 else 0
                    try:
                        fila[nom] = datetime.date.fromordinal(j - 1721425).isoformat() if j > 0 else ""
                    except ValueError:
                        fila[nom] = ""
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila


# facturas por año -> numero
facturas = defaultdict(set)
for f in registros(RUTAS["facturasdiarias"]):
    anio = f["FECHA"][:4]
    if anio:
        facturas[anio].add(f["NUMERO"].lstrip("0") or "0")

print("== facturasdiarias: numeros por anio")
for a in sorted(facturas):
    print(f"   {a}: {len(facturas[a])}")

for nombre in ("salidas", "kardex"):
    print(f"\n== {nombre}: DOCUMENTO por anio y cruce")
    docs = defaultdict(set)
    filas = Counter()
    for f in registros(RUTAS[nombre]):
        anio = f["FECHA"][:4]
        if not anio:
            continue
        doc = (f.get("DOCUMENTO") or "").strip().lstrip("0") or "0"
        docs[anio].add(doc)
        filas[anio] += 1
    for a in sorted(docs):
        cruzan = len(docs[a] & facturas.get(a, set()))
        print(f"   {a}: filas={filas[a]:7d} documentos={len(docs[a]):7d} cruzan_con_factura={cruzan:7d}")
    print("   muestra documentos:", sorted(list(docs.get("2015", set()))[:8]))

# muestra de una factura de 2015 y sus lineas en salidas
print("\n== muestra 2015")
objetivo = None
for f in registros(RUTAS["facturasdiarias"]):
    if f["FECHA"][:4] == "2015":
        objetivo = f["NUMERO"].lstrip("0")
        print("   factura:", {k: f[k] for k in ("NUMERO", "FECHA", "NOMBRE", "TOTAL", "ESTADO", "FORMA_PAGO", "USUARIO")})
        break
if objetivo:
    for f in registros(RUTAS["salidas"]):
        if f["FECHA"][:4] == "2015" and (f.get("DOCUMENTO") or "").strip().lstrip("0") == objetivo:
            print("   salida:", {k: f[k] for k in ("DOCUMENTO", "FECHA", "CODIGO", "CANTIDAD", "DETALLE", "PVENTA1", "VENTAIMP")})
    for f in registros(RUTAS["kardex"]):
        if f["FECHA"][:4] == "2015" and (f.get("DOCUMENTO") or "").strip().lstrip("0") == objetivo:
            print("   kardex:", {k: f[k] for k in ("DOCUMENTO", "FECHA", "CODIGO", "CANTIDAD", "MOVIMIENTO", "TIPODOCUME", "VENTA")})
