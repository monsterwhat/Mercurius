---
target: app/src/main/resources/templates (every input/select/textarea) + _kit form components
round: 3
audit_type: read-only input-contract audit (static inspection + htmx source)
timestamp: 2026-10-26
slug: input-contract-audit
controls: 397 (350 input / 37 select / 10 textarea) across 146 templates
p0_count: 0
p1_count: 9 groups (≈70 concrete instances)
p2_count: 11 groups (≈85 instances)
---

# Mercurius — input contract audit (all controls, all 146 templates)

**Scope:** every `<input>`, `<select>` and `<textarea>` under `app/src/main/resources/templates` — 397 controls in 146 files, including `login.html`, `fragments/navbar.html` and the shared `_kit/**` form surfaces. Not limited to the product-search screens.

**Method:** static inspection only. No browser, no dev server, no Playwright, no screenshots, no Maven/npm, no DB. Started from `C:\Users\alvar\AppData\Local\Temp\opencode\audit_all_inputs.py` (8 findings at T0), then every finding was re-derived by hand from the markup, and the untestable parts were resolved against the **actual htmx source vendored in this repo** (`app/node_modules/htmx.org/dist/htmx.js`, version **2.0.10**), so the “why” below is not folklore.

**Snapshot warning (important):** other agents were editing these templates *while* this audit ran. Between my first and last pass, 8 typing hazards were closed and line numbers in the affected files moved by a few lines (`pages/articulos/*` 98 → 105 lines, `pages/recibos/tabla.html` 108 → 111, `pages/facturas-recibidas/tabla.html`, `pages/stockalert-config/index.html`). Every citation below was re-read at the end of the pass; if a line is off by ±3, the quoted attribute is still unique enough to find it.

I edited nothing. This file is the only write.

---

## 0. Headline

The typing bug the brief names is **gone** — every self-swapping typeahead was converted during the audit window to one of two sound patterns (§4). What remains is a different class of defect that the swap-pattern churn cannot fix:

- **two dialogs on the same page mutate the same form ids**, and the second one silently repaints the first (`hx-target="#forma-usuario"` resolves by `querySelector`, first match wins);
- **11 typeaheads still have no `hx-sync`**, so an older response can overwrite a newer one;
- **20 mutating forms** can double-submit;
- **6 error states are styled but not announced** (`is-danger` with no `aria-invalid`, error text with no id).

| Severity | Groups | Instances | Theme |
|---|---|---|---|
| **P0** | 0 | 0 | No control currently replaces itself while typing. 8 were found at T0 and landed during the window. |
| **P1** | 9 | ≈70 | racing typeaheads, duplicate ids across dialog bodies, whole-region re-render losing user ticks, Enter that saves an order mid-edit, double submit, unannounced error state, unnamed checkbox group, mixed filter pattern |
| **P2** | 11 | ≈85 | `type=text` search fields, missing `inputmode`, 24 px touch rows, inline widths, `autocomplete="off"` on identity fields, missing `enterkeyhint`/`spellcheck`, read-only fields that look editable, stale comments |

---

## 1. P0 — typing / focus hazards

**Status: 0 open.** The rule and the evidence, so this does not regress:

**The rule.** A control that issues `hx-get/post` on `keyup`/`input`/`search` must not live inside the region its own request replaces, unless it carries `hx-preserve="true"` **and** a stable, unique `id`. A stable id alone is not sufficient, and this is verifiable in the vendored runtime:

- `htmx.js:1893-1903` saves `{elt: activeElement, start: selectionStart, end: selectionEnd}` before the swap.
- `htmx.js:1957-1975` restores focus **only** if the element was removed *and* has an id — and restores the caret with `setSelectionRange`.
- `htmx.js:1533-1547` plus `1517-1526` implement `hx-preserve` as a *move* of the live node into a “pantry” and back (`moveBefore` when the browser supports it).

So an id buys you focus + caret back; it does **not** buy back the characters typed **while the request was in flight** — the re-rendered node carries `value="{q}"` from the response, i.e. the text as of the request, not as of the response. That is exactly the reported “typing is interrupted” symptom. `hx-preserve` (or moving the input out of the swapped region) is the only thing that keeps the live node, and with it the in-progress text.

**What existed at T0 (all now closed):**

