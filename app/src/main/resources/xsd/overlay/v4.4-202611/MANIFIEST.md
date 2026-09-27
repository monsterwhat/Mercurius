# Procedencia de los esquemas XSD v4.4

Este directorio contiene los esquemas que usa `HaciendaXsdValidator` para validar
localmente los comprobantes. Hay **dos** juegos y la distincion importa:

| Directorio | Qué es |
|---|---|
| `xsd/v4.4/` | **Oficiales de Hacienda, byte a byte.** No editar. |
| `xsd/overlay/v4.4-202611/` | **Delta local.** Copia de los oficiales + los códigos del Anexo v4.4 que aún no están en el XSD publicado. |

## Por qué existe el overlay

El 2026-04-22 la DGT publicó la *"Bitácora de Ajustes"* de los **Anexos y Estructuras v4.4**
(voluntario desde 2026-04-22, **obligatorio desde 2026-11-01**). Eso **no** es una versión nueva
del esquema: los XSD oficiales siguen con `version="4.4"` y `vc:minVersion="1.1"`, sin cambios.
Un cambio de versión real exigiría una resolución general en La Gaceta
(`MH-DGT-RES-0027-2024`, art. 1).

El problema es que los catálogos viven en **dos artefactos distintos** y el XSD va atrasado:

- **Anexos** (normativo): Nota 9 agrega `16`, `17`; Nota 10 agrega `19`, `20`.
- **XSD publicado**: `CodigoReferenciaType` termina en `12` y `TipoDocReferenciaType` en `18`.

Verificado el 2026-09-26 descargando los XSD del host de Hacienda. El overlay cierra esa
brecha sin tocar los archivos oficiales.

## Delta exacto del overlay

| Tipo | Oficiales | Overlay |
|---|---|---|
| `CodigoReferenciaType` (Nota 9) | `01,02,04..12,99` | `+13,14,15,16,17` |
| `TipoDocReferenciaType` (Nota 10) | `01..13,99,14..18` | `+19,20` |

- `13` Anula documento de referencia por error material
- `14` Corrige monto por error material
- `15` Sustituye comprobante electrónico por error material
- `16` Sustituye comprobante electrónico rechazado
- `17` Pago a comprobante electrónico — **uso exclusivo del Recibo Electrónico de Pago**
- `19` Factura Electrónica de Exportación
- `20` Recibo Electrónico de Pago

`13`–`15` ya estaban en nuestra copia anterior de los XSD, **añadidos a mano y sin documentar**.
Esta correccion los quita de los archivos oficiales (vuelven a ser lo que publica Hacienda) y los
mueve al overlay, donde son rastreables.

## ⚠️ Estado de verificación

```
verification: UNVERIFIED-AGAINST-STAG
```

**No se ha comprobado contra el entorno de pruebas de Hacienda (STAG) si el validador en vivo
acepta estos códigos.** El XSD publicado los rechaza; el validador, según reportes, los acepta desde
el 2026-04-22. Esa discrepancia no se puede resolver desde el escritorio.

Antes de depender del overlay en producción:

1. Emitir una NC que referencie una factura de exportación con `TipoDocIR=19`, otra con `20`, y
   una con `Codigo` `13`/`14`/`15`/`16`/`17`.
2. Registrar qué responde el validador de STAG.
3. Cuando Hacienda publique los XSD con los catálogos corregidos, **borrar el overlay** y volver
   a los oficiales. Re-sondear mensualmente:
   `https://atv.hacienda.go.cr/ATV/ComprobanteElectronico/docs/esquemas/`

## `NotaDebitoElectronica_V4.4.xsd` — excepción documentada

`https://atv.hacienda.go.cr/.../esquemas/2024/v4.4/NotaDebitoElectronica_V4.4.xsd` devuelve
**HTTP 404**. No hay copia oficial que comparar, así que este archivo **no pudo verificarse
contra el upstream**.

Se normalizó quitándole los códigos `13`–`15` añadidos a mano, por consistencia con los otros
seis archivos, que sí se compararon uno a uno contra Hacienda y coinciden. **Es una inferencia,
no una verificación.** Si Hacienda publica el ND v4.4, hay que re-diffear este archivo.

## Verificación de los archivos oficiales

| Archivo | SHA-256 (prefijo) | Descargado |
|---|---|---|
| `FacturaElectronica_V4.4.xsd` | `D384AFEF66557360` | 2026-09-26 |
| `NotaCreditoElectronica_V4.4.xsd` | `9AF7DFF4EE0C2787` | 2026-09-26 |
| `TiqueteElectronico_V4.4.xsd` | `CDA1C7DD97F9A235` | 2026-09-26 |
| `FacturaElectronicaCompra_V4.4.xsd` | `AE1E5B782B568D22` | 2026-09-26 |
| `FacturaElectronicaExportacion_V4.4.xsd` | `D710032A05CD3D41` | 2026-09-26 |
| `ReciboElectronicoPago_V4.4.xsd` | `81D7BE9CD9FC3792` | 2026-09-26 |
| `NotaDebitoElectronica_V4.4.xsd` | — | 404 upstream |

Los SHA son del archivo descargado; en el repo se guardaron normalizados a CRLF (convención del
proyecto), así que el hash del archivo en disco difiere del de la tabla.

## Fuente

- XSD: `https://atv.hacienda.go.cr/ATV/ComprobanteElectronico/docs/esquemas/2024/v4.4/`
- Listado de versiones: `https://atv.hacienda.go.cr/ATV/ComprobanteElectronico/frmAnexosyEstructuras.aspx`
- Anexos v4.4 (vigente): `https://www.hacienda.go.cr/docs/ANEXOS_Y_ESTRUCTURAS_V4.4.pdf`
- Anuncio DGT 2026-04-27: `https://www.facebook.com/ministeriodehaciendacr/posts/1377153191114145/`
- KPMG CR 2026-05-08: `https://kpmg.com/cr/es/insights/2026/05/newsflash-may-8.html`
- Investigación completa: [`docs/investigacion-dgt-v4.4-noviembre-2026.md`](../../../docs/investigacion-dgt-v4.4-noviembre-2026.md)
