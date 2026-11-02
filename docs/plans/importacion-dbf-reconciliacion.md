# Importación de la base vieja (vSuper DBF) — reconciliación e import

**Fecha:** 2026-11-02 · **Base viva:** `192.168.100.99:5432/mercurius`
**Origen:** `F:\Desktop\WebDAV-Facturacion\extracted\Sistema_vsuper_20261004\` (snapshot 2026-10-03)
**Regla:** nunca se decide por `id`; se compara por **claves naturales verificables**
(`codigobarra`, la tupla de movimiento, la **clave de Hacienda** de 50 dígitos).

## 1. Reconciliación (solo lectura)

| Dominio | Origen | Ya estaba | Faltaba | Acción |
|---|---|---|---|---|
| Artículos (`MAESTRO.CODIGO` = codigobarra) | 5 996 | **5 996** | 0 | nada |
| Stock (`MAESTRO.CANTIDAD`) | 5 996 | 2 494 | **3 502** | **importado** |
| Movimientos `KARDEX` (2026) | 143 372 | 0 | 142 712 | **importado** |
| Movimientos `ENTRADAS` | 14 317 | 0 | 8 556 | **importado** |
| Facturas de compra electrónicas (`fe_encabezado.NUMEROFACT` = clave) | 276 | 24 | **252** | pendiente |
| Facturas de compra legadas (`facturascompras`) | 1 592 | — | 1 592 | **sin destino** en el modelo nuevo |
| XML en disco | 34 424 archivos | — | 23 de las 252 claves de compra | requiere los XML del PC viejo |

## 2. Lo importado (aplicado y verificado)

| Tabla | Antes | Después |
|---|---|---|
| `articulostock` | 2 494 | **5 996** (1 116 con stock **negativo**) |
| `inventario` | 673 326 | **824 594** (+151 268) |

Rango de fechas de los movimientos: **2013-01-01 → 2026-10-26**.
Dos filas de `ENTRADAS` traían fecha corrupta (2085-12-01); se corrigieron a 2013-01-01.

Idempotencia: el script carga los CSV del DBF en tablas temporales, arma la tabla
`inv_keys` con la clave natural de lo que ya existe
`(articulo_codigo, fecha, cantidad, documento, tipo)` y **solo inserta lo que no
matchea**. Correrlo dos veces no duplica nada.

## 3. Lo que falta y por qué no se hizo todavía

1. **252 facturas de compra electrónicas.** Los datos están en `fe_encabezado` +
   `fe_detalle` (cabecera y líneas), pero el modelo nuevo necesita el grafo
   `encabezado → detalle_servicio → linea_detalle`, `resumen_factura` y
   `comprobantesrecibidos`. Se puede construir por SQL, pero hay que fijar la
   fecha desde la **clave** (el campo `FECHAEMISI` del DBF está corrupto) y crear
   el `emisor` de cada proveedor.
2. **1 592 facturas de compra legadas** (`facturascompras.dbf`): son cuentas por
   pagar del sistema viejo (proveedor + factura + saldo). **No existe tabla
   equivalente** en Mercurius; importarlas requeriría decidir dónde viven.
3. **XML**: hay 34 424 archivos en disco, pero el índice `FE_DOCUMENTOS` apunta a
   `C:\VSUPER\<clave>.xml` (el PC viejo). Solo 23 de las 252 claves de compra
   tienen XML disponible aquí.

## 4. Scripts

| Archivo | Qué hace |
|---|---|
| `scripts/generar_csv_reconciliacion.py` | DBF → CSV (artículos, kardex, entradas, ajustes, fe_*, compras) |
| `scripts/reconciliacion.sql` | Compara por clave natural y reporta faltantes (solo lectura) |
| `scripts/importar_movimientos_stock.sql` | Importa stock + movimientos faltantes (idempotente, transaccional) |
| `scripts/cruzar_claves_xml.py` | Cruza claves faltantes con los XML disponibles |
| `scripts/inspeccionar_dbf.py` / `inspeccionar_fe_documentos.py` | Introspección de los DBF |

Ejecución (servidor): los CSV se copian a `/tmp/dbf_reconciliacion/` y se corre
`psql -f` como `postgres`.
