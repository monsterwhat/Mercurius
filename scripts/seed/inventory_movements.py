#!/usr/bin/env python3
"""
Generate load-test data for Mercurius inventory movements and cash sessions.

What it writes
--------------
inventario.csv   movements (Venta 70 %, Compra 20 %, Ajuste 10 %)
lotes.csv        inventory batches linked to existing articles
cierrecaja.csv   cash sessions (apertura / cierre, amounts, estado)
load.sql         staging + COPY + INSERT script for the three files

The generator only reads the reference data it needs (article codes and user
ids) from the target database. It never inserts anything. Loading is a separate
step: run `load.sql` with psql (or pass --load, which is deliberately opt-in).

Safety
------
* The host must be local (localhost / 127.0.0.1 / ::1) unless --force is given.
* The database must be `mercurius` or `mercurius_test`.
* Reference reads run in a read-only session.
* IDs are allocated as `max(id) + row_number()` inside one transaction that
  holds EXCLUSIVE locks on the three target tables, so concurrent app writes
  cannot race the allocation.
* Existing rows are never deleted or updated, and no index is created/dropped.

Usage
-----
    python scripts/seed/inventory_movements.py --password 'Mercurius@1!'
    python scripts/seed/inventory_movements.py --count-movements 200000 \
        --count-lotes 5000 --count-cajas 2000 --out ./seed-out

    # After generating (never run by this task):
    psql -h localhost -p 5433 -U mercurius -d mercurius \
         -v ON_ERROR_STOP=1 -f <out>/load.sql

Requirements: Python 3.10+. `psycopg2` is used when available; otherwise the
script falls back to invoking psql for the two read-only reference queries.
"""

from __future__ import annotations

import argparse
import csv
import os
import random
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta
from pathlib import Path

ALLOWED_DATABASES = {"mercurius", "mercurius_test"}
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}

# Movement mix. Kept as exact counts (then shuffled) instead of per-row weighted
# draws so a 1 M row file really is 70/20/10, not merely close to it.
TYPE_VENTA = "Venta"
TYPE_COMPRA = "Compra"
TYPE_AJUSTE = "Ajuste"
TYPE_WEIGHTS = ((TYPE_VENTA, 70), (TYPE_COMPRA, 20), (TYPE_AJUSTE, 10))

NOTAS_VENTA = (
    "Venta mostrador",
    "Salida por factura",
    "Venta POS",
    "Despacho a cliente",
    "Venta al detalle",
)
NOTAS_COMPRA = (
    "Compra a proveedor",
    "Ingreso por reposicion",
    "Recepcion de pedido",
    "Compra local",
    "Reposicion de estanteria",
)
NOTAS_AJUSTE = (
    "Ajuste por conteo fisico",
    "Correccion al alza",
    "Ajuste de sistema",
    "Regularizacion de stock",
)
NOTAS_LOTE = (
    "Lote recibido en buen estado",
    "Ingreso por compra local",
    "Reposicion de estanteria",
    "Lote de proveedor",
    "Recepcion parcial de pedido",
)
NOTAS_CAJA = (
    "Cierre de turno manana",
    "Cierre de turno tarde",
    "Cierre de turno noche",
    "Cierre con faltante",
    "Cierre con sobrante",
)


def default_psql() -> str:
    """psql on PATH, else the newest PostgreSQL install on Windows."""
    found = shutil.which("psql")
    if found:
        return found
    candidates = sorted(
        Path(r"C:\Program Files\PostgreSQL").glob(r"*\bin\psql.exe"),
        reverse=True,
    )
    return str(candidates[0]) if candidates else "psql"


def sql_literal(path: Path) -> str:
    return str(path).replace("\\", "/").replace("'", "''")


# --------------------------------------------------------------------------- #
# Reference data (read-only)
# --------------------------------------------------------------------------- #

def fetch_reference_psycopg2(args: argparse.Namespace) -> tuple[list[int], list[int]]:
    import psycopg2  # type: ignore

    conn = psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.database,
        user=args.user,
        password=args.password or None,
        connect_timeout=10,
    )
    try:
        conn.set_session(readonly=True)
        with conn.cursor() as cur:
            cur.execute("SELECT codigo FROM articulos ORDER BY codigo")
            articulos = [int(row[0]) for row in cur.fetchall()]
            cur.execute("SELECT id FROM users ORDER BY id")
            usuarios = [int(row[0]) for row in cur.fetchall()]
        conn.rollback()
        return articulos, usuarios
    finally:
        conn.close()


