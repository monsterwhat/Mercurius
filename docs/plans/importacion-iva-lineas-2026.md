# Importación 2026: IVA, líneas con código de barras y movimientos de venta

**Fecha:** 2026-11-16 · **Servidor:** `192.168.1.72` (base `mercurius`)
**Origen:** `Z:\Sistema\Sistema_vsuper_20261102-182919.zip` (DBF extraídos en
`C:\Users\alvar\AppData\Local\Temp\opencode\sistema_fe`)

## 1. Qué estaba mal

| Problema | Detalle |
|---|---|
| **IVA de febrero duplicado** | `detafact.dbf` y `detafactbk27022026.DBF` traen las mismas líneas de febrero (rango 745283–752792). Sumar ambos archivos dejó febrero con ₡4.199.968,94 de IVA en vez de ₡2.185.944,21. |
| **IVA de agosto incompleto** | Solo se había cubierto hasta el 15-08; el resto quedó en cero. Corregido: ₡2.421.714,11. |
| **Líneas sin código de barras** | 110.457 líneas de enero–julio no tenían `codigo_comercial` ni impuesto por línea; solo octubre–noviembre lo traían (importadas de XML). |
| **Movimientos de febrero duplicados** | 26.410 filas de `Venta` cuando las líneas reales son 13.617: se importaron kardex FE **y** salidas, y solo 824 filas deduplicaron. |
| **Artículo faltante** | `7443004340162` (GUARO ANEJAZO 335ML), 322 líneas y ₡402.600 en el periodo, no existía en `articulos`. |

## 2. De dónde sale el IVA de ventas

Se revisaron todas las tablas con campos de impuesto:

| Tabla | Campo | 2026 |
|---|---|---|
| `facturas.DBF`, `facturasenero2026`, `facturasdiarias` | `I_VENTAS` | todo en cero |
| `salidas.DBF` | `VENTAIMP` | todo en cero |
| `KARDEX.DBF` | — | no tiene impuesto |
| `fe_encabezado.DBF` | `TOTALIVA13/8/2/4/1` | 276 **facturas de compra**, no de venta |
| `detafact.dbf`, `detafactbk27022026.DBF` | `IMP` | **único IVA por línea de venta** |

`detafact_respaldo_respaldo.dbf` cubre 2022-01 a 2024-04 y no toca 2026.

## 3. Cómo se cruzan las líneas

Dos trampas de los DBF:

1. **La fecha viene en binario.** `FECHA` es un `DateTime` de VFP (día juliano +
   milisegundos). Leerla como texto da basura; hay que decodificarla. Sin eso,
   filtrar por rango numérico mezcla 2025 con 2026 porque **los números de
   factura se repiten entre años**.
2. **`NNUMELINEA` se repite** cuando el tiquete tiene líneas idénticas, así que
   deduplicar por número de línea borra líneas válidas.

`scripts/conciliar_lineas_2026.py` resuelve ambas: decodifica la fecha, descarta
líneas fuera de ±75 días de la fecha del comprobante y, por factura, elige el
conjunto (detafact / backup / unión) cuya suma **cuadre con el total del
comprobante**.

## 4. Resultado

| Métrica | Antes | Después |
|---|---|---|
| IVA enero–agosto | ₡21.363.589,42 | **₡20.249.050,46** |
| IVA febrero | ₡4.199.968,94 | **₡2.185.944,21** |
| IVA agosto | ₡1.496.940,95 | **₡2.421.714,11** |
| Movimientos `Venta` de febrero | 26.410 | **13.617** |
| Líneas ene–jul con código de barras | 0 | **110.452** |
| Líneas ene–jul con impuesto por línea | 19.652 | **110.452** |
| Artículos del catálogo | 22.854 | **22.855** |

D-104 por mes (comprobantes emitidos 2026):

| Mes | Comprobantes | Venta neta | IVA |
|---|---|---|---|
| 01 | 9.926 | 23.821.442,77 | 2.803.788,58 |
| 02 | 7.755 | 18.193.705,25 | 2.185.944,21 |
| 03 | 10.424 | 27.072.072,31 | 3.220.972,12 |
| 04 | 10.459 | 26.599.367,01 | 3.212.333,24 |
| 05 | 7.492 | 17.721.118,80 | 2.137.158,95 |
| 06 | 7.017 | 16.227.701,97 | 1.943.120,78 |
| 07 | 8.521 | 19.374.871,99 | 2.349.305,86 |
| 08 | 9.602 | 20.436.847,09 | 2.421.714,11 |

Los totales de venta coinciden **al centavo** con `facturas.DBF` en marzo, abril,
mayo y julio.

## 5. Scripts

| Archivo | Qué hace |
|---|---|
| `scripts/conciliar_lineas_2026.py` | export + DBF → `lineas_2026.csv`, `iva_2026_fix.csv`, `conciliacion_lineas.csv` |
| `scripts/completar_lineas_2026.sql` | rellena código de barras, CAByS, base e impuesto por línea |
| `scripts/corregir_iva_2026.sql` | corrige `total_impuesto`/`total_venta_neta` de los resúmenes (respaldo en `resumen_factura_bkp_iva_2026`) |
| `scripts/reconstruir_ventas_febrero_2026.sql` | borra y reconstruye los movimientos `Venta` de febrero (respaldo en `inventario_bkp_venta_feb2026`) |
| `scripts/crear_articulo_guaro_2026.sql` | crea el artículo faltante con stock y precio |

Ejecución: los CSV se copian a `/tmp/dbf_reconciliacion/` y se corre
`psql -v aplicar=false|true -f ...` como `postgres`. Todos son idempotentes.

## 6. Pendientes conocidos

- **2.097 facturas (3,4 %)** donde la suma de líneas del origen no cuadra con el
  total de la factura (faltan líneas repetidas que el sistema viejo no guardó).
  Es un problema del respaldo, no del cruce: el total de la factura se mantiene.
- **Stock:** `articulostock` viene del maestro del sistema viejo, no de la suma
  del kardex (8.984 artículos ya difieren). Por eso la reconstrucción de febrero
  no toca existencias.
- **Líneas de agosto:** 16.759 líneas, solo 3.464 con código de barras; vienen de
  XML, que solo trae código cuando el emisor lo incluyó.
