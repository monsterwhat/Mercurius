# P2 Refactor Plan — God classes, chart duplication, authorization defense-in-depth

**Project:** Mercurius (`F:\Documents\GitHub\Mercurius`, Maven module in `app/`)
**Stack:** Quarkus 3.36.2, Java 25, Qute + htmx, Hibernate ORM (no Panache), PostgreSQL
**Document status:** planning only. Nothing here has been implemented.
**Audience:** a cheaper execution agent. Every step names the file, the line range, the
acceptance gate and the command that proves it.

---

## 0. Ground rules for the executing agent

1. **Read the file before you move code.** Line numbers in this document were measured on the
   **working tree as of 2026-10-28**, which contains uncommitted changes on top of `f139ad2`
   (`git status` shows modifications in `application.properties`, several `Services/*` and three
   `Reportes/*` resources). If a file has moved since, re-anchor by symbol name, not by line
   number. In particular, re-read `application.properties` before Phase 3 — its policy lines are
   among the uncommitted edits.
2. **One step = one commit**, and every commit must leave `mvn test` green. The version bump
   (SemVer, per the global rule) goes in the same commit as the code change.
3. **Do not change observable behavior.** Responses, status codes, envelopes, message strings
   and `LOG` lines (including their `source=` token) are part of the contract, pinned by tests.
   If a `LOG.warn`/`LOG.info` moves to a service, it moves **verbatim**, same level, same text.
4. **No Mercatus / Marketplace / CORS changes.** Items that belong to those surfaces are marked
   `out of scope, owner: Mercatus work` and must not be touched.
5. **No browser automation.** The repo forbids launching a browser for verification unless the
   user asks for it in that task. Phase 2 acceptance is therefore test/grep based; if a visual
   check is genuinely needed, **ask the user first**.
6. **Never add a `@Transactional` "for safety".** Every transaction change in this plan is a
   behavior change and is called out explicitly. Transaction boundaries are the single largest
   risk in this refactor.

### Verification commands (run from `app/`)

```powershell
# compile + bundle only (no tests)
mvn -q -DskipTests package

# one test class
mvn -q -Dtest=ArticuloResourceTest test

# a set of test classes
mvn -q -Dtest='PosResourceTest,PosFacturarIdempotenciaTest' test

# whole unit/integration suite (needs PostgreSQL on localhost:5433/mercurius_test)
mvn test
```

`%test` points at `jdbc:postgresql://localhost:5433/mercurius_test` with
`drop-and-create` + `import-test.sql` (`app/src/main/resources/application.properties:156-177`).
Integration tests run under failsafe (`*IT`), not surefire.

---

## 1. Measured baseline

Measured with `Get-Content … | Measure-Object -Line` and `Select-String` on the working tree.

| Target | File | Lines | Routes | Notes |
|---|---|---|---|---|
| God class 1 | `app/src/main/java/Controllers/Api/App/ArticuloResource.java` | 2 065 | 26 | ~71 methods; 11 injected services + 10 Qute templates; static `MSG_*` constants referenced by tests |
| God class 2 | `app/src/main/java/Controllers/Api/App/PosResource.java` | 2 026 | 28 | whole POS pipeline + 8 Qute fragments + 12 payload DTOs |
| God class 3 | `app/src/main/java/Controllers/Api/App/FacturasRecibidasResource.java` | 1 858 | 18 | inbox read model + line correction + MensajeReceptor + `/procesar` + XML upload |
| God class 4 | `app/src/main/java/Controllers/Api/App/DevolucionesResource.java` | 1 498 | 5 | 5 routes but ~2 000-line worth of private helpers (NC generation, HTML-by-string fragments) |
| Templates | `app/src/main/resources/templates/**/*.html` | 147 files | — | **54 `<script>` tags in 46 files**: 50 executable inline JS + 4 `<script type="application/json">` payloads |
| Charts | 11 templates / 14 canvases | — | — | 14 `MercuriusCharts.create(...)` calls; 50 `MercuriusCharts` references |
| Authorization | 97 `@Path` classes | — | 60 annotated | **37 classes with no `@RolesAllowed`/`@Authenticated`/`@PermitAll`** (finding said 36; measured 37, breakdown in Appendix C) |

**Discrepancy to record:** the P2 finding says "24 `MercuriusCharts.create` calls". Measured:
**14** `create(` calls and **50** `MercuriusCharts.*` references. The plan targets the measured
numbers; both are consistent with "chart startup is copy-pasted per template".

**Why the chart duplication is dangerous (evidence):**
- `3f7be8e` "fix(charts): margenes usa un objeto JS en vez de JSON con coma final" — the
  server-rendered JSON ended in a trailing comma, `JSON.parse` failed, the chart was empty with
  36k rows available.
- `21834c5` "fix(ui): corregir estacionalidad…" — a `java.sql.Date` reached `time:format`,
  `Date.toInstant()` threw, the page answered 500.
- `06f108e` "fix(margenes): java.sql.Date no soporta toInstant".

Both classes of bug (hand-built JSON, untyped date/number serialization) are structural, not
typos: they are produced by templates that concatenate values into JS/JSON by hand.

---

## Phase 0 — Safety net before any extraction

**Goal:** make the four god classes' behavior *pinned* before moving a single method, so every
later step can be reverted and re-run independently.

### Files
- new: `app/src/test/java/Controllers/Api/App/FacturasRecibidasTransaccionTest.java`
- new: `app/src/test/java/Controllers/AppAuth/AuthorizationCoverageTest.java`
- new: `app/src/test/java/Controllers/Api/App/ReportChartContractTest.java`
- new: `docs/plans/auth-allowlist.md` (the allowlist the coverage test reads; see Phase 3)

### Steps

**0.1 — Pin the `/procesar` transaction scope (FacturasRecibidas).**
`procesarForm`/`procesarJson` carry `@Transactional` at
`FacturasRecibidasResource.java:936` and `:948`, and every service they call is
`@Transactional` (REQUIRED) — `ArticulosService.java:36`, `InventarioService.java:47`,
`DepartamentoService.java:185`, `ComprobantesRecibidosService.java:127`. Therefore the effective
behavior of `PUT /{id}/procesar` is **one transaction for the whole request**, even though the
javadoc at lines 1084-1085 claims "No es atómico: cada llamada de servicio confirma su propia
transacción". Write a characterization test that:
1. builds a received invoice with at least 2 lines, the **second** one with `cantidad = 0`
   (which throws `ArithmeticException` at line 1240 `montoTotalLinea.divide(cantidad, 20, …)`);
2. calls `PUT /api/app/facturas-recibidas/{id}/procesar` as a `facturacion` user;
3. asserts the response status and that **no** `Articulos` row and **no** `Inventario` row
   from the first line survived.

Run it. **Whatever it asserts becomes the contract** — if it turns out the writes survive,
record that instead and note it in the test javadoc. Do not "fix" the behavior.

**0.2 — Pin the existing authorization surface.**
`AuthorizationCoverageTest` walks `src/main/java`, collects every class with a class-level
`@Path`, and asserts the class carries `@RolesAllowed`/`@Authenticated`/`@PermitAll` **or** is
listed in `docs/plans/auth-allowlist.md` with a reason. Seed the allowlist with today's 37
entries (Appendix C). Pattern to copy: `Controllers/AppAuth/StaticAssetContractTest.java`
already reads files from disk and is the idiomatic form in this repo.

**0.3 — Pin the chart contract.**
`ReportChartContractTest` scans `src/main/resources/templates/**/*.html` and asserts:
- every template that includes `_kit/chart-card` with a `canvasId` also carries a machine
  readable payload marker (`data-report-chart` + a JSON script tag) — **after Phase 2**;
- during Phase 0 it only records the current inventory (no failure), so the test can be
  tightened batch by batch (see Phase 2 acceptance).

### Verification
```powershell
mvn -q -Dtest='FacturasRecibidasTransaccionTest,AuthorizationCoverageTest,ReportChartContractTest' test
```

### Rollback risk
Zero production risk: test-only. If 0.1 reveals non-atomic behavior, that is a finding, not a
failure — update the test, not the code.

### Effort
4–6 h.

---

## Phase 1 — God class extraction

**General order for all four classes** (this is the rule that keeps the suite green):

1. **Pure/static helpers and DTO mappers** (no CDI, no transactions).
2. **Read-only query/consult service** (list, filter, sort, page, view models).
3. **Pure math / builder services** (price math, NC building, XML gates).
4. **Command services for simple mutations** (pay/toggle/line correction).
5. **Command services for the complex writes** (create/update, promotions, `/procesar`,
   `facturar`, devolución).
6. **Optionally** split the JAX-RS class itself — only where the URL surface does not change.

Never invert 5 and 6, and never combine two steps in one commit.

---

### Phase 1.1 — `ArticuloResource` (2 065 → realistic floor ≈1 200 lines; ≈950 if 1.1.5's resource split lands)

> Line count is a **consequence**, not a target. The floor is set by what must stay: 26 route
> signatures, the `MSG_*` constants tests import, the two form payload classes and the Qute
> fragment models. Do not invent a second split just to hit a number.

