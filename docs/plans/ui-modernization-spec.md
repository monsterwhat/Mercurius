# Mercurius — UI Design System & Page Specifications

**Status:** source of truth for the UI modernization (branch `ui-modernization`).
**Audience:** every agent landing the redesign. **Precedence:** this document > ad-hoc UI opinions.
**Stack pins (do not add libraries):** Quarkus + Qute + HTMX 2.0.10 + Alpine.js 3.16.1 (core only) + Bulma 1.0.4 + Chart.js 4.4.8.
**Existing contract that stays valid:** `docs/ui-kit.md` (primitives in `templates/_kit/`, tokens + component CSS in `web/mercurius.scss`, icons in `web/icons.js`, shared JS in `web/index.js`). This document **extends** it; it does not replace it.

---

## 0. Decisions taken before implementation

| ID | Decision | Consequence |
|---|---|---|
| UI-1 | **Typography is NOT changed.** Keep the current font stack. | The "Inter" recommendation in the design reference is dropped. Sizes/weights/leading from §3 are applied to the existing family. No `@font-face`, no font files. |
| UI-2 | **Navigation becomes sidebar + slim header.** | `templates/fragments/navbar.html` is replaced by `AppSidebar` + `AppHeader`. No content/role-gate is lost: every current navbar entry is re-homed. |
| UI-3 | Work happens on a dedicated branch. | `ui-modernization`; `master` stays releasable. One commit per step, SemVer bump in the same commit, tests green before commit. |
| UI-4 | Reuse before inventing. | Every screen composes `_kit/*`; a new pattern opens a design review, not a page-local rule. |
| UI-5 | No business change. | Routes, endpoints, services, roles, filters and exports stay as they are. Only markup, CSS and Alpine/HTMX wiring change. |
| UI-6 | **Browser QA is authorized for this branch.** | Playwright/headless Chrome against a local run (real login, seeded data) at desktop and ~1024 px, to compare each screen against the references and prove interactions. This is the only exception to the global no-browser rule; it does not extend to other branches. |
| UI-7 | **Sidebar is 160 px, fixed, with short labels + tooltips.** | "Configuración" renders as "Ajustes"; long entries use the short label with the full name in `title`/tooltip. No collapsed icon mode. |
| UI-8 | **"Impresoras" has no backend.** | Out of scope. Configuración shows the card disabled with "No disponible", no route, no invented API. |

---

## 1. Visual identity

Modern enterprise ERP: dense but legible, minimal decoration, functional colour.

- General background: very light blue-gray.
- Panels: white with slightly blue borders.
- Sidebar: dark navy.
- Primary actions: electric blue or violet.
- Positive: green. Pending: amber. Error: red. Secondary: blue-gray.
- Slightly rounded corners, very subtle shadows, high information density.

Not a landing page, not a storefront, not a stretched mobile app.

## 2. Palette

| Token | Hex | Use |
|---|---|---|
| `--ui-navy` | `#17294B` | Sidebar background |
| `--ui-navy-2` | `#21385F` | Sidebar hover / nested surface |
| `--ui-primary` | `#3766E8` | Active nav, primary action, links |
| `--ui-purple` | `#7055DB` | Secondary primary action |
| `--ui-bg` | `#F3F6FC` | Application background |
| `--ui-surface` | `#FFFFFF` | Cards, tables, modals |
| `--ui-border` | `#DCE4F2` | Borders, dividers |
| `--ui-text` | `#172442` | Primary text |
| `--ui-success` | `#20B981` | Confirmations, emitted/paid |
| `--ui-warning` | `#F5A623` | Pending |
| `--ui-danger` | `#E65B65` | Errors, rejected |
| `--ui-muted` | `#8290A8` | Secondary text, metadata |

Rules: one meaning per colour; never more than one accent per screen region; no hex literals outside the token block.

## 3. Typography scale (family unchanged — see UI-1)

| Element | Size |
|---|---|
| Page title | 22–26 px |
| Section title | 15–17 px |
| Subtitles | 13–14 px |
| Body | 13–14 px |
| Tables | 12–13 px |
| Labels | 11–12 px |
| Main KPI | 23–28 px |

