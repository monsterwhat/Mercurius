# Plan de modernización UI — Entrega 1 (Dashboard + POS)

Rama `ui-modernization`. Solo lectura de código; cambios solo en los templates listados. Sin backend nuevo ni datos ficticios.

## 1. Dashboard (`pages/dashboard/index.html`)

**Composición objetivo (spec §5 A):** encabezado + fila de 4 KPI principales + fila de soporte + 2 gráficos 6+6 + ranking (admin|inventario) + tarjeta de estado.

| Zona | Markup objetivo | Claves reales |
|---|---|---|
| Encabezado | `_kit/page-header` title=`titulo`, subtitle=`fechaActual`; acciones "Facturas Emitidas/Recibidas" gate admin\|facturacion | `titulo`, `fechaActual` |
| KPI fila 1 (4) | `metric-strip`: Ventas hoy (detail `crecimientoDiario`), Ventas ayer, Esta semana, Últimos 30 días | `ventasHoy`, `ventasAyer`, `ventasSemana`, `ventasMes`, `crecimientoDiario` |
| KPI fila 2 (soporte) | `metric-strip`: Transacciones hoy, Artículos vendidos hoy, Ticket promedio (30 días) | `transaccionesHoy`, `articulosVendidos`, `ticketPromedio` |
| Contexto (no-KPI) | 2 métricas + `<p>{ultimaTransaccion}</p>` | `transaccionesHoy`, `articulosVendidos`, `ultimaTransaccion` |
| Gráfico 1 (col 6) | `_kit/chart-card` "Ventas por hora" + canvas `grafica-ventas-hora` + fallback `datos-respaldo-horas` | `hourlyJson` |
| Gráfico 2 (col 6) | `_kit/chart-card` "Tendencia semanal" + canvas `grafica-tendencia-semanal` + fallback `datos-respaldo-semana` | `weeklyJson` |
| Ranking | `_kit/surface` "Productos más vendidos (7 días)" tabla Producto/Unidades/Ingresos | `topProductos` (`nombre`, `unidades`, `ingresos`) |
| Estado | `_kit/surface` "Estado del sistema": última transacción + conexión (shell) + Hacienda no expuesto | `ultimaTransaccion`, `esInventario` |

Mapeo completo (14 claves, todas reales en `DashboardResource.java`):

| Clave | Elemento | Origen |
|---|---|---|
| `ventasHoy` | KPI fila 1 | `DashboardResource.java:142` |
| `ventasAyer` | KPI fila 1 | `:143` |
| `ventasSemana` | KPI fila 1 | `:144` |
| `ventasMes` | KPI fila 1 | `:145` |
| `ticketPromedio` | KPI soporte | `:146` |
| `crecimientoDiario` | detail de "Ventas hoy" ("+x%" o "Sin cambio") | `:147` |
| `transaccionesHoy` | KPI soporte / contexto | `:149` |
| `articulosVendidos` | KPI soporte / contexto | `:150` |
| `ultimaTransaccion` | línea de contexto / tarjeta de estado | `:151` |
| `topProductos` | tabla ranking (gate `esInventario`) | `:153-163` |
| `hourlyJson` | fallback canvas hora | `:167` |
| `weeklyJson` | fallback canvas semana | `:168` |
| `esKpi` | rama KPI+gráficos (admin\|facturacion) | `:113,:120` |
| `esInventario` | rama ranking (admin\|inventario) | `:114,:121` |

Conservar (ya correcto, `index.html`): `{#include layout}` + title/body (`:37-40`); page-header + acciones gate (`:43-56`); metric-strip + `_kit/metric` (`:64-83`); chart-cards con canvas `role="img"`, aria-label y `<p class="is-sr-only">` (`:93-114`); fallback `<script type="application/json">` (`:119-120`); script módulo fetch + fallback (`:122-265`); ranking surface/tabla (`:283-311`); ramas `esKpi`/`esInventario` (`:58,:268,:283`).

Reemplazar/ajustar: disposición 3+4 KPI → fila de 4 principales + fila de soporte; agregar surface "Estado del sistema". No agregar filtro de período en el gráfico hora (backend fijo a hoy). `crecimientoCss` (`DashboardResource.java:148`) queda como auxiliar de color del detail.