| Control | Was | Now |
|---|---|---|
| `pages/articulos/buscador-cabys.html:24` | `hx-target="#buscador-cabys-fragmento"` (its own parent), `outerHTML`, no preserve, **no `value=` echo** → focus *and* typed text gone | input outside `#buscador-cabys-resultados` + `hx-select` + `hx-sync` |
| `pages/articulos/selector-articulos.html:21` | same shape, parent swap | same fix |
| `pages/articulos/selector-articulos-ajuste.html:21` | same shape, parent swap | same fix |
| `pages/facturas-recibidas/tabla.html:51` | `hx-target="#{modelo.id}"` (ancestor), `outerHTML`, no preserve | `hx-preserve="true"` |
| `pages/recibos/tabla.html:31` | same, ancestor swap on every keystroke | `hx-preserve="true"` |
| `pages/stockalert-config/index.html:86` | text input inside `#filtros-stock-alert-config`, targeted ancestor `#{modelo.id}` | `hx-preserve="true"` + `hx-sync` |
| `pages/stockalert-config/index.html:101,114` | two `<select>`s inside the swapped div → dropdown closes / focus drops on every change | `hx-preserve="true"` + `hx-sync` |

**Keep the guard:** `python C:\Users\alvar\AppData\Local\Temp\opencode\audit_all_inputs.py` → must print `OK: … no self-swap typing hazards`. It cannot see `hx-sync`, duplicate ids or labels — §5 covers those.

**Residual P0-adjacent risk (not a control, but it re-creates the bug):** `pages/stockalert-config/index.html:79-95` keeps the filter bar **inside** the `{#fragment tabla}` that the filter swaps. `hx-preserve` is now doing the load-bearing work for three controls. If that page is ever restructured, the durable shape is the one in `pages/articulos/tabla-articulos.html:52-74`: filter bar outside, `hx-select` into a `#resultados-*` region.

---

## 2. P1 — control contract

### P1-1 · 11 typeaheads can overwrite a newer result with an older one (no `hx-sync`)

`keyup` + `delay:300ms` does **not** abort an in-flight request. Two responses can land out of order; the last one wins regardless of which query it answers. Type `aro` → `arroz` fast on a slow link and you can end up with the results for `aro`.

`hx-sync="this:replace"` (abort the previous request from this element, issue the new one) is already the house pattern — `pages/cabys/tabla.html:41`, `pages/articulos/tabla-articulos.html:67`, `pages/facturas/articulos-picker.html:27`.

Missing on these 11 (add one attribute each, page-local, additive):

| File:line | Control |
|---|---|
| `pages/clientes/tabla.html:39` | `#{modelo.id}-q` search |
| `pages/usuarios/tabla.html:40` | `#{modelo.id}-q` search |
| `pages/recibos/tabla.html:31` | `#{modelo.id}-q` search |
| `pages/facturas-recibidas/tabla.html:51` | `recibidas-busqueda` |
| `pages/reportes/clientes.html:32` | `clientes-q` |
| `pages/reportes/correos.html:17` | `correos-q` |
| `pages/reportes/facturas.html:17` | `facturas-q` |
| `pages/reportes/loyalty.html:76` | `loyalty-q` |
| `pages/reportes/recibos-pendientes.html:35` | `recibos-pendientes-q` |
| `pages/reportes/recibos-vencidos.html:36` | `recibos-vencidos-q` |
| `pages/reportes/usuarios.html:28` | `usuarios-q` |

```html
hx-trigger="keyup changed delay:300ms"
hx-sync="this:replace"        <!-- add -->
```

**Lower risk (5, `change`-triggered, still worth coalescing):** `pages/facturas-recibidas/tabla.html:31` (bucket select) and `pages/reportes/margenes.html:23,31,40,55` — the four margenes filters all hit `#margenes-tabla-container`, so use `hx-sync="closest form:replace"` there (they should abort *each other*, not just themselves).

---

### P1-2 · Two dialogs on the same page hold the same static ids; the second one repaints the first

**Shared pattern, 10 pages.** `_kit/modal.html:58-70` renders `#{id}` / `#{id}-title` / `#{id}-body`, and `window.kitModalClosed` (`_kit/modal.html:140-147`) only unlocks scroll and returns focus — **it never clears the body**. So a body loaded once stays in the DOM forever, inside the (closed but still rendered) dialog.

Where two dialogs load the *same* body URL, the DOM ends up with two copies of every id inside that body:

| Page | Dialogs sharing one `bodyUrl` |
|---|---|
| `pages/usuarios/index.html:48,74` | `crear-usuario` / `editar-usuario` → `/api/app/users/formularios/nuevo` |
| `pages/clientes/index.html` | `crear-cliente` / `editar-cliente` |
| `pages/reportes/clientes.html:19,81` | same pair |
| `pages/articulos/index.html` | `crear-articulo`/`editar-articulo`, `revision-rapida`/`revision-articulo`, `crear-promocion`/`editar-promocion` (3 pairs) |
| `pages/categorias/index.html` | familia pair + departamento pair |
| `pages/compras/ordenes.html` | `crear-orden`/`editar-orden`, and 3 dialogs over `/api/app/ordenes/table` |
| `pages/correos/reportes.html`, `pages/correos/templates.html` | crear/editar pair each |
| `pages/inventario/index.html` | `rapido-inventario`/`revision-ajuste`/`detalles-ajuste` |
| `pages/stockalert-config/index.html` | `nueva-config`/`editar-config` |

