# Ultradip Tributacion Analysis — Mercurius Quarkus

**Date**: 2026-08-19
**Scope**: Exhaustive analysis of all tax logic in the Mercurius electronic invoicing system
**Axes completed**: 6 of 8 (Axes 6-7 failed — external research agents)

---

## Executive Summary

The Mercurius Quarkus tax system is **structurally complete** — it handles 7 document types, supports IVA at multiple rates (0%, 1%, 2%, 4%, 8%, 13%), manages exoneraciones, processes Mensaje Receptor, and integrates with the Hacienda DT API. However, the analysis uncovered **2 critical bugs**, **4 moderate bugs**, and **8 minor issues** that affect correctness.

### Severity Summary

| Severity | Count | Impact |
|----------|-------|--------|
| CRITICAL | 2 | Wrong tax amounts sent to Hacienda for promo items |
| HIGH | 4 | Missing exoneracion fields, credit note data loss, PDF crashes |
| MODERATE | 5 | Double-counting, per-unit vs per-line mismatch, rounding gaps |
| LOW | 7 | Unused fields, missing null guards, semantic mismatches |

---

## CRITICAL BUGS

### BUG-001: Double Taxation of Promo Items in resumenComprobante()

**Severity**: CRITICAL
**File**: `ComprobanteService.java` lines 364-370
**Impact**: Inflated TotalImpuesto and TotalComprobante for any invoice containing promotional items

**Root Cause**: `getTotalArticulos()` returns tax-INCLUSIVE total for promo items (already includes `precioConDescuento * taxRate`), but `resumenComprobante()` multiplies by the tax rate again:

```
precioFinal = articuloCarrito.getTotalArticulos()  // For promo: includes tax
totalImpuestoArticulo = precioFinal * impuesto      // TAXES THE TAX
```

**Example**: Article at 1000 colones, 10% discount, 13% tax, qty 1
- `getTotalArticulos()` = (1000 - 100) + (900 * 0.13) = 1017 (tax-inclusive)
- `totalImpuestoArticulo` = 1017 * 0.13 = **132.21** (WRONG)
- Correct value: 900 * 0.13 = **117.00**

**Also affects**: `totalServGravados`, `totalMercanciasGravadas`, `totalVenta` — all inflated for promo items.

**Non-promo items are NOT affected** because `getTotalArticulos()` for non-promo returns `precioEfectivo * quantity` (tax-exclusive).

**Fix**: Use tax-exclusive line total for promo items, or extract tax from `getTotalArticulos()` before computing `totalImpuesto`.

---

### BUG-002: Impuesto.Monto and ImpuestoNeto are Per-Unit, Not Per-Line

**Severity**: CRITICAL
**File**: `ComprobanteService.java` lines 575, 596
**Impact**: Hacienda receives incorrect tax amounts per line item

**Root Cause**: `impuesto.setMonto(articulo.getTotalImpuesto())` sets the PER-UNIT tax amount. Per Hacienda v4.4 XSD, `Impuesto.Monto` should be the total tax for the line (= unitTax * quantity).

**Example**: 3 units at 1000 colones, 13% tax
- `Impuesto.Monto` set to 130 (per-unit)
- Should be: 390 (130 * 3)
- `ImpuestoNeto` same issue: set to 130 instead of 390

**Note**: The resumen's `TotalImpuesto` IS correctly calculated for non-promo items (line total * rate), so Hacienda may not reject this. But it's technically incorrect per the XSD spec.

---

## HIGH-SEVERITY BUGS

### BUG-003: Exoneracion Entity Missing 5 of 10 Fields

**Severity**: HIGH
**File**: `ComprobanteService.java` lines 579-587
**Impact**: Generated XML has null values for mandatory exoneracion fields

**Missing fields**:
- `tipoDocumentoOTRO` — custom document type code (when tipoDocumentoEX1="99")
- `articulo` — law article number granting exemption
- `inciso` — specific paragraph within the article
- `nombreInstitucionOtros` — custom institution name
- `tarifaExonerada` — exempted rate percentage

**Consequence**: Only the `montoExoneracion`-based exemption path works. Percentage-based exemptions (`tarifaExonerada`) fail silently.

