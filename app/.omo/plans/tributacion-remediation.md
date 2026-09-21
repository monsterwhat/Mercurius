# tributacion-remediation - Work Plan

## TL;DR (For humans)

**What you'll get:** Complete remediation of 7 tributación attention points in Mercurius Quarkus Costa Rica electronic invoicing system — automated MensajeReceptor, precise exchange rates, outbound exoneraciones, proper tax code validation, and UI hardening.

**Why this approach:** Fixes are ordered by legal/financial risk (MR deadline = lost tax credits first, precision issues second, structural improvements last). Each fix is isolated to prevent cross-contamination between unrelated concerns.

**What it will NOT do:** Will not add new document types, will not change the Hacienda API integration logic, will not modify the signing/XAdES process, will not touch the PDF/QR generation.

**Effort:** Large (3 implementation waves + final verification)
**Risk:** Medium - DB migrations require careful rollback; MR auto-send affects tax compliance
**Decisions to sanity-check:** P3 scope (exoneraciones is the largest single item), P1 auto-send behavior (always accept vs. configurable)

Your next move: Run `$start-work tributacion-remediation` to begin execution. Full execution detail follows below.

---

> TL;DR (machine): Large effort, medium risk, 7 tributación fixes across 3 waves + final verification

## Scope
### Must have
- P1: MensajeReceptor auto-send before deadline (extract from FacturasController, add scheduled task)
- P2: TipoCambio double→BigDecimal with 5-decimal precision
- P3: Exoneraciones outbound support (new entity, ComprobanteService changes, UI)
- P4+P5: Cabys int→String with DB validation and dynamic tax code resolution
- P6: CondicionVenta UI validation before Strategy layer
- P7: XmlEncabezadoFlattener monitoring (no code change, alert only)

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No new Hacienda API endpoints or changes to existing API integration
- No modifications to XAdES signing or certificate handling
- No changes to PDF/QR generation logic
- No new document types (FE, TE, ND, NC, REP, FEC remain as-is)
- No changes to the email processing or supplier notification logic
- No database schema changes beyond the two explicit migrations (TipoCambio, Cabys)

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + manual QA scenarios
- Evidence: .omo/evidence/tributacion-remediation/task-<N>.<ext>

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

**Wave 1 (Foundation)**: P2 + P4+P5 — Model and DB changes that other fixes depend on
**Wave 2 (Service + UI)**: P1 + P6 — Service extraction and validation hardening
**Wave 3 (Feature)**: P3 — Exoneraciones outbound (largest scope, depends on Wave 1 model changes)
**Wave 4 (Final)**: F1-F4 — Verification wave (all parallel)

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 (P2: TipoCambio model) | None | 2, 3 | 4 |
| 2 (P2: TipoCambio service) | 1 | 3 | 4, 5, 6 |
| 3 (P2: TipoCambio migration) | 1 | — | 4, 5, 6 |
| 4 (P4+P5: Cabys model) | None | 5, 6 | 1 |
| 5 (P4+P5: Cabys validation) | 4 | 6 | 2, 3 |
| 6 (P4+P5: Cabys migration) | 4 | — | 2, 3 |
| 7 (P1: MensajeReceptorService) | None | 8, 9 | 1, 4 |
| 8 (P1: ProgramadorTareas auto-send) | 7 | — | 2, 3, 5, 6 |
| 9 (P1: FacturasController refactor) | 7 | — | 2, 3, 5, 6 |
| 10 (P6: CondicionVenta validation) | 4 | — | 2, 3, 7, 8, 9 |
| 11 (P3: ProductoExoneracion entity) | 4 | 12, 13 | 2, 3, 5, 6, 7, 8, 9, 10 |
| 12 (P3: ComprobanteService exoneraciones) | 11 | 13 | 2, 3, 5, 6, 7, 8, 9, 10 |
| 13 (P3: UI forms for exoneraciones) | 11, 12 | — | 2, 3, 5, 6, 7, 8, 9, 10 |
| 14 (P7: Monitoring alert) | None | — | 1-13 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

### Wave 1: Foundation (P2 + P4+P5) — Parallel

