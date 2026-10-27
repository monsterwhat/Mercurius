# Page + Dialog Audit — Mercurius (dev, admin)

Method: authenticated curl crawl (form-cookie), flags = non-200 or error markers
(`Template Rendering error`, `INTERNAL_ERROR`, stack traces).

## Pages (37 routes, 2026-09-20)

| Route | Status | Notes |
|---|---|---|
| /app | 303 | redirect to landing — expected |
| /app/dashboard | 200 clean | |
| /app/articulos | 200 clean | |
| /app/inventarios | 200 clean | |
| /app/categorias | 200 clean | |
| /app/cabys | 200 clean | |
| /app/correos | 404 | no base GET by design (only /reportes, /plantillas); no nav links here — OK |
| /app/impresoras | 200 clean | |
| /app/perfil | 200 clean | |
| /app/pos | 200 clean | |
| /app/recibos | 200 clean | |
| /app/devoluciones | 200 clean | |
| /app/usuarios | 200 clean | |
| /app/tipo-cambio | 200 clean | tiny (5KB) — verify fragment content visually |
| /app/tributacion | 200 clean | |
| /app/tributacion/dashboard | 200 clean | |
| /app/tributacion/consultas | 200 clean | |
| /app/reportes/clientes | 200 clean | +Nuevo/Editar verified live in browser |
| /app/reportes/correos | 200 clean | |
| /app/reportes/usuarios | 200 clean | |
| /app/reportes/facturas | 200 clean | |
| /app/reportes/margenes | 200 clean | |
| /app/reportes/loyalty | 200 clean | |
| /app/reportes/estacionalidad | 200 clean | |
| /app/reportes/recibos | 404 | no base GET by design (only /pendientes, /vencidos); no nav links — OK |
| /app/reportes/recibos/pendientes | 200 clean | |
| /app/reportes/recibos/vencidos | 200 clean | |
| /app/reportes/articulos/ventas | 200 clean | |
| /app/reportes/articulos/departamentos | 200 clean | |
| /app/reportes/articulos/familias | 200 clean | |
| /app/reportes/articulos/fechas | 200 clean | |
| /app/reportes/articulos/rendimiento | 200 clean | |
| /app/reportes/articulos/tendencias | 200 clean | |
| /app/reportes/inventario/alertas | 200 clean | |
| /app/reportes/inventario/etiquetas | 200 clean | |
| /app/reportes/inventario/merma | 200 clean | |
| /app/reportes/inventario/precios | 200 clean | |
| /app/reportes/inventario/pronosticos | 200 clean | |
| /app/reportes/inventario/resumen | 200 clean | |
| /app/_probe | 200 clean | dev probe page |

## Round 2 — fragment GETs + tables (authenticated, 2026-09-20)

All id-forms return small JSON/bean 404s on missing ids = endpoints exist.
All tables 200. scan-form is POST-only (405 on GET correct).

| Fragment endpoint | Status | Notes |
|---|---|---|
| /api/app/articulos/1, /formularios/articulos/1, /revision/1, /promociones/1 | JSON-404 | exist ✓ |
| /api/app/articulos+clientes+inventario+ordenes+users+email+categorias+cabys+caja+loyalty+reportes-programados /table | 200 | all tables render |
| /api/app/inventario/revision/siguiente | 200 | empty JSON (empty DB) ✓ |
| /api/app/inventario/ajuste/1/detalles, /revision/1, /ordenes/1, /users/1 (200 seeded admin!), /email-templates/1, /categorias/familia+departamento/1, /cabys/ABC, /reportes-programados/1 | JSON-404/200 | exist ✓ |
| /app/tipo-cambio/historial, /api/app/pos/cart-panel, /api/app/recibos/stats+tabla, /api/app/devoluciones/facturas(400-needs-params) | 200/400-clean | exist ✓ |
| /api/app/pos/scan-form (GET) | 405 | POST-only by design ✓ |

## Dialog defects fixed this round

- _kit/modal shell default Cerrar removed (was doubling every dialog app-wide)
- Theme root fix in web/index.scss + global help/checkbox/radio hardening

## Dialogs (43 bodyUrls — server side, authenticated GET)

| Fragment endpoint | Status | Notes |
|---|---|---|
| /api/app/clientes/formularios/nueva | 200 | +row edit needs real code (404 on /1 = empty dev DB, expected) |
| /api/app/clientes/zonas | 200 | new directory endpoint ([] on empty DB) |
| /api/app/clientes/lookup?cedula=… | 200 | live-verified vs Gometa in browser |
| /api/app/articulos/formularios/articulos/nueva | 200 | |
| /api/app/articulos/formularios/promociones/nueva | 200 | |
| /api/app/articulos/formularios/promociones/articulos | 200 | |
| /api/app/articulos/table?tab=activos | 200 | |
| /api/app/articulos/cabys?q= | 200 | |
| /api/app/articulos/revision/siguiente | 200 | |
| /api/app/categorias/formularios/familia/nueva | 200 | |
| /api/app/categorias/formularios/departamento/nueva | 200 | |
| /api/app/inventario/formularios/ajuste/nueva | 200 | |
| /api/app/inventario/formularios/rapido | 200 | |
| /api/app/users/formularios/nuevo | 200 | |
| /api/app/cabys/table | 200 | |
| /api/app/pos/payment-dialog | 200 | |
| /api/app/pos/client-picker?q= | 200 | |
| /api/app/pos/articulos-picker?q= | 200 | |
| /api/app/loyalty/view | 404 | DEAD placeholder only — row buttons override to /{code}/view which EXISTS (404-JSON on missing = correct). Harmless; repoint placeholder or leave |
| /api/app/ordenes/formularios/nueva | 200 | |
| /api/app/ordenes/table | 200 | |
| /api/app/email-templates/formularios/nueva | 200 | |
| /api/app/reportes-programados/formularios/nueva | 200 | |
| /api/app/stock-alert-config/formularios/nueva | 404 | BROKEN LIVE BUTTON — no backend; row Editar (/{id}/editar) + toggle (/{id}/toggle) also missing. Backend only has JSON obtener/actualizar. Feature build, not layout |
| /api/app/facturas-recibidas/{id} | 404 on id=0 | needs real id — OK |

## Fixes log

- Logo inlined (fragments/logo-img), shell Cerrar default removed, theme root fix + hardening (uncommitted at time of writing)
- Reportes clientes: Nuevo/Editar buttons + Acciones column (verified live)
- Client form: Gometa lookup, zonas directory, actividad principal/remove
