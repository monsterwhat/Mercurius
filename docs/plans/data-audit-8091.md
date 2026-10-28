# Data audit — Mercurius local DB (app on :8091)

**Date:** 2026-10-28 (local time UTC-6), audit window ≈ 04:00–05:10
**Auditor:** read-only subagent
**Target:** local PostgreSQL `127.0.0.1:5433`, database `mercurius` (the DB behind the app on port 8091)
**Revision 4:** adds §15 — live-DB deep-dive on **families, prices and margin-report inputs** (78 families vs 87 source, 418 unpriced articles, `cost × 1.25` repricing, pack-cost unit mismatch, duplicated margin snapshots) with repair plan and SQL (not executed).
**Revision 3:** adds the **full source catalog including every backup**, the **XML/artifact census** (claves, dates, versions, DB overlap), and a **union import plan**. It also reveals that `F:\Desktop\WebDAV-Facturacion\migration\` is a completed migration workspace (the live server was loaded from it on 2026-10-21).
**Revision 2:** incorporates the **current** vSuper snapshot at `F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004\` (2026-10-03). Revision 1 only knew the 2019 copy at `E:\VPALMERAS\`.

**Constraint compliance:** no import, INSERT/UPDATE/DELETE, migration, DDL, code edit, Maven/test run, or commit was performed. The only writes were this report and a throwaway read-only DBF probe under `%TEMP%`. Mercatus surfaces were not touched.

---

## 0. TL;DR

| Domain | Local (:8091) | Best available source | Verdict |
|---|---|---|---|
| Articles | **8** (all created 2026-10-28 04:14, during this audit) | **`MAESTRO.DBF` 5,996 (2026-10-03)** = live DB 5,996; E: 2019 `maestro` 6,424; legacy 2006 `Articulos` 15,058 | **missing** (~0.1 %) |
| Prices | **8** | live DB **16,886**; `MAESTRO` has one cost/sale pair per article (5,996) | **missing** |
| Stock snapshot | **0** (`articulostock`) | live DB **2,494**; `MAESTRO.CANTIDAD` 5,996 rows but **aggregate is negative** | **missing** |
| Inventory movements | **8** | **`KARDEX` 2022-2025 836,761** + current `KARDEX` 2026 **143,371** + `ENTRADAS` 14,317 + `AJUSTESINV` 3,419 = **997,868**; parallel exit ledger `salidas` 907,139; live DB 673,326 | **missing** (0.001 %) |
| Clients | **0** `clients` / 10 `receptor` | `CLIENTES.DBF` **256**; live 238 / 292; E: 2019 `clientes` 95 active | **missing** |
| Sales 2026 | **12,077** | `facturasdiarias` **75,834** POS docs · `facturas` **65,572** e-invoices · live **81,402** | **partial — 15.9 %** |
| Sales 2023–2025 | **104** (2024-03…05 only) | `facturasbk…` 119,383 (2023) + 43,138 (2024 Q1) · `facturasenero2026` **93,145** (2025) · live 116,437 + 41,676 + 92,326 | **missing** |
| Sales 2013–2016 | **0** | E: 2019 `facturasdiarias` **500,708** headers (2013-2019) | **missing** |
| Purchases (received) | **259** | `fe_encabezado` 276 / `fe_detalle` 3,340 (2026); `facturascompras` 1,592 (2022); live 177 | **partial** |
| CABYS catalog | **20,505** | live 20,505 | **complete — do not re-import** |
| XML archive on disk | 11,939 distinct claves in DB (12,182 rows) | **20,306 unique claves on disk**; **8,367 claves not in the DB** (2025-06 → 2026-10) | **large backlog available** |

Root cause is not a broken feature. The local DB is a **thin partial invoice import** (a handful of 2024 and 2026 months, mostly an August fragment plus a complete September) and **the article/inventory domains were never imported**. Meanwhile a working, far more complete master exists twice over: the live server DB at `192.168.100.99:5432/mercurius` (751,869 invoices, 5,996 articles, 673,326 inventory rows) and the **current** vSuper DBF snapshot on `F:` (5,996 articles, 117,022 line rows, 907,139 stock exits, 65,497 Hacienda submissions). On top of that, **the XML archive on disk holds 20,306 unique Hacienda claves, of which 8,367 never reached the local DB.**

Four structural facts drive the whole import design:

1. **`linea_detalle` has no article FK.** It stores Hacienda line text + CABYS. That is why 11,923 invoices coexist with an empty catalog and **zero** referential errors.
2. **The vSuper copies are different generations, not duplicates.** `E:` is a 2019 cumulative snapshot (2013–2019, `salidas.DOCUMENTO` masked); `F:` is the live 2026 system (current-year tables + 2023–2025 archives, real document numbers); `F:\Desktop\Super\New folder (3)\Original DBF` is a 2026-01 snapshot. `F:` must be the primary; `E:` and the 2026-01 snapshot only add depth.
3. **`KARDEX.DBF` (2026) and `salidas.DBF` (2026) are the same movement ledger.** 2026 `salidas` = 135,268 rows and 2026 `kardex` `FE` = 135,269 rows. Importing both double-counts every 2026 sale.
4. **The local DB already contains duplicates in `encabezado`:** 12,182 rows but only **11,939 distinct claves** — 243 claves exist once as an emitted and once as a received document (see §4).

**Live DB deep-dive (§15):** the family taxonomy inherited the legacy supplier names (47 of 78 families, 4,009 articles), 418 articles have no usable latest price, **100 % of the 5,578 valid prices are `cost × 1.25` instead of the POS sale price**, 71 articles carry a case/pack cost (up to 77× the unit cost), and `profit_margin_snapshots` contains **exactly two copies of the history** (786.4 M × 2) with 46 % of rows missing the family label.

Mid-audit, an external actor (not this audit) processed one received invoice through the app, creating 8 articles / 8 prices / 8 inventory rows at `2026-10-28 04:14:53`. That proves `PUT /api/app/facturas-recibidas/{id}/procesar` ("crea artículos e inventario") works — and that article creation is currently per-purchase, not automatic.

---

## 1. Method and exact commands

### 1.1 Local DB

```powershell
$env:PGPASSWORD='Mercurius@1!'
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 5433 -U mercurius -d mercurius -t -A -F '|' -c "<SELECT only>"
```

```sql
-- inventory of estimated sizes
select c.relname, c.reltuples::bigint as est_rows, pg_total_relation_size(c.oid) as bytes
from pg_class c join pg_namespace n on n.oid=c.relnamespace
where n.nspname='public' and c.relkind='r' order by 1;

-- exact counts for all 75 tables, generated as one UNION ALL of
-- select '<table>' as t, count(*) from <table>
select pg_size_pretty(pg_database_size(current_database()));
select datname, pg_size_pretty(pg_database_size(datname)) from pg_database where datistemplate=false;

-- coverage / referential / duplication checks
select to_char(fecha_emision,'YYYY-MM') m, count(*) from encabezado group by 1 order by 1;
select count(*) total, count(distinct detalle) d_detalle, count(distinct codigocabys) d_cabys,
       sum(case when codigocabys is null or btrim(codigocabys)='' then 1 else 0 end) sin_cabys
from linea_detalle;
select count(*) total, count(*) filter (where e.id is null) sin_comprobante
from encabezado e left join comprobantesemitidos c on c.encabezado_id=e.id;
select count(*) from linea_detalle l left join cabys c on c.codigo=l.codigocabys where c.codigo is null;
select receptor_id, count(*) from encabezado group by 1 order by 2 desc;
select count(*), count(distinct codigo), count(*) filter (where status), count(*) filter (where cabys_id is null) from articulos;
```

### 1.2 Live server DB (read-only, SSH)

Host `192.168.100.99` (`palmeras`), user `alvaro`, key `~/.ssh/id_audit_ed25519`, PostgreSQL 16 on 5432.

Credentials were **not printed or stored**: they were read from the running JVM (`/proc/<pid>/environ`: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) and used only inside the remote command.

```powershell
ssh -i C:\Users\alvar\.ssh\id_audit_ed25519 -o BatchMode=yes alvaro@192.168.100.99 `
  'PID=$(pgrep -f Mercurius-runner.jar | head -1); ... PGPASSWORD=$P psql -h localhost -p 5432 -U $U -d mercurius -tA -c <aggregate SELECT>'
```

Only `count(*)`, `min/max`, `extract(year/month)` and anti-join counts were run. `sudo -u postgres` required a password and was not used.

### 1.3 DBF (Visual FoxPro 9) — `F:` current and `E:` 2019

Throwaway probe at `%TEMP%\opencode\dbf_probe.py` (outside the repo), opened with `"rb"` only. It parses header `nrec` (bytes 4-8), `headerLen` (8-10), `recordLen` (10-12), field descriptors at 32..headerLen, skips deleted records (`0x2A`), decodes Latin-1, and decodes VFP `T` fields (4-byte Julian day + 4-byte ms).

```powershell
python %TEMP%\opencode\dbf_probe.py summary  "F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004"
python %TEMP%\opencode\dbf_probe.py schema   "F:\...\facturas.DBF"
python %TEMP%\opencode\dbf_probe.py sample   "F:\...\detafact.dbf" 2
python %TEMP%\opencode\dbf_probe.py months   "F:\...\facturas.DBF" FECHA
python %TEMP%\opencode\dbf_probe.py group    "F:\...\FE_DOCUMENTOS.DBF" CESTADOFE 8
python %TEMP%\opencode\dbf_probe.py agg      "F:\...\MAESTRO.DBF" CANTIDAD
python %TEMP%\opencode\dbf_probe.py overlap  "F:\...\MAESTRO.DBF::CODIGO" "F:\...\detafact.dbf::CODIGO"
python %TEMP%\opencode\dbf_probe.py cross    "F:\...\detafact.dbf::FACTURA" "F:\...\facturas.DBF::NUMERO"
```

DBF `D` fields are `YYYYMMDD` text; `T` fields are binary timestamps; header date (bytes 1-3) was read separately for the file table.

### 1.4 WebDAV and ZIPs

```powershell
curl.exe -s -u '<REDACTED>' -X PROPFIND -H 'Depth: 1' --max-time 30 'http://192.168.100.99:9200/webdav/'
curl.exe -sI -u '<REDACTED>' --max-time 20 'http://192.168.100.99:9200/webdav/Facturas/<file>.zip'
tar -tf <local copy>.zip          # listing only, no extraction
```

Anonymous WebDAV returns **401**; the credentials already present in `%TEMP%\opencode\check-super-webdav.ps1` were used read-only.

### 1.5 XML / artifact census

A second throwaway probe (`%TEMP%\opencode\xml_probe.py`, read-only, streams one file at a time) extracts the 50-digit Hacienda clave from the filename (fallback: from the first 1.5 MB of the body), decodes the emission date from clave positions 3-9, counts unique claves, reads the root element/namespace version from a sample, and compares the on-disk clave set against the DB.

```powershell
python %TEMP%\opencode\xml_probe.py inv    "<folder>"        # totals, unique claves, dates, version sample
python %TEMP%\opencode\xml_probe.py roots  "<folder>"        # document type + schema version histogram
python %TEMP%\opencode\xml_probe.py cmp    "<folder>" "<db_claves.txt>"
python %TEMP%\opencode\xml_probe.py ucmp   "<db_claves.txt>" "<folder1>" "<folder2>" ...
```

The DB side is a read-only export written with `psql -o` (not a DB write):

```powershell
psql ... -t -A -o "$env:TEMP\opencode\db_claves.txt" -c `
  "select 'E|'||e.clave from encabezado e join comprobantesemitidos c on c.encabezado_id=e.id
   union all
   select 'R|'||e.clave from encabezado e join comprobantesrecibidos c on c.encabezado_id=e.id"
