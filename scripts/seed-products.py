#!/usr/bin/env python3
"""
Seed Mercurius with synthetic (or Open Food Facts) products for load testing.

Why synthetic by default
------------------------
Real barcode datasets are large downloads (Open Food Facts JSONL ~8 GB,
Nudger GTIN ~16 GB) and carry attribution/licence obligations. For performance
testing we only need realistic *volume* and *shape*: valid EAN-13 codes, Spanish
product names, the 24 canonical families, distributors, prices and stock.
`--source openfoodfacts` is provided as a hook for real data.

Safety
------
The script refuses to run against a database whose name is not in
ALLOWED_DATABASES. It never deletes existing rows; generated article codes start
above the current maximum.

Usage
-----
    python scripts/seed-products.py --count 200000 --database mercurius --load
    python scripts/seed-products.py --count 200000 --database mercurius --load \
        --host localhost --port 5433 --user mercurius --password '...'

Outputs (under --out, default %TEMP%/mercurius-seed):
    articulos.csv  precios.csv  stock.csv  load.sql

Then, if --load is given, runs `psql -f load.sql`.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import os
import random
import subprocess
import sys
import tempfile
import time
from datetime import date, datetime, timezone
from pathlib import Path

ALLOWED_DATABASES = {"mercurius", "mercurius_test", "mercurius_seed"}

BRANDS = [
    "KION", "DOS PINOS", "FLORIDA", "BOURNE", "JACKS", "POZUELO", "BIMBO",
    "COCA COLA", "PEPSI", "TOSTY", "DIANA", "LACTEOS CORONADO", "BRITT",
    "DELITALIA", "DINANT", "PURA VIDA", "ALPISTE", "TERRABONA", "SABEMAS",
    "MAGGI", "HEINZ", "QUAKER", "TRIJUELAS", "ZUKRA", "ROLAND", "PREGO",
    "TENTACION", "CINI MINIS", "CHUNKY", "KIMBY", "GOYA", "CA",
]

PRODUCTS = [
    "LECHE ENTERA", "LECHE SEMIDESCREMADA", "YOGURT NATURAL", "QUESO FRESCO",
    "CREMA DULCE", "MANTEQUILLA", "JUGO DE NARANJA", "AGUA PURIFICADA",
    "REFRESCO COLA", "CERVEZA LAGER", "CERVEZA TRIGO", "VINO TINTO",
    "CAFE MOLIDO", "CAFE EN GRANO", "TE NEGRO", "CHOCOLATE EN POLVO",
    "GALLETAS SODA", "GALLETAS CREMA", "CEREAL HOJUELAS", "CEREAL CHOCOLATE",
    "ARROZ BLANCO", "FRIJOL NEGRO", "AZUCAR BLANCA", "SAL FINA",
    "ACEITE VEGETAL", "ATUN EN AGUA", "SARDINA EN TOMATE", "MAIZ DULCE",
    "PASTA SPAGHETTI", "SALSA DE TOMATE", "MAYONESA", "MOSTAZA",
    "SALCHICHA POLLO", "SALCHICHON CERDO", "JAMON AHUMADO", "TOCINETA",
    "PAPAS FRITAS", "NACHOS", "MANI SALADO", "SEMILLAS MIXTAS",
    "DETERGENTE", "JABON DE BANO", "SHAMPOO", "PAPEL HIGIENICO",
    "DESODORANTE", "CREMA DENTAL", "CLORO", "BOLSAS PLASTICAS",
    "ALIMENTO PERRO", "ALIMENTO GATO", "PILAS AA", "FOCOS LED",
]

SIZES = ["100G", "150G", "200G", "250G", "300G", "350G", "400G", "500G",
         "750G", "1KG", "1.5KG", "2KG", "250ML", "350ML", "500ML", "600ML",
         "1L", "1.5L", "2L", "3L", "6UD", "12UD", "24UD"]

UNITS = ["Unid", "Kg", "g", "L", "ml"]


def ean13(seq: int, rng: random.Random) -> str:
    """Valid EAN-13 in the internal '20' range; seq must be <= 9,999,999,999."""
    body = f"20{seq:010d}"
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(body))
    check = (10 - total % 10) % 10
    return body + str(check)


def synthetic_rows(count: int, seed: int, run_id: int):
    rng = random.Random(seed)
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    for i in range(1, count + 1):
        barcode = ean13(run_id * 100000 + i, rng)
        brand = BRANDS[i % len(BRANDS)]
        product = PRODUCTS[(i * 7) % len(PRODUCTS)]
        size = SIZES[(i * 5) % len(SIZES)]
        barcode = ean13(i, rng)
        name = f"{product} {brand} {size}"[:255]
        desc = f"{product} {size} - {brand} / lote de prueba {i}"[:500]
        cost = round(rng.uniform(150, 18000), 2)
        margin = round(rng.uniform(15, 45), 2)
        net = round(cost * (1 + margin / 100), 2)
        final = round(net * 1.13, 2)
        yield {
            "articulo": {
                "codigo": i,  # offset applied by SQL
                "codigobarra": barcode,
                "nombre": name,
                "descripcion": desc,
                "unidadmedida": UNITS[i % len(UNITS)],
                "unidadmedidacomercial": UNITS[i % len(UNITS)],
                "cabys_id": None,
                "stockoptimo": 5 + (i % 45),
                "diasstockseguridad": 7,
                "estadoalertas": True,
                "exento": False,
                "processed": True,
                "status": True,
                "version": 0,
                "fecha": datetime.now(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
            },
            "precio": {
                "codigo": i,
                "fechacompra": datetime.now(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
                "porcentaje_utilidad": margin,
                "precio_con_utilidad": net,
                "precio_costo_sin_iva": cost,
                "precio_final": final,
            },
            "stock": {
                "codigo": i,
                "codigobarra": barcode,
                "stock": rng.randint(0, 500),
                "lastupdated": datetime.now(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
                "version": 0,
            },
        }


def openfoodfacts_rows(path: Path, count: int):
    """Stream Open Food Facts JSONL (gz or plain) into the same row shape."""
    opener = gzip.open if path.suffix == ".gz" else open
    emitted = 0
    with opener(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            if emitted >= count:
                break
            try:
                p = json.loads(line)
            except json.JSONDecodeError:
                continue
            code = str(p.get("code") or "").strip()
            name = (p.get("product_name") or p.get("product_name_en") or "").strip()
            if not code.isdigit() or len(code) < 8 or not name:
                continue
            emitted += 1
            cost = 500.0
            margin = 25.0
            net = round(cost * 1.25, 2)
            yield {
                "articulo": {
                    "codigo": emitted,
                    "codigobarra": code[:255],
                    "nombre": name[:255],
                    "descripcion": (p.get("generic_name") or "")[:500],
                    "unidadmedida": "Unid",
                    "unidadmedidacomercial": "Unid",
                    "cabys_id": None,
                    "stockoptimo": 10,
                    "diasstockseguridad": 7,
                    "estadoalertas": True,
                    "exento": False,
                    "processed": True,
                    "status": True,
                    "version": 0,
                    "fecha": datetime.now(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
                },
                "precio": {
                    "codigo": emitted,
                    "fechacompra": datetime.now(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
                    "porcentaje_utilidad": margin,
                    "precio_con_utilidad": net,
                    "precio_costo_sin_iva": cost,
                    "precio_final": round(net * 1.13, 2),
                },
                "stock": {
                    "codigo": emitted,
                    "codigobarra": code[:255],
                    "stock": 0,
                    "lastupdated": datetime.now(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
                    "version": 0,
                },
            }


def write_csvs(rows, out: Path):
    out.mkdir(parents=True, exist_ok=True)
    files = {
        "articulos": ["codigo", "codigobarra", "nombre", "descripcion", "unidadmedida",
                      "unidadmedidacomercial", "cabys_id", "stockoptimo",
                      "diasstockseguridad", "estadoalertas", "exento", "processed",
                      "status", "version", "fecha"],
        "precios": ["codigo", "fechacompra", "porcentaje_utilidad",
                    "precio_con_utilidad", "precio_costo_sin_iva", "precio_final"],
        "stock": ["codigo", "codigobarra", "stock", "lastupdated", "version"],
    }
    handles = {k: open(out / f"{k}.csv", "w", newline="", encoding="utf-8") for k in files}
    writers = {k: csv.DictWriter(handles[k], fieldnames=v) for k, v in files.items()}
    for w in writers.values():
        w.writeheader()
    for row in rows:
        writers["articulos"].writerow(row["articulo"])
        writers["precios"].writerow(row["precio"])
        writers["stock"].writerow(row["stock"])
    for h in handles.values():
        h.close()


LOAD_SQL = r"""
\set ON_ERROR_STOP on
begin;

