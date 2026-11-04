# Verificación manual de la UI — Mercurius

- **Fecha:** 2026-11-03
- **Versión verificada:** v2.33.42 (producción interna, ambiente Hacienda `sandbox`)
- **Método:** navegación real en `https://mercurius.tamarindorents.com/Mercurius` y por la conexión directa `http://192.168.100.99:8081/Mercurius`, abriendo cada pantalla y diálogo, ejecutando la acción y comprobando la respuesta HTTP y/o el estado en PostgreSQL. Los flujos con efectos externos (Hacienda, correo real) o destructivos se marcan como no ejecutados.
- **Alcance temporal:** algunas filas ✅ provienen de las rondas anteriores de la misma QA (emisión TE/FE, puntos, ajuste de precio en POS); no se repitieron en la última pasada para no duplicar trabajo ni generar datos. El resto se ejecutó en esta verificación.
- **Regla de la sesión:** no se envió ningún documento a Tributación.

## Leyenda

| Símbolo | Significado |
|---|---|
| ✅ | Ejecutado y verificado (UI + HTTP y/o BD) |
| 🟡 | Abierto/revisado, pero la acción no se ejecutó (dato de prueba o efecto irreversible) |
| ⛔ | No ejecutado: envía a Hacienda, usa credenciales reales o cambia el acceso |

## 1. Global / navegación

| Control | Estado | Evidencia |
|---|---|---|
| Login / logout | ✅ | sesión creada y cerrada |
| Menú de usuario (perfil, cerrar sesión) | ✅ | abre y navega |
| Toggle de tema claro/oscuro | ✅ | clase `theme-dark` aplicada |
| Sidebar: enlaces y grupos colapsables | ✅ | Gestión, Reportes, Hacienda, Usuarios, Ajustes, Correos |
| Ruta desconocida `/app/...` | ✅ | redirige al dashboard (antes página cruda) |
| Campos de fecha `dd/mm/aaaa` | ✅ | visible `04/10/2026` ↔ ISO `2026-10-04`; filtro envía ISO |
| Búsqueda del hub de Reportes + Limpiar | ✅ | filtra tarjetas y restaura |
| Tema oscuro en tablas/diálogos | ✅ | revisado visualmente |
| Barrido de enlaces internos | ✅ | 88 enlaces (dashboard/gestión/reportes) → 200; `/app` redirige al dashboard |
| Roles en la UI (usuario `facturacion`) | ✅ | dashboard/POS/emitidas/recibidas 200; Usuarios y Ajustes 403; sidebar reducido |
| POS en ventana (`/app/pos/standalone`) | ✅ | mismo POS sin layout; título y controles correctos |
| Redirecciones del proxy | 🟡 | por el dominio público el backend responde `Location: http://…` y Apache lo corrige (301); por la conexión directa `http://192.168.100.99:8081/Mercurius` todo responde correcto |

## 2. Dashboard

| Control | Estado | Evidencia |
|---|---|---|
| KPIs (hoy, ayer, semana, 30 días, ticket) | ✅ | admin ve todo el negocio: semana ₡2.251.668 / 30 días ₡22.319.115 |
| Gráfica “Ventas por hora” | ✅ | sin datos hoy (correcto); no rompe |
| Gráfica “Tendencia semanal” | ✅ | pinta datos de los últimos 7 días |
| “Última actividad” | ✅ | formato `Factura … — ₡650 a las 22:25` |
| “Productos más vendidos” | ✅ | unidades sin `.0` |
| Enlaces “Facturas Emitidas/Recibidas”, “Ver estado de Hacienda” | ✅ | navegan |

## 3. POS