**Why it breaks (verified path, not theory):** every render of `pages/usuarios/form.html` contains `<div id="forma-usuario">` (`:37`) and three forms that target it by id (`:49`, `:87`, `:143`). htmx resolves a target through `find(document, '#forma-usuario')` → `querySelector` (`htmx.js:1261-1263`), i.e. **first in document order** = the *create* dialog, which is included at `index.html:48`, before `editar-usuario` at `:74`. So: open “Crear Usuario” (loads a body), close it, open a row's edit, save → the PUT response is written into the **create** dialog's body. The edit dialog shows nothing changed; the create dialog now holds the edited form. Same wiring exists in `clientes/form.html:26-32`, `form-articulo.html`, `form-orden`, `form-plantilla`, `form-reporte`, `form-familia`, `form-departamento`.

The duplicate-id problem is already acknowledged in code: `pages/articulos/buscador-cabys.html:75` does `inputs[inputs.length - 1]` to “pick the last `#articulo-cabys`”, precisely because two copies can coexist.

**Exact fix (shared, one line, kills the whole class):** `_kit/modal.html:66`

```html
<section class="modal-card-body" id="{id}-body"
         x-effect="if (!open) $el.replaceChildren()">
```

`open` is already the Alpine state (`:44`); clearing on close means at most one body with a given id exists at a time, so `<label for>`, `aria-describedby` and `hx-target` always resolve inside the dialog that is on screen. Both the kit trigger (`:52`) and the `data-kit-open` bridge (`factura-body.html:319-322`, `usuarios/index.html:87-89`) mutate `open`, so both are covered.

**Surgical alternative for page owners** (if `_kit` is off-limits in the current batch): make each form target its own container instead of a document-wide id — `hx-target="closest .modal-card-body"` on `form-usuario`, `forma-cliente`, `forma-articulo`, `forma-orden`, `form-plantilla`, `form-reporte`, `form-familia`, `form-departamento`.

---

### P1-3 · Editing one CAByS code wipes the user's partial-acceptance ticks

`pages/facturas-recibidas/detalle-drawer.html:125-131`

```html
<input class="input is-small" type="text" style="min-width:9rem"
       id="linea-cabys-{linea.id}" … hx-put="…/lineas/{linea.id}"
       hx-trigger="change" hx-target="#detalle-factura-body" hx-swap="innerHTML"/>
```

The input lives **inside** `#detalle-factura-body` (the drawer body) and re-renders the whole body on `change`. The checkboxes at `:119-120` (`name="lineasAceptadas"`, `value="{linea.id}"`) are server-rendered **unchecked**. So a user who ticks 3 lines, then corrects a CAByS code on line 4, loses the 3 ticks — silently, with no message. That is data loss on a Hacienda submission screen.

**Exact fix (additive):**

1. On the checkbox (`:119`) add `id="linea-aceptada-{linea.id}" hx-preserve="true"` — the preserved live node keeps its `checked` state across the body swap.
2. On the input (`:125` area) add `hx-sync="this:replace"`.
3. Preferred (page owner, larger): give the PUT its own row-scoped target so the drawer body is not rebuilt at all.

---

### P1-4 · Changing one cart quantity rebuilds the whole cart panel mid-editing

`pages/facturas/cart-panel.html:115-122`

```html
<form class="is-inline" style="display:inline"
      hx-post="…/item/{item.codigo}/qty-set-form"
      hx-trigger="change, submit"
      hx-target="#cart-panel" hx-swap="outerHTML">
    <input type="number" name="cantidad" step="any" min="0" value="{item.cantidad}"
           class="input is-small has-text-centered"
           aria-label="Cantidad de {item.nombre}" style="width:4.5rem"/>
</form>
```

- The input has **no id**, so htmx's focus/selection restore (`htmx.js:1957-1975`) cannot fire: focus drops to `<body>` after every quantity edit.
- `#cart-panel` `outerHTML` rebuilds **every** row, so if the request for row 2 is in flight while the user is typing into row 5, row 5's text is replaced with the server value.
- No `hx-sync`, no `hx-disabled-elt`, no `inputmode`.

**Exact fix:**

```html
<form … hx-sync="this:replace">
    <input type="number" name="cantidad" step="any" min="0" value="{item.cantidad}"
           id="pos-cantidad-{item.codigo}" hx-preserve="true"
           inputmode="decimal" class="input is-small has-text-centered"
           aria-label="Cantidad de {item.nombre}" style="width:4.5rem"/>
```

The sibling +/− forms (`:105-111`, `:123-129`) are already correct: `type="button"`, `hx-include="closest form"`, `hx-disabled-elt="this"`.

---

### P1-5 · Enter in the order's article typeahead *saves the order*

