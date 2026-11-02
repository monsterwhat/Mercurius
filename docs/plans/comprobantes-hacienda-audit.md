# Comprobantes de Hacienda v4.4 — auditoría de cobertura y plan

**Fecha:** 2026-11-02 · **Versión auditada:** 2.26.2
**Alcance:** qué tipos de comprobante existen en el backend, cuáles se pueden emitir
de punta a punta desde la UI nueva (`/app/...`), y qué falta.
**Método:** lectura del código (estrategias, XSD, servicios, controladores y
plantillas Qute). Sin pruebas de emisión real contra Hacienda.

---

## 1. Inventario: lo que el backend implementa

| Código | Comprobante | Estrategia | XSD v4.4 |
|---|---|---|---|
| **01** | Factura Electrónica | `Services/Strategies/FacturaElectronicaStrategy.java` | `xsd/v4.4/FacturaElectronica_V4.4.xsd` |
| **02** | Nota de Crédito Electrónica | `NotaCreditoElectronicaStrategy.java` | `NotaCreditoElectronica_V4.4.xsd` |
| **03** | Nota de Débito Electrónica | `NotaDebitoElectronicaStrategy.java` | `NotaDebitoElectronica_V4.4.xsd` |
| **04** | Tiquete Electrónico | `TiqueteElectronicoStrategy.java` | `TiqueteElectronico_V4.4.xsd` |
| **05** | Factura Electrónica de Exportación | `FacturaExportacionElectronicaStrategy.java` | `FacturaElectronicaExportacion_V4.4.xsd` |
| **08** | Factura Electrónica de Compra | `FacturaCompraElectronicaStrategy.java` | `FacturaElectronicaCompra_V4.4.xsd` |
| **10** | Recibo Electrónico de Pago | `ReciboElectronicoPagoStrategy.java` | `ReciboElectronicoPago_V4.4.xsd` |
| 05/06/07 (MR) | Mensaje Receptor (aceptación / parcial / rechazo) | XML armado en `ComprobanteService` | `MensajeReceptor_V4.4.xsd` |

**No existen** como tipo: 06, 07, 09, 11–16, Recibo Electrónico de Compra,
Factura de Importación. (Ojo: `Models/Enums/Tipo_Documento` es el catálogo de
**otros cargos**, no de comprobantes.)

> Inconsistencia: una FEE **recibida** se guarda como `09`
> (`Utils/Parsers/Parser.java`), mientras la estrategia emite `05`.

---

## 2. Matriz de capacidad (UI nueva)

| Tipo | Emitir desde UI | Enviar a Hacienda | PDF | Consultar | Pruebas | Anular / NC |
|---|---|---|---|---|---|---|
| 04 TE | ✅ POS | ✅ | ⚠️ PDF genérico dice "TIQUETE" | ✅ `/app/facturas` | ✅ | ✅ `/app/devoluciones` |
| 01 FE | ✅ POS (exige cliente) | ✅ | ⚠️ mismo PDF | ✅ | ✅ | ✅ |
| 02 NC | ✅ sólo `/app/devoluciones` | ✅ | ❌ **no genera PDF** | ✅ | ✅ | es el mecanismo |
| 03 ND | ⚠️ accidental, mal etiquetada | ⚠️ sin `InformacionReferencia` → XSD falla | ⚠️ | ✅ | ✅ | ❌ |
| 05 FEE | ❌ **sólo API** | ⚠️ | ⚠️ | ✅ | ✅ | — |
| 08 FEC | ❌ **sólo API** | ⚠️ sin referencia | ⚠️ | ✅ | ✅ | — |
| 10 REP | ❌ **sólo API** | ⚠️ sin referencia | ⚠️ | ✅ | ✅ | — |
| MR | ✅ `/app/facturas-recibidas` | ✅ | N/A | ✅ `/app/tributacion/consultas` | ✅ | N/A |

---

## 3. Huecos concretos

### A. Backend sin pantalla
1. **05 FEE**, **08 FEC** y **10 REP** existen y se pueden disparar por API
   (`POST /api/app/pos/facturar` con `tipoDocumento`), pero **ninguna pantalla**
   los ofrece.
2. **03 ND** no tiene flujo dedicado; sólo se alcanza por un `<option>` del POS.
3. **Factura de importación** y **Recibo Electrónico de Compra**: no existen.

### B. Emisión inválida o engañosa
4. El selector del POS rotula **`03` como "Nota de crédito"** cuando 03 es
   **Nota de Débito** (`pages/facturas/factura-body.html`).
