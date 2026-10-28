#!/usr/bin/env python3
"""
Generador de datos de carga para las tablas menores de Mercurius.

Genera un CSV por tabla mas un `load.sql` con staging + COPY + INSERT. NO
inserta nada por si solo: `--load` es explicito y esta tarea no lo ejecuta.

Tablas generadas (en el orden exacto del load)
----------------------------------------------
1. notacredito
2. stock_alerts
3. reorder_suggestions
4. envio_fuera_linea
5. departamentometrico
6. precision_pronostico

Hallazgos de esquema relevantes
-------------------------------
* `notacredito.cliente_id` NO apunta a `clientes`; la FK real es
  `clients(code)`. Se usan codes existentes.
* No existe tabla de lineas/detalle de nota de credito. El grafo de detalle
  (`detalle_servicio` / `linea_detalle`) cuelga de `comprobantesemitidos` y no
  distingue NC, por lo que no se genera nada alli. Ver reporte.
* `envio_fuera_linea.xml_firmado`, `xml_firmado_bytes` y `ultimo_error` son
  `@Lob` de Hibernate y en PostgreSQL son columnas `oid` (large objects).
  Son nullable y se dejan NULL a proposito: materializar cientos de miles de
  large objects no aporta a una prueba de carga y complica el rollback.
* `stock_alerts.id` y `reorder_suggestions.id` son `integer`; el resto son
  `bigint`.

Seguridad
---------
* Solo bases `mercurius` y `mercurius_test`.
* Solo hosts locales, salvo `--force`.
* Las consultas de referencia son de solo lectura (`COPY ... TO STDOUT`).
* No se borra ni actualiza ninguna fila existente.
* Los INSERT viven unicamente en `load.sql`; el generador nunca los ejecuta.

Requisitos
----------
Python 3.10+ (solo stdlib) y `psql` para leer IDs existentes. Si `psql` no esta
en PATH se busca en las rutas tipicas de PostgreSQL en Windows.

Uso
---
    python scripts/seed/minor_tables.py
    python scripts/seed/minor_tables.py --out C:/tmp/minor --seed 7
    python scripts/seed/minor_tables.py --password '...' --count-nc 1000

    # Carga explicita (NO la ejecuta este script):
    psql -h localhost -p 5433 -U mercurius -d mercurius \\
         -v ON_ERROR_STOP=1 -f "%TEMP%/mercurius-seed-minor/load.sql"

Salidas (en --out)
------------------
notacredito.csv, stock_alerts.csv, reorder_suggestions.csv,
envio_fuera_linea.csv, departamentometrico.csv, precision_pronostico.csv,
load.sql, report.md
"""

from __future__ import annotations

import argparse
import csv
import io
import logging
import os
import random
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any, Sequence

LOG = logging.getLogger("mercurius.seed.minor")

ALLOWED_DATABASES = {"mercurius", "mercurius_test"}
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}

TABLE_ORDER = [
    "notacredito",
    "stock_alerts",
    "reorder_suggestions",
    "envio_fuera_linea",
    "departamentometrico",
    "precision_pronostico",
]

MONEY2 = Decimal("0.01")
MONEY5 = Decimal("0.00001")
MAX_MONEY10_2 = Decimal("99999999.99")

METODOS_PRONOSTICO = (
    "INGENUO",
    "ESTACIONAL_INGENUO",
    "MEDIA_MOVIL",
    "SES",
    "HOLT_AMORTIGUADO",
    "HOLT_WINTERS",
    "SBA",
)

MOTIVOS_NC = (
    "Devolución de mercancía en buen estado",
    "Error en el precio facturado",
    "Producto entregado incompleto",
    "Producto distinto al solicitado",
    "Anulación por duplicidad de factura",
    "Descuento no aplicado en la factura original",
    "Cobro de flete no acordado",
    "Mercancía dañada durante el transporte",
    "Ajuste por diferencia de tipo de cambio",
    "Cliente desiste de la compra antes del despacho",
    "Corrección de la identificación del receptor",
    "Error en la cantidad despachada",
    "Devolución parcial de la orden",
    "Anulación solicitada por el cliente",
    "Cobro incorrecto de impuestos",
    "Producto vencido al momento de la entrega",
    "Ajuste administrativo de facturación",
    "Faltante detectado en el conteo de bodega",
    "Reemplazo de producto por garantía",
    "Nota de crédito por promoción aplicada fuera de fecha",
)

NOTAS_ALERTA = (
    "Alerta generada por el monitoreo automático de inventario.",
    "Stock por debajo del mínimo configurado para el artículo.",
    "Artículo sin existencias en la bodega principal.",
    "Revisar reposición con el proveedor habitual.",
    "Alerta revisada por el encargado de bodega.",
    "Se solicitó cotización para reabastecimiento.",
    "El artículo tiene demanda alta en la última semana.",
    "Pendiente confirmar fecha de entrega del proveedor.",
)

NOTAS_REORDER = (
    "Reposición calculada con el promedio de ventas de los últimos 30 días.",
    "Sugerencia generada por el motor de inventario.",
    "Incluye stock de seguridad configurado para el artículo.",
    "Validar con el proveedor antes de emitir la orden de compra.",
    "Prioridad alta por quiebre de stock prolongado.",
    "Cantidad ajustada por espacio disponible en bodega.",
    "Se recomienda consolidar con el pedido mensual.",
    "Sugerencia pendiente de aprobación por compras.",
)

PROVEEDORES_SINTETICOS = (
    "Distribuidora Central S.A.",
    "Comercializadora del Valle",
    "Suministros La Sabana",
    "Importadora del Pacífico",
    "Alimentos del Trópico",
    "Depósito Nacional de Insumos",
    "Grupo Agroindustrial Chorotega",
    "Proveeduría San José",
)


# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------


def money2(value: Decimal | float | int | str) -> Decimal:
    return Decimal(str(value)).quantize(MONEY2, rounding=ROUND_HALF_UP)


def money5(value: Decimal | float | int | str) -> Decimal:
    return Decimal(str(value)).quantize(MONEY5, rounding=ROUND_HALF_UP)


def fmt_dt(value: datetime | None) -> str:
    if value is None:
        return ""
    return value.replace(microsecond=0).isoformat(sep=" ", timespec="seconds")


def fmt_dec(value: Decimal | None) -> str:
    return "" if value is None else format(value, "f")


def fmt_float(value: float | None, digits: int = 6) -> str:
    if value is None:
        return ""
    return f"{value:.{digits}f}"


def fmt_bool(value: bool) -> str:
    return "true" if value else "false"


def weighted_choice(rng: random.Random, values: Sequence[Any], weights: Sequence[int]) -> Any:
    return rng.choices(values, weights=weights, k=1)[0]


def random_dt(rng: random.Random, start: datetime, end: datetime) -> datetime:
    """Instante uniforme entre start y end, con hora laboral plausible."""
    if end <= start:
        return start
    span = int((end - start).total_seconds())
    offset = rng.randrange(0, max(span, 1))
    moment = start + timedelta(seconds=offset)
    moment = moment.replace(
        hour=rng.randrange(7, 21),
        minute=rng.randrange(0, 60),
        second=rng.randrange(0, 60),
        microsecond=0,
    )
    if moment > end:
        moment = end.replace(microsecond=0)
    return moment


def add_business_days(moment: datetime, days: int) -> datetime:
    current = moment
    remaining = days
    while remaining > 0:
        current += timedelta(days=1)
        if current.weekday() < 5:
            remaining -= 1
    return current


def open_csv(path: Path, header: Sequence[str]) -> tuple[Any, csv.writer]:
    handle = path.open("w", newline="", encoding="utf-8")
    writer = csv.writer(handle, lineterminator="\n")
    writer.writerow(list(header))
    return handle, writer


