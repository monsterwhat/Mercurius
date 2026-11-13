# Plan: Variantes comerciales, Bodegas y Traslados — Mercurius

**Versión:** 1.2 (plan de ejecución, corregido contra código verificado) — fecha 2026-11-13
**Alcance:** diseño + migración + implementación por fases. No ejecuta código.
**Repo:** `F:\Documents\GitHub\Mercurius-clean` (Quarkus 3.x, Java 25, PostgreSQL, Qute + HTMX).
**Reglas del repo aplicadas:** sin Flyway (`quarkus.hibernate-orm.schema-management.strategy=update` + scripts SQL manuales para prod); dinero/cantidades en `BigDecimal`; logging con `org.jboss.logging.Logger`; sin microservicios ni SPA.

> **Convención de verificación.** Cada afirmación de este documento se apoya en `archivo:línea` del repo. Lo que depende de sistemas externos (Fides, XSD de Hacienda) o de firmas no revisadas se marca explícitamente **no verificado**. Las correcciones respecto de la versión 1.1 están en §16.

---

## 1. Resumen ejecutivo

### 1.1 Qué se construye
- **Variantes comerciales** (padre + SKU): talla/color/presentación/sabor/etc., cada una con código de barras propio, precio propio o heredado, stock propio, imagen opcional, estado.
- **Bodegas** bajo sucursal: `Bodega`, `StockBodega` (artículo/variante × bodega × cantidad), `Traslado` + `TrasladoDetalle` con estados `BORRADOR/ENVIADO/RECIBIDO/ANULADO`.
- **Lotes por bodega** (extensión de `Lote`).
- **Números de serie** (`Serie`).
- Integración con POS, facturación (XML Hacienda), compras, etiquetas, reportes, valoración.

### 1.2 Decisiones de alcance (las 5 más importantes)
1. **Variantes usan EAV normalizado** (`atributo` + `valor_atributo` + `articulo_variante_atributo`) para soportar 1, 2 o 3 atributos sin migraciones de columnas; con índice único por (variante, atributo). Justificación: §3.1.
2. **Artículos existentes quedan como "artículo simple"** (sin hijos en `articulos`; el padre actúa como SKU único). No se crea variante implícita. Justificación: §4.1.
3. **`ArticuloStock` se MANTIENE como agregado por código de barras** y `StockBodega` es el detalle por bodega; doble escritura en la misma transacción. Justificación: §3.2 y §4.2.
4. **Bodega obligatoria en movimientos nuevos; nullable en histórico** (`inventario.bodega_id` nullable). Backfill a "Bodega Principal" **por código de sucursal**, no por fila de `sucursal` (ver §14.12).
5. **Fides no se modifica.** En v1 el SKU de la variante **no viaja** en el documento fiscal cuando se usa Fides: el XML que arma Fides solo recibe CABYS + descripción + cantidad + precio + impuesto. El SKU sigue siendo la identidad interna (POS, stock, etiquetas, reportes). Para tenants que exijan `CodigoComercial` se habilita el camino directo de Hacienda con una bandera (opción 2 de §15); extender Fides es el objetivo de largo plazo (opción 1). `DetalleSurtido` (combos Hacienda) no aplica a variantes.

### 1.3 Qué NO se hará
- No migrar a microservicios ni a SPA; se mantiene Qute + HTMX.
- No se reemplaza `ArticuloStock` por `StockBodega` (evita reescribir POS/reportes/valoración en una sola fase).
- No se modelan variantes como columnas fijas `talla`/`color` (limita a 2 atributos y obliga migración por negocio).
- No se agrega una tabla `articulo_variante` paralela: la variante **es** una fila de `articulos`.
- No se cambia el emisor XML de Hacienda (`ComprobanteService`): solo se usa el `CodigoComercial` que ya arma.
- No se introduce Flyway; se entregan scripts SQL manuales para prod (convención del repo).
- No se borran datos existentes; migración aditiva con backfill.
- **No se "arregla" el flujo de compras en v1**: hoy `recibirOrden` no mueve stock (§14.6); cambiarlo altera el comportamiento actual de todos los tenants y queda detrás de bandera.

---

## 2. Estado actual verificado

### 2.1 Inventario de modelos (archivo:línea)

| Entidad | Archivo | Clave | Stock | Bodega | Variante |
|---|---|---|---|---|---|
| `Articulos` | `Models/Articulos/Articulos.java:20-26` | `codigo` Long (IDENTITY) | no | no | no |
| `ArticuloStock` | `Models/Articulos/ArticuloStock.java:12-42` | `codigo_barra` String, `unique=true` | `stock` BigDecimal | no | no |
| `Inventario` | `Models/Inventario.java:27-64` | `codigo` int | `cantidad` BigDecimal | no | no |
| `Lote` | `Models/Lote.java:24-64` | `id` Long, FK `articulo_codigo` | `cantidadActual` | no | no |
| `Sucursal` | `Models/Sucursal.java:39-61` | `id` Long; único `(codigo_sucursal, codigo_terminal)` | no | no (registro de terminal POS) | no |
| `OrdenCompra`/`Detalle` | `Models/OrdenCompra.java:21`, `OrdenCompraDetalle.java` | id | no | no | no |
| `ArticuloPrecio` | `Models/Articulos/ArticuloPrecio.java:22-46` | `id` int, FK `articulo_id` | no | no | no |
| `CodigoComercial` | `Models/Detalles/CodigoComercial.java:26-45` | `id` Long, FK `linea_detalle_id` | no | no | `codigo` length = 20 |
| `DetalleSurtido`/`LineaDetalleSurtido` | `Models/Detalles/...` | — | — | — | concepto Hacienda (combos), NO variante |

### 2.2 Servicios críticos verificados
- `Services/InventarioService.java:170-191` `getStock(String barcode)` lee `ArticuloStock.stock` por `codigoBarra` y **devuelve `double`** (`.doubleValue()`, línea 184).
- `Services/InventarioService.java:194-211` `calculateTotalStockForItemByBarcode(String barcode)` hace `SUM(i.cantidad)` sobre `Inventario` filtrando `i.articulo.codigoBarra` y **también devuelve `double`** (línea 203). Es una **segunda fuente de verdad** distinta de `ArticuloStock`.
- `Services/InventarioService.java:548-589` `updateStock(Inventario)` es read-modify-write con `PESSIMISTIC_WRITE` (línea 562) + `@Version` de `ArticuloStock`; **deriva el barcode de la entidad** (`entity.getArticulo().getCodigoBarra()`, línea 550) y **lanza excepción si es nulo/vacío** (551-554). **No valida negativos** y **no conoce bodega**.
- `Services/InventarioService.java:786-790` `RESUMEN_FROM` usa `LEFT JOIN articulostock s ON s.codigo_barra = a.codigobarra` (nombres físicos reales; ver §3.3).
- `Services/CarritoService.java:434-452` `ajustarInventario(...)` crea un `Inventario` por línea con `cantidad.negate()` y llama `inventario.update()`.
- `Services/CarritoService.java:459-476` `ingresarInventario(...)` es el espejo (movimiento "Compra") y tampoco setea bodega.
- `Services/CarritoService.java:554-624` `processCodigoBarra(...)` resuelve artículo por `articulosService.findByBarCode(codigo)` (línea 560) y agrupa líneas por `Articulos.codigo` (línea 580). Solo deja entrar artículos `status && processed && codigoCabys != null` (`esFacturable`, 627-630).
- `Services/OrdenCompraService.java:163-177` `recibirOrden(...)` **solo** setea `fechaEntregaReal`, `estado="RECIBIDA"` y `totalReal`. **No crea `Inventario` ni toca `ArticuloStock`.**
- `Services/LoteService.java:32,46,64,77` solo lista/sugiere lotes; **ningún método descuenta `cantidadActual`**.
- `Services/PublicInvoiceService.java:264-311` `getInventoryValuation()` usa `a.lastPrecio.precioFinal` y `JOIN Articulos a ON a.codigoBarra = s.codigoBarra`: **JPQL inválido** (no existe `lastPrecio` como path ni join a entidad sin relación). El `catch (RuntimeException)` (304-309) devuelve lista vacía: hoy la valoración **siempre** sale vacía.

### 2.3 Rutas/endpoints verificados
- `Controllers/Api/App/ArticuloResource.java` `@Path("/api/app/articulos")`, `@RolesAllowed({"admin","inventario"})`.
- `Controllers/Api/App/EtiquetasResource.java` `@Path("/api/app/etiquetas")`, `@RolesAllowed({"admin","inventario"})`; lista `:93-114`, PDF `:132-169`.
- `Controllers/Api/App/PosResource.java` `@Path("/api/app/pos")` `:101`, `@RolesAllowed({"admin","facturacion"})` `:104`; `/scan` `:259-275`; `/articulos-picker` `:1707-1718`; `/articulo-add-form` `:1727`; `/articulos-sugerencias` `:2312-2327`; `articuloPickerHits` `:2339-2368`.
- `Controllers/Api/App/PosPageResource.java` `@Path("/app/pos")` `:34`, `@RolesAllowed({"admin","facturacion"})` `:35` (firmas de render **no verificadas** en detalle).
- `Controllers/Api/App/Compras/OrdenesPagesResource.java` `@Path("/app/compras/ordenes")` `:20`, `@RolesAllowed({"admin","inventario"})` `:22` (firmas de handlers **no verificadas** en detalle).
- `Controllers/Api/Accounting/InventoryValuationController.java` `@Path("/api/v1/accounting/inventory/valuation")` `:24`, `@GET` `:36-43`. **No** es `/api/app/...`.
- `Controllers/Api/App/InventarioResource.java` `@Path("/api/app/inventario")` `:136`, `@RolesAllowed({"admin","inventario"})` `:138`; `/stock` `:320-338` devuelve `getStock` + `calculateTotalStockForItemByBarcode` por barcode.

### 2.4 Configuración verificada
- `application.properties:33` `quarkus.hibernate-orm.schema-management.strategy=update`.
- `application.properties:167` `%test...strategy=drop-and-create`.
- `application.properties:379` `quarkus.http.auth.permission.secured.paths` es una **lista explícita** de rutas: toda ruta nueva debe agregarse ahí o queda sin autenticación por path (el `@RolesAllowed` sigue aplicando, pero la convención del repo es listarla).
- Logger: `org.jboss.logging.Logger` (patrón del repo, p. ej. `CarritoService.java:41`).
- Dinero/cantidades: `BigDecimal` en `ArticuloStock.stock:42`, `Inventario.cantidad:49`, `Lote.cantidadActual:42-45`, `OrdenCompraDetalle`.

