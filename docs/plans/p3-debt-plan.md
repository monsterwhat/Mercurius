# P3 — Debt cleanup plan

Status: **planned, not implemented**. This document is the executable spec for a cheaper agent.
Repo: `F:\Documents\GitHub\Mercurius` (Quarkus 3.36.2, Java 25, Qute + htmx, PostgreSQL/Hibernate, no Panache).

## How the numbers in this plan were produced

| Claim | Method |
| --- | --- |
| 378 classes under `Models`, 50 under `Models/DTO` | `Get-ChildItem app/src/main/java/Models -Recurse -Filter *.java` |
| 94 `.listAll(` text matches | `Select-String -Pattern '\.listAll\('` over `app/src/main/java` |
| 72 real `.listAll()` call sites | the 94 minus 19 log-message strings, 2 javadoc mentions (`OrdenCompraResource:205`, `Api/Mercatus/OrderController:34`) and 1 comment (`FacturasRecibidasResource:1520`) |
| 309 `Response.ok(`, 143 not visibly wrapping `ApiResponse`/`PagedResponse` on the same or next 2 lines | line-level regex over `app/src/main/java` |
| 73 `BAD_REQUEST` sites | line-level regex `Response.status\(Response.Status.BAD_REQUEST\)` |
| Row-count estimates | domain estimates for a supermarket POS (marked "est."), plus the one hard number in the codebase: **1,160 articles** (`Reportes/PronosticosResource.java:519`) and CABYS is the national catalog (~6k rows) |

> **Line numbers are a snapshot of the working tree this plan was written against (`f139ad2` + uncommitted P1 work).** At planning time the worktree already had uncommitted changes from the **P1 SQL-paging effort** in `Services/ArticuloPrecioService.java` (+89), `Services/ArticulosService.java` (+27), `Services/GService.java`, `Reportes/PreciosResource.java` (−91 net), `Reportes/HistoricalDateSalesResource.java` and `Reportes/FacturasReportesResource.java`. Before editing any file, re-locate the cited call with `Select-String -Pattern '\.listAll\(' <file>` (and for item 4/5, grep the symbol) rather than trusting the offset. This is also why §3.2 excludes the P1 report resources entirely.

## Blast radius — do not touch

- `Controllers/Api/Mercatus/**`, `Controllers/Api/Marketplace/**`, `filters/MarketplaceJwtFilter`, CORS config, `PublicApiJwtFilter` (all Mercatus/marketplace work is out of scope).
- The 12 report resources under `Controllers/Api/App/Reportes/**` that call `listAll()` — **owned by P1** (list in §3.2). Read their lines for context, never edit them.
- `db/migration/**`, `application.properties` auth/permission blocks, `RootRedirectResource`, `RootLoginRedirectRoute`.
- No builds, no test runs, no formatting sweeps: this document is the only write.

---

# 1. Orphan 404 page

## 1.1 Finding

`app/src/main/resources/templates/pages/error/404.html` (37 lines) is **completely unreferenced**. Evidence:

- No Java class injects it: `git grep "error/404"` matches only the template's own header comment (`404.html:2`) and documentation prose (`docs/ui-kit.md`, `.impeccable/critique/...`).
- No `quarkus.qute.error-page` / `quarkus.http.error-page` config exists; the only `error-page` property is `application.properties:200`, the **form-login** error page (`/Mercurius/login?error`).
- Both dead-link handlers redirect instead of rendering:
  - `Controllers/FallbackResource.java:45` (`@Path("/{remaining:(?!static/|resources/|q/)...}")`), authenticated branch at `:90` → `303 /app`, anonymous at `:84` → `303 /login`.
  - `Controllers/NotFoundExceptionMapper.java:45-57`, same split.
- `NotFoundExceptionMapper`'s javadoc (`:15-26`) and `README.md:192` both state the redirect is the **intended contract**, and `README.md:192` lists it under "**Corregido en esta sesión**" — i.e. the 303 was a deliberate fix, not an accident.
- Two test methods pin it: `RoutingComprehensiveTest.java:170` (`unknownPageAuthenticatedToApp`) and `:177` (`unknownNestedPageAuthenticatedToApp`) both assert `302|303` + `Location: /app`.
- The template's own header (`404.html:2-3`) claims *"NotFoundExceptionMapper + FallbackResource for authenticated browser hits"* — that statement is false, which is the actual defect.
- The only styles for it live in `web/mercurius.scss` §34, lines **1769-1813** (`.error-page`, `-code`, `-text`, `-path` + the 480px media query). Nothing else in `templates/**` uses `.error-page` (only `404.html`).

## 1.2 Decision: **delete the template** (do not wire it)

Rationale:

1. **The redirect is a documented, intentional product decision with a stated reason** (`NotFoundExceptionMapper:21-25`: "a stale link typed by a signed-in operator belongs on the dashboard instead of a dead page"). A P3 debt cleanup must not silently reverse a contract that a previous session deliberately established and pinned in tests.
2. **The redirect surface is the known-fragile area of the app.** `docs/ui-kit.md:47` explicitly defers it ("README flags the auth/redirect area as the fragile part of the suite"), and `FallbackResource:22-43` documents a real production incident where the catch-all answered 303 for static assets (and a second bug where a `/Mercurius`-prefixed target looped). Wiring a Qute render into an `ExceptionMapper` adds a new failure mode (template/CDI failure inside an exception handler) for zero functional gain.
3. **Deleting is reversible and removes a lie.** The file is dead weight whose comment misleads readers; deleting it plus its CSS leaves the codebase honest in one commit. Wiring it would instead require editing 3 tests, README, the mapper, the fallback resource, and the template header — strictly more churn and more risk.
4. If the owner ever wants a real 404 page, Appendix A contains the full wiring spec, so nothing is lost by deleting now.

## 1.3 Steps