def digits(value: str | None, width: int) -> str:
    raw = "".join(ch for ch in (value or "") if ch.isdigit())
    if not raw:
        return "0" * width
    return raw[-width:].rjust(width, "0")


def identificacion(value: str | None, width: int, fallback: int) -> str:
    """Normaliza una identificación; si viene vacía usa un fallback estable."""
    raw = "".join(ch for ch in (value or "") if ch.isdigit())
    if not raw:
        raw = str(abs(fallback) % 1_000_000_000).rjust(9, "0")
    return raw[-width:].rjust(width, "0")


def build_clave(
    fecha: datetime,
    emisor12: str,
    consecutivo20: str,
    situacion: str,
    seguridad8: str,
) -> str:
    clave = f"506{fecha:%d%m%y}{emisor12}{consecutivo20}{situacion}{seguridad8}"
    if len(clave) != 50:
        raise ValueError(f"Clave con longitud {len(clave)}, se esperaban 50: {clave!r}")
    return clave


# ---------------------------------------------------------------------------
# Referencia (solo lectura, via psql)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Article:
    codigo: int
    departamento_id: int | None


@dataclass(frozen=True)
class Department:
    id: int
    nombre: str
    tiempo_entrega: int


@dataclass(frozen=True)
class User:
    id: int
    username: str


@dataclass(frozen=True)
class Comprobante:
    id: int
    clave: str
    consecutivo: str
    tipo: str
    emisor_tipo: str
    emisor_numero: str
    receptor_tipo: str
    receptor_numero: str
    fecha: datetime | None


@dataclass
class Meta:
    articles: list[Article] = field(default_factory=list)
    departments: list[Department] = field(default_factory=list)
    users: list[User] = field(default_factory=list)
    clients: list[int] = field(default_factory=list)
    comprobantes: list[Comprobante] = field(default_factory=list)
    counts: dict[str, int] = field(default_factory=dict)
    columns: dict[str, list[tuple[str, str, str]]] = field(default_factory=dict)


def resolve_psql(requested: str) -> str:
    """Devuelve un ejecutable psql usable, buscando rutas tipicas si hace falta."""
    if requested and requested != "psql":
        return requested

    found = shutil.which(requested or "psql")
    if found:
        return found

    candidates: list[Path] = []
    if sys.platform.startswith("win"):
        for base in (Path("C:/Program Files/PostgreSQL"), Path("C:/Program Files (x86)/PostgreSQL")):
            if base.is_dir():
                candidates.extend(base.glob("*/bin/psql.exe"))
    if candidates:
        chosen = sorted(candidates, key=lambda p: p.parts[-3], reverse=True)[0]
        LOG.info("psql no esta en PATH; se usa %s", chosen)
        return str(chosen)

    raise RuntimeError(
        "No se encontro psql. Instale el cliente PostgreSQL o pase --psql con la ruta completa."
    )


def psql_env(password: str) -> dict[str, str]:
    env = dict(os.environ)
    env["PGPASSWORD"] = password
    env["PGCLIENTENCODING"] = "UTF8"
    return env


def psql_copy(args: argparse.Namespace, sql: str) -> list[list[str]]:
    cmd = [
        args.psql,
        "-h", args.host,
        "-p", str(args.port),
        "-U", args.user,
        "-d", args.database,
        "-X", "-q", "-A", "-t",
        "-v", "ON_ERROR_STOP=1",
        "-c", sql,
    ]
    result = subprocess.run(
        cmd,
        env=psql_env(args.password),
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr or "").strip() or "sin detalle"
        raise RuntimeError(f"Fallo la consulta de referencia: {detail}")
    return [row for row in csv.reader(io.StringIO(result.stdout)) if row]


def _copy_sql(select: str) -> str:
    return f"COPY (\n{select}\n) TO STDOUT WITH (FORMAT csv)"


def fetch_meta(args: argparse.Namespace) -> Meta:
    meta = Meta()

    rows = psql_copy(args, _copy_sql(
        "SELECT a.codigo, a.departamento_id\n"
        "  FROM articulos a\n"
        " ORDER BY a.codigo"
    ))
    for row in rows:
        try:
            dept = int(row[1]) if len(row) > 1 and row[1] not in ("", "\\N") else None
            meta.articles.append(Article(codigo=int(row[0]), departamento_id=dept))
        except (ValueError, IndexError):
            LOG.debug("Se omite articulo con fila invalida: %r", row)
    if not meta.articles:
        raise RuntimeError("La tabla articulos esta vacia; no hay FK segura para alertas/reorden/pronostico")

    rows = psql_copy(args, _copy_sql(
        "SELECT d.id, COALESCE(NULLIF(BTRIM(d.nombre), ''), 'PROVEEDOR ' || d.id),\n"
        "       COALESCE(d.tiempoentregadias, 0)\n"
        "  FROM departamento d\n"
        " ORDER BY d.id"
    ))
    for row in rows:
        try:
            meta.departments.append(Department(
                id=int(row[0]), nombre=row[1], tiempo_entrega=int(row[2] or 0)
            ))
        except (ValueError, IndexError):
            LOG.debug("Se omite departamento con fila invalida: %r", row)

    rows = psql_copy(args, _copy_sql(
        "SELECT u.id, COALESCE(u.username, 'usuario-' || u.id)\n"
        "  FROM users u\n"
        " ORDER BY u.id"
    ))
    for row in rows:
        try:
            meta.users.append(User(id=int(row[0]), username=str(row[1])[:50]))
        except (ValueError, IndexError):
            LOG.debug("Se omite usuario con fila invalida: %r", row)

    rows = psql_copy(args, _copy_sql("SELECT c.code FROM clients c ORDER BY c.code"))
    for row in rows:
        try:
            meta.clients.append(int(row[0]))
        except (ValueError, IndexError):
            LOG.debug("Se omite cliente con fila invalida: %r", row)

    comprobante_sql = """
        SELECT ce.id, e.clave, e.numero_consecutivo, e.codigo_documento,
               COALESCE(ie.tipo, ''), COALESCE(ie.numero, ''),
               COALESCE(ir.tipo, ''), COALESCE(ir.numero, ''),
               e.fecha_emision
          FROM comprobantesemitidos ce
          JOIN encabezado e ON e.id = ce.encabezado_id
          LEFT JOIN emisor em ON em.id = e.emisor_id
          LEFT JOIN receptor re ON re.id = e.receptor_id
          LEFT JOIN identificacion_emisor ie ON ie.id = em.identificacion_emisor_id
          LEFT JOIN identificacion_receptor ir ON ir.id = re.identificacion_receptor_id
         WHERE e.estado = 'ACEPTADO'
           AND e.codigo_documento = '01'
         ORDER BY ce.id
    """
    rows = psql_copy(args, _copy_sql(comprobante_sql))
    if not rows:
        LOG.warning("No hay comprobantes ACEPTADO/01; se usan todos los comprobantes emitidos")
        rows = psql_copy(args, _copy_sql(
            "SELECT ce.id, e.clave, e.numero_consecutivo, e.codigo_documento,\n"
            "       COALESCE(ie.tipo, ''), COALESCE(ie.numero, ''),\n"
            "       COALESCE(ir.tipo, ''), COALESCE(ir.numero, ''),\n"
            "       e.fecha_emision\n"
            "  FROM comprobantesemitidos ce\n"
            "  JOIN encabezado e ON e.id = ce.encabezado_id\n"
            "  LEFT JOIN emisor em ON em.id = e.emisor_id\n"
            "  LEFT JOIN receptor re ON re.id = e.receptor_id\n"
            "  LEFT JOIN identificacion_emisor ie ON ie.id = em.identificacion_emisor_id\n"
            "  LEFT JOIN identificacion_receptor ir ON ir.id = re.identificacion_receptor_id\n"
            " ORDER BY ce.id"
        ))
    for row in rows:
        try:
            fecha = datetime.fromisoformat(row[8]) if len(row) > 8 and row[8] else None
            meta.comprobantes.append(Comprobante(
                id=int(row[0]),
                clave=row[1],
                consecutivo=row[2],
                tipo=row[3] or "01",
                emisor_tipo=row[4] or "01",
                emisor_numero=row[5],
                receptor_tipo=row[6] or "01",
                receptor_numero=row[7],
                fecha=fecha,
            ))
        except (ValueError, IndexError):
            LOG.debug("Se omite comprobante con fila invalida: %r", row)

    rows = psql_copy(args, _copy_sql(
        "SELECT c.table_name, c.column_name, c.data_type, c.is_nullable\n"
        "  FROM information_schema.columns c\n"
        " WHERE c.table_schema = 'public'\n"
        "   AND c.table_name IN ('notacredito','stock_alerts','reorder_suggestions',\n"
        "                        'envio_fuera_linea','departamentometrico','precision_pronostico')\n"
        " ORDER BY c.table_name, c.ordinal_position"
    ))
    for row in rows:
        meta.columns.setdefault(row[0], []).append((row[1], row[2], row[3]))

    missing = [t for t in TABLE_ORDER if t not in meta.columns]
    if missing:
        raise RuntimeError(f"Faltan tablas destino en la base: {', '.join(missing)}")

    count_sql = "\nUNION ALL\n".join(
        f"SELECT '{t}', count(*) FROM {t}" for t in TABLE_ORDER
    )
    for row in psql_copy(args, _copy_sql(count_sql + "\nORDER BY 1")):
        meta.counts[row[0]] = int(row[1])

    LOG.info(
        "Referencia: %d articulos, %d departamentos, %d usuarios, %d clientes, %d comprobantes",
        len(meta.articles), len(meta.departments), len(meta.users),
        len(meta.clients), len(meta.comprobantes),
    )
    return meta