### 2.5 Lo que NO existe (verificado por búsqueda en `app/src/main/java`, 2026-11-13)
- No existe clase `Bodega`, `Almacen`, `Traslado`, `Serie`, `NumeroSerie`, `ArticuloVariante`, `Atributo`. La única dimensión organizativa es `Sucursal`, y `Sucursal` es un **registro de par (sucursal, terminal)**, no una tabla de sucursales únicas (`Sucursal.java:41-43`).
- No hay columna de bodega en `inventario`, `lotes`, `articulostock` ni `sucursal`.
- No hay unicidad de `articulos.codigobarra`: `Articulos.java:41-42` declara `@Column` sin `unique`, y ningún `@Table` de `Models` la declara (búsqueda de `uniqueConstraints`/`unique = true`: solo `ArticuloStock.codigo_barra`, `Sucursal`, `Usuarios`, `OrdenCompra`, `ConsecutivoEmitido`, `ConsecutivoReceptor`, `EnvioFueraLinea`, `ProductoExoneracion`, `MarketplaceCartItem`, `ClientesApi`). El único control es la validación de aplicación en `ArticuloResource.java:498-501` (TOCTOU, no atómica).

---

## 3. Modelo de datos

### 3.1 Variantes — decisión final

**Recomendación: la variante es una fila hija de `articulos` con `articulo_padre_id`; los atributos (talla/color/presentación) se guardan en EAV (`atributo` + `valor_atributo` + `articulo_variante_atributo`).**

Justificación (verificada en §14):
- Todo el pipeline opera sobre `Articulos`: `findByBarCode` (`ArticulosService.java:203`), `ArticuloStock`, `Inventario`, `Lote`, `ArticuloPrecio`, etiquetas, valoración, POS y `ComprobanteService`. Una tabla paralela obligaría a tocar cada ruta.
- La variante necesita código de barras, precio, stock y CABYS propios: eso ya es un `Articulos`.
- El padre queda como agrupador de catálogo y **no se factura** si tiene variantes activas; el POS abre el selector.
- Costo: duplicación de CABYS/familia/impuestos en cada hijo. Se mitiga copiando del padre al crear y validando consistencia (nunca dejando `processed=false`: `esFacturable` lo bloquearía, `CarritoService.java:627-630`).

**Modelo padre/variante:**
- `articulos.articulo_padre_id BIGINT NULL REFERENCES articulos(codigo)`.
- `articulos.es_variante BOOLEAN NOT NULL DEFAULT FALSE`.
- Si un artículo no tiene hijos, es **artículo simple**.
- Cada variante (hijo) tiene su propio `codigobarra`, `cabys_id`, filas en `articuloprecio`, stock vía `ArticuloStock`/`StockBodega`, lotes vía `lotes.articulo_codigo` y `@Version` (`Articulos.version:124`).
- **No existe tabla `articulo_variante`.** `articulo_variante_atributo` referencia `articulos(codigo)` del hijo.
- **No se agrega `variante_id` a `articuloprecio` ni a `lotes`**: el hijo ya es el `articulo_id`/`articulo_codigo`. Agregar ambas columnas duplicaría la relación y permitiría estados inconsistentes.

### 3.2 Bodegas — decisión mantener `ArticuloStock` agregado

**Recomendación: mantener `ArticuloStock` (agregado por `codigo_barra`) + `StockBodega` (detalle por bodega); doble escritura en la misma transacción.**

Justificación:
- `InventarioService.getStock`, `RESUMEN_FROM`, POS (`PosResource:335-338`), valoración y `MarketplaceProductService` leen `ArticuloStock` (`MarketplaceProductService.java:183,201`). Reemplazarlo obliga a reescribir todos en una sola fase (riesgo alto).
- `StockBodega` permite stock por bodega y traslados.
- La agregación se mantiene sincronizada actualizando `ArticuloStock.stock` dentro de la misma transacción que modifica `StockBodega`. Mitigación de desincronización: un único método `StockBodegaService.aplicarMovimiento(...)` que actualiza ambas y valida (§5).
- Alternativa descartada: reemplazar `ArticuloStock` por vista/sum. Rompe rendimiento de reportes (sumas sobre N bodegas × artículos).
- **Clave de `StockBodega` = `articulo_codigo` (PK de `articulos`), no barcode.** El barcode es editable (`ArticulosService.update:73-80` es un `merge` plano) y no es único a nivel de BD (§2.5); usarlo como clave en las tablas nuevas reproduciría el bug de huérfanos de `ArticuloStock`.

### 3.3 Tablas nuevas/modificadas (DDL conceptual)

> **Nombres físicos verificados antes de escribir SQL:** `articulos`, `lotes`, `sucursal`, `articuloprecio`, `inventario`, `codigo_comercial`. La tabla de usuarios es `"Users"` (mixed case: debe ir entre comillas en PostgreSQL). `ArticuloStock` no declara `@Table`: su nombre físico es `articulostock` y sus columnas son `codigo_barra` (actual, `unique`) **y** `codigobarra` (legado, sin uso; `docs/plans/data-audit-8091.md:739`). La columna real de `Articulos.codigoBarra` es `codigobarra` (sin guion bajo; por eso `RESUMEN_FROM` usa `a.codigobarra`). No inventar `articulo_stock`, `usuarios` ni `articulo_precio`.

#### `atributo` (catálogo de atributos)
```sql
CREATE TABLE atributo (
  id BIGSERIAL PRIMARY KEY,
  nombre VARCHAR(60) NOT NULL,          -- "Talla", "Color"
  codigo VARCHAR(40) NOT NULL UNIQUE,   -- "talla", "color"
  tipo VARCHAR(20) NOT NULL DEFAULT 'TEXTO', -- TEXTO|NUMERO|COLOR_HEX
  status BOOLEAN NOT NULL DEFAULT TRUE
);
```

#### `valor_atributo`
```sql
CREATE TABLE valor_atributo (
  id BIGSERIAL PRIMARY KEY,
  atributo_id BIGINT NOT NULL REFERENCES atributo(id),
  valor VARCHAR(80) NOT NULL,           -- "M", "Rojo"
  codigo VARCHAR(40),                   -- opcional slug
  UNIQUE (atributo_id, valor)
);
```

#### `articulos` — columnas de variante
```sql
ALTER TABLE articulos ADD COLUMN articulo_padre_id BIGINT REFERENCES articulos(codigo);
ALTER TABLE articulos ADD COLUMN es_variante BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_articulos_padre ON articulos(articulo_padre_id);
-- El código de barras del hijo ya vive en articulos.codigobarra (sin cambio de columna).
-- Se prohíbe ser padre y variante a la vez:
ALTER TABLE articulos ADD CONSTRAINT ck_articulo_padre_no_self
  CHECK (articulo_padre_id IS NULL OR articulo_padre_id <> codigo);
```

#### Unicidad de código de barras (paso previo obligatorio)
`articulos.codigobarra` **no** es único hoy. No se declara `unique = true` en la entidad: con `schema-management=update` Hibernate intentaría el DDL al arrancar y **fallaría en prod si ya hay duplicados** (el mismo riesgo que documenta `ArticuloStock.java:29-37`). Secuencia manual:

```sql
-- 1. Detectar duplicados ANTES de crear el índice.
SELECT codigobarra, COUNT(*) FROM articulos
WHERE codigobarra IS NOT NULL AND btrim(codigobarra) <> ''
GROUP BY codigobarra HAVING COUNT(*) > 1;

-- 2. Resolverlos a mano (el plan no decide cuál gana).
-- 3. Crear índice único parcial y índice de búsqueda.
CREATE UNIQUE INDEX uk_articulos_codigobarra
  ON articulos (codigobarra)
  WHERE codigobarra IS NOT NULL AND btrim(codigobarra) <> '';
```

> Si el paso 1 devuelve filas, **detener la migración**: el índice no se puede crear. El índice parcial permite varios `NULL`/vacíos (artículos sin barcode, incluidos padres con variantes).

#### `articulo_variante_atributo`
```sql
CREATE TABLE articulo_variante_atributo (
  variante_id BIGINT NOT NULL REFERENCES articulos(codigo) ON DELETE CASCADE,
  atributo_id BIGINT NOT NULL REFERENCES atributo(id),
  valor_atributo_id BIGINT NOT NULL REFERENCES valor_atributo(id),
  PRIMARY KEY (variante_id, atributo_id)
);
CREATE INDEX idx_ava_atributo ON articulo_variante_atributo(atributo_id);
```

#### `bodega`
`Sucursal` no sirve como FK de sucursal única: su única restricción es `(codigo_sucursal, codigo_terminal)` (`Sucursal.java:41-43`). La bodega se cuelga del **código de sucursal** (`VARCHAR(3)`), que es el identificador de negocio que comparten todos los terminales.

```sql
CREATE TABLE bodega (
  id BIGSERIAL PRIMARY KEY,
  codigo_sucursal VARCHAR(3) NOT NULL,   -- "001"; NO es sucursal.id
  codigo VARCHAR(20) NOT NULL,
  nombre VARCHAR(120) NOT NULL,
  descripcion VARCHAR(300),
  es_principal BOOLEAN NOT NULL DEFAULT FALSE,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (codigo_sucursal, codigo)
);
CREATE INDEX idx_bodega_sucursal ON bodega(codigo_sucursal);
-- Una sola principal por sucursal:
CREATE UNIQUE INDEX uk_bodega_principal
  ON bodega (codigo_sucursal) WHERE es_principal;
```

#### `stock_bodega`
```sql
CREATE TABLE stock_bodega (
  id BIGSERIAL PRIMARY KEY,
  bodega_id BIGINT NOT NULL REFERENCES bodega(id),
  articulo_codigo BIGINT NOT NULL REFERENCES articulos(codigo),
  cantidad NUMERIC(18,3) NOT NULL DEFAULT 0,
  last_updated TIMESTAMP NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (bodega_id, articulo_codigo),
  CHECK (cantidad >= 0)                 -- ver §5.3: los negativos se bloquean aquí
);
CREATE INDEX idx_sb_bodega ON stock_bodega(bodega_id);
CREATE INDEX idx_sb_articulo ON stock_bodega(articulo_codigo);
```

