# Mobile audit — 390×844 (Edge/Chromium)

Pages: **51** · screenshots: `docs/qa/mobile-audit/screens/` · raw data: `mobile-audit.json`

Method: real browser at 390×844, `is_mobile`, touch, logged in as Admin; one full-page screenshot and DOM metrics per page. Every page was loaded after the 502-deploy window; 5xx pages retried.

## P0 — blocking

1. **Global header overflows 44px on every page.** `.app-header-actions` is 374px starting at x=60 → right edge 434 on a 390 viewport. The Admin/user control is clipped on every screen. **Fixed in code** (`fragments/app-header.html` + `web/mercurius.scss`: icon-only Comprobantes and hidden username ≤640px); pending deploy.

2. **`/app/reportes/articulos/ventas-fecha-historica` renders 22,564 interactive elements** (a checkbox per article; 22,553 under 40px). The selector is a 320px scroll box, but the phone still parses 22k inputs. Needs a server-side search/limit (top N or paginated picker).

## P1 — high

- **Tap targets under 40px:** table action buttons (`is-small`, 32px), menu/theme (32px), pager links and chips. Pages with the most: `etiquetas` 47, `resumen` 34, `cabys` 33, `compras/ordenes` 32, `fechas` 31, `loyalty` 31, `categorias` 30.

- **Very wide tables inside scroll containers** (intentional horizontal scroll, but 3 screens wide): `/app/reportes/clientes` 1020px, `/app/reportes/articulos/fechas` 1016px, `/app/reportes/inventario/pronosticos` 940px, `/app/reportes/margenes` 937px, `/app/facturas-recibidas` 769px, `/app/recibos` 769px. Consider hiding low-priority columns on phones or a card row layout.

- **Dense screens:** `/app/aplicacion` is one enormous scroll (sucursales, correo, Hacienda, perfil, subtipos, lealtad, umbrales) with a sticky save bar; `/app/settings` repeats the same pattern; POS stacks scan + cart + totals but the cart stays below the fold.

- **`/app/aplicacion` still has 7px of page overflow** after subtracting the global 44px (51 total). Likely the sticky save bar / a fixed element; inspect at 390px.

## P2 — medium

- **Small text:** table headers 11.5px, sidebar/meta 11px; 10–22 instances per page (worst `reportes/clientes` 22, `loyalty` 21, `inventario/resumen` 19).

- **Toast container** is 352px wide starting at x=66 → right edge 418 (28px clipped). Add `max-width: calc(100vw - 2rem)`.

- **Header context title is empty on mobile** until JS fills it; no visible page name in the 56px header.

## What already works

- `meta viewport` is correct; columns stack on mobile; tables are wrapped in `.table-container.is-scrollable`; modals are capped (client/article modal: 374×804 inside 844 with an internally scrolling body); filter bars stack; POS standalone popup has 0 page overflow.


## Page table

