# Mercurius UI Kit — Pattern Contract

**Status:** W1 (design-system foundation) landed. W2–W6 pending — see §12.
**Audience:** every page worker migrating a JSF/PrimeFaces module to Qute + HTMX + Alpine + Bulma, and every worker landing one of the W1 primitives on a page.
**Source of truth:** this document. Markup lives in `src/main/resources/templates/_kit/`; tokens and component CSS live in `src/main/resources/web/mercurius.scss`; the icon set lives in `src/main/resources/web/icons.js`; shared JS behaviour lives in `src/main/resources/web/index.js`.

Stack pins — **do not add libraries.** HTMX **2.0.10**, Alpine.js **3.16.1** (core only, no plugins), Bulma **1.0.4**, Chart.js **4.4.8**.

---

## 1. Layer order

```
Bulma  →  Mercurius tokens  →  Mercurius components  →  page exceptions
```

`web/index.scss` imports Bulma, then `mercurius.scss`, then the ported legacy layers. Components must be composed from `_kit/` before a page adds any CSS of its own. A page that needs a new visual pattern opens a design review instead of writing a new rule.

### 1.1 W1 scope rule (important)

W1 was **purely additive**. `mercurius.scss` deliberately does **not** redefine any selector owned by the ported legacy layers. These still render exactly as they did and are **not** to be used by new work:

`.stat-card` · `.stats-grid` · `.actions-bar` · `.actions-group` · `.filters-section` · `.filters-row` · `.filter-group` · `.tab-content-card` · `.nav-cards` · `.nav-card` · `.grid-view` · `.articulo-card` · `.table-actions` · `.table-action-btn` · `.action-btn` · `.button-stack` · `.edit-btn` / `.edit-text` · `.empty-state` · `.loading-overlay` · `.side-panel` · the twelve module gradient banners (`.articulos-header`, `.categorias-header`, `.inventario-header`, `.cabys-header`, `.recibos-header`, `.correos-header`, `.usuarios-header`, `.registros-header`, `.backups-header`, `.clientes-header`, `.tributacion-header`, plus `.caja-header` which lives in an inline `<style>` on the caja page) · the `.ui-*` PrimeFaces leftovers (`ui-tabs`, `ui-datatable`, `ui-dialog`, `ui-datepicker`, `ui-tooltip`, `timeline.css` / vis-timeline).

`.status-chip` is the one exception: its base class already existed, so W1 only **adds** the semantic modifiers `.is-success` / `.is-warning` / `.is-info` / `.is-danger` / `.is-neutral` alongside the legacy `.status-processed` / `.status-unprocessed` / `.status-active` / `.status-inactive` aliases. W6 drops the aliases.

## 2. Golden rules

Violating any of these breaks the app.

1. **Context-relative URLs — the kit owns the root path.** Every `baseUrl` / `bodyUrl` you pass is context-relative (`/app/articulos`). Callers must **never** pre-prefix the deployment root, and must **never** hard-code it. The canonical chain is:

   ```
   application.properties (quarkus.http.root-path)
       ↓
   {config:['quarkus.http.root-path']}
       ↓
   kit-generated URLs
   ```

   As of **W2** this holds across all 137 templates: 301 literal occurrences in 97 files were replaced, and `RootPathNormalizationTest` fails the build if a single URL attribute reintroduces one.

   > **The claim that started this.** Every template used to inline the literal root because of one stale comment in `pages/settings/index.html`, asserting the property is *"absent from the runtime config namespace outside dev mode, so `{config:}` expressions risk TemplateException on packaged runs"*, plus a matching note in `_kit/data-table.html`. **Both were false.** The property is declared in `application.properties` and resolves at render time, and `pages/recibos/tabla.html` + `pages/recibos/detalle.html` had been using the `{config:}` form all along without incident. Do not reintroduce the comment; if a `{config:}` expression ever does fail, fix the property, not the convention.

   **Known follow-up (not W2):** several Java classes still inline the root for redirect targets and path checks — `FallbackResource`, `NotFoundExceptionMapper`, `RootRedirectResource`, `RootLoginRedirectRoute`, `PublicApiJwtFilter`, `PDFGenerator`. `OrdenCompraResource` already does it correctly with `@ConfigProperty`. That surface is out of W2's scope because the README flags the auth/redirect area as the fragile part of the suite, and it needs its own wave.
