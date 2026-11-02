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
| `comprobantesrecibidos` | 177 | **2 021** (+252 electrónicas +1 592 legadas) |
| `linea_detalle` | 564 992 | **567 997** (+3 005 líneas de compra) |
| `encabezado` | 753 769 | **755 613** |

Rango de fechas de los movimientos: **2013-01-01 → 2026-10-26**.
Facturas de compra electrónicas: **2018-10-11 → 2026-10-27**.
Facturas legadas (cuentas por pagar): **2015-01-01 → 2022-01-28**, con `paid` tomado
del `ESTADO` del sistema viejo (1 570 canceladas).

Correcciones de datos del origen:
- 2 movimientos `ENTRADAS` con fecha 2085 → 2013-01-01.
- **7 facturas legadas** con fecha corrupta (1520/2085) → 2015-01-01.

Idempotencia: el script carga los CSV del DBF en tablas temporales, arma la tabla
`inv_keys` con la clave natural de lo que ya existe
`(articulo_codigo, fecha, cantidad, documento, tipo)` y **solo inserta lo que no
matchea**. Correrlo dos veces no duplica nada (verificado: segunda pasada
`faltantes = 0` para KARDEX y `claves_faltantes = 0` para compras).

## 3. Lo que falta y por qué no se hizo todavía

1. **XML**: hay 34 424 archivos en disco, pero el índice `FE_DOCUMENTOS` apunta a
   `C:\VSUPER\<clave>.xml` (el PC viejo) y solo 23 de las 276 claves de compra
   tienen XML disponible aquí. Hace falta copiar esa carpeta desde la máquina
   original.
2. **AJUSTESINV** (3 419 ajustes globales): no llevan código de artículo; el
   import viejo los metió con `articulo_codigo = null` y fecha del día del
   import. No se reimportaron.

## 4. Scripts

| Archivo | Qué hace |
|---|---|
| `scripts/generar_csv_reconciliacion.py` | DBF → CSV (artículos, kardex, entradas, ajustes, fe_*, compras) |
| `scripts/reconciliacion.sql` | Compara por clave natural y reporta faltantes (solo lectura) |
| `scripts/importar_movimientos_stock.sql` | Importa stock + movimientos faltantes (idempotente, transaccional) |
| `scripts/importar_facturas_compra.sql` | Importa 252 facturas electrónicas + 1 592 legadas (dry-run con `-v aplicar=false`) |
| `scripts/cruzar_claves_xml.py` | Cruza claves faltantes con los XML disponibles |
| `scripts/inspeccionar_dbf.py` / `inspeccionar_fe_documentos.py` | Introspección de los DBF |

Ejecución (servidor): los CSV se copian a `/tmp/dbf_reconciliacion/` y se corre
`psql -f` como `postgres`.

