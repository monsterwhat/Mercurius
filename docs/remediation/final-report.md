# Final remediation report — Mercurius

Fecha: 2026-11-22
Versión: `3.20.0`

## 1. Resumen de cambios implementados

### Autorización exacta
`Services.auth.UserRoleMapper` ya no usa `contains`. Parsea tokens separados por
coma, con o sin corchetes, recorta espacios y normaliza mayúsculas. Un `admin`
exacto expande a las seis capacidades. `notadmin`, `administrador`,
`superadmin_backup` e `inventario_backup` no conceden nada.

### Stock con identidad estable
- `ArticuloStock` incorpora `articulo_codigo` (nullable, indexado) además del
  barcode único alterno.
- `InventarioService.updateStock` busca primero por artículo, adopta filas
  antiguas por barcode, actualiza el barcode si el artículo cambió y rechaza
  dos filas o un artículo distinto.
- `StockForecastService` lee stock actual desde `ArticuloStock`, no desde la
  suma del libro.
- `InventarioService.reconciliarStock()` compara proyección contra libro y
  reporta diferencias sin corregirlas. Endpoint:
  `GET /api/app/inventario/reconciliacion`.
- Los fallos de stock ya no se convierten en 0: `getStock`, `getAllStock`,
  `findStockByArticleCode`, `listAll`, `create`, `update`, `markAsProcessed` y
  `delete` propagan el error.

### Carrito por terminal
`CartSessionStore` usa la clave `terminal + \0 + usuario`. `PosResource` toma
`X-Terminal-Id` o `?terminal=` y cae en `default` para clientes antiguos. Dos
terminales del mismo cajero ya no comparten líneas, pagos ni sello de factura.

### Pronóstico canónico
- Página, API y exportación comparten `CalculadoraPronostico` +
  `MotorPronostico`.
- Rejilla diaria cero-rellenada hasta hoy; denominador de días naturales.
- Selección por horizonte 7/14/30/60 con 8 orígenes, líneas base obligatorias,
  umbral de mejora del 5 % y bandera de inestabilidad.
- Intervalos empíricos con evaluación de cobertura; aproximación declarada.
- Reposición con nivel de servicio, costo de faltante/mantenimiento, plazo,
  mínimo, múltiplo y órdenes de compra abiertas.
- Banderas de calidad expuestas en la tabla.
- Familia: reparto solo a participantes; SKU dormido recibe 0 y se marca.

### Configuración de negocio en base de datos
Los parámetros económicos se editan en **Ajustes → Pronóstico y reposición** y
se guardan en `configuracion_pronostico` (INSERT-only). Se retiraron de
`application.properties`.

### Secretos y CORS de producción
`Services.config.ValidacionConfiguracionProduccion` aborta el arranque en prod
si el JWT o la clave de sesión son los de ejemplo, miden menos de 32
caracteres, o si CORS usa `*`/orígenes inválidos.

### Backups verificables
- Dump a `.part`, verificación gzip + SHA-256, publicación atómica y sidecar
  `.sha256`.
- `backupUltimoEjecutado` solo se actualiza tras verificar.
- `estadoBackup()` reporta edad, verificación y vencimiento.
- La retención nunca borra el backup más reciente.
- Se valida espacio libre antes de iniciar.

### Fallos explícitos
Las consultas de catálogo, demanda diaria, selección de métodos y primera fecha
de movimiento ya no se degradan a “sin datos”. `StockForecastService` lanza y
el endpoint responde 500 en vez de mostrar un pronóstico vacío como si fuera
real.

## 2. Archivos cambiados

Principales:

```text
app/pom.xml
app/src/main/java/Controllers/Api/App/InventarioResource.java
app/src/main/java/Controllers/Api/App/PosResource.java
app/src/main/java/Controllers/Api/App/Reportes/PronosticosResource.java
app/src/main/java/Controllers/Api/App/SettingsPronosticoResource.java   (nuevo)
app/src/main/java/Models/Articulos/ArticuloStock.java
app/src/main/java/Models/ConfiguracionPronostico.java                  (nuevo)
app/src/main/java/Models/PrecisionPronostico.java
app/src/main/java/Services/BackupScheduler.java
app/src/main/java/Services/BackupService.java
app/src/main/java/Services/ConfiguracionPronosticoService.java          (nuevo)
app/src/main/java/Services/DemandaService.java
app/src/main/java/Services/InventarioService.java
app/src/main/java/Services/MarketplaceProductService.java
app/src/main/java/Services/ProductPerformanceService.java
app/src/main/java/Services/SeleccionMetodoService.java
app/src/main/java/Services/StockForecastService.java
app/src/main/java/Services/auth/UserRoleMapper.java
app/src/main/java/Services/cart/CartSessionStore.java
app/src/main/java/Services/config/ValidacionConfiguracionProduccion.java (nuevo)
app/src/main/java/Services/pronostico/CalculadoraPronostico.java        (nuevo)
app/src/main/java/Services/pronostico/EconomiaReposicion.java           (nuevo)
app/src/main/java/Services/pronostico/FlagCalidad.java                  (nuevo)
app/src/main/java/Services/pronostico/IntervalosPronostico.java         (nuevo)
app/src/main/java/Utils/DiffUtils.java
app/src/main/resources/application.properties
app/src/main/resources/templates/pages/reportes/_tablas/pronosticos.html
app/src/main/resources/templates/pages/settings/index.html
docs/plans/auth-allowlist.md
```

