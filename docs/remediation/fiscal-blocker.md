# Fiscal XML blocker — exempt line `Impuesto`

Fecha: 2026-11-22
Estado: **resuelto y verificado**. `mvn -f app/pom.xml clean test` termina en
`Tests run: 1572, Failures: 0, Errors: 0, Skipped: 1`. Queda solo la nota de
clasificación legal por CABYS (sección 6).

## 1. Fallos que motivaron el bloqueo

La suite limpia reportaba:

```text
Tests run: 1569, Failures: 3, Errors: 0, Skipped: 1
```

Fallos:

- `DevolucionEnvioFueraDeTransaccionTest.rechazoDeHaciendaNoDeshaceLaDevolucion`
- `PosFacturarEnvioFueraDeTransaccionTest.rechazoDeHaciendaNoDeshaceLaVenta`
- `PosFacturarEnvioFueraDeTransaccionTest.segundaVentaNoEsperaAlEnvioDeLaPrimera`

Al investigarlos se separaron tres causas distintas:

1. La línea exenta no emitía `<Impuesto>` y el XSD v4.4 la rechazaba
   (`minOccurs=1`).
2. La NC de una factura exenta sintética no llevaba `ProveedorSistemas`, que el
   XSD v4.4 también exige.
3. El POS mockeaba `hayConectividadConHacienda()`, pero la venta consulta
   `conectividadParaVenta()`; el mock devolvía `false` y la prueba tomaba la
   rama offline (situación 3) en vez del envío inmediato.

Además, el código marcaba `haciendaEstado = "RECHAZADO"` ante un rechazo de
fondo de Hacienda, contradiciendo el contrato de `2e5f6d89` (un rechazo es una
respuesta: el sello `ENVIADO` se conserva y el veredicto queda en el
encabezado).

## 2. Evidencia oficial

### XSD oficial incluido en el repositorio

`app/src/main/resources/xsd/v4.4/TiqueteElectronico_V4.4.xsd`:

```xml
<xs:element name="Impuesto" type="ImpuestoType" maxOccurs="1000">
```

`minOccurs` se omite, por lo que vale **1**: toda línea de detalle debe llevar
al menos un `Impuesto`, incluidas las exentas.

En TE/FE el mismo XSD exige además:

```text
Impuesto
ImpuestoAsumidoEmisorFabrica
ImpuestoNeto
MontoTotalLinea
```

### Nota 8.1 de Hacienda v4.4

Resolución DGT-R-000-2024 (Disposiciones Técnicas de Comprobantes
Electrónicos), nota 8.1:

| Código | Tarifa |
|---|---|
| `01` | Tarifa 0% (Artículo 32, num 1, RLIVA) |
| `02` | Tarifa reducida 1% |
| `03` | Tarifa reducida 2% |
| `04` | Tarifa reducida 4% |
| `08` | Tarifa general 13% |
| `09` | Tarifa reducida 0.5% |
| `10` | Tarifa Exenta (Ley 9635, Artículo 8) |
| `11` | Tarifa 0% sin derecho a crédito |

El Anexo y Estructuras v4.4 asocia los CAByS exentos con el código `10`:

> `TotalServExentos` … será de condición obligatoria cuando se seleccionen
> códigos CAByS que correspondan a un servicio y el servicio esté exento de IVA
> (**que se utilice el código 10 de tarifa exenta del IVA de la nota 8.1**).

Y para el código `10` indica que el campo `Monto` debe ser `0`.

Fuente:
`https://www.hacienda.go.cr/docs/ANEXOS_Y_ESTRUCTURAS_V4.4.pdf`

### Inventario de producción

`docs/remediation/cabys-inventory-prod.md` y
`docs/remediation/cabys-afectados-produccion.csv` inventarían los 11 CABYS con
`impuesto = 0` realmente vinculados a artículos activos en
`192.168.1.72/mercurius`. No es un problema de todo el catálogo: son 92
artículos activos.

## 3. Decisión implementada

- Una tasa CABYS `0` (o vacía) se emite como **Tarifa Exenta `10`**, con
  `Tarifa = 0` y `Monto = 0`.