---

### BUG-004: getCodigoLetra() Crashes for Rates 4%, 5%, 8%, 0.5%, 10%, 11%

**Severity**: HIGH
**File**: `Tipo_CodigoImpuesto.java` lines 57-70
**Impact**: PDFGenerator crashes with `IllegalArgumentException` for any product not at 0%, 1%, 2%, or 13%

**The switch only handles**: 0 → "E", 1 → "U", 2 → "D", 13 → "T"
**Missing**: 4, 5, 8, 10, 11 (and 0.5 would truncate to 0)

**Trigger**: `PDFGenerator.java` line 194 does `Integer.parseInt(codigoCabys.getImpuesto())` then calls `getCodigoLetra()`.

---

### BUG-005: Credit Notes Lose Exoneracion Data

**Severity**: HIGH
**File**: `DevolucionesController.java` lines 344-358
**Impact**: NC for exonerated products shows as "gravado" instead of "exonerado"

**Root Cause**: `procesarDevolucion()` copies `CodigoTarifaIVA` and `Tarifa` from original line but does NOT copy the `Exoneracion`. Also hardcodes `totalServExonerado`, `totalMercExonerada`, and `totalExonerado` to `BigDecimal.ZERO`.

---

### BUG-006: MR TotalFactura Inconsistency Between Auto-Send and Manual UI

**Severity**: HIGH
**Files**:
- `FacturasController.java` line 873: uses `resumen.getTotalVenta()`
- `ProgramadorTareas.java` line 330: uses `resumen.getTotalComprobante()`

**Impact**: The same invoice gets different `totalFactura` values depending on whether it's accepted manually vs auto-sent by the scheduler. `TotalComprobante = TotalVentaNeta + TotalImpuesto`, which is different from `TotalVenta`.

---

## MODERATE-SEVERITY BUGS

### BUG-007: Double-Counting of Exonerado Totals in Resumen

**File**: `ComprobanteService.java` lines 368-384
**Issue**: A product with `impuesto=0` AND an exoneracion record gets counted in BOTH `totalServExentos/totalMercanciasExentas` AND `totalServExonerado/totalMercExonerada`. The exonerado totals are ADDED on top of the exento totals.

**Consequence**: `TotalExonerado + TotalExento + TotalGravado > TotalVenta` for exonerated zero-rate products.

---

### BUG-008: Tipo_TarifaIVA Mapping Mismatches for Special Codes

**File**: `Tipo_TarifaIVA.java` lines 67, 72
**Issue**:
- Input "5" maps to `TRANSITORIO_4` (code "06") — a 5% CABYS code is reported as 4% transitional
- Input "10" maps to `TARIFA_EXENTA` (code "10") — a 10% CABYS code is reported as "exenta"

These are Hacienda-specific codes, not percentages, but the naming causes confusion and the `Impuesto.tarifa` field stores the raw code number (5 or 10) instead of the actual percentage (4 or 0).

**Downstream impact**: `ComprobantesRecibidosPrevalidationService` calculates `baseImponible * tarifa / 100` — for a "5" (transitorio 4%) item this computes 5% instead of 4%.

---

### BUG-009: Impuesto.factorCalculoIVA and Impuesto.montoExportacion Never Populated

**File**: `ComprobanteService.java`
**Issue**: These fields exist on the Impuesto entity and JAXB models but are never set. If Hacienda v4.4 XSD makes these mandatory for FEE (export) documents, export XML generation will fail validation.

---

### BUG-010: Double vs BigDecimal Inconsistency in Tax Rate Parsing

**File**: `CarritoCalculations.java` line 19
**Issue**: `getImpuestoRate()` returns `double` via `Double.parseDouble()`, while most other code uses `BigDecimal`. For the 0.5% rate, floating-point imprecision could accumulate across multiple calculations.

---

### BUG-011: No Rounding Scale on Intermediate Calculations

**File**: `ComprobanteService.java` line 366
**Issue**: `impuesto = BigDecimal.valueOf(impuestoPct).divide(BigDecimal.valueOf(100))` has no scale specified. For non-terminating decimals this throws `ArithmeticException`. Current tax rates (0, 1, 2, 4, 13) are safe, but this is fragile.

