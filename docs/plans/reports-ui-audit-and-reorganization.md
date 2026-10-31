# Reports UI Audit and Reorganization — Specification and Phase 0 Contract

**Project:** Mercurius (`F:\Documents\GitHub\Mercurius`, Maven module in `app/`)
**Stack:** Quarkus 3.36.2, Java 25, Qute + htmx + Bulma, Hibernate ORM, PostgreSQL
**Status:** **Phase 0 completed; Phase 1, Phase 2 item 2, D8, D14 and D15
(export data + role gate) implemented. Decisions D2, D3 (role mapping), D4, D6,
D9, D11, D12 and D13 are still open.**
**Baseline:** `d25f9a4` (working tree clean on 2026-10-31). Line numbers are anchors,
not contracts: re-anchor by symbol if a file moved.
**Audience:** the agent that will execute Phase 0 and, later, the reorganization; the
product owner for the decisions in §7.
**Precedence:** this document > ad-hoc UI opinions. A decision in §7 beats an agent's
judgement. If Phase 0 finds a conflict with this document, stop and update the document
before coding.

---

## 0. How to use this document

1. **Phase 0 produces the matrices. This document only defines their shape.**
   Do not treat the examples in §5–§6 as a populated inventory.
2. **Never infer a screen's type from its label.** Read the controller, the template,
   the service root and the role annotations.
3. **One row = one responsibility.** A page that hosts tabs may produce several rows
   in Matriz A (see §2 and §6).
4. **No implementation starts until Matriz A and Matriz B are complete** and every
   blocking decision in §7 has an owner and a status. Phase 1 is the only exception,
   and it is a one-link hotfix (§8.2).
5. **Line numbers in this document were measured on the baseline commit.** If a
   `git status` shows edits, re-read the file before acting.

---

## 1. Scope and exclusions

### 1.1 In scope

| Surface | Where | Why |
|---|---|---|
| Reports hub | `GET /app/reportes` (`templates/pages/reportes/index.html`) | 32 entries, 4 groups; the primary object of the audit |
| Main navbar | `templates/fragments/navbar.html` | Contains business links and a dropdown that mixes management and reports |
| Dashboard | `templates/pages/dashboard/index.html` | Two cross-module buttons: emitted and received invoices |
| Related screens outside the hub | `/app/facturas`, `/app/facturas-recibidas`, `/app/usuarios`, `/api/app/clientes/table`, `/app/correos/reportes`, `/app/categorias` | They are the other half of the duplicate pairs; an audit of the hub alone cannot resolve §5 |
| In-page cross-module links | e.g. "Ir a facturas recibidas" in `reportes/recibos-pendientes.html` and `reportes/recibos-vencidos.html` | They are navigation accesses and can become stale |

### 1.2 Out of scope

- Authentication, login, logout, session, CSRF.
- POS (`/app/pos`, `/app/pos/standalone`) and "Nueva Factura".
- Profile (`/app/perfil`).
- Backups (`/app/backups`).
- Global application settings (`/app/aplicacion`, `/app/settings`).
- Mercatus / Marketplace surfaces.
- Public API routes not reached by these screens.
- Visual redesign (colors, spacing, components). The reorganization changes
  **navigation and screen boundaries**, not the visual language.
- Business-logic changes. Every mutation must keep its current behavior and gate.

If an excluded surface duplicates an in-scope access, record it in Matriz B as
`out-of-scope, observed`, and do not act on it.

---

## 2. Definitions and the unit of audit

**Canonical functional screen.** A responsibility with its own data root, operations,
permissions and exports. It may share a URL with other screens (tabs), but it has one
canonical name and one ID.

**Container page.** A route that hosts several canonical screens through tabs or a
section switch (e.g. `/app/categorias` hosts Familias, Departamentos and the supplier
ranking). The container is not itself a canonical screen; it is a navigation
mechanism.

**Access.** A link or button in a navigation surface that points to a screen.

**Navigation surface.** `hub`, `navbar`, `dashboard`, `in-page`.

**Service root.** The entity/service that backs a screen. It is the strongest
duplication signal we have, and it is **only a signal** (§5.2).

**ID convention.** Stable, never reused:

| Prefix | Meaning | Example shape |
|---|---|---|
| `rep.` | Report (read-only) | `rep.ventas`, `rep.inventario.analisis` |
| `ges.` | Management (mutates) | `ges.articulos`, `ges.proveedores.ranking` |
| `tool.` | Tool (does not manage records) | `tool.etiquetas` |

A screen's ID is independent of its URL and of its group in the hub.

---

## 3. Matriz A — Canonical functional screens

**One row per canonical screen or responsibility.** Mixed pages are decomposed
(§6). There is no `Mixta` type: if a page mixes responsibilities, it produces
several rows and one `Split` action.

### 3.1 Schema

| # | Field | Required | Content |
|---|---|---|---|
| A1 | `id` | yes | Canonical ID (`rep.*`, `ges.*`, `tool.*`) |
| A2 | `nombre_funcional` | yes | The name the screen *should* have |
| A3 | `nombre_actual` | yes | Visible title today (page header / hub label) |
| A4 | `contenedor` | yes | URL of the page that hosts it (may equal `url_principal`) |
| A5 | `url_principal` | yes | Canonical route |
| A6 | `urls_alternativas` | no | Redirects, API paths used as pages, query-tab variants |
| A7 | `controlador` | yes | Java resource class |
| A8 | `plantilla` | yes | Qute template |
| A9 | `servicio_raiz` | yes | Entity + service (the duplication fingerprint) |
| A10 | `tipo` | yes | `Reporte` / `Gestión` / `Herramienta` |
| A11 | `pestanas` | no | Internal views, with their query keys |
| A12 | `operaciones` | yes | `Consultar` / `Crear` / `Editar` / `Eliminar` / `Procesar` / `Exportar` |
| A13 | `roles` | yes | `admin`, `usuario`, `facturacion`, `tributacion`, `inventario`, `registro` |
| A14 | `exportaciones` | yes | `XLSX` / `PDF` / `Ninguna` **plus the dataset key** |
| A15 | `dependencias` | yes | Services, endpoints, shared datasets, redirects, tests |
| A16 | `estado` | yes | `OK` / `Roto` / `Redirect` / `403` / `Parcial` |
| A17 | `accion` | yes | `Mantener` / `Mover` / `Fusionar` / `Corregir` / `Renombrar` / `Dividir` / `Crear` / `Eliminar` |

`Dividir` applies to the container row when a mixed page is decomposed. `Crear`
applies to a responsibility that has no screen of its own today (e.g. the supplier
ranking). `Eliminar` is only valid after the capability-preservation check in §9
and an explicit decision in §7.

### 3.2 Row template

```
| <id> | <nombre_funcional> | <nombre_actual> | <contenedor> | <url_principal> |
<urls_alternativas> | <controlador> | <plantilla> | <entidad> / <servicio> | <tipo> |
<pestañas> | <operaciones> | <roles> | <export + dataset> | <dependencias> | <estado> |
<acción> |
```

### 3.3 Fill rules

- **Read the annotations.** `@RolesAllowed` / `@Authenticated` on the class is the
  source of truth for A13; method-level annotations override the class.
- **Record the effective gate.** The `@RolesAllowed` on `ClientsResource` is
  `admin,usuario`, but the report page is `@Authenticated`; both facts belong in A13
  or in the dependency note.
- **Record the dataset key, not just "XLSX".** `/api/app/export` is dataset-driven;
  losing the key loses the export.
- **Record redirects.** A screen reached through a 302 is still that screen; the
  redirect belongs in A6, not as a separate row.
- **One responsibility = one row, even if two URLs render it.** If two routes render
  the same responsibility, pick the canonical one and list the other in A6.

### 3.4 Matriz A — populated (Phase 0, baseline `d25f9a4`)

#### 3.4.1 Identity

