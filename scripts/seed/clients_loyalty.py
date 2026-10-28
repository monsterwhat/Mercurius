#!/usr/bin/env python3
"""
Load-test data generator for Mercurius clients, client activity and loyalty points.

Scope
-----
Fills exactly three tables, matched against the live schema (PostgreSQL 18,
``information_schema.columns`` / ``table_constraints``):

    clients               (code PK, no default)
    clienteactividad      (id PK, no default; FK cliente_code -> clients.code)
    puntos_transacciones  (id PK, no default; FK cliente_id  -> clients.code)

There is no separate loyalty-status table: the app reads the
``clients.statuspuntos`` column ('active' / 'inactive' / 'expired').
Nothing else is touched.

Safety
------
* The database name must be ``mercurius`` or ``mercurius_test``.
* The host must be loopback unless ``--force`` is given.
* ``load.sql`` repeats the database-name check in a DO block.
* No row is deleted or updated; generated keys always start above the current
  MAX(...) of the target table.
* ``--load`` exists for convenience but is never invoked by this generator
  unless the operator explicitly passes the flag.

Contract
--------
CSV files carry the target column names. The one deliberate exception is the
``code`` / ``cliente_code`` values in client_activity.csv and puntos.csv: they
hold the *local* 1..N client sequence, not database codes. load.sql maps them
with ``client_base + local`` where ``client_base`` is the pre-insert
``coalesce(max(code), 0)`` of ``clients``. This is what keeps FKs valid even
when the database already has clients.

IDs follow the required rule in SQL, not in Python:

    (select coalesce(max(<pk>), 0) from <table>) + row_number() over (...)

Usage
-----
    python scripts/seed/clients_loyalty.py
    python scripts/seed/clients_loyalty.py --count-clients 200000 \\
        --count-activity 600000 --count-points 600000 --out C:/tmp/seed-clients
    python scripts/seed/clients_loyalty.py --load   # NOT run by CI/agents

Outputs (under --out, default %TEMP%/mercurius-seed-clients):
    clients.csv  client_activity.csv  puntos.csv  load.sql
"""

from __future__ import annotations

import argparse
import csv
import os
import random
import subprocess
import sys
import tempfile
import unicodedata
from datetime import datetime, timedelta
from pathlib import Path

ALLOWED_DATABASES = {"mercurius", "mercurius_test"}
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}

# ---------------------------------------------------------------------------
# Realistic reference data (Costa Rica)
# ---------------------------------------------------------------------------

FIRST_NAMES = [
    "MARIA", "JOSE", "ANA", "CARLOS", "LUIS", "LAURA", "JORGE", "ANDREA",
    "FRANCISCO", "GABRIELA", "MANUEL", "MONICA", "ROBERTO", "PATRICIA",
    "ALBERTO", "SOFIA", "DANIEL", "VALERIA", "EDUARDO", "NATALIA",
    "ALEJANDRO", "KARLA", "GERARDO", "MARIANA", "RAFAEL", "ADRIANA",
    "OSCAR", "CARMEN", "MAURICIO", "PAOLA", "JAVIER", "VERONICA",
    "SERGIO", "TATIANA", "IGNACIO", "CLAUDIA", "ANDRES", "SILVIA",
    "FERNANDO", "ROCIO", "ESTEBAN", "LUCIA", "MIGUEL", "ELENA",
    "PABLO", "REBECA", "VICTOR", "ISABEL", "HUGO", "DANIELA",
]

LAST_NAMES = [
    "RODRIGUEZ", "VARGAS", "JIMENEZ", "MORA", "CHAVES", "SOLANO",
    "CASTRO", "HERNANDEZ", "RAMIREZ", "SANCHEZ", "GONZALEZ", "ROJAS",
    "ALVARADO", "PEREZ", "CAMPOS", "MADRIGAL", "ARAYA", "FALLAS",
    "QUESADA", "MONGE", "AGUILAR", "SALAZAR", "MURILLO", "VILLALOBOS",
    "ZUNIGA", "BARBOZA", "CORDERO", "NAVARRETE", "SEGURA", "ULLOA",
    "BOLAÑOS", "CARVAJAL", "GUZMAN", "HIDALGO", "LEITON", "MASIS",
    "OBANDO", "PANIAGUA", "RETANA", "TREJOS", "VALVERDE", "ZAMORA",
    "ESPINOZA", "FLORES", "GAMBOA", "IBARRA", "LORIA", "MENDEZ",
]

