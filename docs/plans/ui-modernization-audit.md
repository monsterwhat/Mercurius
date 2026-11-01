# UI Modernization Audit — Phase 1 (read-only)

Scope: measure current state before any UI change. Stack unchanged (Quarkus + Qute + HTMX + Alpine + Bulma). Design system: `app/src/main/resources/web/mercurius.scss`, `templates/_kit/`, `docs/ui-kit.md`.

**Decisions applied to this audit** (see `ui-modernization-spec.md` §0): UI-1 keeps the current font stack, so the "Inter not loaded" gap below is **out of scope by decision**, not a pending task. UI-7 fixes the sidebar at 160 px with short labels + tooltips. UI-8 drops "Impresoras" (no backend): the card renders disabled.

## 1. Page inventory

Rows = functional module. Routes are live `/app/*` (or `/api/app/*` full-page). Partials = template files in module minus its main page. `_kit` lists primitives actually included.

| Module | Route(s) | Serving resource(s) | Templates (files / partials) | Roles (`@RolesAllowed`) | `_kit/*` used |
|---|---|---|---|---|---|
| Dashboard | `/app/dashboard` | `DashboardResource` | 1 / 0 | all six tokens | page-header, metric, chart-card, surface |
| POS / Caja | `/app/pos`, `/app/pos/standalone`, `/api/app/caja/table` | `PosPageResource`, `PosResource`, `CierreCajaResource` | 14 / 13 | admin, facturacion (POS); admin, inventario (caja) | page-header, surface, modal, data-table, icon, metric |
| Facturas emitidas | `/app/facturas` | `RecibosPagesResource` | 4 / 3 | admin, facturacion | page-header, data-table, surface, icon, view-tabs |
| Facturas recibidas | `/app/facturas-recibidas` | `FacturasRecibidasPagesResource` | 5 / 4 | admin, facturacion | page-header, data-table, surface, modal, filter-bar, icon |
| Recibos | (same board as emitidas) | `RecibosPagesResource` | 4 / 3 | admin, facturacion | (as above) |
| Devoluciones | `/app/devoluciones` | `DevolucionesPagesResource` | 1 / 0 | admin, facturacion | page-header, surface |
| Artículos | `/app/articulos` | `ArticulosPagesResource` | 10 / 9 | admin, inventario | page-header, data-table, surface, filter-bar, action-bar, modal, icon, metric |
| Categorías | `/app/categorias` | `CategoriasPagesResource` | 5 / 4 | admin, inventario | page-header, data-table, surface, view-tabs, modal, icon |
| Inventarios | `/app/inventarios` | `InventariosPagesResource` | 12 / 11 | admin, inventario | page-header, data-table, surface, view-tabs, modal, filter-bar, icon, metric |
| Órdenes de compra | `/app/compras/ordenes` | `OrdenesPagesResource` | 7 / 6 | admin, inventario | page-header, data-table, surface, modal, filter-bar, icon |
| Clientes | `/app/reportes/clientes` | `ClientesReportesResource` | 3 / 2 | admin, usuario (mutations) | page-header, data-table, surface, modal, filter-bar, icon, metric |
| Usuarios | `/app/usuarios` | `UsuariosPagesResource` | 3 / 2 | admin, usuario | page-header, data-table, surface, modal, filter-bar, icon, metric |
| Loyalty | `/api/app/loyalty/table` | `LoyaltyResource` | 4 / 3 | admin, facturacion | page-header, data-table, surface, modal, icon, metric |
| Etiquetas | `/app/reportes/inventario/etiquetas` | `Reportes.EtiquetasResource` | 1 / 0 | admin, inventario | page-header, surface, icon |
| Alertas de inventario | `/app/reportes/inventario/alertas` | `Reportes.AlertasStockResource` | 1 / 0 | admin, inventario | page-header, surface, icon |
| Reportes (hub + subpáginas) | `/app/reportes` + 23 sub-routes | `MiscPagesResource` + `Reportes/*Resource` | 36 / 35 (24 reportes + 12 `_tablas`) | hub: admin, registro, inventario, tributacion | page-header, chart-card, surface, view-tabs, metric, icon, action-bar |
| Tributación | `/app/tributacion`, `/app/cabys` | `TributacionPagesResource`, `CabysPagesResource` | 7 / 6 (tributacion 4 + cabys 3) | admin, tributacion | page-header, data-table, surface, modal, icon, metric, view-tabs |
| Settings | `/app`, `/app/aplicacion` | `SettingsPagesResource` | 1 / 0 | admin | page-header, surface, icon |
| Perfil | `/app/perfil` | `PerfilPagesResource` | 2 / 1 | all six tokens | page-header, surface, icon |
| Impresoras | n/d | n/d | n/d | n/d | n/d |
| Registros | `/app/registros/log` | `MiscPagesResource` | 1 / 0 | admin, registro | page-header, surface, icon |
| Respaldos | `/app/backups` | referenced in navbar; controller not in Api/App pages list | n/d | admin (per navbar) | n/d |
| Stock alert config | `/api/app/stock-alert-config/pagina` | `StockAlertConfigResource` | 1 / 0 | admin, inventario | page-header, surface, icon |
| Tipo de cambio | `/app/tipo-cambio` | `TipoCambioPageResource` | 3 / 2 | admin, tributacion | page-header, surface, modal, icon |
| Correos | `/app/correos`, `/app/correos/reportes`, `/app/correos/plantillas` | `CorreosPagesResource` | 6 / 5 | admin, facturacion, tributacion | page-header, data-table, surface, modal, filter-bar, icon |