1. Delete `app/src/main/resources/templates/pages/error/404.html`. Confirm the directory is then empty and remove `templates/pages/error/` if so.
2. Delete `app/src/main/resources/web/mercurius.scss` lines **1769-1813** — from the `// =====` banner directly above `// 34. Error page (404)` through the closing `}` of `.error-page .buttons .button` plus the trailing blank line, stopping before the section-35 banner at `:1814`. Keep the rest of the file byte-identical (do **not** renumber sections 35+; that is churn).
3. In `Controllers/NotFoundExceptionMapper.java`, append one line to the class javadoc (after `:25`):
   ` * <p>No 404 template is rendered. This is the contract: do not add one without changing these javadoc, README.md, and RoutingComprehensiveTest.</p>`
4. No other changes. Do **not** touch `FallbackResource`, `RootRedirectResource`, tests, or `README.md:192` (it stays accurate).

## 1.4 Verification

```powershell
git grep -n "error/404"            # expect: no matches
git grep -n "error-page"           # expect: no matches in app/src/main
git grep -n "pages/error"          # expect: no matches
mvn -q -pl app test -Dtest=RoutingComprehensiveTest,StaticAssetContractTest,RootRedirectAppRouteTest
```
The three test classes must stay green **unchanged**. Suite caveat (README): the test DB (`mercurius_test`, port 8081) must not be shared with a parallel build.

## 1.5 Risk / effort

Risk: **very low**. Resource + CSS deletion, one javadoc line. Only failure mode is deleting the wrong SCSS span — verify with `git diff --stat` (expected ≈ 37 lines template + 45 lines SCSS, 0 Java deletions).
Effort: **S (~30 min)**.
Rollback: `git revert`.

---

# 2. Stale TODO/FIXME hygiene

## 2.1 Finding — three stale markers, all verifiable now

| # | File | Lines | Claim | Reality |
| --- | --- | --- | --- | --- |
| a | `Controllers/Api/App/Reportes/EstacionalidadReportesResource.java` | 40-43 | "the four `p:chart` visualizations are intentionally NOT ported yet… until then this page renders the same aggregates as tables" | The page renders four `_kit/chart-card` Chart.js charts: `templates/pages/reportes/estacionalidad.html:53,59,65,71` + JS at `:175-213` via `window.MercuriusCharts` |
| b | `templates/pages/reportes/estacionalidad.html` | 4-7 and 43-45 | same claim ("NOT ported… replace these tables with Chart.js… once chart infrastructure lands") | Charts exist **above** the tables (same file); tables are kept intentionally as the exact-figure view |
| c | `templates/pages/reportes/clientes.html` | 4-7 | "no T17 dataset yet (ExportResource only ships articulos/inventario/stock-alerts/profit-margins) — export wiring TODO" | `ExportResource.java:244` ships `case "clientes"`, and the page wires the form at `clientes.html:85-90` (`dataset=clientes`) |

Extra defect in (a): the marker is written with Qute comment delimiters `{! !}` **inside a Java javadoc**, which is leftover paste from the template and renders as noise in IDE docs.

## 2.2 Steps

1. **`EstacionalidadReportesResource.java:40-43`** — replace the whole paragraph with:

```java
 * <p>The four legacy {@code p:chart} visualizations are ported as Chart.js
 * cards over the same aggregates (monthly trend, day of week, department and
 * family), rendered by {@code pages/reportes/estacionalidad.html} through the
 * {@code _kit/chart-card} helper. The server-side tables stay below the charts
 * because they carry the exact figures the charts only summarize.</p>
```

2. **`estacionalidad.html:4-7`** — replace with:

```
  Replaces secured/pages/Reportes/Estacionalidad/index.xhtml over
  SeasonalityService. The four legacy p:chart visualizations are ported as
  Chart.js cards (window.MercuriusCharts, _kit/chart-card) above the exact
  figure tables.
```

3. **`estacionalidad.html:43-45`** — delete the `{! TODO(T29): ... !}` block entirely (the code below it already implements the described charts).
4. **`clientes.html:4-7`** — replace with:

```
  Replaces secured/pages/Clientes/Reportes/index.xhtml over ClientService
  aggregates. The legacy dataExporter buttons are wired to the shared export
  endpoint (/api/app/export, dataset=clientes — see ExportResource).
```
5. Leave `T29` mentions that are **accurate** (`Api/App/DashboardResource.java:37,41`, `templates/pages/dashboard/index.html:2`, `estacionalidad.html` after step 2). Do not rewrite the plan history.

## 2.3 Grep-based audit (run this, do not trust a plain `TODO` grep)

`Select-String`/`grep -i` **must not** be used unqualified: Spanish `todo`/`todos`/`método` and the constant `METODOS_PAGO` (`PosResource.java:109`) all collide. The correct recipe:

```powershell
# Java markers — case-sensitive, whole word (`\b` is what excludes METODOS_PAGO)
Get-ChildItem app/src/main/java -Recurse -Filter *.java |
  Select-String -Pattern '\b(TODO|FIXME|XXX|HACK)\b' -CaseSensitive |
  ForEach-Object { "$($_.Path.Replace('F:\Documents\GitHub\Mercurius\','')):$($_.LineNumber): $($_.Line.Trim())" }

# Templates (Qute comments only)
Get-ChildItem app/src/main/resources/templates -Recurse -Filter *.html |
  Select-String -Pattern '\b(TODO|FIXME|XXX|HACK)\b' -CaseSensitive |
  ForEach-Object { "$($_.Path.Replace('F:\Documents\GitHub\Mercurius\','')):$($_.LineNumber): $($_.Line.Trim())" }

# optional: JS/SCSS
Get-ChildItem app/src/main/resources/web -Recurse -Include *.js,*.scss |
  Select-String -Pattern '\b(TODO|FIXME|XXX|HACK)\b' -CaseSensitive
```

Current result: **3 hits** (the three above). After the fix: **0**. Record the count in the commit message so a future drift is visible. Rule for the reviewer: a marker is only "real" if it is `TODO(`/`TODO:`/`FIXME:` uppercase; anything inside prose (`todo el catálogo`) is not a marker and must be left alone.

## 2.4 Verification