---

## LOW-SEVERITY ISSUES

### ISSUE-001: Articulos.exento Field Unused
**File**: `Articulos.java` line 87
The `exento` boolean exists but is never consulted in any tax calculation path. Tax determination comes exclusively from `Cabys.impuesto`.

### ISSUE-002: No Null-Safety on Cabys.impuesto
Multiple code paths call `.getImpuesto()` and parse it without null-checking the Cabys object. Only `CarritoCalculations.getImpuestoRate()` has a null guard. `ComprobanteService`, `PDFGenerator`, and `ArticulosController` will NPE if Cabys is null.

### ISSUE-003: No Expiry Validation on Exoneracion Dates
The `fechaEmisionEX` field is stored and included in XML, but no code validates whether the exoneration document has expired.

### ISSUE-004: Duplicate Exoneracion Crash
`ProductoExoneracionService.findByArticuloCodigo()` uses `getSingleResult()` which throws `NonUniqueResultException` (not caught) if multiple exoneracion rows exist for the same article.

### ISSUE-005: Descuento > 100% Not Prevented
`CarritoCalculations` does not validate that discount percentage is <= 100%. A >100% discount produces negative line totals.

### ISSUE-006: 0% vs Exento Conflation
`Tipo_TarifaIVA.getTarifa("0")` returns `TARIFA_0_EXENTO` (code "01") which conflates "0% rate" with "legally exempt." In Costa Rica tax law, these are different concepts with different Hacienda codes (01 vs 10).

### ISSUE-007: ResumenFactura TotalNoSujeto Fields Never Set
`totalNoSujeto`, `totalServNoSujeto`, `totalMercNoSujeta` exist in the model but are never populated by `resumenComprobante()`.

---

## ARCHITECTURE ANALYSIS

### Tax Rate Source
```
Articulos.codigoCabys (FK -> Cabys)
    └── Cabys.impuesto (String, e.g., "0", "1", "2", "4", "8", "13")
```
All tax determination flows through `Cabys.impuesto`. No product-level override exists.

### Tax Calculation Pipeline
```
Cart Level:
  CarritoCalculations.getImpuestoRate() → double (from Cabys.impuesto)
  CarritoCalculations.getTotalImpuesto() → per-unit tax after discount
  CarritoCalculations.getTotalArticulos() → line total (promo=tax-inclusive, non-promo=tax-exclusive)

Invoice Level:
  ComprobanteService.detallesComprobante() → line items with Impuesto entities
  ComprobanteService.resumenComprobante() → aggregate totals

XML Level:
  JAXB marshaller → XmlEncabezadoFlattener → XSD validation → XAdES-EPES signing
```

### Document Type Strategy Pattern
7 strategies (FE, TE, NC, ND, FEE, FEC, REP) share:
- Common EncabezadoBuilder
- Common CondicionVenta validation
- No strategy-level tax calculation (all centralized in ComprobanteService)

Differ by:
- CondicionVenta allowed codes
- Max line items (60-1000)
- Receptor requirement
- Line detail field exclusions (REP skips most, FEE skips BaseImponible)

### Hacienda API Integration
- **Dual path**: Fides (third-party) or Direct Hacienda
- **Token**: OAuth2 ROPC with cache + refresh
- **XML**: JAXB → flatten → XSD validate → sign (XAdES-EPES) → Base64 → JSON payload
- **Polling**: POST /recepcion → GET /recepcion/{clave} every 3s, max 20 attempts
- **Auto-correction**: RECHAZADO invoices get pattern-matched to FIX_CABYS, FIX_TAX, or FIX_TOTALS strategies

### Mensaje Receptor Flow
```
Upload/Email → Parser.parseXML() → persist with prevalidation
    → User action (accept/reject/partial) or auto-scheduler (6am daily)
        → Pre-validation (blocks on errors)
            → MensajeReceptorService → build XML → sign → submit
                → Update haciendaMensajeReceptorEstado
```

**Deadline**: 8 business days from the 1st of the month following invoice emission.

