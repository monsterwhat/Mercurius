# Mercurius UI Kit — Pattern Contract (T16)

**Audience:** every W4 page worker migrating a JSF/PrimeFaces module to Qute + HTMX + Alpine + Bulma.
**Source of truth:** this document. The kit lives in `src/main/resources/templates/_kit/`; the shell (`layout.html`, `fragments/navbar.html`, `fragments/toasts.html`) landed in T11 and MUST NOT be modified by page workers.

Stack pins (from T10 web-bundler, do not add libraries): HTMX **2.0.10**, Alpine.js **3.16.1** (core only — no plugins), Bulma **1.0.4**, Chart.js 4 (charts only, T29).

---

## 1. Kit inventory

| File | Replaces | Purpose |
| --- | --- | --- |
| `_kit/data-table.html` | `p:dataTable` + `p:column` | Sortable, server-paged table; tbody via slot or per-row fragment; built-in pager footer |
| `_kit/pagination.html` | PrimeFaces paginator | Prev/next + numbered links preserving query params |
| `_kit/modal.html` | `p:dialog` | Alpine open/close shell; trigger button `hx-get`s a fragment into the body |
| `_kit/confirm.html` | `p:confirm` / inline `onclick` confirms | Styled dialog hooked into htmx's native `hx-confirm` attribute |
| `_kit/toast-item.html` | `p:growl` | One notification; severity→Bulma color; works server-rendered AND swapped |

## 2. Golden rules (violations break the app)