Pruebas: `UserRoleMapperTest`, `InventarioServiceIntegrationTest`,
`InventarioStockFailureTest`, `CartSessionStoreTest`,
`BackupServiceIntegrationTest`, `ValidacionConfiguracionProduccionTest`,
`PronosticoCanonicoTest`, `CalculadoraPronosticoTest`,
`EconomiaReposicionTest`, `IntervalosPronosticoTest`,
`SettingsPronosticoResourceTest`, `SettingsPageTest`, más los existentes de
pronóstico.

Documentación:

```text
docs/pronostico/CONTRATO_PRONOSTICO.md
docs/remediation/baseline.md
docs/remediation/final-report.md
docs/remediation/fiscal-blocker.md
docs/remediation/cabys-inventory.md
docs/remediation/cabys-inventory.sql
docs/remediation/cabys-inventory-prod.md
docs/remediation/cabys-afectados-produccion.csv
docs/remediation/operations-2026-11-22.md
```

## 3. Migración de base de datos

Con `schema-management.strategy=update`:

- `articulostock.articulo_codigo` (nullable) + índice
  `idx_articulostock_articulo`.
- Tabla `configuracion_pronostico`.
- Columnas nuevas en `precision_pronostico`: `mae`, `origenes`,
  `observaciones`, `fecha_fin_historia`, `modelo_version`, `calidad_flags`,
  `mejora_baseline`, `metodo_baseline`, `seleccionado`.

Antes del despliegue:

```sql
SELECT articulo_codigo, COUNT(*)
FROM articulostock
WHERE articulo_codigo IS NOT NULL
GROUP BY articulo_codigo HAVING COUNT(*) > 1;
```

Si hay duplicados, `updateStock` falla explícitamente hasta consolidarlos.

## 4. Compatibilidad de API

Conservado:

- `/api/app/inventario/stock?codigoBarra=`
- `/api/v1/mercatus/inventory/{articleId}`
- `/api/stock-forecast/*` con sus campos históricos
  (`predictedSales`, `predictedStock`, `avgDailySales`, etc.).

Agregado sin romper:

- `predictedDemand`, `metodo`, `mase`, `sesgo`, `calidadFlags`,
  `reorderPoint`, `recommendedReorder` en `ProductForecast`.
- `GET/POST /api/app/settings/pronostico`.
- `GET /api/app/inventario/reconciliacion`.

Cambios de semántica declarados:

- `predictedSales` sigue siendo la demanda del día; la demanda total del
  horizonte está en `predictedDemand`.
- `confidence` puede ser `UNMEASURED`; no se presenta una confianza no medida.
- `StockForecastService` puede responder 500 ante fallo de datos en vez de
  devolver una lista vacía.

## 5. Pruebas añadidas y ejecutadas

- Roles exactos: 15 casos.
- Stock: barcode cambiado, barcode duplicado, barcode ausente, rollback de
  venta, reconciliación, acuerdo reporte/API/reposición.
- Carrito: dos terminales, terminal en blanco/default, TTL.
- Backups: gzip completo, truncado, vacío, estado verificado/vencido.
- Configuración: prod sin secretos, secreto de ejemplo, secreto corto, CORS `*`
  y orígenes inválidos.
- Pronóstico: días naturales, horizonte, órdenes abiertas, intervalos,
  cobertura, familia sin participación.
- Redacción de logs: `DiffUtilsRedactionTest` verifica que no se serialicen
  passwords, tokens, claves de sesión ni secretos de Hacienda/Fides.
- Suite plana `PronosticoCoreSmokeTest` para que Surefire muestre tests
  ejecutados sin depender del reporte de clases `@Nested`.

Resultados:

```text
Corrida amplia: 226 tests, 0 fallos, 0 errores.
Corrida final:  53 tests, 0 fallos, 0 errores.
Suite completa (mvn clean test): 1572 test cases, 0 fallos, 0 errores, 1 skipped.
```

Nota sobre el conteo: Surefire escribe el atributo `testsuite tests="0"` en
las clases exteriores con `@Nested`, pero los `testcase` reales están dentro de
ese mismo XML. Sumar el atributo da ~1391; contar los elementos `testcase` da
1572, que coincide con la consola de Maven. El conteo publicable es el de
`testcase` (o la línea “Tests run” de Maven), no la suma de atributos.

