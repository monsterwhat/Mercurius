#!/usr/bin/env python3
"""
Generador de datos operativos/referencia para pruebas de carga de Mercurius.

Genera CSV + un `load.sql` con tablas de staging y COPY. NO inserta nada por si
solo: `--load` es explícito y este script no lo ejecuta automáticamente.

Tablas generadas
----------------
orden_compra, orden_compra_detalle, promocion, articulo_carrito_promocion,
cabys, tipocambio, users, reporteprogramado, emailtemplate.

Particularidad de `articulo_carrito_promocion`
----------------------------------------------
La FK real en esta base es `articulo_carrito_id -> articulocarrito(codigo)`,
no a `articulos`. Como `articulocarrito` está vacía en una base limpia, para
que las filas de unión sean FK-safe se generan también líneas de carrito
sintéticas de soporte (`articulocarrito.csv`). Nunca se toca una tabla fuera
de las listadas salvo esa tabla de soporte, y el reporte lo deja explícito.

Seguridad
---------
- Solo bases `mercurius` y `mercurius_test`.
- Solo hosts locales, salvo `--force`.
- Solo lectura para descubrir IDs existentes (`set_session(readonly=True)`).
- No borra ni actualiza filas existentes.

Uso
---
    python scripts/seed/ops.py --out scripts/seed/out
    python scripts/seed/ops.py --out scripts/seed/out \
        --host localhost --port 5433 --user mercurius --password '...'
    python scripts/seed/ops.py --out scripts/seed/out --load

Salidas (en --out)
-------------------
orden_compra.csv, orden_compra_detalle.csv, promocion.csv,
articulo_carrito_promocion.csv, articulocarrito.csv, cabys.csv,
tipocambio.csv, users.csv, reporteprogramado.csv, emailtemplate.csv,
load.sql, report.md
"""

from __future__ import annotations

import argparse
import csv
import logging
import os
import random
import subprocess
import tempfile
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence

LOG = logging.getLogger("mercurius.seed.ops")

ALLOWED_DATABASES = {"mercurius", "mercurius_test"}
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}

DEFAULT_ADMIN123_HASH = (
    "$2b$10$H/crFwETmOxIQ7qIUJbeU.K5Y2g0ebQLTQhKEpmmWRmtE4JfBvyVq"
)

ESTADOS = ["BORRADOR", "ENVIADA", "CONFIRMADA", "RECIBIDA", "FACTURADA"]
ESTADO_WEIGHTS_OLD = [5, 10, 10, 30, 45]
ESTADO_WEIGHTS_MID = [10, 15, 25, 30, 20]
ESTADO_WEIGHTS_NEW = [30, 30, 25, 10, 5]

FRECUENCIAS = ["Diario", "Semanal", "Quincenal", "Mensual"]
REPORTES = ["MOVIMIENTOS", "FACTURACION", "ARTICULOS",
            "DEPARTAMENTOS", "FAMILIAS", "INVENTARIOS"]
EMAIL_TIPOS = ["REPORTES", "ALERTAS_STOCK", "NOTIFICACIONES", "PERSONALIZADO"]

PROMO_BASES = [
    "Semana del arroz", "Martes de fresco", "Combo desayuno",
    "Oferta de limpieza", "Especial lácteos", "Día del café",
    "Promoción de snacks", "Canasta básica", "Fin de semana bebidas",
    "Ahorro en embutidos", "Temporada escolar", "Cuidado personal",
    "Oferta de panadería", "Precios bajos en granos", "Combo familiar",
]

PROMO_SEASONS = ["Verano", "Escolar", "San José", "Fin de año",
                 "Semana Santa", "Aniversario", "Regreso a clases"]

CABYS_PRODUCTS = [
    "Arroz blanco de grano largo",
    "Frijol negro seco",
    "Leche entera pasteurizada",
    "Café molido tostado",
    "Azúcar blanca refinada",
    "Aceite vegetal comestible",
    "Detergente en polvo para ropa",
    "Jabón de baño de glicerina",
    "Papel higiénico doble hoja",
    "Atún en agua enlatado",
    "Pasta spaghetti de sémola",
    "Salsa de tomate envasada",
    "Galletas de soda saladas",
    "Cereal de hojuelas de maíz",
    "Refresco de cola en botella",
    "Cerveza lager en lata",
    "Vino tinto de mesa",
    "Alimento balanceado para perro",
    "Alimento balanceado para gato",
    "Focos LED de bajo consumo",
    "Pilas alcalinas AA",
    "Bolsas plásticas para residuos",
    "Cloro líquido desinfectante",
    "Crema dental con flúor",
    "Shampoo para cabello normal",
    "Desodorante en barra",
    "Maíz dulce enlatado",
    "Sardina en salsa de tomate",
    "Mayonesa cremosa",
    "Mostaza amarilla",
    "Salchicha de pollo",
    "Jamón ahumado rebanado",
    "Queso fresco pasteurizado",
    "Yogurt natural sin azúcar",
    "Mantequilla con sal",
    "Jugo de naranja pasteurizado",
    "Agua purificada embotellada",
    "Té negro en bolsitas",
    "Chocolate en polvo",
    "Miel de abeja pura",
    "Servicio de mantenimiento de equipo de cómputo",
    "Servicio de instalación de software",
    "Servicio de consultoría administrativa",
    "Servicio de transporte de carga local",
    "Servicio de reparación de vehículos",
    "Servicio de limpieza de oficinas",
    "Servicio de seguridad privada",
    "Servicio de capacitación empresarial",
    "Servicio de diseño gráfico",
    "Servicio de contabilidad mensual",
]

CABYS_SECTORS = [
    "Productos de la agricultura, silvicultura y pesca",
    "Productos alimenticios, bebidas y tabaco",
    "Productos textiles, prendas de vestir y cuero",
    "Productos de madera y productos de papel",
    "Productos químicos y productos conexos",
    "Productos de caucho y plástico",
    "Productos minerales no metálicos",
    "Productos metálicos, maquinaria y equipo",
    "Servicios de construcción y conexos",
    "Servicios de comercio al por mayor y menor",
    "Servicios de transporte y almacenamiento",
    "Servicios de alojamiento y alimentación",
    "Servicios profesionales, científicos y técnicos",
    "Servicios administrativos y de apoyo",
    "Servicios de educación",
    "Servicios de salud y asistencia social",
]

CABYS_FAMILIES = [
    "Bienes transportables", "Bienes no transportables", "Servicios",
    "Productos para consumo final", "Productos intermedios",
    "Bienes de capital", "Insumos agropecuarios",
]

USER_GROUPS = [
    ("usuario", 55),
    ("[usuario, facturacion]", 15),
    ("[usuario, inventario]", 15),
    ("[usuario, tributacion]", 7),
    ("[usuario, registro]", 5),
    ("[admin, usuario]", 3),
]

ORDER_NOTES = [
    "Entrega en bodega central.",
    "Confirmar disponibilidad antes de despachar.",
    "Pedido de reposición mensual.",
    "Requiere factura electrónica.",
    "Entrega parcial autorizada.",
    "Revisar fechas de vencimiento al recibir.",
]