# ---------------------------------------------------------------------------
# Helpers de negocio
# ---------------------------------------------------------------------------


def article_department(meta: Meta, rng: random.Random) -> tuple[int, int | None]:
    article = rng.choice(meta.articles)
    dept = article.departamento_id
    if dept is None and meta.departments:
        dept = rng.choice(meta.departments).id
    return article.codigo, dept


def department_name(meta: Meta, dept_id: int | None) -> str:
    if dept_id is None:
        return ""
    for dept in meta.departments:
        if dept.id == dept_id:
            return dept.nombre[:255]
    return ""


# ---------------------------------------------------------------------------
# Generadores
# ---------------------------------------------------------------------------


NC_HEADER = [
    "row_key", "fecha", "fechaanulacion", "haciendaclave", "haciendaestado",
    "montototal", "motivo", "status", "usuario", "cliente_id",
    "comprobante_original_id",
]


def generate_nc(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
    start: datetime,
    seed: int,
) -> dict[str, Any]:
    handle, writer = open_csv(out / "notacredito.csv", NC_HEADER)
    estados: dict[str, int] = {}
    anuladas = 0
    try:
        for key in range(1, count + 1):
            original = rng.choice(meta.comprobantes)
            base = original.fecha or random_dt(rng, start, now)
            if base > now:
                base = now - timedelta(days=rng.randint(1, 30))
            base = max(base, start)
            fecha = base + timedelta(
                days=rng.randint(0, 90),
                hours=rng.randint(0, 10),
                minutes=rng.randint(0, 59),
            )
            if fecha > now:
                fecha = now - timedelta(minutes=rng.randint(5, 600))
            if fecha < base:
                fecha = base

            estado = weighted_choice(
                rng, ("ACEPTADO", "RECHAZADO", "PENDIENTE"), (88, 7, 5)
            )
            estados[estado] = estados.get(estado, 0) + 1

            status = rng.random() >= 0.06
            fecha_anulacion: datetime | None = None
            if not status:
                fecha_anulacion = fecha + timedelta(
                    days=rng.randint(1, 45), hours=rng.randint(0, 12)
                )
                if fecha_anulacion > now:
                    fecha_anulacion = now
                anuladas += 1

            seq = f"{abs(seed) % 10000:04d}{key % 1_000_000:06d}"
            consecutivo = f"0010000102{seq}"
            clave = build_clave(
                fecha,
                "000401920953",
                consecutivo,
                "1",
                f"{rng.randrange(0, 100_000_000):08d}",
            )
            monto = money2(rng.uniform(1_000, 750_000))
            usuario = rng.choice(meta.users).username if meta.users else "sistema"
            cliente_id = rng.choice(meta.clients) if meta.clients else ""

            writer.writerow(
                [
                    key,
                    fmt_dt(fecha),
                    fmt_dt(fecha_anulacion),
                    clave,
                    estado,
                    fmt_dec(monto),
                    rng.choice(MOTIVOS_NC)[:500],
                    fmt_bool(status),
                    usuario,
                    cliente_id,
                    original.id,
                ]
            )
    finally:
        handle.close()

    return {"count": count, "estados": estados, "anuladas": anuladas}


ALERT_HEADER = [
    "row_key", "articulo_codigo", "tipo_alerta", "cantidad_actual",
    "cantidad_minima", "sugerido_reordenar", "departamento_id", "estado",
    "fecha_creacion", "fecha_resolucion", "usuario_resolucion", "notas",
]


def generate_alerts(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
    start: datetime,
) -> dict[str, Any]:
    handle, writer = open_csv(out / "stock_alerts.csv", ALERT_HEADER)
    estados: dict[str, int] = {}
    tipos: dict[str, int] = {}
    try:
        for key in range(1, count + 1):
            codigo, dept = article_department(meta, rng)
            tipo = weighted_choice(
                rng,
                ("low_stock", "out_of_stock", "reorder_suggestion"),
                (55, 25, 20),
            )
            tipos[tipo] = tipos.get(tipo, 0) + 1
            minima = rng.randint(3, 120)

            if tipo == "out_of_stock":
                actual = 0
                sugerido = minima * 2 + rng.randint(0, 80)
            elif tipo == "low_stock":
                actual = rng.randint(1, max(1, minima - 1))
                sugerido = (minima - actual) + rng.randint(5, 60)
            else:
                actual = rng.randint(minima, minima + 5)
                sugerido = rng.randint(10, 250)

            fecha = random_dt(rng, start, now)
            age = (now - fecha).days
            if age <= 7:
                estado = weighted_choice(
                    rng, ("active", "acknowledged", "resolved"), (80, 15, 5)
                )
            elif age <= 30:
                estado = weighted_choice(
                    rng, ("active", "acknowledged", "resolved"), (45, 30, 25)
                )
            elif age <= 180:
                estado = weighted_choice(
                    rng, ("active", "acknowledged", "resolved"), (15, 25, 60)
                )
            else:
                estado = weighted_choice(
                    rng, ("active", "acknowledged", "resolved"), (3, 12, 85)
                )
            estados[estado] = estados.get(estado, 0) + 1

            resolucion: datetime | None = None
            usuario_resolucion: int | str = ""
            if estado in ("acknowledged", "resolved"):
                usuario_resolucion = rng.choice(meta.users).id if meta.users else ""
            if estado == "resolved":
                resolucion = fecha + timedelta(
                    days=rng.randint(0, 20), hours=rng.randint(0, 12)
                )
                if resolucion > now:
                    resolucion = now
                if resolucion < fecha:
                    resolucion = fecha

            writer.writerow(
                [
                    key,
                    codigo,
                    tipo,
                    actual,
                    minima,
                    sugerido,
                    dept if dept is not None else "",
                    estado,
                    fmt_dt(fecha),
                    fmt_dt(resolucion),
                    usuario_resolucion,
                    rng.choice(NOTAS_ALERTA)[:255],
                ]
            )
    finally:
        handle.close()

    return {"count": count, "estados": estados, "tipos": tipos}


