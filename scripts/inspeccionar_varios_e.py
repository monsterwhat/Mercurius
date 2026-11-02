import struct
from collections import Counter
from pathlib import Path

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
            if len(r) != rs: break
            if r[0:1] == b"\x2a": continue
            fila = {}; pos = 1
            for nom, tipo, largo in campos:
                br = r[pos:pos+largo]; pos += largo
                if tipo == "D":
                    s = br.decode("latin-1").strip()
                    fila[nom] = f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s)==8 and s.isdigit() else ""
                else:
                    fila[nom] = br.decode("latin-1").strip()
            yield fila

for nombre in ("DKCONTRL.DBF", "lineasborradas.dbf", "ERRORES.DBF", "maestro.DBF"):
    ruta = Path(r"E:\VPALMERAS") / nombre
    print("==", nombre, round(ruta.stat().st_size/1024/1024,2), "MB")
    with ruta.open("rb") as fh:
        n, hs, rs, campos = cabecera(fh)
    print("   filas:", n)
    print("   campos:", ", ".join(a for a,_,_ in campos))
    muestra = []
    anios = Counter()
    for f in regs(ruta):
        if len(muestra) < 2:
            muestra.append(f)
        for k, v in f.items():
            if k in ("FECHA","FECHAEMI","FECHA_FAC","FECHADOC") and len(v) >= 4:
                anios[v[:4]] += 1
    for m in muestra:
        print("   ", {k: v for k, v in m.items() if v not in ("", "0", "0.00")})
    if anios:
        print("   anios:", dict(sorted(anios.items())))
    print()