`pages/compras/ordenes-form.html:130-136` (and the `template` clone at `:171-177`) is a typeahead inside the native form `#forma-orden` (`:42-45`), which has a real `<button type="submit">Guardar Orden</button>` at `:216`. The only Enter-related wiring in the page script is a **click** handler (`pages/compras/ordenes.html:168-189`); nothing intercepts `keydown`.

So the normal typeahead gesture — type “arroz”, press Enter to accept the first suggestion — performs implicit form submission and saves the order with the previous line still unselected.

**Exact fix (page-local, in the script at `pages/compras/ordenes.html:189`):**

```js
document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') { return; }
    var campo = e.target.closest && e.target.closest('.js-articulo-nombre');
    if (!campo) { return; }
    e.preventDefault();                                  // Enter never saves the order from here
    var fila = campo.closest('.orden-linea');
    var primera = fila && fila.querySelector('.js-articulo-opcion');
    if (primera) { primera.click(); }
});
```

Related (same two inputs, P1 a11y): they are comboboxes without combobox semantics. Add `role="combobox" aria-autocomplete="list" aria-expanded="true"` to each input, and `role="listbox" aria-live="polite"` to the `.js-articulo-resultados` container (`:136`, `:177`). The suggestions themselves are already safe (`ordenes-articulos.html:17`: `type="button"`, `min-height:2.25rem`).

---

### P1-6 · 20 mutating forms can double-submit

No `hx-disabled-elt` on the form and none on its submit button. A double click (or Enter then click) sends two POSTs; the entity creates duplicate rows, the validation-failure form shows an error toast on top of a success.

| File | Forms |
|---|---|
| `pages/articulos/form-articulo.html` | `:30` (PUT), `:33` (POST) |
| `pages/articulos/form-promocion.html` | `:37`, `:42` |
| `pages/articulos/form-revision.html` | `:56` |
| `pages/cabys/form.html` | `:18` |
| `pages/categorias/form-departamento.html` | `:20`, `:23` |
| `pages/categorias/form-familia.html` | `:19`, `:22` |
| `pages/clientes/form.html` | `:28`, `:31` |
| `pages/perfil/index.html` | `:74`, `:96`, `:118` |
| `pages/settings/index.html` | `:215`, `:313`, `:322`, `:350` |
| `pages/facturas/cart-panel.html` | `:115` (qty, see P1-4) |

**Exact fix (additive, one attribute per form):**

```html
hx-disabled-elt="find button[type='submit']"
```

This is the pattern already used in `pages/usuarios/form.html:50` and `pages/facturas-recibidas/detalle-drawer.html:99` (`find button[type='submit']`), and in `pages/inventario/form-ajuste.html:78` / `pages/caja/form-abrir.html:60` (`this` on the button). Prefer the `find …` form: it does not depend on every button carrying its own attribute.

Priority inside this group: entity creates (`clientes/form.html:31`, `form-departamento.html:23`, `form-familia.html:22`, `form-articulo.html:33`, `form-promocion.html:42`, `ordenes-form.html:45`) → P1; `settings` saves and `perfil` changes → P2 (mostly idempotent PUTs).

---

### P1-7 · Six error states are coloured but not announced; eight field errors cannot be referenced

`is-danger` is applied to the control, but `aria-invalid` is not, and the error paragraph has no id — so neither a screen reader nor `aria-describedby` can connect the message to the field:

| Control | Error text |
|---|---|
| `pages/cabys/form.html:30` (textarea `descripcion`) | `:34` (no id) |
| `pages/categorias/form-departamento.html:29` (`departamento-nombre`) | `:33` (no id) |
| `pages/categorias/form-familia.html:28` (`familia-nombre`) | `:32` (no id) |
| `pages/articulos/form-promocion.html:168` (`promo-fin`) | `:53` (no id) |
| `pages/loyalty/panel-ajustes.html:65` (`ajustes-cashback`) | `:92` (no id) |
| `pages/loyalty/panel-ajustes.html:80` (`ajustes-meses`) | `:92` (no id) |

Field-level errors with **no id** also exist in `pages/caja/form-cerrar.html:92` and `pages/usuarios/form.html:180` (that one at least sets `aria-invalid` on `:178`); form-level `role="alert"` banners (`ordenes-form.html:51`, `form-articulo.html:37`, `clientes/form.html:42`, `usuarios/form.html:39`) are fine as-is — they announce on insert and need no `for`/`describedby`.

**Exact fix** (the file already contains the correct pattern at `pages/usuarios/form.html:62-67` — copy it):

```html
<input class="input {#if errorNombre??}is-danger{/if}" id="departamento-nombre" …
       {#if errorNombre??}aria-invalid="true" aria-describedby="departamento-nombre-error"{/if}/>
…
{#if errorNombre??}<p class="help is-danger" id="departamento-nombre-error" role="alert">{errorNombre}</p>{/if}
```

---