REORDER_HEADER = [
    "row_key", "articulo_codigo", "departamento_id", "cantidad_sugerida",
    "costo_total_estimado", "prioridad", "dias_sin_stock",
    "promedio_ventas_mensual", "fecha_creacion",
    "fecha_ultimo_reabastecimiento", "proveedor_sugerido", "notas",
]


def generate_reorder(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
    start: datetime,
) -> dict[str, Any]:
    handle, writer = open_csv(out / "reorder_suggestions.csv", REORDER_HEADER)
    prioridades: dict[str, int] = {}
    try:
        for key in range(1, count + 1):
            codigo, dept = article_department(meta, rng)
            cantidad = rng.randint(5, 600)
            unitario = Decimal(str(rng.uniform(150.0, 45_000.0)))
            costo = money2(Decimal(cantidad) * unitario)
            if costo > MAX_MONEY10_2:
                costo = MAX_MONEY10_2

            prioridad = weighted_choice(
                rng, ("low", "medium", "high", "urgent"), (30, 40, 22, 8)
            )
            prioridades[prioridad] = prioridades.get(prioridad, 0) + 1

            fecha = random_dt(rng, start, now)
            reabastecimiento: datetime | None = None
            if rng.random() < 0.72:
                reabastecimiento = fecha - timedelta(
                    days=rng.randint(1, 90), hours=rng.randint(0, 12)
                )
                if reabastecimiento < start:
                    reabastecimiento = start

            proveedor = department_name(meta, dept)
            if not proveedor:
                proveedor = rng.choice(PROVEEDORES_SINTETICOS)

            writer.writerow(
                [
                    key,
                    codigo,
                    dept if dept is not None else "",
                    cantidad,
                    fmt_dec(costo),
                    prioridad,
                    rng.randint(0, 90),
                    fmt_dec(money2(rng.uniform(0.0, 4_000.0))),
                    fmt_dt(fecha),
                    fmt_dt(reabastecimiento),
                    proveedor[:255],
                    rng.choice(NOTAS_REORDER)[:255],
                ]
            )
    finally:
        handle.close()

    return {"count": count, "prioridades": prioridades}


ENVIO_HEADER = [
    "row_key", "tipo_documento", "clave", "consecutivo", "sucursal",
    "terminal", "situacion", "comprobante_id", "emisor_tipo_id",
    "emisor_numero_id", "receptor_tipo_id", "receptor_numero_id", "intentos",
    "proximo_intento", "estado", "origen", "fecha_emision", "fecha_creacion",
    "fecha_ultimo_intento", "fecha_envio", "vencimiento",
]


def generate_envio(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
    start: datetime,
    seed: int,
) -> dict[str, Any]:
    handle, writer = open_csv(out / "envio_fuera_linea.csv", ENVIO_HEADER)
    estados: dict[str, int] = {}
    origenes: dict[str, int] = {}
    try:
        for key in range(1, count + 1):
            comp = rng.choice(meta.comprobantes)
            base = comp.fecha or random_dt(rng, start, now)
            if base > now:
                base = now - timedelta(days=rng.randint(1, 20))
            base = max(base, start)

            emision = base + timedelta(
                minutes=rng.randint(0, 240), seconds=rng.randint(0, 59)
            )
            if emision > now:
                emision = now - timedelta(minutes=rng.randint(1, 120))
            creacion = emision + timedelta(minutes=rng.randint(0, 15))
            if creacion > now:
                creacion = now

            situacion = "3" if rng.random() < 0.70 else "1"
            origen = (
                "OFFLINE_SITUACION_3" if situacion == "3"
                else "FALLO_ENVIO_INMEDIATO"
            )
            origenes[origen] = origenes.get(origen, 0) + 1

            age = (now - creacion).days
            if age <= 7:
                estado = weighted_choice(
                    rng, ("PENDIENTE", "ENVIADO", "RECHAZADO", "VENCIDO"),
                    (70, 20, 5, 5),
                )
            elif age <= 60:
                estado = weighted_choice(
                    rng, ("PENDIENTE", "ENVIADO", "RECHAZADO", "VENCIDO"),
                    (25, 55, 12, 8),
                )
            else:
                estado = weighted_choice(
                    rng, ("PENDIENTE", "ENVIADO", "RECHAZADO", "VENCIDO"),
                    (2, 63, 15, 20),
                )
            estados[estado] = estados.get(estado, 0) + 1

            sucursal = comp.consecutivo[0:3] if len(comp.consecutivo) >= 3 else "001"
            terminal = comp.consecutivo[3:8] if len(comp.consecutivo) >= 8 else "00001"
            tipo = (comp.tipo or "01")[:2].rjust(2, "0")
            seq = f"{abs(seed) % 10000:04d}{key % 1_000_000:06d}"
            consecutivo = f"{sucursal}{terminal}{tipo}{seq}"
            if len(consecutivo) != 20:
                raise ValueError(f"Consecutivo invalido ({len(consecutivo)}): {consecutivo!r}")

            clave = build_clave(
                emision,
                identificacion(comp.emisor_numero, 12, comp.id),
                consecutivo,
                situacion,
                f"{rng.randrange(0, 100_000_000):08d}",
            )

            if estado == "PENDIENTE":
                intentos = rng.randint(0, 2)
            elif estado == "ENVIADO":
                intentos = rng.randint(1, 5)
            else:
                intentos = rng.randint(2, 10)

            proximo: datetime | None = None
            ultimo_intento: datetime | None = None
            fecha_envio: datetime | None = None

            if estado == "PENDIENTE":
                proximo = now + timedelta(
                    minutes=rng.randint(15, 48 * 60)
                )
            if intentos > 0:
                ultimo_intento = creacion + timedelta(
                    minutes=rng.randint(1, 600)
                )
                if ultimo_intento > now:
                    ultimo_intento = now
            if estado in ("ENVIADO", "RECHAZADO"):
                fecha_envio = (ultimo_intento or creacion) + timedelta(
                    minutes=rng.randint(0, 90)
                )
                if fecha_envio > now:
                    fecha_envio = now

            vencimiento = add_business_days(emision, 2).replace(
                hour=23, minute=59, second=59
            )

            writer.writerow(
                [
                    key,
                    tipo,
                    clave,
                    consecutivo,
                    sucursal,
                    terminal,
                    situacion,
                    comp.id,
                    (comp.emisor_tipo or "01")[:2],
                    identificacion(comp.emisor_numero, 12, comp.id),
                    (comp.receptor_tipo or "01")[:2],
                    identificacion(comp.receptor_numero, 12, comp.id * 7 + 13),
                    intentos,
                    fmt_dt(proximo),
                    estado,
                    origen,
                    fmt_dt(emision),
                    fmt_dt(creacion),
                    fmt_dt(ultimo_intento),
                    fmt_dt(fecha_envio),
                    fmt_dt(vencimiento),
                ]
            )
    finally:
        handle.close()

    return {"count": count, "estados": estados, "origenes": origenes}


DEPT_HEADER = [
    "row_key", "departamento_id", "fechacalculo", "totalfacturasrecibidas",
    "facturaspagadas", "montototalcompras", "montopromediofactura",
    "tiempoentregapromedio", "tasaontimedelivery", "articuloscomprados",
    "score",
]