def fetch_reference_psql(args: argparse.Namespace) -> tuple[list[int], list[int]]:
    env = dict(os.environ, PGPASSWORD=args.password)
    base = [
        args.psql,
        "-h", args.host,
        "-p", str(args.port),
        "-U", args.user,
        "-d", args.database,
        "-X", "-q", "-A", "-t", "-w",
    ]

    def query(sql: str) -> list[int]:
        result = subprocess.run(
            base + ["-c", sql],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "psql failed")
        return [int(line) for line in result.stdout.splitlines() if line.strip()]

    articulos = query("SELECT codigo FROM articulos ORDER BY codigo")
    usuarios = query("SELECT id FROM users ORDER BY id")
    return articulos, usuarios


def fetch_reference(args: argparse.Namespace) -> tuple[list[int], list[int]]:
    try:
        return fetch_reference_psycopg2(args)
    except ImportError:
        print("psycopg2 not installed; falling back to psql for reference reads",
              file=sys.stderr)
    except Exception as exc:  # noqa: BLE001 - surfaced with actionable context
        print(f"psycopg2 reference read failed ({exc}); trying psql", file=sys.stderr)

    try:
        return fetch_reference_psql(args)
    except Exception as exc:  # noqa: BLE001
        raise SystemExit(
            "Cannot read reference data from "
            f"{args.user}@{args.host}:{args.port}/{args.database}: {exc}\n"
            "Check that PostgreSQL is running, the database exists and "
            "--password / PGPASSWORD is correct."
        ) from exc


# --------------------------------------------------------------------------- #
# Generators
# --------------------------------------------------------------------------- #

def movement_types(count: int) -> list[str]:
    """Exact 70/20/10 mix, shuffled deterministically by the caller's RNG."""
    remaining = count
    out: list[str] = []
    for idx, (name, weight) in enumerate(TYPE_WEIGHTS):
        if idx == len(TYPE_WEIGHTS) - 1:
            n = remaining
        else:
            n = count * weight // 100
            remaining -= n
        out.extend([name] * n)
    return out