#### `traslado`
```sql
CREATE TABLE traslado (
  id BIGSERIAL PRIMARY KEY,
  numero_traslado VARCHAR(40) NOT NULL UNIQUE,
  bodega_origen_id BIGINT NOT NULL REFERENCES bodega(id),
  bodega_destino_id BIGINT NOT NULL REFERENCES bodega(id),
  estado VARCHAR(20) NOT NULL DEFAULT 'BORRADOR', -- BORRADOR/ENVIADO/RECIBIDO/ANULADO
  fecha_creacion TIMESTAMP NOT NULL DEFAULT NOW(),
  fecha_envio TIMESTAMP,
  fecha_recepcion TIMESTAMP,
  usuario_envia_id BIGINT REFERENCES "Users"(id),
  usuario_recibe_id BIGINT REFERENCES "Users"(id),
  notas VARCHAR(500),
  version BIGINT NOT NULL DEFAULT 0,
  CHECK (bodega_origen_id <> bodega_destino_id)
);
```

#### `traslado_detalle`
```sql
CREATE TABLE traslado_detalle (
  id BIGSERIAL PRIMARY KEY,
  traslado_id BIGINT NOT NULL REFERENCES traslado(id) ON DELETE CASCADE,
  articulo_codigo BIGINT NOT NULL REFERENCES articulos(codigo),
  cantidad_enviada NUMERIC(18,3) NOT NULL,
  cantidad_recibida NUMERIC(18,3),
  diferencia NUMERIC(18,3),
  lote_id BIGINT REFERENCES lotes(id),
  notas VARCHAR(300),
  CHECK (cantidad_enviada > 0),
  CHECK (cantidad_recibida IS NULL OR cantidad_recibida >= 0)
);
CREATE INDEX idx_td_traslado ON traslado_detalle(traslado_id);
```

#### Modificaciones a tablas existentes
```sql
-- Inventario: bodega del movimiento (nullable para histórico). La variante ya
-- está en inventario.articulo_codigo -> articulos.codigo.
ALTER TABLE inventario ADD COLUMN bodega_id BIGINT REFERENCES bodega(id);
CREATE INDEX idx_inventario_bodega ON inventario(bodega_id);

-- Lote: bodega. NO se agrega variante_id: lotes.articulo_codigo ya apunta al hijo.
ALTER TABLE lotes ADD COLUMN bodega_id BIGINT REFERENCES bodega(id);
CREATE INDEX idx_lotes_bodega ON lotes(bodega_id);

-- Serie (nueva)
CREATE TABLE serie (
  id BIGSERIAL PRIMARY KEY,
  articulo_codigo BIGINT NOT NULL REFERENCES articulos(codigo),
  numero_serie VARCHAR(80) NOT NULL,
  bodega_id BIGINT REFERENCES bodega(id),
  lote_id BIGINT REFERENCES lotes(id),
  estado VARCHAR(20) NOT NULL DEFAULT 'EN_STOCK', -- EN_STOCK/VENDIDO/DEVUELTO/BAJA
  fecha_ingreso TIMESTAMP NOT NULL DEFAULT NOW(),
  fecha_venta TIMESTAMP,
  comprobante_clave VARCHAR(60),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (articulo_codigo, numero_serie)
);
CREATE INDEX idx_serie_articulo ON serie(articulo_codigo);
CREATE INDEX idx_serie_bodega ON serie(bodega_id);
```

#### `@Version` en entidades nuevas
- `Articulos.version` (ya existe, `:124`), `Bodega.version`, `StockBodega.version`, `Traslado.version`, `Serie.version` (bloqueo optimista, patrón de `ArticuloStock.version:54`).
- `Inventario` **no** tiene `@Version` y no se le agrega: el arbitraje de concurrencia sigue en `ArticuloStock`/`StockBodega`.

### 3.4 Integración con `Articulos` padre
- Agregar a `Articulos`: `@ManyToOne Articulos articuloPadre` y `@OneToMany(mappedBy="articuloPadre") List<Articulos> variantes` (LAZY), más `esVariante`.
- El padre conserva `codigobarra`; si tiene variantes activas, el POS no lo factura (abre selector). No es obligatorio dejarlo null.
- Los hijos copian `cabys_id`, `departamento`, `familia`, `unidadMedida`, `unidadMedidaComercial`, `exento` y `tipoRefrigeracion` del padre al crearse; si el padre cambia, una validación avisa de la divergencia. El hijo nace con `processed=true` y `status=true` (si no, `esFacturable` lo rechaza en POS).
- **Longitud de SKU:** `codigo_comercial.codigo` es `VARCHAR(20)` (`CodigoComercial.java:43-45`). Un código de barras de variante de más de 20 caracteres **no se puede facturar por el camino directo**. Restricción: el `codigobarra` de una variante facturable debe caber en 20 caracteres, o se amplía la columna en una migración aparte.

---

## 4. Estrategia de migración

### 4.1 Variantes — artículos existentes
**Decisión: artículo simple (sin variantes).** No crear variante implícita.

Justificación:
- Evita duplicar datos y mantener dos caminos (padre con variante implícita vs simple).
- El código existente (POS, reportes, etiquetas) sigue usando `Articulos.codigobarra` y `ArticuloStock` sin cambios.
- Cuando un usuario quiera variantes, las crea desde la ficha del artículo; el artículo pasa a "con variantes" y su `codigobarra` puede quedar null o marcarse no facturable.

SQL de migración (aditivo, idempotente):
```sql
-- 1. Tablas nuevas (Hibernate 'update' las crea en dev; en prod ejecutar manual)
--    (DDL de §3.3, incluido el índice único parcial de codigobarra tras deduplicar)

-- 2. Backfill bodegas: una "Bodega Principal" por CÓDIGO de sucursal.
--    sucursal es un registro de terminales; agrupar por id crearía una bodega
--    por caja. Se toma el código de sucursal y se ignora el terminal.
INSERT INTO bodega (codigo_sucursal, codigo, nombre, es_principal, activo)
SELECT DISTINCT s.codigo_sucursal, 'PRINCIPAL', 'Bodega Principal', TRUE, TRUE
FROM sucursal s
WHERE s.activo = TRUE
  AND NOT EXISTS (
    SELECT 1 FROM bodega b
    WHERE b.codigo_sucursal = s.codigo_sucursal AND b.es_principal
  );

-- 3. Backfill stock_bodega desde articulostock (tabla real, columna codigo_barra).
--    Se resuelve articulo_codigo por el barcode; las filas de articulostock sin
--    artículo (huérfanas) NO se migran: se listan antes para revisión.
SELECT s.codigo_barra, s.stock
FROM articulostock s
WHERE s.codigo_barra IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM articulos a WHERE a.codigobarra = s.codigo_barra);

INSERT INTO stock_bodega (bodega_id, articulo_codigo, cantidad)
SELECT b.id, a.codigo, s.stock
FROM articulostock s
JOIN articulos a ON a.codigobarra = s.codigo_barra
JOIN bodega b ON b.es_principal = TRUE
              AND b.codigo_sucursal = (
                    SELECT codigo_sucursal FROM sucursal
                    WHERE activo = TRUE ORDER BY codigo_sucursal LIMIT 1)
WHERE s.stock IS NOT NULL AND s.stock > 0
ON CONFLICT (bodega_id, articulo_codigo) DO UPDATE
  SET cantidad = EXCLUDED.cantidad;

-- 4. Backfill inventario.bodega_id (movimientos históricos -> principal).
--    Inventario no guarda sucursal: solo puede asignarse a la principal elegida.
UPDATE inventario SET bodega_id = (
  SELECT b.id FROM bodega b
  WHERE b.es_principal = TRUE
  ORDER BY b.codigo_sucursal LIMIT 1)
WHERE bodega_id IS NULL;

-- 5. Backfill lotes.bodega_id (mismo criterio)
UPDATE lotes SET bodega_id = (
  SELECT b.id FROM bodega b
  WHERE b.es_principal = TRUE
  ORDER BY b.codigo_sucursal LIMIT 1)
WHERE bodega_id IS NULL;
```

> **Nota sobre el stock negativo histórico:** `updateStock` no valida negativos (`InventarioService.java:555-576`), así que `articulostock.stock` puede quedar negativo; el origen DBF también traía negativos (`data-audit-8091.md:757`). El `CHECK (cantidad >= 0)` de `stock_bodega` haría fallar el backfill si se copian negativos. Por eso el `WHERE s.stock > 0`: los negativos **no** se replican en bodega; siguen visibles en `ArticuloStock` (agregado) y se listan para saneamiento. Documentar esta decisión: `SUM(stock_bodega)` no iguala `articulostock.stock` para artículos con saldo negativo.

**Reversión:** las tablas nuevas se pueden `DROP` sin afectar las existentes; columnas nuevas son nullable y se pueden dejar. Para revertir stock, `ArticuloStock` sigue siendo la fuente de verdad agregada; borrar `stock_bodega` no rompe POS/reportes. Documentar que la reversión pierde datos de bodega/traslados.

### 4.2 Bodegas — mantener `ArticuloStock`
- `ArticuloStock` no se elimina; `StockBodega` es detalle. El agregado se recalcula en cada movimiento.
- Migración: backfill §4.1.

### 4.3 Orden de ejecución (prod)
1. Backup de BD.
2. **Detección de duplicados de `articulos.codigobarra`** y resolución manual (§3.3). Sin este paso no se crea el índice único.
3. Ejecutar DDL aditivo (tablas + columnas nullable + índices + `CHECK` + índice único parcial) en ventana de baja carga.
4. Ejecutar backfill (bodegas, stock_bodega, inventario.bodega_id, lotes.bodega_id).
5. Desplegar app (Hibernate `update` es no-op porque todo ya existe; verificar el log de arranque).
6. Verificar conteos:
```sql
SELECT COUNT(*) FROM articulostock;
SELECT COUNT(*) FROM stock_bodega;
SELECT COUNT(*) FROM stock_bodega sb
LEFT JOIN bodega b ON b.id = sb.bodega_id WHERE b.id IS NULL;  -- esperado 0
```
7. Smoke tests POS/etiquetas/reportes.

### 4.4 Concurrencia en migración
- Ejecutar en transacción por bloque; si falla, rollback.
- Para bases grandes, backfill de `inventario.bodega_id` en lotes de 100k (`WHERE bodega_id IS NULL LIMIT 100000` repetido) para no bloquear.
- El índice único parcial se crea `CONCURRENTLY` en prod (`CREATE UNIQUE INDEX CONCURRENTLY ...`), fuera de transacción, para no bloquear escrituras.