5. NC/ND/FEC/REP emitidos desde el POS **no llevan `InformacionReferencia`**,
   obligatoria en los cuatro XSD → falla la firma/validación en modo directo.
6. El endpoint del POS **no valida el código**: cualquier valor cae a TE
   (`DocumentoStrategyFactory`).

### C. PDF
7. **Un solo PDF genérico**: imprime "TIQUETE ELECTRONICO" y guarda
   `tiqueteElectronico_<id>.pdf` para todos los tipos (`Utils/PDFGenerator`).
8. **La NC no genera PDF**: el botón "Imprimir NC" apunta a un archivo que
   nadie crea (`DevolucionesResource`).

### D. Factura de servicios (consulta médica + lentes + seguro)
9. **Multilínea: sí** (N líneas por factura).
10. **Servicio vs mercancía por línea: NO.** `LineaDetalle.tipoTransaccion`
    existe pero nunca se llena; el resumen asume `esServicio = false` y todo va
    a `TotalMercanciasGravadas` (`ComprobanteService`).
11. **Otros cargos (seguro, etc.): sin UI.** El modelo y el XML existen, pero la
    emisión nunca crea un `OtroCargo`.
12. **Exoneraciones: estructura sí, datos no.** Sólo se guarda `exento`; faltan
    tipo/número/institución/fecha/tarifa/monto.
13. **Moneda: fija en CRC.** No hay factura en USD ni `TipoCambio` de factura.

### E. Compra / importación
14. **Recibir y procesar facturas de proveedor: sí** (subida XML, prevalidación,
    Mensaje Receptor).
15. **Emitir FEC desde una orden de compra: no** (el estado `FACTURADA` es sólo
    una etiqueta).
16. **Partida arancelaria:** el campo existe y ningún flujo lo llena.

### F. Mensajes de receptor
17. Aceptación total, parcial y rechazo funcionan, pero **la parcial se guarda
    como `ACEPTADO`** y **no se captura el motivo del rechazo**.

### G. Listados y navegación
18. **Ninguna pantalla muestra el tipo de comprobante** (el DTO sí lo trae) y no
    hay filtro por tipo (`listByCodigoDocumento` existe sin llamadores).
19. `/app/devoluciones` **no está enlazada** desde el sidebar ni el dashboard.
20. `/app/facturas/nueva` es una ruta decorativa: el botón real abre el POS.

### H. Observación de cumplimiento
21. `LineaDetalle.numeroLinea` se genera **desde 0**; Hacienda espera 1..N. Es un
    riesgo de rechazo silencioso en todos los tipos.

---

## 4. Lo que SÍ se puede hacer hoy de punta a punta

- Emitir **TE (04)** y **FE (01)** desde el POS, con envío y PDF.
- Emitir **NC (02)** referenciada desde `/app/devoluciones` (sin PDF).
- Responder **Mensajes de Receptor** desde `/app/facturas-recibidas`.
- Consultar emitidas, recibidas y estado de Hacienda.

## 5. Lo que NO se puede hacer hoy desde la UI

- Emitir **FEE (05)**, **FEC (08)**, **REP (10)** ni **ND (03)** de forma válida.
- Factura de **importación** o **recibo de compra**.
- Factura **mixta servicio + mercancía** con el resumen correcto.
- Factura en **USD**.
- **Imprimir PDF de una NC**.

---

## 6. Plan priorizado

| # | Paso | Capa |
|---|---|---|
| 1 | Corregir el selector del POS (`02` NC, `03` ND) y validar el tipo contra una whitelist en el endpoint | UI + backend |
| 2 | Exigir `InformacionReferencia` en NC/ND/FEC/REP, o impedir que el POS los emita | backend |
| 3 | Separa **servicio/mercancía por línea** (flag en el artículo + `tipoTransaccion` + resumen real) | backend |
| 4 | Completar **exoneraciones** y **otros cargos** en la UI y en la emisión | UI + backend |
| 5 | **PDF por tipo** y PDF real para la NC | backend |
| 6 | Columna y filtro **Tipo** en emitidas/recibidas | UI + backend |
| 7 | Flujos dedicados de **FEC** (desde orden de compra), **FEE** (exportación con moneda y partida arancelaria), **REP** y **ND** | UI + backend |
| 8 | Navegación (`/app/devoluciones`, `/app/facturas`) y **moneda por factura** | UI + backend |

Cada paso debe cerrar con una prueba de integración que valide el XML contra su
XSD (`ElectronicDocumentPipelineTest`, `XsdModelAlignmentTest`) y, cuando aplique,
`PosFacturarTiposDocumentoTest`.