| Control | Estado | Evidencia |
|---|---|---|
| Selector de comprobante (6 tipos) | ✅ | TE/FE/ND/FEC/FEE/REP |
| Aviso de cliente (opcional/requerido) | ✅ | cambia según tipo |
| Escanear código / Enter | ✅ | agrega línea |
| Buscar artículo (picker) | ✅ | buscador + Agregar |
| Cliente (picker) | ✅ | asigna cliente |
| Cantidad +/- y cantidad directa | ✅ | actualiza carrito |
| Quitar línea | ✅ | 200 |
| Ajustar precio (motivo obligatorio) | ✅ | solo rol `admin`; el resto ve el carrito sin el botón |
| Descuento a toda la factura (% + motivo) | ✅ | solo `admin`; 475 → 427,50 con 10%, retirado a 475; auditado en Reportes |
| Restaurar precio | ✅ | vuelve al del sistema |
| Puntos de lealtad (redimir/quitar) | ✅ | preview y descuento |
| Cancelar venta (confirmación) | ✅ | carrito a ₡0 |
| Cobro colones/dólares/vuelto | ✅ | cálculo y guardas |
| ND: referencia (consecutivo, fecha, tipo, código, motivo) | ✅ | campos requeridos; sin ellos 400 |
| REP: condición de venta 09/11 | ✅ | select y persistencia |
| FEC/FEE: notas y receptor | ✅ | avisos correctos |
| Emitir TE/FE | ✅ | documentos creados en pruebas anteriores |
| Emitir ND/FEC/REP desde el POS | ✅ | ND 00100001030000000001, FEC ...080000000001, REP ...100000000001 (soft-deleted) |
| Emitir FEE desde el POS | ✅ | 00100001050000000003 valida contra el XSD; solo falla la firma externa (sin servicio) |
| Efecto en inventario por tipo | ✅ | FEC suma; ND/REP/NC no mueven; ventas restan |
| Título del PDF por tipo | ✅ | Factura/Nota/Compra/Exportación/Recibo/Tiquete |

## 4. Facturación

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| **Emitidas**: lista, búsqueda, paginación | ✅ | 200 |
| Emitidas: drawer de detalle + refrescar | ✅ | 200 |
| Emitidas: “Eliminar factura” (soft delete) | 🟡 | botón visible; no se ejecutó sobre datos reales |
| Emitidas: pagar / procesar / aceptar / rechazar | ⛔/🟡 | “Procesar” envía a Hacienda; aceptar/rechazar son locales pero no se ejecutaron |
| **Recibidas**: lista, búsqueda, cubetas | ✅ | 200 |
| Recibidas: formulario de carga | ✅ | XML real v4.4 → “1 archivo procesado”; inválidos → error por archivo (sin escrituras) |
| Recibidas: “Revisar correo ahora” | ✅ | dispara el barrido IMAP al instante y muestra el resumen (v2.33.39) |
| Recibidas: drawer + prevalidación | ✅ | panel carga |
| Recibidas: procesar / activar-desactivar | ✅ | **Pagar** (paid=true), **Procesar** (creó artículo + movimiento; revertido) y **Desactivar/Activar** con bandeja “Inactivas” |
| Recibidas: Mensaje Receptor (aceptar/parcial/rechazar) | ⛔ | envía a Hacienda |
| **Recibos** (`/app/recibos`) | ✅ | redirige a facturas recibidas |
| **Devoluciones**: búsqueda por consecutivo | ✅ | factura encontrada |
| Devoluciones: seleccionar + total en vivo + motivo | ✅ | total ₡575,22 |
| Devoluciones: procesar/autorizar | ⛔ | genera NC y envía a Hacienda |

## 4 bis. Hacienda (solo lectura)

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| **Dashboard Hacienda** | ✅ | 13.013 emitidos, 99,9% éxito, 10 rechazados, pendientes 0 |
| **Consultas**: pestañas Pendientes/Aceptadas/Rechazadas/Mensajes | ✅ | 0 / 12.998 / 6 / 2.020; tablas con datos |
| Consultas: “Enviar Facturas Pendientes” | ✅ | **deshabilitado** sin pendientes (no se envió nada) |
| **Declaración D-104**: período y cálculo | ✅ | Octubre 2026: ventas ₡12.047.235,15 (5.201), IVA ₡1.389.073,60; compras 0 (no hay recibidas en el período) |

## 5. Compras