---

## 5. API / endpoints

### 5.1 Variantes
Base: `/api/app/articulos` (existente, `@RolesAllowed admin,inventario`).

Nuevos:
- `GET /api/app/articulos/{codigo}/variantes` — lista variantes del padre. Response: `List<VarianteDTO>`.
- `POST /api/app/articulos/{codigo}/variantes` — crea variante. Request: `{codigoBarra, nombreVariante, atributos:[{atributoId, valorId}], precioCostoSinIVA?, porcentajeUtilidad?, imagenUrl?, stockOptimo?}`. 409 si código de barras duplicado (reutilizar `MSG_BARRA_DUPLICADA`), validando `length <= 20` si el tenant usa Fides/directo.
- `PUT /api/app/variantes/{codigo}` — edita variante (por `codigo`, PK de `articulos`, no por id inexistente).
- `DELETE /api/app/variantes/{codigo}` — soft delete (`status=false`).
- `GET /api/app/atributos` — catálogo de atributos.
- `POST /api/app/atributos` — crea atributo (admin).
- `POST /api/app/atributos/{id}/valores` — crea valor.

### 5.2 Bodegas
Base: `/api/app/bodegas` (nuevo, `@RolesAllowed admin,inventario`).
- `GET /api/app/bodegas?sucursalCodigo=` — lista.
- `POST /api/app/bodegas` — crea (valida una sola `es_principal` por `codigo_sucursal`).
- `PUT /api/app/bodegas/{id}` — edita.
- `GET /api/app/bodegas/{id}/stock?q=` — stock por bodega (`stock_bodega` + `articulos`).

### 5.3 Traslados
Base: `/api/app/traslados`.
- `GET /api/app/traslados?estado=&page=&size=` — lista.
- `POST /api/app/traslados` — crea borrador. Request: `{bodegaOrigenId, bodegaDestinoId, notas, detalles:[{articuloCodigo, cantidad, loteId?}]}`.
- `POST /api/app/traslados/{id}/enviar` — BORRADOR→ENVIADO: valida stock origen suficiente (`stock_bodega.cantidad >= cantidad`, con `PESSIMISTIC_WRITE`), decrementa origen. **No** incrementa destino todavía (mercancía en tránsito).
- `POST /api/app/traslados/{id}/recibir` — ENVIADO→RECIBIDO: registra `cantidad_recibida`, calcula diferencia, incrementa destino, crea movimientos `Inventario` tipo "Traslado" con `bodega_id` de destino y `bodega_origen` en notas.
- `POST /api/app/traslados/{id}/anular` — solo BORRADOR. Post-envío no se anula en v1: se recibe con diferencia o se crea un traslado inverso manual.
- **Semántica de `aplicarMovimiento`:** un traslado ENVIADO **no** debe tocar `ArticuloStock` agregado (sale de una bodega pero sigue en la empresa). Solo al ANULAR/RECIBIR con diferencia se ajusta el agregado, y solo por la merma (`cantidad_enviada - cantidad_recibida`). Si se implementa como "restar en origen y sumar en destino", el agregado queda intacto y el detalle por bodega consistente.

### 5.4 Inventario
- Modificar `Inventario` entity: `bodegaId` nullable (`@ManyToOne Bodega`).
- Endpoints existentes (`/api/app/inventario`) añadir filtro `?bodegaId=`.
- `/api/app/inventario/stock` (`InventarioResource.java:320-338`) añadir `?bodegaId=`: sin parámetro devuelve el agregado actual; con parámetro, `stock_bodega`. Mantener la firma de `getStock(String)` para no romper `ArticuloResource.java:1870`; agregar `getStock(String, Long bodegaId)` que devuelva `BigDecimal`.

### 5.5 POS
- `processCodigoBarra` (`CarritoService.java:554`) resuelve por código de barras; si el código es de un padre con variantes activas, devolver `CartOperationResult` con estado nuevo `ARTICULO_CON_VARIANTES` y no agregar. La UI abre selector y agrega el hijo por su propio código.
- Detección: `articulo.getArticuloPadre() == null && !articulo.getVariantes().isEmpty()`. Cuidado con LAZY: usar un `exists` por consulta (`VarianteService.tieneVariantesActivas(codigo)`) en lugar de inicializar la colección en cada escaneo.
- Añadir `bodegaId` al `CartSessionContext` para descontar stock de la bodega activa.

### 5.6 Facturación (XML Hacienda) — verificado
- `ComprobanteService.java:1390-1402`: para todo documento que no sea REP, arma `CodigoComercial` con `tipo="04"` (línea 1394) y `codigo = articulo.getArticulo().getCodigoBarra()` (línea 1395). No hay que cambiar nada: con variante-hijo, ese código es el del hijo.
- `linea.setDetalle(...)` usa `articulo.getArticulo().getNombre()` (línea 1382): el nombre del hijo debe ser descriptivo ("Camisa Roja M") o el documento fiscal no distingue variantes.
- `linea.setCodigoCabys(...)` (línea 1391) usa el CABYS del hijo; si el hijo no lo copió, NPE (el POS lo bloquea antes con `esFacturable`, pero otros caminos como FEC no).
- `CodigoComercial.codigo` es `VARCHAR(20)`: un SKU más largo falla al persistir el comprobante, no al enviar.
- La moneda está fija en CRC en `ComprobanteService.java:1211-1212` (`moneda.setCodigoMoneda("CRC")`), **también en el camino directo**. Enrutar a Hacienda directa no habilita multi-moneda.
- `DetalleSurtido` (combos Hacienda) NO se usa para variantes.

### 5.7 Etiquetas
- `EtiquetasResource` (`:93-114`, `:132-169`) trabaja sobre `Articulos` con `ListAllEnabled()` y filtra en memoria. Extender: `?incluirVariantes=true` (default excluye `es_variante=true` para no inundar la lista) y permitir imprimir por variante con precio propio o heredado (`precioFinalDe:235-243` ya tolera precio nulo, pero deja la etiqueta sin precio; implementar fallback al padre).

### 5.8 Reportes / valoración
- `InventoryValuationController` (`/api/v1/accounting/inventory/valuation`, `:24,36-43`) delega en `PublicInvoiceService.getInventoryValuation()`, **que hoy devuelve vacío por JPQL inválido** (§2.2). Antes de agregar bodega hay que reescribir esa consulta.
- Reportes de stock por bodega: usar `stock_bodega` con join a `bodega`.

### 5.9 Permisos
- Agregar a `application.properties:379` `secured.paths`: `/api/app/bodegas`, `/api/app/bodegas/*`, `/api/app/traslados`, `/api/app/traslados/*`, `/api/app/variantes`, `/api/app/variantes/*`, `/api/app/atributos`, `/api/app/atributos/*`.

---

## 6. UI Qute/HTMX

### 6.1 Reutilización
- `_kit/` data-table, paginación, dialogs (según `docs/ui-kit.md` §2.9, §3.1). Reutilizar templates existentes: `pages/articulos/form-articulo.html`, `tabla-articulos.html`.
- Fragmentos duales (HX-Request → fragmento; sin HX → página completa) como en `ArticuloResource`.

### 6.2 Pantallas nuevas
1. **Ficha de artículo — pestaña "Variantes"** (`pages/articulos/variantes.html`): tabla de variantes + dialog crear/editar con selector de atributos (chips). Estado vacío: "Este artículo no tiene variantes. Las ventas se harán por el código del artículo."
2. **Bodegas** (`pages/bodegas/index.html`): CRUD + stock por bodega. Mostrar el código de sucursal, no el par terminal.
3. **Traslados** (`pages/traslados/index.html` + `form-traslado.html`): wizard BORRADOR→ENVIADO→RECIBIDO; diferencias resaltadas en rojo.
4. **POS**: selector de bodega activa (dropdown en header); al escanear padre con variantes, modal selector.
5. **Inventario**: filtro por bodega; conteo físico por bodega.
6. **Etiquetas**: checkbox "incluir variantes".

### 6.3 Estados vacío/error
- Variante sin stock: badge "Sin stock".
- Traslado con diferencia: resaltar fila y exigir motivo en notas.
- Bodega sin stock: empty state con CTA "Recibir mercancía".

---

## 7. Integración con sistemas existentes

### 7.1 POS
- `CarritoService.processCodigoBarra` (`:554-624`): si el artículo escaneado es un padre con variantes activas, devolver `ARTICULO_CON_VARIANTES` y no agregar; la UI abre selector y agrega el hijo por su propio código. Como la variante es un `Articulos`, `ArticuloCarrito` y todo el pipeline siguen sin cambios.
- Variante = fila `Articulos` con `articulo_padre_id` set; padre = fila sin padre (o con variantes).
- `ArticuloStock`, `Inventario`, `Lote` siguen operando sobre el hijo sin cambios de modelo.
- La tabla `articulo_variante` se reduce a metadata de atributos (`articulo_variante_atributo` apunta a `articulos.codigo` del hijo).

> **Esta es la decisión arquitectónica más importante.** Se recomienda la opción "hijo Articulos" porque el costo de adaptar todo el pipeline a una entidad nueva supera el costo de una columna.

### 7.2 Facturación
- `ComprobanteService` ya usa `codigoBarra` y `getCodigoCabys()` del artículo; con variantes-hijo, ambos existen (§5.6).
- `DetalleSurtido` no aplica.

### 7.3 Compras
- `OrdenCompraDetalle.articulo` (FK `articulos.codigo`) acepta hijo. Recibir por variante = recibir el hijo. Sin cambios de modelo; solo UI selector.
- **La recepción no mueve stock hoy** (`OrdenCompraService.java:163-177`). Ver §14.6 antes de asumir que "recibir compra suma".

### 7.4 Etiquetas
- `EtiquetasResource` trabaja sobre `Articulos`. Con variantes-hijo, aparecen si `incluirVariantes=true`. Default: excluirlas para no duplicar el catálogo en la lista.

### 7.5 Reportes / valoración
- Agrupar por `articulo_padre_id` (o por código si null) para reportes de padre.
- Stock por bodega: `stock_bodega`.
- La valoración está rota hoy (§2.2): corregirla es prerrequisito, no parte del feature de bodegas.