def generate_departamentometrico(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
    start: datetime,
) -> dict[str, Any]:
    handle, writer = open_csv(out / "departamentometrico.csv", DEPT_HEADER)
    dept_seen: dict[int, int] = {}
    try:
        for key in range(1, count + 1):
            dept = rng.choice(meta.departments)
            dept_seen[dept.id] = dept_seen.get(dept.id, 0) + 1

            total = rng.randint(0, 5_000)
            pagadas = int(total * rng.uniform(0.35, 1.0)) if total else 0
            promedio = rng.uniform(2_500.0, 180_000.0)
            monto_total = money5(Decimal(total) * Decimal(str(promedio)))
            monto_promedio = money5(
                (monto_total / total) if total else Decimal("0")
            )
            tiempo = rng.uniform(0.5, 30.0)
            tasa = rng.uniform(35.0, 100.0)
            articulos = rng.randint(0, min(3_000, max(1, total * 3)))
            pago_ratio = (pagadas / total * 100.0) if total else 0.0
            score = (
                0.45 * tasa
                + 0.30 * max(0.0, 100.0 - min(100.0, tiempo * 3.0))
                + 0.25 * pago_ratio
                + rng.gauss(0.0, 3.0)
            )
            score = max(0.0, min(100.0, score))

            writer.writerow(
                [
                    key,
                    dept.id,
                    fmt_dt(random_dt(rng, start, now)),
                    total,
                    pagadas,
                    fmt_dec(monto_total),
                    fmt_dec(monto_promedio),
                    fmt_float(tiempo),
                    fmt_float(min(100.0, tasa)),
                    articulos,
                    fmt_float(score),
                ]
            )
    finally:
        handle.close()

    return {"count": count, "departamentos": len(dept_seen)}


PRECISION_HEADER = [
    "row_key", "articulo_codigo", "metodo", "mase", "sesgo",
    "horizonte_dias", "fecha_calculo",
]


def generate_precision(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
    start: datetime,
) -> dict[str, Any]:
    handle, writer = open_csv(out / "precision_pronostico.csv", PRECISION_HEADER)
    por_metodo: dict[str, int] = {}
    sin_mase = 0
    try:
        for key in range(1, count + 1):
            article = rng.choice(meta.articles)
            metodo = weighted_choice(
                rng,
                METODOS_PRONOSTICO,
                (10, 8, 24, 20, 18, 12, 8),
            )
            por_metodo[metodo] = por_metodo.get(metodo, 0) + 1

            if rng.random() < 0.08:
                mase: float | None = None
                sesgo: float | None = None if rng.random() < 0.60 else max(
                    -1.5, min(1.5, rng.gauss(0.0, 0.25))
                )
                sin_mase += 1
            else:
                mase = max(0.05, min(4.0, rng.gauss(0.95, 0.35)))
                sesgo = max(-1.5, min(1.5, rng.gauss(0.0, 0.28)))

            horizonte = weighted_choice(rng, (1, 3, 7, 14, 30), (15, 15, 30, 25, 15))

            writer.writerow(
                [
                    key,
                    article.codigo,
                    metodo,
                    fmt_float(mase, 6),
                    fmt_float(sesgo, 6),
                    horizonte,
                    fmt_dt(random_dt(rng, start, now)),
                ]
            )
    finally:
        handle.close()

    return {"count": count, "metodos": por_metodo, "sin_mase": sin_mase}


# ---------------------------------------------------------------------------
# load.sql
# ---------------------------------------------------------------------------