### ISC (Impuesto Selectivo de Consumo)
- **Modeled but not implemented for outbound invoices** — entities, JAXB models, and enums exist
- `Impuesto.codigo` is hardcoded to "01" (IVA) in `detallesComprobante()`
- ISC is only parsed from received invoices (inbound)

---

## CONDICION VENTA CROSS-REFERENCE

| Code | Description | FE | TE | NC | ND | FEE | FEC | REP |
|------|-------------|:--:|:--:|:--:|:--:|:---:|:---:|:---:|
| 01 | Contado | Y | Y | Y | Y | Y | Y | - |
| 02 | Credito | Y | Y | Y | Y | Y | Y | - |
| 03 | Consignacion | Y | Y | Y | Y | Y | Y | - |
| 04 | Apartado | Y | Y | Y | Y | Y | Y | - |
| 05 | Arrendamiento compra | Y | Y | Y | Y | Y | Y | - |
| 06 | Arrendamiento financiero | Y | Y | Y | Y | Y | Y | - |
| 07 | Cobro tercero | Y | Y | Y | Y | Y | Y | - |
| 08 | Estado credito | Y | Y | Y | Y | Y | Y | - |
| 09 | Pago servicios Estado | - | - | - | - | - | - | Y |
| 10 | Credito IVA 90 dias | Y | Y | Y | Y | Y | Y | - |
| 11 | Pago credito IVA 90 dias | - | - | - | - | - | - | Y |
| 12 | Mercaderia no nacionalizada | Y | - | - | - | Y | - | - |
| 13 | Bienes usados | Y | - | - | - | Y | Y | - |
| 14 | Arrendamiento operativo | Y | - | - | - | Y | Y | - |
| 15 | Arrendamiento financiero | Y | - | - | - | Y | Y | - |
| 99 | Otros | Y | Y | Y | Y | Y | Y | - |

**Default**: CONTADO ("01") for all except REP (defaults to "11")

---

## RECOMMENDED FIX PRIORITY

### Immediate (blocks correct invoicing)
1. **BUG-001**: Fix double taxation of promo items in `resumenComprobante()`
2. **BUG-002**: Multiply `Impuesto.Monto` and `ImpuestoNeto` by quantity

### Before Production Use
3. **BUG-003**: Copy ALL 10 exoneracion fields in `detallesComprobante()`
4. **BUG-004**: Add missing cases to `getCodigoLetra()` (4, 8, 0.5, etc.)
5. **BUG-005**: Copy exoneracion in `DevolucionesController` for credit notes
6. **BUG-006**: Align `totalFactura` between auto-sender and manual UI

### Quality Improvements
7. **BUG-007**: Fix double-counting of exonerado in resumen
8. **BUG-008**: Correct Tipo_TarifaIVA special code mappings
9. **BUG-010**: Replace `double` with `BigDecimal` in `getImpuestoRate()`

---

## FILES ANALYZED (Complete List)

### Core Tax Calculation
- `Services/ComprobanteService.java` — detallesComprobante(), resumenComprobante(), generateMensajeReceptorXml()
- `Utils/CarritoCalculations.java` — All cart-level tax math
- `Services/CarritoService.java` — calcularTotal(), calcularVuelto()
- `Models/Articulos/Carrito/ArticuloCarrito.java` — Delegates to CarritoCalculations

### Tax Rate Sources
- `Models/Cabys.java` — Tax rate entity
- `Services/CabysService.java` — Loads CAByS from Hacienda API
- `Models/Enums/Tipo_TarifaIVA.java` — Rate → Hacienda code mapping
- `Models/Enums/Tipo_CodigoImpuesto.java` — Tax type codes + letter mapping
- `Models/Enums/Tipo_CondicionImpuesto.java` — Tax condition types

### Line Item Entities
- `Models/Detalles/Impuesto.java` — Line-level tax entity
- `Models/Detalles/Exoneracion.java` — Tax exemption entity
- `Models/Detalles/LineaDetalle.java` — Line detail with tax references
- `Models/Detalles/Descuento.java` — Discount entity
- `Models/Detalles/DatosImpuestoEspecifico.java` — ISC data entity