| ID | Nombre funcional | Nombre actual | Contenedor | URL principal | Controlador | Plantilla | Servicio raíz | Tipo |
|---|---|---|---|---|---|---|---|---|
| `rep.ventas` | Reporte de Ventas | Reporte de Ventas por Cajero / hub "Facturación de Artículos" | grupo ventas/fechas/departamentos/familias | `/app/reportes/articulos/ventas` | `VentasResource`, `DepartamentosResource`, `FamiliasResource` | `pages/reportes/ventas`, `departamentos`, `familias` | `ComprobantesEmitidosService` / `InventarioService` | Reporte |
| `rep.inventario.movimientos` | Movimientos de Inventario | Reporte de Movimientos de Inventario / hub "Movimientos" | mismo grupo | `/app/reportes/articulos/fechas` | `FechasResource` | `pages/reportes/fechas` | `InventarioService` | Reporte |
| `rep.tendencias` | Tendencias y Estacionalidad | Tendencias de Ventas / Análisis de Estacionalidad | grupo tendencias/estacionalidad | `/app/reportes/articulos/tendencias` | `TendenciasResource`, `EstacionalidadReportesResource` | `pages/reportes/tendencias`, `estacionalidad` | `SalesTrendService` / `SeasonalityService` | Reporte |
| `rep.ventas.historica` | Ventas por Fecha Histórica | Ventas por Fecha Histórica | — | `/app/reportes/articulos/ventas-fecha-historica` | `HistoricalDateSalesResource` | `pages/reportes/ventas-fecha-historica` | `ArticulosService` / `SalesTrendService` | Reporte |
| `rep.productos.destacados` | Productos Destacados | Productos Destacados | — | `/app/reportes/articulos/rendimiento` | `RendimientoResource` | `pages/reportes/rendimiento` | `ProductPerformanceService` | Reporte |
| `rep.facturas.emitidas` | Reporte de Facturas Emitidas | Facturas Emitidas / hub "Todas las Facturas" | — | `/app/reportes/facturas` | `FacturasReportesResource` | `pages/reportes/facturas` | `ComprobantesEmitidosService` | Reporte |
| `ges.facturas.emitidas` | Facturas Emitidas (gestión) | Facturas Emitidas | — | `/app/facturas` | `RecibosPagesResource`, `RecibosResource` | `pages/recibos/index`, `tablero` | `ComprobantesEmitidosService` | Gestión |
| `rep.facturas.recibidas.aging` | Antigüedad de Facturas Recibidas | Facturas Recibidas Pendientes / Vencidas | grupo pendientes/vencidos | `/app/reportes/recibos/pendientes` | `RecibosReportesResource` | `pages/reportes/recibos-pendientes`, `recibos-vencidos` | `ComprobantesRecibidosService` | Reporte |
| `ges.facturas.recibidas` | Facturas Recibidas / Cuentas por Pagar | Facturas Recibidas | — | `/app/facturas-recibidas` | `FacturasRecibidasPagesResource`, `FacturasRecibidasResource` | `pages/facturas-recibidas/index`, `tabla` | `ComprobantesRecibidosService` | Gestión |
| `rep.correos.programados` | Reportes Programados por Correo (consulta) | Reportes por Correo / Reportes Programados por Correo | — | `/app/reportes/correos` | `CorreosReportesResource` | `pages/reportes/correos` | `ReportesProgramadosService` | Reporte |
| `ges.correos.programados` | Reportes Programados por Correo (gestión) | Reportes Programados por Correo | — | `/app/correos/reportes` | `CorreosPagesResource`, `ReporteProgramadoResource` | `pages/correos/reportes` | `ReportesProgramadosService` | Gestión |
| `ges.correos.plantillas` | Plantillas de Correo | Plantillas de Correo Electrónico | — | `/app/correos/plantillas` | `CorreosPagesResource`, `EmailTemplateResource` | `pages/correos/templates` | `EmailTemplateService` | Gestión |
| `tool.etiquetas` | Generar Etiquetas | Generar Etiquetas | — | `/app/reportes/inventario/etiquetas` | `EtiquetasResource` | `pages/reportes/etiquetas` | `ArticulosService` | Herramienta |
| `rep.inventario.analisis` | Análisis de Inventario | Resumen de Inventario / Alertas de Stock / Pronósticos de Stock | grupo resumen/pronosticos/alertas | `/app/reportes/inventario/resumen` | `ResumenResource`, `AlertasStockResource`, `PronosticosResource` | `pages/reportes/resumen`, `alertas`, `pronosticos` | `InventarioService` / `StockAlertService` / `StockForecastService` | Reporte |
| `rep.inventario.margenes` | Márgenes de Utilidad | Análisis de Márgenes de Utilidad | — | `/app/reportes/margenes` | `MargenesReportesResource` | `pages/reportes/margenes` | `ProfitAnalysisService` | Reporte |
| `rep.inventario.mermas` | Mermas y Pérdidas | Control de Mermas y Pérdidas | — | `/app/reportes/inventario/merma` | `MermaResource` | `pages/reportes/merma` | `ShrinkageAnalysisService` | Reporte |
| `rep.inventario.precios` | Histórico de Precios | Histórico de Precios | — | `/app/reportes/inventario/precios` | `PreciosResource` | `pages/reportes/precios` | `ArticuloPrecioService` | Reporte |
| `ges.articulos` | Artículos | Artículos | `/app/articulos` | `/app/articulos` | `ArticulosPagesResource`, `ArticuloResource` | `pages/articulos/index` | `ArticulosService` | Gestión |
| `ges.promociones` | Promociones | Promociones | `/app/articulos` | `/app/articulos` (tab) | `ArticuloResource` | `pages/articulos/index`, `tabla-promociones` | `PromocionesService` | Gestión |
| `ges.inventarios` | Inventarios | Gestión de Inventario | — | `/app/inventarios` | `InventariosPagesResource`, `InventarioResource` | `pages/inventario/index` | `InventarioService` | Gestión |
| `ges.categorias.familias` | Familias | Familias | `/app/categorias` | `/app/categorias` (tab familias) | `CategoriasPagesResource`, `CategoriaResource` | `pages/categorias/index` | `FamiliaService` | Gestión |
| `ges.categorias.departamentos` | Departamentos | Departamentos | `/app/categorias` | `/app/categorias` (tab departamentos) | `CategoriasPagesResource`, `CategoriaResource` | `pages/categorias/index` | `DepartamentoService` | Gestión |
| `rep.proveedores.ranking` | Ranking de Rendimiento de Proveedores | Ranking de Rendimiento de Proveedores | `/app/categorias` | `/app/categorias` (sección) | `CategoriaResource` (a extraer) | `pages/categorias/index` (a extraer) | `DepartamentoMetricoService` | Reporte |
| `ges.usuarios` | Usuarios del Sistema | Usuarios del Sistema | — | `/app/usuarios` | `UsuariosPagesResource`, `UsersResource` | `pages/usuarios/index` | `LoginService` | Gestión |
| `rep.usuarios` | Reporte de Usuarios | Gestión de Usuarios | — | `/app/reportes/usuarios` | `UsuariosReportesResource` | `pages/reportes/usuarios` | `LoginService` | Reporte |
| `ges.clientes` | Gestión de Clientes | Gestión de Clientes | — | `/api/app/clientes/table` | `ClientsResource` | `pages/clientes/index` | `ClientService` | Gestión |
| `rep.clientes` | Reporte de Clientes | Reportes de Clientes | — | `/app/reportes/clientes` | `ClientesReportesResource` | `pages/reportes/clientes` | `ClientService` | Reporte (CRUD filtrado) |
| `rep.loyalty` | Puntos y Recompensas | Puntos y Recompensas | — | `/app/reportes/loyalty` | `LoyaltyReportesResource` | `pages/reportes/loyalty` | `LoyaltyService` | Reporte |
| `ges.compras.ordenes` | Órdenes de Compra | Órdenes de Compra | — | `/api/app/ordenes/table` | `OrdenCompraResource` | `pages/compras/ordenes` | `OrdenCompraService` | Gestión |
| `tool.registros` | Registros Internos | Registros Internos / Registro de actividades | — | `/app/registros/log` | `MiscPagesResource` | `pages/registros/log` | logs del sistema | Herramienta |
| `rep.fiscal.iva` | Declaración IVA (D-104) | Resumen de Impuestos / Declaración IVA (D-104) | — | `/app/tributacion/declaracion` | `TributacionPagesResource` | `pages/tributacion/declaracion` | `ComprobantesEmitidosService` / `ComprobantesRecibidosService` | Reporte |
| `rep.tributacion.dashboard` | Dashboard Hacienda | Dashboard Hacienda | — | `/app/tributacion/dashboard` | `TributacionPagesResource` | `pages/tributacion/dashboard` | `ComprobantesEmitidosService` / `ComprobantesRecibidosService` | Reporte |
| `rep.tributacion.consultas` | Consultas de Facturas | Consultas de Facturas | — | `/app/tributacion/consultas` | `TributacionPagesResource` | `pages/tributacion/consultas` | `ComprobantesEmitidosService` / `ComprobantesRecibidosService` | Reporte |
| `ges.cabys` | CaByS | CaByS | — | `/app/cabys` | `CabysPagesResource`, `CabysResource` | `pages/cabys/index` | `CabysService` | Gestión |

Navigation containers (not screens; listed so Matriz B has a join target):

| ID | Nombre | URL | Controlador | Plantilla | Tipo |
|---|---|---|---|---|---|
| `nav.hub` | Hub de Reportes | `/app/reportes` | `MiscPagesResource` | `pages/reportes/index` | Contenedor |
| `nav.dashboard` | Dashboard | `/app/dashboard` | `DashboardResource` | `pages/dashboard/index` | Contenedor |

Out-of-scope screens (listed for referential integrity; do not act on them):

| ID | Nombre | URL | Motivo |
|---|---|---|---|
| `oos.home` | Landing / logo | `/app` | Redirección de entrada; fuera de alcance. |
| `oos.pos` | POS / Nueva Factura | `/app/pos`, `/app/pos/standalone` | Excluido (§1.2) |
| `oos.perfil` | Perfil | `/app/perfil` | Excluido |
| `oos.aplicacion` | Menú de aplicación | `/app/aplicacion` | Excluido |
| `oos.respaldos` | Respaldos | `/app/backups` | Excluido |
| `oos.tipo-cambio` | Tipo de cambio (modal) | `/app/tipo-cambio/fragment` | Excluido (configuración) |
| `oos.auth` | Login/logout/sesión | `/api/app/auth/*` | Excluido |

#### 3.4.2 Behaviour