No implementable sin backend: (a) filtro de período en "Ventas por hora": `DashboardMetricsService.getHourlySalesDistribution(user, LocalDate.now())` no acepta período y `/data/hourly` es fijo — alternativa mantener feed de hoy o ampliar backend (fuera de esta entrega); (b) estado Hacienda en la tarjeta de estado: no existe clave en el modelo (solo `ultimaTransaccion`) — alternativa mostrar última transacción + punto de conexión del shell y enlazar a `/app/tributacion`, marcando "Hacienda: no expuesto" (patrón UI-8) si se quiere la card.

## 2. POS (`pages/facturas/factura-body.html` + `cart-panel.html`)

**Composición objetivo (spec §5 B):** columna izquierda (tipo de documento + cliente, escaneo, búsqueda de artículos) | columna derecha (carrito, totales, pago, total, Facturar). El layout se logra envolviendo los bloques actuales en `columns` sin cambiar endpoints ni ids.

Ya existe en el template actual: escaneo por código de barras (`forma-escaneo`, `barcode-input`, `btn-agregar-scan`, `factura-body.html:91-128`); búsqueda de artículos vía modal (`modal-articulos`, `articulos-picker`, `:120-122,:170-172`); cliente opcional + picker (`modal-cliente`, `:68-82,:164-165`); pago en modal (`modal-pago`, método/monto, total pagado/vuelto, `cart-panel.html:243-248,:198-201`); total `pos-total-carrito` (`cart-panel.html:251-252`); botones Facturar (`btn-facturar`) y Cancelar (`btn-cancelar`) (`cart-panel.html:255-266`); Descuento/IVA/puntos (`cart-panel.html:183-234`); estado vacío del carrito (`cart-panel.html:88-94`); override de precio (`modal-precio`, `cart-panel.html:269-332`). Encabezado compacto con badge de tipo de cambio (`factura-body.html:41-52`).

No se puede cambiar (fijado por tests/backend): rutas `/app/pos`, `/app/pos/standalone` y todos `/api/app/pos/*` (`PosPageResource.java:32,72`); CSRF `body hx-headers` (`layout.html:43`) y redeclaración en standalone; ids `barcode-input`, `cart-panel`, `btn-facturar`, `btn-cancelar`, `modal-pago`, `modal-cliente`, `modal-precio`, `badge-tipo-cambio`, `forma-tipo-documento`, `facturar-resultado`, `pos-total-carrito`, `puntos-input`, `puntos-preview`, `modal-articulos` (`PosFacturaTemplateTest.java:287-302`); popup `data-kit-popup` que abre `/app/pos/standalone` en `nuevaFactura` (`NavbarPopupLauncherTest.java:104-151`); glue `htmx:afterSwap`/`data-kit-open` (`factura-body.html:186-362`); modal de precio dentro de `cart-panel` para viajar en swaps (`cart-panel.html:269-272`).

Tests que lo fijan: `PosFacturaTemplateTest` (marcadores `:275-303`, fragmento `:308-320`, anónimo `:265-271`, 401 `:324-329`, modales `:298-302`); `PosResourceTest` (endpoints/JSON/CSRF/aislamiento); `RootPathNormalizationTest` (sin literal `/Mercurius`); `NavbarPopupLauncherTest` (popup `:104-151`, sidebar/canvas `:233-245`).

No implementable sin backend: grilla persistente de productos + categorías del spec §5 B (no hay endpoint de listado/filtro por categoría para POS, solo `articulos-picker` y escaneo) — alternativa mantener escaneo + picker modal; añadir endpoint queda fuera de esta entrega. Etiqueta "Cobrar" vs actual "Facturar": cambio de texto posible (tests no fijan el label, solo `btn-facturar`), pero mantener "Facturar" evita sorpresas.

## 3. Archivos (trabajo paralelo sin conflicto)