2. **CSRF is inherited.** `layout.html` sets `<body hx-headers='…'>`; every HTMX request carries the token automatically. Do **not** add per-element `hx-headers`. Plain (non-HTMX) POST forms carry `<input type="hidden" name="csrf-token" value="{inject:csrf.token}"/>`.
3. **Alpine braces need a space.** Qute treats `{` as an expression start when an expression character follows. Inside Alpine attributes always write `{ open:false }` / `{ 'is-active': open }`. Same rule for every `{` in a `<script>` block: whitespace or newline immediately after.
4. **Include paths are root-relative.** `{#include _kit/data-table …}` works at any depth. Dynamic includes use `_id`: `{#include _id=rowFragment item=row /}`.
5. **Reserved query keys:** `page`, `size`, `sort`, `dir`. Emitted by the kit; never put them in a `params` map and never parse them as filters.
6. **Severity vocabulary** (case-insensitive): `error` → `is-danger`, `warn`/`warning` → `is-warning`, `success` → `is-success`, anything else → `is-info`. Accepts lowercase tokens and raw FacesMessage-style uppercase from ported services.
7. **Spanish labels** everywhere user-visible. Code comments in English, matching the rest of the codebase.
8. **No business logic in `_kit/`.** Resources compute page windows, totals, permissions. Templates render state.
9. **Fragment-aware resources.** Endpoints backing tables and dialogs check `HX-Request`: render only the fragment when present, the full page otherwise.
10. **Charts are Chart.js only.** `window.MercuriusCharts.create(id, config)` applies the shared defaults. Never hand-draw on a canvas, never call `toLocaleString()` in a page script.
11. **Icons come from `_kit/icon`.** No FontAwesome, no emoji, no numeric HTML entities as glyphs. See §9.
12. **One component per concern.** Page headers, metric cards, view tabs, filter bars, action bars, surfaces, tables, modals, toasts and charts each have exactly one implementation. Do not add a second.

## 3. Kit inventory

### 3.0 Pre-existing (contract unchanged in W1)

| File | Replaces | Purpose |
| --- | --- | --- |
| `_kit/data-table.html` | `p:dataTable` + `p:column` | Sortable, server-paged table; tbody via slot; built-in pager footer |
| `_kit/pagination.html` | PrimeFaces paginator | Prev/next + numbered links preserving query params |
| `_kit/modal.html` | `p:dialog` | Alpine shell; trigger `hx-get`s a fragment into the body |
| `_kit/confirm.html` | `p:confirm` / `onclick` confirms | Styled dialog bound to htmx's `hx-confirm` |
| `_kit/toast-item.html` | `p:growl` | One notification; severity → colour; server-rendered or OOB-swapped |
| `_kit/toast-container.html` | *(new in W1)* | The single toast mount point; extracted verbatim from `layout.html` |

### 3.1 W1 primitives (new; not yet used by any page)

| File | Replaces | Purpose |
| --- | --- | --- |
| `_kit/icon.html` | `fas fa-*`, `&#9998;`, emoji | One inline-SVG icon from `web/icons.js` |
| `_kit/page-header.html` | 12 gradient banners, `.header-bar`, bare `<h1 class="title is-3">` | One page header with an optional semantic accent |
| `_kit/metric.html` | 3 stat-card markups | One metric card: passive, interactive, or link |
| `_kit/view-tabs.html` | `x-show` bars, `.nav-card`, `.tabs.is-boxed`, `?vista=` | One visual tab system; server- or Alpine-driven |
| `_kit/surface.html` | ad-hoc `.box` / `.card` / `.message` panels | One panel with header / body / footer |
| `_kit/filter-bar.html` | `.filters-section`, bare `.box` filter forms | One filter surface with an auto-fit grid |
| `_kit/action-bar.html` | `.actions-bar`, `div.level` action rows, total chips | One result-count + actions strip |
| `_kit/chart-card.html` | 4 hand-rolled chart containers | One chart frame with a height token |

W1 changed **no** existing public contract. `layout.html` now calls `_kit/toast-container.html` with byte-identical markup.

---

## 4. `_kit/data-table` — Purpose

Renders a sortable, server-paginated dataset. The single replacement for `p:dataTable` and for the ~14 dataset tables currently hand-written with `<table class="table is-striped is-hoverable is-fullwidth">`.

### Markup / API

`#id` > `.table-container` > `table.table.is-striped.is-hoverable.is-fullwidth`, with `thead` (sortable headers carry `aria-sort` + `▲/▼`), `tbody` (slot mode or automatic empty state), and an optional `tfoot` holding the count + pager.

