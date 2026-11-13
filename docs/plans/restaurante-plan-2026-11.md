# Plan de implementación — Módulo Restaurante (mesas, comandas, KDS, propina, división de cuenta, recetas)

Status: **planificado, no implementado**. Documento ejecutable por otro agente sin volver a investigar.
Repo: `F:\Documents\GitHub\Mercurius-clean` (Quarkus 3.36.2, Java 25, Qute + HTMX, PostgreSQL/Hibernate, sin Panache, sin SPA).
Fecha del plan: 2026-11-13.

## 0. Resumen ejecutivo

### Decisión de alcance

Se agrega un módulo **restaurante** sobre el POS existente, reutilizando el pipeline de facturación (`ComprobanteService.crearComprobante` + `PosResource.doFacturar`) y el carrito (`CartSessionContext`/`CartSessionStore`). No se reescribe facturación. Se extiende el dominio con mesas, comandas, KDS, modificadores, propina, división de cuenta, recetas/combos/costeo.

### Qué NO se hará (fuera de alcance)

- No microservicios ni SPA: todo sigue en Quarkus + Qute + HTMX.
- No se toca la lógica de facturación electrónica v4.4 (firma, envío Hacienda, XSD); solo se alimentan datos (propina como cargo, líneas con modificadores).
- No se implementa reserva con disponibilidad en tiempo real, delivery, ni integración con apps de terceros.
- No se migra a bodegas múltiples en esta fase (solo sucursal existente); las recetas descuentan del stock de la sucursal activa.
- No se reemplaza el POS de mostrador: el flujo de mesa convive con él.
- No se añade WebSocket; el KDS usa polling HTMX (decisión §6.2).

### Principios transversales

- Todo dinero en `BigDecimal` (estándar del repo, README §Estándares).
- Anotaciones `jakarta.*` únicamente; Lombok donde ya se usa.
- Sin `catch` vacío; todo error se registra con `org.jboss.logging.Logger` (estándar del repo).
- Bloqueo optimista `@Version` en entidades contendidas (patrón `CierreCaja`/`ArticuloStock`); consecutivos siguen pesimistas.
- Idempotencia de cobro reutilizando el sello `CartSessionStore.Entry.facturadoComprobanteId` (ver `PosResource.doFacturarInterior` líneas 557-571).

## 1. Estado actual verificado

### Existe (verificado en código)

| Capacidad | Evidencia |
|---|---|
| Carrito POS por usuario | `Services/cart/CartSessionStore.java` (referenciado en `PosResource` líneas 131-141); `CartSessionContext` |
| Agregar/eliminar artículos, promociones singulares/múltiples | `Services/CarritoService.java` (`addArticulo`, `removeArticulo`, `procesarPromocionesCarrito`) |
| Cobro con pago dividido | `Models/EntradaPago.java`; `PosResource.paymentEntries` (líneas 426-467) y `doFacturar` (líneas 685-710) |
| Caja/arqueo | `Models/CierreCaja.java` (con `@Version`) |
| Sucursales/terminales | `Models/Sucursal.java`; `ConfiguracionAplicacion.codigoSucursal/codigoTerminal` |
| Lotes | `Models/Lote.java` (citado en README) |
| Lealtad (puntos) | `Models/PuntosTransaccion.java`; `LoyaltyService` (canje dentro de `crearComprobante`) |
| Promociones | `Models/Articulos/Promocion.java` (referenciado) |
| Comprobantes FE/TE/NC/ND/FEE/FEC/REP | `ComprobanteService.crearComprobante` (líneas 342-682); estrategias `DocumentoStrategy` |
| CABYS e impuestos por línea | `Models.Cabys` (referenciado en `Articulos`) |
| Reportes, correo | README |
| Inventario por movimiento | `Models.Inventario`; `CarritoService.ajustarInventario` (líneas 424-452) |
| Stock por artículo (único) | `Models/Articulos/ArticuloStock.java` (`codigo_barra` unique, `@Version`) |
| Artículos con precios históricos | `Models/Articulos/Articulos.java` (`precios` ordenados por id) |
| SCSS/design system | `app/src/main/resources/web/mercurius.scss`; templates `pages/facturas/*.html` |

### Existe parcial