# Hacienda CIIU4 activity codes (6 digits) and their descriptions.
ACTIVIDADES = [
    ("011100", "Cultivo de cereales y otros cultivos"),
    ("101001", "Elaboración de productos de panadería"),
    ("107100", "Elaboración de productos de molinería"),
    ("310000", "Fabricación de muebles"),
    ("410000", "Construcción de edificios"),
    ("452000", "Mantenimiento y reparación de vehículos automotores"),
    ("461000", "Comercio al por mayor no especializado"),
    ("471100", "Comercio al por menor en comercios no especializados"),
    ("492300", "Transporte de carga por carretera"),
    ("522100", "Actividades de apoyo al transporte"),
    ("561100", "Actividades de restaurantes y servicio móvil de comidas"),
    ("561200", "Actividades de servicios de comidas para eventos"),
    ("620100", "Programación informática y actividades de consultoría"),
    ("681000", "Actividades inmobiliarias"),
    ("702000", "Consultoría de gestión empresarial"),
    ("731000", "Publicidad"),
    ("862000", "Actividades de práctica médica"),
    ("960200", "Peluquería y otros tratamientos de belleza"),
]

STREETS = ["Calle", "Avenida", "Callejón", "Vía"]
PROVINCE_NAMES = {
    1: "San José",
    2: "Alajuela",
    3: "Cartago",
    4: "Heredia",
    5: "Guanacaste",
    6: "Puntarenas",
    7: "Limón",
}
EMAIL_DOMAINS = ["example.com", "example.co.cr", "correo.example", "test.example"]

ID_FISICA = "Cédula Física"
ID_JURIDICA = "Cédula Jurídica"
ID_DIMEX = "DIMEX"

CLIENT_COLUMNS = [
    "code",
    "codigoactividadcomercial",
    "tipoidentificacion",
    "address",
    "birthdate",
    "canton",
    "discount",
    "distrito",
    "email",
    "idnumber",
    "idtype",
    "lastpurchasedate",
    "name",
    "password",
    "phonenumber",
    "provincia",
    "puntosacumulados",
    "refresh_token",
    "status",
    "statuspuntos",
    "taxpayer",
    "token_expiry",
    "zonecode",
    "usuario_id",
]

ACTIVITY_COLUMNS = ["local_id", "codigo", "descripcion", "cliente_code"]