- `git grep -nE "\b(TODO|FIXME|XXX|HACK)\b" -- app/src/main` → 0 matches.
- `mvn -q -pl app test -Dtest=EstacionalidadReportesPageTest,ClientesReportesPageTest` — both classes exist (`app/src/test/java/Controllers/Api/App/Reportes/`). Content-only changes, so any failure means a typo in the javadoc/template contract, not behaviour.
- Manual read: `estacionalidad.html` must still contain the four `{#include _kit/chart-card ...}` lines and the `window.MercuriusCharts` block.

## 2.5 Risk / effort

Risk: **none to runtime** (comments only; step 3 deletes a Qute comment, not markup).
Effort: **S (~20 min)**.

---

# 3. `listAll()` audit — 72 real call sites (94 text matches)

## 3.1 What the underlying query does

Every audited call bottoms out in `Services/GService.java:125-130`:

```java
TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
return query.getResultList();   // no LIMIT, no pagination
```

`listAll()` (`:84`) additionally **swallows** `PersistenceException` into `Collections.emptyList()` (documented at `:63-82`), so a failed full scan renders as "no rows" rather than an error. `listAllOrThrow()` (`:108`) is the fail-fast twin.

Two overrides matter for the verdicts:
- `Services/OrdenCompraService.java:200-219`: `SELECT DISTINCT o ... LEFT JOIN FETCH o.detalles d, d.articulo, o.proveedor, o.usuario WHERE o.status = true ORDER BY o.fechaOrden DESC` — a full order book **with** two collection/association fetches per row.
- `Services/CabysService` uses the inherited base query; `Services/ArticuloPrecioService.java:30-33` overrides `listAll()` with an equivalent unbounded `SELECT a FROM ArticuloPrecio a`.

Size legend (estimates for a supermarket POS; "est." = inference from the domain, not measured):
`P` = 1,160 active articles (hard number, `Reportes/PronosticosResource.java:519`); `C` ≈ 2-5k clients; `I` = invoices, unbounded (~1k tickets/day ⇒ ~365k/yr); `L` = purchase lots, grows with every purchase line.

## 3.2 Owned by P1 — do not change (15 sites / 12 files)

| File | Lines |
| --- | --- |
| `Reportes/AlertasStockResource.java` | 169, 176 |
| `Reportes/ClientesReportesResource.java` | 72 |
| `Reportes/CorreosReportesResource.java` | 72 |
| `Reportes/FechasResource.java` | 86 |
| `Reportes/HistoricalDateSalesResource.java` | 73 |
| `Reportes/LoyaltyReportesResource.java` | 94 |
| `Reportes/MargenesReportesResource.java` | 171, 172 |
| `Reportes/PreciosResource.java` | 71 |
| `Reportes/ResumenResource.java` | 98, 99 |
| `Reportes/TributacionPagesResource.java` | 471 |
| `Reportes/UsuariosReportesResource.java` | 71 |
| `Reportes/VentasResource.java` | 93 |

Excluded as Mercatus (2 sites): `Api/Mercatus/FamiliesController.java:45`, `Api/Mercatus/PromotionsController.java:46`.

> The P1 brief says "14 report resources"; **only 12 of the 24 files under `Reportes/` actually call `listAll()`** (the two helpers, `ReportePageSupport`/`Tablas`, and 10 resources do not). The list above is derived from code, not from the P1 document, which was not available. Conservative rule for the executing agent: **do not edit anything under `Controllers/Api/App/Reportes/**` while P1 is in flight**, whether or not it appears above.

In scope below: **55 sites**.

## 3.3 In-scope table

Verdicts: **safe** = bounded small table or an output-bounded slice; **needs paging** = the whole result set is materialised to render one page/report; **needs limit** = an unbounded scan feeding a count/search/export/fragment where a SQL aggregate or bounded query is the fix.

