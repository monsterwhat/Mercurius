# Inventario de deuda visual (read-only)

128 páginas en `templates/pages/` (se excluye `probe.html`). Conteos = `rg -c` por archivo sumados por módulo; 0 = sin ocurrencias. Módulos con más deuda: `articulos` (80), `facturas` (72), `compras` (41) (score = style+hex+table+container+columns+box+banner+stat).

| Módulo | Archivo principal | `.box`/`.card` | `.title.is-*` | banners `*-header` | `stat-card` | `<table` | `_kit/*` | `style=""` | hex | `<form>` | `_kit/modal` | `_kit/page-header` (archivos) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dashboard | dashboard/index.html | 0 | 1 | 0 | 0 | 1 | 18 | 0 | 2 | 0 | 0 | 1 |
| facturas | facturas/factura.html | 8 | 1 | 3 | 0 | 6 | 25 | 43 | 4 | 11 | 4 | 1 |
| facturas-recibidas | facturas-recibidas/index.html | 1 | 8 | 0 | 0 | 2 | 16 | 17 | 1 | 5 | 1 | 1 |
| recibos | recibos/index.html | 1 | 8 | 1 | 0 | 0 | 16 | 13 | 0 | 1 | 0 | 1 |
| devoluciones | devoluciones/index.html | 0 | 1 | 0 | 0 | 1 | 8 | 0 | 0 | 1 | 0 | 1 |
| articulos | articulos/index.html | 1 | 1 | 1 | 3 | 6 | 58 | 52 | 0 | 10 | 10 | 1 |
| categorias | categorias/index.html | 0 | 1 | 0 | 0 | 0 | 29 | 7 | 0 | 6 | 6 | 1 |
| inventario | inventario/index.html | 0 | 0 | 1 | 2 | 1 | 76 | 22 | 1 | 5 | 6 | 2 |
| compras | compras/ordenes.html | 2 | 3 | 0 | 1 | 1 | 35 | 29 | 0 | 4 | 5 | 1 |
| clientes | clientes/index.html | 0 | 0 | 0 | 0 | 0 | 23 | 3 | 0 | 2 | 3 | 1 |
| usuarios | usuarios/index.html | 0 | 0 | 0 | 0 | 0 | 17 | 3 | 0 | 4 | 2 | 1 |
| loyalty | loyalty/index.html | 0 | 4 | 0 | 0 | 1 | 17 | 5 | 6 | 1 | 2 | 1 |
| reportes | reportes/index.html | 0 | 1 | 4 | 1 | 13 | 172 | 4 | 5 | 21 | 3 | 23 |
| tributacion | tributacion/index.html | 14 | 7 | 1 | 0 | 1 | 38 | 6 | 0 | 1 | 0 | 4 |
| cabys | cabys/index.html | 0 | 0 | 0 | 0 | 0 | 11 | 3 | 0 | 1 | 1 | 1 |
| settings | settings/index.html | 11 | 24 | 1 | 0 | 3 | 9 | 5 | 0 | 7 | 0 | 1 |
| perfil | perfil/index.html | 0 | 6 | 0 | 0 | 0 | 8 | 1 | 0 | 3 | 0 | 1 |
| registros | registros/log.html | 0 | 0 | 1 | 1 | 1 | 8 | 1 | 0 | 0 | 0 | 1 |
| tipo-cambio | tipo-cambio/index.html | 5 | 0 | 1 | 0 | 1 | 8 | 3 | 0 | 1 | 0 | 1 |
| correos | correos/templates.html | 0 | 0 | 2 | 1 | 0 | 36 | 6 | 7 | 5 | 6 | 2 |
| gestion | gestion/index.html | 0 | 10 | 0 | 0 | 0 | 21 | 1 | 0 | 0 | 0 | 1 |
| stockalert-config | stockalert-config/index.html | 0 | 0 | 0 | 0 | 0 | 17 | 1 | 0 | 0 | 2 | 1 |
| caja | caja/index.html | 0 | 0 | 3 | 0 | 0 | 20 | 2 | 1 | 2 | 0 | 1 |

## Top 10 por hex literales inline

1. `correos/form-plantilla.html:89` — 7 (`#123`)
2. `loyalty/tabla-top.html:15` — 4 (`#ffd700`)
3. `dashboard/index.html:141` — 2 (`#00d1b2`)
4. `facturas/facturar-resultado.html:3` — 2 (`#fac`)
5. `loyalty/drawer-historial.html:30` — 2 (`#ffffff`)
6. `caja/form-cerrar.html:95` — 1 (`#8353`)
7. `facturas-recibidas/index.html:19` — 1 (`#fac`)
8. `facturas/cart-panel.html:264` — 1 (`#fac`)
9. `facturas/factura-body.html:13` — 1 (`#fac`)
10. `inventario/badges.html:6` — 1 (`#bad`)

## Top 10 por `style="..."`

1. `facturas/cart-panel.html:54` — 22
2. `compras/ordenes-form.html:117` — 17
3. `articulos/form-articulo.html:61` — 12
4. `articulos/detalles.html:28` — 10
5. `articulos/form-promocion.html:113` — 9
6. `articulos/form-revision.html:87` — 8
7. `facturas/factura-body.html:32` — 8
8. `recibos/tabla.html:19` — 6
9. `articulos/index.html:49` — 5
10. `compras/ordenes-detalle.html:79` — 5