DETAIL_NOTES = [
    "Empaque original.",
    "Producto sujeto a promoción.",
    "Verificar lote al recibir.",
    "Cantidad ajustada por bodega.",
]

TABLE_ORDER = [
    "users",
    "cabys",
    "tipocambio",
    "orden_compra",
    "orden_compra_detalle",
    "promocion",
    "articulocarrito",
    "articulo_carrito_promocion",
    "reporteprogramado",
    "emailtemplate",
]

MONEY = Decimal("0.01")
UNIT_PRICE = Decimal("0.0001")


# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------


def money(value: Decimal | float | int | str) -> Decimal:
    return Decimal(str(value)).quantize(MONEY, rounding=ROUND_HALF_UP)


def unit_price(value: Decimal | float | int | str) -> Decimal:
    return Decimal(str(value)).quantize(UNIT_PRICE, rounding=ROUND_HALF_UP)


def fmt_dt(value: datetime | None) -> str:
    if value is None:
        return ""
    return value.replace(microsecond=0).isoformat(sep=" ", timespec="seconds")


def fmt_bool(value: bool) -> str:
    return "true" if value else "false"


def fmt_dec(value: Decimal | None) -> str:
    return "" if value is None else format(value, "f")


def weighted_choice(rng: random.Random, values: Sequence[Any], weights: Sequence[int]) -> Any:
    return rng.choices(values, weights=weights, k=1)[0]


def open_csv(path: Path, fieldnames: Sequence[str]) -> tuple[Any, csv.DictWriter]:
    handle = path.open("w", newline="", encoding="utf-8")
    writer = csv.DictWriter(handle, fieldnames=list(fieldnames), lineterminator="\n")
    writer.writeheader()
    return handle, writer


def write_csv(path: Path, fieldnames: Sequence[str], rows: Iterable[dict[str, Any]]) -> int:
    handle, writer = open_csv(path, fieldnames)
    count = 0
    try:
        for row in rows:
            writer.writerow(row)
            count += 1
    finally:
        handle.close()
    return count


# ---------------------------------------------------------------------------
# Descubrimiento de la base (solo lectura)
# ---------------------------------------------------------------------------


@dataclass
class Meta:
    counts: dict[str, int] = field(default_factory=dict)
    columns: dict[str, list[tuple[str, str, str]]] = field(default_factory=dict)
    articulos: list[int] = field(default_factory=list)
    departamentos: list[int] = field(default_factory=list)
    usuarios: list[int] = field(default_factory=list)
    usernames: set[str] = field(default_factory=set)
    cabys_prefix: str = "79"
    cabys_existing: set[str] = field(default_factory=set)
    tc_dates: set[date] = field(default_factory=set)
    password_hash: str = ""
    password_note: str = ""
    order_next: dict[int, int] = field(default_factory=dict)


def _table_count(cur: Any, table: str) -> int:
    cur.execute(f"select count(*) from {table}")
    return int(cur.fetchone()[0])


def pick_password_hash(cur: Any) -> tuple[str, str]:
    """Reutiliza el hash de admin123 de una fila existente cuando existe."""
    cur.execute("select password from users where password is not null and password <> '' order by id")
    hashes = [str(row[0]) for row in cur.fetchall()]

    try:
        import bcrypt  # type: ignore
    except ImportError:
        LOG.warning("bcrypt no está instalado; no se puede verificar el hash existente")
        bcrypt = None  # type: ignore

    if bcrypt is not None:
        for candidate in hashes:
            try:
                if bcrypt.checkpw(b"admin123", candidate.encode("utf-8")):
                    return candidate, "hash existente verificado contra la contraseña admin123"
            except ValueError:
                LOG.debug("Se omite un hash BCrypt con formato inválido: %s", candidate[:12])

    if hashes:
        return hashes[0], "hash copiado de users (contraseña original desconocida)"

    if bcrypt is not None:
        generated = bcrypt.hashpw(b"admin123", bcrypt.gensalt(rounds=12)).decode("ascii")
        return generated, "hash BCrypt generado para admin123 (users estaba vacía)"

    return DEFAULT_ADMIN123_HASH, "hash BCrypt fijo conocido para admin123"


def pick_cabys_prefix(cur: Any, requested: str | None) -> tuple[str, set[str]]:
    if requested is not None:
        cur.execute("select codigo from cabys where left(codigo, 2) = %s", (requested,))
        existing = {str(row[0]) for row in cur.fetchall()}
        if existing:
            LOG.warning("El prefijo CABYS %s ya tiene %d códigos; se omitirán colisiones", requested, len(existing))
        return requested, existing

    for prefix in ("79", "78", "77", "76", "75", "74", "80", "81"):
        cur.execute("select count(*) from cabys where left(codigo, 2) = %s", (prefix,))
        if int(cur.fetchone()[0]) == 0:
            return prefix, set()

    # Fallback: primer prefijo de dos dígitos sin códigos.
    cur.execute(
        """
        select p
          from (select lpad(g::text, 2, '0') as p from generate_series(0, 99) g) p
         where not exists (select 1 from cabys c where left(c.codigo, 2) = p.p)
         order by p
         limit 1
        """
    )
    row = cur.fetchone()
    if row:
        return str(row[0]), set()

    # Todos los prefijos tienen códigos: usar el menos poblado y omitir colisiones.
    cur.execute(
        """
        select left(codigo, 2) as p, count(*) n
          from cabys
         group by 1
         order by n, 1
         limit 1
        """
    )
    prefix = str(cur.fetchone()[0])
    cur.execute("select codigo from cabys where left(codigo, 2) = %s", (prefix,))
    existing = {str(row[0]) for row in cur.fetchall()}
    LOG.warning("Todos los prefijos CABYS tienen códigos; se usa %s y se omiten colisiones", prefix)
    return prefix, existing