| file:line | purpose | max expected rows | verdict |
| --- | --- | --- | --- |
| `Controllers/SettingsController.java:118` | `@PostConstruct` settings list for the legacy settings page | 1 (`ConfiguracionAplicacion`) | safe |
| `Controllers/Settings/SettingsDirController.java:81` | same, settings-dir bean | 1 | safe |
| `Controllers/TipoCambioController.java:44` | legacy bean's exchange-rate list (loads all, uses latest) | ~3.6k (1/day × 10 yr) | needs limit |
| `Api/App/ArticuloResource.java:321` | promotions JSON list, in-memory filter | < 200 | safe |
| `Api/App/ArticuloResource.java:1501` | promotions tab table model | < 200 | safe |
| `Api/App/ArticuloResource.java:1584` | departamentos dropdown (revision form) | < 200 | safe |
| `Api/App/ArticuloResource.java:1585` | familias dropdown (revision form) | < 300 | safe |
| `Api/App/ArticuloResource.java:1601` | departamentos dropdown (article form) | < 200 | safe |
| `Api/App/ArticuloResource.java:1602` | familias dropdown (article form) | < 300 | safe |
| `Api/App/CabysPagesResource.java:102` | CABYS catalog page 1 (then in-memory `subList`) | ~6k (national catalog) | **needs paging** |
| `Api/App/CabysResource.java:128` | CABYS API search: full scan + in-memory filter | ~6k | **needs paging** (SQL `LIKE`) |
| `Api/App/CabysResource.java:444` | CABYS HTML table model, filtered | ~6k | **needs paging** |
| `Api/App/CabysResource.java:448` | CABYS HTML table model, unfiltered | ~6k | **needs paging** |
| `Api/App/CategoriaResource.java:207` | familias API list (filter+sort+page in memory) | < 300 | safe |
| `Api/App/CategoriaResource.java:240` | departamentos API list | < 200 | safe |
| `Api/App/CategoriaResource.java:632` | supplier-metrics JSON (legacy métricas page) | < 200 | safe |
| `Api/App/CategoriaResource.java:889` | supplier-metrics rows on full page | < 200 | safe |
| `Api/App/CategoriaResource.java:953` | familias table model | < 300 | safe |
| `Api/App/CategoriaResource.java:954` | departamentos table model | < 200 | safe |
| `Api/App/CategoriasPagesResource.java:129` | categorias page-1 table (familias) | < 300 | safe |
| `Api/App/CategoriasPagesResource.java:130` | categorias page-1 table (departamentos) | < 200 | safe |
| `Api/App/CategoriasPagesResource.java:176` | metrics rows on page | < 200 | safe |
| `Api/App/ClientsResource.java:487` | `/zonas` distinct zone codes + counts (full client scan) | C ≈ 5k | needs limit (`GROUP BY zoneCode`) |
| `Api/App/ClientsResource.java:852` | header counters (total/activos/inactivos) on full page | C ≈ 5k | needs limit (`count` queries) |
| `Api/App/ClientsResource.java:884` | clients table model, paged **in memory** | C ≈ 5k | **needs paging** |
| `Api/App/DevolucionesPagesResource.java:208` | credit-note history rows on page load | unbounded (`NotaCredito`) | **needs paging** |
| `Api/App/DevolucionesResource.java:674` | invoice search **by client name**: full scan of all emitted invoices | I, unbounded | **needs paging** (SQL query) |
| `Api/App/DevolucionesResource.java:685` | invoice search **by consecutive**: full scan of all emitted invoices | I, unbounded | **needs paging** (SQL query) |
| `Api/App/DevolucionesResource.java:1522` | credit-note history **OOB fragment after every successful mutation** | unbounded | **needs limit** (`LIMIT 20`, newest first) |
| `Api/App/EmailTemplateResource.java:182` | templates API filter, in-memory | < 50 | safe |
| `Api/App/EmailTemplateResource.java:717` | templates table model | < 50 | safe |
| `Api/App/ExportResource.java:238` | Excel export: scheduled reports | < 50 | safe |
| `Api/App/ExportResource.java:241` | Excel export: prices (`ArticuloPrecio`) | P × price rows ≈ 10k+ | needs limit (cell cap; see risk note) |
| `Api/App/ExportResource.java:244` | Excel export: clients | C ≈ 5k | needs limit |
| `Api/App/ExportResource.java:250` | Excel export: users | < 100 | safe |
| `Api/App/ExportResource.java:382` | Excel export: loyalty (reuses client list) | C ≈ 5k | needs limit |
| `Api/App/ExportResource.java:441` | Excel export: familias | < 300 | safe |
| `Api/App/FacturasRecibidasResource.java:1526` | locate **one** received invoice by stream-scanning the whole table | unbounded | needs limit (find by id) |
| `Api/App/LoteResource.java:140` | lotes API list, paged in memory | L, 10^5+ | **needs paging** |
| `Api/App/OrdenCompraResource.java:235` | ordenes API list, paged in memory | 10^4-10^5 | **needs paging** |
| `Api/App/OrdenCompraResource.java:1338` | status counters for the page header (5 stream passes) | 10^4-10^5 | needs limit (`GROUP BY estado`) |
| `Api/App/OrdenCompraResource.java:1395` | proveedores dropdown on the order form | < 200 | safe |
| `Api/App/OrdenCompraResource.java:1421` | ordenes table model | 10^4-10^5 | **needs paging** |
| `Api/App/ReporteProgramadoResource.java:810` | scheduled-reports table model | < 50 | safe |
| `Api/App/TipoCambioPageResource.java:165` | history modal loads **all** rates, then slices last `HISTORIAL_LIMIT` (=20) | ~3.6k | needs limit (`ORDER BY fecha DESC LIMIT 20`) |
| `Api/App/TributacionResource.java:507` | "mensajes receptor" rows: loads all received invoices, then caps to `MENSAJES_LIMIT` (=100) | unbounded | needs limit (SQL) |
| `Api/App/UsersResource.java:884` | user counters | < 100 | safe |
| `Api/App/UsersResource.java:911` | users table model | < 100 | safe |
| `Controllers/Correos/CorreosScheduler.java:30` | nightly job: active scheduled reports | < 50 | safe |
| `Services/PublicInvoiceService.java:124` | received invoices when `page == null` | unbounded | needs paging (default page) |
| `Services/PublicInvoiceService.java:241` | suppliers/clients when `page == null` | C ≈ 5k | needs limit |
| `Services/PublicInvoiceService.java:354` | cash-register rows when `page == null` | ~3.6k (daily) | needs limit |
| `Services/PublicInvoiceService.java:378` | credit notes — **no paging path at all** | unbounded | **needs paging** |
| `Services/PublicInvoiceService.java:402` | profit margins: loads **all** articles to build the page | P = 1,160 | needs limit (SQL / page first) |
| `Services/PublicInvoiceService.java:464` | sales-by-category: loads all articles for a lookup map | P = 1,160 | needs limit (SQL join) |

Totals: **safe 27**, **needs paging 13**, **needs limit 15** (55 in-scope sites).

## 3.4 Steps (ordered by user-visible risk, smallest diff first)

Each step is independent; ship one per commit.

1. **`DevolucionesResource:674/685` + `:1522`** — replace the three full scans. Add to the service (not the resource):
   - `ComprobantesEmitidosService.findByIdContainingConsecutivo(String, Page)` using `WHERE e.encabezado.numeroConsecutivo LIKE :q` with `setMaxResults`;
   - `... findByClientNameLike(String, Page)` joining `encabezado.receptor`;
   - `NotaCreditoService.listRecent(int limit)` = `ORDER BY fecha DESC`, `setMaxResults(limit)`.
   Keep the existing response DTO/map shape so `DevolucionesResourceTest` stays green.