-- Reference data: ensure the 24 canonical families and some distributors exist.
insert into familia (id, nombre, status, fecha)
select (select coalesce(max(id), 0) from familia) + row_number() over (order by v.nombre),
       v.nombre, true, now()
  from (values ('ABARROTES'),('SNACKS'),('LACTEOS'),('LICORES'),('CUIDADO PERSONAL'),
               ('BEBIDAS'),('LIMPIEZA'),('GALLETAS'),('TABACO'),('PANADERIA,REPOSTERIA'),
               ('FRUTAS,VERDURAS'),('CAJA/BOLSAS'),('SIN CLASIFICAR'),('ALQUILERES'),
               ('CERVEZAS'),('EMBUTIDOS'),('FARMACIA'),('PASTAS'),('CHOCOLATE'),
               ('CEREALES'),('CONFITES/CHICLES'),('ANIMALES'),('BATERIAS, CAMARAS'),
               ('LIBRERIA')) v(nombre)
 where not exists (select 1 from familia f where upper(btrim(f.nombre)) = upper(v.nombre));

insert into departamento (id, nombre, fecha, status)
select (select coalesce(max(id), 0) from departamento) + g,
       'DISTRIBUIDORA ' || g, now(), true
  from generate_series(1, 30) g
 where not exists (select 1 from departamento d where d.nombre = 'DISTRIBUIDORA ' || g);

