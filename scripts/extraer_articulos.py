"""Extrae el catalogo viejo completo: Articulos.dbf (15 059) y maestro.DBF (6 424)."""
import csv
import struct
from pathlib import Path

E = Path(r"E:\VPALMERAS")
SALIDA = Path(__file__).resolve().parent.parent / "build" / "dbf_reconciliacion"


def num(valor):
    """Normaliza '1.600.00' -> 1600.00 (separador de miles con punto)."""
    s = (valor or "").strip().replace(" ", "")
    if not s:
        return ""
    if s.count(",") == 1 and "." in s:
        s = s.replace(".", "").replace(",", ".")
    elif s.count(".") > 1:
        i = s.rfind(".")
        s = s[:i].replace(".", "") + s[i:]
    return s


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


def regs(ruta):
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
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila


def escribir(nombre, encabezado, filas):
    destino = SALIDA / nombre
    with destino.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(encabezado)
        n = 0
        for fila in filas:
            w.writerow(fila)
            n += 1
    print(f"  {nombre}: {n} filas")


def articulos_viejos():
    for f in regs(E / "Articulos.dbf"):
        yield [f.get("CODIGO", "").strip(), f.get("DESCRIPCIO", ""), f.get("TIPO", ""),
               f.get("COMENTARIO", ""), num(f.get("PRECIO_VEN")), num(f.get("PRECIO_COS")),
               num(f.get("UTILIDAD")), num(f.get("DESCUENTO")), f.get("UNIDAD_MED", ""),
               num(f.get("XISTENCIA")), num(f.get("EXISTENCIA")), num(f.get("EXISTENCI2")),
               f.get("GRAVADO", ""), f.get("FAMILIA", "")]


def maestro_viejo():
    for f in regs(E / "maestro.DBF"):
        yield [f.get("CODIGO", "").strip(), f.get("NOMBRE", ""), f.get("FAMILIA", ""),
               f.get("UBICACION", ""), num(f.get("CANTIDAD")), num(f.get("CANTINICIO")),
               num(f.get("CANTCIERRE")), num(f.get("CANTIVENTA")), num(f.get("CANTCOMPRA")),
               num(f.get("COSTO")), num(f.get("MARGENUTIL")), num(f.get("DESCUENTO")),
               num(f.get("VENTA")), num(f.get("PROM")), f.get("ULTIMAENTR", ""),
               f.get("ULTIMASALI", ""), num(f.get("STOCKMIN")), num(f.get("STOCKMAX")),
               f.get("EXENTO", ""), f.get("PROV", ""), f.get("INCLUYEIV", ""),
               f.get("TIPOARTI", ""), num(f.get("PORCEIMP"))]


if __name__ == "__main__":
    print("Articulos.dbf:")
    escribir("articulos_viejo.csv",
             ["codigo", "descripcion", "tipo", "comentario", "precio_venta", "precio_costo",
              "utilidad", "descuento", "unidad_medida", "xistencia", "existencia", "existencia2",
              "gravado", "familia"], articulos_viejos())
    print("maestro.DBF:")
    escribir("maestro_viejo.csv",
             ["codigo", "nombre", "familia", "ubicacion", "cantidad", "cantinicio", "cantcierre",
              "cantventa", "cantcompra", "costo", "margenutil", "descuento", "venta", "prom",
              "ultimaentr", "ultimasali", "stockmin", "stockmax", "exento", "prov",
              "incluyeiv", "tipoarti", "porceimp"], maestro_viejo())
