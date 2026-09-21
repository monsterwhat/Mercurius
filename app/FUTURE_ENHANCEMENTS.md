# Future Enhancements

Legitimate gaps identified during the v4.4 tax logic audit (2025-07-14). None are active bugs — all valid per current XSD and business requirements.

---

## 1. Service vs Merchandise Tax Split (esServicio)

**Status:** Latent gap — no impact on current operations.

**What:** `ComprobanteService.resumenComprobante()` hardcodes `boolean esServicio = false` (line 370). All items are classified as merchandise. The `TotalServGravados`, `TotalServExentos`, `TotalServExonerado` fields in ResumenFactura are always zero.

**Why it's safe:** All TotalServ fields are `minOccurs="0"` (optional) in FE, NC, ND XSDs. Zero values are valid. The business currently only sells physical merchandise.

**When this breaks:** If the business starts selling services (e.g., consulting, repairs, subscriptions).

**Fix:**
1. Add a `servicio` boolean field to the `Articulos` entity
2. In `resumenComprobante()`, replace the single `esServicio` flag with per-item detection:
   ```java
   boolean itemEsServicio = articulo.getArticulo().isServicio();
   ```
3. The existing if/else logic for the service split (lines 400-421) already handles this correctly — it just needs the flag to be dynamic

**Files:** `ComprobanteService.java`, `Articulos.java`

---

## 2. ISC (Impuesto Selectivo al Consumo) Support

**Status:** Feature gap — no impact on current product catalog.

**What:** The `DatosImpuestoEspecifico` entity and JAXB models exist, but `detallesComprobante()` never populates them when building Impuesto objects (line 659-687). ISC data is silently dropped.

**Why it's safe:** ISC applies to regulated products (alcohol, tobacco, sugary drinks, perfumes). The business doesn't currently sell these. `DatosImpuestoEspecifico` is optional in all ImpuestoType XSDs.

**When this breaks:** If the business starts selling ISC-regulated products.

**Fix:**
1. Add an `impuestoEspecifico` relationship to the product or CABYS entity
2. In `detallesComprobante()`, after building the Impuesto object, check if the product has ISC data and populate `DatosImpuestoEspecifico`
3. Calculate `TotalImpuestoAsumidoEmisor` in ResumenFactura for ISC absorbed by the seller

**Files:** `ComprobanteService.java`, `DatosImpuestoEspecifico.java`, `Articulos.java` or `Cabys.java`

---

## 3. Missing Tarifa IVA Rate Mappings

**Status:** False alarm — fully resolved.

**What:** The `Tipo_TarifaIVA.getTarifa()` switch statement was suspected of missing rates for codes "3", "5", "6".

**Resolution:** Costa Rica's official IVA rates are 0%, 0.5%, 1%, 2%, 4%, 8%, and 13%. Rates of 3%, 5%, and 6% do not exist in the Costa Rica tax system. The CABYS database from Hacienda will never contain these values. The `default` throw in the switch is correct — it catches data corruption or invalid CABYS entries.

**Action:** None required.
