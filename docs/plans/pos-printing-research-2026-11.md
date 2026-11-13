# Impresión de tiquetes POS térmicos en Mercurius (investigación + plan)

Fecha: 2026-11. Autor: investigación técnica sobre el código de `Mercurius-clean`.
Estado: documento de diseño. **No se modificó código.**
Alcance: POS (`/app/pos`), PDF del servidor, reimpresión desde el historial y NC.
**Fides está fuera de alcance**: no tiene impresión ni PDF y no se toca.

---

## 1. Resumen ejecutivo (corregido)

1. Hoy **no se imprime HTML**: el único camino es un PDF de 72 × 200 mm generado en
   el servidor y abierto en una **ventana nueva** por `printPDF()`
   (`web/print.js:30-81`). No existe ninguna plantilla de tiquete imprimible ni una
   sola regla `@media print` para la app (la única del repo es de etiquetas,
   `pages/reportes/etiquetas.html:13-38`, y es A4).
2. El PDF **no se adapta al contenido**: el alto de página es siempre
   `566.93 + 14 × max(0, líneas − 3)` puntos, con base fija de **200 mm**
   (`Utils/PDFGenerator.java:342-344` y `:139-140`). Un tiquete de 2 líneas ocupa
   igual 200 mm de papel; uno de 25 líneas con nombres largos puede rebosar a una
   segunda página. Nunca hay un alto "justo".
3. El fallo intermitente ("a veces no imprime", "sale en blanco") tiene **dos**
   causas en `print.js`, no una: (a) `printWindow.onload` se asigna **después** de
   `document.close()` (`print.js:60-65`), así que el evento puede haber ocurrido ya;
   (b) `window.open` se ejecuta **después** del XHR y del envío a Hacienda
   (`PosResource.java:821` → `:839`), es decir, fuera de la ventana de activación
   del usuario (~5 s), y el navegador lo bloquea.
4. Recomendación: **plantilla HTML dedicada + iframe oculto + `@page` de alto
   calculado**. Mantener el PDF del servidor como respaldo y como documento
   fiscal archivado. La configuración del driver (papel 72/80 mm continuo, escala
   100 %, márgenes 0) sigue siendo prerrequisito, no opcional.
5. La receta `@page { size: 80mm auto }` que recomendaba la versión anterior de
   este documento **no es CSS válido** (ver §4.1). Con toda probabilidad el
   navegador descarta la declaración y vuelve a Letter/A4 — exactamente el síntoma
   reportado. El plan usa alto fijo calculado por tiquete.

---

## 2. Estado real del código

Todo lo de esta sección está verificado leyendo el árbol; se cita archivo:línea.

### 2.1 `printPDF` y sus llamadores

| Archivo:línea | Qué hace |
|---|---|
| `app/src/main/resources/web/print.js:30-81` | `printPDF(url)`: XHR GET `responseType='blob'` → `FileReader.readAsBinaryString` → `btoa` → `window.open('','_blank')` → `document.write('<iframe src="data:application/pdf;base64,...">')` → `onload` → `focus()` + `print()`. |
| `web/print.js:55-59` | `window.open` **después** de la respuesta del servidor; si es `null` reporta "ventana bloqueada". |
| `web/print.js:60-61` | `document.write(...)` y `document.close()` en la ventana nueva. |
| `web/print.js:62-65` | `printWindow.onload = function(){ focus(); print(); }` — asignado **después** de `close()`, y es el `load` **de la ventana**, no el del `<iframe>`. |
| `web/print.js:86` | `window.printPDF = printPDF` (global para los `onclick` heredados). |
| `web/index.js:9` | `import './print.js'` — entra al bundle que inyecta `{#bundle /}` (`layout.html:44`) y también `factura-standalone.html:21`. |

Llamadores (los únicos cuatro del repo):

| Archivo:línea | Contexto |
|---|---|
| `templates/pages/facturas/facturar-resultado.html:84-92` | Botón "Imprimir" del POS tras facturar (`data-pos-imprimir`, `data-pdf-url`). Si `window.printPDF` no existe, **oculta** el botón (`:84-88`). |
| `facturar-resultado.html:95-104` | Auto-impresión: `obligatorio` (viene del servidor) **o** `localStorage['mercurius.autoPrint'] === '1'`; dedupe por `sessionStorage['mercurius.printed.'+pdfUrl]`. |
| `templates/pages/recibos/detalle.html:20-24` | Reimpresión en el detalle del historial: `onclick="printPDF('/api/app/recibos/{id}/pdf')"`. |
| `Controllers/Api/App/DevolucionesResource.java:1914-1918` | Botón "Imprimir NC" construido en un `StringBuilder`: `printPDF(printUrl)`, con `printUrl` en `:1678-1682` → `/api/app/pos/facturas/factura_<clave>.pdf`. |

Preferencia por estación: `templates/pages/facturas/cart-panel.html:294-321`
(checkbox `#auto-print-toggle` → `localStorage['mercurius.autoPrint']`).

### 2.2 ¿Se imprime el PDF del servidor? ¿Antes o después de Hacienda?

Sí, **siempre el PDF del servidor**; nunca HTML. Orden real en `PosResource.facturar`:

1. `:807` sello de idempotencia (`entry.setFacturadoComprobanteId`).
2. `:821` `comprobanteService.enviarComprobanteCreado(result)` — envío/consulta a
   Hacienda (HTTPS con sondeo, puede tardar segundos).
3. `:834-853` `pdfGenerator.generarPDFTiqueteElectronico(...)` — **después** de
   Hacienda, con el estado ya conocido.
4. `:854-861` si el archivo no existe → `500 PDF_NO_GENERADO`.
5. `:864-877` correo al cliente solo si `result.haciendaEnviado`.
6. `:885` `pdfUrl = uriBasePath() + "api/app/pos/facturas/" + fileName`.
7. `:898` `autoPrintObligatorio = autoPrintObligatorio(cliente, result.haciendaEnviado)`.

La regla de obligatoriedad está en `PosResource.java:2745-2749`: **si el cliente no
tiene correo, o el comprobante no se envió, la impresión no es opcional**. Es
decir: el sistema ya asume que el papel es la entrega; si la impresión falla en
silencio, el cliente se queda sin comprobante.