- `01` queda reservado para la tarifa 0% del Art. 32.1 RLIVA (CCSS,
  municipalidades y casos calificados).
- `11` (0% sin derecho a crédito) requiere una clasificación legal explícita
  que el catálogo CABYS no aporta; no se deriva de `impuesto = 0`.

El cambio es reversible y está concentrado en
`Tipo_TarifaIVA.getTarifa("0"/"")`.

## 4. Cambios de producción

- `Models/Enums/Tipo_TarifaIVA.java`
  - `getTarifa("0")` y `getTarifa("")` devuelven `TARIFA_EXENTA` (`10`).
  - Descripciones y comentarios alineados con la nota 8.1.
- `Services/ComprobanteService.detallesComprobante()`
  - Emite `<Impuesto>` en **toda** línea, no solo cuando el monto es distinto
    de cero.
  - Línea exenta: `Codigo=01`, `CodigoTarifaIVA=10`, `Tarifa=0`, `Monto=0`.
  - Línea gravada: sin cambios (código de tarifa y montos reales).
- `Services/ComprobanteService.resumenComprobante()`
  - El desglose omite tasas `0` (no hay impuesto cobrado; `TotalExento` ya
    lleva la base).
- `Controllers/Api/App/DevolucionesResource.java`
  - Si una línea exenta copiada de una factura legacy no trae `Impuesto`, la NC
    lo sintetiza en cero (`10`) para que el XSD la acepte.
  - El bucket gravado/exento de la NC se decide por la tarifa efectiva, no por
    la mera presencia del nodo.
- `Services/ComprobanteService.enviarComprobanteAHacienda()`
  - Un rechazo de fondo (`SubmitResult.estado == "RECHAZADO"`) conserva
    `haciendaEstado = "ENVIADO"` y deja el veredicto en el encabezado.
  - Un fallo local (`estado == "ERROR"`) sí pasa a `RECHAZADO`, como pidió
    `7d055a21`.

## 5. Matriz de regresión

- `Services/ComprobanteTaxMathTest`
  - toda tasa válida emite exactamente un `Impuesto` enlazado;
  - la línea exenta emite `01` / `10` / `0` / `0` y conserva sus montos;
  - la línea gravada conserva tarifa y monto;
  - el resumen clasifica la línea exenta en `TotalExento`.
- `Documentos/ElectronicDocumentPipelineTest`
  - `exemptLineTEValidatesAgainstTheRealXsd`: TE exento firmado valida contra
    el XSD v4.4 real y lleva exactamente un `<Impuesto>`.
  - `taxableLineKeepsItsTaxNodesAndAmounts`: el TE gravado conserva
    `CodigoTarifaIVA=08`, `Tarifa=13`, `Monto=13` y valida contra el XSD.
- `Controllers/Api/App/PosFacturarEnvioFueraDeTransaccionTest`
  - rechazo de Hacienda no borra la venta y deja el motivo visible;
  - dos ventas consecutivas no se serializan detrás del envío de la primera.
- `Controllers/Api/App/DevolucionEnvioFueraDeTransaccionTest`
  - rechazo de Hacienda no revierte la devolución ni la NC.
- `Controllers/Api/App/DevolucionesResourceTest`
  - la NC de una factura mixta (gravada + exenta) conserva líneas, resumen y
    desglose; la línea exenta lleva su `Impuesto` cero.

## 6. Nota legal residual

La regla implementada es “tasa 0 → Tarifa Exenta (`10`)”, que es el mapeo que
Hacienda documenta para CAByS exentos. Si el responsable fiscal clasifica
alguno de los 11 CABYS de producción como **no sujeto** (sin derecho a
crédito), esa línea debe emitir `11` en lugar de `10`. El catálogo CABYS solo
publica la tasa, no la base legal, así que esa corrección debe hacerse por
CABYS y quedar registrada en la hoja de
`docs/remediation/cabys-inventory-prod.md`.

No se implementó un `0 → 10` “invisible”: la decisión está citada en el código
y es un único punto de cambio.

## 7. Gate de release

```text
mvn -f app/pom.xml clean test
Tests run: 1572, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

El resultado queda registrado también en
`docs/remediation/final-report.md`.