1. **Root-path prefixing is the kit's job.** Every URL you pass (`baseUrl`, `bodyUrl`) is *context-path-relative* (e.g. `/app/articulos`). Fragments emit `{config:['quarkus.http.root-path']}{baseUrl}` themselves (bracket-quoted form is the documented Qute syntax for dotted config property names). Never pre-prefix.
2. **CSRF is inherited.** `layout.html` sets `<body hx-headers='...'>`; every HTMX request carries the CSRF header automatically. Do NOT add per-element `hx-headers`.
3. **Alpine braces need a space.** Qute parses `{` as an expression start only when an expression character follows it. Inside Alpine attributes ALWAYS write `{ open:false }` / `{ 'is-active': open }` — the space after `{` makes Qute leave it literal (same rule that keeps T11's `<script>` blocks safe: every `{` is followed by whitespace/newline).
4. **Include paths are root-relative.** `{#include _kit/data-table ...}` works from any depth; use `./`/`../` only for caller-relative paths. Dynamic includes use the documented `_id` parameter: `{#include _id=rowFragment item=row /}`.
5. **Reserved query keys:** `page`, `size`, `sort`, `dir` are emitted by the kit itself. They must never appear in the `params` map nor be parsed as filters by resources.
6. **Severity vocabulary** (case-insensitive): `error`→`is-danger`, `warn`/`warning`→`is-warning`, `success`→`is-success`, anything else→`is-info`. Accepts both T11 lowercase tokens and raw FacesMessage-style uppercase from ported services.
7. **Spanish labels** everywhere user-visible (legacy parity); code comments in English like the rest of the codebase.
8. **No business logic in `_kit/`.** Resources compute page windows, totals, permissions; templates render state.
9. **Fragment-aware resources:** endpoints backing tables/dialogs check the `HX-Request` header — render only the fragment when present, the full page otherwise.

## 3. Fragment reference

### 3.1 `_kit/data-table`

Params: `id`*, `baseUrl`* (context-relative), `headers`* (list of Maps exposing `label` + nullable `key`; null key ⇒ non-sortable column; build with `Map.of("label","Nombre","key","nombre")`), `rows`, `rowFragment` (root-relative row template; when absent the `{#rows}` slot is used), `sortKey`, `sortDir` (`asc`|`desc`), `page`, `size`, `total`, `totalPages`, `pages` (server-computed window of page numbers), `params` (Map of preserved query params). (* = required)

**Server contract:** `GET {baseUrl}?page=N&size=S&sort=k&dir=asc[&filters]`. Resource reads those params (defaults `page=1, size=20, sort=null, dir=asc`), computes `totalPages = ceil(total/size)` server-side (Qute has no division), and re-emits filters through `params`.

Slot mode (caller owns iteration + empty state):

    {#include _kit/data-table id="clientes-tabla" baseUrl="/app/clientes"
         headers=columnas sortKey=sortKey sortDir=sortDir
         page=page size=size total=total totalPages=totalPages pages=paginas params=filtros}
      {#rows}
        {#for c in clientes}
        <tr><td>{c.codigo}</td><td>{c.nombre}</td>
            <td><button class="button is-small" hx-get="{config:['quarkus.http.root-path']}/app/clientes/{c.codigo}/dialogo"
                        hx-target="#cliente-dialogo-body">Editar</button></td></tr>
        {/for}
      {/rows}
    {/include}

Row-fragment mode (kit iterates; empty state included automatically):

    {#include _kit/data-table id="articulos-tabla" baseUrl="/app/articulos"
         headers=columnas rows=articulos rowFragment="_kit/rows/articulo"
         sortKey=sortKey sortDir=sortDir page=page size=size total=total
         totalPages=totalPages pages=paginas params=filtros /}

with `templates/_kit/rows/articulo.html` receiving `item` (+ zero-based `rowIndex`):

    <tr><td>{item.codigo}</td><td>{item.nombre}</td><td>{item.stock}</td></tr>

Sort links emitted by the kit: `?sort=<key>&dir=<toggled>&page=N&size=S&<params>` against the same endpoint; active column gets `aria-sort` and an arrow.

### 3.2 `_kit/pagination`

Usually pulled in automatically by data-table's footer. Params: `page`*, `totalPages`*, `pages` (window), `baseUrl`*, `params`. Emits `{root-path}{baseUrl}?page=N[&size=S][&k=v...]` — `page` first so no separator logic is needed. Prev/next render disabled at boundaries. To HTMX-boost it, wrap in a container with `hx-target` on the page (links degrade to normal navigation without JS).

### 3.3 `_kit/modal`

Params: `id`*, `title`, `triggerLabel`, `triggerClass`, `bodyUrl`* (context-relative URL whose fragment fills the body). Slots: `{#trigger}` replaces the default button; `{#footer}` replaces the default "Cerrar".

    {#include _kit/modal id="cliente-dialogo" title="Cliente" triggerLabel="Nuevo cliente"
         bodyUrl="/app/clientes/nuevo"}
      {#footer}
        <button class="button is-primary" type="button"
                hx-post="{config:['quarkus.http.root-path']}/app/clientes"
                hx-target="#cliente-dialogo-body"
                hx-confirm="¿Guardar el cliente?">Guardar</button>
        <button class="button" type="button" @click="open = false">Cerrar</button>
      {/footer}
    {/include}

Behavior: Alpine `x-data="{ open:false }"` on the wrapper; trigger click opens AND `hx-get`s the body; Escape/backdrop/delete close; minimal Tab focus trap (`window.kitTrapTab`, shared with confirm). The swapped body content may itself contain Alpine scopes — the Alpine root lives outside the swap target, so state survives swaps.

### 3.4 `_kit/confirm`

Include **once per page**. After that, ANY element with `hx-confirm="¿texto?"` gets the styled dialog instead of the browser popup — implemented via htmx's documented `htmx:confirm` event (`evt.preventDefault()` then `evt.detail.issueRequest(true)` on accept). Pages that skip this fragment silently fall back to native confirm. Optional params: `id` (default `kit-confirm`), `title`, `message` (fallback text), `confirmLabel`, `cancelLabel`, `danger` (Boolean → red accept button).

### 3.5 `_kit/toast-item`

Params: `severity`, `message`. Server-rendered directly, or appended from any HTMX response via out-of-band swap into the layout's `#toast-container`:

    <div hx-swap-oob="beforeend:#toast-container">
        {#include _kit/toast-item severity=result.severity message=result.message /}
    </div>

(htmx inserts the *content* of an `hx-swap-oob` element using the named swap style.) Its guarded script auto-binds dismiss + 5s auto-dismiss for dynamically added toasts via MutationObserver.

## 4. Census recipe table — legacy PrimeFaces tag → kit recipe

Counts from the migration census (plan ref bg_f906ff3a top-20; tags without a number below were outside the cited top-10 excerpt — consult the census for exact figures).

| Legacy tag (count) | Kit recipe | Server contract / notes |
| --- | --- | --- |
| `p:commandButton` (258) | Navigation → `<a class="button">` or plain form POST. Ajax → `<button>` with `hx-post`/`hx-get` + `hx-target`. Destructive → add `hx-confirm` (with `_kit/confirm` on page). Supervisor-gated → POST to the T13 authorize endpoint first, then act | Fragment swap or `HX-Redirect`; see §5 |
| `p:dataTable` (151) | `_kit/data-table` (+ built-in `_kit/pagination` footer) | Paging/sorting contract §3.1; fragment-only render when `HX-Request` |
| `p:column` | One `headers` entry (`label`,`key`) + cell markup in `{#rows}` slot or `rowFragment` template | Row exposed as `item`, `rowIndex` |
| `p:inputText` (199) | `.field > .control > input.input`, `name=` = query param. Filter-as-you-type variant: `hx-trigger="keyup changed delay:300ms"` targeting the table container id | Value round-trips through `params` |
| `p:growl` (108) | `_kit/toast-item` (+ OOB wrapper for swaps, §3.5) | severity vocabulary §2.6 |
| `p:ajax` (108) | Mapping table §7 | — |
| `p:dialog` (65) | `_kit/modal` | `bodyUrl` returns an HTML fragment; CSRF inherited |
| `p:selectOneMenu` (71) | `.field > .control > .select > select`, `hx-trigger="change"` for reactive filters | Selected value preserved via `params`/`value` attr |
| `p:datePicker` (24) | `<input type="date">` / `type="datetime-local"` (native, no extra lib) | ISO values; format server-side |
| `p:autoComplete` (3) | Search-as-you-type dropdown recipe §8; T21 ships the shared client picker (selector `.js-client-picker`) to reuse globally | `q=` query param; results div positioned absolutely under the input |
| `p:fileUpload` (4) | `<form enctype="multipart/form-data">` with `hx-encoding="multipart/form-data" hx-post="..."` | Multipart JAX-RS endpoint (`@Consumes(MULTIPART_FORM_DATA)`) |
| `p:confirm` behavior | `_kit/confirm` once per page + `hx-confirm` attrs | Question text shown in styled dialog |
| `p:dataExporter` / printPDF flow | Export-button pattern §6 → `POST /api/app/export` (T17) | `dataset`+`type` fields; browser download, NOT hx |
| `p:outputLabel`, `p:outputText`, `p:panelGrid`, `p:selectOneRadio`, `p:selectCheckboxMenu`, `p:password`, `p:inputTextarea` | Direct Bulma equivalents: `label.label`, interpolated text, `.columns/.field` groups, `.radio`, `.checkbox`, `input[type=password].input`, `textarea.textarea` | — |

## 5. Form-post error redisplay pattern

**Pattern A — HTMX fragment redisplay (preferred).** The form targets its own container; validation failure re-renders just the form with errors plus an OOB toast:

    {! pages/clientes.html !}
    <div id="cliente-forma">
      <form hx-post="{config:['quarkus.http.root-path']}/app/clientes"
            hx-target="#cliente-forma" hx-swap="outerHTML">
        <input class="input {#if errorNombre??}is-danger{/if}" name="nombre" value="{cliente.nombre}"/>
        {#if errorNombre??}<p class="help is-danger">{errorNombre}</p>{/if}
        <button class="button is-primary" type="submit">Guardar</button>
      </form>
    </div>

Resource on failure returns (HTTP 200/422, content = fragments):

    {! primary swap target: fresh form !}
    <div id="cliente-forma"> ...form again, errors set... </div>
    {! side effect: toast !}
    <div hx-swap-oob="beforeend:#toast-container">
        {#include _kit/toast-item severity="error" message=errorMessage /}
    </div>

On success either return `HX-Redirect: <page url>` header, or swap the list container and append a `success` OOB toast.

**Pattern B — no-JS full-page redisplay.** Plain `<form method="post" action="{root-path}/app/x">`: on validation failure the resource re-renders the WHOLE page template passing `toasts` + field errors in the data model (HTTP 200). Same philosophy as the navbar's no-JS logout. Use when a module has no table/dialog interactivity worth boosting.

## 6. Export-button pattern (T17 `/api/app/export`)

Deliberately a normal browser navigation (NOT htmx) so `Content-Disposition` triggers a download:

    <form method="post" action="{config:['quarkus.http.root-path']}/api/app/export">
      <input type="hidden" name="dataset" value="articulos"/>
      <input type="hidden" name="type" value="xlsx"/>   <!-- or pdf -->
      <input type="hidden" name="sort" value="{sortKey}"/>
      <input type="hidden" name="dir" value="{sortDir}"/>
      {#for f in filtros.entrySet()}<input type="hidden" name="{f.key}" value="{f.value}"/>{/for}
      <button class="button is-link" type="submit">Exportar Excel</button>
    </form>

T17 contract: `{dataset}-{yyyyMMdd}.xlsx|.pdf`, magic bytes `PK\x03\x04` / `%PDF-`; unauthorized → login redirect.

## 7. `p:ajax` → HTMX mapping

| PrimeFaces | HTMX equivalent |
| --- | --- |
| `event="click"` / action methods | default trigger of buttons/links: `hx-get` / `hx-post` |
| filter inputs (`event="keyup"`) | `hx-trigger="keyup changed delay:300ms"` |
| `event="change"` (selects) | `hx-trigger="change"` |
| `event="blur"` | `hx-trigger="blur changed"` |
| `p:poll` interval | `hx-trigger="every 5s"` (T28 countdown uses this) |
| `update=":form:panel"` | `hx-target="#panel-id"` + `hx-swap="innerHTML"` (stable fragment ids!) |
| `process` / `@form` | put controls inside one `<form>`; from outside use `hx-include="#form-id"` |
| listener bean mutation | resource renders the affected fragment(s); multiple regions via several `hx-swap-oob` blocks |

## 8. AutoComplete mini-recipe (until/unless a shared picker exists)

    <div class="field">
      <input class="input" type="text" name="q" autocomplete="off"
             hx-get="{config:['quarkus.http.root-path']}/app/clientes/buscar"
             hx-trigger="keyup changed delay:300ms"
             hx-target="#resultados-cliente" hx-swap="innerHTML"/>
      <div id="resultados-cliente" class="box is-shadowless" style="position:absolute; z-index:30;"></div>
    </div>

Server returns `<button|li>` suggestions carrying `data-id`; the page wires selection (set hidden input + close list). Reuse T21's `.js-client-picker` instead of re-implementing client search.

## 9. Verification status (T16)

Static verification only per task instructions: LayoutProbeResource untouched, zero Java changes, no runtime probe. See `.omo/evidence/t16/` for the checklist, census coverage table and syntax sanity transcript.