POINTS_COLUMNS = [
    "local_id",
    "descripcion",
    "factura_id",
    "fecha_creacion",
    "puntos",
    "saldo_puntos",
    "tipo_transaccion",
    "cliente_code",
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def ascii_slug(text: str) -> str:
    """Lowercase ASCII token usable in an email local part."""
    decomposed = unicodedata.normalize("NFKD", text)
    flat = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return "".join(ch for ch in flat.lower() if ch.isalnum())


def cedula_fisica(seq: int) -> str:
    """9-digit CR physical ID formatted 1-XXXX-XXXX; unique per seq."""
    n = seq - 1
    return f"1-{n // 10000 % 10000:04d}-{n % 10000:04d}"


def cedula_juridica(seq: int) -> str:
    """10-digit CR legal-entity ID formatted 3-XXX-XXXXXX."""
    return f"3-{seq % 1000:03d}-{seq % 1000000:06d}"


def dimex(seq: int) -> str:
    """11-digit DIMEX number (no separators), as Hacienda expects."""
    return f"1{seq:010d}"


def fmt_ts(value: datetime | None) -> str:
    """ISO timestamp for COPY; empty string becomes SQL NULL in CSV mode."""
    return "" if value is None else value.strftime("%Y-%m-%d %H:%M:%S")


def fmt_date(value: datetime | None) -> str:
    return "" if value is None else value.strftime("%Y-%m-%d")


def sql_path(path: Path) -> str:
    """Absolute path safe to embed in a psql single-quoted literal."""
    return str(path.resolve()).replace("\\", "/").replace("'", "''")


# ---------------------------------------------------------------------------
# Pass 1: loyalty ledger (must run before clients so balances are consistent)
# ---------------------------------------------------------------------------


def write_points(
    path: Path,
    count_clients: int,
    count_points: int,
    seed: int,
    as_of: datetime,
) -> tuple[list[float], list[datetime | None], list[str]]:
    """Write puntos.csv and return per-client final balance, last earn, status.

    Transactions are grouped per client and emitted chronologically, so
    saldo_puntos is a true running balance and the last balance equals the
    client's puntosacumulados. Dates span the two years before ``as_of``.
    """
    rng = random.Random(seed * 1000 + 1)
    window_start = as_of - timedelta(days=730)
    span_seconds = int((as_of - window_start).total_seconds())
    if span_seconds <= 0:
        raise ValueError("--as-of must leave a positive two-year window")

    base, remainder = divmod(count_points, count_clients)
    balances = [0.0] * (count_clients + 1)
    last_earn = [None] * (count_clients + 1)
    last_kind = [""] * (count_clients + 1)
    status = ["inactive"] * (count_clients + 1)

    local_id = 0
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=POINTS_COLUMNS)
        writer.writeheader()

        for client in range(1, count_clients + 1):
            movements = base + (1 if client <= remainder else 0)
            if movements == 0:
                continue

            stamps = sorted(
                window_start + timedelta(seconds=rng.randrange(span_seconds))
                for _ in range(movements)
            )
            balance = 0.0
            kind = "earn"
            for index, stamp in enumerate(stamps, start=1):
                roll = rng.random()
                if index == 1 or balance <= 0.0:
                    kind = "earn"
                elif roll < 0.72:
                    kind = "earn"
                elif roll < 0.95:
                    kind = "redeem"
                else:
                    kind = "expire"

                factura_id = None
                if kind == "earn":
                    amount = round(rng.uniform(25.0, 2500.0), 2)
                    # puntos/saldo_puntos are numeric(10,2); switch to a
                    # redemption rather than overflow on pathological counts.
                    if balance + amount > 99_000_000.0:
                        kind = "redeem"
                    else:
                        balance = round(balance + amount, 2)
                        last_earn[client] = stamp
                        factura_id = f"FAC-{client:07d}-{index:04d}"
                        description = "Puntos ganados por compra"
                        points = amount

                if kind == "redeem":
                    amount = round(min(balance, rng.uniform(20.0, 800.0)), 2)
                    if amount <= 0.0:
                        amount = round(rng.uniform(25.0, 2500.0), 2)
                        balance = round(balance + amount, 2)
                        kind = "earn"
                        last_earn[client] = stamp
                        factura_id = f"FAC-{client:07d}-{index:04d}"
                        description = "Puntos ganados por compra"
                        points = amount
                    else:
                        balance = round(balance - amount, 2)
                        description = "Puntos utilizados como descuento"
                        points = -amount

                if kind == "expire":
                    points = -balance
                    balance = 0.0
                    description = "Puntos expirados por inactividad"

                local_id += 1
                last_kind[client] = kind
                writer.writerow(
                    {
                        "local_id": local_id,
                        "descripcion": description,
                        "factura_id": "" if factura_id is None else factura_id,
                        "fecha_creacion": fmt_ts(stamp),
                        "puntos": f"{points:.2f}",
                        "saldo_puntos": f"{balance:.2f}",
                        "tipo_transaccion": kind,
                        "cliente_code": client,
                    }
                )

            balances[client] = round(balance, 2)
            if last_kind[client] == "expire" and balance == 0.0:
                status[client] = "expired"
            elif last_earn[client] is not None and (as_of - last_earn[client]).days <= 180:
                status[client] = "active"
            else:
                status[client] = "inactive"

    return balances, last_earn, status