Efecto colateral del orden: entre el clic del cajero y `window.open` pasan el
envío a Hacienda **y** la generación del PDF. Si eso supera ~5 s, la activación
transitoria del usuario caduca y el popup se bloquea (`print.js:55-59` lo reporta,
pero el tiquete no sale). Este es el caso real de una caja con red lenta y no un
caso teórico.

### 2.3 Plantillas del POS y estilos de impresión existentes

| Archivo | Rol real |
|---|---|
| `templates/pages/facturas/factura.html:12` | POS **con** `layout.html` (sidebar 160 px, header 56 px, footer). |
| `templates/pages/facturas/factura-standalone.html:21,23,30` | POS **sin** layout, pero **no es imprimible**: emite `{#bundle /}` (htmx + Alpine + Bulma), re-declara CSRF en `<body>` y embebe el formulario `factura-body`. Es una pantalla de captura. |
| `templates/pages/facturas/factura-body.html:197` | `<div id="facturar-resultado" aria-live="polite">` donde se inyecta el resultado. |
| `templates/layout.html:57,69-71,73-75` | Shell: `.app-shell`, `main.app-content`, `footer.app-footer`. |

Estilos de impresión que existen hoy:

- **Ninguno** en `web/index.scss` ni en `web/mercurius.scss` (búsqueda de
  `@media print`, `@page`, `print-color-adjust`: cero resultados).
- El único `@page` del repo es de etiquetas: `pages/reportes/etiquetas.html:14`
  (`size: A4; margin: 6mm`) con el patrón `body * { visibility: hidden }` +
  `#vista-previa { visibility: visible }` (`:18-27`). Sirve como precedente de
  estilo, no como solución.

Y sí hay alturas de viewport que arruinarían una impresión de página completa:

- `web/mercurius.scss:335` `.app-shell { min-height: 100vh }`
- `web/mercurius.scss:396` `.app-sidebar { height: 100vh }` (sticky, ≥1025 px)
- `web/mercurius.scss:347-351` `.app-content { padding: var(--merc-content-padding) }`

Ninguna está dentro de `@media print`. Imprimir la página del POS con
`window.print()` produce, como mínimo, una página de viewport con sidebar y
footer, y normalmente una segunda con el contenido.

### 2.4 `PDFGenerator`: geometría, márgenes y cálculo de alto

`addContentToDocument` (`Utils/PDFGenerator.java:334-698`):

```java
int lineas = carrito == null ? 0 : carrito.size();
float altoPagina = 566.93f + Math.max(0, lineas - 3) * 14f; // 200 mm base
Rectangle pageSize = new Rectangle(204.09f, altoPagina);      // 72 mm
Document document = new Document(pageSize, 6f, 6f, 6f, 6f);
```

- `204.09 pt = 72.00 mm` exactos (`204.09/72*25.4`).
- `566.93 pt = 200.00 mm` exactos.
- Márgenes 6 pt por lado → **área útil 192.09 × 554.93 pt = 67.8 × 195.8 mm**.
- Crecimiento: **14 pt (4.94 mm) por ítem a partir del cuarto**.

`buildComprobanteDocument` (`:132-245`, usado por NC y por la reimpresión) usa la
misma geometría: `:139-140` idéntico alto y márgenes.

Lo que **no** se contabiliza en ese alto:

| Elemento | Línea | Coste no presupuestado |
|---|---|---|
| Encabezado del emisor | `:378-397` | hasta 6 líneas (`settings`), ~63 pt |
| Filas de la tabla | `:458-512` | ~14-16 pt por fila (leading por defecto de `Phrase` ≈ 1.5 × 9 pt + `padding(1)`) |
| **Envoltura del nombre** | `:431-432,498-502` | columna de descripción = 2.5/5.0 × 192.09 = **96 pt ≈ 34 mm**; en Helvetica 9 pt caben ~21-24 caracteres, así que un nombre de 40 caracteres ocupa 2 líneas y la fórmula solo presupuesta **una** |
| Promociones | `:548-552` | una línea por promoción distinta |
| Medios de pago | `:592-632` | 2 celdas por entrada + total pagado + vuelto |
| Tasas de impuesto | `:638-655` | una fila por tasa distinta |
| Autorización / leyendas | `:657-667` | 4 líneas, una de ellas larga |
| QR + clave | `:669-691` | **90 pt de imagen** + etiqueta |
| Gracias | `:693-695` | 1 línea |

Consecuencia práctica (estimación derivada del código, hay que confirmarla
generando PDFs): el presupuesto se agota alrededor de **18-22 líneas de una sola
línea de texto**; con nombres que envuelven o con 2 tasas de impuesto, mucho
antes. Ahí aparece la página 2 con solo totales y QR. En el sentido contrario, un
tiquete de 1-3 líneas deja **~70 pt (25 mm) de cola en blanco** dentro del page
box de 200 mm.

Fuente: `:145-147` (POS) y `:349-351` (comprobante) crean
`new com.lowagie.text.Font()` **sin familia**: OpenPDF usa Helvetica, no
monoespaciado. La alineación de columnas depende de `PdfPTable`, no de caracteres
de ancho fijo.

### 2.5 Reimpresión

`Controllers/Api/App/RecibosResource.java:348-391`:

- `GET /api/app/recibos/{id}/pdf`, `@Produces(APPLICATION_OCTET_STREAM)`.
- Resuelve **cualquier** comprobante (`:359`), no solo recibos.
- Nombre: `factura_<clave>.pdf` si `codigoDocumento == "02"`, si no
  `tiqueteElectronico_<id>.pdf` (`:367-372`).
- Si el archivo no está (histórico o movido al ZIP mensual), lo **regenera** con
  `pdfGenerator.generarPDFComprobante(f, settings)` (`:374-376`).
- `Content-Disposition: inline` (`:383-384`).

Diferencia real entre original y reimpresión: `buildComprobanteDocument` **no
imprime QR** (no hay llamada a `QRCodeGenerator` en `:132-245`) y **no imprime
cajero**. El tiquete emitido en caja lleva QR; la reimpresión desde el historial
no. Para un documento fiscal eso es una inconsistencia, no un detalle estético.

El otro camino (POS/NC) sirve bytes ya escritos:
`PosResource.java:1138-1177` (`GET /api/app/pos/facturas/{fileName}`), con
`Content-Disposition: attachment` (`:1176-1177`), validación por regex
(`:110-112`) y respaldo en el ZIP mensual (`:1166-1173`).