### Exoneracion
- `Models/ProductoExoneracion.java` — Product-level exemption config
- `Services/ProductoExoneracionService.java` — CRUD + lookup
- `Models/Enums/Tipo_Documento_Exoneracion.java` — 12 document type codes

### Invoice Summary
- `Models/Resumen/ResumenFactura.java` — All summary total fields
- `Models/Resumen/TotalDesgloseImpuesto.java` — Per-rate tax breakdown

### Strategy Pattern (7 document types)
- `Services/Strategies/DocumentoStrategy.java` — Interface
- `Services/Strategies/DocumentoStrategyFactory.java` — Factory
- `Services/Strategies/FacturaElectronicaStrategy.java` — FE
- `Services/Strategies/TiqueteElectronicoStrategy.java` — TE
- `Services/Strategies/NotaCreditoElectronicaStrategy.java` — NC
- `Services/Strategies/NotaDebitoElectronicaStrategy.java` — ND
- `Services/Strategies/FacturaExportacionElectronicaStrategy.java` — FEE
- `Services/Strategies/FacturaCompraElectronicaStrategy.java` — FEC
- `Services/Strategies/ReciboElectronicoPagoStrategy.java` — REP
- `Services/Strategies/EncabezadoBuilder.java` — Shared header builder

### Hacienda API Integration
- `Services/HaciendaServiceFacade.java` — Dual-path routing
- `Services/HaciendaApiService.java` — HTTP client
- `Services/HaciendaSigner.java` — XAdES-EPES signing
- `Services/HaciendaCertificateService.java` — PKCS12 management
- `Services/HaciendaXsdValidator.java` — XSD validation
- `Services/FidesApiService.java` — Alternative Fides path
- `Utils/XmlEncabezadoFlattener.java` — Post-JAXB flattening

### Mensaje Receptor
- `Services/MensajeReceptorService.java` — Core MR engine
- `Services/ConsecutivoReceptorService.java` — Sequential number generation
- `Models/ComprobantesRecibidos.java` — Inbound invoice entity
- `Services/ComprobantesRecibidosService.java` — CRUD + queries
- `Services/ComprobantesRecibidosPrevalidationService.java` — Pre-validation

### Scheduling
- `Utils/ProgramadorTareas.java` — All scheduled tasks

### Controllers
- `Controllers/FacturasController.java` — Accept/reject/partial + CondicionVenta validation
- `Controllers/DevolucionesController.java` — Credit notes
- `Controllers/DeclaracionIVAController.java` — Monthly IVA declaration
- `Controllers/ConsultasController.java` — Query + NC auto-creation
- `Controllers/HaciendaDashboardController.java` — Dashboard
- `Controllers/Tributacion/TributacionController.java` — Tax reports
- `Controllers/Comprobantes/ComprobantesEmitidosController.java` — Emitted invoices UI
- `Controllers/Api/Accounting/ReportsController.java` — REST API IVA summary

### PDF Generation
- `Utils/PDFGenerator.java` — Tax display on printed invoices

### XML Models (JAXB)
- `Models/Jaxb/FE/Impuesto.java`, `Exoneracion.java`, `DatosImpuestoEspecifico.java`
- `Models/Jaxb/TE/Impuesto.java`, `Exoneracion.java`
- `Models/Jaxb/NC/Impuesto.java`, `Exoneracion.java`
- `Models/Jaxb/ND/Impuesto.java`, `Exoneracion.java`
- `Models/Jaxb/FEE/Impuesto.java`, `Exoneracion.java`
- `Models/Jaxb/FEC/Impuesto.java`, `Exoneracion.java`
- `Models/Jaxb/REP/Impuesto.java`, `Exoneracion.java`

### Correction Service
- `Services/ComprobantesEmitidosCorrectionService.java` — Auto-correction of rejected invoices

### Parsing
- `Utils/Parsers/Parser.java` — Inbound XML parsing

### Security
- `Utils/EncryptionUtil.java` — AES-256-GCM encryption

### Tests
- `Services/HaciendaSignerTest.java`
- `Documentos/ElectronicDocumentPipelineTest.java`
- `Documentos/XsdModelAlignmentTest.java`