### Parameters

Required: `id` (DOM id of the wrapper), `baseUrl` (context-relative endpoint), `headers` (List of Maps, each with `label` and a nullable `key`; a null/absent `key` means non-sortable — build with `Map.of("label","Nombre","key","nombre")`).

Optional: `page`, `size`, `total`, `totalPages`, `pages` (server-computed window of page numbers), `params` (preserved query params, reserved keys excluded), `sortKey`, `sortDir`.

> **`rowFragment` is not part of this contract.** The previous revision documented an optional row-fragment mode pointing at `_kit/rows/<name>.html`. It had zero implementations and zero callers, and the `_kit/rows/` directory does not exist. W1 strikes it. If W3 needs it, it returns *with* an implementation and a test — it is not silently preserved.

### Slots

`{#rows}` — caller owns iteration **and** the empty state. Without it, the kit renders a single `Sin registros` row spanning all columns.

### Server contract

```
GET {baseUrl}?page=N&size=S&sort=k&dir=asc[&filters]
```

Defaults `page=1, size=20, sort=null, dir=asc`. The resource computes `totalPages = ceil(total/size)` server-side (Qute has no division) and re-emits filters through `params`. Sort links target the same endpoint and toggle `asc`/`desc`.

### Accessibility

Sortable headers emit `aria-sort="ascending"|"descending"` on the active column only; the arrow is `aria-hidden`. Pager current page carries `aria-current="page"`. Sort links carry `title="Ordenar por {label}"`. Row-action buttons must carry their own `aria-label` (they are icon-only — see §9).

### HTMX

Links are real `href`s, so the table works with JS off. To swap instead of navigate, the page's own filter controls target the wrapper id with `hx-swap="outerHTML"`; the fragment-aware resource returns only the table under `HX-Request`.

### Alpine / Responsive

None required. Below 768px the container scrolls horizontally; do not compress columns. For dense tables prefer a server-rendered mobile row representation over shrinking text.

### Example

```html
{#include _kit/data-table id="clientes-tabla" baseUrl="/app/clientes"
     headers=columnas sortKey=sortKey sortDir=sortDir
     page=page size=size total=total totalPages=totalPages
     pages=paginas params=filtros /}
  {#rows}
    {#for c in clientes}
    <tr>
      <td>{c.codigo}</td>
      <td>{c.nombre}</td>
      <td>
        <button class="button is-small" aria-label="Editar cliente"
                hx-get="/Mercurius/api/app/clientes/formularios/{c.codigo}"
                hx-target="#editar-cliente-body" hx-swap="innerHTML">
          {#include _kit/icon name="edit" /}
        </button>
      </td>
    </tr>
    {/for}
  {/rows}
{/include}
```

---

## 5. `_kit/modal` — Purpose

One dialog shell. Replaces `p:dialog` and every hand-copied Bulma `modal-card` block (the POS supervisor-authorization dialog, the loyalty history drawer, the reportes/loyalty history modal).

### Markup / API

`.kit-modal-root` (`x-data="{ open:false }"`) wrapping a trigger `<button>` and `.modal#id` (`:class="{ 'is-active': open }"`) containing `.modal-card-head` / `.modal-card-body#id-body` / `.modal-card-foot`.

### Parameters

Required: `id`, `bodyUrl` (context-relative; the response fragment fills the body).

Optional: `title`, `triggerLabel` (default `Abrir`), `triggerClass` (default `is-link`; use `is-hidden` for dialogs opened by row buttons via `data-kit-open`).

### Slots

`{#trigger}` replaces the default button entirely — keep the `@click` / `hx-get` / `hx-target` trio consistent. `{#footer}` replaces the default `Cerrar`; page buttons carry their own `hx-post` + `hx-confirm`.

### HTMX

The trigger both opens the dialog and issues `hx-get` into `#id-body` (`hx-swap="innerHTML"`). CSRF is inherited. Dialogs opened by a row or menu item set `triggerClass="is-hidden"` and are driven by `data-kit-open="<id>"` on the opener — see §8.

### Alpine

State is the `open` boolean on `.kit-modal-root`. Because the Alpine root sits **outside** the swap target, Alpine scopes inside a swapped body survive the swap. Always write `{ open:false }` with the leading space (rule 3).

### Accessibility