### 2.6 Lo que **no** existe

- Plantilla HTML de tiquete imprimible: no existe. Las tres plantillas de
  `pages/facturas/` son pantallas de captura.
- Endpoint HTML de comprobante: no existe (solo PDF/binario).
- `@media print` para la app: no existe.
- `beforeprint`/`afterprint`: no se usan en ningún JS del bundle.
- QR servido como imagen: no existe endpoint; `QRCodeGenerator.java:34-53` genera
  PNG en memoria solo para el PDF (`PDFGenerator.java:673`).

---

## 3. Correcciones a la versión anterior de este documento

| Afirmación previa | Realidad verificada |
|---|---|
| "`factura-standalone.html` es la más cercana a lo que se necesita para imprimir" | Es la pantalla del POS sin layout: carga el bundle completo, Bulma, htmx y CSRF (`:21,23,30`). No sirve como base de impresión; la base correcta es una plantilla nueva autocontenida, sin bundle. |
| "`@page { size: 80mm auto }` → alto continuo" | **No es CSS válido.** La gramática de `size` es `auto \| <length>{1,2} \| [<page-size> \|\| portrait\|landscape]`. Mezclar una longitud con `auto` invalida la declaración completa; el navegador la descarta y usa el papel del driver (Letter/A4 por defecto). Es la causa más probable del síntoma "sale enorme". |
| "El problema en Chrome es que `print.js` espera `onload` del window y no del iframe" | Correcto pero incompleto: además, el handler se asigna **después** de `document.close()` (`print.js:60-65`), por lo que el `load` puede haberse disparado ya; y el popup se abre tras el XHR, fuera de la activación del usuario. |
| "Endpoint: 'no verificado', confirmar el resource" | Verificado: `RecibosResource` (`@Path("/api/app/recibos")`, `@RolesAllowed({"admin","facturacion"})`, `:126-129`) ya resuelve cualquier comprobante por id y regenera el PDF si falta (`:348-391`). Es el lugar natural para el endpoint HTML. |
| "PDF y CSS dan los mismos caracteres por línea (~37-40)" | No: el PDF usa **Helvetica** (`PDFGenerator.java:145,349`), no monoespaciado, y su área útil es 67.8 mm, no 72 mm. Los 37-40 caracteres solo aplican a la plantilla HTML monoespaciada de 72 mm. |
| "Criterio: alto < 120 mm" para el PDF | Imposible: el page box del PDF es siempre 72 × 200 mm (`:342-344`). Ese criterio solo aplica a la ruta HTML. Criterios corregidos en §11. |
| "Fidelidad fiscal: HTML media" | La fidelidad fiscal vive en el XML de Hacienda, no en el PDF ni en el HTML. Lo que cambia es la **fidelidad de maquetación**, y ambas rutas leen el mismo comprobante persistido. |

---

## 4. Tamaños de papel y tipografía

### 4.1 Anchos

| Papel | Nominal | Imprimible útil | En el código |
|---|---|---|---|
| 80 mm | 80 mm | 72 mm (estándar) | `204.09 pt` (`PDFGenerator.java:343`) |
| 58 mm | 58 mm | 48 mm (típico) | no implementado (`136.06 pt`) |

El ancho útil lo fija el cabezal y el **formulario de papel del driver**. Hay que
declarar en el driver exactamente el mismo ancho que se use en CSS/PDF; si el
driver dice 80 mm y el CSS pide 72 mm, el navegador centra la página y aparece un
desplazamiento lateral.

### 4.2 Conversiones

1 in = 25.4 mm; 1 mm = 96/25.4 = 3.7795 px (CSS a 96 dpi); 1 pt = 1/72 in.

| Medida | pt | px (96 dpi) |
|---|---|---|
| 48 mm | 136.06 | 181.4 |
| 58 mm | 164.41 | 219.2 |
| 72 mm | 204.09 | 272.1 |
| 80 mm | 226.77 | 302.4 |
| 200 mm | 566.93 | 755.9 |

### 4.3 Caracteres por línea

Monoespaciado ≈ 0.6 × tamaño. A 9 pt el carácter mide 5.4 pt = 1.905 mm.

| Ancho útil | 8.5 pt | 9 pt | 10 pt |
|---|---|---|---|
| 48 mm (58 mm) | 26 | 25 | 22 |
| 67.8 mm (área útil del PDF) | 38 | 35 | 31 |
| 72 mm (HTML 80 mm) | 40 | 37 | 33 |

En el PDF: 192.09 pt útiles / 5.4 pt = **35 caracteres** si fuese monoespaciado;
como es Helvetica proporcional, el conteo real varía por carácter y la descripción
envuelve antes (ver §2.4).

---

## 5. Por qué falla hoy (causas ordenadas por impacto)

| # | Causa | Evidencia | Fix |
|---|---|---|---|
| 1 | `@page` inválido / ausente → el navegador imprime con Letter/A4 y "Ajustar a la página" | §3, no hay `@page` en el bundle | `@page` con ancho y alto explícitos (§9) |
| 2 | Popup bloqueado tras un XHR largo (Hacienda + PDF) | `print.js:55`, `PosResource.java:821,839` | iframe oculto: no hay popup que bloquear |
| 3 | `onload` de la ventana asignado después de `close()`, y no es el `load` del iframe | `print.js:60-65` | `iframe.addEventListener('load')` + `document.fonts.ready` |
| 4 | Alto del PDF fijo en 200 mm: cola en blanco o segunda página | `PDFGenerator.java:342,139` | alto HTML calculado; auditoría del PDF (§10 F4) |
| 5 | `min-height:100vh` y `height:100vh` fuera de `@media print` | `mercurius.scss:335,396` | no imprimir la página de la app; usar iframe |
| 6 | Driver sin formulario de 72/80 mm continuo, escala distinta de 100 % | operativo | Fase 0 |
| 7 | Reimpresión sin QR | `PDFGenerator.java:132-245` | ticket aparte (§10 F5) |

---

## 6. Mecanismo de impresión: iframe vs ventana vs `window.print()`