### 7.6 Fides
- Fides transmite un XML que arma a partir de `FidesApiService.InvoiceData`: por ítem solo viajan `code` (CABYS), `description`, `quantity`, `unitPrice`, `taxRate` y `discount` (`FidesApiService.java:123-131`, mapeo `:327-343`). **El `CodigoComercial` tipo "04" que arma `ComprobanteService` no se serializa a `InvoiceData` y se pierde en modo Fides.**
- `DetalleSurtido` es contenido XML de combos Hacienda que Fides transporta — no tiene relación con variantes comerciales.
- Opciones y recomendación: §15.

---

## 8. Fases, esfuerzo y dependencias

| Fase | Contenido | Días | Depende de |
|---|---|---|---|
| 0 | Scripts SQL DDL + dedupe de barcode + backfill (§4) + revisión | 2 | — |
| 1 | Entidades JPA: `Atributo`, `ValorAtributo`, columnas en `Articulos`/`Inventario`/`Lote`, `Bodega`, `StockBodega`, `Traslado`/`Detalle`, `Serie` | 3 | 0 |
| 2 | Servicios: `BodegaService`, `StockBodegaService` (doble escritura), `TrasladoService`, `VarianteService` | 5 | 1 |
| 3 | Migración de `InventarioService.updateStock` para actualizar `StockBodega` + `ArticuloStock` | 2 | 2 |
| 4 | POS: selector de bodega, selector de variantes, descontar de bodega activa | 4 | 3 |
| 5 | UI Qute/HTMX: variantes en ficha, bodegas, traslados, conteo físico | 6 | 2 |
| 6 | Compras: recibir por variante/bodega | 2 | 2 |
| 7 | Etiquetas + reportes + valoración por bodega/variante | 3 | 2 |
| 8 | Lotes/series por bodega | 2 | 1 |
| 9 | Pruebas + smoke + ajustes | 4 | todas |
| **Total** | | **~33 días** | |

> El total de §8 es el plan original; §17 lo reemplaza con los ajustes por lo verificado (Fides, valoración rota, barcode).

---

## 9. Criterios de aceptación verificables

1. Crear un artículo padre con 3 variantes (talla S/M/L, color rojo/azul) y códigos de barras distintos; las 3 aparecen en POS al escanear.
2. Escanear el código del padre (si tiene variantes) abre selector; seleccionar variante agrega el SKU correcto.
3. Stock por variante es independiente; vender variante M decrementa solo M.
4. Crear bodega "Bodega 2" en la sucursal 001; trasladar 10 unidades de A a B; stock origen -10, destino +10 tras RECIBIDO; diferencia calculada.
5. Migración: todos los `articulostock` existentes con `stock > 0` aparecen en `stock_bodega` de la Bodega Principal de su sucursal; `inventario.bodega_id` no null en filas migradas.
6. Reporte de resumen de inventario filtra por bodega y muestra variantes.
7. Etiquetas imprime por variante con su precio (heredado o propio).
8. Factura **directa** de venta de variante incluye el código de barras de la variante en `CodigoComercial` tipo "04" (`ComprobanteService.java:1392-1397`).
9. Concurrencia: dos ventas simultáneas de la misma variante no desincronizan stock; la perdedora recibe `OptimisticLockException` (409) o se serializa por `PESSIMISTIC_WRITE`. **No** se exige "stock nunca negativo": `updateStock` no valida negativos y hoy vender sin stock está permitido.
10. Un artículo variante sin `processed=true`/`status=true`/CABYS no entra al POS (`esFacturable`, `CarritoService.java:627-630`); la creación de variante asigna esos campos.

---

## 10. Plan de pruebas

- **Unitarias:** `StockBodegaService` (suma, transferencia, concurrencia mock), `VarianteService` (unicidad de código de barras, herencia de precio), `PrecioVigente` (hijo con y sin precio).
- **Integración:** `mercurius_test` (`drop-and-create`, `application.properties:167`). Seeds: sucursal (2 terminales del mismo `codigo_sucursal`), bodega principal, artículos, variantes. Tests de: traslado completo, doble escritura, recálculo del agregado, backfill.
- **Regresión:** POS escaneo, etiquetas PDF, `/api/v1/accounting/inventory/valuation` (hoy vacío: el test debe fallar antes del fix y pasar después), `/api/app/inventario/stock`.
- **E2E (manual, sin browser automation salvo autorización del usuario):** POS escaneo, traslado, etiquetas PDF.

---

## 11. Riesgos y mitigación

| Riesgo | Mitigación |
|---|---|
| Duplicados de código de barras | Detección previa + índice único parcial (§3.3). No usar `unique=true` en la entidad por el riesgo de DDL al arrancar con `schema-management=update`. |
| Edición de código de barras deja huérfano `ArticuloStock` | `StockBodega` se keyea por `articulo_codigo` (inmune). Para el agregado, `StockBodegaService.renombrarCodigoBarra(...)` migra la fila de `articulostock` en la misma tx. Si no se implementa, bloquear la edición de barcode con stock ≠ 0. |
| Stock partido entre `ArticuloStock` y `StockBodega` | Un único método `aplicarMovimiento` actualiza ambas en la misma tx; test de reconciliación (con la salvedad de negativos históricos, §4.1). |
| Dos fuentes de stock (`ArticuloStock` vs `SUM(Inventario)`) | Documentar cuál usa cada pantalla (§14.1). Los reportes de bodega usan `stock_bodega`/`Inventario.bodega_id`; el POS usa `ArticuloStock`. No mezclarlas en una misma cifra. |
| Precisión | `getStock` y `calculateTotalStockForItemByBarcode` devuelven `double` (`:184,:203`) y `StockAlertService.getCurrentStock` devuelve `Integer` truncando (`:450`). Los caminos nuevos usan `BigDecimal`; corregir los existentes es deuda separada. |
| Concurrencia | `@Version` + `PESSIMISTIC_WRITE` (patrón `InventarioService.updateStock:548-589`) sobre `ArticuloStock` y `StockBodega`. |
| Negativos | `CHECK (cantidad >= 0)` en `stock_bodega`; validación explícita en traslados. En ventas se mantiene la política actual (permitir negativo) salvo que el tenant active bloqueo. |
| Migración | Backup, aditivo, índice `CONCURRENTLY`, backfill por lotes, verificación de conteos, revisión de huérfanos. |
| Rendimiento de reportes | Índices; vistas materializadas si hace falta; no sumar en caliente en POS. |
| Herencia de precio | `getPrecioVigente()` busca precio del hijo y, si no hay, el del padre; cachear en el DTO de la línea para evitar N+1. |
| Explosión de filas por variantes | POS picker (`PosResource.java:2339-2368`) y etiquetas (`EtiquetasResource.java:100`) cargan **todo** el catálogo habilitado con `precios` EAGER (`Articulos.java:102-108`). Con variantes se multiplica. Excluir `es_variante` del picker de padres y del listado de etiquetas por defecto. |

---

## 12. Logging
- Toda nueva operación con try/catch emite log con `org.jboss.logging.Logger` al nivel adecuado (ERROR/WARN/INFO). Sin catch vacíos. Incluir contexto: `bodegaId`, `articuloCodigo`, `codigoBarra`, usuario, `trasladoId`.
- En particular: huérfanos de backfill (WARN con el barcode), diferencias de traslado (INFO), rechazos por stock insuficiente (WARN), conflictos de versión (WARN, ya existe el patrón en `InventarioService.java:578-581`).

---

## 13. Lo que se reutiliza y lo que se descarta

### Reutilizar (rutas verificadas)
- `Models/Articulos/Articulos.java` — padre de catálogo.
- `Models/Articulos/ArticuloStock.java` — agregado por código de barras.
- `Models/Inventario.java` — bitácora (añadir `bodega_id`).
- `Models/Lote.java` — añadir `bodega_id` (el artículo ya identifica la variante).
- `Models/Sucursal.java` — **solo** como fuente de `codigo_sucursal` para la bodega; no como FK.
- `Services/InventarioService.java` — `getStock`, `updateStock`, reportes.
- `Services/CarritoService.java` — `processCodigoBarra`, `ajustarInventario`.
- `Controllers/Api/App/ArticuloResource.java` — CRUD + variantes en ficha.
- `Controllers/Api/App/EtiquetasResource.java` — etiquetas por variante.
- Templates `_kit/`, `pages/articulos/*`.

### Descartar / no usar
- `Models/Detalles/DetalleSurtido` / `LineaDetalleSurtido` — concepto Hacienda (combos XML), no variantes comerciales.
- Tabla `articulo_variante` paralela — la variante es `articulos`.
- Columnas `variante_id` en `articuloprecio`/`lotes` — redundantes con `articulo_id`/`articulo_codigo`.
- Microservicios/SPA — fuera de alcance.
- Flyway — no se introduce; scripts SQL manuales.

---

## 14. Inventario de impacto en código

> Formato: **archivo:línea → supuesto actual → qué se rompe → cambio mínimo**. Todo lo listado fue leído en el repo el 2026-11-13. "No verificado" cuando la firma o el comportamiento no se revisó completo.