2. **`CabysResource:128/444/448` + `CabysPagesResource:102`** — add `CabysService.search(codigoOdescripcion, page, size)` (`LOWER(...) LIKE`, `setFirstResult/setMaxResults`) and `countMatching`. Keep `CabysPageTest` assertions; the table model contract keys stay identical.
3. **`LoteResource:140` / `OrdenCompraResource:235,1421`** — move `page/size/sort` into the service query. `OrdenCompraService.listAll()` already `JOIN FETCH`es two collections; do **not** add `setMaxResults` to that query directly (Hibernate would truncate the joined rows, not the entities). Instead add `listPage(int offset, int size)` that selects ids first (`SELECT o.id ... ORDER BY ...`, paged), then `JOIN FETCH` those ids.
4. **`ClientsResource:852,884,487`** — add `ClientService.countByStatus()` + `pageByNameLike(...)` + `zoneUsage()` (`GROUP BY zoneCode`). `ClientsResource` has no HTML test today: add one assertion-only test (`GET /api/app/clientes` body contains the first seeded client) before refactoring, so the refactor is pinned.
5. **`TipoCambioPageResource:165`**, **`TributacionResource:507`**, **`OrdenCompraResource:1338`**, **`FacturasRecibidasResource:1526`**, **`PublicInvoiceService:124/241/354/378/402/464`** — each is a `LIMIT`/`COUNT`/`GROUP BY` swap; no DTO changes.
6. **`ExportResource:241/244/382`** — cap rows (e.g. 50k) and return a 400 `VALIDATION_ERROR` ("demasiadas filas, acote el rango") above the cap. Note `Utils/ReportExporter.java:946` uses `new XSSFWorkbook()` (fully in-memory); a 10^5-row export is an OOM, not a slow page.
7. **`TipoCambioController:44`** — legacy bean; retire it with the rest of the JSF controllers (see Non-goals) rather than patching it.

## 3.5 Verification

- Per step, run the owning test class: `DevolucionesResourceTest`, `CabysPageTest`, `OrdenCompraResourceTest`, `LoteResourceTest` (if absent: `Lote*`), `CorreosAdminPagesTest`, `DashboardResourceTest`, `FacturasRecibidasResourceTest`.
- SQL gate: the service tests must assert that a page-2 request does not change `SELECT` count. Cheap proxy: log `Hibernate` SQL in `%test` once and confirm one query per page instead of one full scan + slice.
- Data-shape gate: every response body must be byte-compatible (`size()`, `body("...")` assertions unchanged) — if a test needs editing to pass, the refactor changed the contract and must be re-done.

## 3.6 Risk / effort

Risk: **medium**. The paging refactors for `OrdenCompra` (JOIN FETCH + pagination) and `Devoluciones` (search semantics: legacy used `contains`/inverted `contains`) can silently change results. Mitigate by indexing (`numeroConsecutivo`, `receptor.nombre`) in a **separate** migration commit and by keeping the in-memory filter as the fallback for `q` until the SQL version is proven on fixtures.
Effort: **L (3-4 days)** for all 28 non-safe sites; each of steps 1-2 alone is **M**.

---

# 4. DTO sprawl — entities used as view models (378 classes, only 50 DTOs)

## 4.1 The pattern to stop

`Controllers/Api/App/SettingsPagesResource.java:143` (`toDTO`, feeds `AppSettingsDTO`), `CategoriaResource.java:889` (`metricasFilas`, explicit maps) and `OrdenCompraResource.java:1268` (`filaTabla`, explicit maps) are the **house pattern**: map at the boundary, hand the template a flat view. Most other resources hand the template a live JPA entity, so the template becomes a persistence-aware view: it walks lazy associations per row and exposes columns nobody meant to publish (password hash, internal flags).

Concrete cost: `templates/pages/categorias/tabla-familias.html:59` reads `familia.usuario.username` — one lazy `@ManyToOne` per row (`FamiliaDTO` already flattens it to `usuarioUsername`); `templates/pages/articulos/tabla-articulos.html:85` calls `articulo.lastPrecioArticulo` / `articulo.codigoCabys.codigo`, i.e. a per-row lazy load inside the render loop.

## 4.2 The eight cases (extraction order)

| # | Entity | Java site (model handed to the template) | Template consumer | View model to use | Effort |
| --- | --- | --- | --- | --- | --- |
| 1 | `Usuarios` | `Api/App/UsersResource.java:911` (table `filas`), `:854` (form `.data("usuario", usuario)`) | `pages/usuarios/tabla.html:57-67`, `pages/usuarios/form.html:65-130` | **`UsersDTO` (exists)** — already excludes the password hash | S |
| 2 | `Clientes` (table) | `Api/App/ClientsResource.java:884` | `pages/clientes/tabla.html:54-67` (`status, code, name, address, email, idNumber, phoneNumber, taxpayer`) | **`ClientsDTO` (exists)** — field-for-field match | S |
| 3 | `Clientes` (form) | `Api/App/ClientsResource.java:842` | `pages/clientes/form.html:28-195`, incl. `{#for actividad in cliente.actividades}` at `:195` | **new `ClientFormDTO`** (adds `zoneCode`, `actividades`, `birthDate`) | M |
| 4 | `Familia` / `Departamento` | `Api/App/CategoriaResource.java:953,954` (tables), `:758,:794` (forms); `CategoriasPagesResource.java:129,130` | `pages/categorias/tabla-familias.html:56-84`, `tabla-departamentos.html:42-44`, `form-familia.html:30`, `form-departamento.html:31-69` | **`FamiliaDTO` / `DepartamentoDTO` (exist)**; change `familia.usuario.username` → `familia.usuarioUsername` (1 template line each) | S |
| 5 | `Articulos` (table) | `Api/App/ArticuloResource.java:1501` (`buildTableModel`) | `pages/articulos/tabla-articulos.html:78-92` (`processed, codigoBarra, codigoCabys.codigo, nombre, lastPrecioArticulo, codigo`) | **new `ArticuloListDTO`** (flat: `codigo, codigoBarra, nombre, cabysCodigo, processed, precio`) | M |
| 6 | `Articulos` (detail) | `Api/App/ArticuloResource.java:403` (`.data("articulo", articulo)`) | `pages/articulos/detalles.html:30-77` (nested `departamento.nombre`, `familia.nombre`, `codigoCabys.codigo/descripcion`) | **new `ArticuloDetailDTO`** — and it should reuse the existing JSON mapper `toDetailDTO(articulo)` called at the same method (`:407`) | M |
| 7 | `OrdenCompra` (form) | `Api/App/OrdenCompraResource.java:1395-1398` (`.data("orden", orden)` + entities in `proveedores`) | `pages/compras/ordenes-form.html:60-85` (`numeroOrden, estado, proveedor.id`) | **`OrdenCompraDetailDTO` (exists)** for `orden`; leave `proveedores` as `< 200` entities or use `DepartamentoDTO` | S |
| 8 | `ReporteProgramado` | `Api/App/ReporteProgramadoResource.java:493,674` | `pages/correos/form-reporte.html:62-107` (`perfil, frecuencia.contains(...), reportes.contains(...)`) | **`ReporteProgramadoDTO` (exists)** — same field names, template needs no edit | S |