| Capacidad | Detalle |
|---|---|
| Descuento por cliente por línea | `CarritoService.descuentoCliente` / `aplicarDescuentoCliente` (porcentaje en `Clientes.discount`) |
| Ajuste manual de precio por línea | `ArticuloCarrito.precioPersonalizado`, `precioAjustadoFinal`, `motivoAjustePrecio` (auditoría en memoria, se copia a `LineaDetalle` al emitir) |
| Otros cargos en resumen | **Verificado**: `Models/Detalles/OtroCargo.java` existe con `tipoDocumentoOC`, `tipoDocumentoOTROS`, `detalle`, `porcentajeOC`, `montoCargo`, `identificacionTercero`; `ResumenFactura.totalOtrosCargos` existe. `ComprobanteService` ya suma `totalOtrosCargos` al total del comprobante. La propina puede montarse aquí. |
| Notas por línea | **Verificado**: `LineaDetalle` tiene `detalle` (`@XmlElement(name="Detalle")`, `length=200`) y `numeroLinea`, pero **no** tiene `notas`, `esServicio` ni `modificadores`. Los modificadores caben en `detalle` (texto fiscal) y/o en una columna nueva `notas` (`@XmlTransient`). |

### No existe (verificado por ausencia)

- Mesas, sesiones de mesa, comandas, KDS: **verificado por ausencia** — no existen entidades ni referencias en `Models/`, `Services/` ni `templates/`.
- Modificadores (`GrupoModificador`/`OpcionModificador`): no existe.
- Propina/10% servicio: no existe.
- Recetas/insumos, combos, variantes: no existe.
- Bodegas (solo sucursal): confirmado — `Models/Sucursal.java` es el único punto de venta/stock.

### Mecanismo de esquema (importante)

- **No se usa Flyway**: `application.properties` líneas 19, 33: `quarkus.hibernate-orm.schema-management.strategy=update` (prod/dev) y `%test` línea 167 `drop-and-create`.
- Consecuencia: las nuevas tablas se crean al arrancar con `update`. Para producción se recomienda entregar scripts SQL manuales en `docs/db/migrations/` (no existen hoy) y aplicarlos antes del despliegue; `update` es aceptable en dev pero no auditable. **Decisión**: mantener `update` para dev/test y agregar scripts manuales para prod (ver §3.6).

## 2. Modelo de datos propuesto

Convenciones: tablas `snake_case` minúsculas (como `articuloCarrito`, `cierrecaja`), PK `id` `BIGINT GENERATED BY DEFAULT AS IDENTITY` (Hibernate `IDENTITY`), dinero `NUMERIC(18,2)`, timestamps `TIMESTAMP`, bloqueo optimista `@Version` donde haya concurrencia.

### 2.1 Fase 1 — Mesas

**`zona`** (salones/áreas)
- `id BIGINT PK`
- `sucursal_id BIGINT FK -> sucursal.id` (nullable para compatibilidad con datos existentes)
- `nombre VARCHAR(80) NOT NULL`
- `orden INT NOT NULL DEFAULT 0`
- `activa BOOLEAN NOT NULL DEFAULT TRUE`

**`mesa`**
- `id BIGINT PK`
- `zona_id BIGINT FK -> zona.id NOT NULL`
- `numero VARCHAR(10) NOT NULL` (ej. "12", "A3")
- `capacidad INT NOT NULL DEFAULT 4`
- `estado VARCHAR(20) NOT NULL` ∈ {`LIBRE`,`OCUPADA`,`POR_COBRAR`,`RESERVADA`}
- `posicion_x INT`, `posicion_y INT` (plano configurable)
- `activa BOOLEAN NOT NULL DEFAULT TRUE`
- `version BIGINT NOT NULL DEFAULT 0` (`@Version`)
- Índice único `(zona_id, numero)`

**`sesion_mesa`** (cuenta abierta)
- `id BIGINT PK`
- `mesa_id BIGINT FK -> mesa.id NOT NULL`
- `mesero_username VARCHAR(60) NOT NULL`
- `cliente_id BIGINT FK -> clientes.code` (nullable)
- `fecha_apertura TIMESTAMP NOT NULL`
- `fecha_cierre TIMESTAMP` (nullable)
- `estado VARCHAR(20) NOT NULL` ∈ {`ABIERTA`,`COBRADA`,`CANCELADA`}
- `propina_modo VARCHAR(20)` ∈ {`NINGUNA`,`MONTO`,`PORCENTAJE`,`SERVICIO_10`}
- `propina_valor NUMERIC(18,2)`
- `tipo_servicio VARCHAR(20) NOT NULL DEFAULT 'MESA'` ∈ {`MESA`,`PARA_LLEVAR`}
- `notas VARCHAR(500)`
- `version BIGINT NOT NULL DEFAULT 0` (`@Version`)
- Índice `(mesa_id, estado)`, `(fecha_apertura)`

