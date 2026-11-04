# Verificación manual de la UI — Mercurius

- **Fecha:** 2026-11-03
- **Versión verificada:** v2.33.11 (producción interna, ambiente Hacienda `sandbox`)
- **Método:** navegación real en `https://mercurius.tamarindorents.com/Mercurius`, abriendo cada pantalla y diálogo, ejecutando la acción y comprobando la respuesta HTTP y/o el estado en PostgreSQL. Los flujos con efectos externos (Hacienda, correo real) o destructivos se marcan como no ejecutados.
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
| Ajustar precio (motivo obligatorio) | ✅ | aplica y audita |
| Restaurar precio | ✅ | vuelve al del sistema |
| Puntos de lealtad (redimir/quitar) | ✅ | preview y descuento |
| Cancelar venta (confirmación) | ✅ | carrito a ₡0 |
| Cobro colones/dólares/vuelto | ✅ | cálculo y guardas |
| ND: referencia (consecutivo, fecha, tipo, código, motivo) | ✅ | campos requeridos; sin ellos 400 |
| REP: condición de venta 09/11 | ✅ | select y persistencia |
| FEC/FEE: notas y receptor | ✅ | avisos correctos |
| Emitir TE/FE | ✅ | documentos creados en pruebas anteriores |
| Emitir ND/FEC/FEE/REP | ✅ | por API/tests; PDF y XML válidos |
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
| Recibidas: formulario de carga | 🟡 | revisado; no se subió XML |
| Recibidas: drawer + prevalidación | ✅ | panel carga |
| Recibidas: procesar / activar-desactivar | ✅ | **Pagar** verificado (paid=true) y **Desactivar/Activar** con la nueva bandeja “Inactivas” (bug corregido) |
| Recibidas: Mensaje Receptor (aceptar/parcial/rechazar) | ⛔ | envía a Hacienda |
| **Recibos** (`/app/recibos`) | ✅ | redirige a facturas recibidas |
| **Devoluciones**: búsqueda por consecutivo | ✅ | factura encontrada |
| Devoluciones: seleccionar + total en vivo + motivo | ✅ | total ₡575,22 |
| Devoluciones: procesar/autorizar | ⛔ | genera NC y envía a Hacienda |

## 5. Compras

| Control | Estado | Evidencia |
|---|---|---|
| Crear orden (proveedor, fecha, líneas) | ✅ | OC-2026-00001/2, BORRADOR, total correcto |
| Editar orden (notas) | ✅ | persistido |
| Cambiar estado BORRADOR→ENVIADA→CONFIRMADA | ✅ | transiciones y 409 en ilegales |
| Marcar como Recibida | ✅ | RECIBIDA + fecha real (corregido 415) |
| Cancelar orden con motivo | ✅ | CANCELADA + motivo en notas |
| Archivar orden | 🟡 | visible en estados activos; no se ejecutó |

## 6. Inventario

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| Inventarios: pestañas y conteos | ✅ | 1.477.227 |
| Inventarios: Crear Ajuste (diálogo) | ✅ | creó ajuste manual +1 y aplicó stock; revertido y limpiado |
| Inventarios: Exportar PDF | ✅ | 200, 118 KB (tope 2.000 con aviso) |
| Inventarios: procesar/rechazar/reabrir/omitir | 🟡 | no ejecutados (mutan stock) |
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

## 8. Catálogo

| Pantalla / control | Estado | Evidencia |
|---|---|---|
| **Artículos**: pestañas (5) | ✅ | conteos correctos |
| Crear artículo | ✅ | alta + precio + CAByS |
| Editar artículo | ✅ | prefill y guardado |
| Desactivar / Activar | ✅ | toggle y reversa (nuevo endpoint) |
| Procesar / Procesado rápido / Saltar | ✅ | artículo QA procesado (precio 1000→1413) y borrado; Saltar daba 415 (v2.33.15) |
| Promociones (crear/editar/borrar) | ✅/🟡 | crear y borrar verificados (bug de transacción corregido, v2.33.13); editar no ejecutado |
| Buscador CAByS dentro del artículo | ✅ | «arroz» → 10 resultados; selección llena el código |
| Guardar márgenes | ✅ | 200 (historial de prueba limpiado) |
| Exportar Excel | ✅ | 818 KB |
| **Categorías**: pestañas Familias/Departamentos | ✅ | 89/125 |
| Familias: crear/editar/desactivar | ✅ | verificado |
| Departamentos: crear/editar/desactivar | ✅ | verificado (QA limpiado) |
| Búsqueda de cada tabla | ✅ | usa el input correcto por panel |
| **CAByS**: búsqueda y edición | ✅ | modal + guardado |
| CAByS: importar/actualizar catálogo | ⛔ | descarga de Hacienda (largo, externo) |
| **Etiquetas**: seleccionar todos / limpiar / exportar PDF | ✅ | 15 marcados → 0; PDF 629 KB |
| Etiquetas: imprimir | ✅ | `window.print()` invocado |
| **Precios**: histórico y selector | ✅ | 200 |