`role="dialog"`, `aria-modal="true"`, `aria-labelledby="{id}-title"`, `aria-haspopup="dialog"` on every trigger, a `aria-label="Cerrar"` delete button, Escape-to-close via `@keydown.escape.window`, backdrop click to close, and the shared `window.kitTrapTab` focus trap on Tab/Shift+Tab.

### Responsive

`max-width: calc(100vw - 1rem)` at 480px with reduced padding.

### Example

```html
{#include _kit/modal id="editar-cliente" title="Editar Cliente"
     triggerClass="is-hidden"
     bodyUrl="/api/app/clientes/formularios/nueva" /}
  {#footer}
    <button class="button is-primary" type="button"
            hx-post="/Mercurius/api/app/clientes"
            hx-target="#editar-cliente-body"
            hx-confirm="&#191;Guardar el cliente?">Guardar</button>
    <button class="button" type="button" @click="open = false">Cerrar</button>
  {/footer}
{/include}
```

---

## 6. `_kit/confirm` — Purpose

Styled replacement for the browser's native `window.confirm`, wired to htmx's documented `htmx:confirm` hook. Include **once per page**; after that any element with `hx-confirm` gets it for free. A page that omits it degrades silently to the native popup.

### Parameters

All optional: `id` (default `kit-confirm`), `title` (default `Confirmar accion`), `message` (fallback body text; normally the question comes from the triggering element), `confirmLabel` (`Confirmar`), `cancelLabel` (`Cancelar`), `danger` (Boolean → red accept button).

### Slots

None.

### HTMX

Installs a `document.body` listener for `htmx:confirm`, calls `evt.preventDefault()`, then `evt.detail.issueRequest(true)` on accept. Escape and backdrop cancel.

### Alpine

None — plain DOM.

### Accessibility

`role="alertdialog"`, `aria-modal="true"`, `aria-labelledby`, `aria-describedby`, focus moved to the accept button on open, `window.kitTrapTab` focus trap, Escape closes. The install is guarded by `window.kitConfirmReady`, so double-inclusion is harmless.

### Responsive

Same modal treatment as §5.

### Example

```html
{#include _kit/confirm id="caja-confirmar" title="Cerrar Sesion de Caja" danger=true /}

<button class="button is-danger" type="submit"
        hx-post="/Mercurius/api/app/caja/close/form"
        hx-confirm="&#191;Cerrar la sesi&oacute;n de caja?">Cerrar Sesi&oacute;n</button>
```

---

## 7. Toasts — Purpose

Replace `p:growl`. Two pieces: the mount point and the item.

### `_kit/toast-container` — Markup

`#toast-container`, fixed top-right, `width: min(22rem, calc(100vw - 2rem))`, `z-index: 1000`, `aria-live="polite"`, `aria-atomic="false"`. Renders `fragments/toasts.html` only when the page supplies a `toasts` data model (safe expression `toasts??`).

**Parameters:** none. **Slots:** none.

There is exactly one container per document. `pages/facturas/factura-standalone.html` carries a second hand-copied instance because it does not include `layout.html`; W6 folds it onto this include. Do not add a third.

### `_kit/toast-item` — Parameters

`severity`, `message`. Rendered directly, or appended from any HTMX response:

```html
<div hx-swap-oob="beforeend:#toast-container">
  {#include _kit/toast-item severity=result.severity message=result.message /}
</div>
```

htmx inserts the *content* of an `hx-swap-oob` element using the named swap style, so `beforeend` appends.

### Accessibility

Container `aria-live="polite"`; each item `role="alert"` with a `.delete` button labelled `aria-label="Cerrar notificacion"`. Auto-dismiss after 5s. A `MutationObserver` (in `web/icons.js`'s hydration path and the item's own guarded script) binds dynamically swapped toasts.

> Keep toasts one at a time. `role="alert"` is assertive; a burst of them interrupts whatever the user is doing.

### Responsive

`width: min(22rem, calc(100vw - 2rem))` already clamps to the viewport.

---

## 8. Shared JS behaviour — `web/index.js`

Installed once at bundle start, guarded by window flags. All four are **additive**: three need no markup change until W5; `kitPopup()` is opt-in and the navbar already uses it.

### 8.1 `kitBridge()` — `data-kit-open` / `data-kit-close`

One delegated `document.body` click listener that flips the target modal's Alpine `open` state. At W0 this exact listener was copy-pasted into **16 script blocks across 15 templates**; W6 deletes the copies. Markup is unchanged, so anything already carrying `data-kit-open="<modal-id>"` works today.