| Control | Estado | Evidencia |
|---|---|---|
| Crear orden (proveedor, fecha, líneas) | ✅ | buscador de artículos corregido (v2.33.21/.23/.24); OC-2026-00001 total ₡1.200 |
| Editar orden (notas) | ✅ | persistido |
| Cambiar estado BORRADOR→ENVIADA→CONFIRMADA | ✅ | transiciones y 409 en ilegales |
| Marcar como Recibida | ✅ | RECIBIDA + fecha real (corregido 415) |
| Cancelar orden con motivo | ✅ | CANCELADA + motivo en notas |
| Archivar orden | ✅ | `status=false`, desaparece del listado; orden QA borrada |

## 6. Inventario

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| Inventarios: pestañas y conteos | ✅ | 1.477.227 |
| Inventarios: Crear Ajuste (diálogo) | ✅ | creó ajuste manual +1 y aplicó stock; revertido y limpiado |
| Inventarios: Exportar PDF | ✅ | 200, 118 KB (tope 2.000 con aviso) |
| Inventarios: procesar/rechazar/reabrir/omitir | ✅/🟡 | procesó 809703 (+5 stock) y rechazó 809699; ambos revertidos por SQL |
| Alertas: tarjetas, filtros, tabla | ✅ | 4 stock bajo + 18 sobrestock = 22 |
| Alertas: exportar Excel | ✅ | 200 |
| Mermas: tarjetas, filtros, exportar | ✅ | “Movimiento de inventario” |

## 7. Reportes

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| Hub + búsqueda | ✅ | 19 tarjetas |
| Ventas por artículo / cajero | ✅ | 200 |
| Facturas emitidas (reporte) | ✅ | 200 |
| Cambios de carrito | ✅ | 200 + tildes |
| Tendencias | ✅ | 200 |
| Ventas por fecha histórica | ✅ | 22.541 filas |
| Rendimiento de productos | ✅ | 200 |
| Estacionalidad | ✅ | 461 filas |
| Movimientos por fecha | ✅ | 200 |
| Resumen de inventario | ✅ | 200 |
| Márgenes | ✅ | 200 |
| Histórico de precios | ✅ | 200 |
| Ranking de proveedores | ✅ | 66 proveedores, ₡970M (fallback por emisor) |
| Clientes | ✅ | 200 |
| Lealtad (reporte) | ✅ | 200 |
| Filtros de fecha + exportaciones | ✅ | fechas ISO correctas; export 200 |
| Ventas por departamento / familia | ✅ | 20 y 17 filas; enlazadas desde Ventas |
| Pronósticos de inventario | ✅ | 9.387 productos, 6.552 saludables; enlazado desde Alertas |
| Reportes por correo (solo lectura) | ✅ | 200; página sin enlace en el hub (solo por URL) |
| Recibos pendientes / vencidos (rutas viejas) | ✅ | redirigen a Facturas recibidas con la bandeja correcta |

## 8. Catálogo

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| **Artículos**: pestañas (5) | ✅ | conteos correctos |
| Crear artículo | ✅ | alta + precio + CAByS |
| Editar artículo | ✅ | prefill y guardado |
| Desactivar / Activar | ✅ | toggle y reversa (nuevo endpoint) |
| Procesar / Procesado rápido / Saltar | ✅ | artículo QA procesado (precio 1000→1413) y borrado; Saltar daba 415 (v2.33.15) |
| Promociones (crear/editar/borrar) | ✅ | crear/editar/borrar verificados; bugs corregidos en 2.33.13, .16 y .17 |
| Buscador CAByS dentro del artículo | ✅ | «arroz» → 10 resultados; selección llena el código |
| Guardar márgenes | ✅ | 200 (historial de prueba limpiado) |
| Exportar Excel | ✅ | 818 KB |
| **Categorías**: pestañas Familias/Departamentos | ✅ | 89/125 |
| Familias: crear/editar/desactivar | ✅ | verificado |
| Departamentos: crear/editar/desactivar | ✅ | verificado (QA limpiado) |
| Búsqueda de cada tabla | ✅ | usa el input correcto por panel |
| **CAByS**: búsqueda y edición | ✅ | modal + guardado |
| CAByS: importar/actualizar catálogo | ✅ | 20.505 códigos; por conexión directa (`:8081`) responde **200** (por el proxy corta con 502) |
| **Etiquetas**: seleccionar todos / limpiar / exportar PDF | ✅ | 15 marcados → 0; PDF 629 KB |
| Etiquetas: imprimir | ✅ | `window.print()` invocado |
| **Precios**: histórico y selector | ✅ | 200 |

