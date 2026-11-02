import struct, datetime
from collections import Counter
from pathlib import Path

BASE = Path(r"C:\Users\alvar\AppData\Local\Temp\opencode\sistema_20261027\extraido")

def cab(fh):
    h = fh.read(32); n, hs, rs = struct.unpack("<IHH", h[4:12])
    campos = []; fh.seek(32)
    while True:
        raw = fh.read(32)
        if not raw or raw[0:1] == b"\x0d" or raw[0] == 0x00: break
        nom = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
        if nom: campos.append((nom, raw[11:12].decode("latin-1"), raw[16]))
    return n, hs, rs, campos

def anio(br, tipo):
    if tipo == "D":
        s = br.decode("latin-1").strip()
        return s[:4] if len(s) >= 4 and s[:4].isdigit() else None
    if tipo == "T" and len(br) >= 4:
        j = struct.unpack("<I", br[:4])[0]
        if j <= 0: return None
        try: return str(datetime.date.fromordinal(j-1721425).year)
        except ValueError: return None
    return None

for ruta in [BASE/"FE_DOCUMENTOS.DBF", BASE/"fe_detalle.dbf", BASE/"FE_LOG.DBF"]:
    if not ruta.exists():
        print("==", ruta.name, "NO EXISTE"); continue
    print("==", ruta.name, round(ruta.stat().st_size/1024/1024,1), "MB")
    with ruta.open("rb") as fh:
        n, hs, rs, campos = cab(fh)
        print("   filas:", n, "campos:", ", ".join(f"{a}({b})" for a,b,_ in campos))
        pf = tf = None; pos = 1
        for a,b,l in campos:
            if a.upper() in ("FECHA","FECHAEMI","FECHA_EMI","FECHAEMISION"):
                pf, tf = pos, b
            pos += l
        if pf is None:
            print("   sin campo fecha"); print(); continue
        c = Counter()
        fh.seek(hs)
        for _ in range(n):
            r = fh.read(rs)
            if len(r) != rs: break
            if r[0:1] == b"\x2a": continue
            y = anio(r[pf:pf+8], tf)
            if y: c[y] += 1
        print("   anios:", dict(sorted(c.items())))
    print()