#### Current responsibilities (measured)

| # | Responsibility | Lines | Members |
|---|---|---|---|
| R1 | Tab list / paging / table fragment | 300-376, 1419-1572 | `list`, `table`, `tableFragment`, `buildTableModel`, `TableModel`, `Window`, `windowOf`, `fullPageModel` |
| R2 | Detail + DTO mapping | 387-414, 1729-1732, 2035-2101 | `detail`, `stockOf`, `toListDTO`, `toDetailDTO`, `toDTO(Cabys/Promocion)` |
| R3 | Article CRUD | 424-706 | `create`, `createForm`, `doCreate`, `update`, `updateForm`, `doUpdate`, `delete` |
| R4 | Revision workflow | 718-871, 1575-1589 | `revisionSiguiente`, `revisar`, `saltar`, `revisionFragment` |
| R5 | Price override + price math | 886-975, 1654-1695, 1718-1727 | `updatePrecio`, `isSupervisorAuthorized`, `calcularPrecioConUtilidad`, `calcularPrecioConIVA`, `lastPrecioOf` |
| R6 | CAByS picker | 986-1012, 1702-1715 | `buscarCabys`, `findCabys` |
| R7 | Promotions CRUD | 1022-1226, 1609-1644, 1944-2011 | `promoDetail`, `createPromocion(+Form)`, `updatePromocion(+Form)`, `deletePromocion`, `doSavePromocion`, `promoEditorFragment`, `PromocionForm`, `ItemForm`, `promoFormOf`, `buildItems` |
| R8 | Modal form fragments | 1233-1348, 1592-1606 | `formNuevaArticulo`, `formEditarArticulo`, `formRevisionArticulo`, `formNuevaPromocion`, `formEditarPromocion`, `formSelectorArticulos(-Ajuste)`, `articuloFormFragment` |
| R9 | Plumbing | 1355-1412, 1839-1919, 1926-2063 | `isHxRequest`, `normalizeTab`, `notFound`, `failureWith`, `revisionFailure`, `promoFailure`, parsers, `currentUser`, `fullPageModel` |

#### Target units

| New unit | Members moved (source lines) | CDI / transaction |
|---|---|---|
| `Services/ArticuloConsultaService.java` | 1468-1492 `TableModel`, 1562-1572 `Window`/`windowOf`, 1495-1560 `buildTableModel`, 1734-1741 `articulosOfTab`, 1747-1788 `filterArticulos`/`filterPromociones`/`matches`, 1791-1837 `sortArticulos`/`sortPromociones` | `@ApplicationScoped`; no transactions (read-only) |
| `Services/ArticuloVistaService.java` | 1729-1732 `stockOf`, 2035-2101 `toDTO`×2 / `toListDTO` / `toDetailDTO` | `@ApplicationScoped`; deps `ArticuloPrecioService`, `InventarioService` |
| `Services/PrecioCalculadora.java` | 1654-1676, 1682-1695, 1718-1727, 1702-1715 | `@ApplicationScoped`; deps `MargenCalculadora`, `ConfiguracionMargenService`, `CabysService`; **pure, no transaction** |
| `Services/ArticuloRevisionService.java` | 725-741 (`revisionSiguiente` body), 762-826 (`revisar` body), 848-864 (`saltar` body) | `@ApplicationScoped`; deps `ArticulosService`, `DepartamentoService`, `FamiliaService`, `PrecioCalculadora`. **No `@Transactional`** — `ArticulosService.update` owns it (line 66) |
| `Services/PromocionCommandService.java` | 1156-1226 (create/update branch), 2014-2033 `buildItems` | `@ApplicationScoped`; deps `PromocionesService`, `ArticuloCarritoService`, `ArticulosService`; no `@Transactional` |
| `Services/ArticuloCommandService.java` | 471-542, 599-670, 684-706 | `@ApplicationScoped`; deps as above + `ProductoExoneracionService`; no `@Transactional` |
| `Controllers/Api/App/ArticuloResource.java` (thin) | R1 routing, R3/R4/R5/R7 validation **envelopes**, R8 fragments, R9 plumbing, `ArticuloForm`/`PromocionForm` payloads, `MSG_*` constants | unchanged `@RolesAllowed({"admin","inventario"})` |

#### Steps (each ends with a green targeted test run)

**1.1.1 — Static DTO/view service.** Move 1729-1732 and 2035-2101 into
`ArticuloVistaService`; inject it. The two DTO classes (`ArticuloListDTO` 2104, `ArticuloDetailDTO`
2134, `PrecioDTO` 2183) stay in `ArticuloResource` for now (no external references, but keeping
them avoids churn).
*Gate:* `mvn -q -Dtest=ArticuloResourceTest test`.

**1.1.2 — Read model service.** Move 1468-1572 + 1734-1837 + the count block of
`fullPageModel` (1441-1445) into `ArticuloConsultaService`. **`ArticuloResource.fullPageModel()`
(1429-1448) must keep its `public` signature**: `ArticulosPagesResource.java:44` calls it.
*Gate:* `mvn -q -Dtest='ArticuloResourceTest,TablaColumnasTest' test`.

**1.1.3 — Price math.** Move 1654-1695 and 1702-1727 into `PrecioCalculadora`, exposing
`recalcular(ArticuloPrecio, String cabysCodigo)` and `lastPrecioOf(Articulos)` and
`findCabys(String)`. **Copy the rounding verbatim** (HALF_UP 4dp on the utility factor, CEILING
0dp on both steps). Supervisor verification stays in the resource until 1.1.6.
*Gate:* `mvn -q -Dtest='ArticuloResourceTest,ArticuloPrecio*Test' test` (the `ArticuloResourceTest`
cases around lines 497 pin `MSG_SIN_PRECIO_FINAL`).

**1.1.4 — Revision service.** Move the three workflow bodies. The HTTP mapping helper
`revisionFailure` (1393-1403) and `revisionFragment` (1575-1589) stay in the resource.
**Critical:** the legacy "recalculate the LAST precio row in place" step (787-805) must operate
on the same entity instance the resource persists — pass the `Articulos` entity in/out, do not
re-query it inside the service.
*Gate:* same command; plus `ProcesarArticulosPrecargaTest` untouched.

**1.1.5 — Promotions service (JSON API + form twins).** Move `doSavePromocion` core and
`buildItems`. Keep the validation order (items → dates present → bounds non-null → range) and the
exact `MSG_PROMO_*` strings in the resource so the tests at `ArticuloResourceTest.java:623/679`
keep passing.
*Optional sub-step (separate commit):* extract `ArticuloPromocionesResource` with
`@Path("/api/app/articulos/promociones")` moving 1022-1226 and 1276-1300. **Risk:** the
`/{codigo}` route (388) and `/promociones/{id}` currently coexist because JAX-RS prefers the
literal segment; after the split, verify `GET /api/app/articulos/promociones/1` and
`GET /api/app/articulos/1` both still resolve, and that the OpenAPI document does not report a
conflict. Also move `@RolesAllowed` with it.
*Gate:* `mvn -q -Dtest='ArticuloResourceTest,RoutingComprehensiveTest' test`.

**1.1.6 — Article command service + supervisor check.** Move `doCreate`/`doUpdate`/`delete`
cores and the price-append part of `updatePrecio` (916-944). Replace
`isSupervisorAuthorized` (957-975) with the shared `SupervisorAuthService` from Phase 1.2.2
(do 1.2.2 first). Keep the HTTP envelopes and the `@HX` branches in the resource.
*Gate:* `mvn -q -Dtest=ArticuloResourceTest test` and `mvn -q -Dtest='RoleMatrixTest' test`.

#### Transaction-boundary risks (Articulo)
- `ArticulosService.create/update` are `@Transactional` (`ArticulosService.java:36,66`) and
  **also flush `precios` via cascade**. The service extraction must keep passing the *same*
  `Articulos` instance that carries the `precios` list; re-loading the entity inside the new
  service and saving a different graph silently drops the price history row.
- `lastPrecioOf` (1718-1727) deliberately prefers `articulo.getLastPrecio()` (EAGER collection
  element) over the service lookup, because that is the element `update()` cascades. Preserve
  the preference order exactly.
- `delete` is a soft delete (`ArticulosService.softDelete`) — do not turn it into a hard delete.
- Promotions use a **hard** delete (`PromocionesService.delete`, 1140) — preserved quirk.

#### Rollback risk
Low per step. Each step is a mechanical move inside one file + one new class; `git revert <sha>`
restores a single compilation unit. The only risky step is 1.1.5's optional resource split
(route table), which is why it is separate and optional.

#### Effort
12–16 h (5 mandatory steps + 1 optional).

---

### Phase 1.2 — `PosResource` (2 026 → realistic floor ≈1 150 lines)

> The POS surface cannot be split into a second JAX-RS class without changing URLs (all 28 routes
> share `@Path("/api/app/pos")`), so the residual size is the route bodies themselves. Extract
> services; do not chase a line target by moving HTTP methods around.