## Páginas sin `_kit/page-header` (77)

articulos\buscador-cabys.html, articulos\detalles.html, articulos\form-articulo.html, articulos\form-promocion.html, articulos\form-revision.html, articulos\selector-articulos-ajuste.html, articulos\selector-articulos.html, articulos\tabla-articulos.html, articulos\tabla-promociones.html, cabys\form.html, cabys\tabla.html, caja\estado-caja.html, caja\form-abrir.html, caja\form-cerrar.html, caja\tabla-historial.html, categorias\form-departamento.html, categorias\form-familia.html, categorias\tabla-departamentos.html, categorias\tabla-familias.html, clientes\form.html, clientes\tabla.html, compras\ordenes-articulos.html, compras\ordenes-cancelar.html, compras\ordenes-detalle.html, compras\ordenes-estado.html, compras\ordenes-form.html, compras\ordenes-tabla.html, correos\form-plantilla.html, correos\form-reporte.html, correos\tabla-plantillas.html, correos\tabla-reportes.html, facturas-recibidas\detalle-drawer.html, facturas-recibidas\prevalidacion-panel.html, facturas-recibidas\tabla.html, facturas-recibidas\upload-resultado.html, facturas\articulos-picker.html, facturas\auth-modal-body.html, facturas\cart-panel.html, facturas\client-picker.html, facturas\factura-standalone.html, facturas\factura.html, facturas\facturar-resultado.html, facturas\payment-dialog.html, inventario\badges.html, inventario\detalles-ajuste.html, inventario\form-ajuste.html, inventario\form-rapido.html, inventario\form-revision.html, inventario\tabla-activos.html, inventario\tabla-inactivos.html, inventario\tabla-pendientes.html, inventario\tabla-procesados.html, inventario\upload-resultado.html, loyalty\drawer-historial.html, loyalty\panel-ajustes.html, loyalty\tabla-top.html, perfil\toast.html, recibos\detalle.html, recibos\tabla.html, recibos\tablero.html, reportes\_historial-puntos.html, reportes\_tablas\alertas.html, reportes\_tablas\departamentos.html, reportes\_tablas\etiquetas.html, reportes\_tablas\familias.html, reportes\_tablas\fechas.html, reportes\_tablas\merma.html, reportes\_tablas\precios.html, reportes\_tablas\pronosticos.html, reportes\_tablas\rendimiento.html, reportes\_tablas\resumen.html, reportes\_tablas\tendencias.html, reportes\_tablas\ventas.html, tipo-cambio\fragment.html, tipo-cambio\historial.html, usuarios\form.html, usuarios\tabla.html

## Páginas con `<table>` a mano (26; `(hx)` = tiene hx-*)

articulos\buscador-cabys.html(hx), articulos\detalles.html, articulos\form-promocion.html(hx), articulos\selector-articulos-ajuste.html(hx), articulos\selector-articulos.html(hx), compras\ordenes-detalle.html(hx), dashboard\index.html, devoluciones\index.html(hx), facturas-recibidas\detalle-drawer.html(hx), facturas-recibidas\prevalidacion-panel.html(hx), facturas\articulos-picker.html(hx), facturas\cart-panel.html(hx), facturas\client-picker.html(hx), facturas\payment-dialog.html(hx), inventario\detalles-ajuste.html, loyalty\drawer-historial.html(hx), registros\log.html, reportes\_historial-puntos.html, reportes\estacionalidad.html, reportes\loyalty.html(hx), reportes\margenes.html(hx), reportes\proveedores.html, reportes\ventas-fecha-historica.html, settings\index.html(hx), tipo-cambio\historial.html, tributacion\dashboard.html(hx)

## Páginas con `class="container"` o `class="columns"` (47)

articulos\buscador-cabys.html, articulos\detalles.html, articulos\form-articulo.html, articulos\form-promocion.html, articulos\form-revision.html, articulos\selector-articulos-ajuste.html, articulos\selector-articulos.html, caja\estado-caja.html, caja\form-abrir.html, caja\form-cerrar.html, categorias\form-departamento.html, clientes\form.html, compras\ordenes-cancelar.html, compras\ordenes-detalle.html, compras\ordenes-estado.html, compras\ordenes-form.html, correos\templates.html, dashboard\index.html, devoluciones\index.html, facturas-recibidas\detalle-drawer.html, facturas-recibidas\prevalidacion-panel.html, facturas\articulos-picker.html, facturas\cart-panel.html, facturas\client-picker.html, facturas\factura-body.html, facturas\factura-standalone.html, facturas\payment-dialog.html, gestion\index.html, inventario\form-rapido.html, inventario\form-revision.html, loyalty\drawer-historial.html, loyalty\panel-ajustes.html, perfil\index.html, recibos\detalle.html, registros\log.html, reportes\_historial-puntos.html, reportes\estacionalidad.html, reportes\etiquetas.html, reportes\loyalty.html, reportes\margenes.html, reportes\proveedores.html, reportes\ventas-fecha-historica.html, settings\index.html, tipo-cambio\fragment.html, tipo-cambio\historial.html, tributacion\dashboard.html, tributacion\index.html