Los 3 fallos que quedaban eran de generación XML de Hacienda para líneas
exentas. Se resolvieron emitiendo el `<Impuesto>` cero que el XSD exige
(código `01`, Tarifa Exenta `10`, `Tarifa=0`, `Monto=0`), completando la NC de
líneas legacy y corrigiendo dos costuras de prueba (`ProveedorSistemas` y
`conectividadParaVenta`). Evidencia y matriz de regresión en
`docs/remediation/fiscal-blocker.md`.

Correcciones posteriores a la verificación:

- `AuthorizationCoverageTest`: se agregó `InvoicingApiResource` a la allowlist
  documentada (`/api/v1/invoicing` está protegido por `PublicApiJwtFilter`).
- `ProductPerformanceService`: los reportes usan `LEFT JOIN f.resumen r`; un
  comprobante con `resumen = null` ya no desaparece por un inner join implícito.
- `quarkus.http.cors.enabled=true` reemplaza la clave no reconocida
  `quarkus.http.cors`.
- `DiffUtils` redacta propiedades sensibles en snapshots de auditoría.

## 6. Riesgos restantes

1. No se ejecutó shadow mode ni replay histórico contra producción.
2. El carrito sigue en memoria: reinicio/failover pierde carritos y no soporta
   multi-instancia.
3. La reconciliación de stock reporta pero no corrige; requiere decisión
   operativa.
4. Se ejecutó un ensayo real de restauración el 2026-11-22 (SQL plano vía
   `psql`, base descartable). Hallazgo: los backups diarios del 11-09 al 11-22
   estaban corruptos porque dos schedulers escribían el mismo gzip a las 03:00;
   se corrigió en 3.20.1 y se preservó el último backup válido (11-08). Ver
   `docs/remediation/operations-2026-11-22.md`.
5. Persisten joins por nombre en reportes históricos
   (`ProductPerformanceService`, `SalesTrendService`) porque las líneas de
   factura antiguas no tienen FK de artículo. El pronóstico ya no los usa.
6. La cobertura de intervalos se calcula, pero no hay tablero persistido.
7. La suite completa queda verde: `mvn clean test` reporta
   1572 tests, 0 fallos, 0 errores, 1 skipped. El bloqueo fiscal de líneas
   exentas quedó resuelto; la evidencia oficial y la matriz de regresión están
   en `docs/remediation/fiscal-blocker.md`.
8. La validación de producción puede abortar el arranque si la clave de sesión
   de la base no está disponible antes de la validación.

## 7. Supuestos

- Despliegue de una sola instancia para el carrito.
- `X-Terminal-Id`/`?terminal=` identifican terminal; ausente = `default`.
- `Inventario` es libro de auditoría y `ArticuloStock` la proyección.
- Devoluciones no se restan de la demanda pronosticada en esta versión.
- Quiebres históricos no se censuran; se marca `RECENT_STOCKOUT`.
- Órdenes abiertas descuentan stock disponible para reponer, no demanda.
- Backups: verificación de integridad, no restauración automática.

## 8. Rollback

1. Pronóstico: `mercurius.pronostico.canonico-habilitado=false` mantiene el
   camino legado durante un ciclo. El resto de cambios de pronóstico requiere
   redeploy del artefacto anterior.
2. Base de datos: todos los cambios son aditivos y nullable; el código anterior
   ignora las tablas/columnas nuevas. No hay DROP.
3. Roles: revertir el artefacto. No hay flag; el parsing exacto es un fix de
   seguridad y no debe volver a `contains`.
4. Carrito: revertir `CartSessionStore`/`PosResource`; no hay esquema.
5. Backups: revertir el artefacto; los `.sha256` y `.part` son inertes. Los
   `.part` de un crash se pueden borrar manualmente.
6. Configuración de producción: si la validación bloquea el arranque, corregir
   `MERCATUS_JWT_SECRET`, `AUTH_SESSION_KEY`/clave DB y
   `MERCATUS_CORS_ORIGINS`.

## 9. Métricas antes/después disponibles

| Métrica | Antes | Después |
|---|---|---|
| Promedio de demanda dispersa | total / días con venta | total / días naturales |
| Horizontes con ganador propio | 1 (14 días) | 4 (7/14/30/60) |
| Orígenes de backtesting | 3 | 8 por defecto |
| Líneas base comparadas | parcial | ingenuo + estacional ingenuo + media móvil |
| Intervalo | `sigma * sqrt(h)` sin cobertura | empírico con cobertura medida o aproximado declarado |
| Stock leído por pronóstico | suma del libro | proyección `ArticuloStock` |
| Roles admin | substring (`notadmin` pasaba) | token exacto |
| Carrito | clave solo usuario | usuario + terminal |
| Backup | archivo creado | `.part` + gzip/SHA-256 + publicación atómica |
| Pruebas enfocadas | 70 baseline | 226 + 53 de cierre |

No hay métricas de precisión en producción porque el replay/shadow mode queda
pendiente y requiere datos reales.