| Page | Extra overflow* | Tap <40 | Text <12px | Interactive | Screenshot |
|---|---:|---:|---:|---:|---|
| `/app/aplicacion` | 7 | 28 | 7 | 66 | [app__aplicacion.png](screens/app__aplicacion.png) |
| `/app/reportes/articulos/ventas-fecha-historica` | 0 | 22553 | 10 | 22564 | [app__reportes__articulos__ventas-fecha-historica.png](screens/app__reportes__articulos__ventas-fecha-historica.png) |
| `/app/facturas-recibidas` | 0 | 17 | 15 | 99 | [app__facturas-recibidas.png](screens/app__facturas-recibidas.png) |
| `/app/recibos` | 0 | 17 | 15 | 99 | [app__recibos.png](screens/app__recibos.png) |
| `/app/correos/plantillas` | 0 | 26 | 13 | 98 | [app__correos__plantillas.png](screens/app__correos__plantillas.png) |
| `/app/registros/log` | 0 | 26 | 7 | 85 | [app__registros__log.png](screens/app__registros__log.png) |
| `/app/categorias` | 0 | 30 | 9 | 84 | [app__categorias.png](screens/app__categorias.png) |
| `/app/reportes/clientes` | 0 | 28 | 22 | 82 | [app__reportes__clientes.png](screens/app__reportes__clientes.png) |
| `/app/usuarios` | 0 | 27 | 14 | 82 | [app__usuarios.png](screens/app__usuarios.png) |
| `/app/inventarios` | 0 | 28 | 18 | 80 | [app__inventarios.png](screens/app__inventarios.png) |
| `/app/clientes` | 0 | 20 | 18 | 74 | [app__clientes.png](screens/app__clientes.png) |
| `/app/cabys` | 0 | 33 | 14 | 65 | [app__cabys.png](screens/app__cabys.png) |
| `/app/reportes/loyalty` | 0 | 31 | 21 | 65 | [app__reportes__loyalty.png](screens/app__reportes__loyalty.png) |
| `/app/reportes/inventario/etiquetas` | 0 | 47 | 16 | 65 | [app__reportes__inventario__etiquetas.png](screens/app__reportes__inventario__etiquetas.png) |
| `/app/articulos` | 0 | 27 | 14 | 63 | [app__articulos.png](screens/app__articulos.png) |
| `/app/reportes/inventario/resumen` | 0 | 34 | 19 | 58 | [app__reportes__inventario__resumen.png](screens/app__reportes__inventario__resumen.png) |
| `/app/reportes/inventario/alertas` | 0 | 27 | 19 | 50 | [app__reportes__inventario__alertas.png](screens/app__reportes__inventario__alertas.png) |
| `/app/facturas` | 0 | 18 | 15 | 49 | [app__facturas.png](screens/app__facturas.png) |
| `/app/backups` | 0 | 27 | 6 | 49 | [app__backups.png](screens/app__backups.png) |
| `/app/reportes/inventario/pronosticos` | 0 | 26 | 20 | 48 | [app__reportes__inventario__pronosticos.png](screens/app__reportes__inventario__pronosticos.png) |
| `/app/reportes/margenes` | 0 | 27 | 21 | 47 | [app__reportes__margenes.png](screens/app__reportes__margenes.png) |
| `/app/compras/ordenes` | 0 | 32 | 16 | 47 | [app__compras__ordenes.png](screens/app__compras__ordenes.png) |
| `/app/reportes/articulos/departamentos` | 0 | 29 | 9 | 46 | [app__reportes__articulos__departamentos.png](screens/app__reportes__articulos__departamentos.png) |
| `/app/reportes` | 0 | 19 | 3 | 44 | [app__reportes.png](screens/app__reportes.png) |
| `/app/reportes/articulos/ventas` | 0 | 24 | 13 | 43 | [app__reportes__articulos__ventas.png](screens/app__reportes__articulos__ventas.png) |
| `/app/reportes/articulos/tendencias` | 0 | 22 | 9 | 43 | [app__reportes__articulos__tendencias.png](screens/app__reportes__articulos__tendencias.png) |
| `/app/gestion` | 0 | 26 | 3 | 43 | [app__gestion.png](screens/app__gestion.png) |
| `/app/reportes/articulos/familias` | 0 | 29 | 9 | 43 | [app__reportes__articulos__familias.png](screens/app__reportes__articulos__familias.png) |
| `/app/reportes/articulos/fechas` | 0 | 31 | 27 | 42 | [app__reportes__articulos__fechas.png](screens/app__reportes__articulos__fechas.png) |
| `/app/reportes/inventario/precios` | 0 | 26 | 17 | 42 | [app__reportes__inventario__precios.png](screens/app__reportes__inventario__precios.png) |
| `/app/loyalty` | 0 | 30 | 12 | 40 | [app__loyalty.png](screens/app__loyalty.png) |
| `/app/stock-alert-config` | 0 | 26 | 10 | 40 | [app__stock-alert-config.png](screens/app__stock-alert-config.png) |
| `/app/reportes/facturas` | 0 | 25 | 15 | 38 | [app__reportes__facturas.png](screens/app__reportes__facturas.png) |
| `/app/correos/reportes` | 0 | 27 | 10 | 37 | [app__correos__reportes.png](screens/app__correos__reportes.png) |
| `/app/devoluciones` | 0 | 28 | 13 | 37 | [app__devoluciones.png](screens/app__devoluciones.png) |
| `/app/reportes/inventario/merma` | 0 | 22 | 7 | 36 | [app__reportes__inventario__merma.png](screens/app__reportes__inventario__merma.png) |
| `/app/reportes/articulos/rendimiento` | 0 | 22 | 9 | 36 | [app__reportes__articulos__rendimiento.png](screens/app__reportes__articulos__rendimiento.png) |
| `/app/perfil` | 0 | 26 | 3 | 36 | [app__perfil.png](screens/app__perfil.png) |
| `/app/reportes/estacionalidad` | 0 | 26 | 11 | 36 | [app__reportes__estacionalidad.png](screens/app__reportes__estacionalidad.png) |
| `/app/caja` | 0 | 27 | 19 | 35 | [app__caja.png](screens/app__caja.png) |
| `/app/tributacion/dashboard` | 0 | 29 | 6 | 34 | [app__tributacion__dashboard.png](screens/app__tributacion__dashboard.png) |
| `/app/etiquetas` | 0 | 27 | 6 | 33 | [app__etiquetas.png](screens/app__etiquetas.png) |
| `/app/tributacion/consultas` | 0 | 26 | 10 | 31 | [app__tributacion__consultas.png](screens/app__tributacion__consultas.png) |
| `/app/reportes/proveedores` | 0 | 26 | 10 | 30 | [app__reportes__proveedores.png](screens/app__reportes__proveedores.png) |
| `/app/tributacion/declaracion` | 0 | 22 | 14 | 29 | [app__tributacion__declaracion.png](screens/app__tributacion__declaracion.png) |
| `/app/settings` | 0 | 23 | 6 | 29 | [app__settings.png](screens/app__settings.png) |
| `/app/compras` | 0 | 20 | 6 | 25 | [app__compras.png](screens/app__compras.png) |
| `/app/pos` | 0 | 13 | 3 | 23 | [app__pos.png](screens/app__pos.png) |
| `/app/comprobantes` | 0 | 12 | 3 | 19 | [app__comprobantes.png](screens/app__comprobantes.png) |
| `/app/dashboard` | 0 | 13 | 6 | 18 | [app__dashboard.png](screens/app__dashboard.png) |
| `/app/pos/standalone` | 0 | 1 | 0 | 8 | [app__pos__standalone.png](screens/app__pos__standalone.png) |

*Extra overflow = total page overflow − 44px global header. A value of 0 means the page itself fits once the header is fixed.