def load_meta(args: argparse.Namespace) -> Meta:
    try:
        import psycopg2  # type: ignore
    except ImportError as exc:
        raise RuntimeError(
            "psycopg2 es necesario para leer IDs existentes; instale psycopg2-binary"
        ) from exc

    conn = psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.database,
        user=args.user,
        password=args.password,
        connect_timeout=10,
        application_name="mercurius-ops-seed",
    )
    try:
        conn.set_session(readonly=True, autocommit=True)
        meta = Meta()
        with conn.cursor() as cur:
            for table in TABLE_ORDER:
                meta.counts[table] = _table_count(cur, table)

            cur.execute(
                """
                select table_name, column_name, data_type, is_nullable
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = any(%s)
                 order by table_name, ordinal_position
                """,
                (TABLE_ORDER,),
            )
            for table, column, dtype, nullable in cur.fetchall():
                meta.columns.setdefault(str(table), []).append(
                    (str(column), str(dtype), str(nullable))
                )

            cur.execute("select codigo from articulos where status is true order by codigo")
            meta.articulos = [int(row[0]) for row in cur.fetchall()]
            if not meta.articulos:
                cur.execute("select codigo from articulos order by codigo")
                meta.articulos = [int(row[0]) for row in cur.fetchall()]

            cur.execute("select id from departamento where status is true order by id")
            meta.departamentos = [int(row[0]) for row in cur.fetchall()]
            if not meta.departamentos:
                cur.execute("select id from departamento order by id")
                meta.departamentos = [int(row[0]) for row in cur.fetchall()]

            cur.execute("select id, username from users order by id")
            for row in cur.fetchall():
                meta.usuarios.append(int(row[0]))
                if row[1]:
                    meta.usernames.add(str(row[1]).lower())

            cur.execute("select fecha::date from tipocambio")
            meta.tc_dates = {row[0] for row in cur.fetchall()}

            meta.password_hash, meta.password_note = pick_password_hash(cur)
            meta.cabys_prefix, meta.cabys_existing = pick_cabys_prefix(cur, args.cabys_prefix)

            cur.execute(
                """
                select split_part(numeroorden, '-', 2)::int as anio,
                       max(split_part(numeroorden, '-', 3)::int) as ultimo
                  from orden_compra
                 where numeroorden ~ '^OC-[0-9]{4}-[0-9]+$'
                 group by 1
                """
            )
            meta.order_next = {int(row[0]): int(row[1]) for row in cur.fetchall()}

        LOG.info(
            "Metadatos: %d artículos, %d departamentos, %d usuarios, %d fechas TC",
            len(meta.articulos), len(meta.departamentos), len(meta.usuarios), len(meta.tc_dates),
        )
        return meta
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Generadores
# ---------------------------------------------------------------------------


def random_date(rng: random.Random, days: int, today: date) -> date:
    return today - timedelta(days=rng.randrange(0, days + 1))


def generate_orders(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
) -> dict[str, Any]:
    if count <= 0:
        for name, header in (
            ("orden_compra.csv", ORDER_HEADER),
            ("orden_compra_detalle.csv", DETAIL_HEADER),
        ):
            write_csv(out / name, header, [])
        return {"orders": 0, "details": 0, "states": Counter(), "min_date": None, "max_date": None}

    if not meta.articulos:
        raise RuntimeError("No hay articulos; no se pueden generar órdenes FK-safe")
    if not meta.departamentos:
        raise RuntimeError("No hay departamento/proveedor; no se pueden generar órdenes FK-safe")

    today = now.date()
    states: Counter[str] = Counter()
    min_date: datetime | None = None
    max_date: datetime | None = None
    details_total = 0
    next_by_year = dict(meta.order_next)

    order_handle, order_writer = open_csv(out / "orden_compra.csv", ORDER_HEADER)
    detail_handle, detail_writer = open_csv(out / "orden_compra_detalle.csv", DETAIL_HEADER)
    try:
        for key in range(1, count + 1):
            fecha_orden = datetime.combine(
                random_date(rng, 3 * 365, today),
                time(rng.randrange(8, 19), rng.randrange(0, 60), rng.randrange(0, 60)),
            )
            if fecha_orden > now:
                fecha_orden = now - timedelta(minutes=rng.randrange(5, 240))

            age_days = (today - fecha_orden.date()).days
            if age_days > 180:
                weights = ESTADO_WEIGHTS_OLD
            elif age_days > 30:
                weights = ESTADO_WEIGHTS_MID
            else:
                weights = ESTADO_WEIGHTS_NEW
            estado = weighted_choice(rng, ESTADOS, weights)
            states[estado] += 1

            year = fecha_orden.year
            seq = next_by_year.get(year, 0) + 1
            next_by_year[year] = seq
            numero = f"OC-{year}-{seq:05d}"

            line_count = weighted_choice(rng, list(range(1, 11)), [15, 18, 15, 12, 10, 8, 6, 5, 4, 7])
            line_count = min(line_count, len(meta.articulos))
            articulos = rng.sample(meta.articulos, line_count)

            subtotales: list[Decimal] = []
            lines: list[dict[str, Any]] = []
            for line_no, articulo_id in enumerate(articulos, start=1):
                cantidad = Decimal(rng.choice([1, 1, 1, 2, 2, 3, 4, 5, 6, 8, 10, 12, 24]))
                if rng.random() < 0.15:
                    cantidad += Decimal("0.5")
                precio = unit_price(rng.uniform(120.0, 30000.0))
                subtotal = money(cantidad * precio)
                subtotales.append(subtotal)
                lines.append(
                    {
                        "orden_key": key,
                        "line_no": line_no,
                        "cantidad": fmt_dec(cantidad.quantize(UNIT_PRICE)),
                        "notas": rng.choice(DETAIL_NOTES) if rng.random() < 0.10 else "",
                        "preciounitario": fmt_dec(precio),
                        "subtotal": fmt_dec(subtotal),
                        "articulo_id": articulo_id,
                    }
                )

            total = money(sum(subtotales, Decimal("0.00")))
            if estado in ("RECIBIDA", "FACTURADA"):
                total_real = total
            else:
                total_real = money(0)

            fecha_estimada = fecha_orden + timedelta(
                days=rng.randint(2, 20), hours=rng.randint(0, 8), minutes=rng.randint(0, 59)
            )
            fecha_real: datetime | None = None
            if estado in ("RECIBIDA", "FACTURADA"):
                fecha_real = fecha_orden + timedelta(
                    days=rng.randint(1, 25), hours=rng.randint(0, 10), minutes=rng.randint(0, 59)
                )
                if fecha_real > now:
                    fecha_real = now - timedelta(minutes=rng.randint(10, 600))
                if fecha_real < fecha_orden:
                    fecha_real = fecha_orden + timedelta(hours=1)

            notas = rng.choice(ORDER_NOTES) if rng.random() < 0.25 else ""
            activo = rng.random() >= 0.02

            order_writer.writerow(
                {
                    "row_key": key,
                    "estado": estado,
                    "fecha": fmt_dt(fecha_orden),
                    "fechaentregaestimada": fmt_dt(fecha_estimada),
                    "fechaentregareal": fmt_dt(fecha_real),
                    "fechaorden": fmt_dt(fecha_orden),
                    "notas": notas,
                    "numeroorden": numero,
                    "status": fmt_bool(activo),
                    "totalestimado": fmt_dec(total),
                    "totalreal": fmt_dec(total_real),
                    "proveedor_id": rng.choice(meta.departamentos),
                    "usuario_id": rng.choice(meta.usuarios) if meta.usuarios else "",
                }
            )
            for line in lines:
                detail_writer.writerow(line)
            details_total += len(lines)

            min_date = fecha_orden if min_date is None or fecha_orden < min_date else min_date
            max_date = fecha_orden if max_date is None or fecha_orden > max_date else max_date
    finally:
        order_handle.close()
        detail_handle.close()

    return {
        "orders": count,
        "details": details_total,
        "states": states,
        "min_date": min_date,
        "max_date": max_date,
    }