LOAD_SQL = r"""
\set ON_ERROR_STOP on
set client_encoding = 'UTF8';
begin;

-- ===========================================================================
-- Staging. Ninguna tabla destino se toca todavia.
-- ===========================================================================
create temp table stg_nc (
  row_key bigint,
  fecha timestamp,
  fechaanulacion timestamp,
  haciendaclave text,
  haciendaestado text,
  montototal numeric(18,2),
  motivo text,
  status boolean,
  usuario text,
  cliente_id integer,
  comprobante_original_id bigint
) on commit drop;

create temp table stg_alerts (
  row_key bigint,
  articulo_codigo bigint,
  tipo_alerta text,
  cantidad_actual integer,
  cantidad_minima integer,
  sugerido_reordenar integer,
  departamento_id integer,
  estado text,
  fecha_creacion timestamp,
  fecha_resolucion timestamp,
  usuario_resolucion bigint,
  notas text
) on commit drop;

create temp table stg_reorder (
  row_key bigint,
  articulo_codigo bigint,
  departamento_id integer,
  cantidad_sugerida integer,
  costo_total_estimado numeric(10,2),
  prioridad text,
  dias_sin_stock integer,
  promedio_ventas_mensual numeric(8,2),
  fecha_creacion timestamp,
  fecha_ultimo_reabastecimiento timestamp,
  proveedor_sugerido text,
  notas text
) on commit drop;

create temp table stg_envio (
  row_key bigint,
  tipo_documento text,
  clave text,
  consecutivo text,
  sucursal text,
  terminal text,
  situacion text,
  comprobante_id bigint,
  emisor_tipo_id text,
  emisor_numero_id text,
  receptor_tipo_id text,
  receptor_numero_id text,
  intentos integer,
  proximo_intento timestamp,
  estado text,
  origen text,
  fecha_emision timestamp,
  fecha_creacion timestamp,
  fecha_ultimo_intento timestamp,
  fecha_envio timestamp,
  vencimiento timestamp
) on commit drop;

create temp table stg_deptmetrics (
  row_key bigint,
  departamento_id integer,
  fechacalculo timestamp,
  totalfacturasrecibidas integer,
  facturaspagadas integer,
  montototalcompras numeric(18,5),
  montopromediofactura numeric(18,5),
  tiempoentregapromedio double precision,
  tasaontimedelivery double precision,
  articuloscomprados integer,
  score double precision
) on commit drop;

create temp table stg_precision (
  row_key bigint,
  articulo_codigo bigint,
  metodo text,
  mase double precision,
  sesgo double precision,
  horizonte_dias integer,
  fecha_calculo timestamp
) on commit drop;

\copy stg_nc from '__NC_CSV__' csv header
\copy stg_alerts from '__ALERTS_CSV__' csv header
\copy stg_reorder from '__REORDER_CSV__' csv header
\copy stg_envio from '__ENVIO_CSV__' csv header
\copy stg_deptmetrics from '__DEPTMETRICS_CSV__' csv header
\copy stg_precision from '__PRECISION_CSV__' csv header

-- ===========================================================================
-- Validaciones de FK antes de insertar. Si algo no calza, se aborta todo.
-- ===========================================================================
do $$
declare
  v_bad bigint;
begin
  select count(*) into v_bad
    from stg_nc s
   where s.comprobante_original_id is null
      or not exists (
           select 1 from comprobantesemitidos c
            where c.id = s.comprobante_original_id
         );
  if v_bad > 0 then
    raise exception 'notacredito: % filas con comprobante_original_id inexistente', v_bad;
  end if;

  select count(*) into v_bad
    from stg_nc s
   where s.cliente_id is not null
     and not exists (select 1 from clients c where c.code = s.cliente_id);
  if v_bad > 0 then
    raise exception 'notacredito: % filas con cliente_id inexistente en clients(code)', v_bad;
  end if;

  select count(*) into v_bad
    from stg_alerts s
   where not exists (select 1 from articulos a where a.codigo = s.articulo_codigo);
  if v_bad > 0 then
    raise exception 'stock_alerts: % filas con articulo_codigo inexistente', v_bad;
  end if;

  select count(*) into v_bad
    from stg_alerts s
   where s.departamento_id is not null
     and not exists (select 1 from departamento d where d.id = s.departamento_id);
  if v_bad > 0 then
    raise exception 'stock_alerts: % filas con departamento_id inexistente', v_bad;
  end if;

  select count(*) into v_bad
    from stg_alerts s
   where s.usuario_resolucion is not null
     and not exists (select 1 from users u where u.id = s.usuario_resolucion);
  if v_bad > 0 then
    raise exception 'stock_alerts: % filas con usuario_resolucion inexistente', v_bad;
  end if;

  select count(*) into v_bad
    from stg_reorder s
   where not exists (select 1 from articulos a where a.codigo = s.articulo_codigo);
  if v_bad > 0 then
    raise exception 'reorder_suggestions: % filas con articulo_codigo inexistente', v_bad;
  end if;

  select count(*) into v_bad
    from stg_reorder s
   where s.departamento_id is not null
     and not exists (select 1 from departamento d where d.id = s.departamento_id);
  if v_bad > 0 then
    raise exception 'reorder_suggestions: % filas con departamento_id inexistente', v_bad;
  end if;

  select count(*) into v_bad
    from stg_envio s
   where s.comprobante_id is not null
     and not exists (
           select 1 from comprobantesemitidos c where c.id = s.comprobante_id
         );
  if v_bad > 0 then
    raise exception 'envio_fuera_linea: % filas con comprobante_id inexistente', v_bad;
  end if;

  select count(*) into v_bad from stg_envio s where length(s.clave) <> 50;
  if v_bad > 0 then
    raise exception 'envio_fuera_linea: % filas con clave de longitud distinta de 50', v_bad;
  end if;

  select count(*) into v_bad
    from (
      select s.clave
        from stg_envio s
       group by s.clave
      having count(*) > 1
    ) q;
  if v_bad > 0 then
    raise exception 'envio_fuera_linea: % claves duplicadas dentro del CSV', v_bad;
  end if;

  select count(*) into v_bad
    from stg_deptmetrics s
   where not exists (select 1 from departamento d where d.id = s.departamento_id);
  if v_bad > 0 then
    raise exception 'departamentometrico: % filas con departamento_id inexistente', v_bad;
  end if;

  select count(*) into v_bad
    from stg_precision s
   where not exists (select 1 from articulos a where a.codigo = s.articulo_codigo);
  if v_bad > 0 then
    raise exception 'precision_pronostico: % filas con articulo_codigo inexistente', v_bad;
  end if;
end $$;

-- ===========================================================================
-- 1) notacredito. FK: comprobantesemitidos(id) y clients(code).
-- ===========================================================================
insert into notacredito (
  id, fecha, fechaanulacion, haciendaclave, haciendaestado, montototal,
  motivo, status, usuario, cliente_id, comprobante_original_id
)
select (select coalesce(max(id), 0) from notacredito)
       + row_number() over (order by s.row_key),
       s.fecha,
       s.fechaanulacion,
       nullif(s.haciendaclave, ''),
       nullif(s.haciendaestado, ''),
       s.montototal,
       s.motivo,
       coalesce(s.status, true),
       nullif(s.usuario, ''),
       s.cliente_id,
       s.comprobante_original_id
  from stg_nc s;

-- ===========================================================================
-- 2) stock_alerts. FK: articulos(codigo), departamento(id), users(id).
-- ===========================================================================
insert into stock_alerts (
  id, articulo_codigo, tipo_alerta, cantidad_actual, cantidad_minima,
  sugerido_reordenar, departamento_id, estado, fecha_creacion,
  fecha_resolucion, usuario_resolucion, notas
)
select (
         (select coalesce(max(id), 0) from stock_alerts)
         + row_number() over (order by s.row_key)
       )::integer,
       s.articulo_codigo,
       s.tipo_alerta,
       s.cantidad_actual,
       s.cantidad_minima,
       s.sugerido_reordenar,
       s.departamento_id,
       s.estado,
       s.fecha_creacion,
       s.fecha_resolucion,
       s.usuario_resolucion,
       nullif(s.notas, '')
  from stg_alerts s;

-- ===========================================================================
-- 3) reorder_suggestions. FK: articulos(codigo), departamento(id).
-- ===========================================================================
insert into reorder_suggestions (
  id, articulo_codigo, departamento_id, cantidad_sugerida,
  costo_total_estimado, prioridad, dias_sin_stock, promedio_ventas_mensual,
  fecha_creacion, fecha_ultimo_reabastecimiento, proveedor_sugerido, notas
)
select (
         (select coalesce(max(id), 0) from reorder_suggestions)
         + row_number() over (order by s.row_key)
       )::integer,
       s.articulo_codigo,
       s.departamento_id,
       s.cantidad_sugerida,
       s.costo_total_estimado,
       s.prioridad,
       s.dias_sin_stock,
       s.promedio_ventas_mensual,
       s.fecha_creacion,
       s.fecha_ultimo_reabastecimiento,
       nullif(s.proveedor_sugerido, ''),
       nullif(s.notas, '')
  from stg_reorder s;

-- ===========================================================================
-- 4) envio_fuera_linea. comprobante_id es puntero sin FK fisica; se valida
--    contra comprobantesemitidos. La clave es unica: se omite si ya existe.
--    Las columnas LOB (xml_firmado, xml_firmado_bytes, ultimo_error) quedan
--    NULL a proposito.
-- ===========================================================================
insert into envio_fuera_linea (
  id, tipo_documento, clave, consecutivo, sucursal, terminal, situacion,
  comprobante_id, emisor_tipo_id, emisor_numero_id, receptor_tipo_id,
  receptor_numero_id, intentos, proximo_intento, estado, origen,
  fecha_emision, fecha_creacion, fecha_ultimo_intento, fecha_envio, vencimiento
)
select (select coalesce(max(id), 0) from envio_fuera_linea)
       + row_number() over (order by s.row_key),
       s.tipo_documento,
       s.clave,
       s.consecutivo,
       s.sucursal,
       s.terminal,
       s.situacion,
       s.comprobante_id,
       nullif(s.emisor_tipo_id, ''),
       nullif(s.emisor_numero_id, ''),
       nullif(s.receptor_tipo_id, ''),
       nullif(s.receptor_numero_id, ''),
       s.intentos,
       s.proximo_intento,
       s.estado,
       nullif(s.origen, ''),
       s.fecha_emision,
       s.fecha_creacion,
       s.fecha_ultimo_intento,
       s.fecha_envio,
       s.vencimiento
  from stg_envio s
 where not exists (
       select 1 from envio_fuera_linea e where e.clave = s.clave
 );

-- ===========================================================================
-- 5) departamentometrico. FK: departamento(id). Sin unique por fecha.
-- ===========================================================================
insert into departamentometrico (
  id, departamento_id, fechacalculo, totalfacturasrecibidas,
  facturaspagadas, montototalcompras, montopromediofactura,
  tiempoentregapromedio, tasaontimedelivery, articuloscomprados, score
)
select (select coalesce(max(id), 0) from departamentometrico)
       + row_number() over (order by s.row_key),
       s.departamento_id,
       s.fechacalculo,
       s.totalfacturasrecibidas,
       s.facturaspagadas,
       s.montototalcompras,
       s.montopromediofactura,
       s.tiempoentregapromedio,
       s.tasaontimedelivery,
       s.articuloscomprados,
       s.score
  from stg_deptmetrics s;

-- ===========================================================================
-- 6) precision_pronostico. FK: articulos(codigo). Sin unique por articulo:
--    la tabla admite varias corridas y metodos.
-- ===========================================================================
insert into precision_pronostico (
  id, articulo_codigo, metodo, mase, sesgo, horizonte_dias, fecha_calculo
)
select (select coalesce(max(id), 0) from precision_pronostico)
       + row_number() over (order by s.row_key),
       s.articulo_codigo,
       s.metodo,
       s.mase,
       s.sesgo,
       s.horizonte_dias,
       s.fecha_calculo
  from stg_precision s;

-- ===========================================================================
-- Resincronizar secuencias tras inserts con id explicito.
-- ===========================================================================
select setval('notacredito_id_seq',
              coalesce((select max(id) from notacredito), 1),
              exists(select 1 from notacredito));
select setval('stock_alerts_id_seq',
              coalesce((select max(id) from stock_alerts), 1),
              exists(select 1 from stock_alerts));
select setval('reorder_suggestions_id_seq',
              coalesce((select max(id) from reorder_suggestions), 1),
              exists(select 1 from reorder_suggestions));
select setval('envio_fuera_linea_id_seq',
              coalesce((select max(id) from envio_fuera_linea), 1),
              exists(select 1 from envio_fuera_linea));
select setval('departamentometrico_id_seq',
              coalesce((select max(id) from departamentometrico), 1),
              exists(select 1 from departamentometrico));
select setval('precision_pronostico_id_seq',
              coalesce((select max(id) from precision_pronostico), 1),
              exists(select 1 from precision_pronostico));

commit;

analyze notacredito;
analyze stock_alerts;
analyze reorder_suggestions;
analyze envio_fuera_linea;
analyze departamentometrico;
analyze precision_pronostico;

select 'notacredito' as tabla, count(*) as filas from notacredito
union all select 'stock_alerts', count(*) from stock_alerts
union all select 'reorder_suggestions', count(*) from reorder_suggestions
union all select 'envio_fuera_linea', count(*) from envio_fuera_linea
union all select 'departamentometrico', count(*) from departamentometrico
union all select 'precision_pronostico', count(*) from precision_pronostico
order by 1;
"""