## 8 bis. Correos

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| **Plantillas**: crear | ✅ | “QA Plantilla” creada (tipo Reportes) |
| Plantillas: cargar predeterminadas | ✅ | 4 plantillas HTML con estilos (factura, stock, reporte, general); idempotente |
| Plantillas: vista previa | ✅ | modal con datos de ejemplo (v2.33.39) |
| Plantillas: editar | ✅ | nombre cambiado a “QA Plantilla editada” |
| Plantillas: eliminar | ✅ | confirmación + desaparece del listado |
| Correos del sistema con estilo | ✅ | layout HTML de marca (cabecera, cuerpo, pie) en el envío con adjuntos |
| **Reportes programados**: crear | ✅ | “QA Reporte”, Semanal, MOVIMIENTOS, correo QA |
| Reportes programados: editar | ✅ | nombre actualizado |
| Reportes programados: eliminar | ✅ | confirmación + listado vacío |
| `/app/correos` (raíz) | ✅ | redirige a `/app/correos/reportes` (antes “Not Found” pelado, v2.33.28) |
| Envío real de correos | ⛔ | sin SMTP/IMAP configurados |

## 9. Personas y lealtad
| Control | Estado | Evidencia |
|---|---|---|
| **Clientes**: crear/editar/desactivar | ✅ | fecha dd/mm/aaaa; QA limpiado |
| Clientes: buscar nombre en Gometa | ✅ | cédula 104550334 → nombre aplicado (“Datos encontrados y aplicados”) |
| **Usuarios**: crear (rol), editar correo, desactivar | ✅ | QA limpiado |
| Usuarios: guardar permisos | ✅ | `[inventario, usuario]` persistido en BD |
| Usuarios: activar cuenta | ✅ | bug corregido (v2.33.14): el segundo DELETE reactiva |
| **Lealtad (gestión)**: guardar cashback/meses | ✅ | 1%/12 y restaurado |
| Lealtad: ver historial de cliente | ✅ | página Lealtad con cliente real: venta de ₡470,30 al 10% → 47,03 puntos; el modal muestra el movimiento |
| **Clientes (reporte)**: listado y exportar | ✅ | 200 |

## 10. Configuración del negocio

| Control | Estado | Evidencia |
|---|---|---|
| **Ajustes → Guardar ajustes** (identidad, actividad, correo, perfil tributación) | ✅ | 200 “Configuración guardada”; valores conservados; actividad inválida → 400 con mensaje |
| **Sucursales y terminales**: “En uso”, Desactivar/Activar | ✅ | desactivó y reactivó (confirmado en BD) |
| Registrar punto de venta | ✅ | 002/00002 registrado y usado; revertido a 001/00001 y fila borrada |
| **Correo y notificaciones**: SMTP/IMAP/notificaciones | ✅ | correo Gmail + contraseña de aplicación configurados desde la UI; **correo de prueba enviado** (SMTP real). IMAP queda a la espera del ciclo de 15 min |
| **Logo**: subir | ✅ | logo real de 249.754 bytes subido, servido por `/logo` (md5 idéntico), visible en login/sidebar/Ajustes; subida de prueba y restauración byte a byte verificadas (v2.33.40) |
| **Respaldos**: habilitar/hora/retensión/ruta | ✅ | 200; 03:00, 14 días |
| Respaldos: ejecutar ahora | ✅ | `.sql.gz` de 152 MB generado |
| Respaldos: historial | ✅ | fila con fecha y tamaño |
| **Lealtad** (solo lectura en Ajustes) | ✅ | valores mostrados |
| **Umbrales de stock**: enlace y página | ✅ | `/app/stock-alert-config` con layout |
| Umbrales: cargar artículo y guardar | ✅ | 200 + toast |
| **Tipo de cambio**: modal | ✅ | compra/venta/historial |
| Tipo de cambio: actualizar / guardar manual | ✅ | BCCR consultado 2 veces → **1 sola fila** del día (v2.33.27); tasa manual 435 restaurada |
| **Perfil**: formularios de correo y contraseña | ✅ | contraseña re-guardada (mismo valor) y login verificado; correo inválido rechazado (v2.33.26) |
| **Registro de actividades**: listar | ✅ | 142 archivos |
| Registro: descargar log | ✅ | 200, `text/plain`, attachment |
| **Caja**: abrir/cerrar sesión | ✅ | diferencia ₡0,00; QA limpiado |
| Caja: historial | ✅ | fila Cerrada |