Legibility beats decoration: no letter-spacing tricks, no uppercase paragraphs.

## 4. Global structure

```
+-----------+--------------------------------------------------+
| Sidebar   | Header (52-60px): menu toggle · breadcrumb/title  |
| 145-165px |   global search · notifications · avatar/role     |
|  (navy)   +--------------------------------------------------+
|           | Content: padding 16-24px, grids, white surfaces   |
+-----------+--------------------------------------------------+
```

**Sidebar**
- Logo Mercurius.
- Dashboard, Gestión, Reportes, Hacienda, Usuarios, Ajustes.
- Bottom: version, connection status, store/branch identity.
- Active item: brighter blue fill + rounded corners.
- **Width: 160 px fixed (UI-7).** Long labels use the short form; the full name goes in `title`/tooltip.

| Full label | Sidebar label |
|---|---|
| Configuración | Ajustes |
| Puntos y recompensas | Puntos |
| Alertas de inventario | Alertas |
| Órdenes de compra | Compras |
| Facturas recibidas | Recibidas |
| Correos programados | Correos |
| Registro de actividades | Registro |

Any other label that does not fit is truncated with `title` carrying the full text — never abbreviated ad hoc.

**Header**
- Sidebar toggle, breadcrumb/contextual title, global search, notifications, user avatar, name + role, account dropdown.

**Content**
- Padding 16–24 px, consistent grid gaps, white cards, full-width tables, sectioned forms.

## 5. Page specifications

Each module keeps a layout suited to its job; all share the component system.

### A. Dashboard
KPI cards: today, yesterday, this week, last 30 days. Bar chart "sales per hour" with period filter. Weekly trend line chart. Bestsellers ranking (item + units). System status (Hacienda, last invoice sent, connection).

### B. POS / Nueva Factura
Two columns. Left: article search (code/CABYS), categories, product grid (image/icon, name, price, add). Right: cart (lines, quantities, unit price, subtotals, remove), customer, payment method, IVA, total, "Cobrar". Payment opens its own dialog.

### C. Facturas emitidas
Title + export, date filter, status filter, search, table: folio, date, customer, identification, amount, status, actions. Status colours: emitted green, pending amber, rejected red, voided gray. Selecting a row opens a **drawer** with customer, items, taxes, totals, XML, PDF, Hacienda response.

### D. Facturas recibidas
KPIs (received, pending review, overdue, processed), "Cargar factura" + XML/PDF upload area, filters by provider/date/status. Table: consecutive, provider, issue date, due date, amount, status, actions. Drawer: provider, original XML, tax breakdown, validations, approval state, notes.

### E. Recibos
KPIs: total receipts, total amount, receipts in period, pending. Table: receipt number, date, customer, concept, amount, status. Detail: receipt info, payment method, related invoice, print, export.

### F. Artículos
"New article", search, CABYS search, category filter, tabs (activos, inactivos, procesados, pendientes, promociones). Table: code, description, category, price, cost, stock, status, actions. Form: internal code, name, description, category, CABYS, sale price, cost, tax, unit, barcode, inventory control.

### G. Categorías
Two sections: Familias and Departamentos, each with name, code, description, status, article count, actions. Create/edit in a modal.

### H. Inventarios
KPIs: current stock, inventory value, low-stock items, period losses. Inventory-value chart. Tabs: Resumen, Movimientos, Alertas, Pérdidas. Movements table: date, item, type, quantity, previous stock, resulting stock, user.

### I. Órdenes de compra
KPIs: pending orders, received orders, total purchase amount. "Nueva orden". Table: order number, provider, date, expected date, amount, status. Detail: provider, requested items, quantities, costs, taxes, total, reception state.

### J. Devoluciones
"New return", invoice search, period filter, table: folio, date, customer, amount, status. Drawer: original invoice, returned items, quantities, reason, credited amount, credit note.