| ID | Pestañas / vistas | Operaciones | Roles | Exportación (dataset) | Estado | Acción | Notas |
|---|---|---|---|---|---|---|---|
| `rep.ventas` | Por cajero, Por departamento, Por familia | Consultar, Exportar | `admin`, `inventario` | PDF `articulos`; XLSX `reportes-departamentos`, `reportes-familias` | OK | Dividir | El hub tiene 3 entradas para esta pantalla; "Por fecha" es otra responsabilidad. |
| `rep.inventario.movimientos` | Por fecha (misma barra de pestañas) | Consultar, Exportar | `admin`, `inventario` | XLSX `reportes-movimientos` (**404**) | Roto | Mover, Corregir | Export roto; el label "Movimientos" es vago; pertenece a Inventario. |
| `rep.tendencias` | Tendencias (diarias/semanales/mensuales/indicadores), Estacionalidad | Consultar, Exportar | `admin`, `inventario`; Estacionalidad: cualquier autenticado | XLSX `tendencias`, `estacionalidad` | Parcial | Corregir | Gate asimétrico entre las dos pestañas; el hub tiene 2 entradas. |
| `rep.ventas.historica` | — | Consultar, Exportar | `admin`, `inventario` | XLSX `ventas-fecha-historica` | OK | Mantener | — |
| `rep.productos.destacados` | Más vendidos, Menos vendidos, Por ingresos, Por departamento | Consultar, Exportar | `admin`, `inventario` | XLSX `rendimiento` | OK | Mantener | — |
| `rep.facturas.emitidas` | — | Consultar, Exportar | `admin`, `facturacion` | XLSX `facturas` | OK | Renombrar | D7: "Reporte de facturas emitidas". |
| `ges.facturas.emitidas` | Todas, Pagadas, Procesadas, Vencidos | Consultar, Procesar, Pagar, Aceptar/Rechazar, Desactivar, Cargar XML | `admin`, `facturacion` | ninguna | Parcial | Corregir, Renombrar | El upload postea a recibidas; D7: "Facturas emitidas (gestión)". |
| `rep.facturas.recibidas.aging` | Pendientes, Vencidos | Consultar, Exportar | `admin`, `facturacion` | XLSX `recibos-pendientes`, `recibos-vencidos` | OK | Pendiente (D2) | Propuesto: fusionar en `ges.facturas.recibidas`; preservar XLSX. |
| `ges.facturas.recibidas` | Todas, Activas, Pagadas, Procesadas, Pendientes, Vencidas | Consultar, Cargar XML, Procesar, Pagar, Desactivar, Prevalidar, MensajeReceptor | `admin`, `facturacion` | ninguna | Parcial | Mantener | D14: `/app/facturas-recibidas` is now a real page over the same model as `/api/app/facturas-recibidas/tabla`; the upload is the only visible one. No export; absorb aging (D2). |
| `rep.correos.programados` | — | Consultar, Exportar | `admin`, `facturacion` | XLSX `correos` | OK | Pendiente (D4) | Propuesto: fusionar con la gestión; los gates difieren. |
| `ges.correos.programados` | — | Consultar, Crear, Editar, Toggle, Eliminar | `admin`, `tributacion` | ninguna | OK | Pendiente (D4) | Sin export; gate distinto al reporte. |
| `ges.correos.plantillas` | — | Consultar, Crear, Editar, Toggle, Eliminar | `admin`, `tributacion` | ninguna | OK | Pendiente (D9) | Acceso duplicado hub y navbar: una sola entrada. |
| `tool.etiquetas` | — | Consultar, Generar PDF | `admin`, `inventario` | PDF `etiquetas` | OK | Mover | Herramienta, no reporte. |
| `rep.inventario.analisis` | Resumen, Pronósticos, Alertas; Salud, Reorden | Consultar, Exportar | `admin`, `inventario` | XLSX `inventario-resumen` (**404**), `stock-alerts`, `pronosticos` | Roto | Corregir | 3 pestañas = 1 pantalla; export del resumen roto. |
| `rep.inventario.margenes` | Resumen, Top, Bajo margen | Consultar, Exportar | `admin`, `facturacion` | XLSX `profit-margins` | OK | Mantener | — |
| `rep.inventario.mermas` | Detalle, Causas, Departamentos | Consultar, Exportar | `admin`, `inventario` | XLSX `merma` | OK | Mantener | — |
| `rep.inventario.precios` | — | Consultar, Exportar | `admin`, `inventario` | XLSX `precios` | OK | Mantener | — |
| `ges.articulos` | Activos, Inactivos, Procesados, Pendientes | Consultar, Crear, Editar, Procesar, Exportar | `admin`, `inventario` | XLSX/PDF `articulos` (tab ignorado) | Parcial | Mover | D8 resuelto en `e2cdf20` (badge = tabla); el export sigue ignorando `tab` (D15). |
| `ges.promociones` | Promociones | Consultar, Crear, Editar, Eliminar, Exportar | `admin`, `inventario` | XLSX `promociones` (**404**) | Roto | Corregir | Entidad aparte; acceso vía pestaña de Artículos; export roto. |
| `ges.inventarios` | Activos, Pendientes, Procesados, Inactivos | Consultar, Crear, Procesar, Reabrir, Cargar XML, Exportar | `admin`, `inventario` | PDF `inventario` | OK | Mover | Gestión, no reporte. |
| `ges.categorias.familias` | Familias | Consultar, Crear, Editar, Toggle, Exportar | `admin`, `inventario` | XLSX `familias` | OK | Mover | `?tab=` leído/escrito desde `486c343`. |
| `ges.categorias.departamentos` | Departamentos | Consultar, Crear, Editar, Toggle, Exportar | `admin`, `inventario` | XLSX `departamentos` | OK | Mover | `?tab=departamentos` abre la pestaña correcta desde `486c343`. |
| `rep.proveedores.ranking` | — | Consultar, Recalcular (admin) | `admin`, `inventario` | ninguna | Parcial | Crear (D5) | Sin ruta propia; vive dentro de categorías; tope 200 filas. |
| `ges.usuarios` | — | Consultar, Crear, Editar, Toggle, Password | `admin`, `usuario` | ninguna | OK | Pendiente (D3) | Sin export. |
| `rep.usuarios` | — | Consultar, Exportar | `admin` | XLSX `usuarios` | OK | Pendiente (D3) | Duplica el listado; conservar export. |
| `ges.clientes` | — | Consultar, Crear, Editar, Archivar | `admin`, `usuario` | ninguna | OK | Pendiente (D3) | Sin export. |
| `rep.clientes` | — | Consultar, Exportar; Crear/Editar visibles | cualquier autenticado | XLSX `clientes` | Parcial | Corregir, Pendiente (D3) | Botones CRUD para cualquier autenticado; mutaciones `admin/usuario`. |
| `rep.loyalty` | — | Consultar, Exportar | cualquier autenticado | XLSX `loyalty` | OK | Mantener | — |
| `ges.compras.ordenes` | — | Consultar, Crear, Editar, Estado, Recibir, Cancelar | `admin`, `inventario` | ninguna | Parcial | Renombrar, Mover | URL de API; no exporta. |
| `tool.registros` | — | Consultar, Descargar | `admin`, `registro` | ninguna | OK | Pendiente (D9) | Acceso duplicado hub y navbar; logs del sistema. |
| `rep.fiscal.iva` | — | Consultar | `admin`, `tributacion` | ninguna | OK | Renombrar, Mover | Es D-104, no "resumen"; grupo Fiscal. |
| `rep.tributacion.dashboard` | — | Consultar | `admin`, `tributacion` | ninguna | OK | Mantener | Solo navbar; no está en el hub. |
| `rep.tributacion.consultas` | — | Consultar | `admin`, `tributacion` | ninguna | OK | Mantener | Solo navbar; no está en el hub. |
| `ges.cabys` | — | Consultar, Crear, Editar | `admin`, `tributacion` (página) / `admin`, `inventario` (API) | ninguna | Parcial | Corregir | Gate asimétrico página/API. |

---

## 4. Matriz B — Accesses and destinations

**One row per access.** This matrix is what turns "the button is wrong" into a
verifiable statement.

### 4.1 Schema

| # | Field | Required | Content |
|---|---|---|---|
| B1 | `ubicacion` | yes | `hub` / `navbar` / `dashboard` / `in-page` |
| B2 | `grupo` | no | Hub group or navbar dropdown |
| B3 | `etiqueta` | yes | Visible text |
| B4 | `url_actual` | yes | The `href` as rendered today |
| B5 | `url_final` | yes | The intended `href` after the fix |
| B6 | `id_canonico` | yes | Exactly one ID from Matriz A |
| B7 | `rol_visible` | yes | Role(s) that see the access today |
| B8 | `rol_destino` | yes | Role(s) the destination actually requires |
| B9 | `estado` | yes | `OK` / `Roto` / `Redirect` / `Parcial` / `Duplicado` / `403` |
| B10 | `accion` | yes | `Mantener` / `Corregir` / `Renombrar` / `Mover` / `Crear` / `Eliminar`, or `Pendiente (Dx)` when a product decision blocks it |
| B11 | `notas` | no | Evidence, temporary state, dependencies |

### 4.2 Fill rules

- Include **every** access: hub, navbar, dashboard and in-page cross-module links.
- The hub has **32 entries with role `admin`** and **31 without it** (the admin-only
  "Registros Internos" link is conditionally rendered, `reportes/index.html:275`).
  State the role basis of the audit run.
- `url_final` must be a page route, not an API fragment used as a page, unless the
  access is explicitly accepted as an exception (e.g. `/api/app/ordenes/table` is
  dual-mode and renders a full page; decide and record it).
- `rol_destino` is not decorative: it is what catches a link that resolves but
  produces a 403 for the role that can see it.
- If an access is removed, the screen must still have another access (criterion 5)
  and the capability check in §9 must pass.

### 4.3 Row template

```
| <ubicacion> | <grupo> | <etiqueta> | <url_actual> | <url_final> | <id_canonico> |
<rol_visible> | <rol_destino> | <estado> | <accion> | <notas> |
```

### 4.4 Matriz B — hub populated (32 entries, admin view)

`Rol visible` is the set that can open `/app/reportes`
(`MiscPagesResource`: `admin`, `registro`, `inventario`, `tributacion`). An entry is
`Parcial` when at least one of those roles gets 403 at the destination. See §A.7 for
the per-role count.