### 14.1 Stock: dos fuentes de verdad

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/InventarioService.java:170-191` `getStock(String)` | `ArticuloStock.stock` por barcode; devuelve `double` | No distingue bodega; `double` pierde precisión para ventas por peso; NPE si `stock` es NULL (el `get(0).doubleValue()` está fuera del `catch PersistenceException`) | Mantener firma; agregar `getStock(String, Long bodegaId)` que lea `stock_bodega` y devuelva `BigDecimal` |
| `Services/InventarioService.java:194-211` `calculateTotalStockForItemByBarcode` | `SUM(Inventario.cantidad)` filtrando `articulo.codigoBarra`; devuelve `double` | Es una **segunda** fuente de stock que **no** coincide necesariamente con `ArticuloStock`; con bodega no filtra; con padre+variantes el barcode del padre no suma hijos | Agregar overload por `articuloCodigo` y `bodegaId`; no cambiar la firma existente |
| `Services/InventarioService.java:548-589` `updateStock` | Read-modify-write sobre `ArticuloStock`; barcode derivado del artículo (`:550`); sin validación de negativos | No actualiza `StockBodega`; no setea `Inventario.bodegaId`; si el artículo no tiene barcode lanza `IllegalArgumentException` (`:551-554`) | Extender: dentro de la misma tx, `StockBodegaService.aplicarMovimiento(...)`; setear `bodegaId`; mantener la excepción para artículos sin barcode (padres con variantes no deben generar movimientos) |
| `Services/InventarioService.java:742-750` `getAllStock` | Lista `ArticuloStock` completo | Sin bodega | Sin cambio de firma; filtro opcional en el recurso |
| `Services/InventarioService.java:752-763` `findStockByArticleCode(int)` | Subquery por `articulos.codigo` → barcode; `catch (Exception) { return null; }` **sin log** | Viola la regla de logging del repo; con variantes sigue funcionando (hijo = artículo) | Agregar log WARN en el catch (deuda inmediata, no opcional) |
| `Services/InventarioService.java:786-790` `RESUMEN_FROM` | `LEFT JOIN articulostock s ON s.codigo_barra = a.codigobarra` | No distingue bodega; si el barcode cambió, el join se rompe | Añadir `LEFT JOIN stock_bodega sb ON sb.articulo_codigo = a.codigo AND sb.bodega_id = :bodegaId` en la variante por bodega; no tocar el `FROM` global |
| `Services/InventarioService.java:1062-1064` join de último precio | Subquery `MAX(ap.id)` por artículo | Patrón reutilizable para la valoración (ver 14.9) | Reutilizar el patrón, no la consulta |
| `Controllers/Api/App/InventarioResource.java:320-338` `/stock` | Devuelve `getStock` + `calculateTotalStock` por barcode | Sin bodega | Añadir `?bodegaId=` y devolver `BigDecimal` |
| `Controllers/Api/Mercatus/InventoryController.java:45-57` | `findStockByArticleCode(articleId)` | Con variantes, el padre puede tener 0 y los hijos el stock real | Resolver por el código consultado (el hijo es un artículo válido); documentar que el padre no agrega |
| `Services/MarketplaceProductService.java:183,201` | `SELECT a.stock FROM ArticuloStock a WHERE a.codigoBarra = :barcode` | Con variantes, cada SKU es su propia fila: OK | Sin cambio |
| `Models/Articulos/ArticuloStock.java:39-40` | `codigo_barra` único | La unicidad es del **barcode**, no del artículo: dos artículos con el mismo barcode comparten fila de stock | El índice único de `articulos.codigobarra` (§3.3) cierra el caso hacia adelante |

### 14.2 Código de barras e identidad

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Models/Articulos/Articulos.java:41-42` | `codigobarra` sin `unique` | Nada impide dos artículos (o un padre y su variante) con el mismo barcode; `findByBarCode` devuelve uno arbitrario | Índice único parcial en BD + validación de servicio; **no** `unique=true` en la entidad |
| `Services/ArticulosService.java:203-213` `findByBarCode` | `getResultList().get(0)` sin `ORDER BY` ni `setMaxResults(1)` | Con duplicados, el artículo vendido depende del plan de la BD; no determinista | `ORDER BY a.codigo` + `setMaxResults(1)`; log WARN si hay más de uno |
| `Controllers/Api/App/ArticuloResource.java:498-501` | Valida duplicado con `findByBarCode(...) != null` antes de crear | TOCTOU: dos altas concurrentes pasan; no hay constraint que arbitre | Mantener la validación (mensaje 409 `MSG_BARRA_DUPLICADA`) y confiar en el índice único para la carrera |
| `Services/ArticulosService.java:73-80` `update` | `em.merge` plano | Cambiar el barcode deja la fila vieja de `articulostock` huérfana y crea una nueva al siguiente movimiento: **stock partido** | `StockBodegaService.renombrarCodigoBarra(articulo, viejo, nuevo)` en la misma tx, o bloquear la edición con stock ≠ 0 |
| `Services/CarritoService.java:554-624` `processCodigoBarra` | Resuelve y agrega por `Articulos.codigo` (`:580`) | Si el barcode es de un padre con variantes, agrega el padre (que no debe facturarse) | Detectar padre con variantes activas y devolver `ARTICULO_CON_VARIANTES` antes de agregar |
| `Services/ImportacionMasivaService.java:705,1002` | `findByBarCode(codigoBarra)` para upsert | Importar variantes requiere `codigo_padre` y atributos; hoy el importador no los conoce | Fuera de v1; documentar el formato extendido (`codigo_padre`, `atributos`) |
| `Services/ImportacionMasivaService.java:738,809` | `setCodigoBarra(...)` sin validar duplicado contra BD | El importador puede crear duplicados que el índice único rechazará a mitad de lote | Validar antes de persistir y reportar fila; el índice es la red de seguridad |

### 14.3 Precio

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Models/Articulos/Articulos.java:102-108` | `precios` EAGER con `@OrderBy("id ASC")` | Cada variante es un `Articulos` con su propia lista EAGER: el picker/etiquetas cargan precios de todo el catálogo (N+1 multiplicado por variantes) | Sin cambio de mapeo en v1; excluir variantes de los listados masivos (14.7/14.8) |
| `Models/Articulos/Articulos.java:149-163` `getLastPrecio()` | Recorre `precios` y elige el más nuevo por `fechaCompra`; el `id` solo desempata | Hijo sin precios → `null`; el POS y la etiqueta muestran vacío/NPE según el llamador | `getPrecioVigente()`: precio del hijo o, si no hay, `articuloPadre.getLastPrecio()` |
| `Models/Articulos/Carrito/ArticuloCarrito.java:93,115` | `articulo.getLastPrecio().getPrecioConUtilidad()` | NPE si el hijo no tiene precio | Usar `getPrecioVigente()` con guarda |
| `Utils/CarritoCalculations.java:128` | `item.getArticulo().getLastPrecio()` | Igual | Igual |
| `Controllers/Api/App/ArticuloResource.java:662,1742-1748,1858-1861,2164` | Lee `getLastPrecio()` para mostrar/editar | Con variante sin precio, muestra vacío | Fallback al padre en el DTO |
| `Services/ProfitAnalysisService.java:50-117` | `getLastPrecio()` para ingresos/costo | Si el costo vive en el padre y el precio en el hijo, el margen se calcula mal | Resolver costo y precio con la misma regla de herencia |
| `Services/StockAlertService.java:530-531` | `getLastPrecio()` para costo de reposición | Hijo sin precio → costo nulo | Fallback al padre |
| `Services/PublicInvoiceService.java:408-413` | `getLastPrecio()` para venta | Igual | Igual |

### 14.4 Lotes y series

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/LoteService.java:32-44` `listPorArticulo` | Filtra `l.articulo = :articulo` | Con variante-hijo, cada hijo tiene sus lotes; OK | Sin cambio |
| `Services/LoteService.java:46-62` `listProximosVencer` | Filtra por `fechaVencimiento`, sin bodega | No se puede ver "por vencer en Bodega 2" | Añadir filtro opcional `bodegaId` |
| `Services/LoteService.java:64-75` `listVencidos` | Igual | Igual | Igual |
| `Services/LoteService.java:77-91` `sugerirLoteFEFO` | Sugiere el lote más próximo a vencer del artículo | **No se invoca en POS** (no hay descuento de lote en la venta) | Documentar; si se quiere FEFO, llamarlo en `processCodigoBarra` y descontar `cantidadActual` en la misma tx (fase posterior) |
| `Models/Lote.java:24-64` | `articulo`, `cantidadActual`, `fechaVencimiento` | Sin bodega | `ALTER TABLE lotes ADD COLUMN bodega_id` (§3.3); **no** `variante_id` |

### 14.5 Compras

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/OrdenCompraService.java:163-177` `recibirOrden` | Solo setea estado/fecha/total; **no mueve stock** | El plan 1.1 asumía que la recepción sumaba stock: **es falso**. Si se agrega el movimiento, cambia el comportamiento de todos los tenants | v1: no tocar; documentar. Fase 6: movimiento de entrada detrás de bandera `compras.recepcion-mueve-stock` (default `false`) |
| `Controllers/Api/App/Compras/OrdenesPagesResource.java:20-28` | Página de órdenes (handlers **no verificados** en detalle) | UI debe permitir elegir variante (hijo) y bodega destino | Selector de variante y bodega; sin cambio de modelo |
| `Services/CarritoService.java:459-476` `ingresarInventario` | Crea `Inventario` tipo "Compra" (usado por FEC) | No setea `bodega_id` | Setear bodega activa/destino |
| `Models/OrdenCompraDetalle` | FK `articulo` → `articulos.codigo` | Acepta hijo-variante | Sin cambio |

### 14.6 Facturación

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/ComprobanteService.java:1382` | `linea.setDetalle(articulo.getNombre())` | El nombre del hijo debe describir la variante | Convención de nombre al crear la variante; sin cambio de código |
| `Services/ComprobanteService.java:1391` | `setCodigoCabys(articulo.getCodigoCabys().getCodigo())` | NPE si el hijo no copió CABYS (POS lo bloquea, FEC no) | Validar CABYS al crear variante; el importador también |
| `Services/ComprobanteService.java:1392-1397` | `CodigoComercial` tipo `"04"` con `codigoBarra` | Es el único lugar donde el SKU viaja al XML; `codigo` es `VARCHAR(20)` (`CodigoComercial.java:44`) | Mantener; validar longitud del barcode de variante ≤ 20 |
| `Services/ComprobanteService.java:1211-1212` | Moneda fija `"CRC"` | No hay multi-moneda ni en el camino directo | Fuera de alcance; no prometer multi-moneda con la opción 2 de §15 |
| `Services/HaciendaServiceFacade.java:215-295` `buildInvoiceData` | Arma `InvoiceData` con CABYS como `code` (`:265`), primer impuesto (`:271-274`) y descuento (`:275-285`) | El SKU de la variante **no viaja** en modo Fides | Documentar (§15); no tocar Fides |
| `Services/FidesApiService.java:123-131` `ItemData` | `code`,`description`,`quantity`,`unitPrice`,`taxRate`,`discount` | No hay campo para `codigos_comerciales` ni moneda | Opción 1 de §15 (largo plazo) |
| `Models/Detalles/DetalleSurtido` | Concepto Hacienda combos | No aplica a variantes | Sin cambio |

