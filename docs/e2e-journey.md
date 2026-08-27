# F3 North-Star E2E Acceptance Journey

**Status: intentionally RED until migration completes.** This journey *is* the
definition of "the app works" for F3 verification. It exists in three synced
forms:

| Artifact | Purpose |
|---|---|
| `scripts/e2e-journey.ps1` | Executable PowerShell harness against a running app on `http://localhost:8081`. Stops on first failed step, printing its name. Exit 0 = green. |
| `src/test/java/e2e/JourneyE2ETest.java` | `@QuarkusTest` twin, `@Disabled("migration-completion")` so it never affects current gates. Full RestAssured implementation; enable by deleting the annotation when migration lands. |
| `docs/e2e-journey.md` | This contract: endpoint / payload / assertion per step. |

Base URL: `http://localhost:8081` — Quarkus root-path `/Mercurius` is **included
in every path below**. Credentials: `admin` / `admin123` (seeded by
`import-test.sql`, cost-12 BCrypt). Supervisor for overrides: same admin
(legacy parity).

---

## Journey contract

### Step 1 — Login page renders the form-auth target

- **Request:** `GET /Mercurius/login`
- **Handler:** `LoginPageResource` (public permit policy)
- **Assert:** status `200`; body contains `j_security_check`.

### Step 2 — Form login issues the session cookie

- **Request:** `POST /Mercurius/j_security_check`,
  `Content-Type: application/x-www-form-urlencoded`
- **Payload:** `j_username=admin&j_password=admin123`
- **Assert:** status `302` (redirect to landing page); response issues the
  encrypted form-auth session cookie **`quarkus-credential`**.
- All later requests carry this cookie (+ `X-CSRF-TOKEN` header when a
  `csrftoken`/`csrf-token` cookie was issued — defensive parity with the
  existing suite).

### Step 3 — Create an artículo

- **Request:** `POST /Mercurius/api/app/articulos` (JSON)
- **Handler:** `ArticuloResource.create` → `doCreate` (`ArticuloForm`)
- **Payload:**

```json
{
  "nombre": "E2E Journey Articulo <unique>",
  "codigoBarra": "E2EJ<unique>",
  "descripcion": "Created by e2e journey",
  "unidadMedida": "Unid",
  "unidadMedidaComercial": "Unidad",
  "departamentoId": 1,
  "familiaId": 1,
  "cabysCodigo": "501010101",
  "precioCostoSinIVA": 10000,
  "porcentajeUtilidad": 20,
  "exento": false,
  "stockOptimo": 50,
  "diasStockSeguridad": 7
}
```

- **Assert:** status `201`; body `data.codigo > 0` (captured for steps 4 & 6).
- Legacy gates honored: both `departamentoId` and `familiaId` are required
  (`400 VALIDATION_ERROR` otherwise); duplicate barcode → `409`.
- With CAByS `501010101` present at impuesto `13`, precioFinal computes
  deterministically: CEILING(10000 × 1.20 × 1.13) = 13560.

### Step 4 — Positive stock via inventario adjustment

- **Request:** `POST /Mercurius/api/app/inventario/ajustes` (JSON)
- **Handler:** `InventarioResource.crearAjuste` → `doCrearAjuste`
- **Payload:** `{"articuloId": <codigo>, "cantidad": 25, "tipoMovimiento":
  "Ajuste manual", "notas": "E2E journey initial stock"}`
- **Assert:** status `201` (non-HX JSON path; `200` tolerated by the script);
  success envelope carries the created adjustment. The row is created
  `processed=true` immediately (legacy `createInventarioDialog` parity), so
  no revision approval is needed before the POS sale.

### Step 5 — Received-invoice upload + prevalidation PASS

- **Request:** `POST /Mercurius/api/app/facturas-recibidas/upload`
  (`multipart/form-data`, field **`files`**, `application/xml`)
- **Fixture:** `mercurius-quarkus/src/test/resources/fixtures/recibidos/factura-recibida-valida.xml`
  (v4.4-shaped `FacturaElectronica`). Both runners re-stamp `Clave` (50 digits)
  and `NumeroConsecutivo` uniquely per run because the parser skips duplicate
  consecutivos.
- **Assert:** status `200`¹; `data.resultados[0].exito == true`;
  `data.exitosos >= 1`.
- **Id lookup:** `GET /Mercurius/api/app/facturas-recibidas?bucket=todas&q=<consecutivo>`
  → `data[0].id`.
- **Prevalidation:** `GET /Mercurius/api/app/facturas-recibidas/{id}/prevalidacion`
  → status `200`, `data.isValid == true`, `data.errorCount == 0` (**PASS**).
- A second upload flips `<CondicionVenta>01</CondicionVenta>` → `02` and
  `<PlazoCredito>0</PlazoCredito>` → `30`: a **credit receipt** that seeds the
  Recibos flow of step 7 (starts with `paid=false`).

### Step 6 — POS sale end-to-end