### P1-8 · The only unnamed control in the app is a bulk-selection checkbox list, and its label points at the wrong thing

`pages/reportes/ventas-fecha-historica.html`

```html
:37  <label class="label" for="busqueda-elementos">Elementos (marque los que desea, vacío = top 8)</label>
:39  <input class="input" type="search" id="busqueda-elementos" placeholder="Buscar..."/>
:53  <input type="checkbox" name="items" value="{o}" {#if itemsSeleccionados.contains(o)}checked{/if}/>
```

- `:53` is the **only** control in 397 with no programmatic name (no `aria-label`, not inside a `<label>`, no `for=` pointing at it). A screen-reader user hears “checkbox, items”.
- `:37` reads like the group's label, but it is `for=` the text filter, so the group has no label at all and the filter has a misleading one.

**Exact fix:**

```html
<div class="field" role="group" aria-labelledby="elementos-label">
    <span class="label" id="elementos-label">Elementos (marque los que desea, vacío = top 8)</span>
    <input class="input" type="search" id="busqueda-elementos"
           aria-label="Filtrar la lista de elementos" …/>
    …
    <input type="checkbox" name="items" value="{o}" aria-label="Seleccionar {o}" …/>
```

(The same checkbox-list pattern is already correct in `pages/reportes/_tablas/etiquetas.html:12` — `aria-label="Seleccionar {f.nombre}"`.)

---

### P1-9 · Two pages mix both sanctioned filter patterns; Enter reloads the page

`_kit/filter-bar.html:55-60` states the rule: pick *declarative* (controls carry `hx-get`, no submit) **or** *explicit* (controls inert, `Filtrar` submits) — “Do not mix both on one page.” Two pages mix:

- `pages/stockalert-config/index.html:81-133` — three `hx-get` controls **and** `<button type="submit">Buscar</button>` at `:131`.
- `pages/reportes/margenes.html:20-71` — four `hx-get` controls **and** a submit at `:69`.

Consequence: typing filters live, but pressing Enter in the text/date field performs an implicit native GET — a full page navigation that discards scroll position and any modal state, and looks like the app “reloaded itself”.

**Exact fix (needs page-owner sign-off, it removes a visible control):** keep the declarative path and delete the submit. With 2+ fields in the form and no submit button, implicit submission does not fire, so Enter becomes a no-op instead of a reload. Alternative, if the visible button must stay: drop `hx-get` from the controls and let the form submit (pattern b). Either way, do not ship both.

---

## 3. P2 — polish