### 14.7 POS

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/CarritoService.java:434-452` `ajustarInventario` | Crea un `Inventario` por línea con `cantidad.negate()` y llama `inventario.update()` | No setea `bodega_id` ni descuenta `StockBodega` | Setear bodega del `CartSessionContext`; `updateStock` extendido (14.1) |
| `Services/CarritoService.java:554-624` `processCodigoBarra` | Identifica línea por `Articulos.codigo` (`:580`) | Padre con variantes se agrega como línea | Detección + `ARTICULO_CON_VARIANTES` (14.2) |
| `Controllers/Api/App/PosResource.java:259-275` `/scan` | Delega en `processCodigoBarra` | Hereda el cambio anterior | Mapear el estado nuevo a HTTP/JSON |
| `Controllers/Api/App/PosResource.java:1707-1718,1727` picker y add-form | Agrega por `articuloCodigo` | El selector debe ofrecer hijos, no el padre | Filtrar `es_variante`/padres con variantes en el picker |
| `Controllers/Api/App/PosResource.java:2339-2368` `articuloPickerHits` | `ListAllEnabled()` completo + `esFacturable` + filtro en memoria; corta a `TYPEAHEAD_LIMIT` | Con variantes el catálogo crece; el corte por orden de iteración puede esconder el SKU buscado | Excluir variantes del typeahead de padres; agregar endpoint de variantes por padre |
| `Controllers/Api/App/PosPageResource.java:34-35` | Página POS | Sin selector de bodega | Añadir `bodegaId` al contexto (firmas de render **no verificadas**) |

### 14.8 Etiquetas

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Controllers/Api/App/EtiquetasResource.java:100-107` | `ListAllEnabled()` + filtro/orden en memoria | Con variantes, la lista duplica el catálogo y crece sin paginación real | Excluir `es_variante` por defecto; `?incluirVariantes=true` |
| `Controllers/Api/App/EtiquetasResource.java:132-169` `generar` | Resuelve por `articuloCodigo` en un mapa de habilitados | Imprimir una variante requiere que esté en el índice (lo estará: es un artículo) | Sin cambio; validar que el hijo tenga precio o herede |
| `Controllers/Api/App/EtiquetasResource.java:235-243` `precioFinalDe` | `getLastPrecio()` con guarda de null | Etiqueta sin precio si el hijo no tiene precio | Fallback al padre |
| `Controllers/Api/App/EtiquetasResource.java:259-268` `toDTO` | Expone `codigoBarra` del artículo | Con variante, es el barcode del hijo: correcto | Sin cambio |

### 14.9 Reportes y valoración

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/PublicInvoiceService.java:264-311` `getInventoryValuation` | JPQL con `a.lastPrecio.precioFinal` y `JOIN Articulos a ON a.codigoBarra = s.codigoBarra` | **Consulta inválida**: siempre lanza y devuelve vacío (`:304-309`) | Reescribir con subquery de último `articuloprecio` (patrón `InventarioService.java:1062-1064`) **antes** de agregar bodega |
| `Controllers/Api/Accounting/InventoryValuationController.java:43-61` | Llama `getInventoryValuation()` | Hereda el vacío | Añadir `?bodegaId=` cuando la consulta funcione |
| `Services/InventarioService.java:864-905` `pageResumenInventario` | Usa `RESUMEN_FROM` (agregado por barcode) | Sin bodega | Variante de query con `stock_bodega` |
| `Services/InventarioService.java:950-980` `stockResumenPorFamilia` | Agrupa por familia sobre el agregado | Sin bodega | Filtro opcional por bodega |
| `Services/StockForecastService.java:97-108` | Native SQL: `SUM(cantidad) FROM inventario GROUP BY articulo_codigo` | Es la tercera fuente de stock; sin bodega; con variantes cada hijo aparece como fila propia (correcto) | Añadir `AND bodega_id = :bodegaId` en la variante por bodega |

### 14.10 Alertas, pronóstico y merma

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/StockAlertService.java:199-225` `checkAndCreateStockAlerts` | Barre **todos** los artículos activos tras cada venta, ordenado por código, con tope | Con variantes el barrido crece proporcionalmente al número de SKUs | Sin cambio en v1; el tope existente acota el costo. Documentar el impacto |
| `Services/StockAlertService.java:441-454` `getCurrentStock` | `SUM(Inventario.cantidad)` por artículo; `result.intValue()` | Trunca decimales (0.7 → 0) y no distingue bodega | Devolver `BigDecimal` y agregar overload con `bodegaId`; el `intValue()` es un bug de precisión, no de variantes |
| `Services/StockAlertService.java:530-531` | Costo de reposición con `getLastPrecio()` | Hijo sin precio | Fallback al padre |
| `Services/ShrinkageAnalysisService.java:42,63,99,138,183` | Suma `Inventario` por tipo/departamento | Sin bodega | Filtro opcional por `bodega_id` |
| `Services/SeleccionMetodoService.java:379,404,525` | Usa `Inventario` para elegir método de pronóstico | Sin bodega | Sin cambio en v1 |

