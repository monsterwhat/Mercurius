# Contrato canonical de pronostico de demanda

Fecha: 2026-11-22
Version del pipeline: `canonico-v1`

Este documento fija la definicion unica de "demanda diaria" y el flujo que
comparten la pagina de Pronosticos, la exportacion, el API de stock y los
servicios de reposicion. Cualquier consumidor nuevo debe entrar por este
contrato; no se admite una segunda formula de promedio, tendencia o seguridad.

## 1. Baseline (antes del cambio)

### Consumidores encontrados

| Consumidor | Entrada | Valor pronosticado que mostraba |
|---|---|---|
| `/app/reportes/inventario/pronosticos` | `PronosticosResource` -> `StockForecastService.reportePronosticos` | `proyeccion.ventaPronosticada()` del primer dia que cruzaba el punto de reorden |
| Exportacion de pronosticos | `ExportResource` -> `StockForecastService.generateBulkForecast(catalogo, dias)` | mismo valor diario, recalculado aparte |
| `/api/stock-forecast/bulk-forecast` | `StockForecastController` -> `generateBulkForecast` | mismo valor diario |
| `/api/stock-forecast/forecast/{id}` | `generateForecast` | serie diaria con media y tendencia multiplicativa |
| `/api/stock-forecast/demand/{id}` | `predictDemand` | media * horizonte * factor estacional |
| `/api/stock-forecast/reorder/{id}` | `getReorderRecommendation` | stock optimo a 30 dias menos stock actual |
| `DemandaService` | sin controlador HTTP en el momento del baseline | media por dias con venta + intervalo `z*sigma*sqrt(h)` |
| `SeleccionMetodoService` | job semanal | backtesting con 3 origenes y horizonte 14 |

### Problemas confirmados

1. La pagina y el API usaban `StockForecastService`, no `MotorPronostico`.
2. `promedioDiario()` dividia por `diasConVenta`, no por dias naturales.
3. La proyeccion multiplicaba `venta = venta * tendencia` en cada dia.
4. El backtesting solo media un horizonte (14) y 3 origenes.
5. La seleccion persistida se reutilizaba para cualquier horizonte.
6. El intervalo usaba `sigma * sqrt(h)` sin medir cobertura.
7. La demanda historica se enlazaba por `articulos.nombre = linea_detalle.detalle`.
8. `confidence` se derivaba del numero de filas de historia.

### Pruebas al inicio

```
mvn -f app/pom.xml test -Dtest=MotorPronosticoTest
  Tests run: 51, Failures: 0

mvn -f app/pom.xml test \
  -Dtest=SeleccionMetodoTest,DemandaServiceTest,PronosticosResourceTest,\
PronosticosPrecisionTest,AggregadosCantidadNulaTest
  Tests run: 19, Failures: 0
```

Total baseline: **70 pruebas verdes**. No se debilito ninguna asercion para
acomodar el cambio.

### Pruebas al cierre

```
mvn -f app/pom.xml test \
  -Dtest=MotorPronosticoTest,EconomiaReposicionTest,IntervalosPronosticoTest,\
CalculadoraPronosticoTest,PronosticoCanonicoTest,PronosticosPrecisionTest,\
PronosticosResourceTest,SeleccionMetodoTest,DemandaServiceTest,\
AggregadosCantidadNulaTest,SettingsPageTest

  Tests run: 111, Failures: 0, Errors: 0
```

Las aserciones existentes se conservaron; los fixtures que solo sembraban
facturas se completaron con movimientos de inventario porque la demanda
canonica se enlaza por clave de articulo.

## 2. Contrato

### Unidad de demanda

- Unidades por **dia natural**.
- La serie es una rejilla diaria continua desde el primer dia con venta dentro
  de la ventana hasta **hoy**, con 0 en los dias sin movimiento.
- `promedioDiario = total de unidades / dias de la rejilla`.
- Para un articulo con ventas en 5 de 90 dias y 50 unidades, el promedio es
  `50 / 90 = 0.5556`, no `50 / 5 = 10`.

### Fuente de la demanda