1. **12 search fields are `type="text"`** (no clear button, no `Escape` clear, no search keyboard on mobile): `pages/clientes/tabla.html:39`, `pages/usuarios/tabla.html:40`, `pages/recibos/tabla.html:31`, `pages/facturas-recibidas/tabla.html:51`, `pages/facturas/articulos-picker.html:20`, `pages/facturas/client-picker.html:19`, `pages/compras/ordenes-form.html:130,171`, `pages/devoluciones/index.html:72`, `pages/reportes/precios.html:15`, `pages/reportes/resumen.html:45`, `pages/stockalert-config/index.html:86`. Fix: `type="search"` — nothing else changes, all these already have `name`/`label`/`autocomplete`.
2. **10 `<input type="number">` without `inputmode`** (mobile shows the full keyboard, minus/period hard to reach): `pages/facturas/cart-panel.html:119,185`, `pages/reportes/etiquetas.html:27,31`, `pages/reportes/ventas-fecha-historica.html:11,17,23`, `pages/settings/index.html:517,532,648`. Fix: `inputmode="numeric"` (integers), `inputmode="decimal"` (money).
3. **8 controls at 24 px (`is-small`) inside touch rows** — below the 44 px target: `pages/recibos/tabla.html:31`, `pages/facturas/cart-panel.html:119,185`, `pages/facturas/payment-dialog.html:62,84`, `pages/facturas/articulos-picker.html:66`, `pages/articulos/selector-articulos.html:54`, `pages/facturas-recibidas/detalle-drawer.html:125`. Fix per page: drop `is-small` below 768 px, or bump the row min-height (the pattern used by `pages/compras/ordenes-articulos.html:19`: `min-height:2.25rem`).
4. **18 inline widths on inputs/selects** where a utility exists: `pages/compras/ordenes-form.html:140,145,181,186` (`width:7rem/8rem`), `pages/facturas/cart-panel.html:119` (`4.5rem`), `pages/facturas/payment-dialog.html:62,84` (`max-width:9rem`), `pages/facturas-recibidas/detalle-drawer.html:125` (`min-width:9rem`), `pages/facturas/articulos-picker.html:66` (`5rem`), `pages/articulos/selector-articulos.html:54`, plus `style="width:100%"` on selects in `form-articulo.html:105,120,140,160`, `form-promocion.html:114`, `form-revision.html:122,138`, `form-ajuste.html:49`. **Exception, keep it:** `selector-articulos.html:54`'s `font-size:1rem` is deliberate iOS zoom protection (inputs under 16 px zoom the page on focus) — move it to a `.input-touch` utility rather than deleting it.
5. **`autocomplete="off"` on identity fields that should autofill**: `pages/clientes/form.html:49` (`name` → `name`), `:157` (`address` → `street-address`), `:168` (`zoneCode` → `postal-code`), `:142` (`phoneNumber` → `tel`), `pages/settings/index.html:443` (`correoNotificaciones` → `email`). 27 text inputs carry `autocomplete="off"`; the rest are codes/filters where `off` is correct.
6. **`enterkeyhint` missing on 10 search controls** — with `type="search"` this also gives the phone a “search” key: `pages/compras/ordenes-form.html:130,171`, `pages/facturas-recibidas/tabla.html:51`, `pages/facturas/articulos-picker.html:20`, `pages/facturas/client-picker.html:19`, `pages/recibos/tabla.html:31`, `pages/reportes/index.html:59`, `pages/reportes/precios.html:15`, `pages/reportes/resumen.html:45`, `pages/reportes/ventas-fecha-historica.html:39`. Fix: `enterkeyhint="search"`.
7. **`spellcheck="false"` missing** on 11 search fields (red squiggles under codes): `pages/compras/ordenes-form.html:130,171`, `pages/correos/reportes.html:81`, `pages/correos/templates.html:95`, `pages/devoluciones/index.html:72`, `pages/facturas-recibidas/tabla.html:51`, `pages/recibos/tabla.html:31`, `pages/reportes/index.html:59`, `pages/reportes/precios.html:15`, `pages/reportes/resumen.html:45`, `pages/reportes/ventas-fecha-historica.html:39`.
8. **Read-only values rendered as editable inputs**: `pages/compras/ordenes-form.html:59,68` use `class="input is-static"`, but in Bulma `is-static` is a `.control` modifier — on `.input` it is a no-op, so the field still looks typeable; `pages/cabys/form.html:23` uses `disabled` for the Hacienda-assigned code, which also drops it out of the tab order (a screen-reader user tabbing through the dialog never hears the code); `pages/articulos/form-revision.html:185` and `pages/inventario/form-ajuste.html:25` use bare `readonly`. Fix: put `is-static` on the wrapping `.control`, keep `readonly` (not `disabled`), and add `aria-readonly="true"` where the value is programmatic (the `form-ajuste` readonly-proxy + hidden `articuloId` + `aria-required` pattern at `:25-36` is the model).
9. **Unlabeled checkbox group in the invoice drawer**: `pages/facturas-recibidas/detalle-drawer.html:119-120` — `name="lineasAceptadas"` repeated with only a table column header for context. Fix: `role="group" aria-labelledby="lineas-aceptadas-label"` on the table wrapper + `<span class="is-sr-only" id="lineas-aceptadas-label">Líneas a incluir en la aceptación parcial</span>`.
10. **Repeated identical `aria-label` text on N-row groups**: `pages/facturas/payment-dialog.html:54,62,84` (“Método de pago” ×N, “Monto en colones” ×N). Fix: append the row index or the method label (`aria-label="Monto en colones del método {n}"`).
11. **Stale comments that will mislead the next agent** (cheap, avoids a regression when someone “fixes” the code to match the comment):
    - `pages/cabys/tabla.html:22-24` says “The wrapper KEEPS its id, because that element IS the hx-target of the search input — the swap replaces the whole surface”. The code at `:42-43` now targets `#resultados-{modelo.id}` with `hx-select`. 
    - `pages/facturas/factura-body.html:88-91` still describes the pre-`hx-select` barcode flow, but the code there is correct.

---

## 4. Controls that are already correct — do not “fix” these

These were checked against every rule in the brief and are right; they are the reference implementations for the fixes above.