**`comanda`** (envío a cocina por área)
- `id BIGINT PK`
- `sesion_mesa_id BIGINT FK -> sesion_mesa.id NOT NULL`
- `area VARCHAR(20) NOT NULL` ∈ {`COCINA`,`BARRA`,`POSTRES`}
- `numero INT NOT NULL` (secuencial por sesión)
- `estado VARCHAR(20) NOT NULL` ∈ {`PENDIENTE`,`EN_PREPARACION`,`LISTA`,`ENTREGADA`,`CANCELADA`}
- `fecha_envio TIMESTAMP NOT NULL`
- `fecha_lista TIMESTAMP`
- `notas VARCHAR(500)`
- `version BIGINT NOT NULL DEFAULT 0`

**`comanda_detalle`**
- `id BIGINT PK`
- `comanda_id BIGINT FK -> comanda.id NOT NULL`
- `articulo_id BIGINT FK -> articulos.codigo NOT NULL`
- `cantidad NUMERIC(18,3) NOT NULL`
- `notas VARCHAR(200)`
- `modificadores_json TEXT` (serialización de opciones elegidas; alternativa: tabla `comanda_detalle_modificador`)
- `estado VARCHAR(20) NOT NULL` ∈ {`PENDIENTE`,`EN_PREPARACION`,`LISTA`,`ENTREGADA`,`CANCELADA`}
- `fecha_estado TIMESTAMP`

Reutilización del carrito: la `SesionMesa` **no** duplica líneas del carrito en BD en Fase 1. En su lugar, la sesión mantiene una entrada en `CartSessionStore` bajo la clave `mesa:<sesionId>` (ver §6.1). Al cobrar, se transfiere esa entrada al cajero o se factura directamente. Las `comanda_detalle` se generan al enviar a cocina, no al agregar al carrito.

### 2.2 Fase 3 — Modificadores

**`grupo_modificador`**
- `id BIGINT PK`
- `articulo_id BIGINT FK -> articulos.codigo NOT NULL` (el platillo que admite modificadores)
- `nombre VARCHAR(80) NOT NULL` (ej. "Término de cocción")
- `obligatorio BOOLEAN NOT NULL DEFAULT FALSE`
- `min_selecciones INT NOT NULL DEFAULT 0`
- `max_selecciones INT NOT NULL DEFAULT 1`
- `orden INT NOT NULL DEFAULT 0`

**`opcion_modificador`**
- `id BIGINT PK`
- `grupo_id BIGINT FK -> grupo_modificador.id NOT NULL`
- `nombre VARCHAR(80) NOT NULL`
- `precio_extra NUMERIC(18,2) NOT NULL DEFAULT 0`
- `activo BOOLEAN NOT NULL DEFAULT TRUE`
- `orden INT NOT NULL DEFAULT 0`

Selección por línea se guarda en `comanda_detalle.modificadores_json` y se copia a `ArticuloCarrito` (campo nuevo `modificadoresSeleccionados` transient + persistido en `LineaDetalle` al facturar). **No verificado** si `LineaDetalle` soporta campo libre; si no, agregar `modificadores VARCHAR(500)`.

### 2.3 Fase 5 — Recetas y combos

**`receta`**
- `id BIGINT PK`
- `articulo_id BIGINT FK -> articulos.codigo NOT NULL UNIQUE` (el platillo vendible)
- `rendimiento NUMERIC(18,3) NOT NULL DEFAULT 1` (porciones por lote)
- `merma_pct NUMERIC(5,2) NOT NULL DEFAULT 0`
- `activa BOOLEAN NOT NULL DEFAULT TRUE`

**`receta_detalle`**
- `id BIGINT PK`
- `receta_id BIGINT FK -> receta.id NOT NULL`
- `insumo_articulo_id BIGINT FK -> articulos.codigo NOT NULL`
- `cantidad NUMERIC(18,4) NOT NULL`
- `unidad VARCHAR(20) NOT NULL`

**`combo`**
- `id BIGINT PK`
- `articulo_id BIGINT FK -> articulos.codigo NOT NULL UNIQUE` (el artículo vendible de tipo combo)
- `precio_combo NUMERIC(18,2) NOT NULL`
- `activo BOOLEAN NOT NULL DEFAULT TRUE`

**`combo_detalle`**
- `id BIGINT PK`
- `combo_id BIGINT FK -> combo.id NOT NULL`
- `articulo_id BIGINT FK -> articulos.codigo NOT NULL`
- `cantidad NUMERIC(18,3) NOT NULL DEFAULT 1`

