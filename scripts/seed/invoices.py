#!/usr/bin/env python3
"""
Load-test data generator for Mercurius emitted and received invoices.

What it writes
--------------
One CSV per populated table plus ``load.sql``. The graph is the full Hacienda
v4.4 invoice graph that the application reads:

    ubicacion, telefono
    identificacion_emisor, emisor
    identificacion_receptor, receptor
    correo_electronico_emisor, correo_electronico_receptor
    encabezado
    resumen_factura
    total_desglose_impuesto, medio_pago_resumen
    medio_pago_encabezado
    detalle_servicio
    linea_detalle
    codigo_comercial, impuesto
    comprobantesemitidos, comprobantesrecibidos

The generator only reads reference data (articles, prices, CABYS codes and
optionally existing clients) from the target database. It never inserts. The
separate ``load.sql`` does all inserts in one transaction, parents first.

Safety
------
* Host must be loopback (``localhost`` / ``127.0.0.1`` / ``::1``) unless
  ``--force`` is given.
* Database must be ``mercurius`` or ``mercurius_test``. The same check is
  repeated inside ``load.sql``.
* Existing rows are never deleted or updated.
* IDs are allocated as ``max(id) + local seq`` at load time, where the local
  seq is the 1-based CSV row number. This is the rule used by the other
  scripts in this directory.
* ``--load`` exists for convenience but is deliberately opt-in; this task does
  not run it.

Data shape
----------
* Emitted invoices reuse a random existing ``articulos`` row per line. The
  article code is written to ``codigo_comercial`` (tipo ``04``, internal code)
  and the article name to ``linea_detalle.detalle``.
* If ``clients`` has rows, emitted receivers are built from a deterministic
  sample of those clients. There is no client FK on ``encabezado``/``receptor``,
  so the client code selects the source row but cannot be stored as a FK.
  Otherwise synthetic receivers are generated.
* Received invoices use synthetic suppliers (unique ``emisor`` rows) in a
  supplier pool and a single synthetic "our company" ``receptor``.
* Hacienda claves are 50 digits: 506 + DDMMYY + 12-digit issuer id +
  20-digit consecutive + 9-digit security code. They are unique by
  construction (the consecutive is globally unique).
* Totals are computed from line items: 13% IVA on taxed lines, 0% on exempt
  lines, no discounts. ``total_desglose_impuesto`` and both payment tables are
  reconciled to those totals.
* Hacienda state mix: 95% ACEPTADO, 2% RECHAZADO, 3% PENDIENTE.

Usage
-----
    # Generate with defaults (200000 emitted, 50000 received)
    python scripts/seed/invoices.py --password '...'

    # Smaller run
    python scripts/seed/invoices.py --count-emitidas 2000 --count-recibidas 500 \
        --lines-min 1 --lines-max 6 --out C:/tmp/invoices

    # Generate only, never load
    python scripts/seed/invoices.py --out scripts/seed/invoices-out

    # Explicit load (NOT run by this generator unless requested)
    psql -h localhost -p 5433 -U mercurius -d mercurius \
         -v ON_ERROR_STOP=1 -f scripts/seed/invoices-out/load.sql

Requirements: Python 3.10+ (stdlib only). ``psql`` is used for read-only
reference queries and for the optional ``--load``.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import random
import shutil
import subprocess
import sys
from datetime import date, datetime, time, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import NamedTuple

ALLOWED_DATABASES = {"mercurius", "mercurius_test"}
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}

MONEY = Decimal("0.00001")
QTY = Decimal("0.001")
TAX_13 = Decimal("0.13")

ESTADOS = ("ACEPTADO", "RECHAZADO", "PENDIENTE")
ESTADO_MIX = (("ACEPTADO", 95), ("RECHAZADO", 2), ("PENDIENTE", 3))

MEDIOS_PAGO = (
    ("01", 84),   # efectivo
    ("04", 8),    # transferencia / depósito
    ("02", 4),    # tarjeta
    ("06", 2),    # SINPE móvil
    ("05", 1),    # recaudado por terceros
    ("99", 1),    # otros
)

CONDICIONES_VENTA = (("01", 90), ("02", 8), ("99", 2))

ACTIVIDADES = (
    "011100", "101001", "107100", "310000", "410000", "452000",
    "461000", "471100", "492300", "522100", "561100", "561200",
    "620100", "681000", "702000", "731000", "862000", "960200",
)

QUANTITIES = (
    Decimal("1"), Decimal("1"), Decimal("1"), Decimal("2"), Decimal("2"),
    Decimal("3"), Decimal("3"), Decimal("4"), Decimal("5"), Decimal("6"),
    Decimal("8"), Decimal("10"), Decimal("12"), Decimal("0.5"),
    Decimal("1.5"), Decimal("2.5"),
)

COMPANY_NAMES = (
    "DISTRIBUIDORA CENTRAL", "COMERCIAL LA SABANA", "ALIMENTOS DEL VALLE",
    "SUMINISTROS DEL PACIFICO", "INVERSIONES TORRES", "GRUPO AGROINDUSTRIAL",
    "BODEGA SAN JOSE", "TRANSPORTES DEL NORTE", "FERRETERIA EL CONSTRUCTOR",
    "PAPELERIA LA ESQUINA", "IMPORTADORA DEL CARIBE", "LACTEOS DE LA MONTANA",
    "CARNES Y EMBUTIDOS", "FRUTAS TROPICALES", "BEBIDAS Y LICORES",
    "PRODUCTOS DE LIMPIEZA", "SERVICIOS TECNICOS", "TECNOLOGIA APLICADA",
    "CONSULTORES ASOCIADOS", "MANTENIMIENTO INDUSTRIAL", "PLASTICOS MODERNOS",
    "MUEBLES Y OFICINA", "TEXTILES DEL SUR", "CALZADO POPULAR",
    "FARMACIA CENTRAL", "EQUIPOS MEDICOS", "AGROQUIMICOS DEL TROPICO",
    "SEMILLAS Y ABONOS", "PANADERIA EL TRIGO", "CAFE DE ALTURA",
    "AZUCAR Y DERIVADOS", "ACEITES VEGETALES", "CONSERVAS DEL MAR",
    "GRANOS BASICOS", "ESPECIAS Y CONDIMENTOS", "DULCES TRADICIONALES",
    "HELADOS Y POSTRES", "JUGOS NATURALES", "AGUA PURIFICADA",
    "CERVEZAS ARTESANALES", "VINOS Y LICORES", "TABACOS SELECTOS",
)

SUPPLIER_PRODUCTS = (
    "Harina de trigo 50 lb", "Arroz blanco grano largo 25 kg",
    "Frijol negro 25 kg", "Azucar blanca 50 kg", "Aceite vegetal 20 L",
    "Atun en agua caja 48 uds", "Leche entera 1 L caja 12 uds",
    "Cafe molido 500 g caja 24 uds", "Detergente 5 kg", "Jabon de bano caja 72 uds",
    "Papel higienico 48 rollos", "Servilletas 3000 uds", "Vasos desechables 1000 uds",
    "Bolsas plasticas 1000 uds", "Alimento para perro 20 kg",
    "Alimento para gato 10 kg", "Focos LED caja 50 uds", "Pilas AA caja 100 uds",
    "Cuadernos 100 hojas caja 50 uds", "Boligrafos caja 144 uds",
    "Guantes de nitrilo caja 100 uds", "Alcohol gel 1 L caja 12 uds",
    "Cloro 5 L caja 4 uds", "Desinfectante 4 L caja 6 uds",
)

FIRST_NAMES = (
    "MARIA", "JOSE", "ANA", "CARLOS", "LUIS", "LAURA", "JORGE", "ANDREA",
    "FRANCISCO", "GABRIELA", "MANUEL", "MONICA", "ROBERTO", "PATRICIA",
    "ALBERTO", "SOFIA", "DANIEL", "VALERIA", "EDUARDO", "NATALIA",
    "ALEJANDRO", "KARLA", "GERARDO", "MARIANA", "RAFAEL", "ADRIANA",
    "OSCAR", "CARMEN", "MAURICIO", "PAOLA", "JAVIER", "VERONICA",
    "SERGIO", "TATIANA", "IGNACIO", "CLAUDIA", "ANDRES", "SILVIA",
    "FERNANDO", "ROCIO", "ESTEBAN", "LUCIA", "MIGUEL", "ELENA",
)

LAST_NAMES = (
    "RODRIGUEZ", "VARGAS", "JIMENEZ", "MORA", "CHAVES", "SOLANO",
    "CASTRO", "HERNANDEZ", "RAMIREZ", "SANCHEZ", "GONZALEZ", "ROJAS",
    "ALVARADO", "PEREZ", "CAMPOS", "MADRIGAL", "ARAYA", "FALLAS",
    "QUESADA", "MONGE", "AGUILAR", "SALAZAR", "MURILLO", "VILLALOBOS",
    "ZUNIGA", "BARBOZA", "CORDERO", "NAVARRETE", "SEGURA", "ULLOA",
    "BOLANOS", "CARVAJAL", "GUZMAN", "HIDALGO", "LEITON", "MASIS",
    "OBANDO", "PANIAGUA", "RETANA", "TREJOS", "VALVERDE", "ZAMORA",
    "ESPINOZA", "FLORES", "GAMBOA", "IBARRA", "LORIA", "MENDEZ",
)

CLIENT_ID_TYPES = {
    "01": "01", "fisica": "01", "física": "01", "cedula fisica": "01",
    "cédula física": "01", "02": "02", "juridica": "02", "jurídica": "02",
    "cedula juridica": "02", "cédula jurídica": "02", "03": "03",
    "dimex": "03", "04": "04", "nite": "04",
}

COMPANY_EMISOR_NAME = "MERCURIUS DEMO SOCIEDAD ANONIMA"
COMPANY_EMISOR_ID = "3101999999"
COMPANY_ACTIVITY = "471100"

FOOTER_TABLE_ORDER = (
    "ubicacion",
    "telefono",
    "identificacion_emisor",
    "emisor",
    "identificacion_receptor",
    "receptor",
    "correo_electronico_emisor",
    "correo_electronico_receptor",
    "encabezado",
    "resumen_factura",
    "total_desglose_impuesto",
    "medio_pago_resumen",
    "medio_pago_encabezado",
    "detalle_servicio",
    "linea_detalle",
    "codigo_comercial",
    "impuesto",
    "comprobantesemitidos",
    "comprobantesrecibidos",
)


class Article(NamedTuple):
    codigo: int
    nombre: str
    unidad: str
    unidad_comercial: str
    exento: bool
    costo: Decimal
    final: Decimal


class ClientRef(NamedTuple):
    code: int
    nombre: str
    idnumber: str
    idtype: str
    address: str
    provincia: str
    canton: str
    distrito: str
    email: str
    phone: str


class EntityRef(NamedTuple):
    entity_seq: int
    ident_seq: int
    ubic_seq: int
    tel_seq: int


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #

def default_psql() -> str:
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


def money(value: Decimal) -> Decimal:
    return value.quantize(MONEY, rounding=ROUND_HALF_UP)


def as_text(value: object) -> str:
    return "" if value is None else str(value)


def truncate(value: str, length: int) -> str:
    return value[:length]


def peso_choice(rng: random.Random, choices: tuple[tuple[str, int], ...]) -> str:
    return rng.choices([c for c, _ in choices], weights=[w for _, w in choices], k=1)[0]


def assign_estados(count: int, rng: random.Random) -> list[str]:
    """Exact 95/2/3 mix, then shuffled deterministically."""
    rechazado = int(round(count * 0.02))
    pendiente = int(round(count * 0.03))
    aceptado = count - rechazado - pendiente
    states = (["ACEPTADO"] * aceptado
              + ["RECHAZADO"] * rechazado
              + ["PENDIENTE"] * pendiente)
    rng.shuffle(states)
    return states


def mensaje_receptor_limite(fecha: datetime) -> date:
    """8 business days after the first day of the month following emission."""
    first_next = (fecha.date().replace(day=28) + timedelta(days=4)).replace(day=1)
    current = first_next
    business = 0
    while business < 8:
        if current.weekday() < 5:
            business += 1
            if business == 8:
                break
        current += timedelta(days=1)
    return current


def write_csv(path: Path, header: list[str], rows) -> int:
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, lineterminator="\n")
        writer.writerow(header)
        count = 0
        for row in rows:
            writer.writerow(row)
            count += 1
    return count


# --------------------------------------------------------------------------- #
# Read-only reference data (psql; no third-party driver required)
# --------------------------------------------------------------------------- #

def psql_base(args: argparse.Namespace) -> list[str]:
    return [
        args.psql,
        "-h", args.host,
        "-p", str(args.port),
        "-U", args.user,
        "-d", args.database,
        "-X", "-q", "-A", "-t",
    ]


def psql_copy(args: argparse.Namespace, sql: str) -> list[list[str]]:
    env = dict(os.environ, PGPASSWORD=args.password)
    result = subprocess.run(
        psql_base(args) + ["-c", sql],
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "psql reference query failed")
    return [row for row in csv.reader(io.StringIO(result.stdout)) if row]


def fetch_articles(args: argparse.Namespace) -> list[Article]:
    sql = """
        COPY (
            SELECT a.codigo,
                   a.nombre,
                   COALESCE(NULLIF(BTRIM(a.unidadmedida), ''), 'Unid'),
                   COALESCE(NULLIF(BTRIM(a.unidadmedidacomercial), ''), 'Unid'),
                   COALESCE(a.exento, false),
                   COALESCE(p.precio_costo_sin_iva, 0),
                   COALESCE(p.precio_final, 0)
              FROM articulos a
              LEFT JOIN (
                   SELECT DISTINCT ON (articulo_id)
                          articulo_id, precio_costo_sin_iva, precio_final
                     FROM articuloprecio
                    ORDER BY articulo_id, id
              ) p ON p.articulo_id = a.codigo
             WHERE a.nombre IS NOT NULL
               AND BTRIM(a.nombre) <> ''
             ORDER BY a.codigo
        ) TO STDOUT WITH (FORMAT csv)
    """
    rows = psql_copy(args, sql)
    out: list[Article] = []
    for row in rows:
        if len(row) < 7:
            continue
        try:
            out.append(Article(
                codigo=int(row[0]),
                nombre=row[1],
                unidad=row[2],
                unidad_comercial=row[3],
                exento=row[4].strip().lower() in {"t", "true", "1"},
                costo=Decimal(row[5] or "0"),
                final=Decimal(row[6] or "0"),
            ))
        except (ValueError, ArithmeticError):
            continue
    return out


def fetch_clients(args: argparse.Namespace) -> list[ClientRef]:
    sql = """
        COPY (
            SELECT code, name, idnumber, idtype, address,
                   provincia, canton, distrito, email, phonenumber
              FROM clients
             ORDER BY code
        ) TO STDOUT WITH (FORMAT csv)
    """
    rows = psql_copy(args, sql)
    out: list[ClientRef] = []
    for row in rows:
        if len(row) < 10:
            continue
        try:
            out.append(ClientRef(
                code=int(row[0]),
                nombre=row[1],
                idnumber=row[2],
                idtype=row[3],
                address=row[4],
                provincia=row[5],
                canton=row[6],
                distrito=row[7],
                email=row[8],
                phone=row[9],
            ))
        except ValueError:
            continue
    return out


def fetch_cabys(args: argparse.Namespace) -> list[str]:
    sql = """
        COPY (
            SELECT DISTINCT codigocabys
              FROM linea_detalle
             WHERE codigocabys IS NOT NULL
               AND LENGTH(codigocabys) = 13
             ORDER BY 1
        ) TO STDOUT WITH (FORMAT csv)
    """
    rows = psql_copy(args, sql)
    codes = [row[0] for row in rows if row and row[0]]
    if codes:
        return codes
    sql = """
        COPY (
            SELECT codigo
              FROM cabys
             WHERE LENGTH(codigo) = 13
             ORDER BY codigo
        ) TO STDOUT WITH (FORMAT csv)
    """
    return [row[0] for row in psql_copy(args, sql) if row and row[0]]


# --------------------------------------------------------------------------- #
# Dimension pools
# --------------------------------------------------------------------------- #

def map_id_type(value: str) -> str:
    key = (value or "").strip().lower()
    if key in CLIENT_ID_TYPES:
        return CLIENT_ID_TYPES[key]
    for word, code in CLIENT_ID_TYPES.items():
        if word and word in key:
            return code
    return "01"


def unique_identification(rng: random.Random, kind: str, used: set[str]) -> str:
    while True:
        if kind == "02":
            candidate = f"3{rng.randrange(0, 1_000_000_000):09d}"
        elif kind == "03":
            candidate = f"{rng.randrange(10**10, 10**12):012d}"
        elif kind == "04":
            candidate = f"{rng.randrange(10**9, 10**10):010d}"
        else:
            candidate = f"{rng.randrange(100_000_000, 1_000_000_000):09d}"
        if candidate not in used:
            used.add(candidate)
            return candidate


def synthetic_address(rng: random.Random) -> dict[str, str]:
    return {
        "provincia": str(rng.randint(1, 7)),
        "canton": f"{rng.randint(1, 15):02d}",
        "distrito": f"{rng.randint(1, 20):02d}",
        "barrio": f"BARRIO {rng.choice(('CENTRAL', 'EL CARMEN', 'SAN JOSE', 'LA UNION', 'LOS ANGELES'))}",
        "otras_senas": "DEL PARQUE 100 M NORTE, EDIFICIO COMERCIAL",
    }


def synthetic_phone(rng: random.Random) -> str:
    return f"{rng.choice((2, 6, 7, 8))}{rng.randrange(1_000_000, 10_000_000):07d}"


class DimensionBuilder:
    """Accumulates the small dimension tables and hands out local 1..N seqs."""

    def __init__(self) -> None:
        self.ubicaciones: list[dict[str, str]] = []
        self.telefonos: list[str] = []
        self.ident_emisores: list[tuple[str, str]] = []
        self.ident_receptores: list[tuple[str, str]] = []
        self.emisores: list[dict[str, object]] = []
        self.receptores: list[dict[str, object]] = []
        self.mails_emisor: list[tuple[str, int]] = []
        self.mails_receptor: list[tuple[str, int]] = []
        self.used_emitter_ids: set[str] = set()
        self.used_receptor_ids: set[str] = set()

    def _add_ubic(self, address: dict[str, str]) -> int:
        self.ubicaciones.append(address)
        return len(self.ubicaciones)

    def _add_tel(self, phone: str) -> int:
        self.telefonos.append(phone)
        return len(self.telefonos)

    def add_emisor(self, rng: random.Random, nombre: str, tipo: str,
                   numero: str, email: str, address: dict[str, str] | None = None,
                   phone: str | None = None, nombre_comercial: str = "") -> EntityRef:
        if not numero or numero in self.used_emitter_ids:
            numero = unique_identification(rng, tipo, self.used_emitter_ids)
        else:
            self.used_emitter_ids.add(numero)
        self.ident_emisores.append((tipo, numero))
        ident_seq = len(self.ident_emisores)
        ubic_seq = self._add_ubic(address or synthetic_address(rng))
        tel_seq = self._add_tel(phone or synthetic_phone(rng))
        self.emisores.append({
            "nombre": truncate(nombre, 100),
            "nombre_comercial": truncate(nombre_comercial or nombre, 80),
            "identificacion_seq": ident_seq,
            "ubicacion_seq": ubic_seq,
            "telefono_seq": tel_seq,
        })
        entity_seq = len(self.emisores)
        if email:
            self.mails_emisor.append((email[:160], entity_seq))
        return EntityRef(entity_seq, ident_seq, ubic_seq, tel_seq)

    def add_receptor(self, rng: random.Random, nombre: str, tipo: str,
                     numero: str, email: str, address: dict[str, str] | None = None,
                     phone: str | None = None, nombre_comercial: str = "",
                     clients_code: int | None = None) -> EntityRef:
        if not numero or numero in self.used_receptor_ids:
            numero = unique_identification(rng, tipo, self.used_receptor_ids)
        else:
            self.used_receptor_ids.add(numero)
        self.ident_receptores.append((tipo, numero))
        ident_seq = len(self.ident_receptores)
        ubic_seq = self._add_ubic(address or synthetic_address(rng))
        tel_seq = self._add_tel(phone or synthetic_phone(rng))
        self.receptores.append({
            "nombre": truncate(nombre, 100),
            "nombre_comercial": truncate(nombre_comercial or nombre, 80),
            "identificacion_seq": ident_seq,
            "ubicacion_seq": ubic_seq,
            "telefono_seq": tel_seq,
            "clients_code": clients_code,
        })
        entity_seq = len(self.receptores)
        if email:
            self.mails_receptor.append((email[:160], entity_seq))
        return EntityRef(entity_seq, ident_seq, ubic_seq, tel_seq)


def build_dimensions(
    args: argparse.Namespace,
    rng: random.Random,
    clients: list[ClientRef],
    dim: DimensionBuilder,
) -> tuple[EntityRef | None, EntityRef | None, list[int], list[int]]:
    """Returns (company_emisor, company_receptor, customer_receptor_seqs, supplier_emisor_seqs)."""
    company_emisor: EntityRef | None = None
    company_receptor: EntityRef | None = None
    customer_seqs: list[int] = []
    supplier_seqs: list[int] = []

    if args.count_emitidas > 0:
        company_emisor = dim.add_emisor(
            rng,
            COMPANY_EMISOR_NAME,
            "02",
            COMPANY_EMISOR_ID,
            "mercurius.demo@example.cr",
            nombre_comercial=COMPANY_EMISOR_NAME,
        )

        pool_cap = min(5000, max(1, args.count_emitidas))
        customer_pool = clients
        if len(customer_pool) > pool_cap:
            customer_pool = rng.sample(customer_pool, pool_cap)
        if customer_pool:
            for client in customer_pool:
                tipo = map_id_type(client.idtype)
                numero = "".join(ch for ch in client.idnumber if ch.isdigit())[:20]
                phone = "".join(ch for ch in client.phone if ch.isdigit()) or None
                address = {
                    "provincia": client.provincia or str(rng.randint(1, 7)),
                    "canton": client.canton or f"{rng.randint(1, 15):02d}",
                    "distrito": client.distrito or f"{rng.randint(1, 20):02d}",
                    "barrio": f"BARRIO {rng.choice(('CENTRAL', 'EL CARMEN', 'SAN JOSE', 'LA UNION'))}",
                    "otras_senas": truncate(client.address or "DEL PARQUE 100 M NORTE", 250),
                }
                ref = dim.add_receptor(
                    rng,
                    client.nombre or f"CLIENTE {client.code}",
                    tipo,
                    numero,
                    client.email or f"cliente{client.code:06d}@example.cr",
                    address=address,
                    phone=phone,
                    nombre_comercial=client.nombre,
                    clients_code=client.code,
                )
                customer_seqs.append(ref.entity_seq)
        else:
            pool = min(5000, max(1, args.count_emitidas))
            for _ in range(pool):
                tipo = rng.choices(("01", "02", "03"), weights=(60, 25, 15), k=1)[0]
                if tipo == "02":
                    nombre = f"{rng.choice(COMPANY_NAMES)} {rng.randrange(1, 9999):04d} S.A."
                else:
                    nombre = f"{rng.choice(FIRST_NAMES)} {rng.choice(LAST_NAMES)} {rng.choice(LAST_NAMES)}"
                ref = dim.add_receptor(
                    rng,
                    nombre,
                    tipo,
                    "",
                    f"cliente{len(dim.receptores) + 1:06d}@example.cr",
                )
                customer_seqs.append(ref.entity_seq)

    if args.count_recibidas > 0:
        company_receptor = dim.add_receptor(
            rng,
            COMPANY_EMISOR_NAME,
            "02",
            COMPANY_EMISOR_ID,
            "mercurius.compras@example.cr",
            nombre_comercial=COMPANY_EMISOR_NAME,
        )
        supplier_count = min(2000, max(1, args.count_recibidas))
        for idx in range(supplier_count):
            tipo = rng.choices(("02", "01"), weights=(85, 15), k=1)[0]
            base = COMPANY_NAMES[idx % len(COMPANY_NAMES)]
            nombre = f"{base} {idx + 1:04d} S.A."
            ref = dim.add_emisor(
                rng,
                nombre,
                tipo,
                "",
                f"proveedor{idx + 1:05d}@example.cr",
                nombre_comercial=nombre,
            )
            supplier_seqs.append(ref.entity_seq)

    return company_emisor, company_receptor, customer_seqs, supplier_seqs


# --------------------------------------------------------------------------- #
# CSV files
# --------------------------------------------------------------------------- #

HEADERS = {
    "ubicacion": ["seq", "provincia", "canton", "distrito", "barrio",
                  "otras_senas", "schemaversion"],
    "telefono": ["seq", "codigo_pais", "numero_telefono", "schemaversion"],
    "identificacion_emisor": ["seq", "tipo", "numero"],
    "emisor": ["seq", "nombre", "nombre_comercial", "registro_fiscal8707",
               "otras_senas_extranjero", "identificacion_seq", "ubicacion_seq",
               "telefono_seq", "fax_seq"],
    "identificacion_receptor": ["seq", "tipo", "numero"],
    "receptor": ["seq", "nombre", "nombre_comercial", "identificacion_extranjero",
                 "otras_senas_extranjero", "identificacion_seq", "ubicacion_seq",
                 "telefono_seq", "fax_seq", "clients_code"],
    "correo_electronico_emisor": ["seq", "correo", "emisor_seq", "schemaversion"],
    "correo_electronico_receptor": ["seq", "correo", "receptor_seq", "schemaversion"],
    "encabezado": ["seq", "clave", "proveedor_sistemas", "codigo_actividad_emisor",
                   "codigo_actividad_receptor", "numero_consecutivo", "fecha_emision",
                   "condicion_venta", "condicion_venta_otros", "plazo_credito",
                   "estado", "motivo_rechazo", "schemaversion", "codigo_documento",
                   "emisor_seq", "receptor_seq"],
    "resumen_factura": ["seq", "codigo_moneda", "tipo_cambio_moneda", "schemaversion",
                        "total_servicios_gravados", "total_servicios_exentos",
                        "total_servicios_exonerados", "total_servicios_no_sujeto",
                        "total_mercancias_gravadas", "total_mercancias_exentas",
                        "total_mercancias_exoneradas", "total_mercancias_no_sujeta",
                        "total_gravado", "total_exento", "total_exonerado",
                        "total_no_sujeto", "total_venta", "total_descuentos",
                        "total_venta_neta", "total_impuesto",
                        "total_impuesto_asumido_emisor_fabrica", "total_iva_devuelto",
                        "total_otros_cargos", "total_comprobante"],
    "total_desglose_impuesto": ["seq", "invoice_no", "codigo", "codigo_tarifa_iva",
                                "tarifa_iva", "factor_iva", "total_monto_impuesto"],
    "medio_pago_resumen": ["seq", "invoice_no", "tipo_medio_pago", "medio_pago_otros",
                           "total_medio_pago"],
    "medio_pago_encabezado": ["seq", "invoice_no", "medio_pago", "schemaversion"],
    "detalle_servicio": ["seq", "schemaversion", "status", "comprobante_emitido_seq"],
    "linea_detalle": ["seq", "invoice_no", "numero_linea", "partida_arancelaria",
                      "codigocabys", "cantidad", "unidad_medida", "tipo_transaccion",
                      "unidad_medida_comercial", "detalle", "registro_medicamento",
                      "forma_farmaceutica", "precio_unitario", "monto_total",
                      "sub_total", "iva_cobrado_fabrica", "base_imponible",
                      "impuesto_asumido_emisor_fabrica", "impuesto_neto",
                      "monto_total_de_linea", "schemaversion"],
    "codigo_comercial": ["seq", "line_seq", "tipo", "codigo"],
    "impuesto": ["seq", "line_seq", "codigo", "codigo_impuesto_otro",
                 "codigo_tarifa_iva", "tarifa", "factor_calculo_iva", "monto",
                 "monto_exportacion", "datos_impuesto_id"],
    "comprobantesemitidos": ["seq", "invoice_no", "status", "schemaversion", "usuario",
                             "haciendaclave", "haciendaestado", "haciendafechaenvio",
                             "haciendafecharespuesta", "correction_attempts"],
    "comprobantesrecibidos": ["seq", "invoice_no", "status", "processed", "paid",
                              "prevalidationerrors", "schemaversion", "usuario",
                              "hacienda_mensaje_receptor_estado",
                              "hacienda_mensaje_receptor_fecha",
                              "mensaje_receptor_limite"],
}


def write_dimensions(out: Path, dim: DimensionBuilder) -> dict[str, int]:
    counts: dict[str, int] = {}

    counts["ubicacion"] = write_csv(
        out / "ubicacion.csv",
        HEADERS["ubicacion"],
        ((seq, u["provincia"], u["canton"], u["distrito"], u["barrio"],
          u["otras_senas"], "4.4")
         for seq, u in enumerate(dim.ubicaciones, 1)),
    )
    counts["telefono"] = write_csv(
        out / "telefono.csv",
        HEADERS["telefono"],
        ((seq, "506", phone, "4.4") for seq, phone in enumerate(dim.telefonos, 1)),
    )
    counts["identificacion_emisor"] = write_csv(
        out / "identificacion_emisor.csv",
        HEADERS["identificacion_emisor"],
        ((seq, tipo, numero) for seq, (tipo, numero) in enumerate(dim.ident_emisores, 1)),
    )
    counts["emisor"] = write_csv(
        out / "emisor.csv",
        HEADERS["emisor"],
        ((seq, e["nombre"], e["nombre_comercial"], "", "", e["identificacion_seq"],
          e["ubicacion_seq"], e["telefono_seq"], "")
         for seq, e in enumerate(dim.emisores, 1)),
    )
    counts["identificacion_receptor"] = write_csv(
        out / "identificacion_receptor.csv",
        HEADERS["identificacion_receptor"],
        ((seq, tipo, numero) for seq, (tipo, numero) in enumerate(dim.ident_receptores, 1)),
    )
    counts["receptor"] = write_csv(
        out / "receptor.csv",
        HEADERS["receptor"],
        ((seq, r["nombre"], r["nombre_comercial"], "", "", r["identificacion_seq"],
          r["ubicacion_seq"], r["telefono_seq"], "",
          "" if r["clients_code"] is None else r["clients_code"])
         for seq, r in enumerate(dim.receptores, 1)),
    )
    counts["correo_electronico_emisor"] = write_csv(
        out / "correo_electronico_emisor.csv",
        HEADERS["correo_electronico_emisor"],
        ((seq, correo, emisor_seq, "4.4")
         for seq, (correo, emisor_seq) in enumerate(dim.mails_emisor, 1)),
    )
    counts["correo_electronico_receptor"] = write_csv(
        out / "correo_electronico_receptor.csv",
        HEADERS["correo_electronico_receptor"],
        ((seq, correo, receptor_seq, "4.4")
         for seq, (correo, receptor_seq) in enumerate(dim.mails_receptor, 1)),
    )
    return counts


# --------------------------------------------------------------------------- #
# Invoice graph
# --------------------------------------------------------------------------- #

def invoice_dates(rng: random.Random, count: int) -> list[datetime]:
    # Anchored to the calendar day, not the wall clock, so two runs with the
    # same --seed on the same day produce byte-identical CSVs.
    end = datetime.combine(date.today(), time(23, 59, 59))
    start = end - timedelta(days=365 * 5)
    span = int((end - start).total_seconds())
    return [start + timedelta(seconds=rng.randrange(span + 1)) for _ in range(count)]


def make_consecutivo(terminal: str, seq: int) -> str:
    # 001 (sucursal) + 00001/00002 (terminal) + 01 (factura) + 10-digit sequence
    return f"001{terminal}01{seq:010d}"


def make_clave(fecha: datetime, issuer_id: str, consecutivo: str,
               rng: random.Random) -> str:
    issuer12 = "".join(ch for ch in issuer_id if ch.isdigit()).zfill(12)[-12:]
    clave = f"506{fecha:%d%m%y}{issuer12}{consecutivo}{rng.randrange(1_000_000_000):09d}"
    assert len(clave) == 50, len(clave)
    return clave


def article_unit_price(article: Article, exento: bool) -> Decimal:
    if article.final > 0:
        gross = article.final
    elif article.costo > 0:
        gross = article.costo * Decimal("1.30")
    else:
        gross = Decimal("1000")
    if exento:
        return money(gross)
    return money(gross / Decimal("1.13"))


def synthetic_unit_price(rng: random.Random, exento: bool) -> Decimal:
    gross = Decimal(rng.randrange(50_000, 5_000_000)) / Decimal(100)
    if exento:
        return money(gross)
    return money(gross / Decimal("1.13"))


def write_invoices(
    out: Path,
    args: argparse.Namespace,
    rng: random.Random,
    articles: list[Article],
    cabys_codes: list[str],
    dim: DimensionBuilder,
    company_emisor: EntityRef | None,
    company_receptor: EntityRef | None,
    customer_seqs: list[int],
    supplier_seqs: list[int],
    emitted_states: list[str],
    received_states: list[str],
) -> dict[str, int]:
    emitted_dates = invoice_dates(rng, args.count_emitidas)
    received_dates = invoice_dates(rng, args.count_recibidas)

    opened = {
        name: (out / f"{name}.csv").open("w", newline="", encoding="utf-8")
        for name in (
            "encabezado", "resumen_factura", "total_desglose_impuesto",
            "medio_pago_resumen", "medio_pago_encabezado", "detalle_servicio",
            "linea_detalle", "codigo_comercial", "impuesto",
            "comprobantesemitidos", "comprobantesrecibidos",
        )
    }
    writers = {name: csv.writer(fh, lineterminator="\n") for name, fh in opened.items()}
    for name, writer in writers.items():
        writer.writerow(HEADERS[name])

    counters = {
        "encabezado": 0, "resumen_factura": 0, "total_desglose_impuesto": 0,
        "medio_pago_resumen": 0, "medio_pago_encabezado": 0,
        "detalle_servicio": 0, "linea_detalle": 0, "codigo_comercial": 0,
        "impuesto": 0, "comprobantesemitidos": 0, "comprobantesrecibidos": 0,
    }
    state_counts = {
        "ACEPTADO": 0, "RECHAZADO": 0, "PENDIENTE": 0,
    }
    line_no = 0
    desglose_no = 0
    total_lines = 0

    try:
        # ------------------------------------------------------------------ #
        # Emitted invoices
        # ------------------------------------------------------------------ #
        for i in range(1, args.count_emitidas + 1):
            invoice_no = i
            fecha = emitted_dates[i - 1]
            estado = emitted_states[i - 1]
            state_counts[estado] += 1
            consecutivo = make_consecutivo("00001", i)
            assert company_emisor is not None
            emitter_id = dim.ident_emisores[company_emisor.ident_seq - 1][1]
            clave = make_clave(fecha, emitter_id, consecutivo, rng)
            receptor_seq = customer_seqs[rng.randrange(len(customer_seqs))]
            condicion = peso_choice(rng, CONDICIONES_VENTA)
            plazo = rng.choice(("0", "7", "14", "30")) if condicion == "02" else "0"
            actividad_emisor = ACTIVIDADES[rng.randrange(len(ACTIVIDADES))]
            actividad_receptor = ACTIVIDADES[rng.randrange(len(ACTIVIDADES))]
            motivo = ""
            if estado == "RECHAZADO":
                motivo = rng.choice((
                    "Rechazo por datos del receptor inconsistentes",
                    "Rechazo por clave de referencia duplicada",
                    "Rechazo por validacion de totales",
                ))
            writers["encabezado"].writerow((
                invoice_no, clave, "3101999999", actividad_emisor,
                actividad_receptor, consecutivo, fecha.strftime("%Y-%m-%d %H:%M:%S"),
                condicion, "Pago contra entrega" if condicion == "99" else "",
                plazo, estado, motivo, "4.4", "01",
                company_emisor.entity_seq, receptor_seq,
            ))
            counters["encabezado"] += 1

            # One detail-service container per invoice.
            writers["detalle_servicio"].writerow((
                invoice_no, "4.4", "true", "",
            ))
            counters["detalle_servicio"] += 1

            total_gravado = Decimal("0")
            total_exento = Decimal("0")
            total_impuesto = Decimal("0")
            has_gravado = False
            has_exento = False
            n_lines = rng.randint(args.lines_min, args.lines_max)
            total_lines += n_lines

            for numero_linea in range(1, n_lines + 1):
                line_no += 1
                article = articles[rng.randrange(len(articles))]
                exento = article.exento or rng.random() < 0.10
                unit = article_unit_price(article, exento)
                cantidad = rng.choice(QUANTITIES)
                subtotal = money(cantidad * unit)
                if exento:
                    tax = Decimal("0.00000")
                    has_exento = True
                else:
                    tax = money(subtotal * TAX_13)
                    has_gravado = True
                line_total = money(subtotal + tax)
                total_gravado += subtotal if not exento else Decimal("0")
                total_exento += subtotal if exento else Decimal("0")
                total_impuesto += tax

                writers["linea_detalle"].writerow((
                    line_no, invoice_no, numero_linea, "", cabys_codes[rng.randrange(len(cabys_codes))],
                    f"{cantidad:.3f}", truncate(article.unidad, 15), "01",
                    truncate(article.unidad_comercial, 20), truncate(article.nombre, 200),
                    "", "", f"{unit:.5f}", f"{subtotal:.5f}", f"{subtotal:.5f}",
                    "", f"{subtotal:.5f}", "0.00000", f"{tax:.5f}",
                    f"{line_total:.5f}", "4.4",
                ))
                counters["linea_detalle"] += 1

                writers["codigo_comercial"].writerow((
                    line_no, line_no, "04", str(article.codigo),
                ))
                counters["codigo_comercial"] += 1

                writers["impuesto"].writerow((
                    line_no, line_no, "01", "", "01" if exento else "08",
                    "0.00" if exento else "13.00",
                    "0.0000" if exento else "0.1300",
                    f"{tax:.5f}", "0.00000", "",
                ))
                counters["impuesto"] += 1

            total_gravado = money(total_gravado)
            total_exento = money(total_exento)
            total_venta = money(total_gravado + total_exento)
            total_impuesto = money(total_impuesto)
            total_comprobante = money(total_venta + total_impuesto)

            writers["resumen_factura"].writerow((
                invoice_no, "CRC", "1.00000", "4.4",
                "0.00000", "0.00000", "0.00000", "0.00000",
                f"{total_gravado:.5f}", f"{total_exento:.5f}", "0.00000", "0.00000",
                f"{total_gravado:.5f}", f"{total_exento:.5f}", "0.00000", "0.00000",
                f"{total_venta:.5f}", "0.00000", f"{total_venta:.5f}",
                f"{total_impuesto:.5f}", "0.00000", "0.00000", "0.00000",
                f"{total_comprobante:.5f}",
            ))
            counters["resumen_factura"] += 1

            if has_gravado:
                desglose_no += 1
                writers["total_desglose_impuesto"].writerow((
                    desglose_no, invoice_no, "01", "08", "13.00", "0.1300",
                    f"{total_impuesto:.5f}",
                ))
                counters["total_desglose_impuesto"] += 1
            if has_exento:
                desglose_no += 1
                writers["total_desglose_impuesto"].writerow((
                    desglose_no, invoice_no, "01", "01", "0.00", "0.0000",
                    "0.00000",
                ))
                counters["total_desglose_impuesto"] += 1

            medio = peso_choice(rng, MEDIOS_PAGO)
            writers["medio_pago_encabezado"].writerow((
                invoice_no, invoice_no, medio, "4.4",
            ))
            counters["medio_pago_encabezado"] += 1
            writers["medio_pago_resumen"].writerow((
                invoice_no, invoice_no, medio,
                "Transferencia internacional" if medio == "99" else "",
                f"{total_comprobante:.5f}",
            ))
            counters["medio_pago_resumen"] += 1

            envio = fecha + timedelta(seconds=rng.randrange(60, 3600))
            respuesta = "" if estado == "PENDIENTE" else (
                envio + timedelta(seconds=rng.randrange(60, 7200))
            ).strftime("%Y-%m-%d %H:%M:%S")
            writers["comprobantesemitidos"].writerow((
                i, invoice_no, "true", "4.4", "seed", clave, estado,
                envio.strftime("%Y-%m-%d %H:%M:%S"), respuesta, 0,
            ))
            counters["comprobantesemitidos"] += 1

        # ------------------------------------------------------------------ #
        # Received invoices
        # ------------------------------------------------------------------ #
        for j in range(1, args.count_recibidas + 1):
            invoice_no = args.count_emitidas + j
            fecha = received_dates[j - 1]
            estado = received_states[j - 1]
            state_counts[estado] += 1
            consecutivo = make_consecutivo("00002", j)
            assert company_receptor is not None
            supplier_seq = supplier_seqs[rng.randrange(len(supplier_seqs))]
            supplier_ident_seq = int(dim.emisores[supplier_seq - 1]["identificacion_seq"])
            supplier_ident = dim.ident_emisores[supplier_ident_seq - 1][1]
            clave = make_clave(fecha, supplier_ident, consecutivo, rng)
            condicion = peso_choice(rng, CONDICIONES_VENTA)
            plazo = rng.choice(("0", "7", "14", "30")) if condicion == "02" else "0"
            actividad_emisor = ACTIVIDADES[rng.randrange(len(ACTIVIDADES))]
            motivo = ""
            if estado == "RECHAZADO":
                motivo = "Rechazo por datos del emisor"

            writers["encabezado"].writerow((
                invoice_no, clave, "3101999999", actividad_emisor,
                COMPANY_ACTIVITY, consecutivo,
                fecha.strftime("%Y-%m-%d %H:%M:%S"), condicion,
                "Pago contra entrega" if condicion == "99" else "",
                plazo, estado, motivo, "4.4", "01",
                supplier_seq, company_receptor.entity_seq,
            ))
            counters["encabezado"] += 1

            writers["detalle_servicio"].writerow((
                invoice_no, "4.4", "true", "",
            ))
            counters["detalle_servicio"] += 1

            total_gravado = Decimal("0")
            total_exento = Decimal("0")
            total_impuesto = Decimal("0")
            has_gravado = False
            has_exento = False
            n_lines = rng.randint(args.lines_min, args.lines_max)
            total_lines += n_lines

            for numero_linea in range(1, n_lines + 1):
                line_no += 1
                exento = rng.random() < 0.10
                unit = synthetic_unit_price(rng, exento)
                cantidad = rng.choice(QUANTITIES)
                subtotal = money(cantidad * unit)
                if exento:
                    tax = Decimal("0.00000")
                    has_exento = True
                else:
                    tax = money(subtotal * TAX_13)
                    has_gravado = True
                line_total = money(subtotal + tax)
                total_gravado += subtotal if not exento else Decimal("0")
                total_exento += subtotal if exento else Decimal("0")
                total_impuesto += tax
                product = SUPPLIER_PRODUCTS[rng.randrange(len(SUPPLIER_PRODUCTS))]

                writers["linea_detalle"].writerow((
                    line_no, invoice_no, numero_linea, "",
                    cabys_codes[rng.randrange(len(cabys_codes))],
                    f"{cantidad:.3f}", "Unid", "01", "Unid",
                    truncate(product, 200), "", "",
                    f"{unit:.5f}", f"{subtotal:.5f}", f"{subtotal:.5f}",
                    "", f"{subtotal:.5f}", "0.00000", f"{tax:.5f}",
                    f"{line_total:.5f}", "4.4",
                ))
                counters["linea_detalle"] += 1

                writers["codigo_comercial"].writerow((
                    line_no, line_no, "04",
                    f"SUP{supplier_seq:04d}{numero_linea:03d}"[:20],
                ))
                counters["codigo_comercial"] += 1

                writers["impuesto"].writerow((
                    line_no, line_no, "01", "", "01" if exento else "08",
                    "0.00" if exento else "13.00",
                    "0.0000" if exento else "0.1300",
                    f"{tax:.5f}", "0.00000", "",
                ))
                counters["impuesto"] += 1

            total_gravado = money(total_gravado)
            total_exento = money(total_exento)
            total_venta = money(total_gravado + total_exento)
            total_impuesto = money(total_impuesto)
            total_comprobante = money(total_venta + total_impuesto)

            writers["resumen_factura"].writerow((
                invoice_no, "CRC", "1.00000", "4.4",
                "0.00000", "0.00000", "0.00000", "0.00000",
                f"{total_gravado:.5f}", f"{total_exento:.5f}", "0.00000", "0.00000",
                f"{total_gravado:.5f}", f"{total_exento:.5f}", "0.00000", "0.00000",
                f"{total_venta:.5f}", "0.00000", f"{total_venta:.5f}",
                f"{total_impuesto:.5f}", "0.00000", "0.00000", "0.00000",
                f"{total_comprobante:.5f}",
            ))
            counters["resumen_factura"] += 1

            if has_gravado:
                desglose_no += 1
                writers["total_desglose_impuesto"].writerow((
                    desglose_no, invoice_no, "01", "08", "13.00", "0.1300",
                    f"{total_impuesto:.5f}",
                ))
                counters["total_desglose_impuesto"] += 1
            if has_exento:
                desglose_no += 1
                writers["total_desglose_impuesto"].writerow((
                    desglose_no, invoice_no, "01", "01", "0.00", "0.0000",
                    "0.00000",
                ))
                counters["total_desglose_impuesto"] += 1

            medio = peso_choice(rng, MEDIOS_PAGO)
            writers["medio_pago_encabezado"].writerow((
                invoice_no, invoice_no, medio, "4.4",
            ))
            counters["medio_pago_encabezado"] += 1
            writers["medio_pago_resumen"].writerow((
                invoice_no, invoice_no, medio,
                "Transferencia internacional" if medio == "99" else "",
                f"{total_comprobante:.5f}",
            ))
            counters["medio_pago_resumen"] += 1

            mensaje_estado = "" if estado == "PENDIENTE" else estado
            mensaje_fecha = "" if estado == "PENDIENTE" else (
                fecha + timedelta(days=rng.randint(1, 10))
            ).strftime("%Y-%m-%d %H:%M:%S")
            writers["comprobantesrecibidos"].writerow((
                j, invoice_no, "true", "false", "false", "", "4.4", "seed",
                mensaje_estado, mensaje_fecha,
                mensaje_receptor_limite(fecha).strftime("%Y-%m-%d"),
            ))
            counters["comprobantesrecibidos"] += 1

    finally:
        for fh in opened.values():
            fh.close()

    counters["_total_lines"] = total_lines
    counters["_states"] = state_counts  # type: ignore[assignment]
    return counters


# --------------------------------------------------------------------------- #
# load.sql
# --------------------------------------------------------------------------- #

LOAD_SQL = r"""
\set ON_ERROR_STOP on
\echo 'Loading Mercurius invoice seed (transactional)...'
SET client_encoding = 'UTF8';
BEGIN;
SET LOCAL statement_timeout = 0;