## 11. Defectos encontrados y corregidos en esta verificación

| Versión | Defecto | Corrección |
|---|---|---|
| 2.31.0 | `Impuesto` de línea sin FK → XML sin `<Impuesto>` | se enlaza a `LineaDetalle` |
| 2.31.0 | Editar familia no guardaba | `updateAndDisable` corregido |
| 2.31.0 | DELETE daba 415 en varios módulos | `@Consumes(WILDCARD)` |
| 2.31.1 | Tarjetas de Gestión desalineadas | `.surface-grid` |
| 2.32.0 | Dashboard solo veía ventas propias | admin = todo el negocio |
| 2.32.0 | Export inventario reventaba (usuario null) | null-safe + tope PDF |
| 2.32.0 | CAByS sin columna/diálogo de edición | acción + modal |
| 2.32.0 | Clientes/Caja/Lealtad inalcanzables | rutas `/app/*` + enlaces |
| 2.32.0 | Artículo desactivado sin reversa | `PUT /activar` |
| 2.32.0 | POS rotulaba 03 como “Nota de crédito” | selector corregido |
| 2.32.0 | Sobrestock con umbral 0 (396 falsas) | respaldo 14 días + limpieza |
| 2.32.0 | Ranking de proveedores vacío | fallback por emisor |
| 2.32.0 | Respaldos nunca se ejecutaban | `BackupScheduler` + permisos BD |
| 2.33.0 | POS sin ND/FEC/FEE/REP | UI y validaciones |
| 2.33.0 | Fechas en formato del navegador | capa dd/mm/aaaa |
| 2.33.0 | 404 crudo | catch-all `/app` |
| 2.33.0 | Dirección de inventario igual para todo tipo | FEC suma; ND/REP/NC no mueven |
| 2.33.1 | Tarjetas de alertas contaban resueltas | solo activas |
| 2.33.2 | “Stock Óptimo 0” en sobrestock | se persiste el óptimo + “Umbral” |
| 2.33.3 | PDF siempre “TIQUETE ELECTRÓNICO” | título por tipo |
| 2.33.6 | Redirects a `/api/.../table` | vuelven a la página |
| 2.33.7 | Cierre de caja con diferencia = todo lo contado | esperados por medio de pago |
| 2.33.8 | DELETE reporte programado 415 | WILDCARD |
| 2.33.9 | Toggle reporte / recibir orden 415 | WILDCARD |
| 2.33.10 | Enlace de umbrales a `/api/...` | `/app/stock-alert-config` |
| 2.33.11 | “Marcar como Recibida” 415 (cuerpo JSON) | twin de formulario |
| 2.33.12 | Factura recibida desactivada desaparecía sin forma de reactivar | bandeja **Inactivas** |
| 2.33.13 | Crear/eliminar promoción no guardaba (transacción faltante) | `@Transactional` + no tragar errores |
| 2.33.14 | “Activar cuenta” volvía a desactivar | DELETE alterna `status` (`toggleStatus`) |
| 2.33.15 | “Saltar” en la revisión de artículos daba 415 | `@Consumes(WILDCARD)` + test HTMX |
| 2.33.16 | Modal “Editar promoción” daba 500 y quedaba vacío | `time:format` sobre `java.util.Date` |
| 2.33.17 | Editar promoción duplicaba los artículos | desvincular el lado dueño antes de reemplazar |
| 2.33.18 | REP emitía `NombreComercial` en el receptor (XSD inválido) | receptor REP mínimo |
| 2.33.18 | FE/ND/FEC/FEE sin `CodigoActividadReceptor` (XSD inválido) | fallback al emisor + test |
| 2.33.18 | Ajustes aceptaba actividad que no son 6 dígitos | validación `\d{6}` |
| 2.33.19 | Cualquiera con POS podía rebajar el precio de una línea | descuento/ajuste solo rol `admin` |
| 2.33.20 | No existía descuento sobre el total de la factura | % admin-only repartido por línea |
| 2.33.21 | Buscador de artículos en “Nueva Orden” no devolvía nada | `name="q"` en el input |
| 2.33.22 | FEE emitía `CodigoActividadReceptor` (XSD de exportación inválido) | el encabezado FEE no lo emite |
| 2.33.23 | Línea nueva de orden: el buscador no disparaba (clon sin `htmx.process`) | procesar la fila clonada |
| 2.33.24 | `window.htmx` no existía (build ESM no lo publica) | se expone el default export |
| 2.33.25 | FEE emitía totales exclusivos de FE (`TotalServExonerado`, `TotalIVADevuelto`…) | resumen FEE ajustado al XSD |
| 2.33.26 | Perfil aceptaba cualquier texto como correo | validación de formato |
| 2.33.27 | “Actualizar” tipo de cambio duplicaba la fila del día en cada clic | actualiza la fila de esa fecha |
| 2.33.28 | `/app/correos` devolvía “Not Found” pelado | redirige a `/app/correos/reportes` |
| 2.33.28 | plantilla huérfana `pages/stockalert-config/index.html` | eliminada (la página real es `pages/inventario/stock-alert-config`) |
| 2.33.29 | no había forma de guardar la contraseña del correo desde la UI | campo de solo escritura + “Enviar correo de prueba” |
| 2.33.30 | el correo principal era “solo lectura” (no se podía poner el Gmail) | campo editable con validación |
| 2.33.31 | `/app/reportes/recibos` devolvía “Not Found” pelado | redirige a Facturas recibidas (pendientes) |
| 2.33.32 | el barrido IMAP fallaba sin decir por qué (breaker abierto) | captura `RuntimeException` y la registra |
| 2.33.33 | `SettingsDirController.settingsService` sin `@Inject` → NPE en el barrido IMAP | `@Inject` + test de inyección |
| 2.33.34 | el cuerpo HTML de las plantillas no cabía (`VARCHAR(255)`) | columna `TEXT` + plantillas predeterminadas |
| 2.33.34 | los correos del sistema salían en texto plano | layout HTML de marca (texto + HTML alternativo) |
| 2.33.35 | no había forma de forzar la revisión del correo | botón “Revisar correo ahora” + endpoint |
| 2.33.35 | las plantillas no se podían previsualizar | modal de vista previa con datos de ejemplo |
| 2.33.43 | vista previa de plantillas mostraba el HTML escapado | `EmailLayout.wrapHtml` + iframe con sandbox |
| 2.33.44 | acciones de fila apiladas en Clientes (celda muy estrecha) | `white-space:nowrap` + `flex-wrap:nowrap` |
| 2.33.45 | toda la app usaba la hora UTC del servidor (6 h adelantada) | `TimeZone` CR al arrancar + `-Duser.timezone` |