Template total: 147 HTML files under `templates/` (129 under `pages/`, 14 under `_kit/`, 3 fragments, 1 layout, 1 login). Counts from `Get-ChildItem` 2026-10-31.

## 2. Layout & navigation

- `templates/layout.html` (1-104): HTML shell. Slot renders `fragments/navbar` (lines 51-53), `<main id="contenido-principal" class="section">` with `.container` (55-59), footer (61-66), toast container (79), global `/` search shortcut script (86-101). No sidebar; navigation is a Bulma top navbar.
- `templates/fragments/navbar.html` (34-365): Bulma `.navbar` with burger button (45-50), `.navbar-menu`/`.navbar-end` (53), role-gated dropdowns: Artículos (93-106), Usuarios (111-124), Tributación (127-144), Ajustes (164-177); plain items Gestión (150), Reportes (157), Perfil (197); POS launcher (68-77); identity (187-194); logout form (208-213). Inline scripts: `data-kit-open` bridge (234-254), burger + dropdown toggles + "where am I" highlighter (267-365).
- Role rendering: `{inject:securityIdentity.hasRole(...)}` tokens admin/facturacion/inventario/usuario/tributacion/registro (lines 68, 93, 111, 127, 149, 156, 164, 187). Admin receives all six via `UserRoleMapper`.
- Tests pinning it: `NavbarPopupLauncherTest` (role gate, button headers, `navbar is-black` absence, `data-kit-popup`, root-path normalization); `RootPathNormalizationTest`; `DesignSystemPrimitivesTest`. No test asserts a sidebar (none exists).

To move to a vertical sidebar: replace the `<nav class="navbar">` slot in `layout.html` with a sidebar container + main grid; rewrite `fragments/navbar.html` into a sidebar partial (role gates reusable, but Bulma `.navbar-*` classes and burger/dropdown JS at 267-365 must be replaced by collapse/expand logic); update skip link target and "where am I" highlighter (324-363). Tests that would break: `NavbarPopupLauncherTest` (expects `<nav class="navbar"`, `button.navbar-link`, `navbar is-black` absence, `data-kit-popup` survives if launcher kept) and possibly `RootPathNormalizationTest` if URLs change.

## 3. CSS architecture

