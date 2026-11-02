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

## 5. XML de Hacienda (Z:\Facturas)

15 ZIP copiados a temporales y extraídos ahí (**nunca en Z:**):
40 207 XML, 13 030 claves únicas, 12 404 ya estaban en la base.
**624 importadas** como comprobantes EMITIDOS (28-30 de octubre de 2026), con
1 109 líneas: `comprobantesemitidos` 751 869 → **752 493**.
Quedan 2 claves sin XML.

Dos ZIP (64 MB y 89 MB) no se podían copiar por el límite de 50 MB del cliente
WebDAV de Windows; se subió `FileSizeLimitInBytes` a 4 GB (UAC) y se reinició
`WebClient`.

## 6. Fuentes revisadas y lo que queda

| Fuente | Contenido | Estado |
|---|---|---|
| `Z:\Sistema\Sistema_vsuper_20261027-163717.zip` | snapshot más nuevo (177 MB, hasta 27-oct) | revisado; la base ya llega al 27-oct |
| `Z:\Sistema\Sistema_vsuper_20261004.zip` | snapshot usado para todo el import | ✅ |
| `Z:\Facturas\*.zip` | 40 207 XML | ✅ 624 importadas |
| `E:\VPALMERAS` | respaldo 2019: `facturasdiarias.dbf` 500 720 ventas | **parcial** |
| `E:\VPALMERAS\DETAFACT.DBF` | **vacío** (sin líneas) | ❌ |

Faltantes por año según `E:\VPALMERAS`:

| Año | Origen | Base | Falta |
|---|---|---|---|
| 2013 | 49 402 | 49 396 | 6 |
| 2014 | 208 561 | 208 561 | 0 |
| 2015 | 203 103 | 149 874 | **53 229** |
| 2016 | 39 552 | 0 | **39 552** |
| 2017 | no existe en ningún respaldo | 0 | — |

Esas **92 781 ventas de 2015-2016** se pueden importar desde E: pero **sin
líneas de detalle** (el `DETAFACT` del respaldo está vacío).

## 7. Reconstruccion total (2 de noviembre de 2026)

La base original tenia cabeceras parciales y casi ningun detalle. Se reconstruyo
todo lo que existe en los archivos, usando **todas** las fuentes disponibles.

### De donde salio cada anio

| Anio | Cabeceras | Lineas de detalle | Movimientos |
|---|---|---|---|
| 2013 | base (E:) | **sin fuente** | sin fuente |
| 2014 | base (E:) | **sin fuente** | sin fuente |
| 2015 | E: `facturasdiarias` + `kardex` | oct-dic del kardex (ene-sep sin fuente) | kardex + entradas |
| 2016 | kardex FE + E: ene-feb | kardex FE | kardex |
| 2017 | no hubo ventas (numeracion continua 2016 a 2018) | - | - |
| 2018 | base | solo 27 (kardex 2018 vacio) | kardex |
| 2019 | base | sin fuente | kardex |
| 2022 | reconstruidas de `detafact_respaldo_respaldo` | idem | detafact |
| 2023 | base + snapshot | base + snapshot | base + snapshot |
| 2024 | **faltaban 63 046** -> snapshot | snapshot | snapshot |
| 2025 | **faltaban 3 708** -> snapshot | snapshot | snapshot |
| 2026 | **faltaban 12 315** -> snapshot | snapshot | snapshot |

### Scripts

| Script | Que hace |
|---|---|
| `scripts/extraer_historicas.py` | DBF a CSV (ventas, kardex, detafact, entradas) |
| `scripts/numeros_fuente.py` | Lista de numeros de factura de todas las fuentes |
| `scripts/importar_ventas_historicas.sql` | 2015 (faltantes), 2016 y 2022: cabeceras + lineas |
| `scripts/importar_ventas_modernas.sql` | 2023-2026: cabeceras + lineas |
| `scripts/importar_movimientos_historicos.sql` | Movimientos 2015-2016 y 2022 |
| `scripts/importar_movimientos_modernos.sql` | Movimientos 2023-2026 |
| `scripts/rellenar_lineas_faltantes.sql` | Detalle de facturas que quedaron sin lineas |
| `scripts/corregir_totales_historicos.sql` | `total_comprobante` 2013-2015 y `total_venta_neta` 2018-2025 |

Los imports son idempotentes: la clave es (anio, numero de factura) comparado
numericamente, porque la base mezcla formatos (`959552`, `000...959552`,
`709162@20131022`, `722657@20251116`).

### Volumen agregado en esta pasada

- **398 562 cabeceras** nuevas
- **728 439 lineas** de detalle
- **506 941 movimientos** de inventario