| Criterio | `window.print()` en la página del POS | Ventana nueva + PDF (hoy) | **Iframe oculto (recomendado)** |
|---|---|---|---|
| Popup bloqueado | no aplica | **sí**, tras XHR largo | no aplica |
| Control del documento | malo: hay que ocultar shell, sidebar, header, modales, toasts | bueno (PDF) | bueno (documento propio) |
| Fuga de chrome (`100vh`, sidebar) | alta (`mercurius.scss:335,396`) | nula | nula |
| Falla por `onload` | no | **sí** (`print.js:62`) | controlable: `load` del iframe + `fonts.ready` |
| Impresión silenciosa con `--kiosk-printing` | sí | sí | sí |
| Firefox | sí | depende de popups | sí, si el iframe tiene caja (no `display:none`) |
| Reutilizable para POS, historial y NC | no | sí | sí |

Decisión: **iframe oculto** con el documento HTML del tiquete. Se mantiene
`printPDF` como *fallback* y para "Descargar PDF".

Reglas del iframe que importan:

- No usar `display:none` ni `visibility:hidden`: Chrome puede imprimir en blanco y
  Firefox no compone el documento. Usar caja real fuera de pantalla:
  `position: fixed; left: -10000px; top: 0; width: 72mm; height: 297mm; border: 0;`
- Esperar `iframe.addEventListener('load', ...)` y luego `document.fonts.ready`
  antes de medir/imprimir (las fuentes cambian el alto).
- `contentWindow.focus(); contentWindow.print();` imprime **solo** el documento
  del iframe.
- Destruir el iframe en `afterprint` (y por timeout) para no acumular nodos.
- Mismo origen obligatorio (lo es: misma app). Si algún día se imprime un origen
  distinto, hay que volver a ventana.

---

## 7. Impresión silenciosa y respaldo

### 7.1 Chrome / Edge

- Lanzar el navegador con `--kiosk-printing`. Con el flag activo, `print()`
  envía directo a la **impresora predeterminada** con su configuración por
  defecto, sin diálogo.
- Requisitos: la térmica debe ser la predeterminada del SO y su formulario de
  papel debe ser el correcto. El flag no corrige un driver mal configurado.
- Comprobación: `chrome://version` debe listar el flag en la línea de comandos.
- **No verificado en esta estación** (requiere ejecutar el navegador).

### 7.2 Firefox

- No hay flag equivalente. La vía documentada es `about:config`:
  `print.always_print_silent = true` y, por impresora,
  `print.printer_<nombre>.print_silent = true`.
- Es frágil: depende del perfil, del nombre exacto de la impresora y de la versión.
- Sin impresión silenciosa, el diálogo aparece; el objetivo entonces es que el
  diálogo ya venga con **72 mm y 100 %** preseleccionados, que es lo que aporta
  `@page`.
- **No verificado** en la versión instalada.

### 7.3 Respaldo PDF

`printHTML(url, { fallbackPdfUrl })`: si el `GET` del HTML falla, si el iframe no
dispara `load` en 8 s o si `contentWindow.print` lanza, se llama a `printPDF`
(que ya reporta por toast, `print.js:19-28`) y se registra en consola con el id
del comprobante. Nunca se deja al cajero sin feedback.

---

## 8. Tiquete imprimible: plantilla y endpoint

### 8.1 Plantilla nueva

`app/src/main/resources/templates/pages/facturas/tiquete-print.html`

- Documento **completo** (`<!DOCTYPE html>`), **sin** `{#include layout}`, **sin**
  `{#bundle /}`, **sin** htmx/Alpine/Bulma. CSS embebido en `<style>`.
- Motivo: el iframe solo necesita el documento; cargar el bundle duplicaría
  scripts dentro del iframe y arrastraría estilos de pantalla.
- Estructura: emisor (desde `settings`, igual que `PDFGenerator.java:378-397`),
  título según `codigoDocumento` (`:313-326`), datos (fecha, consecutivo, clave,
  cliente, cajero), tabla de líneas (cantidad / descripción / monto + letra de
  impuesto), descuentos, IVA, total, medios de pago y vuelto, desglose por tasa,
  QR, clave en texto y pie.
- QR embebido como `data:image/png;base64,...` generado en el recurso con
  `QRCodeGenerator.generateQRCodeBytes(clave)` (`QRCodeGenerator.java:34-53`),
  igual que `PDFGenerator.java:673`. Sin endpoint nuevo de imagen.
- Un solo `<style id="page-size">` con el `@page` inicial, para que el JS pueda
  reescribirlo con el alto medido (§9.3).

### 8.2 Endpoint propuesto

```
GET /api/app/recibos/{id}/tiquete
    Rol:        admin, facturacion   (heredado de RecibosResource.java:128)
    Produce:    text/html; charset=UTF-8   (override del @Produces de clase, :127)
    Sin CSRF:   GET sin mutación
    Resuelve:   comprobantesEmitidosService.find(id)  → 404 si null  (:359-362)
                appSettingsService.returnCurrent()    → 500 si null  (:363-366)
    Modelo:     comprobante, config, lineas, receptor, resumen,
                qrDataUri, altoMm
    Cache:      Cache-Control: no-store (documento vivo)
```

Por qué ahí y no en `PosPageResource`:

- `RecibosResource` ya sirve `/{id}/pdf` para **cualquier** comprobante
  (`:348-352`), con los mismos roles y la misma resolución.
- Un solo endpoint cubre las tres entradas: POS (con `comprobanteId`), historial
  (`id` de la fila) y NC (mismo id).
- `PosPageResource` (`:34-36`) es de páginas de captura, no de documentos.

Cambios mínimos de plomería en `PosResource` para que el POS conozca la URL con el
root correcto (no hardcodear `/api/...`, que se rompe bajo otro context path):

1. `PosResource.java:2715-2738` (`FacturarResult`): añadir
   `public @Nullable String tiqueteUrl;`
2. `PosResource.java:885` y `:978` (camino normal y replay):
   `out.tiqueteUrl = uriBasePath() + "api/app/recibos/" + comprobante.getId() + "/tiquete";`
3. `PosResource.java:2246-2248` (`resultadoModel`): `resultado.put("tiqueteUrl", data.tiqueteUrl);`

`facturar-resultado.html` ya recibe `resultado.comprobanteId` (`:2248`) y no lo usa;
con `tiqueteUrl` queda explícito y no hay que componer rutas en Qute.

---

## 9. Receta CSS

### 9.1 Reglas que sí importan