- Movimientos de `Inventario` con `tipoMovimiento = 'Venta'`, enlazados por
  `articulo_codigo` (clave estable). Se toma `ABS(cantidad)` porque la venta se
  guarda en negativo al descontar stock.
- `status IS NULL OR status = true` para conservar historia anterior a la
  columna `status`.
- Las lineas de factura **no** se usan para pronosticar. Solo se usan en un
  diagnostico de calidad que cuenta la demanda historica no atribuible.
- Dos articulos con el mismo nombre no comparten historia.
- Un cambio de nombre no borra la continuidad: el enlace es por codigo.

### Ventana

- `mercurius.pronostico.historial-dias=180` por defecto.
- La rejilla empieza en el primer dia con venta dentro de la ventana, no en el
  inicio de la ventana: un articulo nuevo no se castiga con ceros anteriores a
  su existencia.
- La rejilla termina hoy, aunque la ultima venta sea anterior. Los ceros
  recientes son informacion de demanda, no un hueco.

### Devoluciones y cantidades negativas

- Solo se leen movimientos de tipo `Venta`. Las devoluciones (`Devolucion`,
  notas de credito) no se restan de la demanda en esta version.
- `ABS(cantidad)` evita que una venta guardada en negativo se convierta en
  demanda negativa.
- Los valores negativos de un pronostico se acotan a 0: la demanda no existe
  negativa.

### Horizontes

- Soportados: **7, 14, 30 y 60** dias.
- El tope tecnico de `DemandaService` es 90 dias
  (`mercurius.pronostico.horizonte-maximo`), pero la seleccion persistida se
  genera para los cuatro horizontes soportados.
- Un ganador de 14 dias **no** se reutiliza para 60. Si no hay fila del
  horizonte exacto, se usa el respaldo por regimen y se marca
  `NO_EVALUATION`.

### Seleccion de modelo

1. Candidatos del regimen (ADI / CV2 de Syntetos-Boylan) **mas** las tres
   lineas base obligatorias: `INGENUO`, `ESTACIONAL_INGENUO`, `MEDIA_MOVIL`.
2. Backtesting con origenes moviles (domingos con horizonte completo).
   Por defecto **8 origenes**; configurable con
   `mercurius.pronostico.origenes`.
3. Un modelo complejo solo gana si su MASE mejora a la mejor linea base por al
   menos **5 %**. Si no, gana la linea base.
4. Si el ganador y el segundo quedan a menos del **2 %**, se marca
   `MODEL_UNSTABLE` y se prefiere el mas simple.
5. Se persiste una fila por (articulo, horizonte, metodo) con MASE, MAE, sesgo,
   origenes, observaciones, fecha de fin de historia, version de modelo y
   banderas. La fila ganadora lleva `seleccionado = true`.

### Respaldo

- Si no hay seleccion persistida o dejo de ser elegible, se usa el primer
  candidato elegible del regimen.
- Si ningun candidato es elegible, se usa `INGENUO`, que devuelve ceros.
- Todo respaldo se declara en `motivoFallback` y en la bandera
  `NO_EVALUATION`; nunca se presenta como una seleccion medida.

### Incertidumbre

- `IntervalosPronostico` evalua la cobertura real contra origenes historicos.
- Cuando hay al menos 5 errores de horizonte completos, el intervalo usa
  cuantiles empiricos del error a ese horizonte.
- Si no, usa `z * sigma * sqrt(h)` y se marca `INTERVAL_APPROXIMATE`.
- La sigma de respaldo es la desviacion de residuos de un paso adelante.
- `z` se obtiene con la inversa normal de Acklam, no con una tabla de cuatro
  valores.
- La cobertura se mide con: cobertura observada, error contra el objetivo,
  ancho medio, tasa de subpronostico, tasa de sobrepronostico y weighted
  interval score.

### Reposicion

- Punto de reorden = demanda esperada durante el plazo + stock de seguridad.
- Stock de seguridad = `z * sigma * sqrt(plazo)`.
- Nivel de servicio efectivo = max(nivel configurado, razon critica
  `costoFaltante / (costoFaltante + costoMantenimiento)`).
- Cantidad de pedido = max(0, punto de reorden - stock), redondeada al alza al
  multiplo y respetando el minimo del proveedor. Un faltante de cero no genera
  pedido aunque haya minimo.