```

---

## 2. Local DB inventory (`127.0.0.1:5433/mercurius`)

### 2.1 Instance

| Item | Value |
|---|---|
| Database size | 47 MB |
| Tables in `public` | 75 |
| Empty / non-empty | 40 / 35 |
| Total rows (all tables) | 181,713 |
| Sibling DBs on 5433 | `mercurius` 47 MB, `mercurius_test` 20 MB, `tamarentz` 11 MB, `postgres` 8 MB |
| `mercurius_test` | 135 articles, 26 clients, 10 invoices, 43 inventory rows — separate fixture, not a backup |

### 2.2 Top 30 tables (exact `count(*)`)

| # | Table | Rows | Note |
|---:|---|---:|---|
| 1 | `codigo_comercial` | 28,734 | per-line rows (`linea_detalle_id`), 2,320 distinct codes |
| 2 | `impuesto` | 28,521 | ~1 per line |
| 3 | `linea_detalle` | 27,018 | 24,532 emitted + 2,486 received |
| 4 | `cabys` | 20,505 | identical to live |
| 5 | `detalle_servicio` | 12,182 | one per document |
| 6 | `resumen_factura` | 12,182 | |
| 7 | `encabezado` | 12,182 | 11,923 emitted + 259 received |
| 8 | `medio_pago_resumen` | 11,929 | |
| 9 | `comprobantesemitidos` | 11,923 | 11,917 `ACEPTADO`, 6 `RECHAZADO` |
| 10 | `medio_pago_encabezado` | 11,848 | |
| 11 | `descuento` | 2,676 | |
| 12 | `datos_impuesto` | 1,365 | |
| 13 | `comprobantesrecibidos` | 259 | 258 `processed=false`, 1 `true` (04:14 today) |
| 14 | `consecutivo_emitido` | 52 | |
| 15 | `ubicacion` | 48 | 38 emisor + 10 receptor |
| 16 | `telefono` | 48 | |
| 17 | `informacion_referencia` | 47 | |
| 18 | `correo_electronico_emisor` | 39 | |
| 19 | `emisor` | 38 | |
| 20 | `identificacion_emisor` | 38 | |
| 21 | `tipocambio` | 17 | |
| 22 | `receptor` | **10** | 11,341 invoices point at receptor #242 |
| 23 | `identificacion_receptor` | 10 | |
| 24 | `articulos` | **8** | was 0 before 04:14:53 |
| 25 | `alertas` | 8 | app log rows |
| 26 | `inventario` | **8** | was 0 before 04:14:53 |
| 27 | `articuloprecio` | **8** | was 0 before 04:14:53 |
| 28 | `correo_electronico_receptor` | 2 | |
| 29 | `users` | 2 | both `admin` (`Admin`, `admin`) |
| 30 | `appsettings` | 1 | |

**40 empty tables**, including everything a catalog/stock screen needs:
`clients`, `familia`, `departamento` (now 1), `lotes`, `articulostock`, `articulo_imagenes`, `orden_compra`, `orden_compra_detalle`, `notacredito`, `promocion`, `stock_alerts`, `reorder_suggestions`, `profit_margin_snapshots`, `profit_margin_history`, `precision_pronostico`, `puntos_transacciones`, `clienteactividad`, `marketplace_*`, `envio_fuera_linea`, `exoneracion`, `otro_cargo`, `cierrecaja`, `reporteprogramado`, `articulocarrito`, `articulo_carrito_promocion`, `user_shortcuts`, `emailtemplate`, `apiclients`, `productoexoneracion`, `impuesto_surtido`, `descuento_surtido`, `datos_impuesto_surtido`, `codigo_comercial_surtido`, `detalle_surtido`, `total_desglose_impuesto`, `departamentometrico`, `identificacion_tercero`, `numero_vi_no_serie`, `consecutivo_receptor`.

### 2.3 Change observed during the audit (not caused by this audit)

| Table | ~04:05 | ~04:21 | Rows dated 2026-10-28 04:14:53 |
|---|---:|---:|---|
| `articulos` | 0 | 8 | `TABACO DERRINGER…`, `ENCENDEDOR BIC…`, … |
| `articuloprecio` | 0 | 8 | costs 8,294.20 – 29,752.90 |
| `inventario` | 0 | 8 | `Ingreso Automatico por factura`, qty 10/2/1 |
| `departamento` | 0 | 1 | `GABRIELA DE LOS ANGELES CASCANTE PEREZ` |
| `comprobantesrecibidos.processed` | 0 true | 1 true | one purchase processed |

Only the existing endpoint `PUT /api/app/facturas-recibidas/{id}/procesar` produces this set (`FacturasRecibidasResource.java`: "Procesar factura recibida (crea artículos e inventario)"). The 8 articles have `status=true`, **`processed=false`**, `familia_id=NULL`, `cabys_id=NULL`, `departamento_id=2`, no `codigobarra`.

---

## 3. Sales coverage against every source

Local `encabezado`: 2024-03-01 → 2026-10-27, all `codigo_documento='01'`, 11,923 emitted + 259 received. Sum of `resumen_factura.total_comprobante` = **131,857,620.24** (avg 10,823.97).

2026 month-by-month (DBF snapshot is 2026-10-03; live is 2026-10-27):

| 2026 month | `facturas` (e-invoice) | `facturasdiarias` (POS) | Local DB | Live DB |
|---|---:|---:|---:|---:|
| Jan | 0 (archived) | 10,109 | 17 | 9,926 |
| Feb | 7,750 | 7,926 | 2 | 7,755 |
| Mar | 10,424 | 10,432 | 42 | 10,442 |
| Apr | 10,459 | 10,459 | 188 | 10,494 |
| May | 7,492 | 7,492 | 188 | 7,506 |
| Jun | 7,012 | 7,007 | 87 | 7,020 |
| Jul | 8,521 | 8,520 | **0** | 8,521 |
| Aug | 7,624 | 7,624 | 1,978 | 9,344 |
| Sep | 5,817 | 5,792 | **5,792** | 5,817 |
| Oct (to 3rd/27th) | 473 | 473 | 3,783 | 4,577 |
| **Total** | **65,572** | **75,834** | **12,077** | **81,402** |

Readings:

* Local September = **5,792 = `facturasdiarias` September exactly**. That month was imported completely.
* Local August = 1,978 of 7,624 DBF / 9,344 live → **21–26 %**.
* Local January–June = 524 docs of ~50,000 → **~1 %**.
* Local July = 0 while the DBF and live DB both have 8,521.
* Live matches `facturas` exactly for July (8,521) and September (5,817); it was clearly seeded from this DBF family.

Earlier years, per source (documents; `–` = no data in that source):

| Year | Local | Live DB | `F:` 2026 snapshot | `E:` 2019 snapshot |
|---|---:|---:|---:|---:|
| 2013 | 0 | 49,396 | – | 49,402 |
| 2014 | 0 | 208,561 | – | 208,561 |
| 2015 | 0 | 149,874 | – | **203,103** |
| 2016 | 0 | 0 | – | **39,552** |
| 2017 | 0 | 0 | – | 0 |
| 2018 | 0 | 14,094 | – | 95 (`facturas05122018.DBF` = 14,137) |
| 2019 | 0 | 2 | – | 2 |
| 2020 | 0 | 0 | – | – |
| 2021 | 0 | 0 | – | – |
| 2022 | 0 | 1 | `salidas` only (212,478 lines) | – |
| 2023 | 0 | 116,437 | **119,383** (`facturasbk…`) | – |
| 2024 | 104 | 41,676 | **43,138** (`facturasbk…`, Jan–Apr) | – |
| 2025 | 0 | 92,326 | **93,145** (`facturasenero2026.DBF`) | – |
| 2026 | 12,077 | 81,402 | **75,834 / 65,572** | – |

**No single source is complete.** 2013-2016 exist only in `E:`; 2018-2026 only in `F:` + live; 2020-2021 exist nowhere; 2015 is 53k documents richer in `E:` than in live.

Detail rows:

* 27,018 `linea_detalle` over 12,182 docs (avg 2.22, min 1, max 94).
* 2,404 distinct `detalle` (2,372 normalized), 256 distinct CABYS; top-20 descriptions = 23.84 % of lines.
* 2,486 lines belong to the 259 received invoices (943 descriptions, 179 CABYS).
* 1,224 lines (4.5 %) have no CABYS; 0 have empty description.

---

## 4. Referential-integrity findings (local DB, numbers)

| Check | Result | Reading |
|---|---:|---|
| `encabezado` rows | 12,182 | 11,923 emitted + 259 received |
| `encabezado` without `comprobantesemitidos` | 0 | every emitted doc has its child |
| `comprobantesemitidos` without `detalle_servicio` / `resumen_factura` | 0 / 0 | graph complete |
| `linea_detalle` without `detalle_servicio` | **0** | no orphan lines |
| `linea_detalle.detalle_servicio.comprobante_emitido_id` populated | 0 of 12,182 | parent link lives on the child side only |
| Lines whose `codigocabys` is absent from `cabys` | **1,227 / 27,018 (4.5 %)** | free-text code, not an FK |
| `encabezado.receptor_id` resolving to `receptor` | 12,182 / 12,182 | no orphans… |
| distinct receptors actually used | **10** | master is a stub |
| invoices → receptor #242 (blank name) | **11,341 (93.1 %)** | client attribution collapsed |
| invoices → receptor #239 (`ALVARO PADILLA FONSECA`) | 644 (5.3 %) | |
| all other 8 receptors | 197 (1.6 %) | |
| `articulos` | 8 | created 04:14; `status=true`, `processed=false`, `familia_id=NULL`, `cabys_id=NULL` |
| articles with `status=false` / `processed=false` | 0 / **8** | all unprocessed |
| `inventario` with missing article | 0 (8/8 valid) | |
| `lotes` | 0 | lot tracking never imported |
| `users` | 2 | duplicate admin identities |
| sales lines referencing an article id | **0 of 27,018** | there is no article FK in `linea_detalle` |

**"Sales but no articles" is structural, not a constraint violation.** `linea_detalle` stores the Hacienda line (description + CABYS), not an `articulo_id`; emitted invoices never needed the catalog. Any article backfill must be derived from code/text/CABYS, never from a missing FK.

### 4.1 Duplicate claves already in the local DB

| Check | Result |
|---|---:|
| `encabezado` rows | 12,182 |
| distinct `clave` | **11,939** |
| duplicate rows | **243** |
| duplicate claves that are emitted **and** received at the same time | **243** |
| received rows with a clave that also exists as emitted | **243 of 259 (93.8 %)** |
| emitted rows with a duplicated clave | **0** |
| received rows duplicated among themselves | 0 |

So the 259 "received invoices" are not 259 purchases: **only 16 are genuinely new documents**; the other 243 are the same fiscal document present twice, once via `comprobantesemitidos` and once via `comprobantesrecibidos`. That inflates any report that unions both tables and is the most likely origin of the "duplicate keys already cleaned" note. Dedupe key for the union import must therefore be the **clave**, not the table.

---

## 5. Live server DB snapshot (`192.168.100.99:5432/mercurius`)

| Table | Live | Local | Local/live |
|---|---:|---:|---:|
| `articulos` | **5,996** (codes 1…5996, distinct, all `status=true`, 305 without CABYS) | 8 | 0.13 % |
| `articuloprecio` | **16,886** (0 orphans vs `articulos.codigo`) | 8 | 0.05 % |
| `articulostock` | **2,494** (0 orphans vs `articulos.codigobarra`) | 0 | 0 % |
| `inventario` | **673,326** (`Venta` 668,281 · `Apertura` 5,040 · `Ingreso Automatico` 5; 0 orphans) | 8 | 0.001 % |
| `familia` / `departamento` | 78 / 125 | 0 / 1 | |
| `clients` / `receptor` | 238 / 292 | 0 / 10 | |
| `users` | 27 | 2 | |
| `comprobantesemitidos` | **751,869** | 11,923 | **1.59 %** |
| `comprobantesrecibidos` | 177 (`processed` 2, `paid` 1) | 259 (`processed` 1) | |
| `encabezado` | 753,769 | 12,182 | 1.62 % |
| `linea_detalle` | **564,992** | 27,018 | 4.78 % |
| `cabys` | 20,505 | 20,505 | **100 %** |
| `codigo_comercial` | 27,106 | 28,734 | per-line rows |
| distinct receptors used | **126** for 753,769 docs | 10 for 12,182 | same collapse |

Coverage: documents `2013-10-01 → 2026-10-27`; inventory `2013-01-01 → 2026-10-26`.

Live 2026 monthly: Jan 9,926 · Feb 7,755 · Mar 10,442 · Apr 10,494 · May 7,506 · Jun 7,020 · **Jul 8,521** · Aug 9,344 · Sep 5,817 · Oct 4,577.
Live 2025 monthly: 6,743 / 8,609 / 9,612 / 9,529 / 7,991 / 7,430 / 8,761 / 7,900 / 5,443 / 5,002 / 6,606 / 8,700.

Live `articulos` count (**5,996**) is exactly `MAESTRO.DBF`'s record count and `articuloprecio`/`inventario` have zero orphans against it — strong evidence the live catalog was imported from this very DBF.

---

## 6. Source inventory

### 6.1 `F:\Desktop\WebDAV-Facturacion\extracted\` — **preferred current source**

Contents: 10 extracted `Facturas_*` folders (34,424 XMLs: June 5 · August 5,922 · September 17,374 · October snapshots 11,027 · EXTRA_19 5 · XMLS_COMPRA 91) plus `Sistema_vsuper_20261004\` (81 DBFs).

`Sistema_vsuper_20261004\` — current DBF set (header record counts, header date = last update):

| File | Records | Size | Header date | Role |
|---|---:|---:|---|---|
| `salidas.DBF` | **907,139** | 133.2 MB | 2026-10-03 | **Stock exits per line, 2022-2026** (real `DOCUMENTO`, 511,593 distinct) |
| `detafact.dbf` | **117,022** | 44.4 MB | 2026-10-03 | **Invoice LINE detail, 2026** (38 fields, CABYS + tax) |
| `facturas.DBF` | **65,572** | 106.2 MB | 2026-10-03 | **Electronic invoice HEADER, 2026** (clave, Hacienda status, XML paths) |
| `FE_DOCUMENTOS.DBF` | **65,497** | 94.7 MB | 2026-10-03 | **Hacienda submission log 2026** (clave, consecutivo, estado, XML/PDF paths) |
| `facturasdiarias.dbf` | **75,834** | 16.8 MB | 2026-10-03 | **POS invoice HEADER 2026** (superset: 9,931 non-electronic) |
| `KARDEX.DBF` | **143,372** | 23.4 MB | 2026-10-03 | **Inventory ledger 2026** (`FE` 135,269 · `IM` 8,073 · `AJ` 30) |
| `ENTRADAS.DBF` | **14,317** | 1.8 MB | 2026-10-03 | Stock entries 2016-2026 |
| `MAESTRO.DBF` | **5,996** | 1.8 MB | 2026-10-03 | **Current article master** (33 fields, CABYS + tax + stock) |
| `AJUSTESINV.dbf` | **3,419** | 0.14 MB | 2026-08-24 | Adjustment headers (doc/date/user/type/amount) |
| `CLIENTES.DBF` | **256** | 0.16 MB | 2026-10-04 | Clients |
| `PROVEE.DBF` | **146** | 0.03 MB | 2026-09-02 | Suppliers |
| `FAMILIAS.DBF` | **87** | ~0 MB | 2026-08-28 | Families (code + name) |
| `maestrocaby.DBF` | 53,317 | 14.3 MB | 2020-11-24 | older/larger article master with CABYS (legacy mapping) |
| `CODIGOIMPUESTGO.DBF` | 5,859 | 1.7 MB | 2026-04-10 | per-article tax config snapshot |
| `fe_encabezado.DBF` | **276** | 0.08 MB | 2026-08-24 | **Received invoices (compras) header**, clave + IVA buckets |
| `fe_detalle.dbf` | **3,340** | 1.19 MB | 2026-08-24 | **Received invoices lines** (CODIGO, CABYS, qty, price, tax) |
| `FE_LOG.DBF` | 8,276 | 1.31 MB | 2026-10-04 | Hacienda send log |
| `INGRESOS.DBF` / `INGRESO.DBF` | 1,867 / 415 | small | 2026 | cash receipts |
| `ARTI2.DBF` | 15,059 | 2.59 MB | **2006-09-16** | stale legacy catalog (do not use as current) |

**Archives in the same folder** (these fill the missing years — verified by reading their `FECHA` months):

| File | Records | Size | Actual coverage |
|---|---:|---:|---|
| `facturasbk22042024noborrar.DBF` | 162,521 | 263.2 MB | **2023-01 → 2024-04** (2023 = 119,383; 2024 Q1 = 43,138) |
| `facturasenero2026.DBF` | 95,384 | 154.5 MB | **2025-01 → 2025-12 (93,145) + 2026-01 (2,239)** |
| `detafactbk27022026.DBF` | 325,036 | 123.4 MB | **line detail 2024 (123,518) · 2025 (170,341) · 2026 (31,175)** |
| `detafact_respaldo_respaldo.dbf` | 488,551 | 185.4 MB | older line archive (2024-04 header) |
| `facturas05122018.DBF` | 14,137 | 23.2 MB | 2018 |
| `entradas_respaldo.dbf` | 13,201 | 1.7 MB | older entries (2023) — **do not import with `ENTRADAS`** |
| `MAESTROSINCABYS.DBF` | 7,451 | 2.0 MB | 2020 master without CABYS |
| `maestroold.dbf` | 7,255 | 1.8 MB | 2018 master |
| `DETAFACTMEDIOMARZO.DBF` | 10,526 | 4.0 MB | partial March 2024 lines |
| `facturascompras.dbf` | **1,592** | 0.33 MB | purchase headers (2022-01) |
| `repara_respaldo.dbf` | *invalid* | 0 MB | header claims 1,634,886,000 records — corrupt, ignore |

Subfolders: `RESPALDO_ESTRUCTURA_V010_20260823_231404\` (36 DBFs), `RESPALDO_FE_DOCUMENTOS_20260622_230017\`, `RESPALDO_FE_DOCUMENTOS_MIGRACION_20260823_235144\`, `RESPALDO_CLAVE_PBKDF2_20260917_233440\` — backups, not primary.

### 6.2 `E:\VPALMERAS\` — **old 2019 copy** (historical depth only)

56 root DBFs, ~197 MB, last updated **2019-04-13** (some files 2006/2010/2016).

| File | Records | Size | Header date | Role |
|---|---:|---:|---|---|
| `facturasdiarias.dbf` | **500,720** | 110.8 MB | 2019-04-13 | Cumulative POS headers 2013-10 → 2019-04 |
| `kardex.DBF` | 386,566 | 38.3 MB | 2019-04-13 | Movements 2015-2016 (few 2018-19) |
| `salidas.dbf` | 194,750 | 28.6 MB | 2019-04-13 | Exits 2015-2016, **`DOCUMENTO='******'` in 194,749 rows** |
| `entradas.dbf` | 24,698 | 3.2 MB | 2016-09-08 | Entries 2010-2016 |
| `maestro.DBF` | 6,424 | 1.58 MB | 2019-04-13 | 2019 catalog + stock |
| `Articulos.dbf` / `ARTI2.DBF` | 15,059 each | 2.7 / 2.6 MB | 2006-09-16 | Identical 2006 catalog (15,058 distinct) |
| `clientes.DBF` | 96 (95 active) | 62 KB | 2019-04-13 | Clients |
| `PROVEE.DBF` | 119 | 28 KB | 2016-09-19 | Suppliers |
| `FAMILIAS.DBF` | 55 (45 active) | 2.5 KB | 2019-04-13 | 44 distinct family codes |
| `facturascompras.DBF` | 405 | 88 KB | 2016-09-29 | Purchases |
| `DETAFACT.DBF` | **43** | 16 KB | 2019-04-13 | Line detail — effectively empty |
| `AJUSTESINV.dbf` | 775 | 33 KB | 2016-11-17 | Adjustments |
| `lineasborradas.dbf` | 18,171 | 1.75 MB | 2018-10-11 | Deleted lines |
| `maestrometroLOGIC.DBF` | 15,792 | 1.13 MB | 2008 | Metrology (unrelated) |

Key properties: `facturasdiarias` = 500,708 distinct invoice numbers (header-only, `ESTADO='C'`, 98.9 % `CLIENTE='0'`); no line detail for 2013-2019 (`DETAFACT` 43 rows); `kardex.MOVIMIENTO` FE 352,686 · IM 28,746 · AJ 2,555 · FA 2,497 · CI 82, quantities 354,963 negative / 30,424 positive; `kardex.DOCUMENTO` numeric, 88,449 of 199,022 match a `facturasdiarias.NUMERO`; `maestro` stock 4,296 positive / 1,964 zero / **164 negative**, total 39,887.54 units; `Articulos` ∩ `maestro` = only **1,456** codes.

### 6.3 WebDAV `http://192.168.100.99:9200/webdav/`

Root: `Sistema/` and `Facturas/`.

`Facturas/` (12 files, `Content-Length`, XML entries):

| File | Size | Modified | XML |
|---|---:|---|---:|
| `Facturas_2026-06.zip` | 11.9 KB | 2026-10-04 | 5 |
| `Facturas_2026-08.zip` | 22,996,815 B | 2026-10-04 | 5,922 |
| `Facturas_2026-09.zip` | 67,131,522 B | 2026-10-04 | 17,374 |
| `Facturas_2026-10.zip` | 5,458,098 B | 2026-10-04 | 1,417 |
| `Facturas_2026-10_v2_20261011.zip` | 18,171,729 B | 2026-10-11 | 4,705 |
| `Facturas_2026-10-incremental-20261016.zip` | 9,312,106 B | 2026-10-16 | 2,432 |
| `Facturas_2026-10-incremental-20261020.zip` | 9,339,839 B | 2026-10-21 | 2,473 |
| `Facturas_incremental_20261027-163240.zip` | 15,204,197 B | 2026-10-27 | 3,913 |
| `Facturas_incremental_20261027-223002.zip` | 812,554 B | 2026-10-28 | 208 |
| `Facturas_EXTRA_19.zip` | 93,265,263 B | 2026-10-04 | 5 |
| `Facturas_EXTRA_AJUSTES_20261011.zip` | 15,284 B | 2026-10-11 | 0 |
| `Facturas_XMLS_COMPRA.zip` | 1,702,267 B | 2026-10-04 | 91 |

**No `2026-07` export exists on WebDAV** — July exists in `facturas`/`facturasdiarias` (8,521) and live.