| Where | What is right |
|---|---|
| `login.html:91,102` | `autocomplete="username"/"current-password"`, `autocapitalize="none"`, `autocorrect="off"`, `spellcheck="false"`, `enterkeyhint="next"/"go"`, `required`, `autofocus`, real `<label for>`. Textbook. |
| `pages/usuarios/form.html:59-67,157-183` | `required`, `maxlength`, `autocomplete`, conditional `aria-invalid` + `aria-describedby`, error `<p id>` with `role="alert"`, `hx-disabled-elt="find button[type='submit']"` (`:50,88,144`), and the `role="group" aria-labelledby="permisos-label"` checkbox group (`:95,190`). The duplicate `usuario-username`/`usuario-email` ids are in **mutually exclusive** `{#if usuario??}` branches (`:62` vs `:157`) — allowed by the brief, not a defect. |
| `pages/articulos/tabla-articulos.html:52-74` | The **canonical** search layout: filter outside the swapped region, `hx-target="#resultados-…"` + `hx-select="#resultados-…"` + `hx-sync="this:replace"`, `type="search"`, `spellcheck="false"`, `enterkeyhint="search"`, aria-label. Same in `tabla-promociones.html:35-49` and `pages/cabys/tabla.html:35-51`. |
| `pages/facturas/factura-body.html:92-129,196-231` | Scanner form: `<label for>`, `autofocus`, `enterkeyhint="go"`, `aria-describedby` to the help line, `hx-disabled-elt`, and the `focusBarcode()` afterSwap refocus + `select()` (so the next scan overwrites the previous code). Do not remove the `select()`. |
| `pages/facturas/factura-body.html:273-314` | The `puntosSeq` sequence guard on the `/puntos-preview` fetch — a correct last-write-wins race guard. This is what `hx-sync` buys the typeaheads declaratively. |
| `pages/facturas/articulos-picker.html:20-31`, `client-picker.html:19-30` | `hx-preserve` + stable id + `hx-sync` + `autocapitalize="off"` + `spellcheck="false"` + `#pos-anuncio` live region for the result count. |
| `pages/facturas-recibidas/index.html:47`, `pages/recibos/index.html:66`, `pages/inventario/index.html:77` | File inputs: `accept=".xml"`, `multiple`, `required`, `aria-describedby`, real label. |
| `pages/devoluciones/index.html:54-66` | Radio group with `role="radiogroup" aria-labelledby` + wrapping `<label class="radio">`. |
| `pages/compras/ordenes-form.html:128,139-146,179-186` | Hidden `articuloCodigo` + visible typeahead + `inputmode="decimal"` + `min`/`step` on the numeric line fields; the repeated `name="articuloCodigo"/"cantidad"/"precioUnitario"` is **parallel-array encoding by design** (one per line) — the duplicate-hidden-name scan flags it, it is not a bug. |
| `pages/inventario/form-ajuste.html:22-36` | Read-only proxy display + `<input type="hidden" id="ajuste-articulo-id" name="articuloId">` + `aria-required` + `aria-describedby`; the `select` with no `name` is intentional (the hidden `tipoMovimiento` at `:67` carries the value). |
| `pages/settings/index.html:903-912` | `select` with no `name` **and** `disabled` — an informational field with no backing setting (`:900`). Named-less on purpose. |
| `pages/reportes/index.html:53-67` | Client-side filter: `role="search"`, `x-model`, `aria-controls="reportes-lista"`, no `name` (nothing is submitted). Do not add `name` or `hx-get`. |
| `pages/reportes/etiquetas.html:27,31` | `id` + label + `min`/`max`, no `name` — read-only client-side state (`:5-7`). |
| `pages/compras/ordenes-articulos.html:17-19` | Suggestion buttons are `type="button"` (so they cannot submit the order), with `min-height:2.25rem` for touch. |
| `pages/facturas/cart-panel.html:105-111,123-129` | The +/− forms: `type="button"`, `hx-include="closest form"`, `hx-disabled-elt="this"`, `aria-label` with the item name. |
| `pages/caja/form-abrir.html:60`, `form-cerrar.html:94` | `hx-disabled-elt="this"` that documents *why* (a double tap used to open two shifts). |
| `pages/perfil/index.html:128-142` | `autocomplete="current-password"` / `"new-password"` on the three password fields. |
| `_kit/filter-bar.html:80-89` | Both slots now render **inside** `<form>` — the form-ownership P0 from round 2 is genuinely fixed; I re-verified all 15 report-page submits are within the include span. |

**Form ownership: clean.** All 15 `type="submit"` controls that the parser reports as “outside a form” are inside a `{#include _kit/filter-bar}` body, i.e. inside the form that component emits (`filter-bar.html:81`). There is no orphan submit button in the app.

**Labels: essentially clean.** 1 control of 397 lacks a name (P1-8). The 1 `for=` that points to a non-existent id (`_kit/filter-bar.html:90`) is inside a `{! !}` documentation comment, not markup.

---

## 5. Top-10 ordered fix list