# ---------------------------------------------------------------------------
# Pass 2: client activity (random existing client codes)
# ---------------------------------------------------------------------------


def write_activity(
    path: Path,
    count_clients: int,
    count_activity: int,
    seed: int,
) -> None:
    """Write client_activity.csv with a random local client index per row."""
    rng = random.Random(seed * 1000 + 2)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=ACTIVITY_COLUMNS)
        writer.writeheader()
        for local_id in range(1, count_activity + 1):
            codigo, descripcion = ACTIVIDADES[rng.randrange(len(ACTIVIDADES))]
            writer.writerow(
                {
                    "local_id": local_id,
                    "codigo": codigo,
                    "descripcion": descripcion,
                    "cliente_code": rng.randint(1, count_clients),
                }
            )


# ---------------------------------------------------------------------------
# Pass 3: clients
# ---------------------------------------------------------------------------


def write_clients(
    path: Path,
    count_clients: int,
    seed: int,
    as_of: datetime,
    balances: list[float],
    last_earn: list[datetime | None],
    status: list[str],
) -> None:
    rng = random.Random(seed * 1000 + 3)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=CLIENT_COLUMNS)
        writer.writeheader()

        for code in range(1, count_clients + 1):
            first = FIRST_NAMES[rng.randrange(len(FIRST_NAMES))]
            last1 = LAST_NAMES[rng.randrange(len(LAST_NAMES))]
            last2 = LAST_NAMES[rng.randrange(len(LAST_NAMES))]

            roll = rng.random()
            if roll < 0.10:
                id_type = ID_JURIDICA
                id_number = cedula_juridica(code)
                name = f"{last1} {last2} S.A."
                taxpayer = True
                age_days = rng.randrange(365, 35 * 365)
            elif roll < 0.15:
                id_type = ID_DIMEX
                id_number = dimex(code)
                name = f"{first} {last1} {last2}"
                taxpayer = False
                age_days = rng.randrange(18 * 365, 75 * 365)
            else:
                id_type = ID_FISICA
                id_number = cedula_fisica(code)
                name = f"{first} {last1} {last2}"
                taxpayer = rng.random() < 0.12
                age_days = rng.randrange(18 * 365, 80 * 365)

            birthdate = as_of - timedelta(days=age_days)
            purchase = last_earn[code]
            if purchase is None:
                # Never transacted: an old date keeps the expiry scheduler's
                # "lastpurchasedate < cutoff" predicate meaningful.
                purchase = as_of - timedelta(days=rng.randrange(400, 730))

            points_status = status[code]
            email_local = (
                f"{ascii_slug(first)}.{ascii_slug(last1)}.{code:07d}"
            )[:180]
            domain = EMAIL_DOMAINS[rng.randrange(len(EMAIL_DOMAINS))]

            province = rng.randint(1, 7)
            canton = rng.randint(1, 20)
            district = rng.randint(1, 12)

            phone_prefix = rng.choice("678")
            phone = f"{phone_prefix}{rng.randrange(10_000_000):07d}"

            discount = 0.0 if rng.random() < 0.45 else round(rng.uniform(0.0, 20.0), 2)
            primary_activity = ACTIVIDADES[(code * 7) % len(ACTIVIDADES)][0]

            writer.writerow(
                {
                    "code": code,
                    "codigoactividadcomercial": primary_activity,
                    "tipoidentificacion": id_type,
                    "address": (
                        f"{STREETS[rng.randrange(len(STREETS))]} "
                        f"{rng.randint(1, 60)}, Avenida {rng.randint(1, 40)}, "
                        f"Casa {rng.randint(1, 300)}, "
                        f"{PROVINCE_NAMES[province]}"
                    ),
                    "birthdate": fmt_date(birthdate),
                    "canton": f"{canton:02d}",
                    "discount": f"{discount:.2f}",
                    "distrito": f"{district:02d}",
                    "email": f"{email_local}@{domain}",
                    "idnumber": id_number,
                    "idtype": id_type,
                    "lastpurchasedate": fmt_ts(purchase),
                    "name": name,
                    "password": "",
                    "phonenumber": f"{phone[:4]}-{phone[4:]}",
                    "provincia": str(province),
                    "puntosacumulados": f"{balances[code]:.2f}",
                    "refresh_token": "",
                    "status": "true" if rng.random() < 0.93 else "false",
                    "statuspuntos": points_status,
                    "taxpayer": "true" if taxpayer else "false",
                    "token_expiry": "",
                    "zonecode": rng.randint(1, 7),
                    "usuario_id": "",
                }
            )