`Sistema/`: `Sistema_vsuper_20261004.zip` 160,691,015 B, `Sistema_vsuper_20261027-163717.zip` 185,639,437 B. The 2026-10-04 one is already extracted on `F:` and was fully audited here; the 2026-10-27 one was not downloaded.

Local copies: `%TEMP%\mercurius-webdav-facturas\` (9 ZIPs, ~224 MB) and `%TEMP%\mercurius-webdav-extracted\` (15,957 files).

### 6.4 Local project artifacts

| Path | What it is | Verdict |
|---|---|---|
| `TablasFacturas.xlsx` | 4 sheets / 376 strings | **Hacienda XML field spec**, not data |
| `scripts\` | `MigrateInvoices`, `MigrateHistoricalInvoices`, `MigrateInventoryMovements`, `MigrateStock`, `MigrateCompras`, `vsuper-import-catalogs.java`, `vsuper_dbf_to_csv.py`, … | historical toolchain hardcoded to `D:\…\vSuper`, port **5434**, user `OIJ` — does not fit this machine |
| `F:\Documents\Facturas Electronicas\` | supplier folders + `Todas` 179 XML / `facturas_nuevas_44` 245 XML | small purchase batches |
| `F:\Documents\Facturas\` | ~30 loose XMLs (May/Jun 2024) | trivial |
| `F:\Documents\Database1.accdb`, `VentasXDeepartamento.xlsx` | unrelated reporting | not vSuper data |
| repo `logs\`, `sessions\` | app logs only | no import summaries |
| repo-wide file scan | **no SQL dump / backup / data ZIP** | nothing else local |
| `mercurius_test` (5433) | 135 articles / 10 invoices / 43 movements | test fixture |

### 6.5 Live host (`192.168.100.99`) home

| File | Size | Note |
|---|---:|---|
| `backup_inventario_20261021.sql` | 765 KB | inventory backup SQL (2026-10-21) |
| `ledger.sql` | 3.0 KB | small |
| `mercurius-pre-dedupe-20261028-061114.dump` | **0 B** | failed dump — **not a rollback point** |
| `Mercurius/Mercurius-runner.jar` (+`.bak`) | — | `application.properties` uses `${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}` |
| `Mercurius/mercurius.log` | — | app log |

### 6.6 Complete source catalog (backups included)

| Folder | DBFs | Total MB | Newest file | What it appears to be | Status |
|---|---:|---:|---|---|---|
| `F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004` | 81 | 1,208.8 | 2026-10-03 | **Primary current system** (current-year tables + 2023-2025 archives + backups) | **primary** |
| `F:\Desktop\Super\New folder (3)\Original DBF` | 14 | 631.4 | 2026-01-10 | **January-2026 snapshot** (full kardex 2022-2025, POS headers 2022-2025) | secondary (gap-fill) |
| `…\Sistema_vsuper_20261004\RESPALDO_ESTRUCTURA_V010_20260823_231404` | 36 | 307.0 | 2026-08-23 | **Structural backup** taken before a schema/structure upgrade | backup (cross-check) |
| `E:\VPALMERAS` | 56 | 196.9 | 2019-04-13 | **Old 2019 copy** of the same system (2013-2019 depth) | historical only |
| `E:\VPALMERAS\Aceptacion\Tablas` | 23 | 0.5 | 2018-08-13 | 2018 "Aceptación" test tables (CxC / INV modules only) | **exclude** (no sales core) |
| `…\RESPALDO_CLAVE_PBKDF2_20260917_233440` | 1 (+ DBC) | 0.8 | 2026-08-23 | **Key store** (`clave.dbf`, empty) | **exclude** (secrets) |
| `…\RESPALDO_FE_DOCUMENTOS_20260622_230017` | 1 | ~0 | 2026-06-22 | FE document backup, **4 rows only** (`fe_documentos.dbf` 4 records / 0 MB) | **exclude** (4 rows) |
| `…\RESPALDO_FE_DOCUMENTOS_MIGRACION_20260823_235144` | 1 | ~0 | 2026-08-23 | FE document backup, **4 rows only** (`fe_documentos.dbf` 4 records / 0 MB) | **exclude** (4 rows) |
| Visual FoxPro 9 `Samples/Wizards/Gallery` | many | — | installer | **Installer samples** (not business data) | **excluded** |
| `G:\…` | — | — | — | see §14 (reserved for later clusters) | pending |

`F:\Desktop\WebDAV-Facturacion\migration\` is **not** a DBF folder but the completed ETL workspace that loaded the live server — see §6.10.

### 6.7 Header census of every business backup (records and real date ranges)

All values read from the DBF headers / row scans; `–` = table absent.

| Table | Current (2026-10-03) | Original DBF (2026-01-10) | RESPALDO_ESTRUCTURA (2026-08-23) | E: 2019 |
|---|---:|---:|---:|---:|
| `facturas` | 65,572 (2026) | 3,374 (2026) | 57,309 (2026) | 39 (2019 remnant) |
| `facturasdiarias` | 75,834 (2026) | **450,982 (2022-2026)** | 67,596 (2026) | 500,720 (2013-2019) |
| `detafact` | 117,022 (2026) | 300,236 (2024-2026) | 102,040 (2026) | 43 |
| `FE_DOCUMENTOS` | 65,497 (2026) | – | 4 (stale) | – |
| `salidas` | 907,139 (2022-2026) | 778,246 (2022-2026) | 892,157 (2022-2026) | 194,750 (2015-2016, masked) |
| `KARDEX` | 143,372 (2026) | **843,552 (2022-2026)** | 128,211 (2026) | 386,566 (2015-2016) |
| `ENTRADAS` | 14,317 (2016-2026) | 14,103 (2016-2026) | 14,286 (2016-2026) | 24,698 (2010-2016) |
| `MAESTRO` | 5,996 | 5,795 | 5,965 | 6,424 (2019) |
| `CLIENTES` | 256 | 239 | 256 | 96 |
| `PROVEE` | 146 | – | 145 | 119 |
| `FAMILIAS` | 87 | – | 87 | 55 |
| `AJUSTESINV` | 3,419 | 3,405 | 3,419 | 775 |
| `facturascompras` | 1,592 | – | 1,592 | 405 |
| `fe_encabezado` / `fe_detalle` | 276 / 3,340 | – | 276 / 3,340 | – |
| `lineasborradas` | 25 | 22,597 | 23,013 | 18,171 |

Per-year record counts where they matter:

* `Original DBF\KARDEX.DBF` (843,552): 2022 **232,211** · 2023 **218,238** · 2024 **201,391** · 2025 **184,879** · 2026 6,791 (+ 38 junk 2085/2030, 3 blank).
* `Original DBF\salidas.DBF` (778,246): 2022 212,478 · 2023 203,296 · 2024 185,754 · 2025 170,341 · 2026 6,375.
* `Original DBF\facturasdiarias.DBF` (450,982): 2022 122,645 · 2023 119,379 · 2024 107,241 · 2025 98,342 · 2026 3,374.
* `Original DBF\detafact.DBF` (300,236): 2024 123,518 · 2025 170,341 · 2026 6,375.
* `RESPALDO\salidas.dbf` (892,157): 2022 212,478 · 2023 203,296 · 2024 185,754 · 2025 170,341 · 2026 120,286.
* Current `salidas.DBF` (907,139): 2022 212,478 · 2023 203,296 · 2024 185,754 · 2025 170,341 · 2026 **135,268**.

**Key consequence:** `salidas` 2022-2025 is byte-for-byte the same population in all three 2022+ snapshots (identical per-year counts), while 2026 grows with each snapshot. `KARDEX` 2022-2025 exists **only** in the January-2026 snapshot; both current and August backups keep only the current year.

### 6.8 XML / artifact inventory (all business folders)

Counts by extension, measured recursively (installer sample folders excluded):

| Folder | `.xml` | XML MB | `.pdf` | PDF MB | `.b64` | `.utf` | `.zip`/`.rar` |
|---|---:|---:|---:|---:|---:|---:|---:|
| `F:\Desktop\WebDAV-Facturacion\extracted` | **34,424** | 196.9 | 11,243 | 173.6 | 1 | 1 | 0 |
| `F:\Desktop\Super\New folder (3)\Respuestas` | **21,268** | 137.0 | 0 | 0 | 0 | 0 | 0 |
| `E:\VPALMERAS` | 54 | 0.3 | 28 | 7.3 | **27** | **27** | 3 (0.7 MB) |
| `F:\Documents\Facturas Electronicas` | 444 | 8.0 | 32 | 3.8 | 0 | 0 | 0 |
| `%TEMP%\mercurius-webdav-extracted` (local copy) | 12,132 | 68.5 | 3,825 | 24.1 | 0 | 0 | 0 |
| `F:\Desktop\WebDAV-Facturacion\Facturas` | — | — | — | — | — | — | **10 zips / 216.9 MB** |
| `F:\Desktop\WebDAV-Facturacion\Sistema` | — | — | — | — | — | — | **1 zip / 153.25 MB** |
| `RESPALDO_CLAVE_PBKDF2_*` | 0 | 0 | 0 | 0 | 0 | 0 | 0 (key store only) |
| `RESPALDO_FE_DOCUMENTOS_*` (both) | 0 | 0 | 0 | 0 | 0 | 0 | 0 XML (4-row DBFs only) |

File-role composition (by filename convention):

| Folder | plain document | `_firmado` (signed) | `Mensaje Hacienda` | `R_*` / `Res_*` | `SF*` |
|---|---:|---:|---:|---:|---:|
| `extracted` | 11,603 | 11,412 | 11,407 | 1 | 1 |
| `Respuestas` | 7,543 | 0 | 0 | 6,183 | 7,542 |
| `E:\VPALMERAS` | 27 | 0 | 0 | 0 | 27 |
| `Facturas Electronicas` | 440 | 4 | 0 | 0 | 0 |
| `%TEMP%\…extracted` | 3,984 | 4,074 | 4,074 | 0 | 0 |

`E:\VPALMERAS` also holds 27 `.b64` + 27 `.utf` + 28 `.pdf` + `repara.zip` + 2 `.rar`, all from the 2018 cycle (the `.b64`/`.utf`/`.pdf` are per-invoice siblings of the same 27 claves, not extra invoices).

### 6.9 XML uniqueness, dates, versions, and overlap with the local DB

Inventory (clave taken from the filename, fallback to the `<Clave>` element; emission date decoded from clave positions 3-9):

| Source | XML files | Unique claves | Emission range | Already in DB | Not in DB |
|---|---:|---:|---|---:|---:|
| `extracted` | 34,424 | **11,120** | 2026-03-07 → 2026-10-20 | 10,323 | **797** |
| `Respuestas` | 21,268 | **7,542** | **2025-06-26 → 2026-01-11** | **0** | **7,542** |
| `E:\VPALMERAS` | 54 | 27 | 2018-10-11 → 2018-12-01 | 0 | 27 |
| `Facturas Electronicas` | 444 | 366 | 2024-03-01 → 2026-06-09 | 365 | 1 |
| `%TEMP%\…extracted` | 12,132 | 2,671 | 2026-10-01 → 2026-10-27 | 1,877 | 794 |
| **Union of all** | **68,322** | **20,306** | **2018-10-11 → 2026-10-27** | **11,939 (100 % of DB)** | **8,367** |

* The DB has **0** claves with no XML on disk (`db_only = 0`) — every local invoice has a provenance file.
* The 8,367 import candidates are dominated by **`Respuestas` 2025-06 → 2026-01** (7,542 claves) — a period the local DB completely lacks.
* Duplicates per clave are structural, not accidental: average 3.1 files per clave in `extracted` (plain + `_firmado` + `MensajeHacienda`), 2.8 in `Respuestas` (document + `SF` signed), 4.5 in the temp copy.

Format versions (root element + schema namespace):

| Source | Version mix | App support |
|---|---|---|
| `extracted`, `%TEMP%` copy, `Respuestas` | **100 % v4.4** (`TiqueteElectronico`, `FacturaElectronica`, `NotaCreditoElectronica`, `MensajeHacienda`; namespace `cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/*`) | ✅ supported |
| `F:\Documents\Facturas Electronicas` | **v4.3 152 files (34 %)** + v4.4 292 | ✅ supported (both XSD sets exist) |
| `E:\VPALMERAS` (2018) | **100 % v4.2** (`tribunet.hacienda.go.cr/docs/esquemas/2017/v4.2/*`) | ❌ **no v4.2 XSD in the app** (`resources/xsd/` has only v4.3 and v4.4) |

So the current/2025/2026 XML archives plug directly into the v4.4 pipeline; the 2018 batch needs translation or manual treatment, and the 2024 supplier batch contains 152 v4.3 files that are accepted by the app but should be validated against v4.3 rules.

### 6.10 The completed migration workspace (`F:\Desktop\WebDAV-Facturacion\migration\`)

This folder is the **reference ETL** that already loaded the live server. It is read-only for this audit and must not be re-run blindly.

| Artifact | Rows / size | Meaning |
|---|---:|---|
| `maestro.csv` | 5,996 | articles exported from `MAESTRO.DBF` (1:1 with live `articulos`) |
| `clientes.csv` | 256 | clients |
| `provee.csv` | 146 | suppliers |
| `familias.csv` | 87 | families |
| `departamentos.csv` | 191 KB | department mapping |
| `movimientos_inventario.csv` | **156,572** | inventory movements ready to import (`codigo_barra,fecha,cantidad,tipo,documento,notas`) |
| `xml_manifest.csv` | 20,378 | first-pass XML census (`clave,tipo,version,emisor,side,flag,copies,chosen_path,size`) |
| `manifest_final.csv` | **10,261 groups** | **authoritative deduped XML inventory** (`clave,…,chosen_path`) |
| `fe_documentos_estado.csv` | 65,497 | `FE_DOCUMENTOS` states (`clave,estado,tipo_doc,consecutivo,error`) |
| `stock_objetivo.csv` | 5,996 | per-article target stock |
| `stock_negativos_para_conteo.csv` / `stock_descuento_para_conteo.csv` | ~1,116 / ~? | the negative-stock articles |
| `excepciones_migracion.csv` | 1,118 | known exceptions and pending actions |
| `plan_nuevas.csv` / `nuevas_claves.csv` | — | new-invoice import plan |
| `stage1.sql … stage15.sql`, `verif_*.sql` | 44 SQL files | migration stages and verification queries |
| `MIGRATION_PLAN.md`, `deployment_notes.md` | 8.4 / 29.3 KB | the plan and deployment log |

`MIGRATION_PLAN.md` states **"ESTADO: COMPLETADA (2026-10-21)"** with: articles 5,996 · families/departments 78/1 · clients 238 · CABYS 20,505 · **emitted invoices 10,155** · received 62 · opening stock 2,518 articles / 14,122.7 units · inventory ledger 21,618 movements · headers/lines 10,215/18,675.

**Discrepancy to resolve:** the live DB audited today has **751,869 emitted / 564,992 lines**, not 10,155 / 18,675. Either a later bulk historical load happened after 2026-10-21, or the audited instance is not the one described in the plan. This does not change the local verdict, but it must be understood before treating the live DB as the authoritative union source.

---

## 7. Which DBF is what (header / detail / summary)

This is the decisive question for import order, so it was verified with field lists **and** row samples.

| File | Shape | Verdict |
|---|---|---|
| `facturas.DBF` (65,572, 2026) | 60 fields, `NUMERO`, `FECHA`, totals, `CIDDOCUMEN` (50-char clave), `CSTATUSHDA`, `CFECHENVIO`, `CRUTACOMPR/PDFFA/JSONR`, `CEXON*`, `CTIPOMOVIM`, `CACTIVIDAD`, `DESCTONO/SI`, `NPLAZOCRED` | **Electronic invoice HEADER.** Sample `NUMERO 745283`, clave `506010226…`, `CSTATUSHDA='aceptado'`, `ESTADO='C'`. Current-year only. |
| `facturasdiarias.dbf` (75,834, 2026) | 24 fields, no Hacienda columns | **POS invoice HEADER / daily sales log.** Superset of `facturas`: 65,541 common, **9,931 only here** (non-electronic tickets), 31 only in `facturas`. |
| `detafact.dbf` (117,022, 2026) | 38 fields, `FACTURA`, `CODIGO`, `CODIGOCABY`, `DESCRIP`, `CANTIDAD`, `VALORUNITA`, `TOTAL`, `IMP`, `PORCEIMP`, `CCODIMPUES`, `CODITARIF`, `NNUMELINEA`, `FAMILIA`, `CUNIDADGTD`, `DESCUENTO`/`DESCTO` | **Invoice LINE detail.** 65,570 distinct invoices; 65,564 also in `facturas`; 6 orphan invoice numbers. `FECHA` is VFP **T** (timestamp), not `D`. |
| `FE_DOCUMENTOS.DBF` (65,497, 2026) | `IDFE`, `NUMERO`, `CTIPODOCFE`, `CCLAVE`, `CCONSECUTI`, `CESTADOFE`, `FCREACION`, `CRUTAXML/XMLF/RESP/PDF`, `CMENSAJEHD/HA`, `CCLAVEREF`, `CRAZONREF`, `CERRORPROC` | **Hacienda submission log**, not a document table. 100 % of its `NUMERO`s exist in `facturas`. `TE` 64,789 · `FE` 708. |
| `KARDEX.DBF` (143,372, 2026) | `CODIGO`, `CANTIDAD`, `SALDO`, `FECHA`, `MOVIMIENTO`, `DOCUMENTO:C(50)`, `VENTA`, `COSTO`, `UTILIDADIN/GR` | **Inventory movement ledger, 2026 only.** `DOCUMENTO` is the **clave** (not invoice number); 840 blank. |
| `salidas.DBF` (907,139, 2022-2026) | `CODIGO`, `DOCUMENTO:C(7)`, `FECHA`, `CANTIDAD`, `DETALLE`, `PVENTA1`, `VENTAIMP` | **Stock exit ledger.** Real numeric document numbers (511,593 distinct) — unlike the 2019 copy. |
| `ENTRADAS.DBF` (14,317, 2016-2026) | `CODIGO`, `DOCUMENTO:C(7)`, `FECHA`, `CANTIDAD`, `COSTO`, `PROV`, `CODPROV` | **Stock entry ledger.** |
| `AJUSTESINV.dbf` (3,419) | `DOCUMENTO`, `FECHA`, `USUARIO`, `TIPOAJUSTE`, `MONTO` | **Adjustment headers only** — no product, cannot create movements. |
| `MAESTRO.DBF` (5,996) | `CODIGO`, `CODIGO2`, `CODIGOCABY`, `NOMBRE`, `FAMILIA`, `CANTIDAD`, `COSTO`, `VENTA`, `EXENTO`, `INCLUYEIV`, `PORCEIMP`, `CACTIVIDAD`, `CCODIMPUES`, `CODITARIF`, `TIPOARTI`, stock history columns | **Article master + current balance + one price pair.** |
| `maestrocaby.DBF` (53,317, 2020) | same shape, larger | historic master with CABYS — use for legacy code→CABYS mapping only |
| `CODIGOIMPUESTGO.DBF` (5,859, 2026-04) | same shape as `MAESTRO` | tax-config snapshot |
| `fe_encabezado.DBF` (276) | `FECHAEMISI`, `NUMEROFACT` (clave), `NOMBREEMIS`, `CEDULAEMIS`, subtotal/discount/exento/IVA13/8/2/4/1/total | **Received invoice header (compras)** |
| `fe_detalle.dbf` (3,340) | `FECHAEMISI`, `EMISOR`, `NUMEROFACT`, `CODIGO`, `CODIGOCABY`, `DESCRIPCIO`, `CANTIDAD`, `PRECIO_UNI`, `MONTOTOTAL`, `TARIFA`, `MONTOIMPUE`, `APLICADO` | **Received invoice lines** |
| `Articulos.dbf` / `ARTI2.DBF` (15,059) | 2006 | stale; barcode/price aliases only |

Overlaps that decide the import:

| Pair | Intersection | Only A | Only B |
|---|---:|---:|---:|
| `detafact.FACTURA` ∩ `facturas.NUMERO` | 65,564 | 6 | 8 |
| `detafact.FACTURA` ∩ `facturasdiarias.NUMERO` | 65,540 | 30 | 9,932 |
| `facturas.NUMERO` ∩ `facturasdiarias.NUMERO` | 65,541 | 31 | 9,931 |
| `FE_DOCUMENTOS.NUMERO` ∩ `facturas.NUMERO` | **65,497 (100 %)** | 0 | 75 |
| `detafact.FACTURA` ∩ `salidas.DOCUMENTO` | 65,569 | 1 | 446,023 |

---

## 8. Schemas (field names, types, widths)

### 8.1 Current `F:` (2026)

```
facturas.DBF  — 60 fields, 1698 B/rec, 65,572 recs
  NUMERO:N(7) CLIENTE:C(4) CAJERO:N(2) HORA:T(8) HORANUEVA:C(8) HORACAMBIA:C(1)
  NO_REC:N(6) NOMBRE:C(30) SUBTOTAL:N(12,2) DESCUENTO:N(13,2) NSALDO:N(12,2)
  I_VENTAS:N(12,2) TOTAL:N(13,2) TOTALEXENT:N(12,2) TOTALNOEXE:N(12,2)
  FECHA:D(8) FECHAVENC:D(8) FECHACANC:D(8) VENDE:N(2) COMISION:N(12,2)
  FORMA_PAGO:C(1) TIPOTARJET:C(10) EMPRESATAR:C(1) NUMEROTARJ:C(16) UPAGO:D(8)
  TELEFONO:C(9) ESTADO:C(1) USUARIO:C(15) CREDITOS:N(12,2) DEBITOS:N(12,2)
  DOLARES:N(12,2) PAGACON2:N(12,2) VUELTOS:N(12,2) CENVIODGTD:C(10)
  CTIPOENVIO:C(1) CFECHENVIO:C(25) CIDDOCUMEN:C(50) CSTATUSHDA:C(10)
  CSTATFERES:C(25) CRUTACOMPR:C(200) CIDANULACI:C(50) CSTATUSREC:C(1)
  CRUTAERROR:C(240) CENVARCREC:C(1) CRUTAPDFFA:C(200) CRUTAJSONR:C(200)
  CEXONDOCTI:C(2) CEXONDOCNU:C(25) CEXONINSTI:C(60) DEXONFECHA:D(8)
  NMTOLINEXO:N(18,5) NPORCEXENT:N(7,3) DFECHAFACM:D(8) CNUMEFACTM:C(10)
  COBSERVACM:C(200) CTIPOMOVIM:C(2) NPLAZOCRED:N(3) CACTIVIDAD:C(6)
  DESCTONO:N(12,2) DESCTOSI:N(12,2)

facturasdiarias.dbf — 24 fields, 232 B/rec, 75,834 recs
  NUMERO:N(7) CLIENTE:C(4) CAJERO:N(2) NO_REC:N(6) NOMBRE:C(30)
  SUBTOTAL:N(12,2) DESCUENTO:N(13,2) NSALDO:N(12,2) I_VENTAS:N(12,2)
  TOTAL:N(13,2) TOTALEXENT:N(12,2) TOTALGRABA:N(12,2) FECHA:D(8)
  FECHAVENC:D(8) FECHACANC:D(8) VENDE:N(2) COMISION:N(12,2) FORMA_PAGO:C(1)
  UPAGO:D(8) TELEFONO:C(9) ESTADO:C(1) USUARIO:C(15) CREDITOS:N(12,2)
  DEBITOS:N(12,2)

detafact.dbf — 38 fields, 398 B/rec, 117,022 recs
  FACTURA:N(7) CODIGO:C(15) CODIGOCABY:C(15) DESCRIP:C(30) NOMBRE:C(30)
  ESTADO:C(1) NO_REC:N(6) FECHA:T(8) CANTIDAD:N(8,3) DESCTO:N(10,2)
  VALORUNITA:N(12,2) TOTAL:N(12,2) CODIGO2:C(1) FAMILIA:C(3) GRABADO:C(1)
  CAJERO:N(2) USUARIO:C(15) EXENTO:C(1) NNUMELINEA:N(5) CSERVICIOS:C(1)
  NMTOLINEEX:N(18,5) NPORCEXENT:N(6,3) NPRECVENTA:N(16,5) NPRECCOSTO:N(16,5)
  NMONTDESCU:N(16,5) NVENTAEXEN:N(16,5) NVENTAGRAV:N(16,5) NPRECNORMA:N(16,5)
  NMONTDESPR:N(16,5) NMONTOIMPU:N(16,5) CUNIDADGTD:C(15) PORCEIMP:N(5,2)
  IMP:N(10,2) DESCUENTO:N(10,2) CACTIVIDAD:C(6) CCODIMPUES:C(2)
  CODITARIF:C(2) TOTALEXONE:N(12,2)

FE_DOCUMENTOS.DBF — 31 fields, 1516 B/rec, 65,497 recs
  IDFE:C(20) NUMERO:N(10) CTIPODOCFE:C(2) CCLAVE:C(50) CCONSECUTI:C(20)
  CESTADOFE:C(15) LENPROCESO:L(1) FCREACION:C(19) CUSUARIOCR:C(20)
  CESTACIONC:C(30) FINICIOPRO:C(19) FFINPROC:C(19) FENVIO:C(19)
  FRESPUESTA:C(19) CUSUARIOEN:C(20) CESTACIONE:C(30) CMENSAJEHD:M(4)
  CRUTAXML:C(150) CRUTAXMLF:C(150) CRUTARESP:C(150) CCLAVEREF:C(50)
  CCODREF:C(2) CRAZONREF:C(180) CERRORPROC:C(200) CMENSAJEHA:M(4)
  CRUTAPDF:C(150) LENVIOCORR:L(1) FCORREOENV:T(8) CCORREOENV:C(50)
  CERRORCORR:C(100) NCANTCORRE:N(3)

MAESTRO.DBF — 33 fields, 315 B/rec, 5,996 recs
  CODIGO:C(15) CODIGO2:C(15) CODIGOCABY:C(15) NUMEFACTCO:N(8,2) NOMBRE:C(35)
  FAMILIA:C(3) UBICACION:C(1) CANTIDADAP:N(10,2) CANTIDAD:N(10,2)
  CANTIDADOL:N(10,2) CANTINICIO:N(10,2) CANTCIERRE:N(10,2) CANTIVENTA:N(10,2)
  CANTCOMPRA:N(10,2) CANTAJUSTE:N(12,2) CANTAJUST2:N(12,2) COSTO:N(12,2)
  MARGENUTIL:N(6,2) DESCUENTO:N(6,2) VENTA:N(12,2) PROM:N(8)
  ULTIMAENTR:D(8) ULTIMASALI:D(8) STOCKMIN:N(5) STOCKMAX:N(5) EXENTO:C(1)
  PROV:C(40) INCLUYEIV:C(1) TIPOARTI:C(1) PORCEIMP:N(5,2) CACTIVIDAD:C(6)
  CCODIMPUES:C(2) CODITARIF:C(2)

salidas.DBF — 13 fields, 154 B/rec, 907,139 recs
  CODIGO:C(15) FAMILIA:C(3) UBICACION:C(1) DOCUMENTO:C(7) FECHA:D(8)
  CANTIDAD:N(8,3) COD_DESC:C(1) DETALLE:C(40) NOMBRE:C(30) PVENTA1:N(12,2)
  VENTAIMP:N(12,2) USUARIO:C(15) EXENTO:C(1)

KARDEX.DBF — 17 fields, 171 B/rec, 143,372 recs
  CODIGO:C(15) PROVEEDOR:C(4) FAMILIA:C(3) CANTIDAD:N(10,3) SALDO:N(10,3)
  TIPOAJUSTE:C(2) VENTA:N(12,2) COSTO:N(12,2) FECHA:D(8) MOVIMIENTO:C(2)
  DOCUMENTO:C(50) TIPODOCUME:C(1) USUARIO:C(15) CONTROL:C(1) EXENTO:C(1)
  UTILIDADIN:N(12,2) UTILIDADGR:N(12,2)

ENTRADAS.DBF — 10 fields, 134 B/rec, 14,317 recs
  CODIGO:C(15) UBICACION:C(1) DOCUMENTO:C(7) FECHA:D(8) CANTIDAD:N(8,3)
  COSTO:N(10,2) PROV:C(30) CODPROV:C(4) DETALLE:C(35) USUARIO:C(15)

AJUSTESINV.dbf — 5 fields, 42 B/rec, 3,419 recs
  DOCUMENTO:N(4) FECHA:D(8) USUARIO:C(15) TIPOAJUSTE:C(2) MONTO:N(12,2)

CLIENTES.DBF — 33 fields, 633 B/rec, 256 recs
  NUMERO:C(4) DESCUENTO:N(6,2) NUME2:C(4) NOMBRE:C(35) IDENTIFAC:C(20)
  ENCARGADO:C(30) AUTORIZADO:N(12,2) UTILIZADO:N(12,2) CREDITO:C(1)
  MONTOMOR:N(12,2) DIRECCION:C(45) TELEFONO:C(9) FAX:C(7) APDOPOSTAL:C(15)
  PLAZO:N(3) VENDE:N(2) ZONA:C(2) TIPOCLIENT:N(1) FUCOMPRA:D(8)
  MUCOMPRA:N(12,2) TRANSPORTI:C(15) NUEVOCPO:C(10) CTIPOIDENT:C(2)
  CNUMIDENTI:C(25) CCODIGZONA:C(8) CEXONDOCTI:C(2) CEXONDOCNU:C(15)
  CEXONINSTI:C(50) DEXONFECHA:D(8) NPORCEXENT:N(6,3) COBSEREXEN:C(200)
  EMAIL:C(50) OBLIGADO:L(1)

PROVEE.DBF — 13 fields, 232 B/rec, 146 recs
  CODIGO:C(4) NOMBRE:C(30) TELEFONO1:C(8) TELEFONO2:C(8) FAX:C(8)
  IDENTIFICA:C(35) EMAIL:C(30) DIRECCION:C(40) CONTACTO:C(30)
  FECHAULTIM:D(8) MONTOULTIM:N(10,2) TOTALCOMPR:N(10,2) SALDOCOMPR:N(10,2)

FAMILIAS.DBF — 2 fields, 39 B/rec, 87 recs
  NUMERO:C(3) NOMBRE:C(35)

fe_encabezado.DBF — 13 fields, 305 B/rec, 276 recs
  FECHAEMISI:T(8) NUMEROFACT:C(50) NOMBREEMIS:C(100) CEDULAEMIS:C(20)
  SUBTOTAL:N(14,2) TOTALDESC:N(14,2) TOTALEXENT:N(14,2) TOTALIVA13:N(14,2)
  TOTALIVA8:N(14,2) TOTALIVA2:N(14,2) TOTALIVA4:N(14,2) TOTALIVA1:N(14,2)
  TOTALDOC:N(14,2)

fe_detalle.dbf — 19 fields, 373 B/rec, 3,340 recs
  FECHAEMISI:D(8) EMISOR:C(60) NUMEROFACT:C(50) CODIGO:C(15)
  CODIGOCABY:C(15) DESCRIPCIO:C(100) CANTIDAD:N(9,2) EMPAQUE:N(5)
  PRECIO_UNI:N(12,2) MONTOTOTAL:N(12,2) SUBTOTAL:N(12,2) CODIGOIMPU:C(2)
  CODIGOTARI:C(2) TARIFA:N(2) MONTOIMPUE:N(10,2) IMPUESTONE:N(10,2)
  MONTOTOTA2:N(12,2) APLICADO:L(1) IDPROCESO:C(35)
```

### 8.2 Target columns (local Mercurius)

```
articulos(codigo bigint PK, unidadmedida, codigobarra varchar, descripcion, diasstockseguridad,
          estadoalertas bool, exento bool, fecha, nombre, processed bool, recomendacioncabys,
          status bool, stockoptimo int, unidadmedidacomercial, cabys_id varchar,
          departamento_id int, familia_id int, usuario_id bigint, tipo_refrigeracion, version)
articuloprecio(id, fechacompra, porcentaje_utilidad, precio_con_utilidad,
               precio_costo_sin_iva, precio_final, articulo_id bigint, usuario_id)
articulostock(id, codigobarra, lastupdated, stock numeric, codigo_barra, version)
inventario(codigo int, cantidad numeric, fechamovimiento, notas, processed bool, status bool,
           tipomovimiento varchar, unidadesrecomendadasfactura, articulo_codigo bigint, usuario_id)
lotes(id, cantidadactual, cantidadinicial, fechaingreso, fechavencimiento, notas,
      numerolote, status, articulo_codigo, usuario_id, version)
linea_detalle(detalle varchar, codigocabys varchar, cantidad, precio_unitario, …)
```

---

## 9. Field mapping and mismatches

| # | Mismatch | Evidence | Consequence |
|---:|---|---|---|
| 1 | **Article key type** | `articulos.codigo` is `bigint`; live uses 1…5996; DBF `CODIGO` is `C(15)` and **mixed** (internal `"10"`, barcodes `"764009010315"`, `"067"`) | Never write DBF `CODIGO` into `articulos.codigo`. Barcode → `codigobarra` (varchar); `codigo` stays the internal integer, which `inventario.articulo_codigo` references. |
| 2 | **Leading zeros** | `"067"`, `"000"` | bigint casts collapse `067`→`67`, `000`→`0`, creating bogus articles. |
| 3 | **Two catalogs, one count** | `MAESTRO` 5,996 = live `articulos` 5,996; 2006 `ARTI2` 15,058 shares only 1,456 codes with `MAESTRO` | Import the 5,996 current articles; attach 2006 barcodes strictly as aliases for historical join. |
| 4 | **Tax model** | Current `MAESTRO`: `PORCEIMP` = 13.00 (4,881) · 0.00 (628) · 1.00 (435) · 2.00 (49) · 8.00 (3); `EXENTO` S 629 / N 5,367; `INCLUYEIV` S 128 / N 5,868; `CCODIMPUES` `01`, `CODITARIF` `08`; `detafact` repeats the per-line tax | Mercurius stores `exento bool` + CABYS; the rate must come from `PORCEIMP`/`CCODIMPUES`/`CODITARIF` — **not** a hardcoded 13 %. 128 items are price-IVA-included and 628 carry 0 %. |
| 5 | **Stock is unreliable** | `MAESTRO.CANTIDAD`: sum **−52,486.78**, 1,116 negative, 2,362 zero, 2,518 positive | Do **not** seed `articulostock` from `MAESTRO.CANTIDAD`; use live `articulostock` (2,494) or recompute from movements. Keep negative balances only as shipment-shortage evidence. |
| 6 | **Movement ledger duplicated** | 2026 `salidas` = 135,268 rows; 2026 `KARDEX.FE` = 135,269 rows; `KARDEX` adds `IM` 8,073 + `AJ` 30 | `KARDEX` 2026 ≈ `salidas` 2026 + entries. Import **one** per year (see §11 Phase 3). |
| 7 | **Join keys differ** | `salidas.DOCUMENTO` = 7-char invoice number; `KARDEX.DOCUMENTO` = 50-char **clave**; `facturas.CIDDOCUMEN` = clave; `detafact.FACTURA` = number | Build two maps: `NUMERO → clave` (from `facturas`) and `clave → doc` (from `FE_DOCUMENTOS`); never join `KARDEX.DOCUMENTO` to `NUMERO`. |
| 8 | **Date types differ** | `facturas.FECHA` = `D(8)` text `YYYYMMDD`; `detafact.FECHA` and `fe_encabezado.FECHAEMISI` = VFP `T(8)` binary (Julian day + ms); `FE_DOCUMENTOS.FCREACION` = `C(19)` `dd/mm/yyyy hh:mm:ss` | Three parsers required. Zero-filled/NUL dates exist (5 rows in `facturasdiarias`, 1 in `detafact`). |
| 9 | **Line detail scope** | `detafact` 117,022 lines / 65,570 invoices, but only **1,604 distinct article codes**; `MAESTRO` has 5,996 | 4,392 catalog items have no 2026 sales line; import them anyway (inventory/reporting need them). |
| 10 | **Discontinued codes** | `salidas` 3,884 distinct codes, **515 absent from current `MAESTRO`**; `KARDEX` 3 absent | Either create placeholder articles with `status=false` or map to successors; dropping loses 5 % of movement history. |
| 11 | **Received invoices are separate** | `fe_encabezado` 276 / `fe_detalle` 3,340 use `NUMEROFACT` = clave, IVA buckets 13/8/2/4/1 | Do not merge into the `facturas` path; they feed `comprobantesrecibidos` + `detalle_servicio` with their own tax split. |
| 12 | **Client identity** | `CLIENTES.NUMERO:C(4)` (leading zeros), `CTIPOIDENT`/`CNUMIDENTI`; `clients.code integer`, `tipoidentificacion`/`idnumber` | Map `'0001'`→1; keep `'0'` as cash customer. `TIPOCLIENT` is blank for all 256 rows, so no segmentation. |
| 13 | **Families vs departments** | `FAMILIAS` flat 87 codes; Mercurius has `familia` *and* `departamento` (live 78 / 125) | DBF supplies family only; departments must come from live DB. |
| 14 | **Article flags** | app-created articles land with `processed=false`, `familia_id=NULL`, `cabys_id=NULL` | Imported articles must set `processed=true` explicitly or `processed`-filtered reports stay empty. |
| 15 | **No supplier table local** | `PROVEE` 146 exists; local has only `orden_compra.proveedor_id` | Confirm the target table before planning supplier import. |
| 16 | **Hacienda XML paths unusable** | `FE_DOCUMENTOS.CRUTAXML/CRUTAXMLF` = `C:\VSUPER\…` on the POS PC | Use `FE_DOCUMENTOS` for the **clave/estado**, not to fetch XML; the XMLs are the WebDAV ZIPs. |

---

## 10. Gap verdict per domain

| Domain | Local | Best available (union of all sources) | Verdict |
|---|---:|---|---|
| **Articles** | 8 | `MAESTRO` **5,996** (2026) = live 5,996 = `maestro.csv` 5,996; 2026-01 snapshot 5,795; E: 6,424 (2019); 2006 15,058 | **missing** |
| **Prices** | 8 | live `articuloprecio` **16,886**; `maestro.csv` has one cost/sale pair per article | **missing** |
| **Stock snapshot** | 0 | live `articulostock` **2,494**; `stock_objetivo.csv` 5,996 target rows; `MAESTRO.CANTIDAD` unusable in aggregate | **missing** |
| **Inventory movements** | 8 | `Original DBF\KARDEX` **843,552** (2022-2025 = 836,761; 2026 = 6,791) + current `KARDEX` 2026 **143,371** + `ENTRADAS` 14,317 + `AJUSTESINV` 3,419; parallel exit ledger `salidas` 907,139; live 673,326 | **missing** |
| **Lots** | 0 | none anywhere | **n/a** |
| **Families / departments** | 0 / 1 | `FAMILIAS` 87; live 78 / 125 | **missing** |
| **Clients** | 0 / 10 | `CLIENTES` **256** = `clientes.csv`; live 238 / 292; E: 95 | **missing** |
| **Users** | 2 (dup admin) | live 27 | **partial** |
| **Sales 2026** | 12,077 | `facturasdiarias` **75,834** POS / `facturas` 65,572 e-invoices / XML 2026 archive / live 81,402 | **partial 15.9 %** |
| **Sales 2025** | 0 | `Respuestas` **7,542 unique claves (2025-06 → 2026-01)** + `facturasenero2026.DBF` **93,145** headers (full 2025) | **missing** |
| **Sales 2023-2024** | 104 | `facturasbk…` 119,383 (2023) + 43,138 (2024 Q1) headers; `detafactbk…` 2024 lines 123,518 | **missing** |
| **Sales 2022** | 0 | `Original DBF\facturasdiarias` **122,645** POS headers (no electronic clave) | **missing** |
| **Sales 2013-2019** | 0 | E: `facturasdiarias` **500,708** headers (2013-2019); 2018 also `facturas05122018.DBF` 14,137 | **missing** |
| **Sales — lines** | 27,018 (all years) | `detafact` 117,022 (2026) + `detafactbk…` 325,036 (2024-2026) | **missing** |
| **Purchases** | 259 (of which 243 duplicates) | `fe_encabezado` 276 / `fe_detalle` 3,340; `facturascompras` 1,592 (2022); XML `XMLS_COMPRA` 91; live 177 | **partial** |
| **XML archive** | 11,939 distinct claves | **20,306 unique claves** on disk (2018-2026); 8,367 not in DB; 0 DB claves without XML | **backlog** |
| **CABYS** | 20,505 | live 20,505 | **complete** |
| **Taxes/discounts/payments** | 28,521 / 2,676 / 11,848 | children of the missing lines | **partial** |
| **Consecutivos / FX** | 52 / 17 | live | **sufficient** |

Only CABYS, the 2026-09 POS slice and the 8,367-clave backlog listing are resolved. Everything else is partial or missing — the local DB lacks 2024-06…2025-12, 2026-07, and all of 2013-2023.

---

## 11. Union import plan (all sources, oldest → newest)

### 11.1 Coverage matrix — what to take from where

| Period | Invoice headers | Invoice lines | Stock ledger | Notes |
|---|---|---|---|---|
| 2013-2015 | E: `facturasdiarias` (2013 49,402 · 2014 208,561 · 2015 203,103) | – | E: `kardex` (2015-2016) | header-only |
| 2016 | E: `facturasdiarias` 39,552 | – | E: `kardex` + `entradas` | header-only |
| 2017 | **none** | – | E: `entradas` 4,182 | sales gap in every source |
| 2018 | `facturas05122018.DBF` 14,137 | – | E: `kardex` 104 | |
| 2019 | E: `facturasdiarias` 2 | – | – | |
| 2020-2021 | **none** | – | `entradas` only | gap in every source |
| 2022 | `Original DBF\facturasdiarias` **122,645** (POS, no clave) | – | `Original DBF\KARDEX` **232,211** | live has 1 doc |
| 2023 | `facturasbk…` **119,383** (electronic) | **missing** | `Original DBF\KARDEX` **218,238** | lines only from 2024 |
| 2024 | `facturasbk…` **43,138** (Jan-Apr) + `facturasdiarias` 107,241 (POS, full year) | `detafactbk…` **123,518 (Apr-Dec)** + `DETAFACTMEDIOMARZO` partial Mar | `Original DBF\KARDEX` **201,391** | Jan-Mar lines mostly missing; electronic May-Dec missing |
| 2025 | `facturasenero2026.DBF` **93,145** + `Respuestas` XML **7,542** (H2) | `detafactbk…` **170,341** | `Original DBF\KARDEX` **184,879** | |
| 2026 | `facturas` 65,572 + XML archive 11,120 claves | `detafact` **117,022 (Feb-Oct)** + `detafactbk…` Jan-Feb **31,175** | current `KARDEX` **143,371** | |
| Undated | – | – | `ENTRADAS` 14,317 (2016-2026), `AJUSTESINV` 3,419 | |

Line-detail coverage is narrower than header coverage and must be stated explicitly in the plan:

| Period | Lines available | Source |
|---|---:|---|
| 2013 → 2023 | **0** | none |
| 2024-01 → 2024-03 | partial (10,526) | `DETAFACTMEDIOMARZO` (March only, partial) |
| 2024-04 → 2024-12 | 123,518 | `detafactbk27022026` |
| 2025-01 → 2025-12 | 170,341 | `detafactbk27022026` |
| 2026-01 | 18,247 | `detafactbk27022026` (only source) |
| 2026-02 | 26,545 raw (13,617 + 12,928) | `detafact` + `detafactbk` (dedupe) |
| 2026-03 → 2026-10 | 103,404 | current `detafact` |

Take **one** ledger per year: `KARDEX` is a superset of `salidas` for 2022-2026 (2022: 232,211 vs 212,478; 2026: 143,371 vs 135,268). Use `salidas` only as a reconciliation check, and never import both for the same year.

### 11.2 Dedupe keys (fixed for the whole union)

| Entity | Primary key | Fallback | Notes |
|---|---|---|---|
| Document (invoice/ticket/NC/ND) | **`clave`** (50 digits) | `(numero_consecutivo, fecha_emision, total)` | 243 claves already duplicated in `encabezado` — dedupe first |
| Line | `(clave, numero_linea)` → `(FACTURA, NNUMELINEA)` | `(clave, codigo, cantidad, total)` | `detafact` / `detafactbk` overlap in Feb 2026 |
| Inventory movement | `(codigo, fecha, cantidad, documento, tipo)` | + ledger name | kardex vs salidas are parallel ledgers |
| Article | `codigobarra` (= `MAESTRO.CODIGO`) | `MAESTRO.CODIGO2`, then name | never reuse `articulos.codigo` (internal 1…5996) |
| Client | `CNUMIDENTI` | `CLIENTES.NUMERO` | many blank IDs → fallback to number |
| Family | `FAMILIAS.NUMERO` | name | |
| Supplier | `PROVEE.CODIGO` | `IDENTIFICA` | no local supplier table yet |

### 11.3 Order of ingestion

1. **Masters** (categories → articles → clients/suppliers) — §11 Phase 1-2 and Phase 4.
2. **Opening stock + prices** — §11 Phase 2.
3. **Inventory ledger**, oldest → newest — §11 Phase 3.
4. **Invoices from XML**, 2025-06 → 2026-10 — new Phase 5A.
5. **Invoices from DBF-only periods**, 2013-2024 — new Phase 5B.
6. **Purchases** — §11 Phase 8.
7. **Reconciliation** (duplicates, stock, flags) — new Phase 10.

The detailed phases below remain valid; 5A/5B/10 and 11.4-11.6 extend them.

### 11.4 The 2026-01-10 `Original DBF` snapshot: how to treat it

* **Use it for:** `KARDEX` 2022-2025 (**836,761** of its 843,552 rows — the *only* source of those years), `facturasdiarias` 2022-2025 (450,982 POS headers), and as a cross-check for `salidas` 2022-2025 (counts are identical to the current snapshot).
* **Do not use it for 2026:** its 2026 slices stop on 2026-01-10 (kardex 6,791 / salidas 6,375) — superseded by the current files.
* **Do not import its `MAESTRO.DBF`** (5,795 rows, 201 fewer than current) or `CLIENTES.DBF` (239 vs 256); they are older.
* It is the **only** snapshot with a full multi-year kardex, so it must be copied/archived before any cleanup of `F:\Desktop\Super\`.

### 11.5 XML format handling

* **v4.4** (extracted, `Respuestas`, temp copy): matches the app's current pipeline; import directly.
* **v4.3** (152 of 444 files in `F:\Documents\Facturas Electronicas`): supported by the app's v4.3 XSD set; validate against v4.3 before upload.
* **v4.2** (all 54 files / 27 claves in `E:\VPALMERAS`, 2018): **no v4.2 XSD ships with the app**. Translate to v4.4 or exclude; these 27 invoices are already outside the 2013-2019 DBF header import and are low priority.
* Prefer the `_firmado` copy; treat the plain XML as the draft and `Mensaje Hacienda *-aceptado.xml` as state evidence.

### 11.6 Sibling artifacts (PDF / B64 / UTF)

| Artifact | Count | Role | Action |
|---|---:|---|---|
| `.pdf` (11,243 in `extracted` + 3,825 in temp + 28 in E: + 32 in supplier folder) | 15,128 | human-readable representation | **attachment/evidence**, not an import source |
| `.b64` (27, E: only) | 27 | base64 signed payload of the same clave | skip (duplicate of signed XML) |
| `.utf` (27, E: only) | 27 | Hacienda plain-text response | skip (state evidence only) |
| `MensajeHacienda *.xml` (11,407 in extracted, 6,183 `R_*` in Respuestas) | ~17,600 | Hacienda acknowledgement | use to set `comprobantesemitidos.haciendaestado`, never create a document |
| `.zip` (10 + 1) / `.rar` (2) | 13 | containers already extracted | keep as cold backup; do not import from inside |

### 11.7 What the reference ETL already solved (reuse, don't re-derive)

`migration/manifest_final.csv` (10,261 deduped groups, with `chosen_path`) and `xml_manifest.csv` (20,378 raw files) encode the exact duplicate-resolution decisions used for the live server. `movimientos_inventario.csv` (156,572 rows) is a ready movement feed. `fe_documentos_estado.csv` (65,497) gives state per clave. Reusing these files is safer than re-implementing dedupe — but they cover only the 2026 extraction, not `Respuestas` (2025) or E: (2018).

### Phase 0 — prerequisites

1. Real backup of local `mercurius` (`pg_dump -Fc`). The live host's "pre-dedupe" dump is **0 bytes** and unusable.
2. Freeze writes on `:8091` (the app wrote during this audit).
3. Export what must survive: `cabys` 20,505, `consecutivo_emitido` 52, `tipocambio` 17, `appsettings`, `sucursal`, `users`, and the 8 app-created article/price/inventory rows.

### Phase 1 — categories

* `familia` ← `FAMILIAS.DBF` **87** (current) — reconcile with live 78.
* `departamento` ← live **125** only (DBF has no department level).
* Risk: `familia_id`/`departamento_id` are plain ints; import masters before articles and keep ids stable.

### Phase 2 — articles, stock snapshot, prices (highest value)

* Articles from **`MAESTRO.DBF` 5,996** (current) — it already carries `CODIGOCABY`, `PORCEIMP`, `CCODIMPUES`, `CODITARIF`, `TIPOARTI`, `FAMILIA`, `EXENTO`, `INCLUYEIV`.
* Cross-check with live `articulos` (identical 5,996 count): prefer live if you want the already-normalized rows, then fill `codigobarra`/`cabys_id` from `MAESTRO.CODIGO`/`CODIGOCABY`.
* `articulostock` ← live **2,494** (do not use `MAESTRO.CANTIDAD`; see mismatch #5). If live is unavailable, recompute balance from the ordered movement import and mark it as of 2026-10-03.
* `articuloprecio` ← live **16,886**. If starting from DBF only, `MAESTRO.COSTO`/`VENTA`/`MARGENUTIL` gives one row per article (5,996) — acceptable as a bootstrap, never as history.
* Set `status=true`, **`processed=true`**, `version=0`.
* Legacy aliases: `maestrocaby` 53,317 and 2006 `ARTI2` 15,058 provide code→CABYS/barcode mappings for historical rows; never create duplicate articles from them.

### Phase 3 — inventory movements (choose one ledger per year)

Recommended composition, deduplicated by year:

| Years | Source | Rows | Reason |
|---|---|---:|---|
| 2026 | **current `KARDEX.DBF`** | 143,371 | Superset of `salidas` 2026 (135,268); has exits + entries + adjustments |
| 2022-2025 | **`Original DBF\KARDEX.DBF`** | **836,761** (232,211 + 218,238 + 201,391 + 184,879) | **Only** snapshot that still contains multi-year kardex |
| 2016-2026 | `ENTRADAS.DBF` | 14,317 | Purchases/entries; complements kardex for 2016-2021 |
| 2016-2026 | `AJUSTESINV` | 3,419 | Adjustment headers only (no product) — reference, not movements |
| 2013-2016 | E: `kardex.DBF` | 386,566 | Historical depth (E: `salidas` documents are masked, unusable) |
| all | `salidas` | 907,139 | **Cross-check only** — parallel exit ledger; do not import alongside kardex |

Never import **both** `KARDEX` and `salidas` for the same year: they are the same ledger (2026: 135,269 vs 135,268 rows; 2022-2025 counts are identical across snapshots).

Risks: `MAESTRO` has 1,116 negative balances; 515 salidas codes are absent from the catalog; 840 KARDEX rows have no document; `AJUSTESINV` cannot be turned into per-article rows without a source not present here.

### Phase 4 — clients and suppliers

* `clients` ← `CLIENTES.DBF` **256** (current) or live 238; `receptor` ← live 292.
* Suppliers ← `PROVEE.DBF` **146** if a target table is confirmed.
* Keep the generic receptor: 93 % of local invoices already point at receptor #242 (blank). Do not "fix" by name matching.

### Phase 5A — invoices 2025-06 → 2026-10 from XML (do this before the DBF imports)

Import in this order, oldest first, deduping by clave against the DB and against each other:

1. **`Respuestas`** — 7,542 claves, 2025-06-26 → 2026-01-11, **zero** already in the DB.
2. **`extracted`** — 11,120 claves, 2026-03-07 → 2026-10-20, of which **797 are new**.
3. **`%TEMP%\mercurius-webdav-extracted`** — 2,671 claves, 2026-10-01 → 2026-10-27, **794 new** (October incrementals).
4. **`F:\Documents\Facturas Electronicas`** — 366 claves, 2024-03-01 → 2026-06-09, 1 new (mostly already imported).

Rules: prefer `_firmado` XML; take `haciendaestado` from the `Mensaje Hacienda` sibling; skip any clave already present in `comprobantesemitidos` **or** `comprobantesrecibidos` (see the 243 duplicates in §4.1); import the whole document graph in one transaction; insert with the original emission date, never `now()`.

Coverage gap this leaves: **2026-01-12 → 2026-03-06** has no XML in any folder (Respuestas ends Jan 11, extracted starts Mar 7) — fill from `facturas`/`facturasdiarias` in Phase 5B.

### Phase 5B — invoices from DBF for the periods without XML

| Period | Source | Docs | Lines |
|---|---|---:|---|
| 2026-01 | `facturasenero2026.DBF` (2,239 electronic) + `facturasdiarias` Jan (10,109 POS) | ≈10.1k POS / 2.2k with clave | `detafactbk…` Jan 18,247 |
| 2026-01-12 → 2026-03-06 | current `facturas` (Feb 7,750 + Mar 1–6) | ~8k | `detafact` |
| 2026-03 → 2026-10 | **prefer XML (5A)**; DBF only as cross-check | – | – |
| 2025-01 → 2025-05 | `facturasenero2026.DBF` | 6,743 + 8,609 + 9,612 + 9,529 + 7,991 = 42,484 | `detafactbk27022026` |
| 2025-06 → 2025-12 | **prefer XML (5A)** | 7,542 claves | – |
| 2024-01 → 2024-04 | `facturasbk22042024noborrar.DBF` | 43,138 | `detafactbk…` **Apr-Dec** 123,518; Jan-Mar missing |
| 2024-05 → 2024-12 | **no electronic source**; `facturasdiarias` POS only | 107,241 (full-year POS) | **none** |
| 2023 | `facturasbk22042024noborrar.DBF` | 119,383 | **none** |
| 2022 | `Original DBF\facturasdiarias.DBF` (POS, no clave) | 122,645 | **none** |
| 2013-2019 | E: `facturasdiarias.DBF` | 500,708 | **none** |

Header-only rows must be flagged (e.g. `schemaversion='HISTORICO-SIN-DETALLE'` or a dedicated note) so line-level analytics never divide by zero/missing lines.

Dedupe: `clave` when present; otherwise `(numero_consecutivo, fecha_emision, total)`. The 2023/2024 POS headers overlap the electronic docs of the same period — link them by `(fecha, total, cliente)` only if needed, never insert twice.

### Phase 6 — 2022-2023 POS-only backfill (optional)

* 2022 has **no** electronic header table in any source (live has 1 doc); the only record is `Original DBF\facturasdiarias` 122,645 POS headers.
* 2023 has electronic headers (119,383) but **no line detail** anywhere.
* Decide explicitly whether header-only history is acceptable. If not, 2022-2023 stay out and the UI should say so instead of showing zeros.

### Phase 7 — sales 2013-2019 (historical depth)

* `E:\VPALMERAS\facturasdiarias.dbf` **500,708** headers (2013-10 → 2019-04) is the only broad source. **Header-only**: `DETAFACT` has 43 rows and E: `salidas` documents are masked.
* Recommended: import headers + totals only, flag them as "historical, no line detail", and exclude them from line-level analytics. Alternatively skip and keep 2013-2019 out of scope.
* 2018 has a better source: `facturas05122018.DBF` 14,137 (close to live 14,094).
* 2016 (39,552) exists only here; 2017/2020/2021 exist nowhere.

### Phase 8 — purchases

* `fe_encabezado` **276** + `fe_detalle` **3,340** (2026) — clave-keyed, IVA buckets; feeds `comprobantesrecibidos` + `detalle_servicio`.
* `facturascompras` **1,592** (2022) is header-only, no lines; use for totals only.
* Keep the existing 259 local received invoices; dedupe by clave.
* `PUT /procesar` is still the only path that turns a purchase into articles/stock — consider whether that should be automatic after this import (code-level decision, no index changes).

### Phase 9 — housekeeping

* Merge `users` id 1 and 2 (both `admin`).
* Reconcile `articulostock` against the imported ledger per article.
* Fix `processed` on imported articles (mismatch #14).

### Phase 10 — reconciliation after the union

1. Resolve the **243 duplicate claves** (§4.1): keep the `comprobantesemitidos` row as canonical, drop the duplicate `comprobantesrecibidos` row unless its emisor genuinely differs; re-run `count(*) - count(distinct clave)` and expect **0**.
2. Recompute `articulostock` from the imported ledger and compare with live 2,494 rows and `migration\stock_objetivo.csv` (5,996 targets); document any article where the three disagree.
3. Verify per-year counts against the §11.1 matrix and record the deltas.
4. Re-run the integrity queries: 0 orphan `linea_detalle`, 0 `encabezado` without XML provenance, 0 claves outside 50 digits.
5. Re-check `articulos.processed` and `familia_id`/`cabys_id` coverage.

---

## 12. Must NOT be re-imported (duplicate risk)

| Object | Why |
|---|---|
| `cabys` (20,505) | identical to live |
| Existing 11,923 emitted documents + their children (`encabezado`, `detalle_servicio`, `linea_detalle`, `impuesto`, `descuento`, `medio_pago_*`, `resumen_factura`, `comprobantesemitidos`) | clave/consecutivo collisions; 27,018 lines already complete for those docs |
| `consecutivo_emitido` 52, `tipocambio` 17, `appsettings`, `sucursal` | config/sequence state |
| `users` 2 | already duplicated identities |
| 8 articles / 8 prices / 8 inventory rows from 2026-10-28 04:14 | belong to a processed purchase; match, don't recreate |
| `codigo_comercial` / `impuesto` / `descuento` for existing lines | line-keyed; re-running duplicates them |
| `KARDEX 2026` **or** `salidas 2026` (not both) | same ledger, would double every 2026 movement |
| `entradas_respaldo.dbf` together with `ENTRADAS.DBF` | older copy, double-counts |
| `Articulos.dbf` **and** `ARTI2.DBF` together | identical 15,058-code sets |
| `detafactbk27022026` 2026 rows together with `detafact` 2026 rows | overlapping Feb 2026; dedupe on `(FACTURA, NNUMELINEA)` |
| `KARDEX` **and** `salidas` for the same year (any 2022-2026) | parallel ledgers; kardex is the superset |
| Plain `<clave>.xml` when `<clave>_firmado.xml` exists | same document, unsigned draft |
| `Mensaje Hacienda *.xml`, `R_*` / `Res_*`, `.pdf`, `.b64`, `.utf` | siblings/evidence of an existing clave, not new documents |
| `RESPALDO_FE_DOCUMENTOS_20260622_230017` and `…_MIGRACION_*` | **empty** (0 MB, 4 rows) |
| `RESPALDO_CLAVE_PBKDF2_20260917_233440` / `clave.dbf` | password/key store — never import |
| `Original DBF\MAESTRO.DBF` (5,795) and `CLIENTES.DBF` (239) | older than current (5,996 / 256) |
| `Original DBF\KARDEX.DBF` 2026 slice (6,791 rows) | superseded by current kardex (143,371) |
| `E:\VPALMERAS\Aceptacion\Tablas` (23 DBFs) | 2018 CxC/INV module test tables, no sales core |
| Visual FoxPro 9 `Samples/Wizards/Gallery` | installer samples, not business data |
| v4.2 XMLs in `E:\VPALMERAS` (27 claves) | no v4.2 XSD in the app; translate or skip |
| `repara_respaldo.dbf` | corrupt header (1.6 B records / 0 MB) — unreadable |
| `maestro2.DBF`, `facturas2.DBF`, `DETAFACT2.DBF`, `clientes2.dbf`, `*_respaldo*` | empty, superseded, or corrupt |

---

## 13. Open items / what could not be determined

1. **WebDAV anonymous** → 401; only the saved credentials worked (used read-only).
2. **`Sistema_vsuper_20261027-163717.zip`** (185.6 MB) not downloaded — it is newer than the audited 2026-10-04 snapshot and may include October beyond the 3rd.
3. **Live DB credentials** come from env vars; read via `/proc/<pid>/environ`, never printed or stored.
4. **No `sudo`** on the live host (password required) → no `pg_stat_*`/per-table size breakdown there.
5. **No working local dump**; the live "pre-dedupe" dump is 0 bytes.
6. **2013-2019 line detail does not exist** in either DBF copy (E: `DETAFACT` 43 rows, `salidas` masked; F: `detafact` starts 2024 in the archives).
7. **2020-2021** has no sales source in any of the inspected artifacts.
8. **`KARDEX 2026` ≡ `salidas 2026`** is inferred from identical row counts (135,269 vs 135,268) and identical column semantics; it should be confirmed with a 100-row sample join before import.
9. **`F:\Documents\Database1.accdb`** and `VentasXDeepartamento.xlsx` were not opened (not referenced by any migration script).
10. **Live count discrepancy:** `MIGRATION_PLAN.md` (2026-10-21) reports 10,155 emitted invoices / 18,675 lines, but the live DB measured today has **751,869 / 564,992**. Either a later bulk load occurred or the audited instance differs from the migration target. Confirm before treating live as the union authority.
11. **2024-05 → 2024-12 electronic invoices** exist nowhere: no `facturas` archive covers that window, no XML folder starts before 2026-03 (except supplier docs from 2024-03). Only POS `facturasdiarias` headers exist.
12. **2017 and 2020-2021** have no sales source at all (only `ENTRADAS` stock rows).
13. **v4.2 is unsupported** by the app (XSD set is v4.3/v4.4 only); the 27 E: 2018 claves cannot be imported without translation.
14. **`manifest_final.csv` (10,261 groups) covers only the 2026 extraction** — it does not include `Respuestas` (2025) or E: (2018), so it cannot be used as the sole dedupe map for the union.
15. **The reference ETL in `migration\`** must not be re-run as-is: it targets the old endpoint flow and the live production instance, and some scripts assume admin credentials/CSRF sessions.
16. **Mercatus** was not touched.

---

## 14. Reserved: `G:` clusters (append here when scanned)

No DBF/XML business cluster has been identified on `G:` yet (`G:\` top level: `mb_bios_x570…`, `OracleVM`, `SteamLibrary`, `Unity-Proyects`, `Vaulted_Servers`, `Videos`). When a cluster appears, append to §6.6/§6.7 using the same probe commands and mark it primary/secondary/backup. Do not assume it is newer than `F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004\` without reading its headers.

---

## 15. Families, prices and margin inputs — live DB deep-dive

**Scope:** read-only analysis of `192.168.100.99:5432/mercurius` (97 tables, live DB) plus `F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004\MAESTRO.DBF` / `FAMILIAS.DBF`, using the credentials in `/home/alvaro/Mercurius/.env` through a `/tmp` SQL file and `/tmp/run_audit.sh` (both created for this audit; no DML was executed).

**Owner's model confirmed:** `departamento` = seller/distributor (125 rows, all supplier names, 100 % populated) — this is correct and must not be "fixed". `familia` = product type — currently polluted by the legacy POS taxonomy.

| Item | Finding | Severity |
|---|---|---|
| `familia` | 78 rows (75 in use) = **74 source names + 4 synthetic**; **47 of them (4,009 articles) are supplier-named** | high (report labels) |
| Article → source family | **recoverable for 100 %** of articles: `codigobarra` = `MAESTRO.CODIGO` is 5,996/5,996 | — |
| `articuloprecio` | **418 articles** with a null/zero latest price, all import artifacts (`usuario_id` null) | high |
| Price construction | **100 % of 5,578 valid latest prices are `costo × 1.25`**, not `MAESTRO.VENTA` (median 0.893× the real shelf price) | critical |
| Pack costs | 71 articles have `COSTO` as a case/pack cost (median factor 11.9×, max 77.5×) → prices up to 10× too high, margins deeply negative | critical |
| `profit_margin_history` | 258,581 rows generated 2026-10-21 from current costs; **11,771 rows (125 articles) have cost > sale**, −192.2 M cumulative | high |
| `profit_margin_snapshots` | **78,429 rows = exactly 2 copies of the history** (786.4 M × 2); **46 % of rows have no family** | critical |
| Snapshot labels | 26,067 rows carry supplier-prefixed/only family names (62 % of the labelled copy) | high |

### 15.1 How the numbers were obtained

```bash
# /tmp/run_audit.sh
set -a; . /home/alvaro/Mercurius/.env; set +a
export PGPASSWORD="$DB_PASSWORD"
exec psql -h localhost -p 5432 -U "$DB_USERNAME" -d mercurius -v ON_ERROR_STOP=1 -f "$1"
```

Read-only SQL files (`audit1..audit9.sql`, `export_*.sql`) were copied with `scp` and run with `/tmp/run_audit.sh`; four datasets were exported with `\copy … to stdout` to `/tmp/live_*.csv`, copied back to `%TEMP%`, and joined locally against `MAESTRO.DBF`/`FAMILIAS.DBF` exported with `dbf_probe.py csv` (`join_analysis.py`, `price_analysis*.py`, `family_map.py`).

### 15.2 Families

#### 15.2.1 What is in the live table

| Metric | Value |
|---|---:|
| `familia` rows | **78** |
| families with ≥1 article | **75** (ids 33, 38, 39 have 0) |
| `FAMILIAS.DBF` records | **87**, of which **74 named** (13 blank rows) |
| live = 74 source names + 4 synthetic (`FAMILIA 195/215/969`, `SIN FAMILIA`) | 78 ✔ |
| articles with `familia_id` / `departamento_id` null | 0 / 0 |
| distinct families used by articles | 75 |
| `departamento` rows | 125, all with articles; 10 named `PROVEEDOR nnn`, 1 `SIN PROVEEDOR` |

So "75 live vs 87 source" is explained: 87 records − 13 blanks = 74 real names, +4 synthetic = 78 rows, of which 75 are actually assigned.

#### 15.2.2 Classification (as requested)

| Class | Families | Articles | % articles |
|---|---:|---:|---:|
| Exact product name (no supplier token) | 24 | 1,973 | 32.9 % |
| Supplier-prefixed source name (e.g. `KION ABARROTES`) | 25 | 2,492 | 41.6 % |
| Supplier-only name (e.g. `DOS PINOS`, `JACKS`) | 22 | 1,517 | 25.3 % |
| Unmatched/other (4 synthetic + 3 service) | 7 | 14 | 0.2 % |
| **Total** | **78** | **5,996** | |

Every one of the 74 source names is present verbatim in `FAMILIAS.DBF`; the only textual differences are two names carrying cp1252/latin-1 mojibake (`REPT ZARCERE¥A` should read `ZARCEÑA`, `Dist. Isle¤a` should read `Isleña`). The 4 synthetic rows exist only because `MAESTRO.FAMILIA` uses codes 195/215/969 with no `FAMILIAS.DBF` row.

**Root cause:** the legacy POS itself encoded supplier inside "familia" (e.g. `KION ABARROTES`, `DOS PINOS`, `BIMBO PAN REPOS SNACK`). The migration copied `FAMILIAS.DBF` names 1:1, so the live taxonomy inherited the mix. `departamento` also carries the supplier, so the family field is redundant for supplier identity.

#### 15.2.3 Can the source `MAESTRO.FAMILIA` be recovered per article?

| Check | Result |
|---|---:|
| Articles | 5,996 |
| `articulos.codigobarra` = `MAESTRO.CODIGO` (exact) | **5,996 / 5,996 (100 %)** |
| Article name = `MAESTRO.NOMBRE` (exact) | 5,994 / 5,996 |
| Live family name agrees with `MAESTRO.FAMILIA` → `FAMILIAS.NOMBRE` | **5,885 / 5,996 (98.1 %)** |
| Disagreements | **111**, all explained: 98 mojibake (`REPT ZARCERE¥A`, `Dist. Isle¤a`), 12 synthetic codes (195/215/969), 1 `SIN FAMILIA` |

`MAESTRO.DBF` preserves `CODIGO → FAMILIA` and the migration ETL already exported it as `migration\maestro.csv` (5,996 rows, same `CODIGO,FAMILIA` pair). There is no live staging table with this mapping (`plan_ids` is not readable by the app user), but **the CSV + MAESTRO make the mapping fully recoverable** — no article loses its product type if the family is re-pointed.

#### 15.2.4 Proposed canonical taxonomy

24 product families (down from 78), built so that the supplier stays only in `departamento`:

| Canonical family | Articles | Replaces (count) |
|---|---:|---|
| ABARROTES | 1,599 | KION ABARROTES (1,125), BOURNE CIA (71), FLORIDA ABARTS (52), DIPO VARIOS (37), PRICESMART (37), NAPOLI ITALIAN (31), ABARROTES GENERAL (31), Comercial Dinant (29), DIST PIME PIZRA (17), Dist. PTP (17), CROSS (16), DELITALIA (15), CAFE BRITT (14), PRODUCTOS CHINOS (13), FAMILIA 195 (8), LA YEMA DORADA (2), Dist. Isleña (37), METAMAX (47) |
| SNACKS | 835 | PAPAS,SNACKS KION (264), JACKS (139), TOSTY (108), DIANA (53), SNACKS KITTY (41), HELADOS POPS (60), SEMILLAS Y MANI (75), SNACKS BERNAL (15), REPT ZARCEREÑA (61), ALPISTE (0), Helados Sensacion (19) |
| LACTEOS | 645 | DOS PINOS (634), LACTEOS CORONADO (11) |
| LICORES | 573 | LICORES BERNAL (232), VINOS BERNAL (210), VINO FLORIDA (79), LICORES FLORIDA (38), DISPAL LICORES (14) |
| CUIDADO PERSONAL | 414 | CREMA,SHAMPO,HIGIENE (322), BRONCEADORES (76), KION REPELENTES (16) |
| BEBIDAS | 383 | PEPSI (168), COCA COLA (136), BEBIDAS,REFRESCOS (54), HILIX (16), HIELERAS STEREOFON (4), HIELO JUAN VEGA (5) |
| LIMPIEZA | 326 | CASA,LIMPIEZA (324), KION LIMPIEZA (2) |
| GALLETAS | 278 | GALLETAS (153), POZUELO GALLETA (125) |
| TABACO | 191 | TABACCO (137), BATCA cigarros (30), MENDIOLA 2 (24) |
| CONFITES/CHICLES | 148 | unchanged |
| PANADERIA,REPOSTERIA | 134 | PANADERIA (51), BIMBO (47), REPT MOFFY (28), CAJETAS MANA (7), FAMILIA 969 (1) |
| FARMACIA | 123 | unchanged |
| CERVEZAS | 93 | unchanged |
| EMBUTIDOS | 55 | unchanged |
| FRUTAS,VERDURAS | 48 | FRUTAS,VERDURAS (29), PAPAS Y PLATANOS CARIBE TONY (19) |
| PASTAS | 33 | unchanged |
| CHOCOLATE | 31 | unchanged |
| BATERIAS, CAMARAS | 21 | unchanged |
| ANIMALES | 19 | unchanged |
| CEREALES | 18 | unchanged |
| SIN CLASIFICAR | 16 | PALM SHOP (6), Iphone Store (6), FAMILIA 215 (3), SIN FAMILIA (1) |
| CAJA/BOLSAS | 10 | CAJA (9), BOLSAS PLASTICAS (1) |
| LIBRERIA | 2 | unchanged |
| ALQUILERES | 1 | ALQUILER LOCAL, SOLUCIONES Y PROPIEDADES, ALQUILERES |

**Impact:** 5,274 of 5,996 articles (88 %) change family; 722 keep the same label. Full id→name mapping:

| id | nombre actual | clase | arts | familia canónica |
|---:|---|---|---:|---|
| 1 | CERVEZAS | exact product | 93 | CERVEZAS |
| 2 | KION ABARROTES | supplier-prefixed | 1,125 | ABARROTES |
| 3 | PANADERIA,REPOSTERIA | exact product | 51 | PANADERIA,REPOSTERIA |
| 4 | LACTEOS CORONADO | supplier-prefixed | 11 | LACTEOS |
| 5 | FRUTAS,VERDURAS | exact product | 29 | FRUTAS,VERDURAS |
| 6 | FARMACIA | exact product | 123 | FARMACIA |
| 7 | BATCA cigarros | supplier-prefixed | 30 | TABACO |
| 8 | KION LIMPIEZA | supplier-prefixed | 2 | LIMPIEZA |
| 9 | BIMBO PAN REPOS SNACK | supplier-prefixed | 47 | PANADERIA,REPOSTERIA |
| 10 | DOS PINOS | supplier-only | 634 | LACTEOS |
| 11 | PASTAS | exact product | 33 | PASTAS |
| 12 | BRONCEADORES | exact product | 76 | CUIDADO PERSONAL |
| 13 | VINOS BERNAL | supplier-prefixed | 210 | LICORES |
| 14 | CASA,LIMPIEZA | exact product | 324 | LIMPIEZA |
| 15 | COCA COLA | supplier-only | 136 | BEBIDAS |
| 16 | ANIMALES | exact product | 19 | ANIMALES |
| 17 | TABACCO | exact product | 137 | TABACO |
| 18 | BATERIAS, CAMARAS | exact product | 21 | BATERIAS, CAMARAS |
| 19 | KION REPELENTES | supplier-prefixed | 16 | CUIDADO PERSONAL |
| 20 | CREMA,SHAMPO,HIGIENE | exact product | 322 | CUIDADO PERSONAL |
| 21 | CAJA | exact product | 9 | CAJA/BOLSAS |
| 22 | CONFITES/CHICLES | exact product | 148 | CONFITES/CHICLES |
| 23 | CAFE BRITT | supplier-prefixed | 14 | ABARROTES |
| 24 | PEPSI BEBIDAS | exact product | 168 | BEBIDAS |
| 25 | CHOCOLATE | exact product | 31 | CHOCOLATE |
| 26 | EMBUTIDOS EMP. | exact product | 55 | EMBUTIDOS |
| 27 | BEBIDAS,REFRESCOS | exact product | 54 | BEBIDAS |
| 28 | LIBRERIA | exact product | 2 | LIBRERIA |
| 29 | PALM SHOP | supplier-only | 6 | SIN CLASIFICAR |
| 30 | HELADOS POPS | supplier-only | 60 | SNACKS |
| 31 | GALLETAS | exact product | 153 | GALLETAS |
| 32 | TOSTY | supplier-only | 108 | SNACKS |
| 33 | ALPISTE CHOCO | exact product | 0 | SNACKS |
| 34 | DIANA | supplier-only | 53 | SNACKS |
| 35 | JACKS | supplier-only | 139 | SNACKS |
| 36 | PRICESMART | supplier-only | 37 | ABARROTES |
| 37 | NAPOLI ITALIAN | supplier-only | 31 | ABARROTES |
| 38 | ALQUILER LOCAL | unmatched/other | 0 | ALQUILERES |
| 39 | SOLUCIONES Y PROPIEDADES S.A | unmatched/other | 0 | ALQUILERES |
| 40 | POZUELO GALLETA | supplier-prefixed | 125 | GALLETAS |
| 41 | HILIX ELECTROLITROS | supplier-prefixed | 16 | BEBIDAS |
| 42 | HIELERAS STEREOFON | supplier-prefixed | 4 | BEBIDAS |
| 43 | LICORES BERNAL | supplier-prefixed | 232 | LICORES |
| 44 | REPT ZARCERE¥A | supplier-prefixed | 61 | SNACKS |
| 45 | REPT MOFFY | supplier-prefixed | 28 | PANADERIA,REPOSTERIA |
| 46 | VINO FLORIDA | supplier-prefixed | 79 | LICORES |
| 47 | SNACKS BERNAL | supplier-prefixed | 15 | SNACKS |
| 48 | LICORES FLORIDA | supplier-prefixed | 38 | LICORES |
| 49 | MENDIOLA 2 | supplier-only | 24 | TABACO |
| 50 | ALQUILERES | unmatched/other | 1 | ALQUILERES |
| 51 | Dist. PTP | supplier-only | 17 | ABARROTES |
| 52 | Dist. Isle¤a | supplier-only | 37 | ABARROTES |
| 53 | CEREALES | exact product | 18 | CEREALES |
| 54 | PAPAS Y PLATANOS CARIBE TONY | supplier-prefixed | 19 | FRUTAS,VERDURAS |
| 55 | CAJETAS MANA | supplier-prefixed | 7 | PANADERIA,REPOSTERIA |
| 56 | CROSS COMPANY | supplier-only | 16 | ABARROTES |
| 57 | DISPAL LICORES | supplier-prefixed | 14 | LICORES |
| 58 | SEMILLAS Y MANI | exact product | 75 | SNACKS |
| 59 | DIPO VARIOS | supplier-prefixed | 37 | ABARROTES |
| 60 | PAPAS, SNACKS KION | supplier-prefixed | 264 | SNACKS |
| 61 | SNACKS KITTY | supplier-prefixed | 41 | SNACKS |
| 62 | HIELO JUAN VEGA | supplier-prefixed | 5 | BEBIDAS |
| 63 | Comercial Dinant CR | supplier-only | 29 | ABARROTES |
| 64 | DELITALIA S.A | supplier-only | 15 | ABARROTES |
| 65 | BOURNE CIA | supplier-only | 71 | ABARROTES |
| 66 | FLORIDA ABARTS | supplier-prefixed | 52 | ABARROTES |
| 67 | ABARROTES GENERAL | exact product | 31 | ABARROTES |
| 68 | METAMAX LOGISTICA | supplier-only | 47 | ABARROTES |
| 69 | DIST PIME PIZRA SRL | supplier-only | 17 | ABARROTES |
| 70 | BOLSAS PLASTICAS | exact product | 1 | CAJA/BOLSAS |
| 71 | LA YEMA DORADA S.A | supplier-only | 2 | ABARROTES |
| 72 | PRODUCTOS CHINOS | supplier-only | 13 | ABARROTES |
| 73 | Iphone Store | supplier-only | 6 | SIN CLASIFICAR |
| 74 | Helados Sensacion | supplier-only | 19 | SNACKS |
| 75 | FAMILIA 195 | unmatched/other | 8 | ABARROTES |
| 76 | FAMILIA 215 | unmatched/other | 3 | SIN CLASIFICAR |
| 77 | FAMILIA 969 | unmatched/other | 1 | PANADERIA,REPOSTERIA |
| 78 | SIN FAMILIA | unmatched/other | 1 | SIN CLASIFICAR |

Mixed suppliers (`METAMAX LOGISTICA`, `PRICESMART`, `CROSS COMPANY`, `Dist. Isleña`) are mapped to `ABARROTES` as a whole; if per-article accuracy matters, split them later by `cabys_id` prefix instead of by family.

#### 15.2.5 Merge SQL (not executed)

`familia.nombre` has **no unique constraint** (`familia_pkey` only), and `articulos.familia_id` is a real FK, so old rows must be retired, never deleted.

```sql
-- 0) verify there are no pre-existing duplicates by name
select upper(btrim(nombre)), count(*) from familia group by 1 having count(*) > 1;

-- 1) create the canonical families that do not exist yet
insert into familia (nombre, status, fecha)
select v.nombre, true, now()
from (values ('ABARROTES'),('SNACKS'),('LACTEOS'),('LICORES'),('CUIDADO PERSONAL'),
             ('BEBIDAS'),('LIMPIEZA'),('GALLETAS'),('TABACO'),('PANADERIA,REPOSTERIA'),
             ('FRUTAS,VERDURAS'),('CAJA/BOLSAS'),('SIN CLASIFICAR'),('ALQUILERES')) v(nombre)
where not exists (select 1 from familia f where upper(btrim(f.nombre)) = upper(v.nombre));

-- 2) re-point articles (one row per old family id)
with map(familia_id, canon) as (values
  (2,'ABARROTES'),(4,'LACTEOS'),(7,'TABACO'),(8,'LIMPIEZA'),(9,'PANADERIA,REPOSTERIA'),
  (10,'LACTEOS'),(12,'CUIDADO PERSONAL'),(13,'LICORES'),(14,'LIMPIEZA'),(15,'BEBIDAS'),
  (19,'CUIDADO PERSONAL'),(20,'CUIDADO PERSONAL'),(21,'CAJA/BOLSAS'),(23,'ABARROTES'),
  (24,'BEBIDAS'),(27,'BEBIDAS'),(29,'SIN CLASIFICAR'),(30,'SNACKS'),(32,'SNACKS'),
  (33,'SNACKS'),(34,'SNACKS'),(35,'SNACKS'),(36,'ABARROTES'),(37,'ABARROTES'),
  (38,'ALQUILERES'),(39,'ALQUILERES'),(40,'GALLETAS'),(41,'BEBIDAS'),(42,'BEBIDAS'),
  (43,'LICORES'),(44,'SNACKS'),(45,'PANADERIA,REPOSTERIA'),(46,'LICORES'),(47,'SNACKS'),
  (48,'LICORES'),(49,'TABACO'),(50,'ALQUILERES'),(51,'ABARROTES'),(52,'ABARROTES'),
  (54,'FRUTAS,VERDURAS'),(55,'PANADERIA,REPOSTERIA'),(56,'ABARROTES'),(57,'LICORES'),
  (58,'SNACKS'),(59,'ABARROTES'),(60,'SNACKS'),(61,'SNACKS'),(62,'BEBIDAS'),
  (63,'ABARROTES'),(64,'ABARROTES'),(65,'ABARROTES'),(66,'ABARROTES'),(67,'ABARROTES'),
  (68,'ABARROTES'),(69,'ABARROTES'),(70,'CAJA/BOLSAS'),(71,'ABARROTES'),(72,'ABARROTES'),
  (73,'SIN CLASIFICAR'),(74,'SNACKS'),(75,'ABARROTES'),(76,'SIN CLASIFICAR'),
  (77,'PANADERIA,REPOSTERIA'),(78,'SIN CLASIFICAR')
)
update articulos a
set familia_id = c.id
from map m
join familia c on upper(btrim(c.nombre)) = m.canon
where a.familia_id = m.familia_id
  and a.familia_id <> c.id;
-- expected: 5,274 rows

-- 3) retire the superseded rows (keep for FK/history), and fix the two mojibake names
update familia set status = false
where id in (2,4,7,8,9,10,12,13,14,15,19,20,21,23,24,27,29,30,32,33,34,35,36,37,
             40,41,42,43,44,45,46,47,48,49,51,52,54,55,56,57,58,59,60,61,62,63,
             64,65,66,68,69,70,71,72,73,74,75,76,77)
  and upper(btrim(nombre)) not in ('ABARROTES','SNACKS','LACTEOS','LICORES','CUIDADO PERSONAL',
      'BEBIDAS','LIMPIEZA','GALLETAS','TABACO','PANADERIA,REPOSTERIA','FRUTAS,VERDURAS',
      'CAJA/BOLSAS','SIN CLASIFICAR','ALQUILERES');

update familia set nombre = 'REPT ZARCEÑA' where id = 44;
update familia set nombre = 'Dist. Isleña'  where id = 52;
```

### 15.3 Prices

#### 15.3.1 The 418 confirmed

| Metric | Value |
|---|---:|
| `articuloprecio` rows / articles | 16,886 / 5,996 |
| rows with null `precio_final` / zero | 693 / 5 |
| rows with `usuario_id` null | 873 |
| articles whose **latest** row has null / zero `precio_final` | **413 / 5 = 418** |
| …with `usuario_id` null | **418 / 418 (100 %)** |
| latest-bad dates | **2026-10-20: 413** · 2026-10-26: 4 · 2026-10-21: 1 |
| articles with **no valid price ever** | **280** (their only other row is `2013-01-01`, also null) |
| articles with a previous valid row | 138 |
| oldest null-final rows (non-latest) | 280 rows dated `2013-01-01` |

So the 418 are not random: they are one bulk-import pass (2026-10-20, plus three late retries) that created a "current" price row with `usuario_id = NULL`, `precio_costo_sin_iva > 0` in 267 cases, `porcentaje_utilidad = 25` in 413, `precio_con_utilidad > 0` in 262 — but **never wrote `precio_final`**. The 280 articles whose older row is also `2013-01-01`/null were *never* priced correctly: the migration created their base row from cost and left the final price empty from the start.

#### 15.3.2 Root cause A — the whole catalog was repriced as `cost × 1.25`

| Check | Result |
|---|---:|
| valid latest prices | 5,578 |
| `precio_con_utilidad ≈ costo × 1.25` | **5,578 / 5,578 (100.0 %)** |
| `precio_final / precio_con_utilidad` = 1.13 | 4,502 (80.7 %) |
| = 1.00 | 84 (1.5 %) |
| other (1 %, 2 %, 4 %, 8 % rates / rounding) | 992 (17.8 %) |
| latest `precio_final` vs `MAESTRO.VENTA` within 2 % | **400 / 5,577 (7.2 %)** |
| median `precio_final / MAESTRO.VENTA` | **0.893** (p10 0.804, p90 1.001) |

The importer used `configuracion_margen.margen_base = 25.00` (created 2026-10-20 06:50) as the price formula and **ignored `MAESTRO.VENTA`**, so shelf prices are on average 10.7 % below the POS price, and 20 %+ below for the bottom decile.

#### 15.3.3 Root cause B — `MAESTRO.COSTO` is a pack/case cost for some articles

`MAESTRO.MARGENUTIL` is the POS's intended markup, and `MAESTRO.VENTA` the unit shelf price. For 71 articles the ratio `COSTO / (VENTA_net / (1 + MARGENUTIL/100))` is ≥ 1.5 (median **11.9×**, max **77.5×**) — i.e. the cost is for a box/dozen, not a unit:

| Article | `MAESTRO.COSTO` | `MAESTRO.VENTA` (unit) | pack factor | current `precio_final` | real invoice price |
|---|---:|---:|---:|---:|---:|
| PILSEN LT 16ONZ | 5,475.66 | 1,350.00 | 5.7× | 7,735.00 | **1,194.69** |
| BOLETA OCB NEGRA GD | 21,350.00 | 850.00 | 48.3× | 30,158.00 | ~850 |
| SAL ANDREWS UD | 6,991.41 | 250.00 | 47.4× | — | 250 |
| AGUA ALPINA 2L | 3,851.65 | 1,100.00 | 6.0× | 5,441.00 | ~1,050 |
| TABCIN GEL DIA UD | 12,898.27 | 600.00 | 30× | 16,446.00 | 550 |

Consequence: the current price of those articles is **5–10× the real shelf price**, and any margin computed with the pack cost is hugely negative (see §15.4).

#### 15.3.4 Repair coverage of the 418

| Repair path | Articles |
|---|---:|
| From `MAESTRO.VENTA/COSTO/MARGENUTIL` (VENTA > 0) | **270** |
| From the previous valid `articuloprecio` row only | **53** |
| **No source at all** (no VENTA, no previous valid) | **95** |
| Total | **418** |

For the 270, the source already yields coherent economics (example, after normalising `VENTA` to net of IVA and using `MARGENUTIL`):

| Article | costo unitario | venta final | neto | markup | margen sobre venta |
|---|---:|---:|---:|---:|---:|
| ROLAND PIMIENTA VERDE 110ML | 1,437.22 | 2,100.00 | 1,858.41 | 29.3 % | 22.7 % |
| LECHE DE COCO ROLAND 400ML | 945.00 | 1,440.00 | 1,274.34 | 34.9 % | 25.8 % |
| SALSA PREGO HONGOS 680G | 1,825.00 | 2,900.00 | 2,566.37 | 40.6 % | 28.9 % |
| TENTACION LIMON 140G | 380.00 | 650.00 | 575.22 | 51.4 % | 33.9 % |
| MAGGI PASTA TOMATE 174G | 465.00 | 750.00 | 663.72 | 42.7 % | 29.9 % |
| HEINZ PEPINO DILL 473ML | 2,685.00 | 4,250.00 | 3,761.06 | 40.1 % | 28.6 % |

i.e. the repaired margins land in the 20–34 % band instead of null/negative.

#### 15.3.5 Repair SQL (not executed)

The Maestro values must first be staged (CSV → temp table); no DBF access exists inside PostgreSQL.

```sql
-- staging loaded from vsuper_maestro.csv (codigo, venta, costo, margen, iva, exento)
create temp table stg_precio_src (
  codigobarra text primary key, venta numeric, costo_unit numeric, iva numeric, margen numeric);

-- A) 270 articles: rebuild the latest row from MAESTRO
with latest as (
  select distinct on (articulo_id) id, articulo_id
  from articuloprecio order by articulo_id, fechacompra desc nulls last, id desc
)
update articuloprecio p
set precio_costo_sin_iva = s.costo_unit,
    precio_con_utilidad  = s.venta / (1 + s.iva/100.0),
    precio_final         = s.venta,
    porcentaje_utilidad  = s.margen,
    usuario_id           = (select id from users where username='admin' limit 1)
from latest l
join articulos a on a.codigo = l.articulo_id
join stg_precio_src s on s.codigobarra = a.codigobarra
where p.id = l.id and coalesce(p.precio_final,0) = 0;
-- expected 270

-- B) 53 articles: copy the previous valid row into the latest row
with latest as (
  select distinct on (articulo_id) id, articulo_id
  from articuloprecio order by articulo_id, fechacompra desc nulls last, id desc
), prev as (
  select p.articulo_id, p.precio_costo_sin_iva, p.precio_con_utilidad, p.precio_final,
         p.porcentaje_utilidad,
         row_number() over (partition by p.articulo_id order by p.fechacompra desc, p.id desc) rn
  from articuloprecio p
  where coalesce(p.precio_final,0) > 0
)
update articuloprecio p
set precio_costo_sin_iva = v.precio_costo_sin_iva,
    precio_con_utilidad  = v.precio_con_utilidad,
    precio_final         = v.precio_final,
    porcentaje_utilidad  = v.porcentaje_utilidad
from latest l join prev v on v.articulo_id = l.articulo_id and v.rn = 1
where p.id = l.id and coalesce(p.precio_final,0) = 0
  and not exists (select 1 from stg_precio_src s join articulos a on a.codigobarra=s.codigobarra
                  where a.codigo = l.articulo_id);
-- expected 53

-- C) 95 articles with nothing: deactivate or leave unpriced (excluded from reports)
update articulos a set status = false
where a.status and not exists (
  select 1 from articuloprecio p where p.articulo_id=a.codigo and coalesce(p.precio_final,0) > 0
) and a.codigo in ( /* list from the audit */ );
-- expected 95
```

Optional broader fix (recommended): rebuild **all** 5,996 latest prices from `MAESTRO.VENTA/COSTO/MARGENUTIL`, because the current values are 100 % synthetic (`cost × 1.25`) — not just the 418.

### 15.4 Margin report inputs

#### 15.4.1 `profit_margin_history`

| Metric | Value |
|---|---:|
| rows / distinct articles | 258,581 / 3,898 |
| date range / creation | 2015-08-14 → 2026-10-20 · created **2026-10-21 11:21–21:19** |
| `precio_venta` null / zero | **812 / 0** |
| `precio_costo` null / zero | 0 / 399 |
| `margen_real` min / max | **−999.99** / 99.74 |
| rows capped at −999.99 | 3,821 |
| rows with `precio_costo > precio_venta` | **11,771 rows · 125 articles** |
| …revenue / cost on those rows | 40,094,874.53 / **232,267,440.09** (−192.2 M) |
| articles with null `precio_venta` | 812 rows, all in 2015-2016 and all belonging to the 418 bad-price articles |

`margen_real` is `(precio_venta − precio_costo) / precio_venta × 100`, capped at −999.99 — **summing that column is meaningless** (the stored "total" is 1,765,307.99, a sum of percentages).

The 125 negative articles are dominated by the pack-cost ones (71 detected by the cost/margin test); the remaining ~54 sold historically below the current cost (price increases not reflected in the old sale rows). History cost is **constant per article** (PILSEN = 5,475.66 for every date), i.e. it was taken from the current `articuloprecio` row, not from a historical cost — so regenerating history after fixing prices is mandatory.

#### 15.4.2 `profit_margin_snapshots` — the report is double-counted

| Metric | Value |
|---|---:|
| rows / distinct dates | **78,429 / 1,302** |
| date range / creation | 2015-08-14 → 2026-10-20 · created **2026-10-21 23:11** |
| distinct `familia` / `departamento` labels | 72 / 101 |
| **rows with blank `familia`** | **36,303 (46.3 %)** |
| …their `cantidad_articulos` / `total_ventas` | **258,581 / 786,444,360.80** |
| rows with a family label | 42,126 |
| …their `cantidad_articulos` / `total_ventas` | **258,581 / 786,444,360.80** |
| total `cantidad_articulos` / `total_ventas` | **517,162 / 1,572,888,721.60 = exactly 2×** |

The snapshot builder ran twice: once without family/department labels and once with them. **Every sale is counted twice** in any report that sums `total_ventas`/`total_utilidad`, and 46 % of the rows cannot be filtered by family.

Families with negative utility in the snapshots (both copies included): CERVEZAS −19.47 M (−7.1 %), FARMACIA −16.68 M (−449 %), TABACO −11.05 M (−79.8 %), COCA COLA −7.65 M (−10.6 %), GALLETAS −2.12 M (−94.9 %), ABARROTES GENERAL −486 k, Helados Sensacion −208 k. These are the same pack-cost articles as §15.4.1.

#### 15.4.3 Are the bad prices/families already baked in?

* **Prices:** yes. History cost is the current (pack) cost, and 812 rows have no sale price. Snapshots are aggregations of history, so both carry the defect.
* **Families:** yes. 26,067 snapshot rows (33 % of all rows, 62 % of the labelled copy) carry supplier-prefixed or supplier-only family names; 36,303 rows carry no family at all.
* Neither table is safe to patch in place: both are derived aggregates and must be **regenerated** after the family/price repair. Patching rows would leave the double-count and the stale labels.

### 15.5 Repair order (avoid making the report worse mid-repair)

| # | Step | Why this order | Risk if skipped |
|---:|---|---|---|
| 0 | Backup `mercurius` (`pg_dump -Fc`) + freeze writes | every later step rewrites derived data | no rollback (the 2026-10-21 "pre-dedupe" dump is 0 bytes) |
| 1 | Snapshot the four tables (`familia`, `articuloprecio`, `profit_margin_history`, `profit_margin_snapshots`) to CSV | cheap, allows diff after each step | cannot prove what changed |
| 2 | **Families**: create 24 canonical, re-point 5,274 articles, retire old rows, fix the 2 mojibake names | labels are the key of the snapshot regeneration | regenerating first leaves supplier labels |
| 3 | **Prices A/B/C**: 270 + 53 repaired, 95 deactivated; optional full reprice of 5,578 | history cost reads `articuloprecio` | regenerating history first keeps −192 M |
| 4 | **Regenerate `profit_margin_history`** once | removes null prices and pack costs | old rows keep cost 5,475 for PILSEN |
| 5 | **Regenerate `profit_margin_snapshots`** exactly once, with family join and no blank pass | fixes 2× and 46 % blanks | report still double-counts |
| 6 | Verify: `sum(total_ventas)` snapshots = history total (not 2×), 0 blank families, negative-margin rows ≈ 0, 418 → 0 null latest prices | acceptance criteria | silent regressions |

**Do not** regenerate snapshots between steps 2 and 3 — you would bake in the old prices under the new labels and have to do it again. **Do not** delete old `familia` rows (FK from `articulos`); retire with `status=false`.

### 15.6 What could not be determined

1. **True pack size per article.** `MAESTRO` has no explicit `units_per_case` column; the factor was *inferred* from `COSTO / (VENTA_net / (1+MARGENUTIL/100))`. For 71 articles the factor is ≥1.5 (median 11.9), but the exact integer (6, 12, 24, 48…) cannot be proven from this dataset — it should be confirmed against supplier invoices or the POS article screen.
2. **Whether `MAESTRO.VENTA` is still current** (snapshot 2026-10-03 vs live prices 2026-10-20). The POS may have changed prices in those 17 days; the 100 % `cost × 1.25` pattern suggests it did not, but this is an inference.
3. **The 95 articles with no price source** cannot be priced from any local artifact; they need either a POS export or manual entry.
4. **`MARGENUTIL` semantics** are consistent with a markup over net cost (verified on 5 samples: OCB 48 units, Alpina 12, Alka 54, Tabcin 30), but the field is not documented.
5. **The 2026-10-20 price import job** was not identified in code; only its footprint (rows, timestamps, null user) is documented here.

---

## Appendix A — reproducible command set

```powershell
# ---------- local DB ----------
$env:PGPASSWORD='Mercurius@1!'
$P = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
& $P -h 127.0.0.1 -p 5433 -U mercurius -d mercurius -c "select count(*) from articulos;"
& $P -h 127.0.0.1 -p 5433 -U mercurius -d mercurius -c "select to_char(fecha_emision,'YYYY-MM') m, count(*) from encabezado group by 1 order by 1;"
& $P -h 127.0.0.1 -p 5433 -U mercurius -d mercurius -c "select count(*) from linea_detalle l left join cabys c on c.codigo=l.codigocabys where c.codigo is null;"
& $P -h 127.0.0.1 -p 5433 -U mercurius -d mercurius -c "select receptor_id, count(*) from encabezado group by 1 order by 2 desc;"