## 12. No ejecutado (y por qué)

| Elemento | Motivo |
|---|---|
| Envío a Hacienda (facturar con envío, Mensaje Receptor, reemitir, enviar pendientes, autorizar devolución) | Prohibido enviar a Tributación en esta sesión |
| Certificado / clave API de Hacienda | Sin credenciales |
| IMAP (lectura de respuestas) | Configurado, pero el poll corre cada 15 min; no se esperó el ciclo |
| Promociones / ajuste de inventario / procesar artículos | Mutaciones de catálogo/stock no revertibles desde la UI |

## 13. Pendientes de esta lista (siguiente pasada)

- [x] Facturas recibidas: **Pagar** y **Activar/Desactivar** (con bandeja Inactivas, v2.33.12).
- [x] Promociones: crear, editar y eliminar (v2.33.13/.16/.17).
- [x] Inventario: crear ajuste y procesar/rechazar movimientos (revertidos).
- [x] Artículos: “Procesar” / “Procesado rápido” / “Saltar” (v2.33.15) y buscador CAByS.
- [x] Usuarios: guardar permisos y “Activar cuenta” (v2.33.14).
- [x] Sucursales: registrar un punto de venta QA, revertir y eliminarlo.
- [x] Tipo de cambio: tasa manual guardada y fila de historial eliminada.
- [x] Emitir ND, FEC, FEE y REP desde el POS (v2.33.18/.22 corrigen el XML); documentos de prueba soft-deleted y stock revertido.
- [x] Clientes: “Buscar nombre en Gometa” (rellenó el nombre con la cédula).
- [x] Lealtad: “Ver historial” de un cliente (estado vacío correcto).
- [x] Cabys: importar catálogo — 20.505 códigos (el proxy devuelve 502 al navegador aunque el servidor termina; la operación es idempotente).
- [x] Recibidas: “Procesar” — creó artículo y movimiento de inventario; revertido (factura 177).
- [x] Órdenes de compra: crear con buscador y **Archivar** (v2.33.21/.23/.24).
- [x] Perfil: contraseña re-guardada (mismo valor) + login verificado; correo inválido rechazado (v2.33.26).
- [x] **Ajustes: código de actividad corregido a `471102`.**
- [x] Lealtad: flujo completo (config 10% → cliente → venta → 47,03 puntos → historial), datos revertidos.
- [x] `/app/correos` y plantilla huérfana de umbrales corregidos (v2.33.28).
- [x] Correo real: Gmail configurado **desde Ajustes** (correo + contraseña de aplicación) y correo de prueba enviado.
- [x] Barrido automático de correos (IMAP): corregido el NPE (@Inject faltante) y el fallo del logo LOB (@Lob leído fuera de transacción); el ciclo vuelve a completar y hay botón **Revisar correo ahora** (v2.33.39/.38).
- [x] Logo único: GET /logo público sirve el logo subido o el de fábrica; sidebar y login dejan de usar un base64 fijo (v2.33.36/.39).
- [x] Plantillas: vista previa con el layout real del correo (cabecera, cuerpo, pie) y variables también en el asunto (v2.33.37).
- [x] Barrido IMAP verificado de punta a punta en producción: correo con XML adjunto → comprobante  0100001010000990021 creado; después se borraron comprobante, líneas, XML en disco y el correo. El planificador completa cada 15 min (19:15 → 21:00 sin fallos).
- [x] Plantillas predeterminadas: las 4 se crean desde la UI (200) y la vista previa devuelve el correo completo con marca y variables sustituidas (asunto Su factura 00100001010000000001).
- [x] Logo: subida desde Ajustes probada con una imagen distinta (el endpoint público cambia al instante) y restaurado el logo original; login/sidebar/Ajustes apuntan todos a /logo.
- [ ] **Nota operativa:** la columna EmailTemplate.cuerpoHtml era VARCHAR(255); Hibernate update no cambia tipos, así que en bases existentes hay que ejecutar una vez ALTER TABLE emailtemplate ALTER COLUMN cuerpohtml TYPE TEXT; (ya aplicado en producción). Las instalaciones nuevas la crean como TEXT.
- [x] Ciclo automático verificado tras el arreglo: 21:30 completado y **0 ERROR** en el log desde el despliegue de 2.33.42.
- [x] Logging: se eliminó el 	oString() de Usuarios (incluía hash/sal de la contraseña) de 57 logs en 10 recursos; ahora se registra solo el username (Utils.LogUsers). Verificado en el barrido: user=Admin.
## 12. Sesión de navegador (2026-11-04, noche)