- `@page { size: <ancho> <alto>; margin: 0 }` con **ambos valores en longitud**.
  No usar `auto` mezclado con longitud (§3).
- `html, body { margin: 0; padding: 0 }` y **nunca** `height/min-height: 100vh`.
- Ancho del contenedor en `mm`, no en `px` de viewport.
- `box-sizing: border-box`, `overflow-wrap: anywhere` en descripciones.
- Monoespaciado 9 pt, `line-height: 1.15` (3.7 mm por línea) para no desperdiciar
  rollo; `font-variant-ligatures: none`.
- Tablas con `border-collapse: collapse` y columnas en `mm`/`%`, no `flex` para
  filas de importes (flex se parte raro al paginar).
- QR a 18-25 mm, `image-rendering: pixelated` (nítido en térmica).
- `break-inside: avoid` en bloques; `break-after: avoid` en títulos.

### 9.2 80 mm (72 mm útiles)

```css
/* tiquete 80 mm — ancho útil 72 mm */
@page {
  size: 72mm 160mm;   /* el alto lo recalcula el JS antes de imprimir */
  margin: 0;
}

html, body {
  margin: 0;
  padding: 0;
  background: #fff;
  color: #000;
}

.tiquete {
  width: 72mm;
  box-sizing: border-box;
  padding: 2mm 1.5mm 3mm;
  font-family: "Courier New", Courier, monospace;
  font-size: 9pt;
  line-height: 1.15;
  font-variant-ligatures: none;
  overflow-wrap: anywhere;
}

.tiquete .titulo {
  font-size: 11pt;
  font-weight: 700;
  text-align: center;
  margin: 0 0 1.5mm;
  break-after: avoid;
}

.tiquete .centro { text-align: center; }
.tiquete .der { text-align: right; }

.tiquete .sep {
  border: 0;
  border-top: 0.3mm dashed #000;
  margin: 1.5mm 0;
  break-inside: avoid;
}

.tiquete table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 9pt;
}
.tiquete th, .tiquete td {
  padding: 0.4mm 0;
  vertical-align: top;
  font-weight: 400;
}
.tiquete col.c-cant { width: 9mm; }
.tiquete col.c-desc { width: 42mm; }
.tiquete col.c-monto { width: 21mm; }

.tiquete .fila {
  display: flex;
  justify-content: space-between;
  gap: 2mm;
}
.tiquete .fila .valor { white-space: nowrap; }

.tiquete .qr {
  display: block;
  width: 20mm;
  height: 20mm;
  margin: 2mm auto 1mm;
  image-rendering: pixelated;
}

.tiquete .clave {
  font-size: 7.5pt;
  text-align: center;
  letter-spacing: 0.02em;
  overflow-wrap: anywhere;
}

/* Solo para revisar en pantalla; no afecta la impresión */
@media screen {
  body { background: #e9e9e9; }
  .tiquete { background: #fff; margin: 6mm auto; box-shadow: 0 0 4px rgba(0,0,0,.35); }
}
```

### 9.3 Alto calculado (la pieza que evita cola en blanco)

El servidor puede estimar el alto (`altoMm` en el modelo) para el `@page` inicial,
pero la medida fiable se toma en el cliente con el layout real:

```js
// dentro de printHTML(), cuando el iframe ya disparó "load"
function ajustarAlto(frame, anchoMm, altoPorDefectoMm) {
    var doc = frame.contentDocument;
    var tiquete = doc.getElementById('tiquete');
    var pageStyle = doc.getElementById('page-size');
    if (!tiquete || !pageStyle) {
        return altoPorDefectoMm;
    }
    // getBoundingClientRect está en px CSS (96 dpi) porque el iframe mide 72mm.
    var altoPx = tiquete.getBoundingClientRect().height;
    var altoMm = Math.ceil(altoPx * 25.4 / 96) + 2; // +2 mm de aire para el corte
    pageStyle.textContent = '@page { size: ' + anchoMm + 'mm ' + altoMm + 'mm; margin: 0; }';
    return altoMm;
}
```

Requisito para que la medida sea fiel: el `<iframe>` debe tener
`width: <ancho>mm` (no 100 %), porque `getBoundingClientRect()` mide con el ancho
de composición del iframe y el tiquete se maqueta a ese ancho.

Estimación inicial del servidor (respaldo si el JS no mide, calibrable):

```
altoMm = 92 + 4.0 * lineas      // 5 líneas → 112 mm; 20 → 172 mm; 40 → 252 mm
```

El valor real depende de cuántas líneas tenga el encabezado del emisor
(`settings`: 2-6). Se calibra con la matriz de pruebas; el `<style>` del servidor
solo existe para que el primer render no sea 200 mm fijos.

### 9.4 58 mm (48 mm útiles)

```css
@page { size: 48mm 180mm; margin: 0; }

.tiquete {
  width: 48mm;
  padding: 2mm 1mm 3mm;
  font-size: 8.5pt;
  line-height: 1.15;
}
.tiquete col.c-cant { width: 7mm; }
.tiquete col.c-desc { width: 27mm; }
.tiquete col.c-monto { width: 14mm; }
.tiquete .qr { width: 18mm; height: 18mm; }
.tiquete .clave { font-size: 7pt; }
```

A 8.5 pt caben ~26 caracteres en 48 mm; las descripciones largas ocupan 2-3
líneas y el tiquete crece en alto, no en ancho. No reducir a 7 pt salvo que el
cabezal imprima sucio: la térmica de 58 mm pierde nitidez rápido.

### 9.5 Esqueleto de la plantilla

