# Plan — `esServicio`, multimoneda (CRC/USD/EUR) y métodos de pago

**Estado:** planificado, no implementado.
**Repo:** `F:\Documents\GitHub\Mercurius-clean` (Quarkus, Java 25, Qute + HTMX, PostgreSQL/Hibernate).
**Fecha:** 2026-11-13.
**Origen:** corrección de tres defectos de producto detectados en el análisis competitivo (§4.1) y decisión del dueño del producto de soportar CRC, USD y EUR.

---

## 0. Resumen ejecutivo

Tres cambios, en este orden:

1. **`esServicio` por artículo** — hoy el resumen declara todo como mercancía. Es un bug de cumplimiento, no una función nueva. **3–4 días.**
2. **Multimoneda CRC/USD/EUR** — el modelo XML ya soporta `CodigoTipoMoneda`, pero la emisión fija `CRC` y el tipo de cambio queda en `1`. **12–16 días.**
3. **Métodos de pago** — el catálogo ya existe y está completo; falta una sola fuente de verdad y campos de referencia para conciliar. **2–3 días.**

**Total: 17–23 días.** No requiere tocar firma, envío a Hacienda ni el XSD.

### Decisiones de diseño

| # | Decisión | Razón |
|---|---|---|
| D1 | `esServicio` vive en `Articulos`, no en la línea | Es una propiedad del producto; la línea solo la hereda. Evita que el cajero la cambie por error. |
| D2 | La moneda es **por comprobante**, no por línea | Hacienda v4.4 solo permite un `CodigoTipoMoneda` en `ResumenFactura`. |
| D3 | El tipo de cambio se **congela** en el comprobante | Un reporte histórico no puede recalcular con la tasa de hoy. |
| D4 | CRC usa `TipoCambio = 1`; USD/EUR usan la tasa de referencia del día | Es lo que exige Hacienda. |
| D5 | NC/ND heredan moneda y tasa del comprobante original | Una nota de crédito en otra moneda no compensa. |
| D6 | Una sola fuente de verdad para medios de pago: `Tipo_MedioPago` | Hoy `EntradaPago.metodoPagoLabel` tiene códigos que el enum no tiene. |
| D7 | Los pagos se registran en la moneda del comprobante (v1) | Evita doble conversión y descuadres de caja. Pago en otra moneda queda para v2. |

---

## 1. Estado actual verificado

| Punto | Evidencia | Conclusión |
|---|---|---|
| `esServicio` | `ComprobanteService.java:1135` declara `boolean esServicio = false` **fuera** del bucle; se usa en 1183–1202 y nunca cambia | Todo cae en mercancías |
| Campo en artículo | `Models/Articulos/Articulos.java` no tiene `esServicio` | Hay que agregarlo |
| Campo en línea | `Models/Detalles/LineaDetalle.java` no tiene `esServicio` ni `notas`; sí `detalle` (XML `Detalle`, 200) | No hace falta tocarla para `esServicio` |
| Moneda en emisión | `ComprobanteService.java:1211-1213`: `moneda.setCodigoMoneda("CRC")`, `tipoCambioMoneda` queda en `BigDecimal.ONE` | Fijo en colones |
| Moneda en NC | `DevolucionesResource.java:1494-1496`: también fija `"CRC"` | La NC no puede heredar moneda |
| Persistencia | `ResumenFactura` es tabla `resumen_factura`; `codigo_moneda` y `tipo_cambio_moneda` son columnas del embeddable `CodigoTipoMoneda` | Ya hay dónde guardar |
| Tipo de cambio | `Models/TipoCambio.java`: `valorCompra`, `valorVenta`, `manual`; **sin dimensión de moneda** | Solo sirve para USD |
| Fuente | `TipoCambioService.java:52`: `https://api.hacienda.go.cr/indicadores/tc/dolar`; `ProgramadorTareas.java:81`: `actualizarTipoCambioUSD()` | Solo descarga dólar |
| Medios de pago | `Models/Enums/Tipo_MedioPago.java`: 01,02,03,04,05,06,07,99 | Catálogo correcto |
| Etiquetas | `Models/EntradaPago.java`: además mapea 08 y 10, que el enum no define | **Inconsistencia real** |
| Referencia de pago | `EntradaPago` solo tiene `metodoPago` y `monto` | No hay voucher/autorización para conciliar |
| Cálculo | `Utils/CarritoCalculations.java` no conoce moneda | Correcto si los importes ya vienen en la moneda del comprobante |