### 14.11 Importación masiva

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Services/ImportacionMasivaService.java:194-196` | Columnas: Stock Óptimo, Días Stock Seguridad | No hay columnas de padre/variante/bodega | Fase posterior: `Codigo Padre`, `Atributos`, `Bodega`, `Stock Inicial` |
| `Services/ImportacionMasivaService.java:673-720` | Parseo y validación de fila | Ignora variantes | Validar `codigo_padre` existente y no crear ciclos |
| `Services/ImportacionMasivaService.java:705,1002` | Upsert por `findByBarCode` | Un barcode duplicado actualiza el artículo equivocado (no determinista) | Buscar por código de padre + atributos cuando venga especificado |

### 14.12 Semántica de sucursal vs bodega

| Archivo:línea | Supuesto actual | Qué se rompe | Cambio mínimo |
|---|---|---|---|
| `Models/Sucursal.java:41-43` | Único `(codigo_sucursal, codigo_terminal)` | Una "sucursal" es en realidad un **terminal POS**; hay N filas por sucursal real | `bodega.codigo_sucursal` (VARCHAR(3)) en lugar de FK a `sucursal.id`; backfill por `DISTINCT codigo_sucursal` |
| `Models/Sucursal.java:30-37` + `Services/SucursalService.java:171-201` | `seleccionar(Long id)` copia el par `(codigoSucursal, codigoTerminal)` a `ConfiguracionAplicacion`; `seleccionada()` (`:196`) lo devuelve | La "sucursal activa" es un par, no un id: no sirve como FK de bodega | Resolver bodega por `codigoSucursal` de la configuración, no por `sucursal.id` |

### 14.13 Resumen de esfuerzo por punto de impacto

| Bloque | Días extra sobre §8 | Fase |
|---|---|---|
| Dedupe de barcode + índice único + `findByBarCode` determinista | 1 | 0 |
| Rename de barcode → migración de `articulostock` | 1 | 2 |
| `getStock`/`calculateTotal` con `BigDecimal` y bodega | 1 | 3 |
| Compras que mueven stock (detrás de bandera) | 1 | 6 |
| Reescritura de `getInventoryValuation` | 1 | 7 |
| Enrutamiento Fides vs directo (opción 2, opt-in) + documentación | 2 | 9 |
| **Total** | **7** | |

> El costo del picker/etiquetas sin explosión de variantes se absorbe dentro de las fases 4 y 7 (no suma un día aparte).

---

## 15. Restricción Fides (crítico para variantes)

### 15.1 Estado actual verificado

- `Services/HaciendaServiceFacade.java:120-126` enruta todo el envío: `isFidesEnabled()` (`:99-107`) → `submitViaFides` o `submitViaDirectHacienda` (`:301`). La decisión es **global por configuración** (`ConfiguracionAplicacion.useFides`), no por documento.
- `buildInvoiceData` (`:215-295`) arma `FidesApiService.InvoiceData` con: emisor/receptor, clave, consecutivo, condición de venta, plazo, actividad, ítems y total. Por ítem:
  - `item.code = linea.getCodigoCabys()` (`:265`) → **CABYS, no SKU**.
  - `item.description = linea.getDetalle()` (`:266`).
  - `item.taxRate` = **solo el primer impuesto** (`:271-274`).
  - `item.discount` = suma de descuentos (`:275-285`).
- `FidesApiService.InvoiceData.ItemData` (`:123-131`) tiene exactamente `code`, `description`, `quantity`, `unitPrice`, `taxRate`, `discount`; el JSON enviado (`:327-343`) mapea esos seis campos. **No hay `codigos_comerciales`, ni moneda, ni exoneración.**
- `ComprobanteService.java:1392-1397` sí arma `CodigoComercial` tipo `"04"` con el código de barras, pero eso vive en el XML que genera Mercurius para el **camino directo**; no se copia a `InvoiceData`.
- `ComprobanteService.java:1211-1212` fija `"CRC"` incluso en el camino directo: **el problema de moneda no lo resuelve Fides ni el enrutamiento**.
- Fides es un servicio externo (no está en este repo). Los detalles de su `InvoiceItem`, `xml/src/document.rs` y `services/src/invoice.rs` son **no verificados en este repo**; se citan como restricciones reportadas del proveedor.

### 15.2 Consecuencias para variantes

1. En modo Fides, el **SKU/código de barras de la variante no puede viajar como `CodigoComercial`**. El XML de Fides solo tendrá CABYS + descripción; dos variantes del mismo padre con el mismo CABYS son indistinguibles en el documento fiscal salvo por la descripción.
2. El **descuento por línea** se envía pero, según el reporte del proveedor, Fides lo ignora; no rompe, solo se pierde en el XML generado por Fides (**no verificado** en este repo).
3. La **moneda** está fija en CRC en ambos caminos (`ComprobanteService.java:1212` y, reportadamente, en Fides). Cambiar de proveedor no habilita multi-moneda.
4. `CodigoComercial.codigo` es `VARCHAR(20)` (`CodigoComercial.java:44`): un SKU de variante más largo no se puede persistir en el comprobante, independientemente del proveedor.

### 15.3 Opciones

| Opción | Descripción | Costo | Control | Riesgo |
|---|---|---|---|---|
| **1. Extender Fides** | Pedir al proveedor que su API acepte `codigos_comerciales` (tipo 04), moneda y exoneración por ítem, y que `discount` se respete | Externo: depende del roadmap de Fides. En Mercurius, ampliar `ItemData` y el mapeo JSON (~1 día cuando exista) | Nulo sobre el XML propio | Bloqueante si el proveedor no lo prioriza; no se puede comprometer fecha |
| **2. Camino directo de Hacienda (opt-in)** | Bandera por configuración/tenant que fuerza `submitViaDirectHacienda` para documentos con variantes o cuando se exige `CodigoComercial` | Bajo: el camino ya existe (`HaciendaServiceFacade.java:301`), el XML ya arma tipo "04" (`ComprobanteService.java:1392-1397`) | Total: Mercurius controla el XML | Duplica la operación fiscal (dos caminos que mantener); **no** resuelve moneda (CRC fijo en `ComprobanteService:1212`); obliga a probar el camino directo en producción |
| **3. Postergar el SKU en el documento fiscal (v1)** | Aceptar que en Fides el documento distingue variantes solo por descripción; el SKU vive en POS/stock/etiquetas/reportes. Documentarlo como limitación conocida | Cero | Nulo | Un tenant que exija `CodigoComercial` queda insatisfecho hasta la opción 1/2; requiere comunicación comercial clara |

### 15.4 Recomendación

- **v1: opción 3.** No se toca Fides ni se duplica el camino fiscal. El SKU de la variante es identidad **interna** (stock, POS, etiquetas, reportes, trazabilidad) y el documento fiscal sigue saliendo por Fides con CABYS + descripción. Se documenta en la UI de configuración y en el manual: "En modo Fides el código de barras de la variante no se incluye en el XML".
- **Opt-in para tenants que exijan `CodigoComercial`: opción 2**, con bandera `hacienda.directo-para-variantes` (default `false`), aplicada **por documento** (no global) para no forzar todo el tráfico por el camino directo. Requisito de entrada: el tenant ya opera el camino directo (tiene certificado/clave y acepta la operación).
- **Largo plazo: opción 1.** Si Fides expone los campos, se unifica todo por Fides y se retira la bandera. Mientras tanto, la opción 2 es la válvula de escape.
- **No se elige la opción "embeber el SKU en la descripción"** (`[SKU:xxx]`): contamina el detalle fiscal, rompe reportes por descripción y no aporta validación.

### 15.5 Criterios de aceptación de la restricción

11. Factura **directa** de variante incluye el código de barras de la variante en `CodigoComercial` tipo "04" y el documento se acepta en Hacienda (prueba en ambiente de certificación).
12. Factura vía **Fides** de variante incluye CABYS + descripción; el SKU **no** viaja y eso está documentado (no se reporta como bug).
13. La bandera `hacienda.directo-para-variantes` es por documento y su activación no cambia el enrutamiento de los documentos sin variantes.
14. Un código de barras de variante de más de 20 caracteres se rechaza al crear la variante si el tenant puede usar el camino directo.

---

## 16. Correcciones al plan previo (tras verificar el código)

1. **`articulos.codigobarra` NO es único.** La versión 1.1 lo asumía ("unique" en §14 y en la tabla de riesgos). Verificado: `Articulos.java:41-42` no declara unicidad y ningún `@Table` de `Models` la impone; el único control es `ArticuloResource.java:498-501`, que es TOCTOU. Consecuencia: hay que deduplicar y crear un índice único parcial **antes** de apoyar la identidad de variantes en el barcode.
2. **La recepción de compras NO suma stock.** La versión 1.1 decía que "recepción suma stock por artículo". Verificado: `OrdenCompraService.recibirOrden:163-177` solo setea `fechaEntregaReal`, `estado="RECIBIDA"` y `totalReal`; no crea `Inventario` ni toca `ArticuloStock`. Consecuencia: "recibir compra por bodega" es funcionalidad nueva (y opt-in), no una integración existente.
3. **La tabla física es `articulostock`, no `articulo_stock`.** La versión 1.1 usaba `articulo_stock` en el SQL de §4.1/§4.3. Verificado: `ArticuloStock` no declara `@Table`; el DDL real es `articulostock(id, codigobarra, lastupdated, stock, codigo_barra, version)` (`data-audit-8091.md:739`), con `codigo_barra` como columna actual y `codigobarra` legado. Además `articulos.codigoBarra` se mapea a `codigobarra` (sin guion bajo), como prueba `RESUMEN_FROM` (`InventarioService.java:788`).
4. **`Sucursal` no es una sucursal única.** Es un registro de terminales con único `(codigo_sucursal, codigo_terminal)` (`Sucursal.java:41-43`). El backfill "una bodega principal por sucursal" se cambió a "por `codigo_sucursal`" (§3.3, §4.1).
5. **`variante_id` en `articuloprecio`/`lotes` era redundante.** El hijo ya es un `articulos`; `articuloprecio.articulo_id` y `lotes.articulo_codigo` ya lo identifican. Se eliminaron esas columnas del DDL.
6. **`getInventoryValuation` está roto**, no "sin bodega": el JPQL usa `a.lastPrecio` (no existe) y `JOIN Articulos a ON` (no es JPQL válido). El `catch` devuelve vacío. La valoración por bodega no se puede construir sobre esa consulta.
7. **`CodigoComercial` tipo `"04"`, no 01/02**, y `codigo` es `VARCHAR(20)` (`CodigoComercial.java:44`). La corrección de tipo ya estaba en 1.1; se agrega la restricción de longitud.
8. **`CodigoComercial` directo vs Fides:** el tipo "04" existe solo en el XML propio; en Fides se pierde (§15). La versión 1.1 decía "factura de variante incluye el código de barras en `CodigoComercial`" sin aclarar el proveedor.
9. **`processCodigoBarra` está en `:554`** (no `:560`; `:560` es la llamada a `findByBarCode`). `ajustarInventario` está en `:434` (lista) y `:478` (contexto).
10. **`PosPageResource` y `OrdenesPagesResource` sí existen** (`/app/pos`, `/app/compras/ordenes`), pero las firmas de sus handlers siguen **no verificadas**.

---

## 17. Ajuste de fases y esfuerzo

| Fase | Contenido | Días | Depende de | Cambio respecto de §8 |
|---|---|---|---|---|
| 0 | Dedupe `codigobarra` + índice único parcial + DDL + backfill por `codigo_sucursal` | 3 | — | +1 día (dedupe) |
| 1 | Entidades JPA: `Articulo.articuloPadre/esVariante`, `Atributo`, `ValorAtributo`, `Bodega`, `StockBodega`, `Traslado`/`Detalle`, `Serie`, `bodegaId` en `Inventario`/`Lote` | 3 | 0 | +`Articulos.articuloPadre`; sin `variante_id` en precios/lotes |
| 2 | Servicios: `BodegaService`, `StockBodegaService` (doble escritura + `renombrarCodigoBarra`), `TrasladoService`, `VarianteService`, `getPrecioVigente` | 6 | 1 | +1 día (rename de barcode y herencia de precio) |
| 3 | `InventarioService.updateStock` actualiza `StockBodega` + `bodega_id`; overloads `BigDecimal` | 3 | 2 | +1 día (precisión y overloads) |
| 4 | POS: selector de bodega, selector de variantes, picker sin padres | 4 | 3 | — |
| 5 | UI Qute/HTMX: variantes en ficha, bodegas, traslados, conteo físico | 6 | 2 | — |
| 6 | Compras: recibir por variante/bodega **detrás de bandera** | 3 | 2 | +1 día (bandera y retrocompatibilidad) |
| 7 | Etiquetas + reportes + **reescritura de la valoración** + filtro por bodega | 4 | 2 | +1 día (valoración rota) |
| 8 | Lotes/series por bodega | 2 | 1 | — |
| 9 | Enrutamiento Fides vs directo (opción 2, opt-in) + documentación de la opción 3 | 2 | 7 | — |
| 10 | Pruebas + smoke + regresión de valoración | 4 | todas | — |
| **Total** | | **~40 días** | | +7 días sobre §8 |

> El incremento respecto de §8 (+7 días) es exactamente el de §14.13: dedupe de barcode (1), rename de barcode (1), precisión/overloads de stock (1), compras con bandera (1), valoración rota (1) y enrutamiento Fides opt-in (2). El picker/etiquetas se absorbe en las fases 4 y 7.

---

## 18. Criterios de aceptación adicionales

15. `InventarioService.updateStock` actualiza `StockBodega` de la bodega del movimiento en la misma transacción; reconciliación `SUM(stock_bodega.cantidad)` vs `articulostock.stock` cuadra para artículos sin saldo histórico negativo (documentado en §4.1).
16. `calculateTotalStockForItemByBarcode` por padre suma hijos (variante) cuando se consulta por `articuloCodigo` del padre.
17. `/api/v1/accounting/inventory/valuation` deja de devolver vacío con datos reales y acepta `?bodegaId=`.
18. `findByBarCode` es determinista con duplicados (`ORDER BY codigo`, `setMaxResults(1)`) y existe el índice único parcial en BD.
19. Cambiar el barcode de un artículo con stock no deja huérfana la fila de `articulostock` (o la edición se bloquea con un mensaje claro).
20. POS al escanear código de padre con variantes activas devuelve `ARTICULO_CON_VARIANTES` y no agrega el padre.
21. Migración: `articulos.articulo_padre_id` null en todos los artículos existentes; `bodega.codigo_sucursal` no repite terminales (una bodega principal por `codigo_sucursal`).
22. Un traslado ENVIADO no altera `articulostock`; solo el RECIBIDO con diferencia ajusta el agregado por la merma.
23. La recepción de orden de compra no mueve stock con la bandera en `false` (comportamiento actual preservado).

---

## 19. Notas de verificación pendiente (no inventar)

- Firmas exactas de los handlers de `PosPageResource` (`/app/pos`) y `OrdenesPagesResource` (`/app/compras/ordenes`): comprobar con búsqueda antes de implementar.
- Fides es servicio externo: confirmar en su documentación si `InvoiceItem` admite `codigos_comerciales`, `codigo_tipo_moneda` y `exoneracion` (§15). En este repo **no** está el código de Fides.
- Semántica del tipo `"04"` de `CodigoComercial`: el código lo usa, pero el catálogo del XSD de Hacienda **no verificado** en este repo.
- Existencia de `Bodega`/`Atributo`/`Traslado`: confirmar con búsqueda en `app/src/main/java` (esperado: sin resultados, verificado 2026-11-13).
- `SucursalService.seleccionar(Long id)` / `seleccionada()`: verificados en `Services/SucursalService.java:171-201`; se usan solo como referencia para resolver `codigo_sucursal`.
- `ArticulosService.listAllSinProcesar`, `findById(Integer)`, `pageByTab`: confirmar firmas antes de usarlas en los servicios nuevos. `findById` recibe `Integer` y `Articulos.codigo` es `Long` (`ArticulosService.java:168`).
- Índice único de `articulos.codigobarra`: verificar en la BD real si ya existen duplicados **antes** de ejecutar el DDL (§3.3).