create temp table stg_articulos (
  codigo bigint, codigobarra text, nombre text, descripcion text,
  unidadmedida text, unidadmedidacomercial text, cabys_id text,
  stockoptimo int, diasstockseguridad int, estadoalertas boolean, exento boolean,
  processed boolean, status boolean, version bigint, fecha timestamp
);
create temp table stg_precios (
  codigo bigint, fechacompra timestamp, porcentaje_utilidad numeric,
  precio_con_utilidad numeric, precio_costo_sin_iva numeric, precio_final numeric
);
create temp table stg_stock (
  codigo bigint, codigobarra text, stock numeric, lastupdated timestamp, version bigint
);
\copy stg_articulos from 'ARTICULOS_CSV' csv header
\copy stg_precios from 'PRECIOS_CSV' csv header
\copy stg_stock from 'STOCK_CSV' csv header

create temp table base as
select coalesce(max(codigo), 0) as maxcodigo from articulos;

insert into articulos (codigo, codigobarra, nombre, descripcion, unidadmedida,
                       unidadmedidacomercial, cabys_id, stockoptimo,
                       diasstockseguridad, estadoalertas, exento, processed,
                       status, version, fecha, familia_id, departamento_id)
select b.maxcodigo + s.codigo, s.codigobarra, s.nombre, s.descripcion,
       s.unidadmedida, s.unidadmedidacomercial, s.cabys_id, s.stockoptimo,
       s.diasstockseguridad, s.estadoalertas, s.exento, s.processed,
       s.status, s.version, s.fecha,
       f.id,
       d.id
  from stg_articulos s
  cross join base b
  join lateral (
      select id from familia where status order by id
       offset ((s.codigo - 1) % (select count(*) from familia where status)) limit 1
  ) f on true
  join lateral (
      select id from departamento order by id
       offset ((s.codigo - 1) % (select count(*) from departamento)) limit 1
  ) d on true;