`comprobantesemitidos`: 752 493 -> **1 151 055**
`linea_detalle`: 569 106 -> **1 297 545**
`inventario`: 824 594 -> **1 331 535**

### Estado final por anio

| Anio | Facturas | Total CRC | Sin detalle |
|---|---|---|---|
| 2013 | 49 396 | 125 022 501 | 49 396 |
| 2014 | 208 561 | 547 785 205 | 208 560 |
| 2015 | 203 102 | 573 763 941 | 154 417 |
| 2016 | 147 227 | 428 611 310 | 6 |
| 2017 | 0 | 0 | - |
| 2018 | 14 094 | 43 540 217 | 14 067 |
| 2019 | 2 | 20 780 | 2 |
| 2020-2021 | 0 | 0 | - |
| 2022 | 119 036 | 304 115 938 | 0 |
| 2023 | 116 440 | 275 134 458 | 20 |
| 2024 | 104 617 | 259 593 672 | 1 |
| 2025 | 96 034 | 233 461 398 | 3 |
| 2026 | 92 546 | 241 916 845 | 11 |
| **Total** | **1 151 055** | **3 032 966 265** | |

### Verificacion

`scripts/numeros_fuente.py` + comparacion numerica por anio: para 2023, 2024,
2025 y 2026 **no falta ningun numero** de factura de las fuentes. 2022 queda
completo salvo 11 numeros que chocaban con cabeceras huerfanas viejas (ya
recuperados) y 4 cabeceras de 2022 que tienen clave de Hacienda en vez de numero.

### Lo que no se pudo recuperar (no existe en ningun archivo)

- Detalle de **2013 y 2014** (el `DETAFACT` del respaldo 2019 esta vacio).
- Detalle de **2015 enero-septiembre** (el kardex solo trae oct-dic).
- Detalle de **2018** (el kardex de 2018 solo tiene 104 lineas) y **2019**.
- **2017**: no hubo ventas; la numeracion pasa de 1 309 912 (sep-2016) a
  1 309 913 (dic-2018) sin hueco.
- 2 claves de Hacienda sin XML.
- 3 446 cabeceras huerfanas preexistentes (sin comprobante ni resumen), que no
  afectan totales porque no tienen comprobante asociado.

## 8. Desde cuando existe el desglose por articulo

Se revisaron **todas** las fuentes (unidades E:, F:, C:, Z: y todas las tablas de
la base). El desglose por articulo no existe antes de agosto de 2015 en ningun
archivo: el sistema viejo guardaba solo la cabecera del tiquete.

| Archivo | Contenido | Desde | Hasta | Filas |
|---|---|---|---|---|
| `E:\VPALMERAS\facturasdiarias.dbf` | cabeceras | 2013-10-01 | 2019-04-13 | 500 720 |
| `E:\VPALMERAS\DETAFACT.DBF` | lineas | 2018-10-01 | 2018-10-01 | **43** |
| `E:\VPALMERAS\DETAFACT2.DBF` / `detafac2.DBF` | vacios | - | - | 0 |
| `E:\VPALMERAS\salidas.dbf` | lineas por articulo, **numero de factura enmascarado (`******`)** | 2015-08-14 | 2019-04-13 | 194 750 |
| `E:\VPALMERAS\kardex.DBF` | lineas con numero de factura | 2015-10-01 | 2019-04-13 | 386 566 |
| `E:\VPALMERAS\entradas.dbf` | compras | 2010-04-17 | 2016-09-08 | 24 698 |
| `E:\VPALMERAS\lineasborradas.dbf` | lineas borradas | 2009 | 2018 | 18 171 |
| `detafact_respaldo_respaldo.dbf` | lineas | 2022-01-01 | 2024-04 | 488 551 |
| `detafactbk27022026.DBF` | lineas | 2024-04 | 2026-02 | 325 036 |
| `DETAFACTMEDIOMARZO.DBF` | lineas | 2024-04 | 2024-04 | 10 526 |
| `detafact.dbf` (snapshot) | lineas | 2026-01 | 2026-10 | 123 999 |

Las tablas de la propia base confirman lo mismo: `ventas_dia` y
`profit_margin_history` empiezan el **2015-08**, no antes.

`lineasborradas` demuestra que las facturas de 2013-2014 **si estuvieron
itemizadas** en su momento (hay lineas borradas con numero de factura), pero el
`DETAFACT` que las contenia fue purgado: el respaldo de 2019 lo tiene con 43
registros. Ninguna copia disponible conserva ese archivo completo.