#### Current responsibilities (measured)

| # | Responsibility | Lines |
|---|---|---|
| P1 | Cart mutations (scan/add/remove/quantity) | 236-346, 1101-1245 |
| P2 | Client selection + client/article typeahead + pickers | 354-385, 1253-1335, 1775-1850 |
| P3 | Payment staging | 393-428, 1505-1527, 1853-1888 |
| P4 | **The facturar pipeline** (gates, idempotency, Hacienda, PDF, email, cleanup) | 444-914 |
| P5 | Supervisor override authorization | 978-1021, 1535-1582, 2055-2068 |
| P6 | PDF streaming | 1033-1074 |
| P7 | Panel/dialog/result view models | 1656-1771, 1986-2015 |
| P8 | Plumbing + payload DTOs | 1906-1960, 2017-2068, 2073-2185 |

#### Target units

| New unit | Members moved | Notes |
|---|---|---|
| `Services/PosPanelService.java` | `panelModel` 1656-1694, `dialogModel` 1698-1720, `resultadoModel` 1724-1754, `tipoCambioBadge` 1758-1771, `toCartSummary` 1986-2015, `toMessage` 1967-1979, `message`/`mensajeToMap` 1906-1927, `findLineByArticuloCodigo` 1940-1950, `hasOverridesInCarrito` 1953-1960, `stagePagos` 1853-1868, `zipPagos` 1870-1888, `parseDecimal`/`parsePositiveOrDefault` 1890-1904, `typeaheadHits` 1775-1795, `articuloPickerHits` 1799-1850 | `@ApplicationScoped`; deps `CarritoService`, `ClientService`, `CartSessionStore`, `ArticulosService`, `TipoCambioService`. **Keep three one-line delegating methods `panelModel/dialogModel/tipoCambioBadge` on the resource** so `PosPageResource.java:90-91` needs no change |
| `Services/PosFacturacionService.java` | `doFacturar` 476-492 (incl. `synchronized (entry)`), `doFacturarInterior` 494-802, `repetirFacturado` 830-914 | `@ApplicationScoped`, **not** `@Transactional`. Returns a small `PosFacturaResult(status, errorCode, errorMessage, FacturarResult data)`; the resource maps it to `Response` |
| `Services/auth/SupervisorAuthService.java` | verification block 992-1013 (JSON) and 1552-1561 (form), plus `ArticuloResource.isSupervisorAuthorized` 957-975 and `DevolucionesResource.authorize` 486-509 | `@ApplicationScoped`; `Optional<Usuarios> verify(username, password, String source)`. The `source` parameter keeps each caller's log token identical |
| `Services/PosPdfService.java` (or extend `Services/FacturaArchivoService`) | 1037-1074 | no transaction; keep the regex `FACTURA_FILE_PATTERN` (103) and the ZIP fallback |
| `PosResource` (thin) | routing, auth guards, `@HX` fragment selection, payload DTOs 2073-2185 | unchanged path `@Path("/api/app/pos")` |

#### Steps

**1.2.1 — Panel/view service.** Move P7 + typeahead builders + `stagePagos`/`zipPagos` +
parsers. Leave `PosResource.panelModel/dialogModel/tipoCambioBadge` as delegating methods.
*Gate:* `mvn -q -Dtest='PosResourceTest,PosFacturaTemplateTest' test`.

**1.2.2 — Supervisor auth service.** Extract once and rewire **all four** call sites
(`PosResource` JSON + form, `ArticuloResource.updatePrecio`, `DevolucionesResource.authorize`).
`AppAuthResource.supervisorAuthorize` (100-… ) is **not** rewired in this phase (it also derives
roles and shapes a DTO); record it as a follow-up. Keep messages `INVALID_CREDENTIALS` /
`Usuario o contraseña incorrectos` and the three `LOG.info` audit lines verbatim.
*Gate:* `mvn -q -Dtest='PosResourceTest,ArticuloResourceTest,DevolucionesResourceTest' test`.

**1.2.3 — Facturar pipeline.** This is the high-risk step; do it alone.
Move 476-914 into `PosFacturacionService`, preserving **in this exact order**:
1. `synchronized (entry)` around the whole pipeline (489) — the per-cashier serialization is what
   makes double-submit impossible (`PosFacturarConcurrenteTest`);
2. idempotency replay check (508-516) **before** any work, and the stamp at 713 **immediately
   after** `crearComprobante` returns, before the Hacienda send;
3. supervisor gate (522-527) → settings gate (531-543) → cart gate (546-553) → strategy/receptor
   (556-574) → points clamp (579-596) → payments fallback (601-608) → payment sufficiency
   (611-629);
4. `comprobanteService.crearComprobante` inside the existing try/catch for
   `PuntosNoCanjeadosException` (673-687) and `ComprobanteNoCreadoException` (688-699);
5. `comprobanteService.enviarComprobanteCreado` **outside** any transaction (727);
6. PDF generation tolerant of `RuntimeException` (744-767);
7. client email only when `haciendaEnviado` (770-783);
8. `cartSessionStore.remove(username)` last (788);
9. `repetirFacturado` (830-914) untouched semantics: no stock, no numbering, no points, no
   email, PDF retried from the still-staged cart, stamp kept on failure.

`facturarForm` (1592-1614) keeps calling the same shared entry point and keeps
`resultadoModel`'s OOB panel refresh.
*Gate:* the whole POS cluster —
`mvn -q -Dtest='PosResourceTest,PosFacturarIdempotenciaTest,PosFacturarPuntosTest,PosFacturarConcurrenteTest,PosFacturarEnvioFueraDeTransaccionTest,PosFacturarTiposDocumentoTest,PosFacturaTemplateTest,CartConcurrenteTest' test`.

**1.2.4 — PDF streaming.** Move 1037-1074 into `PosPdfService`; keep `Content-Type`
`application/octet-stream`, the `Content-Disposition` filename and the 404/500 envelopes.
*Gate:* `mvn -q -Dtest='PosResourceTest,FacturaArchivoServiceTest' test`.

#### Transaction-boundary risks (POS)
- **Do not annotate `PosFacturacionService` (or its `facturar` entry) with `@Transactional`.**
  `PosFacturarEnvioFueraDeTransaccionTest` proves the Hacienda send must happen with no
  transaction open; an outer transaction would hold it (and reintroduce the consecutivo lock
  contention documented at `PosResource.java:87-92`).
- `ComprobanteService.crearComprobante` is itself `@Transactional` and is the *only* write
  boundary for invoice + stock + points. Keep it as a single call.
- The `synchronized(entry)` monitor must move with the pipeline. Putting it in the resource while
  the pipeline lives in the service (or vice versa) reopens the double-submit window that
  `PosFacturarIdempotenciaTest` pins.
- `cartSessionStore.remove` must remain the last action; moving it earlier discards the staged
  cart that `repetirFacturado` needs for PDF regeneration.

#### Rollback risk
Medium for 1.2.3 only (a 300-line pipeline move). It is one file + one new class, so a revert is
clean, but a partially-applied move can compile while breaking ordering. Enforce the numbered
order above and rely on the POS test cluster.

#### Effort
10–14 h.

---

### Phase 1.3 — `FacturasRecibidasResource` (1 858 → realistic floor ≈900 lines)

#### Current responsibilities (measured)

| # | Responsibility | Lines |
|---|---|---|
| F1 | Inbox read model (buckets, filter, sort, paging, table model, DTOs) | 276-330, 361-527, 1694-1871 |
| F2 | Detail drawer + line CAByS correction | 531-651, 1713-1799 |
| F3 | Prevalidation panel | 653-706 |
| F4 | MensajeReceptor send (three codes + partial lines + prevalidation gate) | 720-925 |
| F5 | `/procesar`: article + inventory creation | 926-978, 1078-1323 |
| F6 | `pagar` / `toggle` | 984-1076 |
| F7 | XML upload / rejected-invoice import / XML hardening | 1419-1692 |
| F8 | Plumbing (`currentUser`, HX detection, envelopes, upload fragment) | 1873-1958 |

#### Target units