def build_load_sql(out: Path) -> Path:
    sql = LOAD_SQL
    replacements = {
        "__NC_CSV__": out / "notacredito.csv",
        "__ALERTS_CSV__": out / "stock_alerts.csv",
        "__REORDER_CSV__": out / "reorder_suggestions.csv",
        "__ENVIO_CSV__": out / "envio_fuera_linea.csv",
        "__DEPTMETRICS_CSV__": out / "departamentometrico.csv",
        "__PRECISION_CSV__": out / "precision_pronostico.csv",
    }
    for placeholder, path in replacements.items():
        literal = str(path).replace("\\", "/").replace("'", "''")
        sql = sql.replace(placeholder, literal)
    path = out / "load.sql"
    path.write_text(sql, encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# Reporte
# ---------------------------------------------------------------------------


def write_report(
    out: Path,
    args: argparse.Namespace,
    meta: Meta,
    stats: dict[str, Any],
    load_path: Path,
) -> Path:
    generated = {
        "notacredito": stats["nc"]["count"],
        "stock_alerts": stats["alerts"]["count"],
        "reorder_suggestions": stats["reorder"]["count"],
        "envio_fuera_linea": stats["envio"]["count"],
        "departamentometrico": stats["deptmetrics"]["count"],
        "precision_pronostico": stats["precision"]["count"],
    }

    lines: list[str] = []
    lines.append("# Reporte de tablas menores de carga — Mercurius")
    lines.append("")
    lines.append(f"- Fecha de generación: {datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"- Semilla: {args.seed}")
    lines.append(f"- Destino: `{args.user}@{args.host}:{args.port}/{args.database}` (solo lectura de referencia)")
    lines.append(f"- Directorio: `{out}`")
    lines.append(f"- Script de carga: `{load_path}`")
    lines.append("")
    lines.append("## Orden exacto de carga")
    lines.append("")
    for index, table in enumerate(TABLE_ORDER, start=1):
        lines.append(f"{index}. `{table}`")
    lines.append("")
    lines.append("## Conteos")
    lines.append("")
    lines.append("| # | Tabla | Existentes | Generadas | Total esperado |")
    lines.append("|---:|---|---:|---:|---:|")
    for index, table in enumerate(TABLE_ORDER, start=1):
        existing = meta.counts.get(table, 0)
        new = generated.get(table, 0)
        lines.append(f"| {index} | `{table}` | {existing:,} | {new:,} | {existing + new:,} |")
    lines.append("")
    lines.append("### Notas de generación")
    lines.append("")
    nc_estados = stats["nc"]["estados"]
    lines.append(
        "- `notacredito`: "
        + ", ".join(f"`{k}` {v:,}" for k, v in sorted(nc_estados.items()))
        + f"; anuladas: {stats['nc']['anuladas']:,}."
    )
    alert_tipos = stats["alerts"]["tipos"]
    alert_estados = stats["alerts"]["estados"]
    lines.append(
        "- `stock_alerts`: "
        + ", ".join(f"`{k}` {v:,}" for k, v in sorted(alert_tipos.items()))
        + "; estados: "
        + ", ".join(f"`{k}` {v:,}" for k, v in sorted(alert_estados.items()))
        + "."
    )
    lines.append(
        "- `reorder_suggestions`: prioridades "
        + ", ".join(f"`{k}` {v:,}" for k, v in sorted(stats["reorder"]["prioridades"].items()))
        + "."
    )
    envio_estados = stats["envio"]["estados"]
    lines.append(
        "- `envio_fuera_linea`: "
        + ", ".join(f"`{k}` {v:,}" for k, v in sorted(envio_estados.items()))
        + "; origen: "
        + ", ".join(f"`{k}` {v:,}" for k, v in sorted(stats["envio"]["origenes"].items()))
        + "."
    )
    lines.append(
        f"- `departamentometrico`: {stats['deptmetrics']['count']:,} filas sobre "
        f"{stats['deptmetrics']['departamentos']:,} departamentos distintos."
    )
    lines.append(
        f"- `precision_pronostico`: {stats['precision']['count']:,} filas; "
        f"{stats['precision']['sin_mase']:,} sin MASE (NULL justificado)."
    )
    lines.append("")
    lines.append("## Columnas por tabla")
    lines.append("")
    for table in TABLE_ORDER:
        lines.append(f"### `{table}`")
        lines.append("")
        for column, dtype, nullable in meta.columns.get(table, []):
            null_txt = "NULL" if nullable == "YES" else "NOT NULL"
            lines.append(f"- `{column}` {dtype} {null_txt}")
        lines.append("")
    lines.append("## Estrategia de IDs y FK")
    lines.append("")
    lines.append("- Todos los `id` se calculan en SQL como `(select coalesce(max(id),0) from T) + row_number()`; nunca se hardcodea el id final.")
    lines.append("- `stock_alerts.id` y `reorder_suggestions.id` son `integer`: el cálculo se castea a `integer`.")
    lines.append("- Los CSV llevan `row_key` solo para ordenar el `row_number()` de forma estable; no se inserta.")
    lines.append("- `notacredito.comprobante_original_id` usa IDs reales de `comprobantesemitidos` (preferentemente `ACEPTADO` tipo `01`).")
    lines.append("- `notacredito.cliente_id` usa `clients.code` (la FK real, no `clientes`).")
    lines.append("- `notacredito.fecha` se deriva de la fecha del comprobante original + 0–90 días, acotada a hoy; cubre el histórico real de 1–3 años.")
    lines.append("- `stock_alerts.articulo_codigo` y `reorder_suggestions.articulo_codigo` usan `articulos.codigo` existentes; el `departamento_id` se toma del propio artículo cuando existe.")
    lines.append("- `stock_alerts.usuario_resolucion` usa `users.id` existentes solo para estados `acknowledged`/`resolved`.")
    lines.append("- `reorder_suggestions.proveedor_sugerido` copia el nombre del `departamento` del artículo (texto, no FK).")
    lines.append("- `envio_fuera_linea.comprobante_id` usa IDs reales de `comprobantesemitidos`; no hay FK física en el esquema, pero se valida en `load.sql`.")
    lines.append("- Las claves de `envio_fuera_linea` son de 50 dígitos (506 + DDMMYY + 12 del emisor + 20 del consecutivo + situación + 8 de seguridad) y únicas por `row_key`; el `load.sql` omite claves ya existentes.")
    lines.append("- `departamentometrico.departamento_id` usa `departamento.id` existentes.")
    lines.append("- `precision_pronostico.articulo_codigo` usa `articulos.codigo` existentes y métodos del enum `MetodoPronostico`.")
    lines.append("- Las secuencias de las seis tablas se resincronizan con `setval(...)` al final del `load.sql`.")
    lines.append("")
    lines.append("## Tablas omitidas y decisiones")
    lines.append("")
    lines.append("- No se generó ninguna tabla de líneas/detalle de nota de crédito porque **no existe** en el esquema. `detalle_servicio` y `linea_detalle` cuelgan de `comprobantesemitidos` y no distinguen NC; agregar filas allí falsearía el grafo de facturación.")
    lines.append("- `envio_fuera_linea.xml_firmado`, `xml_firmado_bytes` y `ultimo_error` quedan NULL: son `oid` (large objects) nullable y no aportan a la prueba de carga.")
    lines.append("- No se toca ninguna otra tabla (ni `comprobantesemitidos`, ni `encabezado`, ni `clientes`).")
    lines.append("")
    lines.append("## Riesgos y supuestos")
    lines.append("")
    lines.append("- Los IDs se calculan contra el `max(id)` vigente al ejecutar `load.sql`; no cargue en paralelo con otro proceso que inserte en estas tablas.")
    lines.append("- Las fechas son sintéticas y relativas al momento de generación; una carga diferida conserva los valores del CSV.")
    lines.append("- `precision_pronostico` no tiene unique por `(articulo, metodo)`: se generan varias corridas y métodos por artículo a propósito.")
    lines.append("- `stock_alerts` y `reorder_suggestions` pueden repetir artículo/fecha; el esquema no impone unicidad.")
    lines.append("- `envio_fuera_linea` puede omitir filas si la clave ya existe; el conteo final del `load.sql` lo refleja.")
    lines.append("- Los montos son ficticios y no se reconcilian contra facturas reales.")
    lines.append("")
    lines.append("## Comando de carga")
    lines.append("")
    lines.append("```powershell")
    lines.append(
        f"$env:PGPASSWORD='<password>'; psql -h {args.host} -p {args.port} "
        f"-U {args.user} -d {args.database} -v ON_ERROR_STOP=1 -f \"{load_path}\""
    )
    lines.append("```")
    lines.append("")
    lines.append("También puede usar el propio generador (no lo hace por defecto):")
    lines.append("")
    lines.append("```powershell")
    lines.append(
        f"python scripts/seed/minor_tables.py --out \"{out}\" --host {args.host} "
        f"--port {args.port} --user {args.user} --password '<password>' --load"
    )
    lines.append("```")
    lines.append("")

    path = out / "report.md"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Genera CSV y load.sql de tablas menores para pruebas de carga de Mercurius.",
    )
    parser.add_argument("--count-nc", type=int, default=50_000)
    parser.add_argument("--count-alerts", type=int, default=200_000)
    parser.add_argument("--count-reorder", type=int, default=200_000)
    parser.add_argument("--count-envio", type=int, default=100_000)
    parser.add_argument("--count-deptmetrics", type=int, default=200_000)
    parser.add_argument("--count-precision", type=int, default=200_000)
    parser.add_argument("--out", type=Path,
                        default=Path(tempfile.gettempdir()) / "mercurius-seed-minor")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--database", default="mercurius")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5433)
    parser.add_argument("--user", default="mercurius")
    parser.add_argument("--password", default=os.environ.get("PGPASSWORD", ""))
    parser.add_argument("--psql", default="psql",
                        help="ejecutable psql para lectura de referencia y --load")
    parser.add_argument("--load", action="store_true",
                        help="ejecutar load.sql con psql (NO es el comportamiento por defecto)")
    parser.add_argument("--force", action="store_true", help="permitir un host no local")
    parser.add_argument("--verbose", action="store_true")
    return parser


def validate_args(parser: argparse.ArgumentParser, args: argparse.Namespace) -> None:
    if args.database not in ALLOWED_DATABASES:
        parser.error(
            f"base rechazada '{args.database}'; permitidas: {', '.join(sorted(ALLOWED_DATABASES))}"
        )
    if args.host not in LOCAL_HOSTS and not args.force:
        parser.error(f"host no local rechazado '{args.host}'; use --force para autorizarlo")
    for name in (
        "count_nc", "count_alerts", "count_reorder", "count_envio",
        "count_deptmetrics", "count_precision",
    ):
        if getattr(args, name) < 0:
            parser.error(f"--{name.replace('_', '-')} no puede ser negativo")


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    validate_args(parser, args)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    try:
        args.out.mkdir(parents=True, exist_ok=True)
    except OSError:
        LOG.exception("No se pudo crear el directorio de salida %s", args.out)
        return 1

    try:
        args.psql = resolve_psql(args.psql)
        meta = fetch_meta(args)
    except Exception:
        LOG.exception("No se pudieron leer los metadatos de la base; no se generó nada")
        return 1

    need_articles = args.count_alerts or args.count_reorder or args.count_precision
    need_departments = args.count_deptmetrics or args.count_alerts or args.count_reorder
    need_comprobantes = args.count_nc or args.count_envio

    if need_articles and not meta.articles:
        LOG.error("No hay articulos; no se pueden generar alertas/reorden/precisión FK-safe")
        return 1
    if need_departments and not meta.departments:
        LOG.error("No hay departamento; no se pueden generar métricas/alertas/reorden FK-safe")
        return 1
    if need_comprobantes and not meta.comprobantes:
        LOG.error("No hay comprobantes emitidos; no se pueden generar NC/envíos FK-safe")
        return 1
    if need_comprobantes and not meta.clients:
        LOG.warning("clients está vacía; notacredito.cliente_id se dejará NULL")

    rng = random.Random(args.seed)
    # Ancla estable por día: misma semilla -> mismos CSV dentro de la misma
    # fecha de generación, sin depender de la hora exacta de ejecución.
    now = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    start = now - timedelta(days=365 * 3)

    try:
        stats: dict[str, Any] = {}
        stats["nc"] = generate_nc(meta, args.count_nc, rng, args.out, now, start, args.seed)
        stats["alerts"] = generate_alerts(meta, args.count_alerts, rng, args.out, now, start)
        stats["reorder"] = generate_reorder(meta, args.count_reorder, rng, args.out, now, start)
        stats["envio"] = generate_envio(meta, args.count_envio, rng, args.out, now, start, args.seed)
        stats["deptmetrics"] = generate_departamentometrico(
            meta, args.count_deptmetrics, rng, args.out, now, start
        )
        stats["precision"] = generate_precision(
            meta, args.count_precision, rng, args.out, now, start
        )
    except Exception:
        LOG.exception("Falló la generación de datos")
        return 1

    load_path = build_load_sql(args.out)
    report_path = write_report(args.out, args, meta, stats, load_path)

    total = sum(item["count"] for item in stats.values())
    LOG.info("Generadas %s filas en %s", f"{total:,}", args.out)
    LOG.info("Script de carga: %s", load_path)
    LOG.info("Reporte: %s", report_path)

    if args.load:
        cmd = [
            args.psql, "-h", args.host, "-p", str(args.port), "-U", args.user,
            "-d", args.database, "-v", "ON_ERROR_STOP=1", "-f", str(load_path),
        ]
        LOG.info("Ejecutando: %s", " ".join(cmd))
        try:
            return subprocess.call(cmd, env=psql_env(args.password))
        except OSError:
            LOG.exception("No se pudo ejecutar psql (%s)", args.psql)
            return 1

    LOG.info("No se ejecutó la carga (use --load para hacerlo)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