---

## 2. `esServicio`

### 2.1 Modelo

`Models/Articulos/Articulos.java`:

```java
@Column(name = "es_servicio", nullable = false)
private boolean esServicio = false;
```

`ArticuloCarrito` no necesita campo: se lee del artículo al construir el resumen.

### 2.2 Cálculo del resumen

En `ComprobanteService.resumenComprobante`, mover la declaración **dentro** del bucle:

```java
for (ArticuloCarrito articuloCarrito : carrito) {
    boolean esServicio = articuloCarrito.getArticulo().isEsServicio();
    ...
}
```

No cambia `totalComprobante`; cambia el reparto entre `totalServ*` y `totalMercancias*`, que es lo que Hacienda y el D-104 leen.

### 2.3 UI y datos

- `templates/pages/articulos/form-articulo.html`: checkbox "Es servicio" junto a CABYS.
- `ArticuloResource` / DTO de artículo: exponer y aceptar `esServicio`.
- Importación masiva y parser de facturas: valor por defecto `false`; no inferir del CABYS (el catálogo no separa bien/servicio de forma fiable).

### 2.4 Migración

```sql
ALTER TABLE articulos ADD COLUMN es_servicio BOOLEAN NOT NULL DEFAULT FALSE;
```

Los artículos existentes quedan como mercancía (comportamiento actual). Clasificar los servicios es tarea del cliente; ofrecer un filtro por CABYS para ayudar.

### 2.5 Pruebas

- Un carrito con 1 servicio gravado + 1 mercancía gravada produce `totalServGravados` y `totalMercanciasGravadas` correctos.
- Servicio exento y servicio exonerado caen en su bucket.
- El total del comprobante no cambia al marcar/desmarcar `esServicio`.

---

## 3. Multimoneda CRC / USD / EUR

### 3.1 Alcance

Moneda **por comprobante**. Se elige al crear la factura/tiquete y se guarda con la tasa. No se convierten precios de artículo ni se lleva contabilidad multi-moneda.

### 3.2 Modelo

`CodigoTipoMoneda` ya existe y se persiste en `resumen_factura`:

```java
codigoMoneda  // "CRC" | "USD" | "EUR"
tipoCambioMoneda // CRC por 1 unidad de codigoMoneda; CRC => 1
```

Agregar dimensión de moneda a la tasa:

```java
// Models/TipoCambio.java
@Column(name = "moneda", nullable = false, length = 3)
private String moneda = "USD";
```

Índice único `(moneda, fecha)` para que USD y EUR del mismo día convivan.

### 3.3 Obtención de la tasa

- **USD:** ya funciona (`/indicadores/tc/dolar`).
- **EUR:** verificar si Hacienda expone `/indicadores/tc/euro`. Si no existe, dos opciones:
  1. Descargar del BCCR (requiere token y formato distinto).
  2. Carga manual diaria desde `tipo-cambio` (ya hay UI y `manual=true`).
- **Regla:** si no hay tasa para la moneda y la fecha, **no se permite emitir** en esa moneda. Fallar con mensaje claro, no usar `1`.

`ProgramadorTareas`: renombrar `actualizarTipoCambioUSD()` a `actualizarTiposCambio()` y recorrer `{USD, EUR}`.

### 3.4 Emisión

`ComprobanteService`:

```java
String monedaCodigo = datos.moneda();            // "CRC" | "USD" | "EUR"
BigDecimal tipoCambio = "CRC".equals(monedaCodigo)
        ? BigDecimal.ONE
        : tipoCambioService.tasaVigente(monedaCodigo, LocalDate.now());
```

- Validar que `tipoCambio > 0`.
- `moneda.setCodigoMoneda(monedaCodigo)` y `moneda.setTipoCambioMoneda(tipoCambio)`.
- Los importes de línea se calculan en esa moneda; `CarritoCalculations` no cambia.

**Escala y redondeo:**

| Moneda | Precio unitario | Totales XML | Visual |
|---|---|---|---|
| CRC | 5 decimales (actual) | 5 | 2 |
| USD | 2–5 | 5 | 2 |
| EUR | 2–5 | 5 | 2 |