Not listed on purpose: `ConfiguracionAplicacion` is already DTO'd (`SettingsPagesResource:100` → `AppSettingsDTO`); `TipoCambio` and `NotaCredito` are mapped to `Map` view rows in their resources (`TipoCambioPageResource:170`, `DevolucionesPagesResource:208`), which is acceptable and cheaper than new DTOs.

## 4.3 Steps (no big-bang)

1. **Sprint A — reuse what exists (cases 1, 2, 4, 7, 8).** For each: change the Java builder to map entities → existing DTO before the `.data(...)`/`TableModel.filas` assignment; Qute property access is identical for getters, so templates need **no** edits except `familia.usuario.username` → `familia.usuarioUsername` (case 4). One commit per case.
2. **Sprint B — new DTOs where the entity graph leaks (cases 3, 5, 6).** Add records/classes under `Models/DTO` following the existing style (plain class + getters, `@Nullable` on optional fields, no Lombok). Map in the resource's own `toDTO` method; for case 6 reuse `toDetailDTO` so HTML and JSON cannot drift.
3. **Guard rail.** Add a comment on `AppRecursoBase` (or a short paragraph in `docs/ui-kit.md`) stating the rule: *resources may pass entities only to `<select>` option lists under ~200 rows; anything rendered in a table/detail must be a DTO or an explicit `Map` view row*. Review gate: `git grep -nE '\.data\("(articulo|cliente|usuario|orden|familia|departamento|reporte)", [a-z][a-zA-Z]*(Entity)?\)'`.
4. **Do not** create a DTO for every entity. 378 classes stay 378; the goal is that no *view* receives an entity.

## 4.4 Verification

- `mvn -q -pl app test -Dtest=UsersPageTest,UsersResourcePrivilegeEscalationTest,CategoriaResourceTest,ArticuloResourceTest,OrdenCompraResourceTest,CorreosAdminPagesTest` — all assert on HTML/JSON substrings and must pass unchanged for cases 1, 2, 4, 7, 8.
- Cases 3/5/6 have no HTML test for the form/detail: add one assertion each (e.g. `GET /api/app/articulos/{codigo}` with `HX-Request: true` → body contains the article name and its familia name) **before** the refactor.
- Security gate for case 1: `git grep -n "getPassword" -- app/src/main/java/Controllers` → 0, and the users table HTML must not contain the seeded password hash.
- Lazy-loading gate: turn on `hibernate.show_sql` for one manual run and confirm the article/familia table issues no per-row `SELECT`.

## 4.5 Risk / effort

Risk: **low-medium**. The main risk is a template reading a field the DTO lacks — the compiler will not catch it, only the render test will. Mitigate by diffing field usage: `git grep -oh "familia\.[a-zA-Z]*" -- templates | sort -u` against the DTO getters before switching.
Effort: **M (1.5-2 days)** total; Sprint A ≈ 3 h.

---

# 5. Response-envelope inconsistency

## 5.1 Measured state

- **309** `Response.ok(...)` call sites. Splitting them (measured, sums to 309):
  - **~147** wrap the payload in `ApiResponse.ok(...)` (`Api/App` + `Api/Accounting` + `Api/Marketplace` style);
  - **19** return a bare `PagedResponse<>` (`Response.ok(new PagedResponse<>(...))`);
  - **50** are HTML fragments or file/`byte[]` downloads (correctly raw);
  - **~89** return a bare service object/array; of those, **39** are the five legacy JSON controllers, **14** are `Api/Accounting/**` (which already conform to rule 1 below), 2 are `DashboardResource`'s chart feeds, and the rest are `List<DTO>` successes;
  - **4** are empty `Response.ok().build()`.
- **73** `BAD_REQUEST` sites: **62** already return `ApiResponse.error(...)`, **10** render an HTMX form fragment (correct — see `ClientsResource:825`, `UsersResource:838`, `InventarioResource:1008/1023/1028`, `CabysResource:315`, `CategoriaResource:847`, `EmailTemplateResource:641`, `ReporteProgramadoResource:680`, `ProfileResource:173`), and **1** hand-rolls JSON: `SalesTrendController.java:173` → `.entity("{\"error\": \"" + e.getMessage() + "\"}")`.
- The five legacy controllers also hand-roll **46** JSON error bodies (`{"error": ...}`): `DashboardMetricsController` 10, `QuickActionsController` 12, `SalesTrendController` 10, `ProductPerformanceController` 9, `StockForecastController` 5. `QuickActionsController:82-84` even hand-rolls its 404.
- The sharpest inconsistency is **inside one class**: `Api/App/DashboardResource.java` returns `ApiResponse.ok(...)` for `/data/kpi` (`:215`) but a **raw array** for `/data/hourly` (`:189`) and `/data/weekly` (`:201`).

## 5.2 Convention (adopt this, do not invent a third shape)

| Context | Success (2xx) | Error (4xx/5xx) |
| --- | --- | --- |
| JSON API | **bare representation**: a DTO, `List<DTO>`, or `PagedResponse<T>` | **always** `ApiResponse.error(code, message[, details])` |
| Paged collection | `PagedResponse<T>` bare (never nested inside `ApiResponse`) | same `ApiResponse.error` |
| HTML / HTMX | the rendered template, `text/html` | same template rendered with the 4xx status (never an envelope) |
| Side-effect only (DELETE/POST with no body) | `204 No Content` (`Response.noContent()`) | `ApiResponse.error` |
| File download | `Response.ok(bytes/File)` + `Content-Disposition` | `ApiResponse.error` as JSON |