Navegación real con Chromium sobre `http://192.168.100.99:8081/Mercurius`, login `Admin`, ventana 1280x720.

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| Login con el logo subido | ✅ | palmera servida por `/logo` (no el base64 viejo) |
| Sidebar (logo 26px) | ✅ | mismo logo; marca "Mercurius" al lado |
| Dashboard (claro y oscuro) | ✅ | métricas, gráficos y conmutador de tema |
| POS standalone: carrito, cliente, descuentos, puntos | ✅ | artículo agregado, cliente TAMARA ROSSI asignado, controles de puntos y descuento visibles |
| POS: selector de cliente con búsqueda | ✅ | `TAMARA` filtra a una sola fila (`keyup delay:250ms`) |
| Facturas emitidas + detalle lateral | ✅ | totales, estados y acciones |
| Facturas recibidas (carga + "Revisar correo ahora") | ✅ | botón y ayuda visibles |
| Inventarios + diálogo Crear Ajuste (claro/oscuro) | ✅ | formulario completo |
| Artículos, Categorías, Órdenes, Etiquetas, CAByS | ✅ | tablas y acciones |
| Clientes | ✅ (2.33.44) | acciones de fila horizontales; antes se apilaban |
| Caja, Usuarios, Perfil, Lealtad, Tipo de cambio | ✅ | sin incidencias |
| Reportes + Cambios de carrito (auditoría) | ✅ | muestra precios ajustados, descuentos y motivos reales |
| Alertas, Mermas, Configuración de alertas | ✅ | métricas y tablas |
| Respaldos, Registro de actividades | ✅ | estado, historial y descarga |
| Devoluciones, Consultas Hacienda | ✅ | formularios y tablas |
| Plantillas: vista previa | ✅ (2.33.43) | HTML renderizado en iframe con `sandbox` (antes salía escapado) |