Si aparece un `DETAFACT.DBF` viejo (2013-2015, tipicamente decenas de MB), se
puede completar sin duplicar nada: `scripts/rellenar_lineas_faltantes.sql` usa la
clave (anio, numero de factura) y solo toca facturas con cero lineas.

Nota: `salidas.dbf` de `E:` si tiene el articulo, la fecha y la cantidad de
2015-08 a 2019, pero el numero de factura esta sobrescrito con `******` y no
trae precios (`PVENTA1`/`VENTAIMP` vacios), asi que no se puede ligar a una
factura concreta. Por eso no se uso para rellenar detalle.

## 9. Catalogo completo, CAByS y movimientos viejos

Ultima pasada: se importo **todo** lo que quedaba en los archivos viejos.

### Articulos

| Fuente | Filas | Que trae |
|---|---|---|
| `E:\VPALMERAS\Articulos.dbf` | 15 059 | catalogo completo (descripcion, precio, costo, familia, gravado) |
| `E:\VPALMERAS\maestro.DBF` | 6 424 | maestro activo en 2019 (stock, costos, ultima entrada) |
| `maestrocaby.DBF` (snapshot) | 53 317 | codigo de articulo -> codigo CAByS |

Union de las dos primeras: **20 026 codigos**, de los cuales **16 858 no existian**
en Mercurius. Importados:

- `articulos`: 5 996 -> **22 854**
- `articulostock`: 5 996 -> **22 854** (stock del maestro 2019; 0 si no habia dato)
- `articuloprecio`: 16 886 -> **26 262** (precio y costo del catalogo viejo)

Los **9 387** que seguian en el maestro de 2019 quedan `status = true` (aparecen en
el POS). Los **13 467** que solo estan en `Articulos.dbf` son codigos retirados:
quedan `status = false`, visibles en "inactivos" pero fuera del punto de venta.

### CAByS

`maestrocaby` trae 43 209 codigos y 1 783 CAByS distintos. **2 090 articulos**
recibieron su CAByS (solo se asignan codigos que existen en la tabla `cabys`, por
la FK). Quedan 15 073 sin CAByS: son casi todos articulos inactivos que nunca se
facturaron electronicamente.

### Movimientos

Al existir los articulos viejos, **26 296 lineas de kardex** que antes no se
podian ligar ya tienen articulo. Ademas se agregaron las fuentes que faltaban:

- `kardex.DBF` completo (2015-10 a 2019-04)
- `salidas.dbf` de E: solo donde el kardex no llega (2015-08/09, 2018-2019)
- `entradas.dbf` completo (2010-04 a 2016-09)

Resultado: **+142 189 movimientos historicos** y **+3 499 modernos**.
`inventario`: 824 594 -> **1 477 223**, ahora con movimientos desde **2010**.
Verificado: 0 movimientos sin articulo.

### Lo unico que quedo fuera a proposito

- `lineasborradas.dbf` (18 171 filas 2009-2018): **no son movimientos de
  inventario**. Es la bitacora de lineas que un cajero quito de una factura antes
  de cerrarla, con el supervisor que autorizo (`CODIGO, DOCUMENTO, FECHA,
  CANTIDAD, VENTA, CAJERO, SUPERVISOR`). El movimiento de inventario se genera al
  cerrar la factura, con las lineas que quedaron.

  Verificacion (2015-2016, que es donde hay kardex para comparar):
  * 6 652 lineas borradas; 4 903 tienen factura existente.
  * Solo **310 (4.6 %)** coinciden con una linea del kardex de esa factura, y en
    todos esos casos el articulo y la cantidad **siguen en la factura final**
    (se quito y se volvio a agregar, o era una linea repetida).
  * El total de la factura cuadra con la suma del kardex **sin** la linea
    borrada (ej.: factura 960 891, total 550, linea borrada de 600).
  * De 2 555 ajustes `AJ` del kardex, solo 23 coinciden por articulo+fecha con
    una linea borrada, así que las eliminaciones tampoco generan ajustes.

  Importarlas inflaria ventas y salidas de stock. Quedan disponibles en el DBF
  original si algun dia se necesitan como auditoria.
- 147 codigos CAByS del maestro viejo que no existen en la tabla `cabys`.
- `cur_precios` no se uso: ninguna parte de la app lo consulta.

### Totales finales

| Tabla | Antes de toda la migracion | Ahora |
|---|---|---|
| `articulos` | 5 996 | **22 854** |
| `comprobantesemitidos` | 751 869 | **1 151 055** |
| `comprobantesrecibidos` | 177 | **2 021** |
| `linea_detalle` | 569 106 | **1 297 560** |
| `inventario` | 673 326 | **1 477 223** |