Rules:
1. **Errors are the only thing the envelope is for.** `ApiResponse` carries `data` *and* `error`; using it for success makes every client branch on a nullable pair and forces every collection to choose between "wrapped" and "not wrapped" — which is exactly the 147-vs-19 split we have today.
2. **No hand-rolled JSON.** `{"error": "..."}` is banned; it drops the `code` that the UI and tests key on (`MarketplaceErrorEnvelopeTest` already asserts `error.code`).
3. **`ApiResponse.ok(...)` is grandfathered, not extended.** The ~147 existing sites keep shipping `{data: ...}`; new endpoints return the bare representation. Do not mass-migrate — every migration is a client-visible breaking change for zero functional gain, and **the success envelope is pinned by tests**: `DashboardResourceTest:117`, `SettingsPageTest:205,257,358` and `AuthJourneyTest:219` all assert `.body("error", nullValue())` on *successful* call, so unwrapping those responses would break the suite.
4. **`PagedResponse` must never be wrapped** (0 sites today; keep it that way).
5. Document rules 1-4 in a new `docs/api-response-conventions.md` (~25 lines) and link it from `README.md`.

## 5.3 Highest-traffic offenders

Traffic basis: `/api/dashboard/*` feeds the landing page (`templates/pages/dashboard/index.html:145-152` fetches it on every dashboard load), and `/api/sales-trend/*` + `/api/product-performance/*` are fetched by the report pages (`tendencias.html:102`, `rendimiento.html:91`).

| Rank | File | Raw-success sites | Hand-rolled JSON errors | Consumer | Fix |
| --- | --- | --- | --- | --- | --- |
| 1 | `Controllers/DashboardMetricsController.java` | 10 (`:29,43,57,71,85,99,115,132,150,165`) | 10 (all catch blocks) | dashboard landing page | errors → `ApiResponse.error`; success stays bare (rule 3) |
| 2 | `Controllers/QuickActionsController.java` | 6 (`:117,133,151,171,187,269`) + 4 empty `ok()` (`:203,219,235,251`) | 12, incl. hard-coded 404 at `:82-84` | no in-repo consumer found (JWT-exposed API) | errors → `ApiResponse.error`; empty `ok()` → `noContent()` |
| 3 | `Controllers/SalesTrendController.java` | 9 (`:34,51,68,81,96,113,130,147,171`) | 10, incl. `BAD_REQUEST` at `:173` | `reportes/tendencias.html:102` | errors → `ApiResponse.error` (success untouched) |
| 4 | `Controllers/ProductPerformanceController.java` | 9 (`:35,53,71,85,101,117,133,149,166`) | 9 | `reportes/rendimiento.html:91` | same |
| 5 | `Controllers/StockForecastController.java` | 5 (`:31,46,62,76,90`) | 5 | JWT-exposed API | same |
| 6 | `Api/App/DashboardResource.java` | 2 (`:189` hourly, `:201` weekly) | 0 (already enveloped) | same class's own `/data/kpi` is enveloped | **leave the success shapes alone**; note the mixed contract in the javadoc, or envelope them only together with the JS + test change |

Out of scope by instruction: `Api/Mercatus/**` and `Api/Marketplace/**` (`CartController` 5 raw, `OrderController` 3, `ProductController` 3, `AuthController` 3, etc.).

## 5.4 Migration approach that does not break tests

Phase 1 — **errors only (test-safe, do this first)**
- Replace the 46 hand-rolled error bodies + `QuickActionsController:82` 404 + `SalesTrendController:173` 400 with `ApiResponse.error(CODE, mensaje)`, `Content-Type: application/json`.
- Code mapping: 400 → `VALIDATION_ERROR`, 401 → `UNAUTHORIZED`, 404 → `NOT_FOUND`, 500 → `INTERNAL_ERROR`.
- Safety: **no test asserts the legacy error shape.** The only shape-sensitive tests are `DashboardResourceTest:82-99` (hourly/weekly success arrays) plus the success-envelope assertions listed in §5.2 rule 3 — all of them assert `error == null` on success, which Phase 1 preserves. The `{"error":...}` string appears in `app/src/test` only inside javadoc/comments (`MarketplaceErrorEnvelopeTest:45,160,453`, `AuthJourneyTest:52`), never as an assertion.

Phase 2 — **empty `ok()` → `noContent()`** (4 sites, `QuickActionsController:203,219,235,251`). Verify the JS/consumers treat 204 like 200-with-empty-body; there is no in-repo consumer, so add a note in the endpoint javadoc.

Phase 3 — **documentation + guard**
- Add `docs/api-response-conventions.md`; link from `README.md`.
- Add a test that walks the registered JAX-RS resources? Not worth it. Instead add a grep gate to the PR checklist: `git grep -nF '{"error"' -- app/src/main/java` → 0.

Phase 4 — **optional, only with the owner's sign-off**: envelope `DashboardResource /data/hourly|/data/weekly`. That requires, in one commit: `DashboardResource:189,201`, the fetch/parse block in `dashboard/index.html:~145-200`, the two no-fetch fallback keys (`hourlyJson`/`weeklyJson`, `:165-166`), and `DashboardResourceTest:82-99` assertions. Until then, annotate the javadoc at `:181-203` that these two feeds are intentionally bare (legacy chart shape) so nobody "fixes" them in isolation.

## 5.5 Verification

- Phase 1: `mvn -q -pl app test -Dtest=RoutingComprehensiveTest,DashboardResourceTest,PublicApiJwtFilterRoutingTest` plus `MarketplaceErrorEnvelopeTest` (guards that the shared envelope contract still holds).
- Contract probe per converted endpoint: `curl -s -o - -w '%{http_code}'` on a failing input must return the mapped status **and** a body with `error.code` + `error.message` + `error.details`.
- Phase 2: assert `204` and an empty body. The four converted actions are `PUT /shortcuts/{id}/usage` (`:194-203`), `DELETE /shortcuts/{id}` (`:210-219`), `POST /initialize/{username}` (`:226-235`) and `PUT /reorder/{username}` (`:242-251`). **No template in the repo calls `/api/quick-actions`** (`git grep quick-actions -- templates` → 0), so the only consumers are outside this repo; callers use `hx-put`/`hx-post`/`hx-delete`, which act on the status alone.
- Phase 3: the grep gate above returns 0.