- `web/index.scss` (961 lines): layer order Bulma → legacy layers → (mercurius pulled in from JS). Sections (line ranges): base-theme 52-74, button-stack 77-115, datatable 117-141, dialog 143-168, form-elements 170-254, headers (twelve banners) 256-318, stats-grid/stat-card 320-368, ui-tabs 370-432, tab-content-card 434-441, actions-bar 443-461, action-btn 463-482, filters 484-511, tables 512-533, status-chip 534-561, table-actions 563-586, grid-view/articulo-card 588-653, responsive 655-711, animations 713-727, tooltip 729-739, loading-overlay 741-754, empty-state 756-777, nav-cards 780-795, side-panel 797-810, tabs 811-846, timeline/vis 847-925, hero/notification fixes 927-961.
- `web/mercurius.scss` (≈1500 lines): tokens §3.1-3.8 (28-224): spacing 31-39, radius 41-49, elevation 51-62, motion 64-82, accents 84-99, chart ink 101-116, muted text 118-129, accent ink 131-146, status pills 148-182, focus 184-224. Components: PageHeader 226-337, MetricCard 339-437, ViewTabs 439-514, FilterBar/ActionBar 516-564, Surface 566-619, ChartCard 621-694, Icon 696-719, StatusChip 721-757, accessibility 760-789, figures 791-815, motion safety 817-835, dialog entrance 837-913, toast 915-953, brand invert 955-964, file input 966-991, buttons/headers 993-1205, responsive 1279-1499+. Total: 14 component sections + 8 token sections + 4 responsive/motion sections. Every component uses `--mercurius-*` tokens; no component defines its own color hex.
- Legacy selector references in templates (grep counts, 2026-10-31): `stat-card` 13, `empty-state` 2, `*-header` banner classes 16 total (articulos 1, categorias 1, clientes 1, cabys 1, recibos 1, correos 2, usuarios 1, registros 2, backups 1, inventario 1, tributacion 2, caja 2), `actions-bar` 1, `filters-section` 1, `side-panel` 0, `tab-content-card` 0, `grid-view` 0, `articulo-card` 0, `table-actions` 0, `button-stack` 0. Most legacy layout classes already unused; banners and stat-card remain the main legacy surface.
- Tokens already defined: `--mercurius-space-*`, `--mercurius-radius-*`, `--mercurius-shadow-*`, `--mercurius-ease-*`/`--mercurius-dur-*`, `--mercurius-accent-*`, `--mercurius-surface*`, `--mercurius-border`, `--mercurius-chart-series-*`, `--mercurius-text-muted`, `--mercurius-accent-ink`, `--mercurius-pill-*-bg/ink`, `--mercurius-focus-ring`. Colors derived from Bulma vars (no hex). Missing vs target: no sidebar tokens, no font-family token (Inter not defined), no density/row-height token, no elevation beyond 3 levels, no manual dark-mode toggle tokens (only scheme vars).

## 4. Shared components (`_kit/`)

| File | Renders | Params (req / opt) | Includes | Orphan? |
|---|---|---|---|---|
| `_kit/icon.html` | inline SVG marker hydrated by `icons.js` | name (req); label, size | 159 | no |
| `_kit/page-header.html` | page header with `<h1>` | title (req); subtitle, variant, accent | 69 | no |
| `_kit/surface.html` | panel with header/body/footer | none req; title, subtitle, flush, id | 127 | no |
| `_kit/metric.html` | metric card | label, value (req); detail, detailSuffix, href, interactive, active, id | 94 | no |
| `_kit/view-tabs.html` | tab nav | ariaLabel (req); tabs, id | 27 | no |
| `_kit/filter-bar.html` | filter surface + grid | none req; id, method, action, id_field | 24 | no |
| `_kit/action-bar.html` | count + actions strip | none req; count, countLabel, id | 8 | no |
| `_kit/chart-card.html` | chart frame | title (req); subtitle, size, canvasId, height | 19 | no |
| `_kit/data-table.html` | sortable/paged table | id, baseUrl, headers (req); page, size, total, totalPages, pages, params, sortKey, sortDir | 65 | no |
| `_kit/modal.html` | dialog shell | id, bodyUrl (req); title, triggerLabel, triggerClass | 59 | no |
| `_kit/confirm.html` | styled confirm dialog | all optional | 23 | no |
| `_kit/pagination.html` | pager | n/d | 6 | no (low use) |
| `_kit/toast-item.html` | one notification | severity, message | 48 | no |
| `_kit/toast-container.html` | toast mount point | none | 1 (layout) + 1 (factura-standalone) | no |

No orphan primitives: every `_kit` file has at least one include. Lowest adoption: `pagination.html` (6) and `action-bar.html` (8). `toast-container` is included once in `layout.html` (line 79) plus a hand-copied instance in `pages/facturas/factura-standalone.html:24`.

## 5. HTMX surface