```html
<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8"/>
  <title>Tiquete {comprobante.encabezado.numeroConsecutivo}</title>
  <style id="page-size">@page { size: 72mm {altoMm}mm; margin: 0; }</style>
  <style>/* receta 9.2 o 9.4 */</style>
</head>
<body>
  <div class="tiquete" id="tiquete">
    <p class="titulo">{titulo}</p>
    <p class="centro">{config.nombreNegocio}</p>
    <p class="centro">{config.identificacion}<br/>{config.direccionCompleta}</p>
    <hr class="sep"/>
    <div>Fecha: {fecha}<br/>Consecutivo: {consecutivo}<br/>Cliente: {receptor}<br/>Cajero: {cajero}</div>
    <hr class="sep"/>
    <table>
      <colgroup><col class="c-cant"/><col class="c-desc"/><col class="c-monto"/></colgroup>
      <thead><tr><th>Cant</th><th>Descripción</th><th class="der">Total</th></tr></thead>
      <tbody>
        {#for l in lineas}
        <tr><td>{l.cantidad}</td><td>{l.detalle}</td><td class="der">{monto}</td></tr>
        {/for}
      </tbody>
    </table>
    <hr class="sep"/>
    <div class="fila"><span>Descuentos</span><span class="valor">{descuentos}</span></div>
    <div class="fila"><span>IVA</span><span class="valor">{iva}</span></div>
    <div class="fila"><span><strong>Total</strong></span><span class="valor"><strong>{total}</strong></span></div>
    <hr class="sep"/>
    {#if qrDataUri}<img class="qr" src="{qrDataUri}" alt="Código QR de la clave"/>...{/if}
    <p class="clave">{clave}</p>
    <p class="centro">*** GRACIAS POR SU PREFERENCIA ***</p>
  </div>
</body>
</html>
```

(`{titulo}`, `{fecha}`, `{monto}`, etc. son claves del modelo que arma el recurso;
los importes se formatean en Java con `FormatoVista.moneda`, igual que el PDF, para
que las dos rutas impriman el mismo texto.)

---

## 10. Plan de implementación

Estimación total: **5 días**. Orden pensado para que cada fase sea verificable
sola y para no tocar `print.js` antes de que exista el documento que va a
imprimir.

### Fase 0 — Driver y estación (0.5 día, operativo, sin código)

- En cada caja: formulario de papel del driver = **72 mm de ancho** (o 80 mm si el
  driver no permite 72) × continuo, orientación retrato, escala 100 %, márgenes 0.
- Térmica como impresora **predeterminada** del SO (requisito de
  `--kiosk-printing`).
- Imprimir una línea de `#` repetidos hasta el borde y anotar cuántos caben; si no
  caben 37, el útil real es menor y hay que ajustar el ancho de la plantilla.
- Dejar el checklist en este documento (Anexo A).

### Fase 1 — Plantilla y endpoint (1.5 días)

Archivos:

1. **Nuevo** `app/src/main/resources/templates/pages/facturas/tiquete-print.html`
   (Qute, autocontenido, sin layout ni bundle; §8.1, §9).
2. `app/src/main/java/Controllers/Api/App/RecibosResource.java`
   - inyectar `@Location("pages/facturas/tiquete-print") Template tiquetePrint;`
     junto a las otras tres plantillas (`:176-188`);
   - añadir `@GET @Path("/{id}/tiquete") @Produces(MediaType.TEXT_HTML)` que
     resuelve comprobante (`:359-362`), settings (`:363-366`), arma el modelo,
     codifica el QR y devuelve `Response.ok(html).type("text/html; charset=UTF-8")`.
     Reutilizar el patrón `htmlOk(...)` de `AppRecursoBase:98`.
3. `app/src/main/java/Controllers/Api/App/PosResource.java`
   - `FacturarResult.tiqueteUrl` (`:2715-2738`);
   - asignarlo en `:885` y `:978`;
   - publicarlo en `resultadoModel` (`:2246-2248`).

Verificación de fase: abrir la URL en el navegador (rol `facturacion`) y ver el
tiquete completo con QR; comparar texto contra el PDF del mismo id.

### Fase 2 — `printHTML` (1 día)

Archivo: `app/src/main/resources/web/print.js`

- `export function printHTML(url, options)`:
  - crea el iframe fuera de pantalla con `width: 72mm`;
  - `load` → `document.fonts.ready` → `ajustarAlto()` (§9.3) →
    `requestAnimationFrame` → `focus()` + `print()`;
  - `afterprint` → destruir; timeout 8 s → destruir + `fallbackPdfUrl`;
  - errores por toast con el id del comprobante (mismo patrón que
    `reportPrintFailure`, `:19-28`);
  - `frame.contentWindow === null` (origen distinto) → fallback.
- No tocar `printPDF` (sigue siendo el fallback y "Descargar PDF").

Verificación de fase: en la consola, `printHTML('/api/app/recibos/<id>/tiquete')`
con un comprobante real; comprobar 1 página y que el diálogo muestre 72 mm.

### Fase 3 — Conectar las tres entradas (1 día)

1. `templates/pages/facturas/facturar-resultado.html`
   - botón: `data-tiquete-url="{resultado.tiqueteUrl}"`;
   - `:84-92` → `printHTML(tiqueteUrl, { fallbackPdfUrl: pdfUrl })`;
   - `:95-104` (auto-impresión) → mismo camino, conservando `obligatorio` y el
     dedupe, cambiando la clave a `'mercurius.printed.' + comprobanteId`;
   - conservar el `try/catch` de `:105-107` y su `console.warn`.
2. `templates/pages/recibos/detalle.html:20-24`
   - `onclick` → `printHTML('/api/app/recibos/{id}/tiquete', { fallbackPdfUrl: '/api/app/recibos/{id}/pdf' })`
     (o `data-` + listener, para no depender del global).
3. `Controllers/Api/App/DevolucionesResource.java:1914-1918`
   - el botón NC pasa a `printHTML('/api/app/recibos/<id>/tiquete', { fallbackPdfUrl: printUrl(clave) })`.
     Para eso el `NcSummary` necesita el id del comprobante NC; si no lo tiene,
     dejar NC con `printPDF` en esta fase y anotarlo como deuda (no inventar el
     dato).

Verificación de fase: los tres botones, con y sin auto-impresión, y el caso de
error (endpoint caído) cayendo al PDF.

### Fase 4 — Auditoría del PDF del servidor (0.5 día)

Archivo: `app/src/main/java/Utils/PDFGenerator.java`

- `addContentToDocument:342` y `buildComprobanteDocument:139`: presupuestar por
  **líneas renderizadas**, no por ítems: sumar líneas del encabezado, filas de
  tabla (con la envoltura estimada a partir de `detalle.length()` y 34 mm de
  columna), promociones, medios de pago y tasas.
- Opción barata y suficiente: mantener la fórmula pero añadir
  `+ 11 * max(0, líneas - 3)` extra cuando el detalle exceda ~22 caracteres, y
  subir el término por fila de 14 a 16 pt.