insert into articuloprecio (id, articulo_id, fechacompra, porcentaje_utilidad,
                            precio_con_utilidad, precio_costo_sin_iva, precio_final)
select (select coalesce(max(id), 0) from articuloprecio) + p.codigo,
       b.maxcodigo + p.codigo, p.fechacompra, p.porcentaje_utilidad,
       p.precio_con_utilidad, p.precio_costo_sin_iva, p.precio_final
  from stg_precios p cross join base b;

insert into articulostock (id, codigobarra, codigo_barra, stock, lastupdated, version)
select (select coalesce(max(id), 0) from articulostock) + s.codigo,
       s.codigobarra, s.codigobarra, s.stock, s.lastupdated, s.version
  from stg_stock s;

commit;
analyze articulos;
analyze articuloprecio;
analyze articulostock;
select 'articulos' k, count(*) n from articulos
union all select 'precios', count(*) from articuloprecio
union all select 'stock', count(*) from articulostock;
"""


def build_load_sql(out: Path) -> Path:
    sql = (LOAD_SQL
           .replace("ARTICULOS_CSV", str(out / "articulos.csv").replace("\\", "/"))
           .replace("PRECIOS_CSV", str(out / "precios.csv").replace("\\", "/"))
           .replace("STOCK_CSV", str(out / "stock.csv").replace("\\", "/")))
    path = out / "load.sql"
    path.write_text(sql, encoding="utf-8")
    return path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=5000)
    ap.add_argument("--source", choices=["synthetic", "openfoodfacts"], default="synthetic")
    ap.add_argument("--off-jsonl", type=Path, help="Path to openfoodfacts-products.jsonl(.gz)")
    ap.add_argument("--database", default="mercurius")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", default="5433")
    ap.add_argument("--user", default="mercurius")
    ap.add_argument("--password", default=os.environ.get("PGPASSWORD", ""))
    ap.add_argument("--psql", default="psql", help="path to psql executable")
    ap.add_argument("--out", type=Path, default=Path(tempfile.gettempdir()) / "mercurius-seed")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--run-id", type=int, default=None,
                    help="barcode namespace (default: epoch seconds mod 100000)")
    ap.add_argument("--load", action="store_true")
    args = ap.parse_args()

    if args.database not in ALLOWED_DATABASES:
        print(f"Refusing to seed '{args.database}'; allowed: {sorted(ALLOWED_DATABASES)}", file=sys.stderr)
        return 2

    if args.source == "openfoodfacts":
        if not args.off_jsonl:
            print("--off-jsonl is required for --source openfoodfacts", file=sys.stderr)
            return 2
        rows = openfoodfacts_rows(args.off_jsonl, args.count)
    else:
        run_id = args.run_id if args.run_id is not None else int(time.time()) % 100000
        rows = synthetic_rows(args.count, args.seed, run_id)

    write_csvs(rows, args.out)
    load = build_load_sql(args.out)
    print(f"generated {args.count} products in {args.out}")
    print(f"load script: {load}")

    if args.load:
        env = dict(os.environ, PGPASSWORD=args.password)
        cmd = [args.psql, "-h", args.host, "-p", str(args.port), "-U", args.user,
               "-d", args.database, "-v", "ON_ERROR_STOP=1", "-f", str(load)]
        print("running:", " ".join(cmd))
        return subprocess.call(cmd, env=env)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