# ---------------------------------------------------------------------------
# SQL contract
# ---------------------------------------------------------------------------

LOAD_SQL = r"""
\set ON_ERROR_STOP on
set client_encoding = 'UTF8';

-- Defence in depth: the Python CLI checks the host before anything runs, but
-- load.sql may be executed directly with psql, so pin the database here too.
do $$
begin
    if current_database() not in ('mercurius', 'mercurius_test') then
        raise exception
            'clients_loyalty seed refused: database % is not one of mercurius/mercurius_test',
            current_database();
    end if;
end
$$;

begin;

-- Block concurrent writers for the (short) load: the id/code bases below are
-- MAX(...) snapshots, and the whole point of the seed is that children can be
-- remapped deterministically. Reads stay unaffected.
lock table clients in exclusive mode;
lock table clienteactividad in exclusive mode;
lock table puntos_transacciones in exclusive mode;

-- ---------------------------------------------------------------------------
-- Staging: mirror of every target column that we fill. Empty CSV fields are
-- NULL in COPY CSV mode. `on commit drop` keeps load.sql re-runnable without
-- leaving anything behind.
-- ---------------------------------------------------------------------------
create temp table stg_clients (
    code bigint,
    codigoactividadcomercial text,
    tipoidentificacion text,
    address text,
    birthdate timestamp,
    canton text,
    discount double precision,
    distrito text,
    email text,
    idnumber text,
    idtype text,
    lastpurchasedate timestamp,
    name text,
    password text,
    phonenumber text,
    provincia text,
    puntosacumulados numeric(38,2),
    refresh_token text,
    status boolean,
    statuspuntos text,
    taxpayer boolean,
    token_expiry timestamp,
    zonecode integer,
    usuario_id bigint
) on commit drop;

create temp table stg_activity (
    local_id bigint,
    codigo text,
    descripcion text,
    cliente_code bigint          -- local 1..N, remapped below
) on commit drop;

create temp table stg_points (
    local_id bigint,
    descripcion text,
    factura_id text,
    fecha_creacion timestamp,
    puntos numeric(10,2),
    saldo_puntos numeric(10,2),
    tipo_transaccion text,
    cliente_code bigint          -- local 1..N, remapped below
) on commit drop;

\copy stg_clients (code, codigoactividadcomercial, tipoidentificacion, address, birthdate, canton, discount, distrito, email, idnumber, idtype, lastpurchasedate, name, password, phonenumber, provincia, puntosacumulados, refresh_token, status, statuspuntos, taxpayer, token_expiry, zonecode, usuario_id) from 'CLIENTS_CSV' with (format csv, header true, encoding 'UTF8')
\copy stg_activity (local_id, codigo, descripcion, cliente_code) from 'ACTIVITY_CSV' with (format csv, header true, encoding 'UTF8')
\copy stg_points (local_id, descripcion, factura_id, fecha_creacion, puntos, saldo_puntos, tipo_transaccion, cliente_code) from 'POINTS_CSV' with (format csv, header true, encoding 'UTF8')

-- MAX(code) is read once, before clients are inserted; the same value drives
-- both the new PKs and the local->database remap used by the two child tables.
create temp table run as
select (select coalesce(max(code), 0) from clients) as client_base;

-- ---------------------------------------------------------------------------
-- clients: code has no default, so MAX(code) + row_number() is the key.
-- row_number() over (order by local code 1..N) == the local code.
-- ---------------------------------------------------------------------------
insert into clients (
    code, codigoactividadcomercial, tipoidentificacion, address, birthdate,
    canton, discount, distrito, email, idnumber, idtype, lastpurchasedate,
    name, password, phonenumber, provincia, puntosacumulados, refresh_token,
    status, statuspuntos, taxpayer, token_expiry, zonecode, usuario_id
)
select (select coalesce(max(code), 0) from clients)
           + row_number() over (order by s.code),
       s.codigoactividadcomercial, s.tipoidentificacion, s.address, s.birthdate,
       s.canton, s.discount, s.distrito, s.email, s.idnumber, s.idtype,
       s.lastpurchasedate, s.name, s.password, s.phonenumber, s.provincia,
       s.puntosacumulados, s.refresh_token, s.status, s.statuspuntos,
       s.taxpayer, s.token_expiry, s.zonecode, s.usuario_id
  from stg_clients s;

-- ---------------------------------------------------------------------------
-- clienteactividad: id is generated; cliente_code remapped to clients.code.
-- ---------------------------------------------------------------------------
insert into clienteactividad (id, codigo, descripcion, cliente_code)
select (select coalesce(max(id), 0) from clienteactividad)
           + row_number() over (order by a.local_id),
       a.codigo, a.descripcion, r.client_base + a.cliente_code
  from stg_activity a
 cross join run r;

-- ---------------------------------------------------------------------------
-- puntos_transacciones: id is generated; cliente_id remapped to clients.code.
-- ---------------------------------------------------------------------------
insert into puntos_transacciones (
    id, descripcion, factura_id, fecha_creacion, puntos, saldo_puntos,
    tipo_transaccion, cliente_id
)
select (select coalesce(max(id), 0) from puntos_transacciones)
           + row_number() over (order by p.local_id),
       p.descripcion, p.factura_id, p.fecha_creacion, p.puntos,
       p.saldo_puntos, p.tipo_transaccion, r.client_base + p.cliente_code
  from stg_points p
 cross join run r;

commit;

analyze clients;
analyze clienteactividad;
analyze puntos_transacciones;

-- ---------------------------------------------------------------------------
-- Post-load report, scoped to the generated batch (everything above the
-- pre-insert MAX). It also fails loudly if an FK remap went wrong.
-- ---------------------------------------------------------------------------
select 'clients' as table_name,
       count(*) as rows,
       min(code) as min_key,
       max(code) as max_key
  from clients c
 cross join run r
 where c.code > r.client_base
union all
select 'clienteactividad', count(*), min(a.id), max(a.id)
  from clienteactividad a
 cross join run r
 where a.cliente_code > r.client_base
union all
select 'puntos_transacciones', count(*), min(p.id), max(p.id)
  from puntos_transacciones p
 cross join run r
 where p.cliente_id > r.client_base;

select count(*) as orphan_activity
  from clienteactividad a
 cross join run r
  left join clients c on c.code = a.cliente_code
 where a.cliente_code > r.client_base
   and c.code is null;

select count(*) as orphan_points
  from puntos_transacciones p
 cross join run r
  left join clients c on c.code = p.cliente_id
 where p.cliente_id > r.client_base
   and c.code is null;

select count(*) as negative_balances
  from puntos_transacciones p
 cross join run r
 where p.cliente_id > r.client_base
   and p.saldo_puntos < 0;

select count(*) as clients_without_ledger
  from clients c
 cross join run r
 where c.code > r.client_base
   and not exists (
       select 1 from puntos_transacciones p where p.cliente_id = c.code
   );
"""