| # | Request | Payload | Assert |
|---|---|---|---|
| 6a | `POST /Mercurius/api/app/pos/scan` ×2 | `{"codigoBarra":"<barcode>","cantidad":1}` | `200` each |
| 6b | `GET /Mercurius/api/app/pos/cart` | — | `200`; `data.items[0].cantidad == 2` (cart snapshot qty=2); capture `data.totalCarrito` |
| 6c | `POST /Mercurius/api/app/pos/client` | `{"clientCode":1}` | `200` (puntos redemption requires a selected client) |
| 6d | `POST /Mercurius/api/app/pos/payment-entries` | `[{"metodoPago":"01","monto":<total>},{"metodoPago":"06","monto":1000}]` | `200`; `data.vuelto >= 0` |
| 6e | `POST /Mercurius/api/app/pos/override-authorize` | `{"username":"admin","password":"admin123"}` | `200`; `data.authorizedBy == "admin"` |
| 6f | `POST /Mercurius/api/app/pos/facturar` | `{"tipoDocumento":"04","puntosARedimir":10}` | `200`; non-empty `data.pdfUrl` |
| 6g | `GET <pdfUrl>` | — | `200`; body starts with `%PDF-` |

Notes:
- Payment method codes per `PagoEntry.metodoPagoLabel`: `01` = Efectivo,
  `06` = SINPE Móvil (split payment).
- `facturar` gates exercised in order: override gate (6e satisfies it),
  settings gate (AppSettings must exist, `estatus != false` — else
  `503 NO_SETTINGS` / `409 FACTURACION_DESHABILITADA`), cart gate, payment
  sufficiency (`FALTANTE_DE_PAGO` on shortfall).
- `puntosARedimir` is clamped to the client balance (1 punto = ₡1); the seeded
  client starts at zero points, so redemption is accepted but neutral.
- `pdfUrl` points at `GET /Mercurius/api/app/pos/facturas/tiqueteElectronico_<id>.pdf`.

### Step 7 — Recibos pay/process action

- **Request:** `POST /Mercurius/api/app/recibos/{id}/pagar` (JSON `{}`) on the
  credit receipt uploaded in step 5.
- **Contract pinned by this north-star** (T27 owns the implementation; the
  read-only Recibos report pages explicitly defer pay/process actions there):
  flipping `ComprobantesRecibidos.paid` `false → true` is the observable state
  change behind the legacy "Recibos Pendientes/Vencidos" pages.
- **Assert:** status `200`; re-listing the inbox shows `data[0].paid == true`.

### Step 8 — Dataset exports stream real bytes

- **Request:** `POST /Mercurius/api/app/export`,
  `application/x-www-form-urlencoded`²
- **Handler:** `ExportResource.download` (`@FormParam type|dataset`)
- **Matrix** (dataset↔format support is enforced server-side):

| type | dataset | Result | Assert |
|---|---|---|---|
| `xlsx` | `stock-alerts` | workbook attachment | `200`, body starts `PK\x03\x04` |
| `pdf` | `articulos` | PDF attachment | `200`, body starts `%PDF-` |

Other valid keys: `inventario`→pdf, `profit-margins`→xlsx. Unknown combos → 404.

### Step 9 — Logout invalidates the session

- **Request:** `POST /Mercurius/api/app/auth/logout`
- **Assert:** status `303` (seeOther); `Location` ends with `/Mercurius/login`.
- **Replay:** resend the OLD `quarkus-credential` value on
  `GET /Mercurius/api/app/auth/me` → `401` (anonymous identity envelope) or
  `302` (form-auth redirect to login). Either proves server-side invalidation.

---

## Deviations from the original F3 sketch (controller-sourced truth)

1. **Upload returns `200`, not `201`.** `FacturasRecibidasResource.upload`
   responds `Response.ok(...)` with per-file results; asserting 201 would keep
   the north-star red forever.
2. **Export is `POST` form-urlencoded, not `GET` query params.**
   `ExportResource` declares `@POST` + `@Consumes(FORM_URLENCODED)` with
   `@FormParam type/dataset`. Also, `articulos` supports **pdf only** — the
   xlsx magic-byte check uses dataset `stock-alerts` (xlsx-only), pdf uses
   `articulos`.
3. **Recibos endpoint pinned as `POST /api/app/recibos/{id}/pagar`.** No
   pay/process resource exists yet (T27 lane); this journey defines the
   contract its implementation must satisfy, with `paid` as the flipped state.

## Preconditions (F3 environment)

| Requirement | Source |
|---|---|
| Users `admin/admin123` (BCrypt cost 12) | `import-test.sql` |
| Departamento id=1, Familia id=1, Clients code=1 ('Cliente Contado') | `import-test.sql` |
| CAByS `501010101` (impuesto 13) and `0111010010010` (fixture line) rows ACTIVO | Seeded by the Java test via `CabysService`; for the PS run the DB must already carry them (legacy CABYS import or seed extension) |
| AppSettings row with facturación enabled | Java test seeds via `AppSettingsService.findOrCreateCurrent()`; PS run requires a configured instance (else step 6 fails with the explicit `NO_SETTINGS` envelope) |
| PostgreSQL on `localhost:5433` (`mercurius` / `mercurius_test`) | `application.properties` |

## Running

```powershell
# executable twin (app expected on :8081)
powershell -ExecutionPolicy Bypass -File scripts\e2e-journey.ps1

# QuarkusTest twin: remove @Disabled("migration-completion") when migration
# completes, then run as part of the normal test suite (mvn test).
```

The script is pure ASCII on purpose: PowerShell 5.1 reads BOM-less files as
ANSI, and multi-byte characters corrupt parsing/string literals.