- Pattern counts across 147 templates: `hx-get` 137, `hx-post` 74, `hx-target` 212, `hx-swap` 248. Common swap targets: `#...-body`, `#...-tabla`, outerHTML/innerHTML. `hx-trigger` includes `keyup changed delay:300ms`, `change`, `every 5s/30s`.
- Fragment endpoints: 37 `@Path("/table")` plus multiple `@Path("/formularios/...")` in `Controllers/Api/App/*Resource.java` and `Controllers/Api/Accounting/*`. Pages are fragment-aware (check `HX-Request`: render fragment only when present, full page otherwise) per `ui-kit.md` §2.9.
- Pages with no HTMX at all (24 files): `pages/dashboard/index.html`, `pages/gestion/index.html`, `pages/registros/log.html`, and 20 report subpages (`reportes/alertas.html`, `departamentos.html`, `estacionalidad.html`, `etiquetas.html`, `familias.html`, `fechas.html`, `index.html`, `merma.html`, `precios.html`, `pronosticos.html`, `proveedores.html`, `rendimiento.html`, `resumen.html`, `tendencias.html`, `ventas-fecha-historica.html`, plus `_historial-puntos.html`), `pages/facturas/factura.html`, `pages/inventario/detalles-ajuste.html`, `pages/tipo-cambio/historial.html`, `pages/tipo-cambio/index.html`, `pages/tributacion/declaracion.html`.

## 6. Alpine surface

- Totals: `x-data` 18, `x-show` 58, `$data` 40 across templates.
- Top 15 files by Alpine attribute count: `reportes/index.html` 30, `settings/index.html` 21, `articulos/index.html` 7, `inventario/index.html` 6, `usuarios/form.html` 6, `tributacion/consultas.html` 5, `categorias/index.html` 5, `articulos/form-promocion.html` 5, `inventario/form-ajuste.html` 4, `recibos/tablero.html` 4, `tributacion/dashboard.html` 4, `tipo-cambio/fragment.html` 3, `facturas/cart-panel.html` 1, `cabys/index.html` 1, `categorias/tabla-departamentos.html` 1.
- `web/index.js` (653 lines) registers globally: `window.Alpine`, `window.Chart`/`window.MercuriusCharts` (create, destroyAll, resolveColour, currency, number, date), `window.mercuriusNotify`; functions `kitBridge`, `kitUploadProgress`, `kitPolling`, `kitPopup`, `kitToasts`, `kitTableScroll`, `kitHtmlErrorSwap`, `kitHttpErrors`, plus `boot()` wiring (636-653). Must not break: CSRF header inheritance, Alpine brace spacing rule, icon hydration (`icons.js`), chart defaults (`chart-config.js`), toast lifecycle.

## 7. Forms / tables / dialogs / drawers