-- Same local-only guard as the generator.
DO $$
BEGIN
  IF current_database() NOT IN ('mercurius', 'mercurius_test') THEN
    RAISE EXCEPTION 'Refusing to load into database % (allowed: mercurius, mercurius_test)', current_database();
  END IF;
END $$;

-- EXCLUSIVE locks block writers while max(id)+row_number() is allocated, but
-- still allow plain SELECTs from the running application.
LOCK TABLE
  ubicacion, telefono, identificacion_emisor, emisor,
  identificacion_receptor, receptor,
  correo_electronico_emisor, correo_electronico_receptor,
  encabezado, resumen_factura, total_desglose_impuesto,
  medio_pago_resumen, medio_pago_encabezado,
  detalle_servicio, linea_detalle, codigo_comercial, impuesto,
  comprobantesemitidos, comprobantesrecibidos
IN EXCLUSIVE MODE;

CREATE TEMP TABLE stg_ubicacion (
  seq bigint, provincia text, canton text, distrito text, barrio text,
  otras_senas text, schemaversion text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_telefono (
  seq bigint, codigo_pais text, numero_telefono text, schemaversion text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_identificacion_emisor (
  seq bigint, tipo text, numero text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_emisor (
  seq bigint, nombre text, nombre_comercial text, registro_fiscal8707 text,
  otras_senas_extranjero text, identificacion_seq bigint, ubicacion_seq bigint,
  telefono_seq bigint, fax_seq bigint
) ON COMMIT DROP;
CREATE TEMP TABLE stg_identificacion_receptor (
  seq bigint, tipo text, numero text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_receptor (
  seq bigint, nombre text, nombre_comercial text, identificacion_extranjero text,
  otras_senas_extranjero text, identificacion_seq bigint, ubicacion_seq bigint,
  telefono_seq bigint, fax_seq bigint, clients_code bigint
) ON COMMIT DROP;
CREATE TEMP TABLE stg_correo_electronico_emisor (
  seq bigint, correo text, emisor_seq bigint, schemaversion text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_correo_electronico_receptor (
  seq bigint, correo text, receptor_seq bigint, schemaversion text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_encabezado (
  seq bigint, clave text, proveedor_sistemas text, codigo_actividad_emisor text,
  codigo_actividad_receptor text, numero_consecutivo text, fecha_emision timestamp,
  condicion_venta text, condicion_venta_otros text, plazo_credito text,
  estado text, motivo_rechazo text, schemaversion text, codigo_documento text,
  emisor_seq bigint, receptor_seq bigint
) ON COMMIT DROP;
CREATE TEMP TABLE stg_resumen_factura (
  seq bigint, codigo_moneda text, tipo_cambio_moneda numeric,
  schemaversion text, total_servicios_gravados numeric,
  total_servicios_exentos numeric, total_servicios_exonerados numeric,
  total_servicios_no_sujeto numeric, total_mercancias_gravadas numeric,
  total_mercancias_exentas numeric, total_mercancias_exoneradas numeric,
  total_mercancias_no_sujeta numeric, total_gravado numeric, total_exento numeric,
  total_exonerado numeric, total_no_sujeto numeric, total_venta numeric,
  total_descuentos numeric, total_venta_neta numeric, total_impuesto numeric,
  total_impuesto_asumido_emisor_fabrica numeric, total_iva_devuelto numeric,
  total_otros_cargos numeric, total_comprobante numeric
) ON COMMIT DROP;
CREATE TEMP TABLE stg_total_desglose_impuesto (
  seq bigint, invoice_no bigint, codigo text, codigo_tarifa_iva text,
  tarifa_iva numeric, factor_iva numeric, total_monto_impuesto numeric
) ON COMMIT DROP;
CREATE TEMP TABLE stg_medio_pago_resumen (
  seq bigint, invoice_no bigint, tipo_medio_pago text, medio_pago_otros text,
  total_medio_pago numeric
) ON COMMIT DROP;
CREATE TEMP TABLE stg_medio_pago_encabezado (
  seq bigint, invoice_no bigint, medio_pago text, schemaversion text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_detalle_servicio (
  seq bigint, schemaversion text, status boolean, comprobante_emitido_seq bigint
) ON COMMIT DROP;
CREATE TEMP TABLE stg_linea_detalle (
  seq bigint, invoice_no bigint, numero_linea int, partida_arancelaria text,
  codigocabys text, cantidad numeric, unidad_medida text, tipo_transaccion text,
  unidad_medida_comercial text, detalle text, registro_medicamento text,
  forma_farmaceutica text, precio_unitario numeric, monto_total numeric,
  sub_total numeric, iva_cobrado_fabrica text, base_imponible numeric,
  impuesto_asumido_emisor_fabrica numeric, impuesto_neto numeric,
  monto_total_de_linea numeric, schemaversion text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_codigo_comercial (
  seq bigint, line_seq bigint, tipo text, codigo text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_impuesto (
  seq bigint, line_seq bigint, codigo text, codigo_impuesto_otro text,
  codigo_tarifa_iva text, tarifa numeric, factor_calculo_iva numeric, monto numeric,
  monto_exportacion numeric, datos_impuesto_id bigint
) ON COMMIT DROP;
CREATE TEMP TABLE stg_comprobantesemitidos (
  seq bigint, invoice_no bigint, status boolean, schemaversion text, usuario text,
  haciendaclave text, haciendaestado text, haciendafechaenvio timestamp,
  haciendafecharespuesta timestamp, correction_attempts int
) ON COMMIT DROP;
CREATE TEMP TABLE stg_comprobantesrecibidos (
  seq bigint, invoice_no bigint, status boolean, processed boolean, paid boolean,
  prevalidationerrors text, schemaversion text, usuario text,
  hacienda_mensaje_receptor_estado text, hacienda_mensaje_receptor_fecha timestamp,
  mensaje_receptor_limite date
) ON COMMIT DROP;

\copy stg_ubicacion FROM 'UBICACION_CSV' CSV HEADER
\copy stg_telefono FROM 'TELEFONO_CSV' CSV HEADER
\copy stg_identificacion_emisor FROM 'IDENTIFICACION_EMISOR_CSV' CSV HEADER
\copy stg_emisor FROM 'EMISOR_CSV' CSV HEADER
\copy stg_identificacion_receptor FROM 'IDENTIFICACION_RECEPTOR_CSV' CSV HEADER
\copy stg_receptor FROM 'RECEPTOR_CSV' CSV HEADER
\copy stg_correo_electronico_emisor FROM 'CORREO_EMISOR_CSV' CSV HEADER
\copy stg_correo_electronico_receptor FROM 'CORREO_RECEPTOR_CSV' CSV HEADER
\copy stg_encabezado FROM 'ENCABEZADO_CSV' CSV HEADER
\copy stg_resumen_factura FROM 'RESUMEN_CSV' CSV HEADER
\copy stg_total_desglose_impuesto FROM 'DESGLOSE_CSV' CSV HEADER
\copy stg_medio_pago_resumen FROM 'MP_RESUMEN_CSV' CSV HEADER
\copy stg_medio_pago_encabezado FROM 'MP_ENCABEZADO_CSV' CSV HEADER
\copy stg_detalle_servicio FROM 'DETALLE_CSV' CSV HEADER
\copy stg_linea_detalle FROM 'LINEA_CSV' CSV HEADER
\copy stg_codigo_comercial FROM 'CODIGO_COMERCIAL_CSV' CSV HEADER
\copy stg_impuesto FROM 'IMPUESTO_CSV' CSV HEADER
\copy stg_comprobantesemitidos FROM 'CE_CSV' CSV HEADER
\copy stg_comprobantesrecibidos FROM 'CR_CSV' CSV HEADER

-- Index the staging copies: the FK/clave pre-checks below are joins over
-- 250k/1.1M rows and would nested-loop without these.
CREATE INDEX ON stg_encabezado (clave);
CREATE INDEX ON stg_encabezado (numero_consecutivo);
CREATE INDEX ON stg_encabezado (emisor_seq);
CREATE INDEX ON stg_encabezado (receptor_seq);
CREATE INDEX ON stg_emisor (seq);
CREATE INDEX ON stg_receptor (seq);
CREATE INDEX ON stg_detalle_servicio (seq);
CREATE INDEX ON stg_linea_detalle (invoice_no);
CREATE INDEX ON stg_comprobantesemitidos (invoice_no);
CREATE INDEX ON stg_comprobantesrecibidos (invoice_no);

-- Fail with readable messages before FK checks fire.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM stg_encabezado GROUP BY clave HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'duplicate hacienda clave in encabezado.csv';
  END IF;
  IF EXISTS (SELECT 1 FROM stg_encabezado GROUP BY numero_consecutivo HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'duplicate numero_consecutivo in encabezado.csv';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_encabezado s
    JOIN encabezado e ON e.clave = s.clave
  ) THEN
    RAISE EXCEPTION 'a generated hacienda clave already exists in encabezado';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_encabezado s
    LEFT JOIN stg_emisor e ON e.seq = s.emisor_seq
    WHERE s.emisor_seq IS NULL OR e.seq IS NULL
  ) THEN
    RAISE EXCEPTION 'encabezado.csv has an emisor_seq that is not in emisor.csv';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_encabezado s
    LEFT JOIN stg_receptor r ON r.seq = s.receptor_seq
    WHERE s.receptor_seq IS NULL OR r.seq IS NULL
  ) THEN
    RAISE EXCEPTION 'encabezado.csv has a receptor_seq that is not in receptor.csv';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_linea_detalle l
    LEFT JOIN stg_detalle_servicio d ON d.seq = l.invoice_no
    WHERE d.seq IS NULL
  ) THEN
    RAISE EXCEPTION 'linea_detalle.csv references an invoice without detalle_servicio';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_comprobantesemitidos c
    LEFT JOIN stg_detalle_servicio d ON d.seq = c.invoice_no
    WHERE d.seq IS NULL
  ) THEN
    RAISE EXCEPTION 'comprobantesemitidos.csv references an invoice without detalle_servicio';
  END IF;
  IF EXISTS (
    SELECT 1 FROM stg_comprobantesrecibidos c
    LEFT JOIN stg_detalle_servicio d ON d.seq = c.invoice_no
    WHERE d.seq IS NULL
  ) THEN
    RAISE EXCEPTION 'comprobantesrecibidos.csv references an invoice without detalle_servicio';
  END IF;
END $$;

-- Base ids are read once, then every child uses base + its CSV seq. The
-- generator guarantees the parent seq values are 1..N with no gaps, so
-- base + seq is exactly max(id) + row_number() over (order by seq).
CREATE TEMP TABLE seed_base AS
SELECT
  (SELECT COALESCE(MAX(id), 0) FROM ubicacion) AS ub,
  (SELECT COALESCE(MAX(id), 0) FROM telefono) AS tel,
  (SELECT COALESCE(MAX(id), 0) FROM identificacion_emisor) AS ie,
  (SELECT COALESCE(MAX(id), 0) FROM emisor) AS em,
  (SELECT COALESCE(MAX(id), 0) FROM identificacion_receptor) AS ir,
  (SELECT COALESCE(MAX(id), 0) FROM receptor) AS rec,
  (SELECT COALESCE(MAX(id), 0) FROM encabezado) AS enc,
  (SELECT COALESCE(MAX(id), 0) FROM resumen_factura) AS res,
  (SELECT COALESCE(MAX(id), 0) FROM detalle_servicio) AS ds,
  (SELECT COALESCE(MAX(id), 0) FROM linea_detalle) AS line,
  (SELECT COALESCE(MAX(id), 0) FROM impuesto) AS imp,
  (SELECT COALESCE(MAX(id), 0) FROM codigo_comercial) AS cc,
  (SELECT COALESCE(MAX(id), 0) FROM total_desglose_impuesto) AS tdi,
  (SELECT COALESCE(MAX(id), 0) FROM medio_pago_encabezado) AS mpe,
  (SELECT COALESCE(MAX(id), 0) FROM medio_pago_resumen) AS mpr,
  (SELECT COALESCE(MAX(id), 0) FROM comprobantesemitidos) AS ce,
  (SELECT COALESCE(MAX(id), 0) FROM comprobantesrecibidos) AS cr,
  (SELECT COALESCE(MAX(id), 0) FROM correo_electronico_emisor) AS cem,
  (SELECT COALESCE(MAX(id), 0) FROM correo_electronico_receptor) AS cer
;

INSERT INTO ubicacion (id, provincia, canton, distrito, barrio, otras_senas, schemaversion)
SELECT b.ub + s.seq, s.provincia, s.canton, s.distrito, s.barrio,
       s.otras_senas, s.schemaversion
  FROM stg_ubicacion s CROSS JOIN seed_base b;

INSERT INTO telefono (id, codigo_pais, numero_telefono, schemaversion)
SELECT b.tel + s.seq, s.codigo_pais, s.numero_telefono, s.schemaversion
  FROM stg_telefono s CROSS JOIN seed_base b;

INSERT INTO identificacion_emisor (id, tipo, numero)
SELECT b.ie + s.seq, s.tipo, s.numero
  FROM stg_identificacion_emisor s CROSS JOIN seed_base b;

INSERT INTO emisor (id, nombre, nombre_comercial, registro_fiscal8707,
                    otras_senas_extranjero, identificacion_emisor_id,
                    ubicacion_id, telefono_id, fax_id)
SELECT b.em + s.seq, s.nombre, s.nombre_comercial, NULLIF(s.registro_fiscal8707, ''),
       NULLIF(s.otras_senas_extranjero, ''), b.ie + s.identificacion_seq,
       b.ub + s.ubicacion_seq, b.tel + s.telefono_seq, NULL
  FROM stg_emisor s CROSS JOIN seed_base b;

INSERT INTO identificacion_receptor (id, tipo, numero)
SELECT b.ir + s.seq, s.tipo, s.numero
  FROM stg_identificacion_receptor s CROSS JOIN seed_base b;

INSERT INTO receptor (id, nombre, nombre_comercial, identificacion_extranjero,
                      otras_senas_extranjero, identificacion_receptor_id,
                      ubicacion_id, telefono_id, fax_id)
SELECT b.rec + s.seq, s.nombre, s.nombre_comercial,
       NULLIF(s.identificacion_extranjero, ''), NULLIF(s.otras_senas_extranjero, ''),
       b.ir + s.identificacion_seq, b.ub + s.ubicacion_seq,
       b.tel + s.telefono_seq, NULL
  FROM stg_receptor s CROSS JOIN seed_base b;

INSERT INTO correo_electronico_emisor (id, correo, emisor_id, schemaversion)
SELECT b.cem + s.seq, s.correo, b.em + s.emisor_seq, s.schemaversion
  FROM stg_correo_electronico_emisor s CROSS JOIN seed_base b;

INSERT INTO correo_electronico_receptor (id, correo, receptor_id, schemaversion)
SELECT b.cer + s.seq, s.correo, b.rec + s.receptor_seq, s.schemaversion
  FROM stg_correo_electronico_receptor s CROSS JOIN seed_base b;

INSERT INTO encabezado (id, clave, proveedor_sistemas, codigo_actividad_emisor,
                        codigo_actividad_receptor, numero_consecutivo, fecha_emision,
                        condicion_venta, condicion_venta_otros, plazo_credito,
                        estado, motivo_rechazo, schemaversion, codigo_documento,
                        emisor_id, receptor_id)
SELECT b.enc + s.seq, s.clave, s.proveedor_sistemas, s.codigo_actividad_emisor,
       s.codigo_actividad_receptor, s.numero_consecutivo, s.fecha_emision,
       s.condicion_venta, NULLIF(s.condicion_venta_otros, ''), s.plazo_credito,
       s.estado, NULLIF(s.motivo_rechazo, ''), s.schemaversion, s.codigo_documento,
       b.em + s.emisor_seq, b.rec + s.receptor_seq
  FROM stg_encabezado s CROSS JOIN seed_base b;

INSERT INTO resumen_factura (
  id, codigo_moneda, tipo_cambio_moneda, schemaversion,
  total_servicios_gravados, total_servicios_exentos, total_servicios_exonerados,
  total_servicios_no_sujeto, total_mercancias_gravadas, total_mercancias_exentas,
  total_mercancias_exoneradas, total_mercancias_no_sujeta, total_gravado,
  total_exento, total_exonerado, total_no_sujeto, total_venta, total_descuentos,
  total_venta_neta, total_impuesto, total_impuesto_asumido_emisor_fabrica,
  total_iva_devuelto, total_otros_cargos, total_comprobante
)
SELECT b.res + s.seq, s.codigo_moneda, s.tipo_cambio_moneda, s.schemaversion,
       s.total_servicios_gravados, s.total_servicios_exentos,
       s.total_servicios_exonerados, s.total_servicios_no_sujeto,
       s.total_mercancias_gravadas, s.total_mercancias_exentas,
       s.total_mercancias_exoneradas, s.total_mercancias_no_sujeta,
       s.total_gravado, s.total_exento, s.total_exonerado, s.total_no_sujeto,
       s.total_venta, s.total_descuentos, s.total_venta_neta, s.total_impuesto,
       s.total_impuesto_asumido_emisor_fabrica, s.total_iva_devuelto,
       s.total_otros_cargos, s.total_comprobante
  FROM stg_resumen_factura s CROSS JOIN seed_base b;

INSERT INTO total_desglose_impuesto (
  id, codigo, codigo_tarifa_iva, tarifa_iva, factor_iva,
  total_monto_impuesto, resumen_factura_id
)
SELECT b.tdi + s.seq, s.codigo, s.codigo_tarifa_iva, s.tarifa_iva, s.factor_iva,
       s.total_monto_impuesto, b.res + s.invoice_no
  FROM stg_total_desglose_impuesto s CROSS JOIN seed_base b;

INSERT INTO medio_pago_resumen (
  id, tipo_medio_pago, medio_pago_otros, total_medio_pago, resumen_factura_id
)
SELECT b.mpr + s.seq, s.tipo_medio_pago, NULLIF(s.medio_pago_otros, ''),
       s.total_medio_pago, b.res + s.invoice_no
  FROM stg_medio_pago_resumen s CROSS JOIN seed_base b;

INSERT INTO medio_pago_encabezado (id, medio_pago, schemaversion, comprobante_id)
SELECT b.mpe + s.seq, s.medio_pago, s.schemaversion, b.enc + s.invoice_no
  FROM stg_medio_pago_encabezado s CROSS JOIN seed_base b;

INSERT INTO detalle_servicio (id, schemaversion, status, comprobante_emitido_id)
SELECT b.ds + s.seq, s.schemaversion, s.status, NULL
  FROM stg_detalle_servicio s CROSS JOIN seed_base b;

INSERT INTO linea_detalle (
  id, numero_linea, partida_arancelaria, codigocabys, cantidad, unidad_medida,
  tipo_transaccion, unidad_medida_comercial, detalle, registro_medicamento,
  forma_farmaceutica, precio_unitario, monto_total, sub_total, iva_cobrado_fabrica,
  base_imponible, impuesto_asumido_emisor_fabrica, impuesto_neto,
  monto_total_de_linea, schemaversion, detalle_servicio_id
)
SELECT b.line + s.seq, s.numero_linea, NULLIF(s.partida_arancelaria, ''),
       NULLIF(s.codigocabys, ''), s.cantidad, s.unidad_medida, s.tipo_transaccion,
       NULLIF(s.unidad_medida_comercial, ''), s.detalle,
       NULLIF(s.registro_medicamento, ''), NULLIF(s.forma_farmaceutica, ''),
       s.precio_unitario, s.monto_total, s.sub_total,
       NULLIF(s.iva_cobrado_fabrica, ''), s.base_imponible,
       s.impuesto_asumido_emisor_fabrica, s.impuesto_neto, s.monto_total_de_linea,
       s.schemaversion, b.ds + s.invoice_no
  FROM stg_linea_detalle s CROSS JOIN seed_base b;

INSERT INTO codigo_comercial (id, codigo, tipo, linea_detalle_id)
SELECT b.cc + s.seq, s.codigo, s.tipo, b.line + s.line_seq
  FROM stg_codigo_comercial s CROSS JOIN seed_base b;

INSERT INTO impuesto (
  id, codigo, codigo_impuesto_otro, codigo_tarifa_iva, factor_calculo_iva,
  monto, monto_exportacion, tarifa, datos_impuesto_id, linea_detalle_id
)
SELECT b.imp + s.seq, s.codigo, NULLIF(s.codigo_impuesto_otro, ''),
       s.codigo_tarifa_iva, s.factor_calculo_iva, s.monto, s.monto_exportacion,
       s.tarifa, NULL, b.line + s.line_seq
  FROM stg_impuesto s CROSS JOIN seed_base b;

INSERT INTO comprobantesemitidos (
  id, status, schemaversion, usuario, haciendaclave, haciendaestado,
  haciendafechaenvio, haciendafecharespuesta, correction_attempts,
  encabezado_id, resumen_id, detalle_servicio_id
)
SELECT b.ce + s.seq, s.status, s.schemaversion, s.usuario, s.haciendaclave,
       s.haciendaestado, s.haciendafechaenvio,
       s.haciendafecharespuesta, s.correction_attempts,
       b.enc + s.invoice_no, b.res + s.invoice_no, b.ds + s.invoice_no
  FROM stg_comprobantesemitidos s CROSS JOIN seed_base b;

INSERT INTO comprobantesrecibidos (
  id, status, processed, paid, prevalidationerrors, schemaversion, usuario,
  hacienda_mensaje_receptor_estado, hacienda_mensaje_receptor_fecha,
  mensaje_receptor_limite, encabezado_id, resumen_id, detalle_servicio_id
)
SELECT b.cr + s.seq, s.status, s.processed, s.paid,
       NULLIF(s.prevalidationerrors, ''), s.schemaversion, s.usuario,
       NULLIF(s.hacienda_mensaje_receptor_estado, ''),
       s.hacienda_mensaje_receptor_fecha, s.mensaje_receptor_limite,
       b.enc + s.invoice_no, b.res + s.invoice_no, b.ds + s.invoice_no
  FROM stg_comprobantesrecibidos s CROSS JOIN seed_base b;

-- Explicit ids do not advance identity sequences.
DO $$
DECLARE
  mx bigint;
BEGIN
  SELECT MAX(id) INTO mx FROM ubicacion;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('ubicacion', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM telefono;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('telefono', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM identificacion_emisor;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('identificacion_emisor', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM emisor;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('emisor', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM identificacion_receptor;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('identificacion_receptor', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM receptor;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('receptor', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM correo_electronico_emisor;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('correo_electronico_emisor', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM correo_electronico_receptor;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('correo_electronico_receptor', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM encabezado;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('encabezado', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM resumen_factura;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('resumen_factura', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM total_desglose_impuesto;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('total_desglose_impuesto', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM medio_pago_resumen;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('medio_pago_resumen', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM medio_pago_encabezado;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('medio_pago_encabezado', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM detalle_servicio;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('detalle_servicio', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM linea_detalle;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('linea_detalle', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM codigo_comercial;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('codigo_comercial', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM impuesto;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('impuesto', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM comprobantesemitidos;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('comprobantesemitidos', 'id'), mx, true); END IF;
  SELECT MAX(id) INTO mx FROM comprobantesrecibidos;
  IF mx IS NOT NULL THEN PERFORM setval(pg_get_serial_sequence('comprobantesrecibidos', 'id'), mx, true); END IF;
END $$;

COMMIT;

ANALYZE ubicacion;
ANALYZE telefono;
ANALYZE identificacion_emisor;
ANALYZE emisor;
ANALYZE identificacion_receptor;
ANALYZE receptor;
ANALYZE correo_electronico_emisor;
ANALYZE correo_electronico_receptor;
ANALYZE encabezado;
ANALYZE resumen_factura;
ANALYZE total_desglose_impuesto;
ANALYZE medio_pago_resumen;
ANALYZE medio_pago_encabezado;
ANALYZE detalle_servicio;
ANALYZE linea_detalle;
ANALYZE codigo_comercial;
ANALYZE impuesto;
ANALYZE comprobantesemitidos;
ANALYZE comprobantesrecibidos;

SELECT 'emitidas' AS tipo, count(*) AS filas FROM comprobantesemitidos
UNION ALL SELECT 'recibidas', count(*) FROM comprobantesrecibidos
UNION ALL SELECT 'encabezado', count(*) FROM encabezado
UNION ALL SELECT 'linea_detalle', count(*) FROM linea_detalle
UNION ALL SELECT 'impuesto', count(*) FROM impuesto
UNION ALL SELECT 'codigo_comercial', count(*) FROM codigo_comercial;
"""


def build_load_sql(out: Path) -> Path:
    out = out.resolve()
    replacements = {
        "UBICACION_CSV": out / "ubicacion.csv",
        "TELEFONO_CSV": out / "telefono.csv",
        "IDENTIFICACION_EMISOR_CSV": out / "identificacion_emisor.csv",
        "EMISOR_CSV": out / "emisor.csv",
        "IDENTIFICACION_RECEPTOR_CSV": out / "identificacion_receptor.csv",
        "RECEPTOR_CSV": out / "receptor.csv",
        "CORREO_EMISOR_CSV": out / "correo_electronico_emisor.csv",
        "CORREO_RECEPTOR_CSV": out / "correo_electronico_receptor.csv",
        "ENCABEZADO_CSV": out / "encabezado.csv",
        "RESUMEN_CSV": out / "resumen_factura.csv",
        "DESGLOSE_CSV": out / "total_desglose_impuesto.csv",
        "MP_RESUMEN_CSV": out / "medio_pago_resumen.csv",
        "MP_ENCABEZADO_CSV": out / "medio_pago_encabezado.csv",
        "DETALLE_CSV": out / "detalle_servicio.csv",
        "LINEA_CSV": out / "linea_detalle.csv",
        "CODIGO_COMERCIAL_CSV": out / "codigo_comercial.csv",
        "IMPUESTO_CSV": out / "impuesto.csv",
        "CE_CSV": out / "comprobantesemitidos.csv",
        "CR_CSV": out / "comprobantesrecibidos.csv",
    }
    sql = LOAD_SQL
    # Longest first: CORREO_EMISOR_CSV contains EMISOR_CSV, and the
    # MP_* placeholders contain their un-prefixed counterparts.
    for key in sorted(replacements, key=len, reverse=True):
        sql = sql.replace(key, sql_literal(replacements[key]))
    load_path = out / "load.sql"
    load_path.write_text(sql, encoding="utf-8", newline="\n")
    return load_path


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    default_out = Path(__file__).resolve().parent / "invoices-out"
    ap = argparse.ArgumentParser(
        description="Generate Mercurius emitted/received invoice load-test data.",
    )
    ap.add_argument("--count-emitidas", type=int, default=200_000)
    ap.add_argument("--count-recibidas", type=int, default=50_000)
    ap.add_argument("--lines-min", type=int, default=1)
    ap.add_argument("--lines-max", type=int, default=8)
    ap.add_argument("--out", type=Path, default=default_out)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--load", action="store_true",
                    help="run psql -f load.sql after generating (off by default)")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=5433)
    ap.add_argument("--user", default="mercurius")
    ap.add_argument("--password", default=os.environ.get("PGPASSWORD", ""))
    ap.add_argument("--database", default="mercurius")
    ap.add_argument("--psql", default=default_psql(),
                    help="psql executable used for reference reads and --load")
    ap.add_argument("--force", action="store_true",
                    help="allow a non-local host (never recommended)")
    return ap.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if args.host not in LOCAL_HOSTS and not args.force:
        print(f"Refusing non-local host '{args.host}' without --force", file=sys.stderr)
        return 2
    if args.database not in ALLOWED_DATABASES:
        print(
            f"Refusing database '{args.database}'; allowed: {sorted(ALLOWED_DATABASES)}",
            file=sys.stderr,
        )
        return 2
    if args.count_emitidas < 0 or args.count_recibidas < 0:
        print("--count-emitidas and --count-recibidas must be >= 0", file=sys.stderr)
        return 2
    if args.lines_min < 1 or args.lines_max < args.lines_min:
        print("--lines-min must be >= 1 and --lines-max >= --lines-min", file=sys.stderr)
        return 2

    args.out.mkdir(parents=True, exist_ok=True)
    args.out = args.out.resolve()

    print(f"reading reference data from {args.user}@{args.host}:{args.port}/{args.database}")
    try:
        articles = fetch_articles(args)
        clients = fetch_clients(args)
        cabys_codes = fetch_cabys(args)
    except Exception as exc:  # noqa: BLE001 - reported with actionable context
        print(
            "Cannot read reference data from "
            f"{args.user}@{args.host}:{args.port}/{args.database}: {exc}\n"
            "Check that PostgreSQL is running, the database exists and "
            "--password / PGPASSWORD is correct.",
            file=sys.stderr,
        )
        return 2

    if args.count_emitidas > 0 and not articles:
        print("No rows in articulos: cannot generate emitted invoice lines.", file=sys.stderr)
        return 2
    if not cabys_codes:
        print("No 13-digit CABYS codes found: cannot generate valid lines.", file=sys.stderr)
        return 2

    print(f"reference snapshot: {len(articles):,} articulos, "
          f"{len(clients):,} clients, {len(cabys_codes):,} CABYS codes")

    rng = random.Random(args.seed)
    dim = DimensionBuilder()
    company_emisor, company_receptor, customer_seqs, supplier_seqs = build_dimensions(
        args, rng, clients, dim,
    )

    if args.count_emitidas > 0:
        if company_emisor is None or not customer_seqs:
            print("Internal error: emitted invoices need an emisor and receptor pool.",
                  file=sys.stderr)
            return 2
    if args.count_recibidas > 0:
        if company_receptor is None or not supplier_seqs:
            print("Internal error: received invoices need a receptor and supplier pool.",
                  file=sys.stderr)
            return 2

    emitted_states = assign_estados(args.count_emitidas, rng)
    received_states = assign_estados(args.count_recibidas, rng)

    print("writing dimension CSVs...")
    counts = write_dimensions(args.out, dim)

    print("writing invoice CSVs...")
    counts.update(write_invoices(
        args.out, args, rng, articles, cabys_codes, dim,
        company_emisor, company_receptor, customer_seqs, supplier_seqs,
        emitted_states, received_states,
    ))
    line_total = counts.pop("_total_lines", 0)
    state_counts = counts.pop("_states", {})
    emitted_state_counts = {
        name: emitted_states.count(name) for name in ESTADOS
    }
    received_state_counts = {
        name: received_states.count(name) for name in ESTADOS
    }

    load_path = build_load_sql(args.out)

    manifest = {
        "seed": args.seed,
        "database": args.database,
        "host": args.host,
        "port": args.port,
        "counts": {
            "emitidas": args.count_emitidas,
            "recibidas": args.count_recibidas,
            "lines_min": args.lines_min,
            "lines_max": args.lines_max,
            "linea_detalle": line_total,
        },
        "estado_counts": state_counts,
        "estado_counts_emitidas": emitted_state_counts,
        "estado_counts_recibidas": received_state_counts,
        "table_order": list(FOOTER_TABLE_ORDER),
        "row_counts": {k: counts[k] for k in FOOTER_TABLE_ORDER if k in counts},
        "reference_snapshot": {
            "articulos": len(articles),
            "clients": len(clients),
            "cabys_codes": len(cabys_codes),
            "supplier_pool": len(supplier_seqs),
            "emitted_receptor_pool": len(customer_seqs),
        },
        "fk_strategy": (
            "CSV uses local 1..N seqs. load.sql computes "
            "max(target.id)+seq for parents and base+parent_seq for children."
        ),
        "not_populated": {
            "fax": "nullable and unused by the generator; fax_id is null",
            "informacion_referencia": "not required by the emitted/received graph",
            "datos_impuesto": "only used by specific/exonerated taxes",
            "descuento": "invoice totals have zero discounts",
            "exoneracion": "only used by exonerated taxes",
            "otros": "no table named otros exists in the live schema",
        },
        "notes": [
            "Emitted line items use random existing articulos; the article code "
            "is stored in codigo_comercial.tipo='04' because linea_detalle has "
            "no articulo_id column.",
            "clients rows are used to build emitted receivers when present. "
            "There is no clients FK in encabezado/receptor, so the code cannot "
            "be stored as a foreign key.",
            "detalle_servicio.comprobante_emitido_id stays null, matching the "
            "existing data; comprobantesemitidos.detalle_servicio_id is the "
            "effective link.",
        ],
    }
    (args.out / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )

    print()
    print(f"generated {args.count_emitidas:,} emitted + {args.count_recibidas:,} received invoices")
    print(f"lines: {line_total:,} (min {args.lines_min}, max {args.lines_max})")
    print("Hacienda states:")
    for name in ESTADOS:
        print(f"  {name:10s} {state_counts.get(name, 0):>8,}")
    print()
    print("table order and row counts:")
    for name in FOOTER_TABLE_ORDER:
        print(f"  {name:32s} {counts.get(name, 0):>12,}")
    print()
    print(f"output: {args.out}")
    print(f"load script: {load_path}")
    print("load with:")
    print(
        f'  {args.psql} -h {args.host} -p {args.port} -U {args.user} '
        f'-d {args.database} -v ON_ERROR_STOP=1 -f "{load_path}"'
    )

    if args.load:
        env = dict(os.environ, PGPASSWORD=args.password)
        cmd = [
            args.psql, "-h", args.host, "-p", str(args.port),
            "-U", args.user, "-d", args.database,
            "-v", "ON_ERROR_STOP=1", "-f", str(load_path),
        ]
        print("running:", " ".join(cmd))
        return subprocess.call(cmd, env=env)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