Insumos son artículos con un flag `esInsumo BOOLEAN` (agregar a `Articulos`) o se identifican por departamento/familia. **No verificado** convención existente; recomiendo agregar columna `es_insumo BOOLEAN NOT NULL DEFAULT FALSE` a `articulos`.

### 2.4 Migraciones

No hay Flyway (verificado). Entregar:
- `docs/db/migrations/V20261113_01_restaurante_mesas.sql`
- `docs/db/migrations/V20261113_02_restaurante_modificadores.sql`
- `docs/db/migrations/V20261113_03_restaurante_recetas.sql`

Cada script con `CREATE TABLE IF NOT EXISTS` e índices. En dev/test Hibernate `update`/`drop-and-create` los crea solo; en prod ejecutar scripts antes del despliegue y dejar `update` activo como red de seguridad.

## 3. API / endpoints

Base path existente: `/api/app/pos` (`PosResource`, `@RolesAllowed({"admin","facturacion"})`). Nuevos recursos bajo el mismo prefijo para reutilizar seguridad y cart store.

### 3.1 Mesas (Fase 1)

| Método | Ruta | Permiso | Request | Response |
|---|---|---|---|---|
| GET | `/api/app/pos/mesas` | admin/facturacion | `?zonaId` | lista `MesaDTO` con estado |
| GET | `/api/app/pos/mesas/{id}` | admin/facturacion | — | `MesaDTO` + sesión activa |
| POST | `/api/app/pos/mesas` | admin | `{zonaId, numero, capacidad, x, y}` | `MesaDTO` |
| PATCH | `/api/app/pos/mesas/{id}` | admin | `{capacidad, x, y, activa}` | `MesaDTO` |
| POST | `/api/app/pos/mesas/{id}/abrir` | admin/facturacion | `{mesero, clienteCode?, tipoServicio}` | `SesionMesaDTO` (crea entrada en cart store) |
| POST | `/api/app/pos/mesas/{id}/cerrar` | admin/facturacion | `{motivo?}` | `OperationMessage` (libera mesa, cancela sesión) |
| POST | `/api/app/pos/mesas/{id}/mover` | admin/facturacion | `{mesaDestinoId}` | transfiere sesión y cart a mesa destino; origen queda LIBRE |
| POST | `/api/app/pos/mesas/unir` | admin/facturacion | `{mesaIds[]}` | marca mesas como unidas bajo una sesión principal |
| POST | `/api/app/pos/mesas/{id}/transferir-cuenta` | admin/facturacion | `{mesaDestinoId, itemIds[]?}` | mueve toda o parte de la cuenta |

### 3.2 Carrito sobre sesión de mesa (Fase 1)

Reutilizar los mismos endpoints de carrito pero resolviendo el contexto por `sesionMesaId` en lugar de username:

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/app/pos/mesas/{mesaId}/items` | body `{articuloId, cantidad, modificadores?}` → delega a `CarritoService.addArticulo` sobre el cart de la sesión |
| DELETE | `/api/app/pos/mesas/{mesaId}/items/{codigo}` | delega a `removeArticulo` |
| GET | `/api/app/pos/mesas/{mesaId}/cuenta` | resumen de la cuenta (totales, impuestos, propina) |
| POST | `/api/app/pos/mesas/{mesaId}/comanda` | body `{area}` → crea `Comanda` con los ítems pendientes de envío y marca líneas como enviadas |

### 3.3 KDS (Fase 2)

| Método | Ruta | Permiso | Descripción |
|---|---|---|---|
| GET | `/app/kds` (Qute page) | admin/facturacion/cocina | vista columnas |
| GET | `/api/app/kds/comandas` | roles cocina | `?area=&estado=` → lista comandas con timers |
| PATCH | `/api/app/kds/comandas/{id}/estado` | roles cocina | `{estado}` → avanza estado |
| PATCH | `/api/app/kds/comandas/{id}/items/{itemId}/estado` | roles cocina | `{estado}` |
| POST | `/api/app/kds/items/{itemId}/agotado` | admin | marca "86" (notifica mesas con ese artículo pendiente) |

Roles cocina: agregar rol `cocina` a `@RolesAllowed` donde corresponda (no existe hoy — **verificar** roles en `Models/Usuarios`/security config; si no existe, crear rol `cocina` en el seed y en la política de permisos).

### 3.4 Modificadores y propina (Fase 3)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/app/pos/modificadores/{articuloId}` | grupos/opciones del artículo |
| POST/PUT/DELETE | `/api/app/pos/modificadores/grupos...` | CRUD admin |
| PATCH | `/api/app/pos/mesas/{mesaId}/propina` | body `{modo, valor}` |
| PATCH | `/api/app/pos/mesas/{mesaId}/tipo-servicio` | `{tipoServicio}` (afecta propina legal 10% solo MESA) |