- Forms: 98 `<form>` tags across templates; HTMX Pattern A (fragment redisplay) and Pattern B (no-JS full page) per `ui-kit.md` §11; `hx-post` 74. CSRF inherited from layout (hidden `csrf-token` only on plain POST forms).
- Tables: `_kit/data-table` used in 65 includes; additional hand-written `<table class="table ...">` remain (legacy). `pagination.html` only 6 includes (most tables use data-table's built-in pager).
- Modals: `_kit/modal` 59 includes; confirm `_kit/confirm` 23 includes (one per page expected).
- Drawers/side-panels: no `.side-panel` usage (0 refs). "Drawers" are modals: `pages/facturas-recibidas/detalle-drawer.html`, `pages/loyalty/drawer-historial.html` (loaded into `_kit/modal` body). `side-panel` CSS class exists but unused.
- Toasts: one container in `layout.html` (line 79) + hand-copied instance in `factura-standalone.html`; `toast-item` 48 includes (server-rendered or OOB).

## 8. Gap list vs target direction

| Area | Current state | Gap | Risk | Files |
|---|---|---|---|---|
| Sidebar | Top Bulma navbar only (`fragments/navbar.html`) | No vertical sidebar exists | High (layout + 100+ templates/tests) | `templates/layout.html`, `templates/fragments/navbar.html` |
| Typography | Bulma default font stack; no `@font-face`/Inter | **Out of scope by decision UI-1** (stack unchanged); only the size scale in the spec is applied | None | `web/mercurius.scss` |
| Palette | Tokens derived from Bulma vars; 0 hex literals in mercurius.scss; 50 hex literals in templates (inline) | Inline hex in templates must be removed | Medium | `templates/pages/**` |
| Density | Spacing tokens exist (`--mercurius-space-*`); row heights from Bulma defaults | No explicit density/row-height token; compact density not enforced | Medium | `web/mercurius.scss` §3.1 |
| Surfaces | `_kit/surface` + tokens (`--mercurius-surface`) | Legacy `.box`/`.card` still used in pages | Low-Medium | `templates/pages/**` |
| Radii/shadows | Tokens `--mercurius-radius-*`, 3 elevation levels; "border OR shadow" rule | Legacy selectors still carry own radii/shadows | Low | `web/index.scss`, `web/mercurius.scss` |
| Responsive | Breakpoints 768px/480px; horizontal table scroll with affordance | Tables fixed min-width; some pages overflow on mobile | Medium | `web/mercurius.scss` §21, `_kit/data-table` |
| Dark mode | `prefers-color-scheme` media queries only; no manual toggle; `.theme-light/.theme-dark` hooks in focus token | No user-facing theme switch; Bulma scheme auto only | Low | `web/mercurius.scss` (203-223) |
| Legacy banners | 12 `*-header` classes still referenced (16 total) | Flatten to `_kit/page-header` | Medium | `web/index.scss` 256-318, templates |
| Legacy stat-card | 13 references | Migrate to `_kit/metric` | Medium | `templates/pages/**` |

## 9. Tests that pin UI (would break on modernization)

| Test | What it pins | File |
|---|---|---|
| `NavbarPopupLauncherTest` | `<nav class="navbar"`, `button.navbar-link`, `navbar is-black` absence, `data-kit-popup`, root-path | `app/src/test/.../NavbarPopupLauncherTest.java` |
| `RootPathNormalizationTest` | no literal root-path in URL attrs | `app/src/test/.../RootPathNormalizationTest.java` |
| `DesignSystemPrimitivesTest` | `_kit/*` parse + a11y markers | `app/src/test/.../DesignSystemPrimitivesTest.java` |
| Reports hub roles tests | role gates on `/app/reportes` | `app/src/test/.../Reportes/ReportsHubRolesTest.java` |
| Page tests (≈40) | template render + role gates per module | `app/src/test/java/Controllers/Api/App/*PageTest.java` |

## 10. Verification commands (reproducible)

```powershell
Get-ChildItem app/src/main/resources/templates/pages -Recurse -Filter *.html | Measure-Object
Get-ChildItem app/src/main/resources/templates -Recurse -Filter *.html | Select-String -Pattern '_kit/' | ...
```

## 11. Additional observations

- `templates/login.html` exists outside `pages/` and does not include `layout.html` (standalone auth screen). It is the only non-layout top-level template besides `layout.html`.
- `pages/facturas/factura-standalone.html` is the POS popup document: it does not include `layout.html` and carries a hand-copied toast container (see §7). Converging it onto `_kit/toast-container` is a W6 item per `ui-kit.md` §7.
- `pages/inventario/` has the most templates (12) and the most Alpine refs among data-entry modules; its upload form uses multipart + progress (candidate for `kitUploadProgress()`).
- `pages/reportes/` contains 24 templates + 12 under `_tablas` + `_historial-puntos.html`; many are chart-heavy and HTMX-free (server-rendered, chart init via inline module scripts).
- `web/icons.js` is the single icon source (24x24 stroke icons); `_kit/icon` hydration covers htmx-swapped content via MutationObserver. Unknown keys log one console warning.
- `web/chart-config.js` + `report-chart.js` apply shared Chart.js defaults; `MercuriusCharts.resolveColour` required for CSS token → canvas (see ui-kit.md §10.1 Charts).
- No FontAwesome dependency; nine surviving `fas fa-*` references rendered nothing (per ui-kit.md §9).
- No dark-mode manual toggle anywhere in templates/JS; only `prefers-color-scheme` media queries in `mercurius.scss`.
- No Inter (or any custom) `@font-face`; font stack is Bulma's default (system UI).
- `quarkus.http.root-path` (`/Mercurius`) is resolved via `{config:...}` in templates; `RootPathNormalizationTest` guards against regressions.
- CSRF: `layout.html` sets `hx-headers` on `<body>` (line 38); plain forms carry hidden `csrf-token`.
- Bulma version pinned at 1.0.4 (per `docs/ui-kit.md` §1); HTMX 2.0.10, Alpine 3.16.1, Chart.js 4.4.8.
- No new libraries may be added (stack pin rule in `docs/ui-kit.md`).
- All new primitives are additive; legacy selectors deleted only in W6 after zero-reference grep.

## 12. Summary counts

- Pages inventoried: 26 module rows (incl. n/d for impresoras/respaldos).
- Templates: 147 total (129 pages + 14 kit + 3 fragments + layout + login).
- `_kit` primitives: 14 files, all in use (lowest: pagination 6).
- Legacy header classes still referenced: 16; stat-card: 13.

End of Phase 1 audit.

Counts above from `Get-ChildItem`/`Select-String` on 2026-10-31. Re-run to refresh. `n/d` = not determined from available evidence. Audit is read-only: no source files modified except this document.