## 9. Personas y lealtad

| Control | Estado | Evidencia |
|---|---|---|
| **Clientes**: crear/editar/desactivar | ✅ | fecha dd/mm/aaaa; QA limpiado |
| Clientes: buscar nombre en Gometa | 🟡 | no ejecutado |
| **Usuarios**: crear (rol), editar correo, desactivar | ✅ | QA limpiado |
| Usuarios: guardar permisos | ✅ | `[inventario, usuario]` persistido en BD |
| Usuarios: activar cuenta | ✅ | bug corregido (v2.33.14): el segundo DELETE reactiva |
| **Lealtad (gestión)**: guardar cashback/meses | ✅ | 1%/12 y restaurado |
| Lealtad: ver historial de cliente | 🟡 | no ejecutado |
| **Clientes (reporte)**: listado y exportar | ✅ | 200 |

## 10. Configuración del negocio

| Control | Estado | Evidencia |
|---|---|---|
| **Ajustes → Guardar ajustes** (identidad, actividad, correo, perfil tributación) | ✅ | 200 “Configuración guardada”; valores conservados |
| **Sucursales y terminales**: “En uso”, Desactivar/Activar | ✅ | desactivó y reactivó (confirmado en BD) |
| Registrar punto de venta | ✅ | 002/00002 registrado y usado; revertido a 001/00001 y fila borrada |
| **Correo y notificaciones**: SMTP/IMAP/notificaciones | 🟡 | formulario guarda; sin credenciales no hay envío real |
| **Logo**: subir | ✅ | PNG de prueba subido (oid + image/png) y restaurado |
| **Respaldos**: habilitar/hora/retensión/ruta | ✅ | 200; 03:00, 14 días |
| Respaldos: ejecutar ahora | ✅ | `.sql.gz` de 152 MB generado |
| Respaldos: historial | ✅ | fila con fecha y tamaño |
| **Lealtad** (solo lectura en Ajustes) | ✅ | valores mostrados |
| **Umbrales de stock**: enlace y página | ✅ | `/app/stock-alert-config` con layout |
| Umbrales: cargar artículo y guardar | ✅ | 200 + toast |
| **Tipo de cambio**: modal | ✅ | compra/venta/historial |
| Tipo de cambio: actualizar / guardar manual | ⛔/✅ | API externa no probada; tasa manual guardada (histórico revertido) |
| **Perfil**: formularios de correo y contraseña | ⛔ | cambiaría las credenciales de acceso |
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

## 12. No ejecutado (y por qué)

| Elemento | Motivo |
|---|---|
| Envío a Hacienda (facturar con envío, Mensaje Receptor, reemitir, enviar pendientes, autorizar devolución) | Prohibido enviar a Tributación en esta sesión |
| Correo real (SMTP/IMAP) | Sin credenciales configuradas |
| Certificado / clave API de Hacienda | Sin credenciales |
| Subir XML de facturas recibidas | No se usó un archivo real |
| Procesar factura recibida | Crea artículos y movimientos definitivos |
| Promociones / ajuste de inventario / procesar artículos | Mutaciones de catálogo/stock no revertibles desde la UI |
| Tipo de cambio: actualizar | Llama a la API de Hacienda/BCCR |
| Perfil: correo/contraseña | Cambiaría el acceso |
| Archivar orden | Botón disponible solo en estados activos; no se ejecutó |

## 13. Pendientes de esta lista (siguiente pasada)

- [x] Facturas recibidas: **Pagar** y **Activar/Desactivar** (con bandeja Inactivas, v2.33.12).
- [x] Promociones: crear y eliminar (v2.33.13); editar pendiente.
- [x] Inventario: crear ajuste (aplica stock); pendiente aprobar/rechazar/reabrir.
- [x] Artículos: “Procesar” / “Procesado rápido” / “Saltar” (v2.33.15) y buscador CAByS.
- [x] Usuarios: guardar permisos y “Activar cuenta” (v2.33.14).
- [x] Sucursales: registrar un punto de venta QA, revertir y eliminarlo.
- [x] Tipo de cambio: tasa manual guardada y fila de historial eliminada.
- [ ] Cabys: importar catálogo en ventana controlada (llamada externa larga).