- [ ] 1. P2: Change TipoCambio model from double to BigDecimal
  What to do / Must NOT do: Change `TipoCambio.java` fields `valorCompra` and `valorVenta` from `double` to `BigDecimal`. Add `@Column(precision = 18, scale = 5)` annotations. Add `import java.math.BigDecimal`. Do NOT change any other model files. Do NOT modify the constructor or Lombok annotations.
  Parallelization: Wave 1 | Blocked by: None | Blocks: 2, 3
  References: Models/TipoCambio.java:18-19 (double fields), src/main/resources/xsd/v4.4/FacturaElectronica_V4.4.xsd:1838-1848 (DecimalDineroType definition)
  Acceptance criteria (agent-executable): `grep -n "private.*valorCompra\|private.*valorVenta" Models/TipoCambio.java` shows `BigDecimal` type; `grep -n "precision.*18.*scale.*5" Models/TipoCambio.java` shows annotations
  QA scenarios:
  - happy: Model compiles with BigDecimal fields, Evidence .omo/evidence/tributacion-remediation/task-1-compile.txt
  - failure: Compilation error if BigDecimal import missing, Evidence .omo/evidence/tributacion-remediation/task-1-failure.txt
  Commit: Y | feat(tipo-cambio): change double fields to BigDecimal for XSD compliance

- [ ] 2. P2: Fix TipoCambioService.parseTipoCambio to preserve decimals
  What to do / Must NOT do: In `TipoCambioService.java:73-74`, replace `Math.floor(ventaNode.get("valor").asDouble())` with `new BigDecimal(ventaNode.get("valor").asText()).setScale(5, RoundingMode.HALF_UP)`. Same for compraNode. Add `import java.math.BigDecimal` and `import java.math.RoundingMode`. Do NOT change the BCCR API URL or response parsing logic.
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 3
  References: Services/TipoCambioService.java:73-74 (Math.floor lines), Services/TipoCambioService.java:65-86 (parseTipoCambio method)
  Acceptance criteria (agent-executable): `grep -n "Math.floor\|new BigDecimal.*asText.*setScale" Services/TipoCambioService.java` shows BigDecimal with setScale, no Math.floor
  QA scenarios:
  - happy: parseTipoCambio("523.47") returns TipoCambio with valorVenta = 523.47000, Evidence .omo/evidence/tributacion-remediation/task-2-happy.txt
  - failure: parseTipoCambio("invalid") returns null (existing error handling), Evidence .omo/evidence/tributacion-remediation/task-2-failure.txt
  Commit: Y | fix(tipo-cambio): preserve decimal precision from BCCR API

- [ ] 3. P2: Create DB migration script for TipoCambio columns
  What to do / Must NOT do: Create a SQL migration file `src/main/resources/db/migration/V2026_08_20__tipo_cambio_bigdecimal.sql` that alters `TipoCambio` table columns `valor_compra` and `valor_venta` from `DOUBLE` to `DECIMAL(18,5)`. Include backfill logic to preserve existing values. Include a rollback section in comments. Do NOT execute the migration — just create the script.
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: None
  References: Models/TipoCambio.java:18-19 (column names), Quarkus Flyway migration convention
  Acceptance criteria (agent-executable): File exists at specified path; `grep -n "ALTER TABLE.*tipo_cambio\|DECIMAL(18,5)" <file>` shows migration commands
  QA scenarios:
  - happy: SQL file is syntactically valid, Evidence .omo/evidence/tributacion-remediation/task-3-sql.txt
  - failure: Missing rollback section flagged, Evidence .omo/evidence/tributacion-remediation/task-3-failure.txt
  Commit: Y | chore(db): add TipoCambio DECIMAL migration script