### 8.2 `kitUploadProgress()` — upload progress

htmx 2 emits `htmx:xhr:progress` natively; there is **no** `hx-upload-progress` attribute. This helper is only the shared part. Opt in per form:

```html
<form data-upload-progress="#inventario-progreso"
      hx-post="/Mercurius/api/app/inventario/upload"
      hx-encoding="multipart/form-data"
      hx-target="#inventario-upload-resultado" hx-swap="innerHTML">
  <progress id="inventario-progreso" class="progress is-primary" max="100" value="0"></progress>
  <button type="submit" class="button is-primary">Procesar Facturas</button>
</form>
```

While the upload runs the form's submit buttons get `is-loading` and `disabled`; both are reverted on `htmx:afterRequest`, `responseError`, `sendError` and `timeout`. W5 migrates Inventario — the only page with a `<progress>` and no listener. Recibos and Facturas recibidas already have working inline copies.

### 8.3 `kitPolling()` — polling lifecycle

Opt in on any polling container:

```html
<div id="inventario-badges"
     hx-get="/Mercurius/api/app/inventario/badges"
     hx-trigger="every 30s" hx-swap="innerHTML"
     data-poll data-poll-age="#inventario-edad"> … </div>
<span id="inventario-edad" class="action-count"></span>
```

Behaviour: stamps `data-poll-last` on every successful swap; repaints the age label once per second (`Actualizado ahora` / `Actualizado hace 12 s`); and on `visibilitychange` → visible, issues an immediate catch-up refresh if the interval has already elapsed. htmx still owns the interval and still tears it down in `cleanUpElement` when the element is removed — this helper adds visibility handling and the age label, nothing more.

**5-second polling is only permitted where operationally necessary** (the Tributacion countdown). 30s for slower state such as inventory badges. A hidden tab must not hammer a Hacienda-facing endpoint.

### 8.4 `kitPopup()` — named-window launcher

Opt in on any link that must open a **named popup window** instead of a tab:

```html
<a class="button is-dark"
   href="/Mercurius/app/pos/standalone"
   target="_blank"
   rel="noopener"
   data-kit-popup="nuevaFactura"
   data-kit-popup-features="popup=yes,width=1280,height=800,left=120,top=80"
   aria-haspopup="dialog">Nueva Factura</a>
```

`data-kit-popup` is the window name (reused, so a second activation raises the window already open instead of spawning another). `data-kit-popup-features` is the verbatim `window.open` feature string. The **URL comes from the link's own `href`** — never from a second attribute — so the deployment root is resolved in exactly one place (`{config:['quarkus.http.root-path']}`, W2 in §12) and the launcher cannot get a root wrong.

| Activation | Result |
| --- | --- |
| Plain left click | `window.open` in the named window with the requested geometry; default prevented, so **this tab does not navigate** |
| Keyboard (`Enter` on the focused link) | Same as a left click — the browser dispatches the same primary click, carrying the user activation a popup blocker needs |
| Ctrl/Cmd/Shift+click, middle-click | Browser default. The user asked for *their* tab/window, so the helper does not intercept and this page is never navigated away |
| Popup blocked (`window.open` → `null`) | Default deliberately **not** prevented: the anchor follows its own `href`, and `rel="noopener"` makes that a **new tab**, so the page the user was working in is not replaced. With JavaScript off this is the only path, and it is the same one |

Keep `target="_blank"`. It is what makes the blocked-popup fallback land in a new tab instead of this one. Keep `rel="noopener"` too: it severs `window.opener` (no reverse tabnabbing) for that fallback tab. Keep the real `href` too: it is the no-JS path, the blocked-popup fallback, and what modified-clicks follow. Do not add `role="button"` — the control is genuinely a link, and that is what lets AT and the browser offer "open in new tab".

Replaces the per-page `onclick="window.open(...); return false;"` the POS launcher used to carry. That form ran on every activation, so a Ctrl+click was hijacked into a popup, and `return false` made a blocked popup a dead button.

---

## 9. `_kit/icon` — Purpose

The only way to render an icon. Source of truth is `web/icons.js` (name → inline SVG, 24×24, `stroke="currentColor"`). It replaces three systems: `<i class="fas fa-*">` markup that **never rendered** because FontAwesome was never a declared dependency; numeric HTML entities used as glyphs (`&#9998;`, `&#9211;`, `&#8635;`, `&#10003;`…); and raw emoji whose glyph varied per OS.

### Parameters