Mantener `BigDecimal` y `HALF_UP`; no introducir `double`.

### 3.5 Notas de crédito y débito

`DevolucionesResource` debe leer la moneda y la tasa del comprobante original:

```java
ncResumen.setCodigoMoneda(original.getResumen().getCodigoMoneda());
```

Nunca usar la tasa del día para una NC. Si el original es CRC, la NC es CRC.

### 3.6 POS y UI

- Selector de moneda junto al badge de tipo de cambio (`templates/pages/facturas/factura.html`, `PosResource.tipoCambioBadge()`).
- Mostrar `₡ / $ / €` y la tasa aplicada; si es CRC, ocultar la tasa.
- Persistir la moneda elegida en la sesión del carrito (`CartSessionStore`) para que no se pierda al recargar.
- `ConfiguracionAplicacion`: `monedaPorDefecto` (`CRC` por defecto).

### 3.7 Pagos

- `EntradaPago.monto` se interpreta en la moneda del comprobante.
- Validar que la suma de pagos = `totalComprobante` en esa moneda (ya existe la validación; solo cambia la unidad).
- Caja: `CierreCaja` debe separar por moneda o guardar el equivalente en CRC con la tasa del día. **Recomendación:** guardar `monto` en moneda del comprobante + `moneda` + `tipoCambio`, y calcular el equivalente CRC al cerrar.

### 3.8 Reportes y D-104

- Los reportes deben mostrar la moneda del comprobante y, para totales consolidados, convertir a CRC con la **tasa guardada**, no la actual.
- El D-104 es en colones: sumar `importe * tipoCambio` por comprobante.
- Añadir columna "Moneda" y "Tipo cambio" a los listados de emitidos/recibidos.

### 3.9 Migración

```sql
ALTER TABLE tipo_cambio ADD COLUMN moneda VARCHAR(3) NOT NULL DEFAULT 'USD';
CREATE UNIQUE INDEX ux_tipo_cambio_moneda_fecha ON tipo_cambio (moneda, CAST(fecha AS date));

-- Comprobantes históricos: ya son CRC
UPDATE resumen_factura
SET codigo_moneda = 'CRC', tipo_cambio_moneda = 1
WHERE codigo_moneda IS NULL;
```

`codigo_moneda` puede ser null en filas viejas; el backfill es obligatorio antes de habilitar USD/EUR.

### 3.10 Pruebas

- Factura USD: `CodigoMoneda=CodigoMoneda USD`, `TipoCambio` igual a la tasa del día, totales en USD.
- Factura CRC: `TipoCambio=1`.
- Sin tasa para EUR: la emisión falla con error explícito.
- NC de una factura USD: hereda USD y la misma tasa.
- D-104: un comprobante USD entra convertido a CRC con la tasa histórica.
- Recargo/descuento y pago dividido en USD cuadran al centavo.

---

## 4. Métodos de pago

### 4.1 Estado

El catálogo de Hacienda está en `Tipo_MedioPago` (01–07, 99). El problema es que `EntradaPago.metodoPagoLabel` define además `08` y `10`, que el enum no reconoce. Dos fuentes de verdad ⇒ un pago con código 08 se etiqueta pero falla al validar.

### 4.2 Cambios

1. **Una sola fuente:** `EntradaPago.metodoPagoLabel` debe delegar en `Tipo_MedioPago.fromCodigo(code).getDescripcion()`.
2. **Códigos 08 y 10:** decidir con Hacienda si existen en el catálogo v4.4.
   - Si existen: agregarlos al enum.
   - Si no: eliminarlos de la etiqueta y migrar datos.
3. **Referencia de pago:** agregar a `EntradaPago`:

```java
@Nullable
private String referencia; // voucher, autorización, teléfono SINPE, número de transferencia
```

   `@XmlTransient` (no viaja en el XML fiscal; es para conciliación y arqueo).
4. **UI de cobro:** pedir referencia solo cuando el método lo amerita:
   - Tarjeta → autorización/voucher.
   - SINPE → teléfono o comprobante.
   - Transferencia → banco y número.
   - Cheque → número.
5. **Caja:** `CierreCaja` ya separa montos por método; agregar el detalle de referencias al arqueo.

### 4.3 Pruebas

- Los 8 códigos del enum se etiquetan y se aceptan.
- Un código desconocido falla con mensaje claro, no con `NullPointerException`.
- El arqueo muestra la referencia de cada pago no efectivo.

