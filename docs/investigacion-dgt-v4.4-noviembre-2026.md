# Investigación: cambios DGT a los Anexos v4.4 con vigencia 2026-11-01

**Fecha de investigación:** 2026-09-26
**Estado:** investigación de escritorio, pendiente de validación contra STAG.

## Resumen ejecutivo

La versión **v4.4 sigue siendo la vigente** (obligatoria desde el **2025-09-01**). No hay v4.5
anunciada. El 2026-04-22 la DGT publicó una revisión de los *Anexos y Estructuras v4.4*
("Bitácora de Ajustes al 22/04/2026"), **no una versión nueva**. Los códigos nuevos son de
implementación **obligatoria desde el 2026-11-01** para todos los obligados tributarios, y de
uso voluntario desde el 2026-04-22.

El hallazgo de mayor riesgo: **el XSD publicado y el validador en vivo no coinciden hoy.**

## Cronología verificada

| Fecha | Hecho |
|---|---|
| 2024-11-19 | `MH-DGT-RES-0027-2024` publicada en La Gaceta (transitorio fijaba 2025-06-01) |
| 2024-11 | v4.4.0 publicada; `ProveedorSistemas` obligatorio desde esta versión |
| 2025-02-13 | `MH-DGT-RES-0001-2025` mueve la obligatoriedad al 2025-09-01 |
| 2025-09-01 | v4.4 obligatoria; v4.3 derogada |
| 2026-04-22 | Bitácora de ajustes a los Anexos v4.4; voluntary desde este momento, en STAG y producción |
| **2026-11-01** | **Implementación obligatoria de los códigos nuevos** |

## Contenido del cambio (2026-04-22)

### Nota 9 — `CodigoReferenciaType` (motivos de referencia en NC/ND)

| Código | Descripción |
|---|---|
| 13 | Anula documento de referencia por error material |
| 14 | Corrige monto por error material |
| 15 | Sustituye comprobante electrónico por error material |
| 16 | Sustituye comprobante electrónico rechazado |
| 17 | Pago a comprobante electrónico (exclusivo de Recibo Electrónico de Pago) |

El código 12 también se ajusta para la correcta aplicación de efectos contables.

### Nota 10 — `TipoDocReferenciaType` (tipos de documento referenciado)

| Código | Descripción |
|---|---|
| 19 | Factura Electrónica de Exportación |
| 20 | Recibo Electrónico de Pago |

### Reglas de reconocimiento contable

1. **Código 12**: si se aplica una exoneración de impuesto local aprobada *posterior* a la
   transacción, el efecto debe reconocerse en el mismo período en que se emite la nota de crédito.
2. **Códigos 13 y 14**: el efecto contable debe reflejarse en el mismo período en que se emitió
   el comprobante electrónico que se está modificando.
3. **Código 13 → código 15**: al generar un comprobante sustitutivo, el bloque de referencia
   debe indicar el código 15.

El punto 2 tiene consecuencias de declaración (D-151 / IVA), no solo de XML: puede obligar a
corregir impuestos ya declarados. Coordinar con quien Riesgo/Fiscal lleve la lógica de períodos.

## ⚠️ El conflicto entre el XSD publicado y el validador

Inspección directa de los XSD oficiales en el host de Hacienda
(`atv.hacienda.go.cr/ATV/ComprobanteElectronico/docs/esquemas/2024/v4.4/`, 2026-09-26):

- `FacturaElectronica_V4.4.xsd` → `CodigoReferenciaType`: `01,02,04..12,99` — **sin 13–17**
- `FacturaElectronica_V4.4.xsd` → `TipoDocReferenciaType`: `01..18,99` — **sin 19 ni 20**
- `NotaCreditoElectronica_V4.4.xsd` → **idéntico, tampoco los incluye**
- No existe ruta publicada `/esquemas/2025/` ni `/esquemas/2026/` (devuelven 404)

Es decir: **los XSD oficiales publicados hoy rechazan 13–17 y 19/20 a nivel de esquema.** No es
una contradicción entre Hacienda y KPMG: KPMG describe el documento *Anexos*, y el XSD es otro
artefacto que no se ha actualizado. La contradicción está dentro de la propia publicación de
Hacienda.

Hay un indicio de que Hacienda **sí** republicó los esquemas (facturaencr.com afirma haber
verificado emisión con `19` funcionando), pero no se pudo corroborar: la única ruta pública sigue
siendo la de 2024 y sus archivos no incluyen los códigos nuevos. **No resolver esto desde el
escritorio — probar contra STAG.**

## Changes descartados como falsos

Descartados tras verificarlos contra los XSD oficiales:

- **"La fórmula de la Clave cambia en v4.4"** — falso. Composición sin cambios desde v4.0:
  `506` (3) + DDMMAA (6) + identificación del emisor (12, con ceros a la izquierda según Nota 4.1)
  + `NumeroConsecutivo` (20) + situación (1) + código de seguridad (8).
  `ClaveType` sigue siendo `\d{50,50}`.
- **"Se publicaron XSD nuevos el 2026-04-30"** — no confirmado. El único cambio de artefacto
  documentado es el PDF de Anexos.
- **"La Clave ahora admite letras"** — el PDF de Anexos cambió la descripción a "permite números y
  letras para personas jurídicas", pero el XSD sigue restringiendo a dígitos. Latente, no vivo:
  el Registro Nacional aún no emite cédulas alfanuméricas.
- `llbsolutions.com/es/xsd-4-4-costa-rica/` describe `ds:Signature` 1→5 y Clave alfanumérica; el
  primero ya estaba en v4.4.0 (nov 2024) y el segundo es falso. Es el comunicado reetiquetado
  como diff de XSD.