- Reducir el alto base solo si la medición muestra cola > 10 mm (hoy ~25 mm).
- **No** cambiar la fuente ni el diseño en esta fase: el PDF es el documento
  archivado y cualquier cambio de layout obliga a revalidar la paridad con el
  XML.

Verificación de fase: generar facturas de 1, 5, 12, 20, 30 y 40 líneas y contar
páginas con `pdfinfo`. Objetivo: 1 página hasta 20 líneas de texto corto; nunca
una página final con solo QR o solo totales.

### Fase 5 — Paridad de la reimpresión (0.5 día, ticket aparte)

- `buildComprobanteDocument` no imprime QR (`:132-245`) ni cajero. Añadir el QR
  con el mismo `QRCodeGenerator` que usa `:669-691`.
- Motivo: el cliente que pide reimpresión debe recibir el mismo documento que se
  llevó la primera vez; si no, la reimpresión parece falsa.
- Si se decide no hacerlo, documentarlo como limitación conocida en el detalle
  del historial.

### Fase 6 — Verificación en 3 navegadores (1 día)

- Matriz de §13 + criterios de §11, en Firefox, Chrome y Edge.
- Anotar versión (`about:support` / `chrome://version`) y resultado por criterio.
- Ajustar `altoMm` base y `@page` si alguna estación mide distinto.

### Fuera de este plan (varias cajas)

ESC/POS directo vía agente local (QZ Tray o servicio propio) sigue siendo la
solución para impresión silenciosa real y multi-impresora: 5-10 días + instalación
por caja. No se propone Electron ni apps nativas; el navegador cubre la caja
única.

---

## 11. Criterios de aceptación verificables

Cada criterio se mide sobre el PDF guardado ("Guardar como PDF") del propio
navegador, no a ojo.

### Ruta HTML (nueva)

| ID | Criterio |
|---|---|
| CA-1 | Factura de **5 líneas**, encabezado de 3 líneas y 1 tasa de IVA: **1 página**, ancho de página **72 mm ± 0.5**, alto **≤ 125 mm**. |
| CA-2 | La misma factura con el bloque fiscal completo (autorización + desglose + clave en texto): **1 página**, alto **≤ 160 mm**. |
| CA-3 | **Sin cola en blanco**: distancia entre la última tinta y el borde inferior de la página **≤ 6 mm**. |
| CA-4 | **Sin márgenes laterales**: la primera tinta empieza a **≤ 2 mm** del borde izquierdo y la última termina a **≤ 2 mm** del derecho. |
| CA-5 | Factura de **20 líneas**: 1 página, alto ≤ 200 mm, ninguna línea cortada ni superpuesta. |
| CA-6 | Factura de **40 líneas**: 1 o 2 páginas, pero **ninguna página vacía** y ninguna página con solo el QR. |
| CA-7 | Descripción de **60 caracteres**: envuelve dentro de la columna, no desborda a la derecha ni recorta el monto. |
| CA-8 | El tiquete impreso contiene: emisor, receptor, fecha, consecutivo, clave, líneas, descuentos, IVA, total, medios de pago, QR y leyenda. |
| CA-9 | El QR impreso a 20 mm se lee con un lector de teléfono a 15 cm. |
| CA-10 | Impresión disparada desde el POS, el historial y la NC producen **el mismo contenido** (texto y QR) para el mismo comprobante. |
| CA-11 | Con el endpoint caído (simulado: 500), el botón cae al PDF y muestra un toast de error; el cajero nunca queda sin feedback. |

### Ruta PDF (existente)

| ID | Criterio |
|---|---|
| CA-12 | El page box del PDF es **exactamente 204.09 × alto** pt (72 mm de ancho). |
| CA-13 | Factura de 1-3 líneas: **1 página**; la tinta termina al menos **10 mm** antes del borde inferior (hoy quedan ~25 mm). |
| CA-14 | Factura de 20 líneas con nombres de ≤ 22 caracteres: **1 página**. |
| CA-15 | Factura de 30 líneas con nombres de 40 caracteres: **≤ 2 páginas** y la segunda contiene totales y QR juntos. |
| CA-16 | Reimpresión desde el historial: incluye QR (tras Fase 5) o queda documentada la ausencia. |

### Transversal

| ID | Criterio |
|---|---|
| CA-17 | Ningún flujo abre popup: `window.open` no se invoca desde el camino de impresión normal. |
| CA-18 | No se agregan nodos huérfanos: tras 20 impresiones no queda ningún `iframe` en el DOM. |
| CA-19 | `@page` del documento contiene dos longitudes (`72mm 160mm`), nunca `auto` mezclado. |
| CA-20 | Con `--kiosk-printing`, 10 facturas seguidas salen sin diálogo y con corte correcto. |

---

## 12. Pruebas

### 12.1 Sin hardware (obligatorias antes de tocar la impresora)

1. **Guardar como PDF** desde el diálogo de impresión de cada navegador; guardar
   el archivo como `tiquete-<navegador>-<n-lineas>.pdf`.
2. Tamaño y páginas:
   - `pdfinfo tiquete.pdf` → `Page size` y `Pages` (poppler).
   - Sin poppler en Windows: abrir el PDF y leer propiedades, o
     `Get-Content` no sirve; usar el visor y anotar.
3. Contenido: `pdftotext -layout tiquete.pdf -` y comparar contra el PDF del
   servidor del mismo comprobante (misma clave, mismo total, mismas líneas).
4. Cotas: renderizar el PDF a PNG a 300 dpi y medir en el editor de imágenes:
   ancho de página, primera/última tinta, cola inferior. Es la forma de verificar
   CA-3 y CA-4 sin papel.
5. QR: decodificar el PNG recortado con cualquier lector (o `zbarimg`) y comparar
   con `comprobante.haciendaClave`.
6. Regresión de layout: probar nombre de artículo de 60 caracteres, cliente vacío,
   3 tasas de impuesto, 4 medios de pago y una factura con promoción.
7. Repetir todo con la variante 58 mm.

### 12.2 Con hardware

1. Configuración de Fase 0 aplicada; imprimir un tiquete de 5 líneas y **medir el
   largo del papel** con regla: debe coincidir con el alto de página ± 3 mm.
2. Verificar corte: sin avance extra después del pie ni corte a media línea.
3. Verificar ancho: sin banda blanca lateral > 2 mm ni texto cortado a la derecha.
4. Legibilidad: texto de 9 pt negro pleno, sin gris; QR legible a 15 cm.
5. Serie de 20 tiquetes seguidos con `--kiosk-printing`: sin diálogo, sin
   atascos, sin tiquetes duplicados.