def build_load_sql(
    out: Path,
    clients_csv: Path,
    activity_csv: Path,
    points_csv: Path,
) -> Path:
    sql = (
        LOAD_SQL.replace("CLIENTS_CSV", sql_path(clients_csv))
        .replace("ACTIVITY_CSV", sql_path(activity_csv))
        .replace("POINTS_CSV", sql_path(points_csv))
    )
    path = out / "load.sql"
    path.write_text(sql, encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_as_of(value: str) -> datetime:
    try:
        parsed = datetime.strptime(value, "%Y-%m-%d")
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"--as-of must be YYYY-MM-DD, got {value!r}"
        ) from exc
    return parsed.replace(hour=12, minute=0, second=0)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate clients / client activity / loyalty points seed CSVs "
        "for Mercurius (local databases only).",
    )
    parser.add_argument("--count-clients", type=int, default=100_000)
    parser.add_argument("--count-activity", type=int, default=300_000)
    parser.add_argument("--count-points", type=int, default=300_000)
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(tempfile.gettempdir()) / "mercurius-seed-clients",
        help="output directory (default: %%TEMP%%/mercurius-seed-clients)",
    )
    parser.add_argument("--database", default="mercurius")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5433)
    parser.add_argument("--user", default="mercurius")
    parser.add_argument(
        "--password",
        default=os.environ.get("PGPASSWORD", ""),
        help="psql password; prefer the PGPASSWORD environment variable",
    )
    parser.add_argument("--psql", default="psql", help="path to the psql executable")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--as-of",
        type=parse_as_of,
        default=datetime.now().replace(hour=12, minute=0, second=0, microsecond=0),
        help="end of the two-year date window (YYYY-MM-DD; default: today)",
    )
    parser.add_argument(
        "--load",
        action="store_true",
        help="run load.sql with psql after generating (never implicit)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="allow a non-loopback host (live seeding is not recommended)",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()

    if args.database not in ALLOWED_DATABASES:
        print(
            f"Refusing database '{args.database}'; allowed: "
            f"{sorted(ALLOWED_DATABASES)}",
            file=sys.stderr,
        )
        return 2
    if args.host not in LOCAL_HOSTS and not args.force:
        print(
            f"Refusing non-local host '{args.host}' without --force",
            file=sys.stderr,
        )
        return 2
    if args.count_clients < 1:
        print("--count-clients must be >= 1", file=sys.stderr)
        return 2
    if args.count_activity < 0 or args.count_points < 0:
        print("--count-activity/--count-points must be >= 0", file=sys.stderr)
        return 2
    if args.port < 1 or args.port > 65535:
        print("--port must be between 1 and 65535", file=sys.stderr)
        return 2

    out: Path = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    clients_csv = out / "clients.csv"
    activity_csv = out / "client_activity.csv"
    points_csv = out / "puntos.csv"

    # psql \copy quotes the filename in a single-quoted meta-command argument;
    # apostrophes are not portable there, so reject them instead of emitting a
    # load.sql that fails halfway.
    for path in (clients_csv, activity_csv, points_csv):
        if "'" in str(path):
            print(
                f"Refusing output path with an apostrophe: {path}",
                file=sys.stderr,
            )
            return 2

    print(
        f"generating {args.count_clients} clients, {args.count_activity} "
        f"activities, {args.count_points} point movements (seed={args.seed})"
    )

    balances, last_earn, status = write_points(
        points_csv, args.count_clients, args.count_points, args.seed, args.as_of
    )
    print(f"  {points_csv}  {args.count_points} rows")

    write_activity(activity_csv, args.count_clients, args.count_activity, args.seed)
    print(f"  {activity_csv}  {args.count_activity} rows")

    write_clients(
        clients_csv,
        args.count_clients,
        args.seed,
        args.as_of,
        balances,
        last_earn,
        status,
    )
    print(f"  {clients_csv}  {args.count_clients} rows")

    load_sql = build_load_sql(out, clients_csv, activity_csv, points_csv)
    print(f"  {load_sql}  (staging + COPY + INSERT ... SELECT)")

    print(
        "\nFK contract: clients.code starts at pre-insert MAX(code)+1; "
        "child rows store the local 1..N sequence and load.sql remaps it "
        "with client_base + local."
    )

    if args.load:
        env = dict(os.environ, PGPASSWORD=args.password)
        command = [
            args.psql,
            "-h",
            args.host,
            "-p",
            str(args.port),
            "-U",
            args.user,
            "-d",
            args.database,
            "-v",
            "ON_ERROR_STOP=1",
            "-f",
            str(load_sql),
        ]
        print("running:", " ".join(command))
        return subprocess.call(command, env=env)

    print("not loaded (--load not given); no database connection was opened.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