def generate_promotions(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
) -> dict[str, Any]:
    if count <= 0:
        for name, header in (
            ("promocion.csv", PROMO_HEADER),
            ("articulo_carrito_promocion.csv", PROMO_LINK_HEADER),
            ("articulocarrito.csv", CART_HEADER),
        ):
            write_csv(out / name, header, [])
        return {"promos": 0, "links": 0, "carts": 0, "active": 0}

    if not meta.articulos:
        raise RuntimeError("No hay articulos; no se pueden generar enlaces de promoción FK-safe")

    promo_handle, promo_writer = open_csv(out / "promocion.csv", PROMO_HEADER)
    link_handle, link_writer = open_csv(out / "articulo_carrito_promocion.csv", PROMO_LINK_HEADER)
    cart_handle, cart_writer = open_csv(out / "articulocarrito.csv", CART_HEADER)

    active = 0
    links = 0
    carts = 0
    cart_key = 0
    try:
        for key in range(1, count + 1):
            if rng.random() < 0.58:
                start = now - timedelta(days=rng.randint(1, 120), hours=rng.randint(0, 23))
                end = now + timedelta(days=rng.randint(1, 90), hours=rng.randint(0, 23))
                activa = True
            elif rng.random() < 0.55:
                start = now - timedelta(days=rng.randint(60, 1000), hours=rng.randint(0, 23))
                end = now - timedelta(days=rng.randint(1, 59), hours=rng.randint(0, 23))
                activa = False
            else:
                start = now + timedelta(days=rng.randint(1, 120), hours=rng.randint(0, 23))
                end = start + timedelta(days=rng.randint(3, 60))
                activa = False
            if activa:
                active += 1

            descuento = money(rng.choice([5, 8, 10, 12, 15, 20, 25, 30, 35, 40, 50, 60]))
            cantidad = money(rng.randint(10, 2000)) if rng.random() < 0.28 else None
            nombre = (
                f"{rng.choice(PROMO_BASES)} {rng.choice(PROMO_SEASONS)} "
                f"{start.year} #{key:05d}"
            )[:255]

            promo_writer.writerow(
                {
                    "row_key": key,
                    "activa": fmt_bool(activa),
                    "cantidad": fmt_dec(cantidad),
                    "codigo_descuento": weighted_choice(
                        rng,
                        ["01", "02", "03", "04", "05", "06", "07", "08", "09", "10"],
                        [5, 5, 8, 8, 5, 48, 10, 5, 4, 2],
                    ),
                    "descuento": fmt_dec(descuento),
                    "ensamblado_origen": fmt_bool(rng.random() < 0.08),
                    "fechafin": fmt_dt(end),
                    "fechainicio": fmt_dt(start),
                    "nombre": nombre,
                    "usuario_id": rng.choice(meta.usuarios) if meta.usuarios else "",
                }
            )

            for _ in range(rng.choices([1, 2, 3], weights=[50, 35, 15], k=1)[0]):
                cart_key += 1
                article_id = rng.choice(meta.articulos)
                cart_writer.writerow(
                    {
                        "row_key": cart_key,
                        "articulo_id": article_id,
                        "cantidad": fmt_dec(money(rng.randint(1, 5))),
                        "descuento": "0.00",
                        "ispromo": "true",
                        "preciopersonalizado": "",
                    }
                )
                link_writer.writerow({"cart_key": cart_key, "promo_key": key})
                links += 1
                carts += 1
    finally:
        promo_handle.close()
        link_handle.close()
        cart_handle.close()

    return {"promos": count, "links": links, "carts": carts, "active": active}


def _cabys_description(rng: random.Random, index: int) -> str:
    product = rng.choice(CABYS_PRODUCTS)
    packaging = rng.choice(
        ["bolsa de 1 kg", "empaque de 500 g", "caja de 12 unidades",
         "botella de 1 L", "presentación familiar", "unidad", "paquete de 6"]
    )
    return (
        f"{product}, {packaging}. Variante sintética {index:06d} "
        f"para pruebas de carga; no corresponde a un código oficial."
    )[:2000]


def generate_cabys(
    count: int,
    prefix: str,
    existing: set[str],
    rng: random.Random,
    out: Path,
) -> dict[str, Any]:
    generated = 0
    skipped = 0
    seq = 0

    def rows() -> Iterator[dict[str, Any]]:
        nonlocal generated, skipped, seq
        while generated < count:
            seq += 1
            codigo = f"{prefix}{seq:011d}"
            if len(codigo) != 13:
                raise RuntimeError(f"Código CABYS con longitud inválida: {codigo}")
            if codigo in existing:
                skipped += 1
                continue
            generated += 1
            sector = rng.choice(CABYS_SECTORS)
            family = rng.choice(CABYS_FAMILIES)
            item = rng.choice(CABYS_PRODUCTS)
            impuesto = weighted_choice(
                rng,
                ["13", "8", "4", "2", "1", "0"],
                [82, 6, 4, 3, 3, 2],
            )
            yield {
                "codigo": codigo,
                "categorias": f"{sector}; {family}; {item}"[:3000],
                "descripcion": _cabys_description(rng, generated),
                "estado": "Activo" if rng.random() >= 0.04 else "Inactivo",
                "impuesto": impuesto,
                "uri": f"https://www.hacienda.go.cr/docs/cabys/{codigo}"[:255],
            }

    write_csv(out / "cabys.csv", CABYS_HEADER, rows())
    return {"cabys": generated, "skipped": skipped, "prefix": prefix}


def _tc_anchor(year_fraction: float) -> float:
    anchors = [
        (2006.0, 515.0), (2008.0, 545.0), (2009.0, 575.0), (2010.0, 525.0),
        (2012.0, 505.0), (2014.0, 545.0), (2016.0, 550.0), (2018.0, 575.0),
        (2020.0, 600.0), (2021.0, 640.0), (2022.0, 670.0), (2023.0, 545.0),
        (2024.0, 520.0), (2025.0, 470.0), (2026.0, 440.0),
    ]
    if year_fraction <= anchors[0][0]:
        return anchors[0][1]
    if year_fraction >= anchors[-1][0]:
        return anchors[-1][1]
    for (y0, v0), (y1, v1) in zip(anchors, anchors[1:]):
        if y0 <= year_fraction <= y1:
            ratio = (year_fraction - y0) / (y1 - y0)
            return v0 + (v1 - v0) * ratio
    return anchors[-1][1]