def write_inventario(path: Path, count: int, rng: random.Random,
                     articulos: list[int], usuarios: list[int],
                     now: datetime) -> None:
    header = [
        "seq", "cantidad", "fechamovimiento", "notas", "processed", "status",
        "tipomovimiento", "unidadesrecomendadasfactura", "articulo_codigo",
        "usuario_id",
    ]
    span = 1095 * 86400
    start = now - timedelta(days=1095)
    bucket = max(1, span // max(1, count))
    types = movement_types(count)
    rng.shuffle(types)

    notes = {
        TYPE_VENTA: NOTAS_VENTA,
        TYPE_COMPRA: NOTAS_COMPRA,
        TYPE_AJUSTE: NOTAS_AJUSTE,
    }
    n_art = len(articulos)
    n_usr = len(usuarios)

    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        for seq in range(1, count + 1):
            tipo = types[seq - 1]
            if tipo == TYPE_VENTA:
                cantidad = -round(rng.uniform(0.5, 24.0), 2)
                unidades = ""
            elif tipo == TYPE_COMPRA:
                cantidad = round(rng.uniform(12.0, 240.0), 2)
                unidades = str(rng.randint(1, 12))
            else:
                cantidad = round(rng.uniform(1.0, 30.0), 2)
                unidades = ""

            fecha = start + timedelta(
                seconds=min(
                    span - 1,
                    (seq - 1) * span // count + rng.randrange(bucket),
                )
            )
            w.writerow((
                seq,
                f"{cantidad:.2f}",
                fecha.isoformat(sep=" "),
                rng.choice(notes[tipo]),
                "true",
                "true",
                tipo,
                unidades,
                articulos[rng.randrange(n_art)],
                usuarios[rng.randrange(n_usr)],
            ))


def write_lotes(path: Path, count: int, rng: random.Random,
                articulos: list[int], usuarios: list[int],
                now: datetime) -> None:
    header = [
        "seq", "articulo_codigo", "numerolote", "fechavencimiento",
        "cantidadinicial", "cantidadactual", "fechaingreso", "usuario_id",
        "notas", "status", "version",
    ]
    ingreso_start = now - timedelta(days=730)
    span = int((now - ingreso_start).total_seconds())
    n_art = len(articulos)

    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        for seq in range(1, count + 1):
            ingreso = ingreso_start + timedelta(seconds=rng.randrange(span))
            vence = ingreso.date() + timedelta(days=rng.randint(30, 730))
            inicial = round(rng.uniform(6.0, 600.0), 3)
            activo = rng.random() < 0.95
            actual = round(inicial * rng.uniform(0.0, 1.0), 3) if activo else 0.0
            w.writerow((
                seq,
                articulos[rng.randrange(n_art)],
                f"LOTE-{ingreso:%Y%m%d}-{seq:06d}",
                vence.isoformat(),
                f"{inicial:.3f}",
                f"{actual:.3f}",
                ingreso.isoformat(sep=" "),
                rng.choice(usuarios) if usuarios and rng.random() < 0.85 else "",
                rng.choice(NOTAS_LOTE) if rng.random() < 0.8 else "",
                "true" if activo else "false",
                "0",
            ))


def write_cierrecaja(path: Path, count: int, rng: random.Random,
                     usuarios: list[int], now: datetime) -> None:
    header = [
        "seq", "usuario_id", "fechaapertura", "fechacierre", "montoinicial",
        "montoesperadoefectivo", "montoesperadosinpe", "montoesperadotarjeta",
        "montocontadoefectivo", "montocontadosinpe", "montocontadotarjeta",
        "diferencia", "estado", "notas", "version",
    ]
    start = now - timedelta(days=1095)
    closed_end = now - timedelta(hours=13)
    n_usr = len(usuarios)
    n_open = min(n_usr, count)
    closed_count = count - n_open
    closed_span = int((closed_end - start).total_seconds())
    bucket = max(1, closed_span // max(1, closed_count))

    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(header)
        for seq in range(1, count + 1):
            # Cyclic assignment keeps the final n_usr rows on distinct users, so
            # there is at most one open session per user (findSesionAbierta uses
            # getSingleResult and would log a NonUniqueResultException otherwise).
            usuario = usuarios[(seq - 1) % n_usr]
            abierto = seq > closed_count
            if abierto:
                slot = max(1, (6 * 3600) // max(1, n_open))
                j = seq - closed_count - 1
                apertura = now - timedelta(
                    seconds=6 * 3600 - (j * slot + rng.randrange(slot))
                )
                cierre = None
            else:
                apertura = start + timedelta(
                    seconds=min(
                        closed_span - 1,
                        (seq - 1) * closed_span // closed_count
                        + rng.randrange(bucket),
                    )
                )
                cierre = min(
                    apertura + timedelta(seconds=rng.randrange(4 * 3600, 12 * 3600)),
                    now,
                )

            inicial = round(rng.uniform(20_000.0, 120_000.0), 2)
            if abierto:
                esperado_efe = esperado_sinpe = esperado_tarjeta = None
                contado_efe = contado_sinpe = contado_tarjeta = None
                diferencia = None
                notas = "Turno en curso"
            else:
                esperado_efe = round(rng.uniform(50_000.0, 900_000.0), 2)
                esperado_sinpe = round(rng.uniform(10_000.0, 400_000.0), 2)
                esperado_tarjeta = round(rng.uniform(20_000.0, 700_000.0), 2)
                contado_efe = round(max(0.0, esperado_efe * rng.uniform(0.97, 1.03)), 2)
                contado_sinpe = round(max(0.0, esperado_sinpe * rng.uniform(0.97, 1.03)), 2)
                contado_tarjeta = round(
                    max(0.0, esperado_tarjeta * rng.uniform(0.97, 1.03)), 2
                )
                diferencia = round(
                    (contado_efe + contado_sinpe + contado_tarjeta)
                    - (esperado_efe + esperado_sinpe + esperado_tarjeta),
                    2,
                )
                notas = rng.choice(NOTAS_CAJA)

            w.writerow((
                seq,
                usuario,
                apertura.isoformat(sep=" "),
                "" if cierre is None else cierre.isoformat(sep=" "),
                f"{inicial:.2f}",
                "" if esperado_efe is None else f"{esperado_efe:.2f}",
                "" if esperado_sinpe is None else f"{esperado_sinpe:.2f}",
                "" if esperado_tarjeta is None else f"{esperado_tarjeta:.2f}",
                "" if contado_efe is None else f"{contado_efe:.2f}",
                "" if contado_sinpe is None else f"{contado_sinpe:.2f}",
                "" if contado_tarjeta is None else f"{contado_tarjeta:.2f}",
                "" if diferencia is None else f"{diferencia:.2f}",
                "abierto" if abierto else "cerrado",
                notas,
                "0",
            ))


# --------------------------------------------------------------------------- #
# load.sql
# --------------------------------------------------------------------------- #

LOAD_SQL = r"""
\set ON_ERROR_STOP on
\echo 'Loading Mercurius inventory seed (transactional; EXCLUSIVE locks held)...'
BEGIN;
SET LOCAL statement_timeout = 0;

-- EXCLUSIVE (not ACCESS EXCLUSIVE) still allows plain SELECTs from the app but
-- blocks INSERT/UPDATE/DELETE, so max(id)+row_number() cannot collide with a
-- concurrent writer. Released automatically at COMMIT/ROLLBACK.
LOCK TABLE inventario, lotes, cierrecaja IN EXCLUSIVE MODE;

CREATE TEMP TABLE stg_inventario (
  seq                          bigint,
  cantidad                     numeric(38,2),
  fechamovimiento              timestamp(6),
  notas                        varchar(255),
  processed                    boolean,
  status                       boolean,
  tipomovimiento               varchar(255),
  unidadesrecomendadasfactura  numeric(38,2),
  articulo_codigo              bigint,
  usuario_id                   bigint
) ON COMMIT DROP;

CREATE TEMP TABLE stg_lotes (
  seq               bigint,
  articulo_codigo   bigint,
  numerolote        varchar(50),
  fechavencimiento  date,
  cantidadinicial   numeric(18,3),
  cantidadactual    numeric(18,3),
  fechaingreso      timestamp(6),
  usuario_id        bigint,
  notas             varchar(100),
  status            boolean,
  version           bigint
) ON COMMIT DROP;

CREATE TEMP TABLE stg_cierrecaja (
  seq                    bigint,
  usuario_id             bigint,
  fechaapertura          timestamp(6),
  fechacierre            timestamp(6),
  montoinicial           numeric(18,2),
  montoesperadoefectivo  numeric(18,2),
  montoesperadosinpe     numeric(18,2),
  montoesperadotarjeta   numeric(18,2),
  montocontadoefectivo   numeric(18,2),
  montocontadosinpe      numeric(18,2),
  montocontadotarjeta    numeric(18,2),
  diferencia             numeric(18,2),
  estado                 varchar(20),
  notas                  varchar(255),
  version                bigint
) ON COMMIT DROP;

\copy stg_inventario (seq, cantidad, fechamovimiento, notas, processed, status, tipomovimiento, unidadesrecomendadasfactura, articulo_codigo, usuario_id) FROM 'INVENTARIO_CSV' CSV HEADER
\copy stg_lotes (seq, articulo_codigo, numerolote, fechavencimiento, cantidadinicial, cantidadactual, fechaingreso, usuario_id, notas, status, version) FROM 'LOTES_CSV' CSV HEADER
\copy stg_cierrecaja (seq, usuario_id, fechaapertura, fechacierre, montoinicial, montoesperadoefectivo, montoesperadosinpe, montoesperadotarjeta, montocontadoefectivo, montocontadosinpe, montocontadotarjeta, diferencia, estado, notas, version) FROM 'CIERRECAJA_CSV' CSV HEADER

-- Fail with a readable message before the FK checks fire, so a stale article or
-- user snapshot is obvious rather than a bare constraint name.
DO $$
BEGIN
  IF (SELECT COALESCE(MAX(codigo), 0) FROM inventario)
     + (SELECT COUNT(*) FROM stg_inventario) > 2147483647 THEN
    RAISE EXCEPTION 'inventario.codigo is integer: max(codigo)+rows would overflow';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_inventario s
    LEFT JOIN articulos a ON a.codigo = s.articulo_codigo
    WHERE a.codigo IS NULL
  ) THEN
    RAISE EXCEPTION 'inventario.csv references articulo_codigo values that no longer exist in articulos';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_inventario s
    LEFT JOIN users u ON u.id = s.usuario_id
    WHERE u.id IS NULL
  ) THEN
    RAISE EXCEPTION 'inventario.csv references usuario_id values that no longer exist in users';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_lotes s
    LEFT JOIN articulos a ON a.codigo = s.articulo_codigo
    WHERE a.codigo IS NULL
  ) THEN
    RAISE EXCEPTION 'lotes.csv references articulo_codigo values that no longer exist in articulos';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_lotes s
    LEFT JOIN users u ON u.id = s.usuario_id
    WHERE s.usuario_id IS NOT NULL AND u.id IS NULL
  ) THEN
    RAISE EXCEPTION 'lotes.csv references usuario_id values that no longer exist in users';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_cierrecaja s
    LEFT JOIN users u ON u.id = s.usuario_id
    WHERE u.id IS NULL
  ) THEN
    RAISE EXCEPTION 'cierrecaja.csv references usuario_id values that no longer exist in users';
  END IF;
END $$;

INSERT INTO inventario (
  codigo, cantidad, fechamovimiento, notas, processed, status,
  tipomovimiento, unidadesrecomendadasfactura, articulo_codigo, usuario_id
)
SELECT (SELECT COALESCE(MAX(codigo), 0) FROM inventario)
       + ROW_NUMBER() OVER (ORDER BY s.seq),
       s.cantidad,
       s.fechamovimiento,
       s.notas,
       s.processed,
       s.status,
       s.tipomovimiento,
       s.unidadesrecomendadasfactura,
       s.articulo_codigo,
       s.usuario_id
  FROM stg_inventario s;

INSERT INTO lotes (
  id, articulo_codigo, numerolote, fechavencimiento, cantidadinicial,
  cantidadactual, fechaingreso, usuario_id, notas, status, version
)
SELECT (SELECT COALESCE(MAX(id), 0) FROM lotes)
       + ROW_NUMBER() OVER (ORDER BY s.seq),
       s.articulo_codigo,
       s.numerolote,
       s.fechavencimiento,
       s.cantidadinicial,
       s.cantidadactual,
       s.fechaingreso,
       s.usuario_id,
       s.notas,
       s.status,
       s.version
  FROM stg_lotes s;

INSERT INTO cierrecaja (
  id, usuario_id, fechaapertura, fechacierre, montoinicial,
  montoesperadoefectivo, montoesperadosinpe, montoesperadotarjeta,
  montocontadoefectivo, montocontadosinpe, montocontadotarjeta, diferencia,
  estado, notas, version
)
SELECT (SELECT COALESCE(MAX(id), 0) FROM cierrecaja)
       + ROW_NUMBER() OVER (ORDER BY s.seq),
       s.usuario_id,
       s.fechaapertura,
       s.fechacierre,
       s.montoinicial,
       s.montoesperadoefectivo,
       s.montoesperadosinpe,
       s.montoesperadotarjeta,
       s.montocontadoefectivo,
       s.montocontadosinpe,
       s.montocontadotarjeta,
       s.diferencia,
       s.estado,
       s.notas,
       s.version
  FROM stg_cierrecaja s;

-- Explicit ids do not advance an identity sequence. Move each sequence to the
-- new maximum so the application's next nextval() cannot reuse a seeded id.
DO $$
DECLARE
  mx bigint;
BEGIN
  SELECT MAX(codigo) INTO mx FROM inventario;
  IF mx IS NOT NULL THEN
    PERFORM setval(pg_get_serial_sequence('inventario', 'codigo'), mx, true);
  END IF;

  SELECT MAX(id) INTO mx FROM lotes;
  IF mx IS NOT NULL THEN
    PERFORM setval(pg_get_serial_sequence('lotes', 'id'), mx, true);
  END IF;

  SELECT MAX(id) INTO mx FROM cierrecaja;
  IF mx IS NOT NULL THEN
    PERFORM setval(pg_get_serial_sequence('cierrecaja', 'id'), mx, true);
  END IF;
END $$;

COMMIT;
ANALYZE inventario;
ANALYZE lotes;
ANALYZE cierrecaja;

SELECT 'inventario' AS tabla, count(*) AS filas FROM inventario
UNION ALL SELECT 'lotes', count(*) FROM lotes
UNION ALL SELECT 'cierrecaja', count(*) FROM cierrecaja;
"""


def build_load_sql(out: Path) -> Path:
    sql = (LOAD_SQL
           .replace("INVENTARIO_CSV", sql_literal(out / "inventario.csv"))
           .replace("LOTES_CSV", sql_literal(out / "lotes.csv"))
           .replace("CIERRECAJA_CSV", sql_literal(out / "cierrecaja.csv")))
    path = out / "load.sql"
    path.write_text(sql, encoding="utf-8", newline="\n")
    return path


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(
        description="Generate load-test CSVs + load.sql for Mercurius inventory.",
    )
    ap.add_argument("--count-movements", type=int, default=1_000_000)
    ap.add_argument("--count-lotes", type=int, default=50_000)
    ap.add_argument("--count-cajas", type=int, default=20_000)
    ap.add_argument("--out", type=Path,
                    default=Path(tempfile.gettempdir()) / "mercurius-seed-inventory")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--load", action="store_true",
                    help="run psql -f load.sql after generating (off by default)")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=5433)
    ap.add_argument("--user", default="mercurius")
    ap.add_argument("--password", default=os.environ.get("PGPASSWORD", ""))
    ap.add_argument("--database", default="mercurius")
    ap.add_argument("--psql", default=default_psql(),
                    help="psql executable used for --load and the reference fallback")
    ap.add_argument("--force", action="store_true",
                    help="allow a non-local host (never recommended)")
    return ap.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if args.host not in LOCAL_HOSTS and not args.force:
        print(
            f"Refusing non-local host '{args.host}' without --force",
            file=sys.stderr,
        )
        return 2
    if args.database not in ALLOWED_DATABASES:
        print(
            f"Refusing database '{args.database}'; allowed: "
            f"{sorted(ALLOWED_DATABASES)}",
            file=sys.stderr,
        )
        return 2
    for name in ("count_movements", "count_lotes", "count_cajas"):
        if getattr(args, name) < 0:
            print(f"--{name.replace('_', '-')} must be >= 0", file=sys.stderr)
            return 2

    args.out.mkdir(parents=True, exist_ok=True)

    articulos, usuarios = fetch_reference(args)
    if (args.count_movements or args.count_lotes) and not articulos:
        print("No rows in articulos: cannot generate FK-valid movements/lotes.",
              file=sys.stderr)
        return 2
    if (args.count_movements or args.count_lotes or args.count_cajas) and not usuarios:
        print("No rows in users: cannot generate FK-valid rows.", file=sys.stderr)
        return 2

    rng = random.Random(args.seed)
    now = datetime.now().replace(microsecond=0)

    print(f"reference snapshot: {len(articulos):,} articulos, "
          f"{len(usuarios):,} usuarios")

    write_inventario(args.out / "inventario.csv", args.count_movements,
                     rng, articulos, usuarios, now)
    print(f"wrote {args.count_movements:,} movements")

    write_lotes(args.out / "lotes.csv", args.count_lotes,
                rng, articulos, usuarios, now)
    print(f"wrote {args.count_lotes:,} lotes")

    write_cierrecaja(args.out / "cierrecaja.csv", args.count_cajas,
                     rng, usuarios, now)
    print(f"wrote {args.count_cajas:,} cash sessions")

    load = build_load_sql(args.out)
    print(f"load script: {load}")
    print("load with:")
    print(
        f'  {args.psql} -h {args.host} -p {args.port} -U {args.user} '
        f'-d {args.database} -w -v ON_ERROR_STOP=1 -f "{load}"'
    )

    if args.load:
        env = dict(os.environ, PGPASSWORD=args.password)
        cmd = [
            args.psql, "-h", args.host, "-p", str(args.port),
            "-U", args.user, "-d", args.database, "-w",
            "-v", "ON_ERROR_STOP=1", "-f", str(load),
        ]
        print("running:", " ".join(cmd))
        return subprocess.call(cmd, env=env)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