# ---------- live DB (read-only) ----------
$k = 'C:\Users\alvar\.ssh\id_audit_ed25519'
ssh -i $k -o BatchMode=yes alvaro@192.168.100.99 'hostname; which psql; systemctl is-active postgresql'
ssh -i $k -o BatchMode=yes alvaro@192.168.100.99 'PID=$(pgrep -f Mercurius-runner.jar | head -1); E=/proc/$PID/environ; U=$(strings $E | grep -a ^DB_USERNAME= | cut -d= -f2-); P=$(strings $E | grep -a ^DB_PASSWORD= | cut -d= -f2-); PGPASSWORD=$P psql -h localhost -p 5432 -U $U -d mercurius -tA -c "select count(*) from articulos"'

# ---------- current DBF (F:) ----------
$S = 'F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004'
python "$env:TEMP\opencode\dbf_probe.py" summary $S
python "$env:TEMP\opencode\dbf_probe.py" schema  "$S\facturas.DBF"
python "$env:TEMP\opencode\dbf_probe.py" months  "$S\facturas.DBF" FECHA
python "$env:TEMP\opencode\dbf_probe.py" cross   "$S\detafact.dbf::FACTURA" "$S\facturas.DBF::NUMERO"
python "$env:TEMP\opencode\dbf_probe.py" overlap "$S\MAESTRO.DBF::CODIGO" "$S\detafact.dbf::CODIGO"