- [ ] 4. P4+P5: Change Cabys.impuesto from int to String
  What to do / Must NOT do: Change `Cabys.java:30` from `private int impuesto` to `private String impuesto`. Update the constructor at line 39 to accept `String`. Add `@Column(length = 2)` annotation. Do NOT change the `equals` or `hashCode` methods (they don't use impuesto). Do NOT change any other model files.
  Parallelization: Wave 1 | Blocked by: None | Blocks: 5, 6
  References: Models/Cabys.java:30 (int impuesto), Models/Cabys.java:39 (constructor), src/main/resources/xsd/v4.4/FacturaElectronica_V4.4.xsd (CodigoImpuesto is string)
  Acceptance criteria (agent-executable): `grep -n "private.*impuesto" Models/Cabys.java` shows `String` type; `grep -n "length.*2" Models/Cabys.java` shows annotation
  QA scenarios:
  - happy: Model compiles with String impuesto, Evidence .omo/evidence/tributacion-remediation/task-4-compile.txt
  - failure: Compilation error if constructor signature mismatch, Evidence .omo/evidence/tributacion-remediation/task-4-failure.txt
  Commit: Y | feat(cabys): change impuesto from int to String for Hacienda compliance

- [ ] 5. P4+P5: Add validation to Tipo_CodigoImpuesto.fromCodigo
  What to do / Must NOT do: In `Tipo_CodigoImpuesto.java:35-42`, add a safe lookup method `fromCodigoOrNull(String codigo)` that returns `null` instead of throwing `IllegalArgumentException` for invalid codes. Keep the existing `fromCodigo` method unchanged. Add a `boolean isValidCodigo(String codigo)` convenience method. Do NOT change the enum values or their descriptions.
  Parallelization: Wave 1 | Blocked by: 4 | Blocks: 6
  References: Models/Enums/Tipo_CodigoImpuesto.java:35-42 (fromCodigo method), Models/Enums/Tipo_CodigoImpuesto.java:6-15 (enum values)
  Acceptance criteria (agent-executable): `grep -n "fromCodigoOrNull\|isValidCodigo" Models/Enums/Tipo_CodigoImpuesto.java` shows both new methods
  QA scenarios:
  - happy: fromCodigoOrNull("01") returns IVA; fromCodigoOrNull("99") returns OTROS; fromCodigoOrNull("99") returns null for invalid
  - failure: isValidCodigo("XX") returns false
  Commit: Y | feat(codigo-impuesto): add safe lookup methods for validation

- [ ] 6. P4+P5: Create DB migration script for Cabys impuesto type
  What to do / Must NOT do: Create a SQL migration file `src/main/resources/db/migration/V2026_08_20__cabys_impuesto_string.sql` that alters `Cabys` table column `impuesto` from `INT` to `VARCHAR(2)`. Include backfill logic to convert existing int values to zero-padded strings (e.g., 1→'01', 13→'13'). Include a rollback section in comments. Do NOT execute the migration — just create the script.
  Parallelization: Wave 1 | Blocked by: 4 | Blocks: None
  References: Models/Cabys.java:30 (impuesto field), Quarkus Flyway migration convention
  Acceptance criteria (agent-executable): File exists at specified path; `grep -n "ALTER TABLE.*cabys\|VARCHAR(2)" <file>` shows migration commands
  QA scenarios:
  - happy: SQL file is syntactically valid with backfill, Evidence .omo/evidence/tributacion-remediation/task-6-sql.txt
  - failure: Missing backfill logic flagged, Evidence .omo/evidence/tributacion-remediation/task-6-failure.txt
  Commit: Y | chore(db): add Cabys impuesto VARCHAR migration script

### Wave 2: Service + UI (P1 + P6) — Parallel

- [ ] 7. P1: Extract MensajeReceptor logic from FacturasController into MensajeReceptorService
  What to do / Must NOT do: Create a new `Services/MensajeReceptorService.java` with `@ApplicationScoped` and `@Named`. Move the MR-sending logic from `FacturasController.procesarMensajeReceptor()` (lines 960-1060) into a new method `enviarMensajeReceptor(ComprobantesRecibidos factura, int codigoMensaje, String accion, BigDecimal montoTotalImpuesto, BigDecimal montoTotalFactura)`. The new service should inject `AppSettingsService`, `HaciendaSigner`, `HaciendaApiService`, `ConsecutivoReceptorService`, `ComprobanteService`, `AlertasService`. Do NOT change the existing `procesarMensajeReceptor` method yet — just create the new service.
  Parallelization: Wave 2 | Blocked by: None | Blocks: 8, 9
  References: Controllers/FacturasController.java:960-1060 (procesarMensajeReceptor), Controllers/FacturasController.java:984-1001 (consecutivo generation), Controllers/FacturasController.java:1003-1006 (XML generation), Controllers/FacturasController.java:1014-1019 (signing), Controllers/FacturasController.java:1032-1039 (Hacienda API call)
  Acceptance criteria (agent-executable): File `Services/MensajeReceptorService.java` exists; `grep -n "enviarMensajeReceptor" Services/MensajeReceptorService.java` shows the method; `grep -n "class MensajeReceptorService" Services/MensajeReceptorService.java` shows @ApplicationScoped
  QA scenarios:
  - happy: Service compiles with all injected dependencies, Evidence .omo/evidence/tributacion-remediation/task-7-compile.txt
  - failure: Missing injection annotation flagged, Evidence .omo/evidence/tributacion-remediation/task-7-failure.txt
  Commit: Y | feat(mr-service): extract MensajeReceptor logic from FacturasController

- [ ] 8. P1: Add scheduled auto-send task to ProgramadorTareas
  What to do / Must NOT do: In `ProgramadorTareas.java`, add a new `@Scheduled(cron = "0 0 6 * * ?")` method `enviarMensajesReceptorPendientes()`. This method should: (1) inject `MensajeReceptorService`, (2) query `ComprobantesRecibidosService` for invoices where `getDiasRestantesMensajeReceptor() <= 2` and `haciendaMensajeReceptorEstado == null`, (3) for each, call `mensajeReceptorService.enviarMensajeReceptor(factura, 1, "ACEPTADO", ...)`, (4) log alerts for successes and failures. Do NOT modify the existing `verificarVencimientoMensajeReceptor` method — keep it as-is for backward compatibility.
  Parallelization: Wave 2 | Blocked by: 7 | Blocks: None
  References: Utils/ProgramadorTareas.java:272-299 (verificarVencimientoMensajeReceptor), Utils/ProgramadorTareas.java:44-45 (@Singleton, @Inject fields), Services/ComprobantesRecibidosService.java (need to add findProximosVencerPendientes method)
  Acceptance criteria (agent-executable): `grep -n "enviarMensajesReceptorPendientes" Utils/ProgramadorTareas.java` shows new scheduled method; `grep -n "@Scheduled.*cron.*0 0 6" Utils/ProgramadorTareas.java` shows cron expression
  QA scenarios:
  - happy: Method compiles and scheduled annotation is present, Evidence .omo/evidence/tributacion-remediation/task-8-compile.txt
  - failure: Missing injection for MensajeReceptorService flagged, Evidence .omo/evidence/tributacion-remediation/task-8-failure.txt
  Commit: Y | feat(mr-auto-send): add scheduled auto-send for pending MensajeReceptor

- [ ] 9. P1: Refactor FacturasController to use MensajeReceptorService
  What to do / Must NOT do: In `FacturasController.java:960-1060`, refactor `procesarMensajeReceptor()` to delegate to `MensajeReceptorService.enviarMensajeReceptor()`. Remove the duplicated logic. Keep the FacesContext messaging and UI feedback in the controller. Do NOT change the method signature or the UI button bindings.
  Parallelization: Wave 2 | Blocked by: 7 | Blocks: None
  References: Controllers/FacturasController.java:960-1060 (procesarMensajeReceptor), Services/MensajeReceptorService.java (new service from todo 7)
  Acceptance criteria (agent-executable): `grep -n "mensajeReceptorService.enviarMensajeReceptor" Controllers/FacturasController.java` shows delegation; `grep -n "generateMensajeReceptorXml\|haciendaSigner.signXml" Controllers/FacturasController.java` shows no direct calls (removed)
  QA scenarios:
  - happy: Controller compiles and delegates to service, Evidence .omo/evidence/tributacion-remediation/task-9-compile.txt
  - failure: Missing import for MensajeReceptorService flagged, Evidence .omo/evidence/tributacion-remediation/task-9-failure.txt
  Commit: Y | refactor(facturas-controller): delegate MR logic to MensajeReceptorService

- [ ] 10. P6: Add CondicionVenta validation in FacturasController
  What to do / Must NOT do: In `FacturasController.java`, before the invoice is sent to the Strategy layer, add a validation check for `CondicionVenta` against `getCondicionVentaPermitidas()` for the target document type. This should be a new private method `validarCondicionVentaFactura(String condicionVenta, String codigoDocumento)` that throws `IllegalArgumentException` if invalid. Call this method before `buildEncabezado()`. Do NOT change the existing `DocumentoStrategy.validarCondicionVenta()` method.
  Parallelization: Wave 2 | Blocked by: 4 | Blocks: None
  References: Controllers/FacturasController.java (find buildEncabezado call), Services/Strategies/DocumentoStrategy.java:43-51 (validarCondicionVenta), Services/Strategies/FacturaElectronicaStrategy.java:128-131 (getCondicionVentaPermitidas)
  Acceptance criteria (agent-executable): `grep -n "validarCondicionVentaFactura" Controllers/FacturasController.java` shows new validation method
  QA scenarios:
  - happy: Invalid CondicionVenta code throws IllegalArgumentException before Strategy call
  - failure: Valid CondicionVenta code passes validation
  Commit: Y | feat(condicion-venta): add UI-level validation before Strategy

### Wave 3: Feature (P3) — Serial

- [ ] 11. P3: Create ProductoExoneracion entity and repository
  What to do / Must NOT do: Create a new `Models/ProductoExoneracion.java` entity with fields: `id` (Long, auto-generated), `articuloCodigo` (String, FK to Articulos.codigo), `tipoDocumentoEX1` (String), `tipoDocumentoOTRO` (String), `numeroDocumento` (String), `articulo` (BigDecimal), `inciso` (BigDecimal), `nombreInstitucion` (String), `nombreInstitucionOtros` (String), `fechaEmisionEX` (LocalDateTime), `tarifaExonerada` (BigDecimal), `montoExoneracion` (BigDecimal). Add `@OneToOne` relationship to `Articulos` entity. Create a `Services/ProductoExoneracionService.java` with CRUD methods. Do NOT modify the existing `Models/Jaxb/FE/Exoneracion.java` — that's the JAXB output model.
  Parallelization: Wave 3 | Blocked by: 4 | Blocks: 12, 13
  References: Models/Jaxb/FE/Exoneracion.java:11-58 (field definitions), Models/Articulos/Articulos.java (to add relationship)
  Acceptance criteria (agent-executable): File `Models/ProductoExoneracion.java` exists with @Entity annotation; `grep -n "articuloCodigo\|tipoDocumentoEX1\|tarifaExonerada" Models/ProductoExoneracion.java` shows all fields
  QA scenarios:
  - happy: Entity compiles and can be persisted, Evidence .omo/evidence/tributacion-remediation/task-11-compile.txt
  - failure: Missing @OneToOne annotation flagged, Evidence .omo/evidence/tributacion-remediation/task-11-failure.txt
  Commit: Y | feat(exoneracion): create ProductoExoneracion entity for outbound exemption data

- [ ] 12. P3: Update ComprobanteService to populate Exoneracion in outbound XML
  What to do / Must NOT do: In `ComprobanteService.java`, in the `detallesComprobante()` method (around line 430-650), when building each `Impuesto` for a product, check if the product has an associated `ProductoExoneracion`. If so, populate the `Models.Jaxb.FE.Exoneracion` object on the `Impuesto` entity. Also update `resumenComprobante()` to correctly sum `TotalExonerado` for products with exoneraciones. Do NOT change the existing tax calculation logic — only add the exoneracion population.
  Parallelization: Wave 3 | Blocked by: 11 | Blocks: 13
  References: Services/ComprobanteService.java:430-650 (detallesComprobante, resumenComprobante), Models/Jaxb/FE/Exoneracion.java (JAXB output model), Models/Detalles/Impuesto.java (has Exoneracion field)
  Acceptance criteria (agent-executable): `grep -n "ProductoExoneracion\|exoneracion" Services/ComprobanteService.java` shows exoneracion population logic
  QA scenarios:
  - happy: Product with exoneracion produces XML with Exoneracion node, Evidence .omo/evidence/tributacion-remediation/task-12-xml.txt
  - failure: Product without exoneracion produces XML without Exoneracion node
  Commit: Y | feat(exoneracion): populate Exoneracion in outbound XML for exempt products

- [ ] 13. P3: Add UI forms for exoneraciones in article management
  What to do / Must NOT do: In the article creation/editing UI (likely `Controllers/ArticulosController.java` or a new XHTML page), add a section for entering exoneracion details when a product is marked as exempt. This should call `ProductoExoneracionService` to save the exoneracion data. Do NOT modify the existing tax rate selection UI — add a new section below it.
  Parallelization: Wave 3 | Blocked by: 11, 12 | Blocks: None
  References: Controllers/ArticulosController.java (article creation flow), Services/ProductoExoneracionService.java (new service from todo 11)
  Acceptance criteria (agent-executable): `grep -n "ProductoExoneracion\|exoneracion" Controllers/ArticulosController.java` shows exoneracion handling
  QA scenarios:
  - happy: Article with exoneracion saves to ProductoExoneracion table, Evidence .omo/evidence/tributacion-remediation/task-13-ui.txt
  - failure: Article without exoneracion does not create ProductoExoneracion record
  Commit: Y | feat(exoneracion): add UI forms for product exemption data entry

### Wave 3: Monitoring (P7) — Parallel with Wave 3

- [ ] 14. P7: Add monitoring alert for XmlEncabezadoFlattener
  What to do / Must NOT do: In `XmlEncabezadoFlattener.java`, add a warning log when the fallback path is taken (when `<Encabezado>` is not found and XML is returned as-is). Use `java.util.logging.Logger` consistent with the rest of the codebase. Do NOT change the flattening logic itself.
  Parallelization: Wave 3 | Blocked by: None | Blocks: None
  References: Utils/XmlEncabezadoFlattener.java:108 (flatten method), Utils/XmlEncabezadoFlattener.java (fallback path)
  Acceptance criteria (agent-executable): `grep -n "WARNING\|fallback\|Encabezado not found" Utils/XmlEncabezadoFlattener.java` shows warning log
  QA scenarios:
  - happy: Fallback path logs warning, Evidence .omo/evidence/tributacion-remediation/task-14-log.txt
  - failure: Normal path does not log warning
  Commit: Y | feat(xml-flattener): add warning log for fallback path

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit — Verify all 14 todos are implemented, all acceptance criteria pass, all commits are clean
- [ ] F2. Code quality review — Run LSP diagnostics on all changed files, verify no type errors, check for code smells
- [ ] F3. Real manual QA — Test P1 (MR auto-send with approaching deadline), P2 (TipoCambio XML with decimals), P3 (exoneracion in outbound XML), P4+P5 (invalid impuesto code rejected), P6 (invalid CondicionVenta rejected in UI)
- [ ] F4. Scope fidelity — Verify no changes beyond the 7 attention points, no new features, no unrelated refactors

## Commit strategy
Each todo produces one atomic commit. Commits are ordered by wave:
1. Wave 1: `feat(tipo-cambio)`, `fix(tipo-cambio)`, `chore(db)`, `feat(cabys)`, `feat(codigo-impuesto)`, `chore(db)`
2. Wave 2: `feat(mr-service)`, `feat(mr-auto-send)`, `refactor(facturas-controller)`, `feat(condicion-venta)`
3. Wave 3: `feat(exoneracion)` x3, `feat(xml-flattener)`

## Success criteria
- All 7 attention points remediated
- All DB migrations have rollback scripts
- All acceptance criteria pass
- No type errors in changed files
- No changes beyond scope
- MensajeReceptor auto-sends before deadline
- TipoCambio XML uses 5-decimal precision
- Exoneraciones populate in outbound XML
- Invalid impuesto codes rejected at DB level
- Invalid CondicionVenta rejected in UI