- Los parametros economicos **no** viven en `application.properties`: se editan
  en Ajustes y se guardan en `configuracion_pronostico` (INSERT-only).

### Frescura

- El snapshot canonico del catalogo se comparte entre pagina, API y
  exportacion durante `mercurius.pronostico.catalogo-ttl-ms` (60 s por
  defecto). El stock mostrado puede tener hasta esa antiguedad.
- La seleccion de metodos se recalcula semanalmente (domingo 03:00) para los
  cuatro horizontes.

### Stockouts, promociones y huecos

- `RECENT_STOCKOUT` se marca cuando el stock actual es cero.
- No hay serie historica de disponibilidad diaria, de modo que los ceros
  historicos se tratan como demanda cero y el quiebre se declara en la bandera.
  Censurar los ceros requeriria reconstruir el saldo diario de inventario; queda
  documentado como limitacion y como siguiente paso.
- `PROMOTION_CONTAMINATED` no se calcula en esta version porque la serie
  canonica no lleva la marca de promocion. Es un hueco declarado, no una
  suposicion silenciosa.
- `DATA_GAP` se marca con rachas de mas de 28 dias sin movimiento.
- `INSUFFICIENT_HISTORY` con menos de 14 dias de rejilla o menos de 3 dias con
  demanda.

## 3. Flujo canonico

```
movimientos de inventario 'Venta' (FK articulo_codigo)
    -> rejilla diaria cero-rellenada hasta hoy
    -> clasificacion de regimen (ADI / CV2)
    -> candidatos del regimen + lineas base
    -> backtesting por horizonte (8 origenes)
    -> ganador persistido por (articulo, horizonte)
    -> pronostico diario con MotorPronostico
    -> intervalo empirico o aproximado declarado
    -> economia de reposicion (punto, seguridad, minimo, multiplo)
    -> banderas de calidad
    -> fila de pagina / ProductForecast / exportacion
```

## 4. Compatibilidad

- Se conservaron los nombres JSON `articuloId`, `articuloNombre`,
  `forecastDate`, `currentStock`, `predictedSales`, `predictedStock`,
  `shouldReorder`, `avgDailySales`, `salesVelocity`.
- `predictedSales` sigue siendo la demanda del dia pronosticado.
- `predictedDemand` es el total del horizonte (campo nuevo, aditivo).
- La pagina muestra la demanda total del horizonte; la columna se llama
  "Demanda Pronosticada" y comparte la misma corrida que el API.
- El camino legado queda detras de
  `mercurius.pronostico.canonico-habilitado=false`, solo para rollback durante
  un ciclo de evaluacion. No es un modo de produccion a largo plazo.

## 5. Pruebas

- Puras: `MotorPronosticoTest`, `EconomiaReposicionTest`,
  `IntervalosPronosticoTest`, `CalculadoraPronosticoTest`.
- Integracion: `PronosticoCanonicoTest`, `SeleccionMetodoTest`,
  `DemandaServiceTest`, `PronosticosPrecisionTest`,
  `PronosticosResourceTest`, `AggregadosCantidadNulaTest`, `SettingsPageTest`.
- Casos cubiertos: dias naturales, demanda dispersa, nombres duplicados,
  seleccion por horizonte, configuracion persistida, intervalos no negativos,
  monotonia stock -> pedido, diagnostico de demanda sin articulo, y acuerdo
  pagina/API.

## 6. Pendiente / limites declarados

- **Replay historico en produccion**: no se ejecuto contra la base real. El
  plan de rollout exige shadow mode y comparacion por SKU, categoria, horizonte
  y regimen antes de retirar el camino legado.
- **Censura por quiebre**: requiere saldo diario de inventario; hoy solo se
  marca `RECENT_STOCKOUT`.
- **Promociones**: falta la marca en la serie; no se afirma que esten limpias.
- **Cobertura publicada**: el evaluador existe y esta probado, pero aun no hay
  un tablero con la cobertura de produccion.
- **Familia**: se mantiene el top-down con participacion de 30 dias y suma
  exacta; falta el backtesting comparativo bottom-up/top-down/middle-out.