| # | Grupo | Etiqueta | URL actual | URL final | ID canónico | Rol visible | Rol destino | Estado | Acción | Notas |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Facturación | Movimientos | `/app/reportes/articulos/fechas` | `/app/reportes/articulos/fechas` | `rep.inventario.movimientos` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mover, Renombrar | 403 para registro/tributación; label vago; export 404. |
| 2 | Facturación | Facturación de Artículos | `/app/reportes/articulos/ventas` | `/app/reportes/articulos/ventas` | `rep.ventas` | admin, registro, inventario, tributación | admin, inventario | OK | Mantener | Entrada canónica de la pantalla de ventas. |
| 3 | Facturación | Ventas por Familia | `/app/reportes/articulos/familias` | `/app/reportes/articulos/ventas` | `rep.ventas` | admin, registro, inventario, tributación | admin, inventario | Duplicado | Eliminar | Es la pestaña "Por familia". |
| 4 | Facturación | Ventas por Departamento | `/app/reportes/articulos/departamentos` | `/app/reportes/articulos/ventas` | `rep.ventas` | admin, registro, inventario, tributación | admin, inventario | Duplicado | Eliminar | Es la pestaña "Por departamento". |
| 5 | Facturación | Todas las Facturas | `/app/reportes/facturas` | `/app/reportes/facturas` | `rep.facturas.emitidas` | admin, registro, inventario, tributación | admin, facturacion | Parcial | Renombrar | D7: "Reporte de facturas emitidas". |
| 6 | Facturación | Tendencias de Ventas | `/app/reportes/articulos/tendencias` | `/app/reportes/articulos/tendencias` | `rep.tendencias` | admin, registro, inventario, tributación | admin, inventario | OK | Mantener | Entrada canónica; Estacionalidad es pestaña. |
| 7 | Facturación | Ventas por Fecha Histórica | `/app/reportes/articulos/ventas-fecha-historica` | `/app/reportes/articulos/ventas-fecha-historica` | `rep.ventas.historica` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mantener | — |
| 8 | Facturación | Productos Destacados | `/app/reportes/articulos/rendimiento` | `/app/reportes/articulos/rendimiento` | `rep.productos.destacados` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mantener | — |
| 9 | Facturación | Estacionalidad | `/app/reportes/estacionalidad` | `/app/reportes/articulos/tendencias` | `rep.tendencias` | admin, registro, inventario, tributación | admin, inventario (contenedor) | Duplicado | Eliminar | Pestaña de Tendencias. |
| 10 | Facturación | Resumen de Impuestos | `/app/tributacion/declaracion` | `/app/tributacion/declaracion` | `rep.fiscal.iva` | admin, registro, inventario, tributación | admin, tributacion | Parcial | Renombrar, Mover | Es la D-104; grupo Fiscal. |
| 11 | Facturas y correo | Facturas emitidas | `/app/recibos` | `/app/facturas` | `ges.facturas.emitidas` | admin, registro, inventario, tributación | admin, facturacion | OK | Renombrar | Corregido en `2ab771e`; D7 naming. |
| 12 | Facturas y correo | Facturas recibidas pendientes de pago | `/app/reportes/recibos/pendientes` | (D2) `/app/facturas-recibidas?bucket=pendientes` | `rep.facturas.recibidas.aging` | admin, registro, inventario, tributación | admin, facturacion | Parcial | Pendiente (D2) | Preservar XLSX; la página real ya existe (D14). |
| 13 | Facturas y correo | Facturas recibidas vencidas | `/app/reportes/recibos/vencidos` | (D2) `/app/facturas-recibidas?bucket=vencidas` | `rep.facturas.recibidas.aging` | admin, registro, inventario, tributación | admin, facturacion | Parcial | Pendiente (D2) | Preservar XLSX; la página real ya existe (D14). |
| 14 | Facturas y correo | Reportes por Correo | `/app/reportes/correos` | (D4) `/app/correos/reportes` | `rep.correos.programados` | admin, registro, inventario, tributación | admin, facturacion | Parcial | Pendiente (D4) | Gate distinto; preservar XLSX. |
| 15 | Facturas y correo | Plantillas de Correo | `/app/correos/plantillas` | `/app/correos/plantillas` | `ges.correos.plantillas` | admin, registro, inventario, tributación | admin, tributacion | Duplicado | Pendiente (D9) | Misma ruta que navbar: una de las dos entradas se elimina. |
| 16 | Facturas y correo | Registros Internos | `/app/registros/log` | `/app/registros/log` | `tool.registros` | admin | admin, registro | OK | Pendiente (D9) | Misma ruta que navbar: una de las dos entradas se elimina. |
| 17 | Inventarios | Artículos | `/app/articulos` | `/app/articulos` | `ges.articulos` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mover | Gestión; badge Catálogo (D8). |
| 18 | Inventarios | Resumen de Inventario | `/app/reportes/inventario/resumen` | `/app/reportes/inventario/resumen` | `rep.inventario.analisis` | admin, registro, inventario, tributación | admin, inventario | OK | Mantener | Entrada canónica de las 3 pestañas. |
| 19 | Inventarios | Departamentos | `/app/categorias` | `/app/categorias?tab=departamentos` | `ges.categorias.departamentos` | admin, registro, inventario, tributación | admin, inventario | OK | Mover | Corregido en `486c343`: `?tab=` leído/escrito; abre Departamentos. |
| 20 | Inventarios | Familias | `/app/categorias` | `/app/categorias?tab=familias` | `ges.categorias.familias` | admin, registro, inventario, tributación | admin, inventario | OK | Mover | Corregido en `486c343`. |
| 21 | Inventarios | Inventarios | `/app/inventarios` | `/app/inventarios` | `ges.inventarios` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mover | Gestión. |
| 22 | Inventarios | Generar Etiquetas | `/app/reportes/inventario/etiquetas` | `/app/reportes/inventario/etiquetas` | `tool.etiquetas` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mover | Herramienta. |
| 23 | Inventarios | Márgenes de Utilidad | `/app/reportes/margenes` | `/app/reportes/margenes` | `rep.inventario.margenes` | admin, registro, inventario, tributación | admin, facturacion | Parcial | Mantener | Solo admin del hub puede abrirlo. |
| 24 | Inventarios | Alertas de Stock | `/app/reportes/inventario/alertas` | `/app/reportes/inventario/resumen` | `rep.inventario.analisis` | admin, registro, inventario, tributación | admin, inventario | Duplicado | Eliminar | Pestaña de Análisis. |
| 25 | Inventarios | Pronósticos de Stock | `/app/reportes/inventario/pronosticos` | `/app/reportes/inventario/resumen` | `rep.inventario.analisis` | admin, registro, inventario, tributación | admin, inventario | Duplicado | Eliminar | Pestaña de Análisis. |
| 26 | Inventarios | Mermas y Pérdidas | `/app/reportes/inventario/merma` | `/app/reportes/inventario/merma` | `rep.inventario.mermas` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mantener | — |
| 27 | Inventarios | Histórico de precios | `/app/reportes/inventario/precios` | `/app/reportes/inventario/precios` | `rep.inventario.precios` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mantener | — |
| 28 | Inventarios | Órdenes de Compra | `/api/app/ordenes/table` | `/app/compras/ordenes` | `ges.compras.ordenes` | admin, registro, inventario, tributación | admin, inventario | Parcial | Renombrar, Mover | Ruta de API; Gestión. |
| 29 | Inventarios | Métricas de proveedores | `/app/categorias` | `/app/reportes/proveedores` | `rep.proveedores.ranking` | admin, registro, inventario, tributación | admin, inventario | Parcial | Mover | Aterriza en la parte superior de categorías; ranking abajo. |
| 30 | Usuarios | Usuarios Detallados | `/app/reportes/usuarios` | (D3) `/app/usuarios` o mantener | `rep.usuarios` | admin, registro, inventario, tributación | admin | Parcial | Pendiente (D3) | Conservar XLSX. |
| 31 | Usuarios | Clientes | `/app/reportes/clientes` | (D3) `/api/app/clientes/table` o mantener | `rep.clientes` | admin, registro, inventario, tributación | cualquier autenticado | OK | Pendiente (D3) | CRUD visible para cualquier autenticado. |
| 32 | Usuarios | Puntos y Recompensas | `/app/reportes/loyalty` | `/app/reportes/loyalty` | `rep.loyalty` | admin, registro, inventario, tributación | cualquier autenticado | OK | Mantener | — |

### 4.5 Matriz B — navbar, dashboard and in-page populated

| # | Superficie | Etiqueta | URL actual | URL final | ID canónico | Rol visible | Rol destino | Estado | Acción | Notas |
|---|---|---|---|---|---|---|---|---|---|---|
| N1 | navbar | Artículos | `/app/articulos` | `/app/articulos` | `ges.articulos` | inventario | admin, inventario | OK | Mantener | — |
| N2 | navbar | Categorías | `/app/categorias` | `/app/categorias?tab=familias` | `ges.categorias.familias` | inventario | admin, inventario | OK | Mantener | Corregido en `486c343`: `?tab=familias` explícito. |
| N3 | navbar | Inventarios | `/app/inventarios` | `/app/inventarios` | `ges.inventarios` | inventario | admin, inventario | OK | Mantener | — |
| N4 | navbar | Alertas de Stock | `/app/reportes/inventario/alertas` | `/app/reportes/inventario/resumen` | `rep.inventario.analisis` | inventario | admin, inventario | Parcial | Corregir | Atajo directo a una pestaña; viola Regla 1 si se mantiene. Apuntar al entry canónico o eliminar. |
| N5 | navbar | Mermas y Pérdidas | `/app/reportes/inventario/merma` | `/app/reportes/inventario/merma` | `rep.inventario.mermas` | inventario | admin, inventario | OK | Mantener | — |
| N6 | navbar | Usuarios | `/app/usuarios` | `/app/usuarios` | `ges.usuarios` | usuario | admin, usuario | OK | Mantener | — |
| N7 | navbar | Clientes | `/api/app/clientes/table` | `/api/app/clientes/table` | `ges.clientes` | usuario | admin, usuario | OK | Mantener | Ruta de API con página completa (dual-mode). |
| N8 | navbar | Dashboard Hacienda | `/app/tributacion/dashboard` | `/app/tributacion/dashboard` | `rep.tributacion.dashboard` | tributacion | admin, tributacion | OK | Mantener | — |
| N9 | navbar | Consultas | `/app/tributacion/consultas` | `/app/tributacion/consultas` | `rep.tributacion.consultas` | tributacion | admin, tributacion | OK | Mantener | — |
| N10 | navbar | CaByS | `/app/cabys` | `/app/cabys` | `ges.cabys` | tributacion | admin, tributacion (página) / admin, inventario (API) | Parcial | Corregir | Gate asimétrico página/API. |
| N11 | navbar | Reportes | `/app/reportes` | `/app/reportes` | `nav.hub` | registro | admin, registro, inventario, tributacion | OK | Mantener | `facturacion` y `usuario` no ven este enlace. |
| N12 | navbar | Menú de correos | `/app/correos/reportes` | `/app/correos/reportes` | `ges.correos.programados` | admin | admin, tributacion | OK | Pendiente (D4) | Duplica el reporte (D4). |
| N13 | navbar | Plantillas | `/app/correos/plantillas` | `/app/correos/plantillas` | `ges.correos.plantillas` | admin | admin, tributacion | Duplicado | Pendiente (D9) | Misma ruta que el hub: una de las dos entradas se elimina. |
| N14 | navbar | Registro de actividades | `/app/registros/log` | `/app/registros/log` | `tool.registros` | admin | admin, registro | Duplicado | Pendiente (D9) | Misma ruta que el hub: una de las dos entradas se elimina. |
| D1 | dashboard | Facturas Emitidas | `/app/facturas` | `/app/facturas` | `ges.facturas.emitidas` | todos | admin, facturacion | Parcial | Mantener | 403 para roles sin facturacion. |
| D2 | dashboard | Facturas Recibidas | `/app/facturas-recibidas` | `/app/facturas-recibidas` | `ges.facturas.recibidas` | todos | admin, facturacion | Parcial | Mantener | Corregido en `6b1654e`; solo `admin/facturacion` puede abrirlo. |
| IP1 | in-page | Ir a facturas recibidas (pendientes) | `/app/facturas-recibidas` | `/app/facturas-recibidas` | `ges.facturas.recibidas` | facturacion, admin | admin, facturacion | OK | Mantener | `recibos-pendientes.html:28`; la ruta ya existe (D14). |
| IP2 | in-page | Ir a facturas recibidas (vencidos) | `/app/facturas-recibidas` | `/app/facturas-recibidas` | `ges.facturas.recibidas` | facturacion, admin | admin, facturacion | OK | Mantener | `recibos-vencidos.html:29`; la ruta ya existe (D14). |