def generate_tipocambio(
    meta: Meta,
    rng: random.Random,
    out: Path,
    today: date,
    years: int = 20,
) -> dict[str, Any]:
    start = today - timedelta(days=365 * years)
    generated = 0
    skipped = 0
    noise = 0.0
    min_rate = 10 ** 9
    max_rate = 0.0
    actual_start: date | None = None
    actual_end: date | None = None

    def rows() -> Iterator[dict[str, Any]]:
        nonlocal generated, skipped, noise, min_rate, max_rate, actual_start, actual_end
        current = start
        key = 0
        while current <= today:
            key += 1
            if current in meta.tc_dates:
                # La fila se emite igual: si se carga en una base vacía queda el
                # día completo y, si ya existe, el anti-join del load.sql la omite.
                skipped += 1
            year_fraction = current.year + (current.timetuple().tm_yday - 1) / 365.25
            noise = noise * 0.94 + rng.gauss(0.0, 0.0016)
            base = _tc_anchor(year_fraction) * (1.0 + noise)
            spread = rng.uniform(0.003, 0.018)
            compra = max(1.0, base * (1.0 - spread / 2.0))
            venta = max(compra + 0.01, base * (1.0 + spread / 2.0))
            compra_d = Decimal(str(compra)).quantize(Decimal("0.00001"), rounding=ROUND_HALF_UP)
            venta_d = Decimal(str(venta)).quantize(Decimal("0.00001"), rounding=ROUND_HALF_UP)
            min_rate = min(min_rate, float(compra_d))
            max_rate = max(max_rate, float(venta_d))
            actual_start = current if actual_start is None else min(actual_start, current)
            actual_end = current if actual_end is None else max(actual_end, current)
            generated += 1
            yield {
                "row_key": key,
                "fecha": fmt_dt(datetime.combine(current, time(0, 0, 0))),
                "valorcompra": fmt_dec(compra_d),
                "valorventa": fmt_dec(venta_d),
                "manual": "false",
            }
            current += timedelta(days=1)

    write_csv(out / "tipocambio.csv", TC_HEADER, rows())
    return {
        "tipocambio": generated,
        "new": generated - skipped,
        "skipped": skipped,
        "start": start,
        "end": today,
        "actual_start": actual_start,
        "actual_end": actual_end,
        "min_rate": min_rate if generated else 0.0,
        "max_rate": max_rate if generated else 0.0,
    }


def generate_users(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    seed: int,
) -> dict[str, Any]:
    # El prefijo incluye la semilla para que dos corridas distintas no choquen;
    # si aun así existe, se agrega un sufijo numérico.
    prefix = f"carga{abs(seed) % 1000:03d}"

    rows: list[dict[str, Any]] = []
    usernames = set(meta.usernames)
    for index in range(1, count + 1):
        candidate = f"{prefix}_{index:04d}"
        suffix = 1
        while candidate.lower() in usernames:
            suffix += 1
            candidate = f"{prefix}_{index:04d}_{suffix}"
        usernames.add(candidate.lower())
        group = weighted_choice(rng, [g for g, _ in USER_GROUPS], [w for _, w in USER_GROUPS])
        rows.append(
            {
                "row_key": index,
                "email": f"{candidate}@mercurius.local",
                "groupname": group,
                "password": meta.password_hash,
                "status": fmt_bool(rng.random() >= 0.03),
                "username": candidate,
            }
        )

    write_csv(out / "users.csv", USERS_HEADER, rows)
    return {"users": len(rows), "prefix": prefix, "password_note": meta.password_note}