- **"El catálogo termina en 18, no existen 19 ni 20"** — correcto respecto del XSD publicado, no
  respecto de los Anexos. Ver arriba.

## Alcance de la obligación (importante)

| Ítem | Quién está obligado | Cuándo |
|---|---|---|
| Códigos Nota 9 (13–17) y ajuste del 12 | **Todos** los obligados tributarios | **2026-11-01** |
| Códigos Nota 10 (19, 20) | **Todos** | **2026-11-01** |
| Cédula jurídica alfanumérica | Solo PJ que **tengan** cédula alfanumérica (nuevas, o que soliciten conversión) | Sin fecha; se espera Q4 2026 |

La cláusula de "solo entidades nuevas" aplica **únicamente** a la cédula alfanumérica, no a los
códigos nuevos.

## ProveedorSistemas

Sin cambios en cardinalidad, posición ni longitud: sigue obligatorio, inmediatamente después de
`Clave`, con `maxLength 20`. Lo único que cambió fue la redacción (de "número de cédula" a
"identificación"), por el mismo motivo de la cédula alfanumérica.

Esto es relevante para el trabajo de esta sesión: el campo **no tenía ningún escritor** en
`src/main/java`, así que `getProvedor()` devolvía `null` y JAXB omitía el elemento, produciendo
documentos que no validan contra el esquema. Ya se corrigió (ver commit `c0cb440`).

## Acciones propuestas

### P0 — esta semana

1. **Resolver 13–17 y 19/20 empíricamente contra STAG.** Es lo único que zanja la duda. Emitir
   una NC que referencie una factura de exportación con `TipoDocIR=19`, otra con `20`, y una con
   `Codigo` 13/14/15/16, y registrar qué responde el validador.
2. **No usar el XSD oficial publicado como única validación** para estos códigos: hoy los rechaza.
   Hace falta un overlay/fork local que extienda `TipoDocReferenciaType` y `CodigoReferenciaType`,
   con el original fijado y el delta documentado.
3. **Fijar la Clave como `String(50)`, nunca tipo numérico.** Evita truncamiento silencioso.
4. **Corregir suposiciones de fecha en el repo:** si algo dice "junio 2025" o "1 nov 2026 solo
   para códigos", está mal.

### P1 — antes del 2026-11-01

5. **Implementar 13–17 y 19/20.** Los plazos de =~5 semanas. La semántica de reconocimiento por
   período (13/14 → período del comprobante modificado) es lógica de negocio, no solo un enum.
6. **Implementar la regla 13 → 15 como validación dura**, no como convención.
7. **Relajar la asunción de identificador numérico** emisor/receptor y `ProveedorSistemas`, con
   feature flag, para aceptar `3-101-A00001`.
8. **Permitir la excepción de teléfono** (911 y similares) en cualquier validación local de
   longitud mínima.

### P2 — vigilar, no construir

9. Vigilar la republicación de los XSD con los enums corregidos; re-sondear mensualmente
   `atv.hacienda.go.cr/ATV/ComprobanteElectronico/docs/esquemas/`.
10. Vigilar la fecha de activación de la cédula alfanumérica.
11. Vigilar una eventual v4.5/v5 en **La Gaceta** (un cambio de versión real exige resolución
    general, `MH-DGT-RES-0027-2024` art. 1), no en blogs de proveedores.

## No verificado

- Los PDFs de Hacienda (`ANEXOS_Y_ESTRUCTURAS_V4.4.pdf`,
  `ActualizacionAnexosyEstructurasVersion4.4CEv2.pdf`) devuelven HTTP 400 a la red usada. Su
  contenido se verificó por el índice de búsqueda de esas URLs, el post oficial de Hacienda que
  las enlaza, y la cita de KPMG. **No se leyó el comunicado original.**
- El artículo de gosocket del 2026-04-30: cuerpo inaccesible.
- Si Hacienda republicó los XSD en alguna ruta no listada.
- Ítem 3 de la bitácora del 2026-04-22 (redactado oficial).
- Decreto Ejecutivo N.° 44648-MJ (cédula alfanumérica), atribuido por dos fuentes sin
  verificar contra fuente primaria. **No citar en un expediente de cumplimiento sin comprobarlo.**
- No se probó contra STAG: todo lo relativo a lo que acepta el validador en vivo es de segunda mano.

## Fuentes

- Anexos y Estructuras v4.4 (edición vigente):
  https://www.hacienda.go.cr/docs/ANEXOS_Y_ESTRUCTURAS_V4.4.pdf
- Anexos (copia antigua, **no usar**: dice "rige a partir del 01 de junio del 2025"):
  https://atv.hacienda.go.cr/ATV/ComprobanteElectronico/docs/esquemas/2024/v4.4/ANEXOS%20Y%20ESTRUCTURAS_V4.4.pdf
- XSDs oficiales:
  https://atv.hacienda.go.cr/ATV/ComprobanteElectronico/docs/esquemas/2024/v4.4/
- Listado oficial de versiones:
  https://atv.hacienda.go.cr/ATV/ComprobanteElectronico/frmAnexosyEstructuras.aspx
- KPMG Costa Rica, 2026-05-08 (ES) / 2026-05-11 (EN):
  https://kpmg.com/cr/es/insights/2026/05/newsflash-may-8.html
- Ministry of Hacienda CR, anuncio 2026-04-27:
  https://www.facebook.com/ministeriodehaciendacr/posts/1377153191114145/
- Reglamento MH-DGT-RES-0027-2024:
  https://pgrweb.go.cr/scij/Busqueda/Normativa/Normas/nrm_texto_completo.aspx?nValor1=1&nValor2=89094&nValor3=116989&param1=NRTC&strTipM=TC