## 5.6 Risk / effort

Risk: **low** for Phases 1-3 (error bodies only; no test pins them). **Medium** for Phase 4 (JS + shape-pinned tests). The one real hazard is an external consumer of `/api/quick-actions` or `/api/stock-forecast` (both are exposed through `PublicApiJwtFilter`) — confirm with the API owner before changing their error shape; if unknown, ship Phase 1 for the internal controllers first and leave those two for last.
Effort: **M (~1 day)** for Phases 1-3; Phase 4 is **S** but needs coordination.

---

# 6. Prioritized order

| # | Item | Why now | Effort |
| --- | --- | --- | --- |
| 1 | **§2** stale TODO/T29 comments (3 files) | Zero risk, removes actively misleading documentation, unblocks P1 reviewers who read those comments | S |
| 2 | **§1** delete orphan 404 template + dead `.error-page` CSS | Zero runtime risk, resolves the "template claims to be used" lie | S |
| 3 | **§3 steps 1-2** — `Devoluciones` full invoice scans, `Cabys` full-catalog scans | Real production risk (unbounded scans on the busiest write path and a 6k-row catalog), highest ratio of risk removed per line | M |
| 4 | **§5 phases 1-2** — error envelope + `noContent` | Test-safe, makes failures diagnosable, small diff | M |
| 5 | **§4 Sprint A** — reuse `UsersDTO`/`ClientsDTO`/`FamiliaDTO`/`DepartamentoDTO`/`OrdenCompraDetailDTO`/`ReporteProgramadoDTO` | Cheap, removes lazy N+1 and the password-hash temptation | S-M |
| 6 | **§3 steps 3-6** — paging for `Lote`/`OrdenCompra`/`Clientes`, `LIMIT`s in `TipoCambio`/`Tributacion`/`PublicInvoiceService`, export caps | Larger diffs; do after the P1 paging work lands to avoid two agents in the same services | L |
| 7 | **§4 Sprint B** — new `ArticuloListDTO`/`ArticuloDetailDTO`/`ClientFormDTO` | Needs new classes + new tests; no urgency once the cheap cases are done | M |
| 8 | **§5 phase 4** — dashboard hourly/weekly envelope (optional) | Client-visible break, zero functional gain; only with owner sign-off | S |

Interleaving rule: **#3 and #6 touch `Services/*`, so they must not run concurrently with the P1 SQL-paging work** (same services: `ComprobantesEmitidosService`, `LoteService`, `OrdenCompraService`, `ClientService`). #1, #2, #5, #7, #8 are resource/template-only and can run in parallel with P1.

# 7. Non-goals

- **Mercatus / marketplace**: `Controllers/Api/Mercatus/**`, `Controllers/Api/Marketplace/**`, `filters/MarketplaceJwtFilter`, CORS, `PublicApiJwtFilter` allow-list. Not touched, not audited.
- **The 12 P1 report resources** (§3.2): no edits, not even comment fixes, while P1 is in flight (avoids conflicting hunks).
- **Rewriting the legacy JSF-era controllers** (`DashboardMetricsController`, `QuickActionsController`, `SalesTrendController`, `ProductPerformanceController`, `StockForecastController`, `TipoCambioController`, `SettingsController`, `FacturasController`, `ArticulosController`): this plan only standardises their **error** bodies. Retiring them is a separate workstream.
- **Mass `ApiResponse.ok` → bare migration**: ~147 call sites, every consumer, every shape-pinned test; explicitly rejected in §5.2 rule 3.
- **A DTO for every entity**: 378 classes stay; only view boundaries get DTOs.
- **New global pagination framework / generic repository**: no Panache by project rule; each service gets a minimal `listPage`/`count`/`search` method.
- **Root-path normalisation** of `FallbackResource`/`NotFoundExceptionMapper`/`PDFGenerator` (the `docs/ui-kit.md:47` follow-up): separate wave, auth-adjacent.
- **Deleting or renaming the legacy `.xhtml`/`web.xml` surface**, dependency upgrades, version bumps outside the commit rule, formatter sweeps, and any build/test execution in this planning pass.

---

## Appendix A — Alternative for §1: wiring the 404 template (NOT chosen)

Kept so the owner can override without re-planning. Apply **only** if the redirect contract is deliberately reversed.

1. `NotFoundExceptionMapper`: inject `@Location("pages/error/404") Template page;` and `Logger`, replace the authenticated branch at `:57`:
   ```java
   if (anonymous) { return Response.seeOther(URI.create("/login")).build(); }
   try {
       return Response.status(Response.Status.NOT_FOUND)
               .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
               .entity(page.data("ruta", "/" + normalized).render())
               .build();
   } catch (RuntimeException e) {
       LOG.error("No se pudo renderizar la pagina 404; se conserva el redirect", e);
       return Response.seeOther(URI.create("/app")).build();   // never worse than today
   }
   ```
   Status **404** (not 200, not 303). Target paths stay **deployment-relative** (`/app`), matching `FallbackResource:88-90`; the template already prefixes links with `{config:['quarkus.http.root-path']}`.
2. `FallbackResource`: same injection, replace `:90`. Keep `:62-67` (root → dashboard) first, keep `:76-81` (JSON 404), keep `:83-85` (anonymous → login). Only `GET`/`HEAD` render; other methods keep the 303 (`:98-126`).
3. Template: keep `404.html`, fix its header comment to match the new contract, keep `ruta` (Qute escapes it by default — add a test with `/app/<script>` to prove it).
4. Tests to change: `RoutingComprehensiveTest:170` and `:177` → expect `404` + body contains `Página no encontrada`; add a third asserting anonymous still gets `303 /login`; keep `StaticAssetContractTest:248` (`303|404` is already permissive).
5. `README.md:192` must be rewritten (it currently advertises the 303).
6. Extra risk to accept: rendering a Qute template from an `ExceptionMapper` runs the layout, which includes `fragments/navbar.html` and therefore `{inject:securityIdentity...}` (`UiSecurityIdentity`) — a CDI failure there is now on the error path, which is why the `try/catch` fallback above is mandatory.
