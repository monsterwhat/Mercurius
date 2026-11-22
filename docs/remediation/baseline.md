# Remediation baseline — Mercurius

Fecha: 2026-11-22
Repositorio: `F:\Documents\GitHub\Mercurius-clean`

## 1. Estado de git al iniciar

El árbol ya tenía cambios de la migración de pronóstico y archivos no
rastreados de una lane de facturación (`InvoicingApiResource`,
`ApiVentaIdempotencia`, `InvoicingService`, migración SQL y QA). No se tocaron
los archivos bajo `sources/` ni `app/target/`.

Cambios de esta remediación (además de la migración de pronóstico):

- `Services/auth/UserRoleMapper.java` — parsing exacto de roles.
- `Models/Articulos/ArticuloStock.java` — identidad estable por artículo.
- `Services/InventarioService.java` — stock por artículo, reconciliación,
  fallos explícitos.
- `Services/StockForecastService.java` — stock actual desde `ArticuloStock`,
  fallos de consulta no degradados a “sin datos”.
- `Services/MarketplaceProductService.java` — fallos de stock no degradados a 0.
- `Services/cart/CartSessionStore.java` + `PosResource` — carrito por
  terminal además de cajero.
- `Services/config/ValidacionConfiguracionProduccion.java` — secretos/CORS de
  producción.
- `Services/BackupService.java` + `BackupScheduler` — backup temporal,
  verificación gzip/SHA-256, estado y retención segura.
- `Controllers/Api/App/InventarioResource.java` — endpoint de reconciliación.

## 2. Resultado de pruebas enfocadas

Ejecutadas contra PostgreSQL local `mercurius_test` (puerto 5433):

```text
MotorPronosticoTest                  51 tests, 0 fallos
SeleccionMetodoTest                   5 tests, 0 fallos
DemandaServiceTest                    7 tests, 0 fallos
PronosticosResourceTest               4 tests, 0 fallos
InventarioServiceIntegrationTest     11 tests, 0 fallos
InventarioStockFailureTest            1 test,  0 fallos
RoleMatrixTest                       10 tests, 0 fallos
UserRoleMapperTest                   15 tests, 0 fallos
CartSessionStoreTest                  9 tests, 0 fallos
BackupServiceIntegrationTest          4 tests, 0 fallos
ValidacionConfiguracionProduccionTest 7 tests, 0 fallos
```

Conjunto amplio de pronóstico, autorización, stock, carrito, backups y
configuración:

```text
Tests run: 226, Failures: 0, Errors: 0
```

Corrida final de cierre (stock, backups, carrito, roles, configuración y
pronóstico canónico):

```text
Tests run: 53, Failures: 0, Errors: 0
```

Nota: la suite completa queda en 1572 tests, 0 fallos, 0 errores y 1 skipped.
Los 3 fallos de generación XML de Hacienda para líneas exentas se corrigieron
emitiendo el `<Impuesto>` cero que exige el XSD (código `01`, Tarifa Exenta
`10`, `Tarifa=0`, `Monto=0`). Los tres fallos no eran de pronóstico. Los dos
fallos de agregados REP con `resumen = null` se corrigieron con
`LEFT JOIN f.resumen r`.

El conteo debe hacerse sobre los elementos `testcase` del XML de Surefire, no
sobre el atributo `testsuite.tests`: las clases exteriores con `@Nested`
declaran `tests="0"` aunque su XML contiene los casos reales. Ese atributo
subestima el total (~1391) y explica la discrepancia con la consola de Maven
(1572).

## 3. Consumidores y clases afectadas

### Pronóstico

- `StockForecastService` → `CalculadoraPronostico` → `MotorPronostico`.
- `DemandaService` → `SeleccionMetodoService` → `MotorPronostico`.
- Página `/app/reportes/inventario/pronosticos` y exportación comparten el
  snapshot canónico.
- `/api/stock-forecast/*` consume el mismo snapshot.
- `PrecisionPronostico` persiste una fila por artículo, horizonte y método.

### Stock

Escritores:

- `InventarioService.createWithStock`, `update`, `markAsProcessed`.
- `CarritoService.ajustarInventario` (usa `InventarioService.update`).
- `ReversionInventarioService` (usa `update`).
- Ajustes manuales en `InventarioResource`.

Lectores:

- `InventarioService.getStock(String barcode)` — clave alterna.
- `InventarioService.getStockByArticleId(long)` — clave estable.
- `InventarioService.findStockByArticleCode(int)` — id con fallback barcode.
- `InventarioService.pageResumenInventario` / `countResumenInventarioKpis`.
- `StockForecastService.getCurrentStock` y `CONSULTA_CATALOGO`.
- `MarketplaceProductService.hasStock` / `getStockQuantity`.
- `InventoryController` (Mercatus).

### Autorización y sesión

