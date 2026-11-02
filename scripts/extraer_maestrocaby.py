import csv, struct
from pathlib import Path
SNAP = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido")
SALIDA = Path(r"build\dbf_reconciliacion\maestrocaby.csv")
def cab(fh):
    h = fh.read(32); n, hs, rs = struct.unpack("<IHH", h[4:12])
    campos = []; fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00: break
        nom = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        if nom: campos.append((nom, raw[11:12].decode("latin-1"), raw[16]))
    return n, hs, rs, campos
ruta = SNAP / "maestrocaby.DBF"
with ruta.open("rb") as fh:
    n, hs, rs, campos = cab(fh); fh.seek(hs)
    filas = []; con = 0
    for _ in range(n):
        r = fh.read(rs)
        if len(r) != rs: break
        if r[0:1] == b"\x2a": continue
        fila = {}; pos = 1
        for a,b,l in campos:
            fila[a] = r[pos:pos+l].decode("latin-1").strip(); pos += l
        cod = fila.get("CODIGO","").strip(); caby = fila.get("CODIGOCABY","").strip()
        if not cod or not caby: continue
        con += 1
        filas.append([cod, caby, fila.get("NOMBRE","")])
with SALIDA.open("w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh); w.writerow(["codigo","codigocaby","nombre"]); w.writerows(filas)
print("filas con CAByS:", con, "->", SALIDA)