| New unit | Members moved | Notes |
|---|---|---|
| `Services/ComprobantesRecibidosConsultaService.java` | 330-527, 1694-1871 (`toListDTO`, paging helpers, `params`) | `@ApplicationScoped`, read-only |
| `Services/ComprobantesRecibidosAccionesService.java` | 601-651 (`doCorregirLinea`), 1009-1029 (`doPagar`), 1059-1076 (`doToggle`) | `@ApplicationScoped`; **line correction keeps `@Transactional`** (moves from `:577`/`:594` to the service method) |
| `Services/ComprobantesRecibidosProcesamientoService.java` | 953-978 + 1118-1323 | `@ApplicationScoped`, **no `@Transactional`** (inherits caller's transaction; see risk below). Injects its **own** `EntityManager` |
| `Services/ComprobantesRecibidosMensajeReceptorService.java` | 747-925 orchestration (delegating to the existing `MensajeReceptorService`) | `@ApplicationScoped` |
| `Services/ComprobantesRecibidosImportService.java` | 1428-1462, 1465-1497, 1511-1568, 1571-1650 | `@ApplicationScoped`; keeps `AsyncUserContext` set/clear in `try/finally` |
| `FacturasRecibidasResource` (thin) | routes, `@HX` fragment choice, toast mapping, DTO records | unchanged `@RolesAllowed({"admin","facturacion"})` |

#### Steps

**1.3.1 — Read model.** Move F1. `tablaModel` stays `public` (currently only used internally at
317/1327, but it is public API of the class; moving it to the service and delegating keeps
callers safe).
*Gate:* `mvn -q -Dtest='FacturasRecibidasResourceTest,TablaColumnasTest' test`.

**1.3.2 — Actions (line correction / pagar / toggle).** Move F2's mutation and F6. The
`@Transactional` annotations at 577/594 **must move with** `corregirLinea*`: today the resource
method opens the transaction, `LineaDetalleService.update` (`Services/Facturas/LineaDetalleService.java:68`)
joins it (REQUIRED), and the re-read at 624 rebuilds the drawer from the same persistence context.
If the annotation is dropped from both sides, the update commits in the service's own transaction
and the re-read can observe a different snapshot; if it is duplicated (`REQUIRES_NEW` on the
service), the drawer rebuild no longer sees the write. Keep exactly one `REQUIRED` boundary.
*Gate:* `mvn -q -Dtest=FacturasRecibidasResourceTest test` (line-correction case at 476).

**1.3.3 — MensajeReceptor orchestration.** Move F4. Keep the prevalidation gate ("errors block,
warnings allow"), the tampered-document detection (761-780), the partial-acceptance sum
(834-…), the 409 `PREVALIDATION_FAILED` panel and the existing `MensajeReceptorService` calls.
*Gate:* `mvn -q -Dtest='FacturaMensajeReceptorIntegrationTest,FacturasRecibidasResourceTest' test`.

**1.3.4 — `/procesar`.** Move F5 **without adding any annotation**. Keep:
- the preload `WHERE IN` queries and the `putIfAbsent` "first row wins" semantics (1157-1215);
- the fallback to `findByName`/`findByBarCode` when a map key is absent;
- the memoized department and its null-not-memoized failure path;
- the `codigoBarra.isEmpty()` NPE preserved on purpose (1131-1134);
- `notaCredito ? cantidad.negate() : cantidad` inventory direction (1308-1320).
The new service is `@ApplicationScoped` and injects `EntityManager` **directly**; the comment at
`FacturasRecibidasResource.java:222-227` explains that reading another bean's `em` field through
a client proxy returns `null` — a service owning its own field is unaffected.
*Gate:* `mvn -q -Dtest='FacturasRecibidasResourceTest,ProcesarArticulosPrecargaTest' test` plus the
Phase 0.1 transaction test.

**1.3.5 — XML upload.** Move F7. Keep `prevalidateXml` as a single hardened parse with the
DOCTYPE rejection decided on the parsed tree (1580-1650), the `AsyncUserContext` bracket, and the
rejected-invoice import retry loop (15 × 200 ms).
*Gate:* `mvn -q -Dtest='FacturaUploadDoctypeRejectionTest,FacturaUploadIntegrationTest,FacturasRecibidasResourceTest' test`.

#### Transaction-boundary risks (FacturasRecibidas)
- **The biggest trap:** `procesarArticulos` is called from two different transaction contexts —
  `/procesar` (resource `@Transactional`, one tx for everything) and
  `importarProductosDeFacturaRechazada` (upload path, **no** outer transaction → each service
  call commits on its own). The extracted method must stay annotation-free so it keeps inheriting
  the caller's context. Adding `@Transactional` or `REQUIRES_NEW` changes one of the two paths.
- `corregirLinea`'s transaction must cover the line `update` **and** the follow-up
  `findByIdWithDetails` read used to rebuild the drawer (621-629); splitting them into two
  services with separate transactions reintroduces the stale-fragment state.
- `mensaje-receptor` is **not** transactional at the resource; `MensajeReceptorService` owns
  whatever it needs. Do not wrap the orchestration.

#### Rollback risk
Medium. Steps 1.3.1/1.3.2/1.3.5 are isolated; 1.3.3 and 1.3.4 are large moves whose damage mode
is silent behavior drift (not compile errors). Gate each on its own test class.

#### Effort
10–12 h.

---

### Phase 1.4 — `DevolucionesResource` (1 498 → realistic floor ≈700 lines; ≈600 with Qute fragments)

#### Current responsibilities (measured)

| # | Responsibility | Lines |
|---|---|---|
| D1 | Returnable-invoice search + line detail | 254-333, 668-732 |
| D2 | Selection parsing + guards + total | 741-840 |
| D3 | Credential gate + two-phase processing | 477-558, 580-655 |
| D4 | NC electrónica assembly | 1025-1279 |
| D5 | Hacienda send + pending-enqueue | 931-1024 |
| D6 | HTML fragments built by string concatenation | 1335-1561 |
| D7 | Plumbing | 1298-1333 |

#### Target units

| New unit | Members moved | Notes |
|---|---|---|
| `Services/DevolucionConsultaService.java` | 668-704 (`buscarFacturas`), 706-714 (`toRow`), 717-732 (`lineaRows`), 334-… search paging | read-only |
| `Services/DevolucionSeleccionService.java` | 741-787 (`parseSelecciones`), 790-831 (`validarGuardias`), 834-840 (`totalDevolucion`) | pure; guard messages verbatim |
| `Services/DevolucionCommandService.java` | 580-655 (`procesarDevolucion`) | **no `@Transactional`**; caller keeps `QuarkusTransaction.requiringNew()` (524) |
| `Services/NotaCreditoElectronicaService.java` | 1025-1279 (`generarNcElectronica`), 931-973 (`enviarNcAHacienda`), 974-1024 (`registrarPendienteDeEnvioNc`) | `@ApplicationScoped`; **no transaction on the send methods** |
| `Services/DevolucionFragmentService.java` **or** Qute templates `pages/devoluciones/*.html` | 1335-1561 | preferred end state is templates; the service wrapper is the low-risk intermediate |
| `DevolucionesResource` (thin) | routes, credentials gate, phase ordering, envelopes | unchanged `@RolesAllowed({"admin","facturacion"})`; keep `public record NcSummary` (imported by `DevolucionesResourceTest.java:28`) |

#### Steps

**1.4.1 — Consulta + selección.** Move D1/D2. Keep the inverted `contains` semantics in
`buscarFacturas` (679: `needle.contains(nombre)`) — it is a preserved legacy quirk, not a bug.
*Gate:* `mvn -q -Dtest=DevolucionesResourceTest test` (search cases at 382-415).

**1.4.2 — NC assembly.** Move D4 to `NotaCreditoElectronicaService`. Keep the 6-dp factor
(1094), the per-rate `taxByRate` merge (1182), the `totalIVADevuelto` subtraction (1198), the
`InformacionReferencia` `ANULA_DOCUMENTO_REFERENCIA` code (1242) and the swallowed NC error
(1273-1278 → `NcElectronicaResultado.sinDocumento`, logged).
*Gate:* `mvn -q -Dtest=DevolucionesResourceTest test` (NC assertions at 173).

**1.4.3 — Send + enqueue.** Move D5. `enviarNcAHacienda` must stay callable **after** the
transaction commits; `registrarPendienteDeEnvioNc`'s out-of-line queue insert must keep its own
transaction behavior (973-1024). `DevolucionEnvioFueraDeTransaccionTest` is the oracle: a second
devolución must not wait for the first one's Hacienda poll.
*Gate:* `mvn -q -Dtest='DevolucionEnvioFueraDeTransaccionTest,DevolucionesResourceTest' test`.

**1.4.4 — Processing command.** Move `procesarDevolucion`. The only transaction boundary stays
`QuarkusTransaction.requiringNew().call(...)` in `authorize` (524). If you move the
`requiringNew` into the service, you must move the Hacienda send out of it too — do **not**
annotate the service method `@Transactional`.
*Gate:* the two Devolución tests above + `EnvioFueraDeLineaTest`.

**1.4.5 (optional) — Fragments to Qute.** Replace D6 with `pages/devoluciones/facturas-fragment.html`,
`lineas-fragment.html`, `auth-form.html`, `nc-summary.html`, `historial-oob.html`. **The tests
assert literal substrings** — preserve `id="devolucion-form"`, `name="lineaNumero"`,
`name="lineaCantidad"`, `auth-devolucion-modal`, `id="auth-devolucion-form"`,
`id="historial-tbody"`, `data-kit-table`
(`DevolucionesResourceTest.java:430-466`). Keep `escape()` semantics for user text.
*Gate:* `mvn -q -Dtest=DevolucionesResourceTest test`.

#### Transaction-boundary risks (Devoluciones)
- Phase (a) must stay one confirmed transaction containing `NotaCredito` + all `Inventario`
  movements + the NC entity; a split transaction can leave a credit note without stock movement.
- Phase (b) (Hacienda) must run with **no transaction open**; the pessimistic lock on the "02"
  consecutive is released at commit.
- The legacy "Autorización Exitosa" audit line fires **before** the guards (515) — keep that
  ordering; `DevolucionesResourceTest` and the parity notes depend on it.
- Credential failure must produce **zero** side effects (401): do not move the credential check
  into the transactional command.

#### Rollback risk
Medium-low. D1/D2 are pure; D4 is a large but self-contained builder; D5/D3 are the ones where a
mistake is only visible under concurrency (test 1.4.3 covers it).

#### Effort
10–14 h (12 if 1.4.5 is deferred).

---

## Phase 2 — Shared `report-chart.js` bootstrap + data contract

**Goal:** one place that (a) serializes chart payloads safely and (b) mounts/remounts charts.
Delete 14 copy-pasted initializers and every hand-built payload: six templates concatenate arrays
into JS (`departamentos`, `familias`, `resumen`, `merma`, `margenes`, `estacionalidad`), two emit
`<script type="application/json">` (`pronosticos`, `dashboard`), and one interpolates a label
inside a JS string literal (`pronosticos.html:144`).

### 2.1 The contract

**Payload** (one JSON object per canvas, emitted by the server, never concatenated in a template):

```json
{
  "type": "bar",
  "label": "Ventas",
  "labels": ["Enero", "Febrero"],
  "series": [{ "label": "Ventas", "values": [1200, 3400] }],
  "legend": false,
  "emptyLabel": "Sin datos para el período"
}
```

- `type` ∈ `bar | line`; `series` is always an array (1..n) so the historical multi-line chart
  (`ventas-fecha-historica`) fits without a second schema.
- Numbers are JSON numbers (`BigDecimal.toPlainString()` at serialization, **not** Qute `{v}`).
- Dates are `yyyy-MM-dd` strings produced by the resource (`LocalDate`), never `java.sql.Date`.
- `emptyLabel` is optional copy for the in-frame empty state.

**Marker**: the canvas carries `data-report-chart="<payload-id>"`; the payload lives in
`<script type="application/json" id="<payload-id>">…</script>` immediately after the chart card.

**Java side** (new):
- `Models/DTO/ReportChartPayload.java` — record `(String id, String type, String label,
  List<String> labels, List<ReportChartSeries> series, boolean legend, String emptyLabel)`.
- `Models/DTO/ReportChartSeries.java` — record `(String label, List<BigDecimal> values)`.
- `Utils/ReportChartJson.java` — single `toScriptJson(Object)` using Jackson **plus** escaping of
  `<` → `\u003c`, `>` → `\u003e`, `&` → `\u0026`, U+2028/U+2029. This is what makes
  `</script>` in a product name harmless.
- `templates/_kit/chart-card.html` — add two optional params: `dataId` (emits
  `data-report-chart="{dataId}"` on the canvas) and `chartJson` (emits the JSON script tag).
  Existing params (`title`, `subtitle`, `size`, `canvasId`, `height`, slots) unchanged.

**JS side** (new): `app/src/main/resources/web/report-chart.js`
- `mount(root = document)`: for each `[data-report-chart]`, read + `JSON.parse` the payload,
  build the Chart.js config (hue from `--mercurius-chart-series-1` via
  `MercuriusCharts.resolveColour`), call `MercuriusCharts.create(canvasId, config)`, and put the
  payload's `emptyLabel` on the empty state.
- Registers `DOMContentLoaded` **once** and delegates `htmx:afterSwap` → `mount(event.target)`
  so partial table swaps re-render charts (`margenes` and `estacionalidad` currently hand-roll
  this; the other nine don't, which is a latent stale-chart bug).
- Failure policy: missing element → `console.warn` + leave the existing empty state; invalid
  JSON → `console.error` with the payload id and show "No se pudo dibujar el gráfico". No silent
  empty canvas (matches `chart-config.js:294-299` policy).
- `app/src/main/resources/web/index.js`: add `import './report-chart.js'` **after**
  `window.MercuriusCharts` is assigned (currently line ~26).

**Fetch-based pages** get one extra optional attribute: `data-report-chart-src` plus
`data-report-chart-adapter` with exactly three adapters in `report-chart.js`
(`sales-trend-daily`, `product-performance-department`, `same-date-history`). Adapters are ~10
lines each and only map the endpoint's JSON to the contract.

### 2.2 Templates to migrate (complete list)

| Batch | Template | Canvas / payload today |
|---|---|---|
| A | `pages/reportes/departamentos.html` | `grafica-departamentos`, inline arrays 49-72 |
| A | `pages/reportes/familias.html` | `grafica-familias`, inline arrays 49-70 |
| A | `pages/reportes/resumen.html` | `grafica-resumen`, inline arrays 86-108 |
| A | `pages/reportes/merma.html` | `grafica-merma`, inline arrays 59-79 + server empty branch 49 |
| A | `pages/reportes/margenes.html` | `margenes-chart`, `window.margenesChartData` 166-171 + initializer 176-206 (the trailing-comma workaround) |
| A | `pages/reportes/estacionalidad.html` | 4 canvases, inline arrays 172-217 |
| B | `pages/reportes/pronosticos.html` | `grafica-pronosticos`, JSON block 117 + initializer 118-155 (label interpolated into a JS string literal) |
| C | `pages/reportes/tendencias.html` | `grafica-tendencias`, `fetch /api/sales-trend/daily` 129-153 |
| C | `pages/reportes/rendimiento.html` | `grafica-rendimiento`, `fetch /api/product-performance/department-performance` 83-141 |
| C | `pages/reportes/ventas-fecha-historica.html` | `grafica-fecha-historica`, `fetch /api/sales-trend/same-date-history` 163-230 |
| D | `pages/dashboard/index.html` | `grafica-ventas-hora`, `grafica-tendencia-semanal`; 2 `application/json` fallbacks 115-116 + module script 118-261 |

That is **11 templates / 14 canvases**.

### 2.3 Server-side follow-ups required by the migration

| Batch | Resource change |
|---|---|
| A | Replace `model.put("chartLabels", …)` / `chartValues` with one `ReportChartPayload`. Files: `Reportes/DepartamentosResource.java:101-139`, `Reportes/FamiliasResource.java:103-141`, `Reportes/MargenesReportesResource.java:139-177`, `Reportes/MermaResource.java:112-177`, `Reportes/ResumenResource.java:157-184`, `Reportes/EstacionalidadReportesResource.java` (monthly/daily/dept/family lists) |
| B | `Reportes/PronosticosResource.java:227-268,285` — replace the private `toJson` with `ReportChartJson.toScriptJson` |
| C | No server change (endpoints already return JSON) |
| D | `DashboardResource` — keep `hourlyJson`/`weeklyJson` as `ReportChartPayload` serialized with `ReportChartJson` |

### 2.4 Steps

1. Add `ReportChartPayload`, `ReportChartJson`, the `chart-card` params, `web/report-chart.js`,
   the `index.js` import. **No template migrated yet.** Gate: `mvn -q -DskipTests package` +
   `mvn -q -Dtest='DashboardResourceTest' test` (bundle must still build).
2. Batch A (6 templates + 6 resources). One commit per template keeps the diff reviewable.
   Gate: the page tests listed below after each.
3. Batch B (pronosticos).
4. Batch D (dashboard) — note it is the only page with a fetch + JSON fallback; migrate both
   charts and delete the duplicated module bootstrap.
5. Batch C (3 fetch pages) + the three adapters.
6. Tighten `ReportChartContractTest`: every chart template must satisfy the marker contract, and
   **no template may contain `MercuriusCharts.create(`**.

### 2.5 Acceptance checks

**Automated (required):**

```powershell
# no chart initializer left in templates
Select-String -Path (Get-ChildItem -Recurse -Filter *.html app\src\main\resources\templates).FullName `
  -Pattern 'MercuriusCharts\.create\(' | Measure-Object        # must be 0

# no hand-built payloads left
Select-String -Path (Get-ChildItem -Recurse -Filter *.html app\src\main\resources\templates).FullName `
  -Pattern 'chartLabels|chartValues|ChartData =' | Measure-Object   # must be 0

mvn -q -Dtest='ReportChartContractTest,MargenesReportesPageTest,EstacionalidadReportesPageTest,ResumenResourceTest,MermaResourceTest,DepartamentosResourceTest,FamiliasResourceTest,PronosticosResourceTest,DashboardResourceTest,TendenciasResourceTest,RendimientoResourceTest' test
```

**New assertions in `ReportChartContractTest`:**
1. every canvas id in a `_kit/chart-card` include has `data-report-chart` and a matching
   `<script type="application/json" id=…>`;
2. rendering a page whose label contains `O'Brien "x" </script>` produces a payload that
   `JSON.parse` accepts and that does **not** close the script element early;
3. a payload with `values: []` yields `"series":[{"values":[]}]` and an `emptyLabel` (never a
   `NaN`/`undefined` literal);
4. no template contains the string `MercuriusCharts.create(`.

**Explicitly out of scope:** visual/browser confirmation. Per repo rule, do not launch a browser.
If the user wants a screenshot pass, they must ask for it; otherwise the acceptance is the
contract test + the existing page tests.

### 2.6 Rollback risk
Low and incremental: each template is independent, and `render() ` failures are caught by the
page tests. The one cross-cutting change is `index.js` + `_kit/chart-card.html` (touches every
page that includes the kit); land it first with zero migrations, prove the bundle builds, then
migrate.

### 2.7 Effort
8–12 h (≈45 min per template including its resource change).

---

## Phase 3 — Authorization defense-in-depth

**Goal:** every resource carries its own declarative gate, so a policy gap cannot silently expose
an endpoint. The HTTP permission policy stays as the outer layer; annotations become the inner
one.

### 3.1 What is actually true today (measured)

- 97 classes declare `@Path`; **60** carry `@RolesAllowed`; **0** carry `@Authenticated`;
  **37** carry nothing (full breakdown in Appendix C).
- `application.properties:368` enumerates **25** `/api/*` prefixes, each duplicated with the
  `/Mercurius` prefix. It does **not** cover all `/api/app/*`: `/api/app/historico-emitidos` and
  `/api/app/importacion-masiva` are missing (both have class-level `@RolesAllowed`, so they are
  not exposed — but the enumeration drift is exactly the failure mode this phase prevents).
- `PublicApiJwtFilter` fail-closes any unknown `/api/` path (`decidir()` 257-292, `DENEGAR` at
  291) and authenticates `/api/v1/*` + the five analytics endpoints; it treats `/api/app/**` and
  `/api/marketplace/**` as "other domains" (`ESPACIOS_AJENOS` 118-121).
- `MarketplaceJwtFilter` guards `/api/marketplace/*` on its own.

### 3.2 The two enumeration questions (answered here, not deferred)

**`/api/v1/*` in the HTTP permission policy → NO.**
`quarkus.http.auth.permission.secured.policy=authenticated` is the **form-cookie** policy
(`application.properties:197-201`). A Bearer client under `/api/v1/accounting/*` has no session
cookie; adding the prefix to that list would replace today's 401 JSON envelope with a 302
form-auth challenge (documented in `StaticAssetContractTest` javadoc: 302 +
`quarkus-redirect-location` cookie = form challenge). That is a breaking change for API clients
and it duplicates a gate the JWT filter already enforces fail-closed. **Keep `/api/v1/*` out of
the property policy.**

**`/api/marketplace/*` in the HTTP permission policy → NO, and out of scope.**
It has its own filter and its own token model. *Out of scope, owner: Mercatus work.*

**What to do instead for `/api/app/*`:** replace the 25-entry enumeration with a wildcard pair
`/api/app/*` + `/Mercurius/api/app/*`, keeping the existing public carve-outs in
`quarkus.http.auth.permission.public.paths` (line 352) **ahead** of it. This closes future drift
(any new `/api/app/...` resource is authenticated by default instead of depending on someone
remembering to add a line). It also removes the two missing prefixes without a behavior change —
both are currently protected by annotations only.

> **Risk to verify:** `/api/app/auth/me`, `/api/app/auth/logout`, `/api/app/pos/cart`,
> `/api/app/pos/cart-panel` are in the public list and must keep winning. `AuthJourneyTest`
> (157-181, 284-335) and `PublicApiJwtFilterRoutingTest` are the oracles. If ordering proves
> unreliable on Quarkus 3.36, revert to enumeration and add the two missing prefixes instead.

### 3.3 Classes to annotate (non-Mercatus)

| Class | Path | Proposed gate | Why / risk |
|---|---|---|---|
| `Controllers/Api/App/PosResource.java` | `/api/app/pos` | `@RolesAllowed({"admin","facturacion"})` + `@PermitAll` on `cart()` (957-967) and `cartPanel()` (1083-1093) | Mirrors `PosPageResource.java:33`. The two `@PermitAll` methods already self-check and return the 401 envelope; annotating the class without them would change those two responses. POS tests log in as `admin` (`PosResourceTest.java:111-123`) and one method uses
`@TestSecurity(roles={"facturacion"})` (`PosResourceTest.java:583`) → both pass |
| `Controllers/Api/App/Reportes/ClientesReportesResource.java` | `/app/reportes/clientes` | `@Authenticated` | Its own javadoc: legacy constraint was "generic `/secured/*` any-authenticated". No area role existed, so `@Authenticated` is the faithful gate |
| `Controllers/Api/App/Reportes/EstacionalidadReportesResource.java` | `/app/reportes/estacionalidad` | `@Authenticated` | Same javadoc rationale |
| `Controllers/Api/App/AppAuthResource.java` | `/api/app/auth` | `@Authenticated` on `supervisorAuthorize` (100) only; leave `me` (234) and `logout` (211) unannotated | `/me` and `/logout` are in the public policy list on purpose (they must answer the 401 envelope / no-op logout). `supervisor-authorize` is a session-gated mutation |
| `Controllers/Api/App/LoginPageResource.java` | `/login` | `@PermitAll` (class) | Pre-auth page; makes the intent explicit instead of relying on the public list |
| `Controllers/AppEntryResource.java` | `/app` | `@PermitAll` (class) | Public entry redirect (`/app` is in the public list) |
| `Controllers/RootRedirectResource.java` | `/` | `@PermitAll` (class) | Public root |
| `Controllers/FallbackResource.java` | `/{remaining:.+}` | `@PermitAll` (class) | Public catch-all; **do not** add `@Authenticated` — it would swallow static assets (see `StaticAssetContractTest` history) |
| `Controllers/Api/App/RecibosRedirectResource.java` | `/app/recibos` | `@Authenticated` | Pure redirect into an authenticated inbox; no area role needed |
| `Controllers/Api/App/LayoutProbeResource.java` | `/app/_probe` | `@Authenticated` **or delete** | Class is `@Deprecated` scaffolding; deleting it is preferable. If kept, `@Authenticated` (it renders through the role-gated layout) |
| `Controllers/Api/OAuthController.java` | `/oauth` | `@PermitAll` on the token endpoint only | `POST /oauth/token` is in `EXEMPT_PATHS` (`PublicApiJwtFilter.java:66`); annotate `@PermitAll` and leave any other method annotated individually |
| `Controllers/DashboardMetricsController.java` | `/api/dashboard` | **defer** (see 3.4) | Bearer/session dual auth |
| `Controllers/SalesTrendController.java` | `/api/sales-trend` | **defer** | same |
| `Controllers/ProductPerformanceController.java` | `/api/product-performance` | **defer** | same |
| `Controllers/StockForecastController.java` | `/api/stock-forecast` | **defer** | same |
| `Controllers/Api/Accounting/*.java` (10 classes) | `/api/v1/accounting/*` | **defer / spike** | Bearer-scope identity, see 3.4 |

**Out of scope, owner: Mercatus work:** `Controllers/Api/Mercatus/*` (7 classes) and
`Controllers/Api/Marketplace/*` (5 classes). Do not annotate, do not add their prefixes to the
policy, do not touch CORS.

### 3.4 The Bearer-identity spike (must run before annotating accounting/analytics)

`PublicApiJwtFilter` sets a **JAX-RS** `SecurityContext` (383-400, 411-426) — for API clients the
roles come from the token `scope` claim; for Mercatus client tokens `isUserInRole` returns
`false` (417). Quarkus authorization (`@RolesAllowed`, `@Authenticated`) evaluates the **Quarkus
`SecurityIdentity`**, which for the cookie path is produced by `MercuriusIdentityProvider`. It is
not established that a `ContainerRequestContext.setSecurityContext(...)` call from this filter
feeds that identity.

**Spike (1–2 h, throwaway branch, revert after):**
1. Add `@Authenticated` to `InvoicesIssuedController` only.
2. Add an integration test that (a) calls `GET /api/v1/accounting/invoices/issued` with no token →
   expects today's 401 JSON envelope; (b) with a valid API token (see `Services/JwtTokenUtilTest`
   for token minting) → expects 200.
3. If (b) returns 401/403, **do not annotate** the `/api/v1` classes: the filter is the gate, and
   the correct fix is a filter-to-SecurityIdentity bridge, which is a separate task. Record the
   outcome in `docs/plans/auth-allowlist.md` as "identity supplied by JAX-RS SecurityContext
   only; annotated gate would lock out valid tokens".
4. Also pin the analytics endpoints (`/api/dashboard` etc.) with the same two cases; they accept
   a cookie session (filter lines 338-341), so `@Authenticated` *may* work there. Annotate only
   what the test proves.

### 3.5 Steps

1. **3.5.1** Rewrite the policy line: `/api/app/*` + `/Mercurius/api/app/*` (single pair)
   replacing the 25 enumerated `/api/app/...` entries; keep `/app/*` as is; **do not** add
   `/api/v1/*` or `/api/marketplace/*`. Gate:
   `mvn -q -Dtest='AuthJourneyTest,PublicApiJwtFilterRoutingTest,RootPathNormalizationTest,StaticAssetContractTest' test`.
2. **3.5.2** Annotate the 3 app data resources (Pos, ClientesReportes, Estacionalidad) +
   `AppAuthResource.supervisorAuthorize`. Gate:
   `mvn -q -Dtest='PosResourceTest,ClientesReportesPageTest,EstacionalidadReportesPageTest,RoleMatrixTest' test`.
3. **3.5.3** Annotate the public-by-design shells (`@PermitAll`) and delete or annotate
   `LayoutProbeResource`. Gate: `AuthJourneyTest` + `RoutingComprehensiveTest`.
4. **3.5.4** Run the spike; annotate only the classes the spike proves safe. Update
   `docs/plans/auth-allowlist.md` accordingly.
5. **3.5.5** Remove the now-unused entries from the allowlist; `AuthorizationCoverageTest` must
   then fail if any new `@Path` class appears without a gate or an explicit reason.

### 3.6 Verification

```powershell
mvn -q -Dtest='AuthorizationCoverageTest,RoleMatrixTest,AuthJourneyTest,PublicApiJwtFilterRoutingTest,RootPathNormalizationTest,StaticAssetContractTest,RoutingComprehensiveTest' test
mvn test
```

### 3.7 Rollback risk
**Medium-high for 3.5.1**, low elsewhere. The wildcard changes how *every* `/api/app/*` request is
classified; a mistake produces a login redirect where an API envelope is expected. That is why it
is a one-line commit with its own test gate and an explicit revert instruction. The annotations in
3.5.2/3.5.3 are additive and revertable one class at a time.

### 3.8 Effort
6–10 h (including the spike).

---

## 4. Recommended execution order

| Order | Phase | Why here | Effort |
|---|---|---|---|
| 1 | Phase 0.1 (transaction pin) + 0.2 (authorization allowlist test) | Cheap, and 0.1 must exist before 1.3.4 | 3 h |
| 2 | Phase 3.5.1 (policy wildcard) | One-line, highest leverage per minute, independent of the Java refactors | 1 h |
| 3 | Phase 3.5.2/3.5.3 (app annotations) | Small diffs, immediately reduces the 37 unannotated | 3 h |
| 4 | Phase 2 batch A (6 server-rendered charts) | Kills the class of bug that already shipped twice | 4 h |
| 5 | Phase 1.1 `ArticuloResource` | Lowest-risk god class (clear seams), unblocks the shared `SupervisorAuthService` | 14 h |
| 6 | Phase 2 batches B/D/C | Finishes the chart contract while the frontend context is loaded | 5 h |
| 7 | Phase 1.2 `PosResource` (incl. supervisor service) | Depends on 1.1.6 for the shared auth service | 12 h |
| 8 | Phase 1.4 `DevolucionesResource` | Shares `SupervisorAuthService`; NC logic is self-contained | 12 h |
| 9 | Phase 1.3 `FacturasRecibidasResource` | Needs the 0.1 transaction characterization first | 11 h |
| 10 | Phase 3.5.4/3.5.5 (Bearer spike + allowlist cleanup) | Last: it may end in "do not annotate", which is a documented decision, not code | 3 h |

Total ≈ 60–75 h of agent work. Phases 1.1–1.4 are independent of each other; if two agents run in
parallel, give each a disjoint package and forbid touching `AppRecursoBase`.
**Version bumps:** every step is a commit → PATCH bump (`2.15.6` → `2.15.7` …). Phase 2's new
`report-chart.js` public contract and Phase 3's policy change are behavior-compatible → still
PATCH under this repo's convention; only bump MINOR if a step adds a new user-facing capability.

---

## 5. What NOT to do

**God classes**
1. **Do not move `@Transactional` methods without moving the annotation.** The annotation is the
   boundary; a service call from another bean is proxied, a self-call is not.
2. **Do not add `@Transactional` to `procesarArticulos` or to the POS/Devolución pipelines.**
   `procesarArticulos` must inherit the caller's context (Phase 1.3 risk), and the Hacienda calls
   must run outside a transaction.
3. **Do not split `PosResource` into two JAX-RS classes.** Both halves live under
   `@Path("/api/app/pos")`; a second class means either a duplicate root or new URLs. Extract
   services instead.
4. **Do not rename or move `MSG_*` constants out of `ArticuloResource`** while tests reference
   them (`ArticuloResourceTest.java:323,345,378,497,623,679`), and do not move
   `DevolucionesResource.NcSummary` (`DevolucionesResourceTest.java:28`) in the same phase that
   changes behavior.
5. **Do not "clean up" the preserved quirks:** soft delete for articles, hard delete for
   promotions, inverted `contains` in `buscarFacturas` (679), `processed=false` on received
   invoice article update (1292), the `codigoBarra.isEmpty()` NPE path (1131-1134), the NC error
   swallow (1273). Each one has a comment or a test explaining why it is there.
6. **Do not re-query the `Articulos` entity inside an extracted service** and save that instance —
   the price history hangs off the caller's instance (`ArticulosService.update` cascade).
7. **Do not move the `synchronized (entry)` monitor away from the facturar pipeline**, and do not
   replace it with a lock on the service singleton.
8. **Do not delete or downgrade `LOG` statements during the move.** Move them verbatim (same
   level, same `source=` token) into the service; the audit trail is part of the contract.

**Charts**
9. **Do not keep building JSON by concatenation**, not even temporarily "for one page". That is
   the defect being removed.
10. **Do not `JSON.stringify(quteString)`** or interpolate a label into a JS string literal
    (current `pronosticos.html:144` does this inside `label: '{chartSeriesLabel}'`).
11. **Do not emit `java.sql.Date`/`java.util.Date` into the payload**; serialize `LocalDate`/
    ISO strings.
12. **Do not migrate all 11 templates in one commit.** One template per commit keeps revert
    trivial and the page tests meaningful.
13. **Do not verify visually with a headless browser.** Repo rule; ask the user first.

**Authorization**
14. **Do not add `/api/v1/*` or `/api/marketplace/*` to `quarkus.http.auth.permission.*`.** The
    first breaks Bearer clients (302 instead of 401); the second is Mercatus-owned.
15. **Do not annotate `FallbackResource` with `@Authenticated`** — it also serves static assets
    once a JAX-RS match wins (see `StaticAssetContractTest` javadoc).
16. **Do not annotate `AppAuthResource.me()`/`logout()`** — they must answer the 401 envelope /
    no-op logout without a session.
17. **Do not annotate the `/api/v1/accounting/*` or analytics controllers before the spike
    proves the token identity reaches Quarkus authorization.**
18. **Do not touch Mercatus/Marketplace/CORS.** *Out of scope, owner: Mercatus work.*

---

## Appendix A — Route inventories (for diff review)

**ArticuloResource** (26): `GET /` `GET /table` `GET /{codigo}` `POST /` `POST /` (form)
`PUT /{codigo}` `PUT /{codigo}` (form) `DELETE /{codigo}` `GET /revision/siguiente`
`POST /{codigo}/revision` `POST /{codigo}/revision/saltar` `POST /{codigo}/precio` `GET /cabys`
`GET /promociones/{id}` `POST /promociones` `POST /promociones` (form) `PUT /promociones/{id}`
`PUT /promociones/{id}` (form) `DELETE /promociones/{id}` `GET /formularios/articulos/nueva`
`GET /formularios/articulos/{codigo}` `GET /formularios/revision/{codigo}`
`GET /formularios/promociones/nueva` `GET /formularios/promociones/{id}`
`GET /formularios/promociones/articulos` `GET /formularios/ajuste/articulos`.

**PosResource** (28): `POST /scan` `POST /add` `DELETE /item/{codigo}` `POST /client`
`POST /payment-entries` `POST /facturar` `POST /cancel` `GET /cart` `POST /override-authorize`
`GET /facturas/{fileName:.+}` `GET /cart-panel` `POST /scan-form` `POST /item/{codigo}/qty-form`
`POST /item/{codigo}/qty-set-form` `POST /item/{codigo}/remove-form` `GET /client-search`
`GET /client-picker` `POST /client-select-form` `GET /articulos-picker`
`POST /articulo-add-form` `GET /puntos-preview` `POST /puntos-form` `GET /tipo-cambio`
`GET /payment-dialog` `POST /payment-entries-form` `POST /override-authorize-form`
`POST /facturar-form` `POST /cancel-form`.

**FacturasRecibidasResource** (18): `GET /` `GET /tabla` `GET /{id}`
`PUT /{id}/lineas/{lineaId}` (json + form) `GET /{id}/prevalidacion`
`POST /{id}/mensaje-receptor` (form + json) `PUT /{id}/procesar` (form + json)
`PUT /{id}/pagar` (form + json) `PUT /{id}/toggle` (form + json) `GET /consecutivo-receptor`
`POST /upload`.

**DevolucionesResource** (5): `GET /facturas` `GET /{id}/lineas` `GET /{id}/authform`
`POST /initiate` `POST /{id}/authorize`.

---

## Appendix B — Method map quick reference

**ArticuloResource**: see §Phase 1.1 table. Anchor symbols: `list` 308, `table` 358,
`detail` 395, `doCreate` 471, `doUpdate` 599, `delete` 684, `revisionSiguiente` 725,
`revisar` 754, `saltar` 848, `updatePrecio` 896, `isSupervisorAuthorized` 957,
`buscarCabys` 993, `promoDetail` 1030, `doSavePromocion` 1156, `formSelectorArticulos` 1310,
`fullPageModel` 1429, `buildTableModel` 1495, `calcularPrecioConUtilidad` 1654,
`calcularPrecioConIVA` 1682, `findCabys` 1702, `lastPrecioOf` 1718, `stockOf` 1729,
`filterArticulos` 1747, `sortArticulos` 1791, `currentUser` 1909, `toListDTO` 2055,
`toDetailDTO` 2071.

**PosResource**: `scan` 239, `add` 266, `removeItem` 320, `selectClient` 357,
`paymentEntries` 396, `facturar` 447, `doFacturar` 476, `doFacturarInterior` 494,
`repetirFacturado` 830, `cancel` 929, `cart` 960, `overrideAuthorize` 981, `facturaPdf` 1037,
`cartPanel` 1087, `scanForm` 1106, `qtyForm` 1136, `qtySetForm` 1186, `removeForm` 1225,
`clientSearch` 1256, `clientPicker` 1293, `articuloAddForm` 1370, `puntosPreview` 1404,
`puntosForm` 1435, `tipoCambio` 1473, `paymentDialog` 1490, `paymentEntriesForm` 1510,
`overrideAuthorizeForm` 1540, `facturarForm` 1597, `cancelForm` 1626, `panelModel` 1656,
`dialogModel` 1698, `resultadoModel` 1724, `tipoCambioBadge` 1758, `stagePagos` 1853,
`zipPagos` 1870, `toMessage` 1967, `toCartSummary` 1986, `currentUsername` 1932.

**FacturasRecibidasResource**: `list` 284, `tabla` 309, `tablaModel` 330, `detalle` 539,
`corregirLineaJson` 585, `corregirLineaForm` 596, `doCorregirLinea` 601, `prevalidacion` 662,
`mensajeReceptorForm` 731, `mensajeReceptorJson` 742, `doMensajeReceptor` 747, `procesarForm` 937,
`procesarJson` 949, `doProcesar` 953, `pagarForm` 994, `pagarJson` 1005, `doPagar` 1009,
`toggleForm` 1044, `toggleJson` 1055, `doToggle` 1059, `procesarArticulos` 1118,
`consecutivoReceptor` 1375, `upload` 1428, `processSingleFile` 1465,
`importarProductosDeFacturaRechazada` 1511, `extractConsecutivoDe` 1572, `prevalidateXml` 1614.

**DevolucionesResource**: `facturas` 262, `lineas` 313, `authform` 349, `initiate` 386,
`authorize` 477, `procesarDevolucion` 580, `buscarFacturas` 668, `lineaRows` 717,
`parseSelecciones` 741, `validarGuardias` 790, `totalDevolucion` 834,
`buscarClienteDeFactura` 842, `enviarNcAHacienda` 931, `registrarPendienteDeEnvioNc` 974,
`generarNcElectronica` 1025, `printUrl` 1282, `facturasFragment` 1350, `lineasFragment` 1382,
`includeKitModal` 1439, `authformFragment` 1464, `ncSummaryFragment` 1484,
`historialOobFragment` 1519.

---

## Appendix C — Authorization matrix (37 unannotated classes)

| Group | Count | Classes | Decision |
|---|---|---|---|
| Mercatus (out of scope, owner: Mercatus work) | 7 | `Mercatus/{Articles,ClientAuth,Departamentos,Families,Inventory,Order,Promotions}Controller` | Do not touch |
| Marketplace (out of scope, owner: Mercatus work) | 5 | `Marketplace/{Auth,Cart,Order,Product,Profile}Controller` | Do not touch |
| Public by design | 8 | `RootRedirectResource` (`/`), `FallbackResource`, `AppEntryResource` (`/app`), `LoginPageResource` (`/login`), `OAuthController` (`/oauth`), `RecibosRedirectResource`, `LayoutProbeResource`, `AppAuthResource` | `@PermitAll` on public shells; `@Authenticated` on `supervisorAuthorize`; delete `LayoutProbeResource` if possible |
| App data surface | 3 | `PosResource`, `Reportes/ClientesReportesResource`, `Reportes/EstacionalidadReportesResource` | Annotate (Phase 3.5.2) |
| Accounting API (Bearer) | 10 | `Accounting/{CashRegister,CreditNotes,ExchangeRates,InventoryValuation,InvoiceDetail,InvoicesIssued,InvoicesReceived,Payments,Reports,Suppliers}Controller` | Spike first; annotate only if proven |
| Analytics API (Bearer/session) | 4 | `DashboardMetricsController`, `SalesTrendController`, `ProductPerformanceController`, `StockForecastController` | Spike first; annotate only if proven |
| **Total** | **37** | | |

Policy coverage today (measured on `application.properties:368`): 25 `/api/*` prefixes, each with
and without `/Mercurius`. Missing from the enumeration but still protected by annotation:
`/api/app/historico-emitidos`, `/api/app/importacion-masiva`. The wildcard in Phase 3.5.1 makes
that class of drift impossible.

---

## Appendix D — Chart inventory (current state → target)

| # | Template | Canvas id | Payload today | Batch |
|---|---|---|---|---|
| 1 | `reportes/departamentos.html` | `grafica-departamentos` | inline JS arrays (52-53) | A |
| 2 | `reportes/familias.html` | `grafica-familias` | inline JS arrays (52-53) | A |
| 3 | `reportes/resumen.html` | `grafica-resumen` | inline JS arrays (89-90) | A |
| 4 | `reportes/merma.html` | `grafica-merma` | inline JS arrays (61-62) + server empty branch (49) | A |
| 5 | `reportes/margenes.html` | `margenes-chart` | `window.margenesChartData` (166-171) | A |
| 6 | `reportes/estacionalidad.html` | `estacionalidad-{mensual,dias,departamentos,familias}-chart` | inline JS arrays (181-188) | A |
| 7 | `reportes/pronosticos.html` | `grafica-pronosticos` | `<script type="application/json">` (117) | B |
| 8 | `reportes/tendencias.html` | `grafica-tendencias` | `fetch /api/sales-trend/daily` | C |
| 9 | `reportes/rendimiento.html` | `grafica-rendimiento` | `fetch /api/product-performance/department-performance` | C |
| 10 | `reportes/ventas-fecha-historica.html` | `grafica-fecha-historica` | `fetch /api/sales-trend/same-date-history` (multi-series) | C |
| 11 | `dashboard/index.html` | `grafica-ventas-hora`, `grafica-tendencia-semanal` | fetch `/app/dashboard/data/{hourly,weekly}` + JSON fallbacks (115-116) | D |

Resources that feed them: `Reportes/{Departamentos,Familias,MargenesReportes,Merma,Resumen}Resource`
(`chartLabels`/`chartValues`), `Reportes/PronosticosResource` (`chartJson` via a private
`ObjectMapper`), `DashboardResource` (`hourlyJson`/`weeklyJson`), and the three read endpoints
`SalesTrendController`, `ProductPerformanceController`.

---

## Appendix E — Things this plan could not determine

1. **Whether `ContainerRequestContext.setSecurityContext(...)` from `PublicApiJwtFilter`
   satisfies Quarkus `@Authenticated`/`@RolesAllowed`.** No test proves it either way; Phase 3.5.4
   is the spike that decides. Until then, the accounting/analytics classes stay unannotated by
   design.
2. **The real transaction semantics of `PUT /{id}/procesar`.** The code has `@Transactional` on
   the resource and the javadoc claims per-service commits. Phase 0.1 measures it; the plan
   deliberately does not assume the answer.
3. **Whether `/api/app/*` as a wildcard policy path overrides the explicit public carve-outs
   reliably on Quarkus 3.36.2.** The comment at `application.properties:297-306` says ordering
   works; the plan verifies it with `AuthJourneyTest` and keeps an enumeration fallback.
4. **Two measurement discrepancies against the P2 finding:** 37 unannotated classes (not 36) and
   14 `MercuriusCharts.create` calls (not 24; 50 `MercuriusCharts` references). Both are
   itemized above so the plan targets reality, not the summary.
5. **`EstacionalidadReportesResource` role intent.** Its javadoc says the legacy constraint was
   "any authenticated", so the plan proposes `@Authenticated`; if the owner wants the report
   grouped with `Resumen/Merma` (`{admin, inventario}`), that is a one-word change but a
   deliberate behavior change, so it is left as a question, not a step.
6. **Whether the uncommitted working-tree edits (seen in `git status` on 2026-10-28) are part of
   the intended baseline.** They touch `app/pom.xml`, `application.properties`, three
   `Reportes/*` resources and five `Services/*` classes. Any of them can shift a line reference
   or change a policy line; re-measure before executing Phase 3.