| # | Fix | Where | Why first |
|---|---|---|---|
| 1 | Clear a dialog body on close (`x-effect="if (!open) $el.replaceChildren()"`) | `_kit/modal.html:66` (shared) | One line removes the duplicate-id class: wrong labels, wrong `aria-describedby`, and **the edit form repainting the create dialog**. Largest blast radius, smallest diff. |
| 2 | `hx-preserve="true"` + `id` on the acceptance checkboxes; `hx-sync` on the line input | `pages/facturas-recibidas/detalle-drawer.html:119,125` | Silent loss of the user's selected lines before a Hacienda submission. |
| 3 | `hx-sync="this:replace"` on the 11 typeaheads (§P1-1) | 10 page-local files | Stale results overwriting newer ones is the remaining half of the reported “typing” bug. |
| 4 | Intercept Enter in the order article typeahead | `pages/compras/ordenes.html:189` (page script) | Enter currently **saves the order** with an unselected line. |
| 5 | `hx-disabled-elt="find button[type='submit']"` on the 6 entity-create forms | `clientes/form.html:31`, `form-departamento.html:23`, `form-familia.html:22`, `form-articulo.html:33`, `form-promocion.html:42`, `ordenes-form.html:45` | Duplicate rows from a double click. |
| 6 | `aria-invalid` + error `<p id>` + `aria-describedby` on the 6 fields in §P1-7 | `cabys/form.html`, `categorias/form-{departamento,familia}.html`, `form-promocion.html`, `loyalty/panel-ajustes.html` | Error text exists but is invisible to assistive tech today. |
| 7 | Name the bulk-selection checkbox + retire the misattached label | `pages/reportes/ventas-fecha-historica.html:37,39,53` | The single unnamed control in the app. |
| 8 | `id` + `hx-preserve` + `inputmode` on the cart quantity input; `hx-sync` on its form | `pages/facturas/cart-panel.html:115-122` | Whole-panel rebuild loses focus and in-flight edits in the busiest screen. |
| 9 | Remove the mixed submit, or the declarative `hx-get` (pick one) | `pages/stockalert-config/index.html:131`, `pages/reportes/margenes.html:69` | Enter currently full-reloads the board. |
| 10 | The mechanical P2 sweep: `type="search"` (12), `inputmode` (10), `hx-disabled-elt` on the remaining 14 forms, `spellcheck`/`enterkeyhint` (21) | see §3 | Cheap, no behaviour risk, closes the long tail. |

---

## 6. Appendix — how this was measured, and the false positives

**Counts by category (measured on the final pass; categories overlap, the same control can appear twice):**

| Category | Instances |
|---|---|
| Controls scanned | 397 (350 `input`, 37 `select`, 10 `textarea`) in 146 templates |
| Controls with a typing/change `hx-trigger` | 32 |
| … without `hx-sync` | 16 (11 typeahead + 5 change-only) |
| Self-swap typing hazards at T0 → open now | 8 → **0** |
| `hx-preserve` without a stable `id` | 0 |
| `hx-get` with no `hx-target` | 0 |
| Controls with no programmatic name | 1 |
| Labels `for=` pointing at a missing id | 0 in markup (1 in a doc comment) |
| Duplicate static ids inside one file | 5 ids, all in mutually exclusive `{#if}` branches (`usuarios/form.html`) → allowed |
| Pages whose dialogs share a body URL (duplicate ids across simultaneously-open dialogs) | 10 |
| Submitted controls with no `name` | 0 — the 16 no-name controls were audited one by one: display/read-only fields, JS state holders, client-side filters, and values carried by a hidden input (§4, §6) |
| Error state not exposed (`is-danger`, no `aria-invalid`) | 6 |
| Field-level error text without an id | 8 |
| Mutating forms with no double-submit guard | 20 (6 creates → P1, 14 idempotent saves → P2) |
| Search fields typed as `text` | 12 |
| `type="number"` without `inputmode` | 10 |
| Touch rows under 44 px (`is-small` controls) | 8 |
| Inline width/font-size on controls | 18 |
| `autocomplete="off"` where a real token applies | 5 |
| Mixed declarative + explicit filter pattern | 2 |
| Orphan submit buttons (outside a form) | 0 |
| Duplicate hidden `name` inside one form | 1 file — `ordenes-form.html` parallel arrays, by design |

**Re-measure:**

```powershell
python C:\Users\alvar\AppData\Local\Temp\opencode\audit_all_inputs.py
```

It only covers the self-swap rule. The other categories need the attribute sweep (`hx-sync`, `name`, label/`for`, `aria-invalid`, duplicate ids per include graph, `hx-disabled-elt`) — the checks are listed above with their exact selectors.

**False positives I deliberately did not report:**

- `type="submit"` “outside a form” in 15 report templates — the form comes from `_kit/filter-bar.html:81`.
- `name="articuloCodigo" / "cantidad" / "precioUnitario"` repeated inside `ordenes-form.html` — parallel arrays, one per order line.
- `name="monto" / "metodoPago"` repeated inside `payment-dialog.html` — same, one per payment row.
- `id="usuario-username"` twice in `usuarios/form.html` — mutually exclusive create/edit branches.
- `select` with no `name` in `form-ajuste.html:49` and `settings/index.html:912` — a hidden field and a disabled informational field carry the value / nothing.
- `input` with no `name` in `form-revision.html:185`, `ordenes-form.html:59,68`, `cabys/form.html:23` — read-only display fields (see §3.8, the *styling* is the P2, not the missing name).
- `_kit/filter-bar.html:90` `for="recibos-q"` — inside a `{! !}` doc comment.