`name` **required** — a key in `PATHS`. `label` optional — accessible name for an icon that stands alone. `size` optional — `small` | `medium` | `large`; default inherits `1.25em` so the icon tracks the control's font size.

### Slots

None.

### How it renders

`_kit/icon` contains **no path data**. It emits a marker and the bundle hydrates it:

```html
<span class="mi-icon" data-icon="edit" aria-hidden="true"></span>
```

* zero HTTP requests — the SVG ships inside the existing `app-*.js`;
* one canonical definition per icon;
* htmx-swapped icons are covered by a `MutationObserver`;
* an unknown key renders nothing, sets `data-icon-missing`, and logs **one** console warning listing the valid keys, so a typo surfaces immediately instead of leaving an invisible gap.

Because a Qute template cannot read a JavaScript map at render time, icons appear once the bundle executes. That is why:

**Every icon-only button carries its own `aria-label`.** The icon is decorative and gets `aria-hidden="true"`; the accessible name lives on the button.

### Rules

* **Icons are not labels.** For anything outside a dense table, prefer text + icon.
* Valid keys: `plus minus check close edit eye trash power arrow-right arrow-left refresh search filter download upload print send user lock money cart barcode package file chart gauge clock alert info`.
* Adding an icon = one entry in `PATHS`. Nothing else.

```html
<!-- icon-only, dense table -->
<button class="button is-small" aria-label="Editar articulo"
        hx-get="/Mercurius/api/app/articulos/formularios/articulos/{codigo}"
        hx-target="#editar-articulo-body" hx-swap="innerHTML">
  {#include _kit/icon name="edit" /}
</button>

<!-- text + icon, preferred everywhere else -->
<button class="button is-primary">
  {#include _kit/icon name="plus" /}
  <span>Crear Art&iacute;culo</span>
</button>

<!-- standalone, meaning carried by the icon -->
<span class="icon is-large has-text-link">
  {#include _kit/icon name="gauge" label="Dashboard" size="large" /}
</span>
```

---

## 10. W1 primitives — contract summary

All eight are documented in full at the top of each template. Common rules:

* **Parameters:** `{@java.lang.String x}` declares a **required** param (Qute fails rendering if a caller omits it). Optional params are left undeclared and guarded with `??`.
* **Slots:** `{#insert name}default{/insert}`.
* **Accessibility:** the components already carry the required roles, `aria-*` and focus behaviour. Do not override them from a page.
* **Responsive:** all eight are pure CSS with breakpoints at 768px and 480px. **No page may add a media query for them.**

| Component | Required | Optional | Slots |
| --- | --- | --- | --- |
| `page-header` | `title` | `eyebrow`, `subtitle`, `variant` (`compact`), `accent` (6 Bulma semantics) | `{#actions}`, `{#metrics}` |
| `metric` | `label`, `value` | `detail`, `href`, `interactive`, `active`, `id` | — |
| `view-tabs` | `ariaLabel` | `tabs` (List of Map: `key`,`label`,`href?`,`count?`,`active?`), `id` | `{#tabs}` (slot mode) |
| `surface` | — | `title`, `subtitle`, `flush`, `id` | `{#actions}`, `{#body}`, `{#footer}` |
| `filter-bar` | — | `id`, `method`, `action`, `id_field` | default (grid), `{#actions}` |
| `action-bar` | — | `count`, `countLabel`, `id` | `{#left}`, `{#actions}` |
| `chart-card` | `title` | `subtitle`, `size` (`short`/`tall`), `canvasId`, `height` | `{#actions}`, `{#body}` |
| `icon` | `name` | `label`, `size` | — |

### Notes that are easy to get wrong

* **`.metric-value` is not a Bulma `.title`.** It carries its own font-size token so it stays distinct from `.page-title` / `.surface-title`. Do not add `title is-3`.
* **`view-tabs` is navigation, not a widget.** It renders `<nav aria-label>` and anchors carry `aria-current="page"`. Do **not** add `role="tablist"` / `role="tab"` — there are no tabpanels and the content is server-rendered, so the ARIA tab pattern would misrepresent it. If a future surface genuinely swaps panels in place with keyboard support, use the full pattern (`role="tablist"`, `role="tab"` + `aria-selected`, `role="tabpanel"`, roving tabindex) and document it as an exception.
* **`page-header` renders exactly one `<h1>`.** A page must not add another.
* **`chart-card` height** comes from the `--chart-height` token via `size`. Prefer `size` over the `height` escape hatch.
* **A11y is not optional on charts.** A canvas is opaque to screen readers; `chart-card` emits `aria-label` plus a text fallback, and any chart carrying information not shown elsewhere must also ship a table.