---

## 5. Fases y esfuerzo

| Fase | Contenido | Días | Depende de |
|---|---|---|---|
| F1 | `esServicio`: columna, UI, resumen, pruebas | 3–4 | — |
| F2 | `TipoCambio.moneda` + carga USD/EUR + migración | 4–5 | — |
| F3 | Emisión multimoneda (FE/TE) + selector POS + persistencia | 4–5 | F2 |
| F4 | NC/ND heredan moneda y tasa | 2 | F3 |
| F5 | Reportes + D-104 con conversión histórica | 3 | F3 |
| F6 | Medios de pago: fuente única + referencia + arqueo | 2–3 | — |
| F7 | Pruebas E2E y documentación | 2 | F1–F6 |

**Total: 17–23 días.** F1 y F6 se pueden hacer en paralelo con F2.

---

## 6. Criterios de aceptación

- Un artículo marcado como servicio produce `TotalServGravados > 0` y `TotalMercanciasGravadas = 0` en su línea.
- Una factura en USD emite `CodigoMoneda=USD` y `TipoCambio` igual a la tasa vigente, no `1`.
- Una factura en CRC sigue emitiendo `TipoCambio=1` sin cambios.
- Sin tasa EUR del día, el sistema **no** emite y muestra el motivo.
- Una NC de una factura USD conserva USD y la tasa original.
- El D-104 de un período con facturas USD y CRC suma todo en colones con la tasa de cada factura.
- Un pago con código 08 no se acepta si el enum no lo define, y el mensaje lo dice.
- El arqueo de caja muestra la referencia de cada pago con tarjeta/SINPE/transferencia.

---

## 7. Riesgos

| Riesgo | Mitigación |
|---|---|
| Redondeo distinto entre línea y total en USD | Mantener la regla actual "precio final redondeado es la fuente de verdad" y probar con 3 decimales |
| Tasa desactualizada o ausente | Bloquear emisión; no caer a `1` |
| NC con tasa de hoy | Leer siempre del comprobante original |
| D-104 descuadrado | Convertir por comprobante con la tasa guardada; test con mezcla de monedas |
| `codigo_moneda` null en histórico | Backfill antes de habilitar el selector |
| Doble fuente de medios de pago | Eliminar el `switch` de `EntradaPago` y delegar en el enum |
| Caja con monedas mezcladas | Guardar moneda + tasa por pago y consolidar en CRC al cerrar |

---

## 8. Archivos a tocar

| Archivo | Cambio |
|---|---|
| `Models/Articulos/Articulos.java` | `esServicio` |
| `Models/Detalles/LineaDetalle.java` | Solo si se agrega `notas` (no obligatorio para este plan) |
| `Services/ComprobanteService.java` | `esServicio` por línea; moneda y tasa en `resumenComprobante` |
| `Controllers/Api/App/DevolucionesResource.java` | Heredar moneda/tasa del original |
| `Models/TipoCambio.java` | `moneda` |
| `Services/TipoCambioService.java` | Carga USD/EUR; `tasaVigente(moneda, fecha)` |
| `Utils/ProgramadorTareas.java` | Recorrer monedas |
| `Models/EntradaPago.java` | `referencia`; etiqueta desde el enum |
| `Models/Enums/Tipo_MedioPago.java` | Resolver 08/10 según catálogo |
| `Controllers/Api/App/PosResource.java` | Selector de moneda, badge, validación de pagos |
| `templates/pages/facturas/factura.html` | Selector de moneda |
| `templates/pages/articulos/form-articulo.html` | Checkbox "Es servicio" |
| `Models/ConfiguracionAplicacion.java` | `monedaPorDefecto` |
| Reportes / D-104 | Columna moneda y conversión histórica |

---

## 9. Lo que este plan NO hace

- No convierte precios de artículo entre monedas.
- No lleva contabilidad multi-moneda ni revalorización.
- No permite pagar en una moneda distinta a la del comprobante (v2).
- No cambia firma, envío, XSD ni la cola de reintento.
- No toca el módulo de restaurante (`docs/plans/restaurante-plan-2026-11.md`); ambos comparten `ComprobanteService`, así que **F1 de este plan debe entrar antes** que la fase de propina del plan de restaurante.