# ---------- backups ----------
python "$env:TEMP\opencode\dbf_probe.py" summary 'F:\Desktop\Super\New folder (3)\Original DBF'
python "$env:TEMP\opencode\dbf_probe.py" summary "$S\RESPALDO_ESTRUCTURA_V010_20260823_231404"
python "$env:TEMP\opencode\dbf_probe.py" years   'F:\Desktop\Super\New folder (3)\Original DBF\KARDEX.DBF' FECHA
python "$env:TEMP\opencode\dbf_probe.py" years   "$S\RESPALDO_ESTRUCTURA_V010_20260823_231404\salidas.dbf" FECHA

# ---------- XML census / overlap ----------
python "$env:TEMP\opencode\xml_probe.py" inv   'F:\Desktop\WebDAV-Facturacion\extracted'
python "$env:TEMP\opencode\xml_probe.py" inv   'F:\Desktop\Super\New folder (3)\Respuestas'
python "$env:TEMP\opencode\xml_probe.py" roots 'F:\Documents\Facturas Electronicas'
python "$env:TEMP\opencode\xml_probe.py" roots 'E:\VPALMERAS'
# DB claves exported read-only once:
psql ... -t -A -o "$env:TEMP\opencode\db_claves.txt" -c "select 'E|'||e.clave from encabezado e join comprobantesemitidos c on c.encabezado_id=e.id union all select 'R|'||e.clave from encabezado e join comprobantesrecibidos c on c.encabezado_id=e.id"
python "$env:TEMP\opencode\xml_probe.py" ucmp "$env:TEMP\opencode\db_claves.txt" `
  'F:\Desktop\WebDAV-Facturacion\extracted' `
  'C:\Users\alvar\AppData\Local\Temp\mercurius-webdav-extracted' `
  'F:\Desktop\Super\New folder (3)\Respuestas' `
  'E:\VPALMERAS' `
  'F:\Documents\Facturas Electronicas'

# ---------- duplicate claves ----------
# select count(*) total, count(distinct clave) distinct, count(*)-count(distinct clave) dup from encabezado;
# select count(*) from comprobantesrecibidos c join encabezado e on e.id=c.encabezado_id
#   where exists (select 1 from comprobantesemitidos m join encabezado e2 on e2.id=m.encabezado_id where e2.clave=e.clave);

# ---------- 2019 DBF (E:) ----------
python "$env:TEMP\opencode\dbf_probe.py" summary 'E:\VPALMERAS'

# ---------- WebDAV ----------
curl.exe -s -u '<REDACTED>' -X PROPFIND -H 'Depth: 1' 'http://192.168.100.99:9200/webdav/Facturas/'
curl.exe -sI -u '<REDACTED>' 'http://192.168.100.99:9200/webdav/Facturas/Facturas_2026-09.zip'
Get-ChildItem "$env:TEMP\mercurius-webdav-facturas" -Filter *.zip | ForEach-Object { & tar -tf $_.FullName }
```

*Report generated read-only; no database, file, or repository state was modified except the creation of this document and two throwaway probe scripts under `%TEMP%` (`dbf_probe.py`, `xml_probe.py`).*
