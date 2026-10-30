# Load-test dataset (local 8091)

Generated 2026-10-28 against the local `mercurius` database (PostgreSQL 5433).
No production data was touched; every generator refuses non-local hosts unless
`--force` and only accepts `mercurius` / `mercurius_test`.

## Why synthetic first

Real barcode sources were evaluated:

| Source | Volume | Licence | Notes |
|---|---|---|---|
| Open Food Facts | 4M+ products | ODbL | JSONL ~8 GB / Parquet 7.95 GB, nightly |
| USDA FoodData Central (Branded) | ~1M foods | public domain | CSV/JSON, `gtin_upc` |
| Nudger GTIN (Kaggle) | tens of millions | CC BY 4.0 | 16.65 GB, account required |
| OpenMRP (India) | community | ODbL | GitHub/API |
| Datakick | 6.5k | CC0 | too small |

`scripts/seed-products.py --source openfoodfacts` is available for real data;
the default synthetic mode was used because it needs no 8–16 GB download and
can be sized to any volume.

## Generators (run in this order)

1. `scripts/seed-products.py` — articles, prices, stock.
2. `scripts/seed/clients_loyalty.py` — clients, activity, points.
3. `scripts/seed/inventory_movements.py` — inventory, lotes, cash sessions.
4. `scripts/seed/invoices.py` — emitted/received invoices and full graph.
5. `scripts/seed/ops.py` — orders, promotions, CAByS, exchange rates, users,
   scheduled reports.
6. `scripts/seed/minor_tables.py` — credit notes, stock alerts, reorder,
   offline queue, department metrics, forecast precision.

Each writes CSV + `load.sql` (staging + `COPY` + `INSERT ... SELECT`), uses
`max(id)+row_number()` for PKs, validates FKs, resynchronizes sequences and
runs `ANALYZE`.

## Volumes loaded

| Table | Rows |
|---|---:|
| articulos | 252,008 |
| articuloprecio | 252,008 |
| articulostock | 252,000 |
| clients | 105,000 |
| clienteactividad | 300,000 |
| puntos_transacciones | 315,000 |
| inventario | 1,000,008 |
| lotes | 50,000 |
| cierrecaja | 20,000 |
| comprobantesemitidos | 211,923 |
| comprobantesrecibidos | 50,259 |
| encabezado | 262,182 |
| linea_detalle | 1,149,044 |
| impuesto | 1,150,547 |
| orden_compra | 100,000 |
| orden_compra_detalle | 431,130 |
| promocion | 20,000 |
| cabys | 120,505 |
| tipocambio | 7,307 |
| users | 202 |
| notacredito | 50,000 |
| stock_alerts | 200,000 |
| reorder_suggestions | 200,000 |
| envio_fuera_linea | 100,000 |
| departamentometrico | 200,000 |
| precision_pronostico | 200,000 |
| profit_margin_history | 699,167 |
| profit_margin_snapshots | 60,280 |

Database size: **1.54 GB**.

## Performance results

Fixed after the load exposed them:

| Page | Before | After |
|---|---:|---:|
| Artículos (252k) | >25 s | 1.38 s |
| Artículos API | >25 s | 0.23 s |
| Estacionalidad (1M movs) | >25 s | 0.95 s |
| Tributación (212k) | >25 s | 0.85 s |
| Resumen de inventario | >20 s | 0.82 s |
| Facturas Recibidas (50k) | >25 s | 0.60 s |
| Alertas de stock (200k) | 15.7 s | 0.20 s |
| Pronósticos de inventario (252k) | >30 s | 0.3 s (catálogo cacheado 60 s) |

Already fast: dashboard 0.56 s, facturas emitidas 0.37 s, órdenes 0.33 s,
precios 0.22 s, merma 0.46 s, rendimiento 1.43 s, correos 0.14 s, CAByS 0.04 s.

## Known remaining hotspots

- `pronosticos` (~4 s en la primera carga, ~0,3-0,7 s al navegar): la página
  lee el catálogo con una sola consulta agregada
  (`StockForecastService.estadisticasCatalogo`) y pagina en memoria la lista
  compacta. Esa lectura se guarda 60 s
  (`mercurius.pronostico.catalogo-ttl-ms`) y la comparten sección, orden,
  página, filtros y exportación; el primer request de cada minuto sigue
  pagando el escaneo de `linea_detalle` de 90 días (24M de filas, sin índice
  por `detalle_servicio_id`).
- La exportación `dataset=pronosticos` genera las ~148k filas del pronóstico a
  Excel en ~20 s (3.5 MB): es un adjunto completo, no una página.
- All other pages measured in the final sweep are ≤2.5 s.

## Full re-check (51 routes, seeded DB)

Sweep of every page, report and table view with 252k articles / 1M movements /
212k emitted invoices / 50k received. **0 HTTP 500s, 0 template errors.**

Slow at the time of the sweep, then fixed:

| Route | Before | After |
|---|---:|---:|
| `/app/reportes/inventario/resumen` | >30 s | 0.82 s |
| `/app/reportes/inventario/alertas` | 15.7 s | 0.20 s |
| `/api/app/facturas-recibidas/tabla` | >25 s | 0.41 s |
| `/app/tributacion` | >25 s | 0.85 s |

Found in the sweep, now fixed:

| Route | Before | After |
|---|---:|---:|
| `/app/categorias` | 136 MB / 200,043 rows | 245 KB / 0.39 s |
| `/app/devoluciones` | >30 s | 0.16 s |
| `/app/tributacion/consultas` | >30 s | 2.42 s |
| `/app/reportes/recibos/pendientes` | >30 s | 0.55 s |
| `/app/reportes/recibos/vencidos` | >30 s | 0.40 s |
| `/app/reportes/inventario/etiquetas` | >30 s | 0.07 s |
| `/api/app/stock-alert-config/pagina` | 18.9 s | 0.09 s |

Fixed in the sweep's follow-up (pronósticos, served by one aggregate query and
in-memory pagination, with the catalog read cached for 60 s):

| Route | Before | After |
|---|---|---|
| `/app/reportes/inventario/pronosticos` | >30 s | 4.9 s primera carga / 0.3-0.7 s al navegar |
| `…?sort=articulo` (orden por texto) | 17.4 s | 1.4 s en página 3 (ordenar 148k filas) |
| `…?seccion=salud` | — | 0.1 s |
| `…?dias=60&page=5&size=100` | — | 0.5 s |

`/app/reportes/articulos/ventas-fecha-historica` renders 4.7 MB / 9.5k rows in
1.5 s; large but not a timeout.

Expected non-200s (not bugs): `/app/recibos` is a 302 redirect to the received
table; `/app/reportes/recibos` is not a route; `/app/compras/ordenes` and
`/app/facturas-recibidas` are API-only paths with no page.