### K. Clientes
KPIs: total, new, frequent. Search + zone filter. Table: identification, name, phone, email, zone, last purchase, actions. Detail: personal/fiscal data, address, purchase history, invoices, receipts, loyalty points, Gometa data where available.

### L. Usuarios
KPIs: total, active, inactive. "Nuevo usuario". Table: user, name, email, role, last access, status. Detail: user data, role, permissions, activity history. Roles: admin, facturacion, inventario, usuario, tributacion, registro.

### M. Puntos y recompensas
KPIs: points issued, redeemed, participating customers, available rewards. Tabs: Clientes, Movimientos, Recompensas, Configuración. Movements: customer, date, type, points, reference. Reward catalogue: name, image, points required, availability, status.

### N. Etiquetas
Article search, multi-select, label quantity, paper size, templates, **real print preview** (actual labels, not a product table). Each label: name, price, barcode, internal code.

### O. Alertas de inventario
Low stock, out of stock, near-out-of-stock, configured thresholds. Table: item, current, minimum, recommended, status. Configuration form: minimum, target, suggested replenishment.

### P. Reportes
Hub grouped by category (Facturación, Facturas y correo, Inventario, Usuarios) as cards. Every report page: period selector, filters, relevant KPIs, charts, detail table, export.

### Q. Tributación / Hacienda
Tabs: Hacienda, CABYS, Tipo de cambio. Hacienda: connection state, last query, last invoices sent, reception state, rejected documents, "Consultar Hacienda". CABYS: code search, description, category, results, selection. Tipo de cambio: buy, sell, reference date, history. Tax summary: IVA debit, IVA credit, total payable, fiscal period, operations detail.

### R. Configuración
Hub of cards: General, Correos, Respaldo, Reportes programados, Perfil. Cards group: SMTP/plantillas/destinatarios/programados; general settings/parameters/preferences; backups (create, history, restore); activity log (user, action, date, module, result); printers (available, config, default, test print); users and roles.

## 6. Component contract

| Component | Existing primitive | Status |
|---|---|---|
| AppSidebar | — | **new** (`_kit/app-sidebar`) |
| AppHeader | — | **new** (`_kit/app-header`) |
| PageHeader | `_kit/page-header` | reuse |
| StatCard | `_kit/metric` | extend (density) |
| DataTable | `_kit/data-table` | reuse |
| FilterBar | `_kit/filter-bar` | reuse |
| StatusBadge | `.status-chip.is-*` | reuse |
| Tabs | `_kit/view-tabs` | reuse |
| BaseModal | `_kit/modal` | reuse |
| DetailDrawer | — | **new** (`_kit/drawer`) |
| EmptyState | — | **new** (`_kit/empty-state`) |
| LoadingState | — | **new** (`_kit/loading-state`) |
| ChartCard | `_kit/chart-card` | reuse |
| ConfirmDialog | `_kit/confirm` | reuse |
| FormSection | — | **new** (`_kit/form-section`) |

Every screen composes these. No module-specific button, table or modal.

## 7. Implementation rules

- Keep Quarkus/Qute/HTMX/Alpine/Bulma. No React/Vue/Angular/TypeScript.
- Do not duplicate business logic; do not change endpoints unless strictly required.
- Keep routes, permissions, filters, exports and the electronic-invoice flows working.
- Do not invent functionality the backend does not support.
- Do not show mock data in production paths.

## 8. Mandatory UX rules

1. No decorative buttons.
2. Tables need loading, empty and error states.
3. Forms validate their fields.
4. Destructive actions require confirmation.
5. Filters look the same everywhere.
6. Drawers keep page context.
7. Notifications are discreet.
8. Pages work at different resolutions.
9. Permissions decide what is visible.
10. No fictitious data in production.

## 9. Delivery plan

| Delivery | Scope |
|---|---|
| 1 | Design system, navigation (sidebar + header), Dashboard, POS |
| 2 | Invoicing, articles, inventory, customers, purchases |
| 3 | Reports, Hacienda, settings and administration |

After each delivery: compare against the reference screens, verify interactions still work, run the suite.