### 3.5 División de cuenta (Fase 4)

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/app/pos/mesas/{mesaId}/dividir` | body `{modo: ITEM|COMENSAL|IGUAL, partes: int, asignaciones?}` → devuelve N sub-cuentas |
| POST | `/api/app/pos/mesas/{mesaId}/cobrar` | body `{divisionId?, pagos[]}` → factura (reutiliza `doFacturar`) |

### 3.6 Recetas/combos/costeo (Fase 5)

| Método | Ruta | Descripción |
|---|---|---|
| CRUD | `/api/app/pos/recetas`, `/api/app/pos/combos` | admin |
| GET | `/api/app/pos/recetas/{articuloId}/costo` | costo y margen |
| POST | `/api/app/pos/recetas/{articuloId}/producir` | descuenta insumos manualmente (producción) |

## 4. UI (Qute + HTMX)

Respetar `mercurius.scss` y `_kit/`. Convenciones: Bulma, HTMX 2.0.10, Alpine.js para estado local, fragments HTMX para swaps.

### 4.1 Fase 1 — Plano de mesas

- Página `/app/pos/mesas` (Qute) con grid de mesas por zona, color por estado (libre=blanco, ocupada=azul, por cobrar=amarillo, reservada=gris), capacidad.
- Click en mesa libre → modal "Abrir mesa" (mesero, cliente opcional, para llevar/mesa) → HTMX POST → swap del tile.
- Click en mesa ocupada → panel lateral con cuenta (reutilizar `cart-panel` fragment con `panelModel` adaptado).
- Wireframe textual:
```
+----------------------------------------------------------+
| Zonas: [Salón principal][Terraza][Barra]                 |
| +--------+ +--------+ +--------+                          |
| | M-12   | | M-13   | | M-14   |  (libre: borde gris)   |
| | 4 pax  | | 4 pax  | | 2 pax  |                        |
| +--------+ +--------+ +--------+                          |
| +--------+ +--------+                                    |
| | M-05   | | M-06   |  (ocupada: fondo azul)            |
| | 6 pax  | | 4 pax  |                                    |
| +--------+ +--------+                                    |
+----------------------------------------------------------+
| Panel cuenta (HTMX) | Totales | Propina | Cobrar          |
+----------------------------------------------------------+
```
- Estados vacíos: "No hay mesas configuradas. Cree la primera en Administración → Mesas."
- Error: toast (Sonner si está) con mensaje del envelope `{severity,summary,detail}`.

### 4.2 Fase 2 — KDS

- Página `/app/kds` con 4 columnas (Pendiente, En preparación, Lista, Entregada), cards por comanda con timer (Alpine.js, desde `fecha_envio`), notas en rojo, badge de mesa.
- Polling: `hx-trigger="every 2s"` sobre contenedor, `hx-get="/api/app/kds/comandas?area=..."`.
- Botón "86" en artículo agotado → toast a mesas con ese artículo pendiente (implementar notificación simple por polling de un endpoint `/api/app/pos/alertas`).

### 4.3 Fase 3 — Modificadores

- Al agregar artículo con modificadores, modal con grupos (radio/checkbox según min/max), precio extra visible. HTMX POST envía `modificadores` JSON.
- Propina: selector en panel de cuenta (ninguna, monto, %, 10% servicio). El 10% solo si `tipoServicio=MESA` (ley costarricense).

### 4.4 Fase 4 — División

- Modal con tabs: Por ítem (asignar cada línea a comensal 1..N), Por comensal (N montos), Montos iguales (N partes). Vista previa de subtotales. Botón "Cobrar" por sub-cuenta.

### 4.5 Fase 5 — Recetas/costeo

- Pantalla admin de receta por artículo: tabla insumo/cantidad/merma, costo calculado, margen vs precio de venta.

## 5. Integración con facturación

### 5.1 Reutilización (no duplicar lógica)

Una sesión de mesa se convierte en factura sin duplicar:
1. La sesión mantiene una entrada en `CartSessionStore` bajo clave `mesa:<sesionId>` (misma estructura `CartSessionContext`).
2. Al cobrar, `PosResource` (o un nuevo `MesaCobroResource`) resuelve esa entrada y llama al mismo `doFacturar` pipeline, pasando el cart de la sesión en lugar del cart del usuario. El cajero actual es `currentUser`.
3. Stock se descuenta dentro de `crearComprobante` vía `carritoService.ajustarInventario` (líneas 375-381), igual que hoy. Para recetas (Fase 5), el descuento de insumos ocurre aquí también (ver §5.4).
4. Idempotencia: el sello `facturadoComprobanteId` vive en la entrada; un reintento reproduce el resultado sin duplicar.

### 5.2 Impacto en `numeroLinea`, `esServicio`, propina, descuentos

- `numeroLinea`: **verificado** — `ComprobanteService.java:1360` asigna `linea.setNumeroLinea(i + 1)`. Los modificadores NO generan líneas nuevas; se agregan a `detalle` (XML `Detalle`, 200 chars) y/o a una columna `notas` nueva. La propina sí es un cargo aparte (`OtrosCargos`).
- `esServicio`: **no existe** en `LineaDetalle` ni en `Articulos`. Hoy `ComprobanteService.java:1135` declara `boolean esServicio = false` fuera del bucle y nunca lo cambia, por eso todo cae en mercancías. Hay que agregar `Articulos.esServicio` y leerlo por línea dentro de `resumenComprobante`; la propina, además, siempre es servicio.
- Propina:
  - Modos: monto libre, porcentaje, 10% legal (solo MESA).
  - **No es base de IVA**: se suma al total del comprobante fuera del cálculo de impuesto. En el XML v4.4 se representa como `OtrosCargos` (código de cargo "06" u otro según catálogo de Hacienda) o como línea de servicio con tarifa 0%. **Decisión recomendada**: usar `OtrosCargos` con tipo de documento "06" (propina), porque es un cargo adicional, no un bien/servicio facturable con IVA. Verificar contra el XSD v4.4 y el catálogo de Hacienda antes de implementar; si Hacienda exige línea de detalle, representarla como `LineaDetalle` con `esServicio=true` e impuesto 0%. Documentar la decisión en el código.
  - Impacto en resumen: `totalOtrosCargos` ya se suma a `totalComprobante` (línea 535: `totalComprobante = totalVentaNeta + totalImpuesto + totalOtrosCargos - totalIVADevuelto`). La propina se incluye ahí. Los `MedioPagoR` deben sumar al nuevo total (el bloque de líneas 539-576 ajusta el último medio de pago al total).
- Descuentos: los descuentos por línea (promociones, descuento cliente) siguen aplicándose en `CarritoCalculations`. La propina se calcula sobre el subtotal con descuentos aplicados (decisión: sobre total neto de líneas).

### 5.3 División de cuenta (Fase 4) — recomendación

**Recomendación: una sola factura con N pagos (`EntradaPago`) por defecto**, porque:
- Un solo consecutivo, un solo stock, una sola firma/envío Hacienda.
- El pago dividido ya existe y se reutiliza (`EntradaPago`, `paymentEntries`).
- Menor riesgo de doble cobro y de inconsistencia.

**Facturas separadas** solo cuando haya receptor distinto por comensal (cliente con cédula) o cuando el operador lo pida explícitamente. En ese caso se crean N entradas en el cart store (`mesa:<sesionId>:parte:<n>`), cada una con su subconjunto de líneas, y se factura secuencialmente con el mismo pipeline. El stock se descuenta una sola vez en total (cuidado: cada factura descuenta; hay que evitar doble descuento — marcar las líneas originales como "ya facturadas" y no re-facturar). **Riesgo alto; mitigar con estado `FACTURADA` en `comanda_detalle`.**

### 5.4 Recetas (Fase 5) — descuento de inventario

En `ComprobanteService.crearComprobante`, después de `ajustarInventario` (líneas 375-381), si el artículo tiene receta activa, por cada unidad vendida descontar `receta_detalle.cantidad * (1 + merma_pct/100)` del insumo correspondiente, creando movimientos `Inventario` con nota "Insumo de receta: <platillo>". Los insumos que no tengan stock suficiente → lanzar excepción dentro de la transacción (rollback). Combos: al vender el artículo combo, descontar cada componente.

### 5.5 Impacto en PDF

`Utils/PDFGenerator.java` genera el tiquete; se invoca desde `PosResource` con `ctx.getCarrito()`. Para mesas, pasar el cart de la sesión. La propina y los modificadores deben aparecer en el PDF: extender la plantilla de `PDFGenerator` (verificado que la clase existe; el método exacto se confirma al abrirla).

## 6. Decisiones de arquitectura clave

### 6.1 Reutilizar `CartSessionStore` con clave `mesa:<id>`

En lugar de crear un carrito paralelo, la sesión de mesa es dueña de una entrada del store. `CartSessionStore.getOrCreate(key)` ya existe (usado en `PosResource` línea 267). Esto hace que todo el cálculo de totales, impuestos, promociones y pago dividido funcione sin cambios.

### 6.2 KDS por polling HTMX, no WebSocket

El stack **no tiene WebSocket**: no hay dependencia ni configuración (`app/pom.xml`, `application.properties`), y el patrón es HTMX. Polling cada 2 s es suficiente para una cocina pequeña y evita nueva infraestructura. Si la carga lo exige, migrar a SSE (Quarkus `@Produces(MediaType.SERVER_SENT_EVENTS)`).

### 6.3 Concurrencia de mesas

- `Mesa` y `SesionMesa` con `@Version` (patrón `CierreCaja` línea 83).
- Abrir/mover/cobrar una mesa: transacción corta con `PESSIMISTIC_WRITE` sobre la fila de `Mesa` (como consecutivos) para evitar doble apertura.
- El cart store por sesión evita contención entre meseros; solo la fila de mesa se bloquea.

### 6.4 Doble cobro

- Idempotencia reutilizada (`facturadoComprobanteId`).
- Botón "Cobrar" deshabilitado en cliente (Alpine) hasta respuesta; backend serializa por entrada (`synchronized(entry)` como en `doFacturar` línea 542).

### 6.5 Stock negativo

- `ajustarInventario` ya propaga excepciones (líneas 424-432).
- Agregar validación previa en `CarritoService`/servicio de mesa: si stock insuficiente, rechazar antes de enviar comanda (alerta "86"). Para recetas, validar insumos al vender.

## 7. Fases, esfuerzo y dependencias

| Fase | Contenido | Esfuerzo (días) | Depende de |
|---|---|---|---|
| F0 | Infra: roles `cocina`, entidades base, scripts SQL, rutas Qute | 2 | — |
| F1 | Mesas + sesiones + plano + abrir/cerrar/mover/unir + carrito sobre sesión | 6 | F0 |
| F2 | Comandas + KDS + impresión térmica + "86" | 5 | F1 |
| F3 | Modificadores + notas + propina + impacto XML | 5 | F1 |
| F4 | División de cuenta (ítem/comensal/igual) + cobro | 4 | F1, F3 |
| F5 | Recetas + descuento inventario + costeo + combos | 6 | F1 |
| F6 | Pruebas E2E + ajustes + documentación | 3 | F1-F5 |

Total: ~31 días. Se puede paralelizar F2/F3/F5 tras F1.

## 8. Criterios de aceptación (verificables)

- F1: abrir mesa con 3 ítems, cerrarla y reabrirla conserva los 3 ítems; mover cuenta a otra mesa deja la origen LIBRE; dos usuarios no pueden abrir la misma mesa (segundo recibe 409).
- F1: una sesión de mesa se factura con el mismo pipeline y produce FE/TE con consecutivo válido, stock descontado una vez.
- F2: enviar comanda con 3 líneas; KDS muestra las 3 en <2 s (polling); cambiar estado a LISTA refleja en <2 s; timer visible.
- F2: imprimir comanda térmica genera ticket ESC/POS con mesa, ítems, modificadores, notas.
- F3: artículo con grupo obligatorio no se puede agregar sin selección; precio extra se suma al total; propina 10% solo en MESA y no afecta base de IVA (verificar XML: TotalImpuesto no incluye propina).
- F4: dividir cuenta de 6 ítems en 3 comensales; cobrar los 3; total de los 3 = total original ±0.01; stock descontado una vez.
- F5: vender un platillo con receta descuenta insumos; si falta insumo, la venta se revierte (sin factura, sin stock descontado); costo y margen visibles.

## 9. Plan de pruebas

### 9.1 Unitarias

- `CarritoCalculations` con modificadores y propina (nuevos casos).
- Servicios de mesa: abrir/mover/unir con mocks.
- Receta: cálculo de costo y descuento.

### 9.2 Integración (PostgreSQL local `mercurius_test`, puerto 5433)

- Pruebas REST con login form-cookie (`admin`/`admin123`), patrón existente.
- Verificar tablas creadas por `drop-and-create` en `%test`.
- Concurrencia: dos threads abriendo misma mesa → uno 409.
- Idempotencia: doble cobro de mesa → segunda respuesta es replay, sin factura duplicada.

### 9.3 E2E

- Extender `scripts\e2e-journey.ps1` o crear `scripts\e2e-restaurante.ps1`: login → crear mesa → abrir → agregar 3 ítems → enviar comanda → KDS lista → cobrar con propina 10% → verificar factura y stock.

### 9.4 Verificación de propina vs IVA

- Test que parsea el XML generado y verifica que `TotalImpuesto` no cambia al agregar propina, y que `TotalComprobante` aumenta en el monto de propina.

## 10. Riesgos y mitigación

| Riesgo | Mitigación |
|---|---|
| Concurrencia de mesas (doble apertura) | `@Version` + `PESSIMISTIC_WRITE` en apertura; 409 al segundo |
| Doble cobro | Idempotencia existente (`facturadoComprobanteId`); botón deshabilitado en cliente; `synchronized(entry)` |
| Stock negativo / insumos faltantes | Validación previa + rollback transaccional en `crearComprobante`; alerta "86" |
| Propina vs IVA (rechazo Hacienda) | Usar `OtrosCargos`; test de XML; verificar contra XSD antes de prod |
| Migración de datos (prod) | Scripts SQL manuales + backup; `update` como red de seguridad; probar en staging |
| Rendimiento KDS | Polling 2 s acotado por área; índice en `comanda(estado, area)`; paginación si >50 comandas activas |
| Roles cocina no existen | Verificar `Models/Usuarios` y security config; crear rol y seed |
| Modificadores en `LineaDetalle` | Verificar campo; si falta, agregar columna nullable con migración |

## 11. Reutilización vs descarte

### Se reutiliza (con rutas)

- `Services/CarritoService.java` (`addArticulo`, `removeArticulo`, `calcularTotal`, `calcularVuelto`, `ajustarInventario`, `cancel`) — toda la lógica de carrito.
- `Services/ArticuloCarritoService.java` (GService base) — CRUD.
- `Services/ComprobanteService.java` (`crearComprobante`, `enviarComprobanteCreado`) — pipeline completo.
- `Controllers/Api/App/PosResource.java` (`doFacturar`, `doFacturarInterior`, `paymentEntries`) — cobro.
- `Controllers/Api/App/PosPageResource.java` — patrón de página Qute.
- `Services/cart/CartSessionStore.java` — sesiones por clave.
- `Models/EntradaPago.java` — pago dividido.
- `Models/CierreCaja.java`, `Models/Sucursal.java` — caja y sucursal.
- `Models/Articulos/ArticuloStock.java` — stock por artículo.
- `Utils/CarritoCalculations.java` — totales e impuestos.
- `app/src/main/resources/web/mercurius.scss` y `_kit/` — design system.

### Se descarta / no se toca

- No se reemplaza el POS de mostrador (`/app/pos`).
- No se modifica la firma/envío Hacienda.
- No se migra a bodegas múltiples.
- No se añade WebSocket.

## 12. Verificaciones cerradas en esta revisión

| Punto | Resultado |
|---|---|
| `LineaDetalle`: `notas`, `esServicio`, `modificadores`, `numeroLinea` | `numeroLinea` existe y ya se asigna `i + 1` (`ComprobanteService.java:1360`). Existe `detalle` (XML `Detalle`, 200 chars). **No** existen `notas`, `esServicio` ni `modificadores`: hay que agregarlos. |
| `ResumenFactura` / `OtrosCargos` | `Models/Detalles/OtroCargo.java` existe (`tipoDocumentoOC`, `tipoDocumentoOTROS`, `detalle`, `porcentajeOC`, `montoCargo`). `ResumenFactura.totalOtrosCargos` existe y ya suma al total. |
| Roles | `Models/Usuarios.java` guarda el rol en `groupName`. Valores en uso: `admin`, `facturacion`, `inventario`, `usuario`, `tributacion`, `registro`. **No existe `cocina`**: hay que crearlo en F0 (o reutilizar `facturacion`). |
| PDF | `Utils/PDFGenerator.java`; el método de tiquete se invoca desde `PosResource`. Hay que extender la plantilla para propina y modificadores. |
| `numeroLinea` | Ya correcto (`i + 1`). No requiere cambio. |
| "86" / alerta de stock | `Models/AlertaStock.java` existe; **no** hay `StockAlertService`. El bloqueo de venta sin stock debe implementarse en el servicio de mesa (F2), no reutilizando una alerta existente. |

Pendiente real antes de F3: confirmar en el XSD v4.4 y el catálogo de Hacienda si la propina va como `OtrosCargos` (código de cargo) o como línea de servicio al 0%. El plan recomienda `OtrosCargos`; esa decisión debe quedar escrita en el código.

Si algo de la tabla no calza al implementar, se crea en la fase correspondiente y se documenta el cambio.