### 4.6 Matriz B — out-of-scope accesses (recorded, not acted on)

| # | Superficie | Etiqueta | URL | ID | Motivo |
|---|---|---|---|---|---|
| O1 | navbar | Logo | `/app` | `oos.home` | Landing; fuera de alcance. |
| O2 | navbar | Nueva Factura | `/app/pos/standalone` | `oos.pos` | POS; fuera de alcance. |
| O3 | navbar | Tipo de cambio | `/app/tipo-cambio/fragment` | `oos.tipo-cambio` | Modal de configuración; fuera de alcance. |
| O4 | navbar | Menú de aplicación | `/app/aplicacion` | `oos.aplicacion` | Ajustes globales; fuera de alcance. |
| O5 | navbar | Respaldos | `/app/backups` | `oos.respaldos` | Fuera de alcance. |
| O6 | navbar | Perfil | `/app/perfil` | `oos.perfil` | Fuera de alcance. |
| O7 | navbar | Cerrar sesión | `POST /api/app/auth/logout` | `oos.auth` | Autenticación; fuera de alcance. |

**Summary:** 32 hub + 14 navbar + 2 dashboard + 2 in-page = **50 in-scope accesses**.
After Phases 1 and 2 and D14, there are no `Roto` destinations: `/app/facturas`,
`/app/categorias?tab=...` and `/app/facturas-recibidas` all resolve. The hub keeps 6
`Duplicado` (#3, #4, #9, #15, #24, #25), and the navbar adds 2 (N13, N14). The root
cause of most `Parcial` rows is one defect: the hub's role gate does not match its
contents (§A.7), not 30 independent bugs.

---

## 5. Duplication identification by service root

### 5.1 Protocol

1. Group Matriz A rows by `servicio_raiz`.
2. For each group with more than one row, compare:
   - operations (A12),
   - roles (A13),
   - exports and dataset keys (A14),
   - filters, sorting and pagination,
   - endpoints and JSON contracts (A15),
   - whether one of them mutates data.
3. Classify the pair:
   - **Access duplicate**: one screen, two entries. Fix in Matriz B only.
   - **Implementation duplicate**: two screens over the same data with overlapping
     capabilities. Candidate for consolidation (§8.4).
   - **Different responsibilities over the same data**: keep both, give them
     distinct names and IDs (e.g. emitted report vs emitted board).
4. A shared service root **does not** authorize a merge. See §5.3.

### 5.2 Verified signals (confirmed in Phase 0)

These were verified on the baseline commit and classified in §5.4. The table is the
short form; the evidence lives in Matriz A and Matriz B.

| Signal | Screens | Service root | What it actually is | Decision |
|---|---|---|---|---|
| Emitted invoices | `/app/facturas` (board, mutates) + `/app/reportes/facturas` (report) | `ComprobantesEmitidosService` / `ComprobantesEmitidos` | Different responsibilities: operations vs read-only report | Keep both; distinct names/IDs; do **not** merge |
| Received invoices | `/app/facturas-recibidas` (board, buckets) + `/app/reportes/recibos/pendientes` + `/vencidos` (read-only) | `ComprobantesRecibidosService` / `ComprobantesRecibidos` | Partial duplication: the board already has `pendientes`/`vencidas` buckets | Decide view retention and XLSX transfer (§7 D2) |
| Users | `/app/usuarios` (management) + `/app/reportes/usuarios` (report) | `LoginService` / users | Duplicate listing; different gates and exports | Consolidate or keep with distinct names (§7 D3) |
| Clients | `/api/app/clientes/table` (management, dual-mode full page) + `/app/reportes/clientes` (report with create/edit buttons) | `ClientService` / `Clientes` | Duplicate listing + visible CRUD from the report | Fix permissions first; then consolidate (§7 D3) |
| Scheduled email reports | `/app/correos/reportes` (management) + `/app/reportes/correos` (report) | `ReportesProgramadosService` / `ReporteProgramado` | Duplicate listing; different gates and exports | Define the unified role matrix (§7 D4) |
| Supplier ranking | embedded in `/app/categorias` | `DepartamentoMetricoService` / `DepartamentoMetrico` | Not a duplicate: a report responsibility inside a management container | Create an independent screen (§7 D5) |

### 5.3 Shared service ≠ interchangeable screens

`LoginService`, `ClientService` and `ReportesProgramadosService` being shared is a
reason to **investigate**, not a license to merge. Before consolidating, the agent
must prove that the surviving screen preserves:

- who can read and who can mutate (per method, not per screen),
- the export dataset and its columns,
- filters, sorting and pagination,
- the endpoint contract and its callers,
- the visible label(s) and the role-specific behavior.

If any of those differ, the pair is **not** a duplicate until a product decision
says which behavior wins.

### 5.4 Phase 0 classification of every signal

| Pair / group | Classification | Evidence | Decision |
|---|---|---|---|
| `rep.facturas.emitidas` + `ges.facturas.emitidas` | Different responsibilities over the same data | Report is read-only; board mutates (pay/process/upload) | Keep both; D7 names |
| `rep.facturas.recibidas.aging` + `ges.facturas.recibidas` | Implementation duplicate | The board already has `pendientes`/`vencidas`/`pagadas` buckets over the same service | D2 |
| `rep.usuarios` + `ges.usuarios` | Implementation duplicate | Same listing from `LoginService`; different gates and export | D3 |
| `rep.clientes` + `ges.clientes` | Implementation duplicate + mixed CRUD | Same listing from `ClientService`; report exposes create/edit to any authenticated user | D3 + Phase 2 gate fix |
| `rep.correos.programados` + `ges.correos.programados` | Implementation duplicate | Same listing from `ReportesProgramadosService`; different gates and export | D4 |
| `ges.correos.plantillas` (hub + navbar) | Access duplicate | Same route `/app/correos/plantillas` in two surfaces | D9 |
| `tool.registros` (hub + navbar) | Access duplicate | Same route `/app/registros/log` in two surfaces | D9 |
| Ventas: cajero + familia + departamento | Same responsibility, three hub entries | Three tabs of one screen; the hub lists each tab | One hub entry |
| Análisis de inventario: resumen + alertas + pronósticos | Same responsibility, three hub entries | Three tabs of one screen; the hub lists each tab | One hub entry |
| Tendencias + estacionalidad | Same responsibility, two hub entries | Two tabs of one screen; the hub lists each tab | One hub entry |
| `/app/categorias` | Mixed container | Familias + Departamentos (management) + supplier ranking (report) | Split; D5 for the ranking |
| `/app/articulos` | Mixed container | Articles management + Promociones (separate entity) + review workflow | Two rows; keep the tab if the product wants it |
| `/app/facturas` upload | Capability mismatch, not a duplicate | Emitted board posts its XML to the received upload endpoint | Phase 2 |
| Dashboard `Facturas Emitidas` / `Facturas Recibidas` | Cross-surface shortcuts, not duplicates | A different navigation surface; allowed if intentional | Keep; note the 403s for roles without `facturacion` |

---

## 6. Mixed-screen classification and decomposition

### 6.1 Method

1. List the page's responsibilities.
2. Assign each one an ID and a type (`Reporte`, `Gestión`, `Herramienta`).
3. Decide the destination surface for each (hub group, Gestión module, Ajustes).
4. Mark the container row `Dividir`.
5. A page may remain mixed **only** with an explicit product decision and a written
   justification. "It works today" is not a justification.

### 6.2 Verified mixed pages (to be decomposed in Phase 0)

| Container | Responsibilities | Verified evidence |
|---|---|---|
| `/app/categorias` | Familias (gestión), Departamentos (gestión), Ranking de proveedores (reporte) | `categorias/index.html:46,130-171`; `CategoriaResource.java:899-929` |
| `/app/articulos` | Catálogo/gestión de artículos, Promociones (entidad aparte), revisión de pendientes | `articulos/index.html:61-90` |
| `/app/inventarios` | Ajustes/movimientos (gestión), carga XML, revisión y procesado | `inventario/index.html:98-155` |
| `/api/app/clientes/table` + `/app/reportes/clientes` | Reporte (métricas) + CRUD visible | `reportes/clientes.html:18,63-66`; `clientes/index.html:49-76` |
| `/app/facturas` | Tablero operativo + acciones + carga XML (que hoy sube recibidas) | `recibos/index.html:59`; `recibos/tablero.html:36-54` |

### 6.3 Rule

If a responsibility mutates data, its canonical type is `Gestión`. If it must also
appear in the reports hub, either (a) the hub group is explicitly mixed and says so,
or (b) the hub links to a read-only slice and the mutating screen lives in Gestión.
There is no third option.

---

## 7. Product decisions

### 7.1 Registered decisions (approved in principle)

| ID | Topic | Treatment | Status |
|---|---|---|---|
| D1 | Billing group | A **mixed group named "Facturación"** that includes the operational board. The group is not labeled "Reportes". | Approved |
| D2 | Pending/overdue received invoices | Decide whether the two read-only views survive as tabs of the payables board, and transfer their XLSX exports if they do not. `/app/facturas-recibidas` does not export today. | Pending |
| D3 | Users / clients | Consolidate respecting **per-operation** permissions, not just per-screen roles. Preserve XLSX. | Approved as constraint; role mapping pending |
| D4 | Scheduled email reports | Define the role matrix of the unified interface. The two current screens have different gates. | Pending |
| D5 | Supplier ranking | Create an independent screen reusing `DepartamentoMetrico`; do not invent a `Proveedor` entity (supplier = `Departamento`). | Approved |
| D6 | Purchase orders and labels | Document explicitly their location and nature (management vs tool). Do not leave them implicit. | Pending |
| D7 | Emitted invoices naming | Keep **"Reporte de facturas emitidas"** (`/app/reportes/facturas`) and **"Facturas emitidas (gestión)"** (`/app/facturas`) as distinct IDs and labels. | Approved |

### 7.2 Additional decisions Phase 0 must close

| ID | Topic | Why it blocks | Status |
|---|---|---|---|
| D8 | `Catálogo` semantics | It currently queries `processed=true AND status=true`, i.e. it *is* the "Procesados" view, while its badge counts all articles. Choose: rename to "Procesados" and fix the badge, or redefine as "all articles" and change the query. | Resolved in `e2cdf20`: renamed to Procesados, badge = `countByTab("catalogo", null)`; export remains D15 |
| D9 | Canonical access for Plantillas / Registros | They are one screen each with two access points (hub + navbar). Decide which surface is canonical; this is an access duplicate, not a screen duplicate. | Pending |
| D10 | "Todas las Facturas" | It is the read-only emitted report. After the hotfix it will coexist with "Facturas emitidas (gestión)" and both currently render the title "Facturas Emitidas". Confirm D7's naming in Matriz B. | Resolved by D7; Matriz A has distinct functional names, Matriz B rows 5 and 11 are the two accesses |

A decision marked `Pending` blocks any phase that depends on it. It does not block
Phase 0 (the audit must record the options and their consequences).

### 7.3 Decisions surfaced by Phase 0

| ID | Topic | Why it surfaced | Options | Recommendation | Status |
|---|---|---|---|---|---|
| D11 | Hub role model | `/app/reportes` is gated `admin, registro, inventario, tributacion`, but 28 of the 31 links a `registro` user can see answer 403; `facturacion` cannot open the hub at all. | (a) filter entries by role; (b) widen the hub to every authenticated user and filter; (c) one hub per role | (a) + align the navbar "Reportes" link with the effective gate | Pending |
| D12 | Split inventory movements from sales | The "Por fecha" tab of the sales screen uses `InventarioService` and shows inventory movements, not sales. | (a) `rep.inventario.movimientos` as its own report under Inventario; (b) keep it as a tab of `rep.ventas` | (a) | Pending |
| D13 | Where the Gestión screens live | The plan removes Artículos, Inventarios, Categorías, Órdenes and Etiquetas from the hub, but there is no Gestión section today (the navbar has separate Artículos/Usuarios dropdowns). | (a) a new "Gestión" navbar entry/landing; (b) keep the existing dropdowns and only remove the hub entries; (c) a Gestión group inside the hub | (a) | Pending |
| D14 | Official XML upload surface | Three forms, two endpoints; the emitted board and the inventory board both write received invoices; `/app/facturas-recibidas` is dead. | (a) keep only the received board upload, remove the other two cards, retire the inventory endpoint; (b) keep an inventory shortcut repointed to the received endpoint and gate it; (c) build a real emitted upload; (d) status quo | (a) | Resolved in `6b1654e`: real page route, two cards removed, links repointed; endpoints retained |
| D15 | Export authorization and correctness | `/api/app/export` is `admin,registro`, while the buttons live on `inventario`/`facturacion`/`@Authenticated` screens; 18 of 25 forms are dead for the page role. | (a) per-dataset authorization + hide/gate the button; (b) widen the class gate; (c) remove the buttons from roles that cannot export | (a) | Resolved: D15a (`9d4fced`) fixed the three 404s, the Articles tab and `ventas`; D15b (`dd5cebe`) added the per-dataset role map (six-role class gate, `usuarios` admin-only) |

---

## 8. Implementation phases

### 8.1 Phase 0 — Complete audit (no code)

**Deliverables**

1. Matriz A populated (one row per responsibility, mixed pages decomposed).
2. Matriz B populated (every access in every surface).
3. Dependency map (services, endpoints, export datasets, redirects, tests).
4. Split proposal for every mixed page.
5. Decision log: §7.1 confirmed, §7.2 closed or explicitly deferred with an owner.

**Exit gate:** zero orphan IDs in either direction; every access resolves to exactly
one ID; every mixed page has a split proposal; no blocking `Pending` decision without
an owner.

**Do not** change templates, resources, routes or tests in this phase.

### 8.2 Phase 1 — Hotfix (one link) — done 2026-10-31

**Status:** implemented in `reportes/index.html`; regression test
`Controllers.Api.App.Reportes.ReportsHubLinksTest` passes
(`Tests run: 1, Failures: 0, Errors: 0`).

**Scope:** only the hub's "Facturas emitidas" entry, from `/app/recibos` to
`/app/facturas`.

**Why it cannot wait:** the link currently renders the **received invoices** board
(“Facturas emitidas” → 302 → `/api/app/facturas-recibidas/tabla`), so an operator
looking for emitted invoices sees the wrong dataset.

**Constraints**

- Do **not** remove or change the `/app/recibos` redirect; it is intentional legacy
  naming (`RecibosRedirectResource`).
- Do **not** rename "Todas las Facturas" here; D7/D10 handle naming in Phase 2/4.
- Add a regression test that asserts the hub's href for that label is
  `/app/facturas` (there is no hub-link test today).

**Exit gate:** the hub entry resolves to `/app/facturas` with status 200 for an
authorized role; no other file changes; the new test fails on the old href.

### 8.3 Phase 2 — Corrections that depend on decisions

**Status:** items 2, 4 and 5 done on 2026-10-31; items 1 and 3 remain (D8, D3).

1. **Catálogo** (D8): apply the chosen semantics and make the badge use the same
   predicate as the table. `catalogo` and `procesados` are the same query today
   (`ArticulosService:354-359`); the badge uses `count()` (all rows).
2. **[x] Familias / Departamentos** (done 2026-10-31): the container reads and
   writes `?tab=`; both hub entries and the navbar link carry the parameter.
   `CategoriasPageTest` and the two `ReportsHubLinksTest` cases pass. Today
   `/app/categorias` starts on Familias when no parameter is present.
3. **Clientes permissions**: the report page is `@Authenticated`, but its
   create/edit buttons call `admin/usuario` endpoints. Hide or gate them, or gate
   the page; this is a per-operation decision (D3).
4. **[x] XML upload surfaces** (D14, done in `6b1654e`): the received board is
   the only visible upload, at the new real page route `/app/facturas-recibidas`.
   The emitted and inventory cards were removed, the dashboard and report links
   were repointed, and `D14UploadSurfacesTest` pins the four cases. The upload
   endpoints remain for API compatibility.
5. **[x] Export dataset integrity and authorization** (D15a `9d4fced`, D15b
   `dd5cebe`): the three missing datasets (`reportes-movimientos`,
   `inventario-resumen`, `promociones`) resolve; the Articles export honours
   `tab`/`q`; and `ventas.html` uses `reportes-ventas` instead of downloading the
   article catalogue. D15b replaced the `admin,registro` class gate with a
   six-role gate plus a per-dataset `DATASET_ROLES` map (`usuarios` is
   admin-only), so every page role can export its own data and no role can
   export a dataset it cannot read. `ExportResourceTest` covers 14 cases.

**Exit gate:** each item has a test or a documented manual verification, and no
role sees a control it cannot use.

### 8.4 Phase 3 — Functional consolidation

Resolve the duplicate pairs classified in §5.4, one at a time, with the §5.3 checklist:

- Users (D3),
- Clients (D3),
- Scheduled email reports (D4).

For each: decide the surviving screen, preserve the union of capabilities, and
update Matriz A/B in the same change.

**Exit gate:** no duplicate implementation remains (or the remaining pair has an
approved justification), and the capability diff is empty or approved.

### 8.5 Phase 4 — Hub reorganization

Build the groups from the completed Matriz A/B. Rules:

- one entry per canonical screen per navigation surface,
- one name per screen, one screen per name,
- tabs stay inside their screen,
- a mixed group (D1) is labeled as such and does not pretend to be read-only.

**Exit gate:** criteria 1–5 in §10 pass.

### 8.6 Phase 5 — Validation

Run the full checklist in §10, the per-role smoke, the existing page tests and the
new hub test. No browser automation unless the user explicitly asks for it in that
task.

---

## 9. Non-regression constraints

These are hard constraints for every phase:

1. **No endpoint path changes** for JSON contracts consumed by the UI or tests.
2. **No redirect removal.** `/app/recibos` stays; fix the caller, not the redirect.
3. **No role widening or narrowing without a decision** in §7. A merge must not
   silently change who can read or mutate.
4. **No export loss.** Preserve the dataset key, not only the button. The keys the
   export endpoint actually accepts today are:
   `correos`, `precios`, `clientes`, `recibos-pendientes`, `usuarios`,
   `recibos-vencidos`, `facturas`, `merma`, `tendencias`, `rendimiento`,
   `pronosticos`, `estacionalidad`, `loyalty`, `etiquetas`, `ventas-fecha-historica`,
   `articulos`, `inventario`, `stock-alerts`, `profit-margins`, `familias`,
   `departamentos`, `reportes-familias`, `reportes-departamentos`
   (`ExportResource.java:236-458`). An unknown key answers **404** (`ExportResource.java:214-215,524-526`).
   The audit must record, per screen, the template's `dataset` value and whether it
   resolves — not just that an "Exportar" button exists (§A.6).
5. **No filter, sort or pagination loss** when a report becomes a tab or vice versa.
6. **No test deletion.** Update a test with the change; do not remove its coverage.
7. **No browser automation** as a verification step unless the user asks for it.
8. **One step = one commit**, with the SemVer bump in the same commit, and `mvn test`
   green.
9. **No business-logic change** disguised as a navigation fix.

---

## 10. Acceptance criteria (mandatory close checklist)

| # | Criterion | Verification |
|---|---|---|
| 1 | **Zero orphan IDs.** Every Matriz B `id_canonico` exists in Matriz A, and every Matriz A row has at least one access. | Join both tables on `id`; assert no unmatched rows in either direction. |
| 2 | **One entry per screen per surface.** No canonical screen has more than one access in the same navigation surface. | Group Matriz B by (`id_canonico`, `ubicacion`); assert count ≤ 1. |
| 3 | **Zero redirects to the wrong screen.** Every Matriz B access resolves to its `id_canonico`, with no 3xx to a different ID. | Authenticated `GET` per access with `redirects=false`; compare against the expected canonical URL. |
| 4 | **Zero ambiguous names.** No two IDs share a `nombre_funcional`; no ID has two visible names. | Uniqueness query over Matriz A and the rendered labels. |
| 5 | **No orphan screens.** Every Matriz A row is reachable from at least one Matriz B access, directly or as a tab of a container that has an access. Navigation containers (`nav.*`) and out-of-scope rows (`oos.*`) are exempt. | Covered by criterion 1 plus the `contenedor` column; `ges.promociones` is the known tab-only case. |
| 6 | **Per-role access.** For every role, every visible access returns 200 and the expected screen; no visible control leads to a 403. | Role matrix smoke: one request per (role, access). |
| 7 | **No capability removed without approval.** Operations, roles, exports and filters in Matriz A match the baseline, or the delta is listed in §7 with `Approved`. Every export dataset resolves (no 404 from `/api/app/export`). | Before/after diff of A12–A14; one export request per screen/dataset. |
| 8 | **Tests green.** Existing page tests pass and the new hub-link test fails on the pre-fix href. | `mvn test`; targeted `-Dtest=...` for the touched pages. |

Criterion 0 (implicit): Matriz A and Matriz B are complete. An incomplete matrix
cannot satisfy 1–7.

---

## 11. Phase 0 definition of done

Run: **2026-10-31**, baseline `d25f9a4`.

- [x] Matriz A has one row per responsibility; no `Mixta` rows.
- [x] Matriz B has every access from hub, navbar, dashboard and in-page links.
- [x] The 32 hub entries (31 without admin) are all mapped.
- [x] The screens outside the hub are mapped (`/app/facturas`,
      `/app/facturas-recibidas`, `/app/usuarios`, `/api/app/clientes/table`,
      `/app/correos/reportes`, `/app/categorias`).
- [x] Every duplicate pair has a classification: access / implementation /
      different responsibility.
- [x] Every mixed page has a split proposal.
- [x] The dependency map lists services, endpoints, datasets, redirects and tests.
- [x] The export map lists, per screen, the template's `dataset` value and whether
      `/api/app/export` resolves it (A.6 now seeds three known failures plus one
      tab-ignoring export).
- [x] Every §7 decision is `Approved` or has an owner and a target phase.
- [ ] Reviewed by the product owner before Phase 1.

**Review question for the product owner:** close D11 (hub role model) first. It
changes more entries in Matriz B than any other decision and it determines whether
Phase 4 is a reorganization or a permission fix.

---

## Appendix A — Verified current state (baseline `d25f9a4`)

These facts are the seed for Phase 0. They are **not** the populated matrices.

### A.1 Navigation defects

| # | Defect | Evidence |
|---|---|---|
| 1 | Hub "Facturas emitidas" points to `/app/recibos`, which 302-redirects to the **received** invoices table. The emitted board is `/app/facturas`. | `reportes/index.html:235`; `RecibosRedirectResource.java:21-38`; `RecibosPagesResource.java:49` |
| 2 | Hub "Departamentos" and "Familias" both point to `/app/categorias`; the page defaults to Familias and does not read a tab parameter. | `reportes/index.html:324,333`; `categorias/index.html:46` |
| 3 | Hub "Órdenes de Compra" points to `/api/app/ordenes/table`. It works as a full page (dual-mode), but it is an API path, not a page route. | `reportes/index.html:402`; `OrdenCompraResource.java:107,590-618` |
| 4 | Hub "Métricas de proveedores" points to the management page `/app/categorias`. The label is accurate (the page contains a real supplier ranking); the problem is the mixed container. | `reportes/index.html:411`; `categorias/index.html:130-171` |

### A.2 Counters and states

- Articles tabs: Activos / Inactivos / Catálogo / Pendientes / Promociones
  (`articulos/index.html:61-90`).
- `catalogo` and `procesados` are the same predicate:
  `processed=true AND status=true` (`ArticulosService.java:354-359`).
- The Catálogo badge uses `articulosService.count()` = **all** articles
  (`ArticuloResource.java:1447`; `GService.java:171-174`), so it does not match the
  table.
- Activos is a **superset** of Pendientes and Procesados: `status=true` vs
  `status=true AND processed=false/true`.
- Inventory tabs: Activos / Pendientes / Procesados / Inactivos
  (`inventario/badges.html:14-37`); the Procesados badge is the derived
  `activos - pendientes` (`InventarioResource.java:1056-1064`).

### A.3 Duplicate screens: roles and exports

| Pair | Report gate | Management gate | Report export | Management export |
|---|---|---|---|---|
| Users | `admin` (`UsuariosReportesResource.java:45`) | `admin,usuario` (`UsuariosPagesResource.java:37`) | XLSX (`reportes/usuarios.html:64-67`) | none |
| Clients | `@Authenticated` (`ClientesReportesResource.java:46`) | `admin,usuario` (`ClientsResource.java:84`) | XLSX (`reportes/clientes.html:86-89`) | none |
| Emails | `facturacion,admin` (`CorreosReportesResource.java:45`) | `admin,tributacion` (`CorreosPagesResource.java:30`) | XLSX (`reportes/correos.html:56-59`) | none |
| Received pending/overdue | `facturacion,admin` (`RecibosReportesResource.java:54`) | `admin,facturacion` (`FacturasRecibidasResource.java:164`) | XLSX (`recibos-pendientes.html:76`, `recibos-vencidos.html:78`) | none |

### A.4 Cross-upload (closed by D14)

Before D14 there were **three forms but only two endpoints**; D14 removed the two
misplaced forms and added the missing page route:

- `/app/facturas-recibidas` is now the real received-board page
  (`FacturasRecibidasPagesResource`), rendering the same model as the dual-mode
  `/api/app/facturas-recibidas/tabla`. Its upload card is the only visible one.
- The emitted board (`/app/facturas`) and the inventory board (`/app/inventarios`)
  no longer render upload cards. Historically both posted to received endpoints
  (`recibos/index.html:59`, `inventario/index.html:71`), and the inventory endpoint
  created no `Inventario` rows.
- The upload endpoints remain for API compatibility. `D14UploadSurfacesTest`
  pins the page route, the role gate and the two removed cards.

This was D14, not D6.

### A.5 Baseline counts

- Hub: 32 links (10 / 6 admin–5 non-admin / 13 / 3), `reportes/index.html:118,226-230,302,434`.
- Non-admin view: 31 links (Registros is admin-only, `reportes/index.html:275`).
- Estimated screen families behind the hub: ~23 (to be confirmed by Matriz A).

### A.6 Export datasets: baseline and resolution

**Resolution (D15a `9d4fced`, D15b `dd5cebe`):** the three 404s, the `articulos`
tab, the `ventas` wrong-content export and the role gate are fixed;
`ExportResourceTest` covers 14 cases.

| Screen | Template sends | Endpoint accepts | Result |
|---|---|---|---|
| Movimientos de inventario | `reportes-movimientos` (`fechas.html:60`) | no such case (`ExportResource.java:236-458`) | 404 |
| Resumen de inventario | `inventario-resumen` (`resumen.html:89`) | no such case; only `inventario` | 404 |
| Promociones (Articles tab) | `promociones` (`articulos/index.html:209`) | no such case | 404 |
| Articles (all four tabs) | `articulos` + `tab=activos/inactivos/catalogo/pendientes` | `articulos` ignores `tab` and `q`; exports `ListAllEnabled()` | Activos: right rows, `q`/sort dropped; Inactivos/Catálogo/Pendientes: wrong rows, 200 |
| Ventas por Cajero | `articulos` + `type=pdf` (`ventas.html:63`) | yes | Article-catalogue PDF, not sales (wrong content, 200) |

Every other template dataset resolves (correos, precios, clientes, usuarios,
recibos-pendientes/vencidos, facturas, merma, tendencias, rendimiento, pronosticos,
estacionalidad, loyalty, etiquetas, ventas-fecha-historica, profit-margins,
stock-alerts, articulos, inventario, familias, departamentos, reportes-familias,
reportes-departamentos). Three caveats:

- **Authorization (fixed by D15b):** the class gate is now the six application
  roles and `DATASET_ROLES` checks each dataset against the page roles. Inventory
  datasets allow `admin,inventario,registro`; billing datasets allow
  `admin,facturacion,registro`; `clientes`, `loyalty` and `estacionalidad` allow
  all six; `usuarios` is `admin` only. The old `admin,registro` gate made 18 of 25
  page buttons dead for their own role; that is resolved.
- **Filters:** several datasets forward `q`/dates but the exporter calls
  `listAll()` (`ExportResource.java:237-251`), so the export is unfiltered
  (articulos, usuarios, clientes, correos).
- **404 conflation:** an unknown dataset and a known dataset with an unsupported
  type both return 404 (`ExportResource.java:214-215,524-527`), so a 404 alone does
  not prove the key is missing.

These are capability gaps, not navigation gaps: they belong in Matriz A (A14) and
Phase 2.

### A.7 Hub role model defect (the largest Phase 0 finding)

`/app/reportes` is gated `admin, registro, inventario, tributacion`
(`MiscPagesResource.java:46`). The navbar link is gated `registro`
(`navbar.html:147-149`). Neither includes `facturacion` or `usuario`, yet the hub
links to facturacion-only screens. Conversely, `registro`, `inventario` and
`tributacion` can open the hub but most destinations are outside their roles.

| Role | Can open `/app/reportes`? | Visible hub links | Destinations 200 | Destinations 403 |
|---|---|---|---|---|
| `admin` | yes | 32 | 32 | 0 |
| `registro` | yes | 31 | 3 | 28 |
| `inventario` | yes | 31 | 22 | 9 |
| `tributacion` | yes | 31 | 5 | 26 |
| `facturacion` | **no** | — | dashboard + direct URLs | — |
| `usuario` | **no** | — | direct Clientes/Loyalty/Usuarios | — |

The three links that work for `registro` are Estacionalidad (`@Authenticated`),
Clientes and Loyalty. The hub is therefore a page that shows an operator 31 buttons
and denies 28 of them. This is not a per-link bug; it is D11.

Two smaller gate asymmetries:

- `rep.tendencias`: the Tendencias tab is `admin,inventario`; the Estacionalidad tab
  is `@Authenticated` (`TendenciasResource` vs `EstacionalidadReportesResource`).
- `ges.cabys`: the page is `admin,tributacion`; the API is `admin,inventario`
  (`CabysPagesResource` vs `CabysResource`).

### A.8 Phase 0 summary

| Metric | Count |
|---|---|
| Canonical in-scope screens (Matriz A) | 34 |
| In-scope accesses (hub + navbar + dashboard + in-page) | 50 |
| Broken destinations (`Roto`) | 0 after `2ab771e`, `486c343` and `6b1654e` |
| Access duplicates (`Duplicado`) | 8 (6 hub + 2 navbar) |
| Broken export datasets | 0 after D15a (`9d4fced`); baseline was 3 (`reportes-movimientos`, `inventario-resumen`, `promociones`) |
| Exports that ignore their tab | 0 after D15a; baseline was 1 (`articulos`). `q` is still dropped by `usuarios`, `clientes`, `correos` |
| Wrong-content export (200) | 0 after D15a; baseline was 1 (`ventas.html` downloaded the article catalogue) |
| Dead page routes | 0 (`/app/facturas-recibidas` now resolves; D14) |
| Mixed surfaces decomposed | 4 (`/app/categorias`, `/app/articulos`, `/app/facturas`, clientes report/board) |
| Role-model defects | 1 (hub) + 2 gate asymmetries; the export gate was fixed in D15b |
| Open decisions | D2, D3 (role mapping), D4, D6, D9, D11, D12, D13 |

The effective root causes are four: the hub role model (§A.7), the flat tab-as-entry
hub (Matriz B), the duplicate management/report pairs (§5.4), and three stale export
datasets plus one tab-ignoring export (§A.6).

### A.9 Phase 0 verification addendum (2026-10-31)

Five read-only Big Pickle agents re-verified the decisions D8, D3, D4/D9, D14 and
D15. They changed no files. Findings that refine the matrices:

**D8 — Articles / Promociones.**
- `procesados` is already accepted as a tab key and normalized to `catalogo`
  (`ArticuloResource.java:1364-1370`), so renaming the tab costs one label.
- Export: `Activos` exports the right row set (only `q`/sort are dropped);
  `Inactivos`, `Catálogo` and `Pendientes` export `ListAllEnabled()`, i.e. active
  articles, so their buttons lie. `Promociones` has no `case` (404).
- `countActivos/Inactivos/Pendientes` restate the predicates instead of calling
  `countByTab`; only Catálogo diverges today, but the drift is structural.
- Recommendation: Option A (rename to Procesados, badge = `countByTab`), then fix
  the export separately. **Implemented in `e2cdf20`.**

**D3 — Users / Clients.**
- Users: create/edit/toggle are `admin`-only (`UsersResource.java:170,255,314`),
  but `usuario` sees the buttons and gets 403; there is no password control in the
  UI; the report is a strict subset of management plus XLSX.
- Clients: the report's create/edit are visible to **any authenticated** user but
  execute only for `admin/usuario`; export is `admin,registro`; management search
  is name-only while the report searches email/cédula; the "Activar" button always
  sets `status=false`.
- Recommendation: Users absorbs the report now (move the admin-gated XLSX export);
  Clients fixes the gate and keeps both routes until D3 closes.

**D4 / D9 — Correos, Plantillas, Registros.**
- The correos report renders 5 headers for 6 cells (status chip has no header) and
  its export ignores `q`.
- The management screen also has a third URL:
  `/api/app/reportes-programados/table` (dual-mode, `admin,tributacion`).
- Delete is admin-only in the UI but `admin,tributacion` in the API.
- Plantillas has no toggle; the doc's operation list is overstated.
- `registro` can open `/app/registros/log` but has no entry on either surface.
- Recommendation: management survives with the union gate and a per-dataset export;
  Plantillas canonical in the hub; Registros canonical in Ajustes.

**D14 — XML uploads.**
- Three forms, two endpoints. The emitted board and the inventory board both wrote
  `ComprobantesRecibidos`; the inventory upload created no `Inventario` rows and was
  invisible to the `inventario` role.
- `/app/facturas-recibidas` was dead (fallback); the board was
  `/api/app/facturas-recibidas/tabla`.
- Recommendation U1: keep only the received-board upload, remove the other two
  cards, and give the received board a real page route. **Implemented in `6b1654e`**:
  the page route exists, both cards are gone, links are repointed, and the upload
  endpoints remain for compatibility.

**D15 — Exports.**
- 25 forms, 26 dataset values; the three 404s plus the `articulos` tab issue plus
  `ventas.html` sending `dataset=articulos&type=pdf` (wrong content, 200).
- `/api/app/export` is `admin,registro`; most pages render a button their role
  cannot execute, and `registro` can export datasets whose page it cannot read.
- An unknown dataset and a known dataset with an unsupported type both return 404.
- Recommendation: per-dataset authorization + hide/gate the button; add the three
  cases and thread `tab`/`q`; add a coverage test that scans template datasets.

---

## Appendix B — Verification commands

Run from `app/`:

```powershell
# state of the tree
git status --porcelain

# one test class
mvn -q -Dtest=ReportsHubLinksTest test

# page contract tests likely touched by consolidation
mvn -q -Dtest='UsuariosReportesPageTest,ClientesReportesPageTest,CorreosReportesPageTest,RecibosReportesPageTest' test

# full suite (PostgreSQL on localhost:5433/mercurius_test)
mvn test
```

Suggested hub-link test (Phase 1): a `@QuarkusTest` with
`@TestSecurity(roles={"registro"})` that GETs `/app/reportes` and asserts the
rendered href for "Facturas emitidas" is `/app/facturas` (and that it is not
`/app/recibos`). `NavbarPopupLauncherTest` already treats `/app/facturas` as the
canonical facturacion page and `/app/reportes` as the registro page, so the
fixtures are consistent with the rest of the suite.

Do **not** use a browser for verification. If a visual check is genuinely needed,
ask the user first.

---

## Appendix C — Change log

| Date | Change |
|---|---|
| 2026-10-31 | Initial specification and Phase 0 template. Method approved; matrices not populated; no code. |
| 2026-10-31 | Phase 0 executed on `d25f9a4`: Matriz A (34 in-scope screens + 2 containers + 6 out-of-scope), Matriz B (50 accesses), duplicate classification, role-model audit, export audit. No code changed. |
| 2026-10-31 | Phase 1 hotfix: "Facturas emitidas" now points to `/app/facturas`; `ReportsHubLinksTest` added; version `2.16.4 → 2.16.5`. |
| 2026-10-31 | Phase 2, item 2: Categorías reads/writes `?tab=`; hub and navbar links carry the tab; `CategoriasPageTest` added; version `2.16.5 → 2.16.6`. |
| 2026-10-31 | Five read-only Big Pickle agents re-verified D8/D3/D4/D9/D14/D15; findings consolidated in A.9 and the affected rows/decisions updated. No code changed. |
| 2026-10-31 | D8: Articles tab renamed to Procesados; badge now uses `countByTab`; version `2.16.6 → 2.16.7`. |
| 2026-10-31 | D14: real `/app/facturas-recibidas` page, both misplaced upload cards removed, links repointed, `D14UploadSurfacesTest` added; version `2.16.9 → 2.16.10`. |
| 2026-10-31 | D15a: added `reportes-movimientos`, `inventario-resumen`, `promociones` and `reportes-ventas`; Articles export honours `tab`/`q`; `ExportResourceTest` added; version `2.16.10 → 2.17.0`. |
| 2026-10-31 | D15b: six-role class gate + per-dataset `DATASET_ROLES` map (`usuarios` admin-only); export tests cover role allow/deny; version `2.17.0 → 2.17.1`. |