**Defectos encontrados en esta sesión (corregidos):**
- la vista previa de plantillas mostraba las etiquetas HTML como texto → `wrapHtml` + iframe con `sandbox`.
- las acciones de fila de Clientes salían en dos líneas (celda de ~85 px) → `white-space:nowrap` + `flex-wrap:nowrap`.

**Ampliación del barrido:**
- Recibidas: detalle lateral con clave, emisor/receptor, totales y pre-validación (6 advertencias) ✅
- Usuarios: diálogo "Crear Usuario" con módulos/permisos ✅
- Clientes: diálogo "Crear Cliente" con búsqueda en Gometa ✅
- D-104 (`/app/tributacion/declaracion`): resumen del período y del año ✅
- Reportes con datos: Márgenes (28,5% margen), Estacionalidad (gráficos), Pronósticos (MASE/sesgo), Facturas emitidas ✅
- Tema oscuro: dashboard, tabla de artículos y diálogo de ajuste con buen contraste ✅
- POS: selector de cliente filtra por nombre y asigna el cliente al carrito (con puntos y descuentos visibles) ✅

**Hallazgo importante (hora del servidor):** el servidor corre en **UTC** y la app usaba `LocalDateTime.now()`, así que todas las horas visibles (y la `FechaEmision` de los comprobantes) salían **6 horas adelantadas** respecto a Costa Rica (21:5x vs 15:5x). En Hacienda eso puede rechazar por fecha de emisión futura.
- Arreglo 2.33.45: `TimeZone.setDefault(America/Costa_Rica)` al arrancar + `-Duser.timezone=America/Costa_Rica` en `mercurius-start.sh`. Verificado: el log pasó de 21:56 a 15:56 sin tocar el reloj del sistema (que sigue en UTC).
- Los registros anteriores conservan su hora (columnas `timestamp` sin zona); los nuevos se guardan en hora de Costa Rica.

- **Prueba del arreglo:** tras reiniciar con `-Duser.timezone=America/Costa_Rica`, una eliminación de línea en el POS quedó registrada a las **15:58:50** (hora de Costa Rica), mientras los registros viejos del mismo día seguían en 05:38 (UTC). El reloj del sistema no se tocó (sigue en UTC). El registro de prueba se borró.

- **Reloj del sistema:** además del JVM, se cambió la zona del servidor con `timedatectl set-timezone America/Costa_Rica` (antes `Etc/UTC`). Ahora `date`, los logs del sistema y la app coinciden con la hora local (`CST, -0600`); el instante UTC no se alteró.

- **Columna TEXT en producción:** se creó una plantilla de prueba con 453 caracteres de HTML (`201`) y se eliminó (`200`), confirmando que el `ALTER TABLE … TYPE TEXT` resolvió el límite de `VARCHAR(255)`.