- `UserRoleMapper` alimenta los roles de Quarkus desde `Usuarios.groupName`.
- `RoleMatrixTest` y `AuthJourneyTest` cubren los gates.
- `CartSessionStore` es estado en memoria por instancia; ahora la clave es
  terminal + cajero.

### Respaldos

- `BackupService.ejecutarBackup` / `limpiarBackupsViejos` / `listarBackups`.
- `BackupScheduler` ejecuta el respaldo programado y ahora también comprueba
  vigencia diaria.
- `SettingsResource.backupStatus` / `backupTrigger` / `backupDescargar`.

## 4. Lógica duplicada encontrada

1. **Pronóstico legado** en `StockForecastService`: media por días con venta,
   tendencia multiplicativa y seguridad `stdDev * 1.65`. Queda detrás de
   `mercurius.pronostico.canonico-habilitado=false`, solo rollback.
2. **Lectura de stock**: el pronóstico leía `SUM(inventario.cantidad)` mientras
   la POS y el marketplace leían `ArticuloStock`. Ahora el pronóstico usa la
   proyección `ArticuloStock`.
3. **Roles por subcadena**: `String.contains` concedía admin a `notadmin` y
   `administrador`. Reemplazado por tokens exactos.
4. **Carrito por usuario**: dos terminales del mismo cajero compartían líneas,
   pagos y sello de factura. Ahora la clave incluye terminal.

## 5. Contratos de API conocidos

- `GET /api/app/inventario/stock?codigoBarra=` devuelve stock proyectado y
  suma del libro. Se conserva.
- `GET /api/v1/mercatus/inventory/{articleId}` devuelve `articleId`, `barcode`,
  `quantity`, `status`. Se conserva.
- `GET /api/stock-forecast/bulk-forecast?days=` conserva
  `articuloId`, `articuloNombre`, `forecastDate`, `currentStock`,
  `predictedSales`, `predictedStock`, `shouldReorder`, `avgDailySales`,
  `salesVelocity`; agrega `predictedDemand`, `metodo`, `mase`, `sesgo`,
  `calidadFlags`, `reorderPoint`, `recommendedReorder`.
- `GET/POST /api/app/settings/pronostico` es nuevo y solo afecta parámetros
  económicos.
- `GET /api/app/inventario/reconciliacion` es nuevo, de solo lectura.

## 6. Migración de base de datos requerida

Con `quarkus.hibernate-orm.schema-management.strategy=update`, Hibernate crea o
agrega lo siguiente al arrancar:

- `articulostock.articulo_codigo` (nullable), índice
  `idx_articulostock_articulo`. No es UNIQUE para permitir el arranque con
  duplicados históricos; la reconciliación los reporta.
- `configuracion_pronostico` y su historial INSERT-only.
- `precision_pronostico`: `mae`, `origenes`, `observaciones`,
  `fecha_fin_historia`, `modelo_version`, `calidad_flags`, `mejora_baseline`,
  `metodo_baseline`, `seleccionado`.

Antes de desplegar en una base existente conviene ejecutar:

```sql
SELECT articulo_codigo, COUNT(*)
FROM articulostock
WHERE articulo_codigo IS NOT NULL
GROUP BY articulo_codigo HAVING COUNT(*) > 1;
```

Si devuelve filas, la reconciliación las expondrá y `updateStock` se negará a
vender hasta que se consoliden. No se consolidan automáticamente.

## 7. Supuestos que requieren confirmación

1. **Despliegue de una sola instancia**: el carrito sigue en memoria. Reinicio
   o failover pierde carritos. Si se confirma multi-instancia, debe migrarse a
   un store compartido con expiración.
2. **Terminal**: se usa `X-Terminal-Id` o `?terminal=`; clientes antiguos caen
   en el terminal `default` y conservan el comportamiento anterior.
3. **Stock**: `Inventario` es el libro de auditoría y `ArticuloStock` la
   proyección. La reconciliación reporta diferencias pero no las corrige.
4. **Devoluciones**: la demanda pronosticada lee solo movimientos `Venta`; las
   devoluciones no se restan en esta versión.
5. **Quiebres**: no hay saldo diario de inventario; los ceros históricos se
   tratan como demanda cero y el quiebre actual se marca `RECENT_STOCKOUT`.
6. **Órdenes de compra abiertas**: se descuentan del stock disponible para la
   reposición (`BORRADOR`, `ENVIADA`, `CONFIRMADA`). No se descuentan de la
   demanda pronosticada.
7. **Backup**: la verificación es gzip legible + SHA-256 y publicación atómica;
   no se ejecutó un `pg_restore` real contra una base desechable en esta fase.
8. **Hacienda**: el fallo XSD por `ProveedorSistemas` es configuración de
   entorno; no se cambió el flujo fiscal en esta remediación.
