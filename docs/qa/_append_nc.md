
## 27. Notas de crédito y re-creación de tiquetes (2026-11-09)

**NC electrónica — bugs corregidos:**
- El total se calculaba con `precioUnitario × cantidad` (base antes de descuento y sin IVA). Ahora se prorratea `montoTotalDeLinea` (o `subTotal + impuestoNeto`) y la línea copia `montoTotal`, `subTotal`, `baseImponible`, `impuestoNeto` y `montoTotalLinea`.
- Cuando el comprobante importado no tiene filas en `descuento`/`impuesto`, se sintetizan a partir de los agregados (descuento código 07; IVA con la tarifa implícita, 13% en los casos probados).
- El consecutivo de la NC era de 18 dígitos; Hacienda exige 20 (`\d{20,20}`). Se agregó la situación: `sucursal(3)+terminal(3)+situación(2)+tipo(2)+secuencia(10)`.
- La NC exigía un `Clientes` que coincidiera por nombre con el receptor. Ahora usa el receptor original del comprobante cuando no hay cliente, preservando identificación, correos y ubicación.
- El historial se refresca por OOB; el `<tbody>` va envuelto en una tabla oculta para que el parser no descarte el atributo `hx-swap-oob`.
- `notacredito.haciendaEstado` se actualiza con el estado del comprobante electrónico.

**Verificación (shadow, sin credenciales Hacienda):** las NC quedan `ENVIADO` con el error local de firma (no hay certificado); no se envía nada a Hacienda.
- `00100101020000000003`: TotalVenta 801,64 / Descuento 45,00 / Neta 756,64 / IVA 98,36 / Total 855,00.
- `00100101020000000004`: 712,56 / 40,00 / 672,56 / 87,44 / 760,00.
- `00100101020000000006`: 966,04 / 512,50 / 453,54 / 58,96 / 512,50; receptor original “ALVARO ENRIQUE PADILLA FONSECA” sin fila en `Clientes`.

**Tiquetes re-creados uno por uno en el POS (Zoidberg):**

| Nuevo consecutivo | Carrito | Total |
|---|---|---|
| 00100001040001350010 | 2 × CAFI ASPIRINA | ₡150,0012 |
| 00100001040001350011 | 1 × CAFI ASPIRINA | ₡75,0006 |
| 00100001040001350012 | 1 × CAFI ASPIRINA | ₡75,0006 |
| 00100001040001350013 | 1 × CULANTRO ROLLO | ₡475,003 |
| 00100001040001350014 | 1 × BOHEMIA 12ONZ | ₡599,9961 |
| 00100001040001350015 | 1 × BOHEMIA 12ONZ | ₡599,9961 |
| 00100001040001350016 | 2 × BOHEMIA con JR. ALVARO 50% | ₡599,9961 |

Los totales, líneas, descuentos e IVA coinciden con los originales (`350003`–`350009`). El estado local es `ENVIADO` con el error de firma esperado.

**Logo:** se eliminó `filter: invert(1)` de `.mercurius-mark` en modo oscuro; el logo cargado conserva sus colores.

**Pendiente:** la secuencia de NC puede saltar si una generación falla (los intentos fallidos consumieron el 5); en producción conviene revisar que la reserva del consecutivo sea transaccional.