def generate_reports(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    active = 0
    for index in range(1, count + 1):
        frecuencia = rng.sample(FRECUENCIAS, rng.choices([1, 2], weights=[75, 25], k=1)[0])
        reportes = rng.sample(REPORTES, rng.randint(1, 4))
        correos = [
            f"reportes{index:05d}@mercurius.local",
            f"gerencia{index:05d}@mercurius.local",
        ][: rng.randint(1, 2)]
        status = rng.random() >= 0.10
        lastrun = now - timedelta(days=rng.randint(0, 90), hours=rng.randint(0, 23))
        if status:
            nextruntime = now + timedelta(days=rng.randint(1, 30), hours=rng.randint(0, 23))
            active += 1
        else:
            nextruntime = now - timedelta(days=rng.randint(1, 30), hours=rng.randint(0, 23))

        rows.append(
            {
                "row_key": index,
                "correos": "|".join(correos),
                "frecuencia": "|".join(frecuencia),
                "lastrun": fmt_dt(lastrun),
                "nextruntime": fmt_dt(nextruntime),
                "perfil": f"Reporte de carga {index:05d}",
                "reportes": "|".join(reportes),
                "status": fmt_bool(status),
            }
        )

    write_csv(out / "reporteprogramado.csv", REPORT_HEADER, rows)
    return {"reports": len(rows), "active": active}


def generate_email_templates(
    meta: Meta,
    count: int,
    rng: random.Random,
    out: Path,
    now: datetime,
) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    for index in range(1, count + 1):
        tipo = rng.choice(EMAIL_TIPOS)
        created = now - timedelta(days=rng.randint(0, 730), hours=rng.randint(0, 23))
        modified = min(now, created + timedelta(days=rng.randint(0, 30), hours=rng.randint(0, 23)))
        rows.append(
            {
                "row_key": index,
                "asunto": f"[Carga] {tipo.title()} {index:05d}",
                "cuerpohtml": (
                    f"<p>Hola {{{{usuario}}}}, este es el mensaje sintético "
                    f"{index:05d} para pruebas de carga.</p>"
                )[:255],
                "fechacreacion": fmt_dt(created),
                "fechamodificacion": fmt_dt(modified),
                "nombre": f"Plantilla de carga {index:05d}",
                "status": fmt_bool(rng.random() >= 0.08),
                "tipo": tipo,
                "usuario_id": rng.choice(meta.usuarios) if meta.usuarios else "",
            }
        )

    write_csv(out / "emailtemplate.csv", EMAIL_HEADER, rows)
    return {"templates": len(rows)}


# ---------------------------------------------------------------------------
# Cabeceras CSV
# ---------------------------------------------------------------------------

ORDER_HEADER = [
    "row_key", "estado", "fecha", "fechaentregaestimada", "fechaentregareal",
    "fechaorden", "notas", "numeroorden", "status", "totalestimado",
    "totalreal", "proveedor_id", "usuario_id",
]
DETAIL_HEADER = [
    "orden_key", "line_no", "cantidad", "notas", "preciounitario",
    "subtotal", "articulo_id",
]
PROMO_HEADER = [
    "row_key", "activa", "cantidad", "codigo_descuento", "descuento",
    "ensamblado_origen", "fechafin", "fechainicio", "nombre", "usuario_id",
]
PROMO_LINK_HEADER = ["cart_key", "promo_key"]
CART_HEADER = [
    "row_key", "articulo_id", "cantidad", "descuento", "ispromo",
    "preciopersonalizado",
]
CABYS_HEADER = [
    "codigo", "categorias", "descripcion", "estado", "impuesto", "uri",
]
TC_HEADER = ["row_key", "fecha", "valorcompra", "valorventa", "manual"]
USERS_HEADER = ["row_key", "email", "groupname", "password", "status", "username"]
REPORT_HEADER = [
    "row_key", "correos", "frecuencia", "lastrun", "nextruntime", "perfil",
    "reportes", "status",
]
EMAIL_HEADER = [
    "row_key", "asunto", "cuerpohtml", "fechacreacion", "fechamodificacion",
    "nombre", "status", "tipo", "usuario_id",
]


# ---------------------------------------------------------------------------
# load.sql
# ---------------------------------------------------------------------------

LOAD_SQL = r"""
\set ON_ERROR_STOP on
set client_encoding = 'UTF8';
begin;

-- ===========================================================================
-- Staging. Ninguna tabla destino se toca todavía.
-- ===========================================================================
create temp table stg_users (
  row_key bigint, email text, groupname text, password text,
  status boolean, username text
) on commit drop;
create temp table stg_cabys (
  codigo text, categorias text, descripcion text, estado text,
  impuesto text, uri text
) on commit drop;
create temp table stg_tipocambio (
  row_key bigint, fecha timestamp, valorcompra numeric(18,5),
  valorventa numeric(18,5), manual boolean
) on commit drop;
create temp table stg_ordenes (
  row_key bigint, estado text, fecha timestamp,
  fechaentregaestimada timestamp, fechaentregareal timestamp,
  fechaorden timestamp, notas text, numeroorden text, status boolean,
  totalestimado numeric(12,2), totalreal numeric(12,2),
  proveedor_id integer, usuario_id bigint
) on commit drop;
create temp table stg_orden_detalle (
  orden_key bigint, line_no integer, cantidad numeric(12,4), notas text,
  preciounitario numeric(12,4), subtotal numeric(12,2), articulo_id bigint
) on commit drop;
create temp table stg_promos (
  row_key bigint, activa boolean, cantidad numeric(38,2),
  codigo_descuento varchar(2), descuento numeric(38,2),
  ensamblado_origen boolean, fechafin timestamp, fechainicio timestamp,
  nombre text, usuario_id bigint
) on commit drop;
create temp table stg_cart_lines (
  row_key bigint, articulo_id bigint, cantidad numeric, descuento numeric,
  ispromo boolean, preciopersonalizado numeric
) on commit drop;
create temp table stg_promo_links (
  cart_key bigint, promo_key bigint
) on commit drop;
create temp table stg_reports (
  row_key bigint, correos text, frecuencia text, lastrun timestamp,
  nextruntime timestamp, perfil text, reportes text, status boolean
) on commit drop;
create temp table stg_email_templates (
  row_key bigint, asunto text, cuerpohtml text, fechacreacion timestamp,
  fechamodificacion timestamp, nombre text, status boolean, tipo text,
  usuario_id bigint
) on commit drop;

\copy stg_users from '__USERS_CSV__' csv header
\copy stg_cabys from '__CABYS_CSV__' csv header
\copy stg_tipocambio from '__TC_CSV__' csv header
\copy stg_ordenes from '__ORDERS_CSV__' csv header
\copy stg_orden_detalle from '__DETAILS_CSV__' csv header
\copy stg_promos from '__PROMOS_CSV__' csv header
\copy stg_cart_lines from '__CART_CSV__' csv header
\copy stg_promo_links from '__LINKS_CSV__' csv header
\copy stg_reports from '__REPORTS_CSV__' csv header
\copy stg_email_templates from '__EMAIL_CSV__' csv header

-- ===========================================================================
-- 1) Users (sin FKs). Se insertan antes de las tablas que las referencian.
-- ===========================================================================
insert into users (id, email, groupname, password, status, username)
select (select coalesce(max(id), 0) from users)
       + row_number() over (order by row_key),
       nullif(email, ''), nullif(groupname, ''), nullif(password, ''),
       coalesce(status, true), username
  from stg_users;

-- ===========================================================================
-- 2) CABYS (sin FKs). Se omiten colisiones por si el catálogo cambió.
-- ===========================================================================
insert into cabys (codigo, categorias, descripcion, estado, impuesto, uri)
select s.codigo, s.categorias, s.descripcion, s.estado, s.impuesto, s.uri
  from stg_cabys s
 where not exists (select 1 from cabys c where c.codigo = s.codigo);

-- ===========================================================================
-- 3) Tipo de cambio (una fila por día; se omiten fechas ya existentes).
-- ===========================================================================
insert into tipocambio (id, fecha, valorcompra, valorventa, manual)
select (select coalesce(max(id), 0) from tipocambio)
       + row_number() over (order by s.row_key),
       s.fecha, s.valorcompra, s.valorventa, s.manual
  from stg_tipocambio s
 where not exists (
       select 1 from tipocambio t where t.fecha::date = s.fecha::date
 );

-- ===========================================================================
-- 4) Órdenes de compra + detalle. El mapa traduce row_key -> id real.
-- ===========================================================================
create temp table map_orden on commit drop as
select row_key,
       (select coalesce(max(id), 0) from orden_compra)
       + row_number() over (order by row_key) as id
  from stg_ordenes;

insert into orden_compra (
  id, estado, fecha, fechaentregaestimada, fechaentregareal, fechaorden,
  notas, numeroorden, status, totalestimado, totalreal, proveedor_id,
  usuario_id
)
select m.id, s.estado, s.fecha, s.fechaentregaestimada, s.fechaentregareal,
       s.fechaorden, nullif(s.notas, ''), s.numeroorden, coalesce(s.status, true),
       s.totalestimado, s.totalreal, s.proveedor_id, s.usuario_id
  from stg_ordenes s
  join map_orden m on m.row_key = s.row_key;

insert into orden_compra_detalle (
  id, cantidad, notas, preciounitario, subtotal, articulo_id, orden_compra_id
)
select (select coalesce(max(id), 0) from orden_compra_detalle)
       + row_number() over (order by d.orden_key, d.line_no),
       d.cantidad, nullif(d.notas, ''), d.preciounitario, d.subtotal,
       d.articulo_id, m.id
  from stg_orden_detalle d
  join map_orden m on m.row_key = d.orden_key;

-- ===========================================================================
-- 5) Promociones + líneas de carrito de soporte + tabla de unión.
--    La FK real de articulo_carrito_promocion apunta a articulocarrito.
-- ===========================================================================
create temp table map_promo on commit drop as
select row_key,
       (select coalesce(max(id), 0) from promocion)
       + row_number() over (order by row_key) as id
  from stg_promos;

insert into promocion (
  id, activa, cantidad, codigo_descuento, descuento, ensamblado_origen,
  fechafin, fechainicio, nombre, usuario_id
)
select m.id, coalesce(s.activa, false), s.cantidad, s.codigo_descuento,
       s.descuento, coalesce(s.ensamblado_origen, false),
       s.fechafin, s.fechainicio, s.nombre, s.usuario_id
  from stg_promos s
  join map_promo m on m.row_key = s.row_key;

create temp table map_cart on commit drop as
select row_key,
       (select coalesce(max(codigo), 0) from articulocarrito)
       + row_number() over (order by row_key) as codigo
  from stg_cart_lines;

insert into articulocarrito (
  codigo, cantidad, descuento, ispromo, preciopersonalizado, articulo_id
)
select m.codigo, s.cantidad, s.descuento, coalesce(s.ispromo, true),
       s.preciopersonalizado, s.articulo_id
  from stg_cart_lines s
  join map_cart m on m.row_key = s.row_key;

insert into articulo_carrito_promocion (articulo_carrito_id, promocion_id)
select mc.codigo, mp.id
  from stg_promo_links l
  join map_cart mc on mc.row_key = l.cart_key
  join map_promo mp on mp.row_key = l.promo_key;

-- ===========================================================================
-- 6) Reportes programados (arrays varchar[] desde texto separado por |).
-- ===========================================================================
insert into reporteprogramado (
  id, correos, frecuencia, lastrun, nextruntime, perfil, reportes, status
)
select (select coalesce(max(id), 0) from reporteprogramado)
       + row_number() over (order by row_key),
       string_to_array(nullif(correos, ''), '|'),
       string_to_array(nullif(frecuencia, ''), '|'),
       lastrun, nextruntime, perfil,
       string_to_array(nullif(reportes, ''), '|'),
       coalesce(status, true)
  from stg_reports;

-- ===========================================================================
-- 7) Plantillas de correo (FK usuario_id -> users).
-- ===========================================================================
insert into emailtemplate (
  id, asunto, cuerpohtml, fechacreacion, fechamodificacion, nombre, status,
  tipo, usuario_id
)
select (select coalesce(max(id), 0) from emailtemplate)
       + row_number() over (order by row_key),
       asunto, cuerpohtml, fechacreacion, fechamodificacion, nombre,
       coalesce(status, true), tipo, usuario_id
  from stg_email_templates;

-- ===========================================================================
-- Validaciones de consistencia (solo filas de esta corrida).
-- ===========================================================================
do $$
declare
  v_bad bigint;
  v_orphan bigint;
begin
  select count(*) into v_bad
    from (
      select o.id
        from map_orden m
        join orden_compra o on o.id = m.id
        join orden_compra_detalle d on d.orden_compra_id = o.id
       group by o.id, o.totalestimado
      having sum(d.subtotal) <> o.totalestimado
    ) q;
  if v_bad > 0 then
    raise exception 'Hay % órdenes con totalestimado distinto de la suma de detalles', v_bad;
  end if;

  select count(*) into v_orphan
    from map_orden m
   where not exists (
         select 1 from orden_compra_detalle d where d.orden_compra_id = m.id
   );
  if v_orphan > 0 then
    raise exception 'Hay % órdenes sin detalle', v_orphan;
  end if;
end $$;

-- ===========================================================================
-- Resincronizar secuencias tras inserts con id explícito.
-- ===========================================================================
select setval('users_id_seq',
              coalesce((select max(id) from users), 1),
              exists(select 1 from users));
select setval('tipocambio_id_seq',
              coalesce((select max(id) from tipocambio), 1),
              exists(select 1 from tipocambio));
select setval('orden_compra_id_seq',
              coalesce((select max(id) from orden_compra), 1),
              exists(select 1 from orden_compra));
select setval('orden_compra_detalle_id_seq',
              coalesce((select max(id) from orden_compra_detalle), 1),
              exists(select 1 from orden_compra_detalle));
select setval('promocion_id_seq',
              coalesce((select max(id) from promocion), 1),
              exists(select 1 from promocion));
select setval('articulocarrito_codigo_seq',
              coalesce((select max(codigo) from articulocarrito), 1),
              exists(select 1 from articulocarrito));
select setval('reporteprogramado_id_seq',
              coalesce((select max(id) from reporteprogramado), 1),
              exists(select 1 from reporteprogramado));
select setval('emailtemplate_id_seq',
              coalesce((select max(id) from emailtemplate), 1),
              exists(select 1 from emailtemplate));

commit;

analyze users;
analyze cabys;
analyze tipocambio;
analyze orden_compra;
analyze orden_compra_detalle;
analyze promocion;
analyze articulocarrito;
analyze articulo_carrito_promocion;
analyze reporteprogramado;
analyze emailtemplate;

select 'orden_compra' as tabla, count(*) as filas from orden_compra
union all select 'orden_compra_detalle', count(*) from orden_compra_detalle
union all select 'promocion', count(*) from promocion
union all select 'articulo_carrito_promocion', count(*) from articulo_carrito_promocion
union all select 'cabys', count(*) from cabys
union all select 'tipocambio', count(*) from tipocambio
union all select 'users', count(*) from users
union all select 'reporteprogramado', count(*) from reporteprogramado
union all select 'emailtemplate', count(*) from emailtemplate
order by 1;
"""


def build_load_sql(out: Path) -> Path:
    sql = LOAD_SQL
    replacements = {
        "__USERS_CSV__": out / "users.csv",
        "__CABYS_CSV__": out / "cabys.csv",
        "__TC_CSV__": out / "tipocambio.csv",
        "__ORDERS_CSV__": out / "orden_compra.csv",
        "__DETAILS_CSV__": out / "orden_compra_detalle.csv",
        "__PROMOS_CSV__": out / "promocion.csv",
        "__CART_CSV__": out / "articulocarrito.csv",
        "__LINKS_CSV__": out / "articulo_carrito_promocion.csv",
        "__REPORTS_CSV__": out / "reporteprogramado.csv",
        "__EMAIL_CSV__": out / "emailtemplate.csv",
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
        "users": stats["users"]["users"],
        "cabys": stats["cabys"]["cabys"],
        "tipocambio": stats["tipocambio"]["new"],
        "orden_compra": stats["orders"]["orders"],
        "orden_compra_detalle": stats["orders"]["details"],
        "promocion": stats["promos"]["promos"],
        "articulocarrito": stats["promos"]["carts"],
        "articulo_carrito_promocion": stats["promos"]["links"],
        "reporteprogramado": stats["reports"]["reports"],
        "emailtemplate": stats["emails"]["templates"],
    }

    lines: list[str] = []
    lines.append("# Reporte de datos operativos de carga — Mercurius")
    lines.append("")
    lines.append(f"- Fecha de generación: {datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"- Semilla: {args.seed}")
    lines.append(f"- Destino: `{args.user}@{args.host}:{args.port}/{args.database}` (solo se leyó metadata)")
    lines.append(f"- Directorio: `{out}`")
    lines.append(f"- Script de carga: `{load_path}`")
    lines.append("")
    lines.append("## Conteos")
    lines.append("")
    lines.append("| Tabla | Existentes | Generadas | Total esperado |")
    lines.append("|---|---:|---:|---:|")
    for table in TABLE_ORDER:
        existing = meta.counts.get(table, 0)
        new = generated.get(table, 0)
        lines.append(f"| `{table}` | {existing:,} | {new:,} | {existing + new:,} |")
    lines.append("")
    lines.append("### Distribución de estados de órdenes")
    lines.append("")
    states = stats["orders"]["states"]
    for estado in ESTADOS:
        lines.append(f"- `{estado}`: {states.get(estado, 0):,}")
    if stats["orders"]["min_date"] and stats["orders"]["max_date"]:
        lines.append(
            f"- Rango de fechas: {stats['orders']['min_date'].date()} a "
            f"{stats['orders']['max_date'].date()} (3 años)"
        )
    lines.append("")
    lines.append("### Otros detalles")
    lines.append("")
    lines.append(f"- Promociones activas: {stats['promos']['active']:,} de {stats['promos']['promos']:,}")
    lines.append(f"- Enlaces promoción-carrito: {stats['promos']['links']:,}")
    lines.append(f"- Prefijo CABYS sintético: `{stats['cabys']['prefix']}` "
                 f"(colisiones omitidas: {stats['cabys']['skipped']:,})")
    tc_start = stats["tipocambio"]["actual_start"] or stats["tipocambio"]["start"]
    tc_end = stats["tipocambio"]["actual_end"] or stats["tipocambio"]["end"]
    lines.append(
        f"- Tipo de cambio: {tc_start} a {tc_end}, "
        f"{stats['tipocambio']['tipocambio']:,} días en CSV; "
        f"{stats['tipocambio']['skipped']:,} fechas ya existen y se omitirán al cargar "
        f"({stats['tipocambio']['new']:,} filas nuevas esperadas)"
    )
    if stats["tipocambio"]["min_rate"]:
        lines.append(
            f"- Rango USD/CRC generado: {stats['tipocambio']['min_rate']:.2f} – "
            f"{stats['tipocambio']['max_rate']:.2f}"
        )
    lines.append(f"- Usuarios nuevos: prefijo `{stats['users']['prefix']}`")
    lines.append(f"- Hash de contraseña: {stats['users']['password_note']}")
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
    lines.append("## Estrategia de FK e IDs")
    lines.append("")
    lines.append("- `users` se inserta primero; el resto de referencias usa IDs existentes o mapas temporales.")
    lines.append("- `orden_compra.id` y `orden_compra_detalle.id` se calculan en SQL con `max(id) + row_number()`.")
    lines.append("- El detalle referencia la orden mediante `map_orden(row_key, id)`; nunca se hardcodea el id final.")
    lines.append("- `promocion.id` se calcula con `max(id) + row_number()` y se expone como `map_promo`.")
    lines.append("- `articulo_carrito_promocion` referencia `articulocarrito(codigo)`, no `articulos`.")
    lines.append("  Por eso se generan líneas de carrito sintéticas en `articulocarrito.csv` y se mapean con `map_cart`.")
    lines.append("- `emailtemplate.usuario_id` y `orden_compra.usuario_id` usan únicamente usuarios ya existentes.")
    lines.append("- Las secuencias de identidad se resincronizan con `setval(...)` al final del `load.sql`.")
    lines.append("")
    lines.append("## Riesgos y supuestos")
    lines.append("")
    lines.append("- Los IDs se calculan contra el `max(id)` vigente al momento de ejecutar `load.sql`. No cargue en paralelo con otro proceso que inserte en estas tablas.")
    lines.append("- `orden_compra.numeroorden` es único. Se evitan colisiones con las órdenes existentes al generar, pero una carga concurrente o diferida puede chocar.")
    lines.append("- Los códigos CABYS usan un prefijo libre; si el catálogo oficial se actualiza con ese prefijo, el `load.sql` omite colisiones silenciosamente (no falla).")
    lines.append("- `tipocambio` no tiene índice único por fecha; el `load.sql` omite fechas ya presentes con `NOT EXISTS`, pero no puede evitar duplicados creados por otro proceso concurrente.")
    lines.append("- `articulo_carrito_promocion` no tiene PK ni unique; los pares generados son únicos por construcción.")
    lines.append("- Los precios de detalle son sintéticos y no se derivan de `articuloprecio`; los subtotales y totales sí son consistentes entre sí.")
    lines.append("- El hash BCrypt se reutiliza para todos los usuarios nuevos; cámbielo si estos usuarios se usan fuera de un entorno local.")
    lines.append("- `--load` ejecuta `psql`; no se ejecutó durante esta generación.")
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
        f"python scripts/seed/ops.py --out \"{out}\" --host {args.host} "
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
        description="Genera CSV y load.sql de datos operativos para pruebas de carga de Mercurius.",
    )
    parser.add_argument("--count-orders", type=int, default=100000)
    parser.add_argument("--count-promos", type=int, default=20000)
    parser.add_argument("--count-cabys", type=int, default=100000)
    parser.add_argument("--count-users", type=int, default=200)
    parser.add_argument("--count-reports", type=int, default=5000)
    parser.add_argument("--count-email-templates", type=int, default=1000)
    parser.add_argument("--out", type=Path, default=Path(tempfile.gettempdir()) / "mercurius-ops")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--database", default="mercurius")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5433)
    parser.add_argument("--user", default="mercurius")
    parser.add_argument("--password", default=os.environ.get("PGPASSWORD", ""))
    parser.add_argument("--cabys-prefix", default=None, help="prefijo de 2 dígitos para los códigos CABYS")
    parser.add_argument("--psql", default="psql", help="ejecutable psql para --load")
    parser.add_argument("--load", action="store_true", help="ejecutar load.sql con psql (NO es el comportamiento por defecto)")
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
        "count_orders", "count_promos", "count_cabys", "count_users",
        "count_reports", "count_email_templates",
    ):
        if getattr(args, name) < 0:
            parser.error(f"--{name.replace('_', '-')} no puede ser negativo")
    if args.cabys_prefix is not None and (
        len(args.cabys_prefix) != 2 or not args.cabys_prefix.isdigit()
    ):
        parser.error("--cabys-prefix debe tener exactamente 2 dígitos")


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
        meta = load_meta(args)
    except Exception:
        LOG.exception("No se pudieron leer los metadatos de la base; no se generó nada")
        return 1

    if args.count_orders > 0 and not meta.departamentos:
        LOG.error("No hay filas en departamento; no se pueden generar órdenes FK-safe")
        return 1
    if (args.count_orders > 0 or args.count_promos > 0) and not meta.articulos:
        LOG.error("No hay filas en articulos; no se pueden generar órdenes/promociones FK-safe")
        return 1

    rng = random.Random(args.seed)
    now = datetime.now().replace(microsecond=0)
    today = now.date()

    try:
        stats: dict[str, Any] = {}
        stats["users"] = generate_users(meta, args.count_users, rng, args.out, args.seed)
        stats["cabys"] = generate_cabys(
            args.count_cabys, meta.cabys_prefix, meta.cabys_existing, rng, args.out
        )
        stats["tipocambio"] = generate_tipocambio(meta, rng, args.out, today)
        stats["orders"] = generate_orders(meta, args.count_orders, rng, args.out, now)
        stats["promos"] = generate_promotions(meta, args.count_promos, rng, args.out, now)
        stats["reports"] = generate_reports(meta, args.count_reports, rng, args.out, now)
        stats["emails"] = generate_email_templates(meta, args.count_email_templates, rng, args.out, now)
    except Exception:
        LOG.exception("Falló la generación de datos")
        return 1

    load_path = build_load_sql(args.out)
    report_path = write_report(args.out, args, meta, stats, load_path)

    total = sum(
        stats[k][v]
        for k, v in (
            ("users", "users"), ("cabys", "cabys"), ("tipocambio", "tipocambio"),
            ("orders", "orders"), ("orders", "details"), ("promos", "promos"),
            ("promos", "carts"), ("promos", "links"), ("reports", "reports"),
            ("emails", "templates"),
        )
    )
    LOG.info("Generadas %s filas en %s", f"{total:,}", args.out)
    LOG.info("Script de carga: %s", load_path)
    LOG.info("Reporte: %s", report_path)

    if args.load:
        env = dict(os.environ, PGPASSWORD=args.password)
        cmd = [
            args.psql, "-h", args.host, "-p", str(args.port), "-U", args.user,
            "-d", args.database, "-v", "ON_ERROR_STOP=1", "-f", str(load_path),
        ]
        LOG.info("Ejecutando: %s", " ".join(cmd))
        try:
            return subprocess.call(cmd, env=env)
        except OSError:
            LOG.exception("No se pudo ejecutar psql (%s)", args.psql)
            return 1

    LOG.info("No se ejecutó la carga (use --load para hacerlo)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