### Charts

Chart.js is the only implementation. The bundle publishes the shared factory with defaults already applied:

```js
const c = MercuriusCharts.create('grafica-ventas-hora', {
    type: 'bar',
    labels: hourly.labels,
    datasets: [{ label: 'Ventas (colones)', data: hourly.ventas }]
});
MercuriusCharts.number(n); MercuriusCharts.currency(n); MercuriusCharts.date(v);
```

Pages supply labels, datasets, type and an optional `options` override. They must **not** redefine fonts, grid colours, tooltip appearance or locale. `responsive: true` and `maintainAspectRatio: false` are global.

---

## 11. Form-post error redisplay

**Pattern A — HTMX fragment redisplay (preferred).** The form targets its own container; on failure the resource re-renders just the form with errors plus an out-of-band toast:

```html
<div id="cliente-forma">
  <form hx-post="/Mercurius/api/app/clientes"
        hx-target="#cliente-forma" hx-swap="outerHTML">
    <input class="input {#if errorNombre??}is-danger{/if}" name="nombre" value="{cliente.nombre}"/>
    {#if errorNombre??}<p class="help is-danger">{errorNombre}</p>{/if}
    <button class="button is-primary" type="submit">Guardar</button>
  </form>
</div>
```

The failure response carries **two** top-level fragments: the re-rendered form (primary swap target) and the toast.

```html
<div id="cliente-forma"> …form again, errors set… </div>
<div hx-swap-oob="beforeend:#toast-container">
  {#include _kit/toast-item severity="error" message=errorMessage /}
</div>
```

On success either return `HX-Redirect: <url>`, or swap the list container and append a `success` toast.

**Pattern B — no-JS full-page redisplay.** Plain `<form method="post" action="…">`; on failure re-render the whole page passing `toasts` + field errors. Same philosophy as the navbar's no-JS logout. Use only where a module has no table/dialog interactivity worth boosting.

**Exports** are deliberately a normal browser navigation, never HTMX, so `Content-Disposition` triggers a download:

```html
<form method="post" action="/Mercurius/api/app/export">
  <input type="hidden" name="csrf-token" value="{inject:csrf.token}"/>
  <input type="hidden" name="dataset" value="articulos"/>
  <input type="hidden" name="type" value="xlsx"/>
  {#for f in filtros.entrySet()}<input type="hidden" name="{f.key}" value="{f.value}"/>{/for}
  <button class="button is-success" type="submit">Exportar Excel</button>
</form>
```

Contract: `{dataset}-{yyyyMMdd}.xlsx|.pdf`, magic bytes `PK\x03\x04` / `%PDF-`; unauthorized → login redirect.

## 11.1 `p:ajax` → HTMX

| PrimeFaces | HTMX |
| --- | --- |
| `event="click"` / action methods | default trigger of buttons/links: `hx-get` / `hx-post` |
| filter inputs (`event="keyup"`) | `hx-trigger="keyup changed delay:300ms"` |
| `event="change"` (selects) | `hx-trigger="change"` |
| `event="blur"` | `hx-trigger="blur changed"` |
| `p:poll interval=5` | `hx-trigger="every 5s"` (+ `data-poll` — see §8.3) |
| `update=":form:panel"` | `hx-target="#panel-id"` + `hx-swap="innerHTML"` (stable fragment ids) |
| `process` / `@form` | one `<form>`; from outside use `hx-include="#form-id"` |
| listener bean mutation | render the affected fragment(s); several regions via several `hx-swap-oob` blocks |

## 11.2 Census recipe