6. Prueba de fallo: apagar la impresora y facturar. El sistema debe mostrar el
   error (toast) y permitir reimprimir desde el historial.
7. Prueba de rollo: cambiar el rollo a mitad de una serie y verificar que el
   siguiente tiquete no sale desplazado.
8. Anotar modelo de impresora, driver, versión y ancho útil real medido.

---

## 13. Matriz de navegadores

Leyenda: **VC** = verificado en código (leído en el repo, sin ejecutar);
**NV** = no verificado (requiere ejecución en la estación).

| Capacidad | Firefox | Chrome | Edge |
|---|---|---|---|
| `@page { size: <ancho> <alto> }` con dos longitudes | NV | NV | NV |
| `@page` con `auto` mezclado se descarta | NV (derivado de la spec) | NV | NV |
| `@page { margin: 0 }` neutraliza márgenes del diálogo | NV | NV | NV |
| `iframe` fuera de pantalla (no `display:none`) imprime | NV | NV | NV |
| `contentWindow.print()` imprime solo el iframe | NV | NV | NV |
| `document.fonts.ready` antes de medir | NV | NV | NV |
| `afterprint` en el iframe | NV | NV | NV |
| Impresión silenciosa | NV (`print.always_print_silent`, por perfil) | NV (`--kiosk-printing`) | NV (`--kiosk-printing`) |
| WebUSB / WebSerial | No (limitación conocida) | NV | NV |
| Popup bloqueado tras XHR largo | NV | NV | NV |
| El PDF del servidor se imprime a 72 mm con el driver correcto | NV | NV | NV |
| `print.js` usa `window.open` (código) | VC | VC | VC |
| `print.js` asigna `onload` tras `close()` (código) | VC | VC | VC |
| No hay `@media print` para la app (código) | VC | VC | VC |
| `.app-shell` con `min-height:100vh` (código) | VC | VC | VC |

Toda la columna "VC" es independiente del navegador: es una propiedad del árbol
de código. Las filas NV se llenan durante la Fase 6 y se anotan con versión y
fecha en el Anexo B.

---

## 14. Riesgos y límites

| Riesgo | Impacto | Mitigación |
|---|---|---|
| El navegador ignora `@page size` y usa Letter/A4 | tiquete enorme, varias páginas | medir en Fase 6; si falla, fijar el formulario de papel del driver al tamaño exacto y dejar que el navegador herede |
| `--kiosk-printing` no está disponible en la versión desplegada | aparece el diálogo en cada venta | aceptar el diálogo y garantizar que venga con 72 mm y 100 %; o pasar a ESC/POS |
| Firefox no imprime el iframe por falta de caja | impresión en blanco | iframe con `width:72mm; height:297mm` fuera de pantalla, nunca `display:none` |
| La medición del alto ocurre antes de que carguen las fuentes | tiquete cortado | `document.fonts.ready` antes de medir, y +2 mm de aire |
| El driver fuerza una altura mínima de formulario | cola en blanco inevitable | usar formulario continuo; si no existe, fijar el alto al mínimo del driver y aceptar el sobrante |
| Nombres de artículo muy largos | 2-3 líneas por ítem, tiquete largo | `overflow-wrap:anywhere` + columnas en mm + CA-7 |
| Reimpresión sin QR | documento distinto al original | Fase 5 |
| Cambiar el PDF rompe la paridad con el XML archivado | riesgo fiscal | Fase 4 limitada a presupuesto de alto, sin cambiar fuente ni orden de bloques |

---

## Anexo A — Checklist de estación

- [ ] Impresora térmica instalada y como predeterminada del SO.
- [ ] Formulario de papel: 72 mm (o 80 mm) × continuo, retrato.
- [ ] Márgenes del driver: 0. Escala: 100 %.
- [ ] Línea de `#` medida: ___ caracteres a 9 pt (esperado 37 en 80 mm / 25 en 58 mm).
- [ ] Navegador: ______ versión ______.
- [ ] Flag/perfil de impresión silenciosa: ______.
- [ ] Tiquete de prueba de 5 líneas medido: ancho ___ mm, alto ___ mm.
- [ ] QR leído correctamente: sí / no.

## Anexo B — Registro de verificación

| Fecha | Navegador / versión | CA-1 | CA-3 | CA-4 | CA-13 | Observaciones |
|---|---|---|---|---|---|---|
| | | | | | | |
| | | | | | | |
| | | | | | | |

---

## 15. Fuentes

- MDN — `@page` (gramática de `size`): https://developer.mozilla.org/en-US/docs/Web/CSS/@page
- CSS Paged Media Level 3 — descriptor `size`: https://www.w3.org/TR/css-page-3/#page-size-prop
- MDN — `break-inside`: https://developer.mozilla.org/en-US/docs/Web/CSS/break-inside
- MDN — `print-color-adjust`: https://developer.mozilla.org/en-US/docs/Web/CSS/print-color-adjust
- MDN — `Window.print()`: https://developer.mozilla.org/en-US/docs/Web/API/Window/print
- MDN — `beforeprint` / `afterprint`: https://developer.mozilla.org/en-US/docs/Web/API/Window/beforeprint_event
- MDN — `FontFaceSet.ready` (`document.fonts.ready`): https://developer.mozilla.org/en-US/docs/Web/API/FontFaceSet/ready
- Chrome Enterprise — kiosk printing (verificar vigencia): https://support.google.com/chrome/a/answer/3017305
- Firefox — preferencias de impresión (`print.always_print_silent`): comprobar en `about:config`; la URL de soporte cambia por versión.
- WebUSB: https://developer.mozilla.org/en-US/docs/Web/API/USB
- Web Serial: https://developer.mozilla.org/en-US/docs/Web/API/Web_Serial_API
- QZ Tray: https://qz.io/
- OpenPDF: https://github.com/LibrePDF/OpenPDF
- Poppler (`pdfinfo`, `pdftotext`): https://poppler.freedesktop.org/

Las URLs de soporte de Chrome/Firefox para impresión silenciosa y la vigencia de
`--kiosk-printing` se marcan como **no verificadas**: confirmar antes de la
Fase 6.