| Agente | Tocar | No tocar |
|---|---|---|
| Dashboard | `templates/pages/dashboard/index.html` | `DashboardResource.java`, servicios, `DashboardResourceTest.java`, `_kit/*`, `web/index.js`, `web/chart-config.js`, `layout.html` |
| POS | `templates/pages/facturas/factura-body.html`, `templates/pages/facturas/cart-panel.html` (solo contenedores/clases) | `PosPageResource.java`, `PosResource.java`, `factura.html`, `factura-standalone.html`, endpoints, `_kit/modal`, `web/index.js`, tests |
| Compartido | — | `web/mercurius.scss`, `fragments/app-sidebar.html`, `fragments/app-header.html`, `layout.html` |

## 4. Riesgos

| Riesgo | Test asociado | Mitigación |
|---|---|---|
| Renombrar canvas id rompe gráficos y test | `DashboardResourceTest.paginaAutenticadaContieneCanvasYKpis` (`:30-41`) | Mantener ids y fallback |
| Perder fallback JSON deja gráfico en blanco | `paginaAdminIncluyeRespaldoJsonYTopProductos` (`:43-53`) | Mantener `datos-respaldo-*` |
| Cajero ve gráficos si se borra la rama | `cajeroVeResumenBasicoSinGraficas` (`:55-65`) | Mantener `{#if esKpi}` / `{#if !esKpi}` |
| Cambiar launcher rompe popup | `NavbarPopupLauncherTest` (`:104-151,:233-245`) | No tocar header/sidebar |
| Cambiar ids POS rompe templates | `PosFacturaTemplateTest` (`:275-303`) | Preservar ids listados |
| Literal `/Mercurius` en URL rompe build | `RootPathNormalizationTest` (`:72-102`) | Usar `{config:['quarkus.http.root-path']}` |
| Romper standalone (sin layout) quita CSRF/toast | `PosFacturaTemplateTest` (`:275-303` espera `<html`; `:308-320` no `<html`) | No cambiar includes de layout |

## 5. Criterios de aceptación (§18.12, verificables)

Dashboard: `GET /app/dashboard` admin/facturacion contiene `grafica-ventas-hora`, `grafica-tendencia-semanal`, "Ventas hoy", "Ticket promedio (30 días)", `type="module"`, `window.MercuriusCharts` (`DashboardResourceTest:30-41`); admin/inventario contiene fallbacks y "Productos más vendidos (7 días)" (`:43-53`); cajero contiene "Transacciones hoy"/"No hay transacciones hoy" y no canvas (`:55-65`); anónimo 302 (`:67-74`); feeds 24/7 slots y envelope KPI (`:76-143`); un solo `<h1>` vía page-header; tablas con `scope="col"` y columna `num`; sin `/Mercurius` literal (`RootPathNormalizationTest`); sin datos ficticios (fallback solo labels fijos + números de servicio).

POS: page contiene todos los ids fijados (`PosFacturaTemplateTest:287-302`); fragmento cart-panel sin `<html` (`:308-320`) y standalone con `<html` (`:275-303`); anónimo 302 (`:265-271`) y fragmento 401 (`:324-329`); modales `modal-pago`/`modal-cliente`/`modal-precio` presentes y pago muestra Efectivo/Tarjeta (`:633-645`); carrito vacío muestra estado vacío (`:318`); endpoints de scan/qty/remove/client/puntos/payment/facturar/cancel siguen respondiendo (`PosResourceTest`/`PosFacturaTemplateTest`); sin literal root path (`RootPathNormalizationTest`); popup sigue abriendo `/app/pos/standalone` (`NavbarPopupLauncherTest:118-126`).

## 6. Tres decisiones de composición

1. Dashboard: 4 KPI principales en una sola fila (ventas hoy/ayer/semana/mes) y métricas de soporte en segunda fila; no se inventan KPIs nuevos.
2. POS: mantener escaneo + picker modal y solo envolver en dos columnas (captura izquierda, carrito derecha) sin tocar endpoints ni ids.
3. Marcar explícitamente como "no implementable sin backend" el filtro de período del gráfico hora, el estado Hacienda de la tarjeta y la grilla persistente de productos/categorías, en lugar de simularlos.