| Legacy tag | Recipe | Notes |
| --- | --- | --- |
| `p:commandButton` (258) | Navigation → `<a class="button">` or plain form POST. Ajax → `<button>` with `hx-post`/`hx-get` + target. Destructive → add `hx-confirm`. Supervisor-gated → POST to the authorize endpoint first, then act | §11 |
| `p:dataTable` (151) | `_kit/data-table` + built-in pager footer | §4; fragment-only under `HX-Request` |
| `p:column` | one `headers` entry (`label`,`key`) + cell markup in `{#rows}` | — |
| `p:inputText` (199) | `.field > .control > input.input`, `name=` = query param; filter-as-you-type via `hx-trigger="keyup changed delay:300ms"` | value round-trips through `params` |
| `p:growl` (108) | `_kit/toast-item` + OOB wrapper | §7 |
| `p:ajax` (108) | §11.1 | — |
| `p:dialog` (65) | `_kit/modal` | §5; `bodyUrl` returns a fragment; CSRF inherited |
| `p:selectOneMenu` (71) | `.field > .control > .select > select`, `hx-trigger="change"` | selected value preserved via `params` |
| `p:datePicker` (24) | `<input type="date">` / `datetime-local` (native, no extra lib) | ISO values; format server-side |
| `p:autoComplete` (3) | `.js-client-picker` typeahead; search-as-you-type + absolutely positioned results | `q=` query param |
| `p:fileUpload` (4) | `<form hx-encoding="multipart/form-data" hx-post="…">` + `kitUploadProgress()` | multipart endpoint |
| `p:confirm` behaviour | `_kit/confirm` once per page + `hx-confirm` | §6 |
| `p:dataExporter` / printPDF | Export-button pattern → `POST /api/app/export` | §11 |
| `p:outputLabel`, `p:outputText`, `p:panelGrid`, `p:selectOneRadio`, `p:selectCheckboxMenu`, `p:password`, `p:inputTextarea` | Bulma equivalents: `label.label`, interpolated text, `.columns`/`.field`, `.radio`, `.checkbox`, `input[type=password].input`, `textarea.textarea` | — |

---

## 12. Migration status

| Wave | Scope | State |
| --- | --- | --- |
| **W1** | Tokens, `PageHeader`, `MetricCard`, `ViewTabs`, `Surface`, `FilterBar`, `ActionBar`, `ChartCard`, `Icon`, `ToastContainer`, `kitBridge`, `kitUploadProgress`, `kitPolling`, shared Chart.js config, this document. **Additive only.** | ✅ landed |
| **W2** | Root-path normalization — **done**. The real count was **301 literal occurrences across 97 files**, not the ~60 first estimated. All replaced with `{config:['quarkus.http.root-path']}`; zero literals remain in any template URL attribute. The two stale comments that caused the hardcoding (in `pages/settings/index.html` and `_kit/data-table.html`) were rewritten to state the truth and to warn against reintroducing the claim. Also hand-corrected the kit prose in `_kit/pagination.html` and `_kit/modal.html`, the `fragments/navbar.html` link note, and the `login.html` "hardcoded for determinism" note (the form action is relative and always was). `RootPathNormalizationTest` guards it two ways: a source scan of all templates, and a render assertion on `_kit/data-table` + `_kit/pagination` proving the root resolves exactly once and the expression never leaks into markup. Java-side redirect literals are a documented follow-up, not W2. | ✅ landed |
| **W3** | Dataset tables → `_kit/data-table`. ~14 real migrations (estacionalidad, margenes, loyalty, registros/log, tipo-cambio/historial, the POS pickers, the detail datasets). The other ~20 hand-written tables are metadata or totals and stay as-is per rule "a small static information grid is not a dataset". | ⬜ pending |
| **W4** | Charts. Move `reportes/tendencias`, `reportes/rendimiento`, `reportes/pronosticos` from hand-drawn canvas to Chart.js; delete the custom rendering. | ⬜ pending |
| **W5** | Page migration, by archetype: list → tabbed board → reports → settings → POS → hub. Adopt the W1 primitives; remove per-page `<style>`/`<script>` duplicates. | ⬜ pending |
| **W6** | Cleanup **after** a repo-wide grep proves zero references: FontAwesome references, emoji/Unicode action glyphs, the 16 `data-kit-open` bridge script copies, the PrimeFaces `ui-*` and `timeline.css` layers, the twelve module gradient banners and other legacy selectors, the second `#toast-container` in `factura-standalone.html`, `probe.html`, and the two hand-rolled `htmx:afterRequest` toast renderers (`settings/index.html`, `tipo-cambio/fragment.html`). Then grep for `fas fa-`, `ui-`, `vis-timeline`, `config:['quarkus.http.root-path']`, `.nav-card`, `.stat-card`, `*-header`, and require them to be **gone from the source**, not merely unused. | ⬜ pending |

**Definition of done for the redesign:** redesign the 10–15 primitives, then migrate pages onto them. That yields one visual language while preserving the existing Qute/HTMX architecture, server-side pagination, CSRF model, role gating, routes, business logic and `_kit` contracts.
