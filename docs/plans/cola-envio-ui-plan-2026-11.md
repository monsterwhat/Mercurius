# Plan: pantalla "Cola de envío a Hacienda" (outbox Art. 21 ¶3)

**Fecha:** 2026-11-13
**Ámbito:** una pantalla nueva (`/app/tributacion/cola-envio`) + los endpoints que la alimentan. No se cambia la lógica del outbox.
**Stack:** Quarkus 3.x + Qute + HTMX, on-premise, sin WebSocket.
**Roles:** `admin`, `tributacion` (mismo gate que el resto de `@Path("/app/tributacion")`).

---

## 0. Resumen ejecutivo

Hoy el outbox de `EnvioFueraLinea` es **infraestructura sin superficie**: el operador no puede ver cuántos documentos esperan, cuándo vencen, por qué falló el último intento, ni cuáles ya incumplieron el plazo legal de dos días hábiles. Esa información solo existe en la tabla `envio_fuera_linea` y en líneas de log. La pantalla propuesta la expone y añade dos acciones acotadas ("Reintentar ahora" por fila y "Reintentar el lote") bajo reglas de seguridad explícitas.

### 0.1 Las cinco decisiones que gobiernan el diseño

1. **Se mantiene polling HTMX cada 30 s; no se consumen webhooks de Fides.** Mercurius es on-premise y no garantiza ser alcanzable desde Fides (URL pública, TLS, firewall), y el outbox **bypasea Fides a propósito** porque Fides firma sus propios documentos mientras que Art. 21 ¶3 exige reenviar el XML firmado en la venta. Los eventos `document.*` de Fides no describen estas filas.
2. **Ningún sondeo de conectividad automático en la pantalla.** `hayConectividadConHacienda()` es `synchronized` y hace un `connect()` de hasta 2,5 s; la ruta de venta lo llama **dentro** de la transacción que retiene el bloqueo pesimista del consecutivo. Un poll cada 30 s sería un poll cada 60 s de TTL real y serializaría ventas detrás de un socket. El KPI de conexión se pide **solo por acción explícita del operador**.
3. **"Reintentar ahora" solo se habilita cuando el reintento es realmente ejecutable**: fila `PENDIENTE`, `proximoIntento <= ahora`, `vencimiento > ahora`. Si está fuera de plazo, el servidor **no toca la red**: la fila pasa a `VENCIDO` (`reintentar` ya lo hace así), de modo que la UI no ofrece un botón cuyo efecto sería escalar en silencio.
4. **"Reintentar todos" se publica solo junto con un candado de lote.** El job (`@Scheduled every 15m`, `SKIP`) y un POST concurrente pueden cargar las mismas filas `PENDIENTE` y enviarlas dos veces: `SKIP` impide solapar ejecuciones del scheduler, no scheduler contra HTTP. Se resuelve con un `ReentrantLock.tryLock()` compartido por ambas entradas y un límite de lote para UI; si el candado no se implementa, **el botón no se publica**.
5. **Esta pantalla no se mezcla con `ComprobantesEmitidos`.** "Pendiente" aquí es "firmado y sin transmitir", no `haciendaEstado = PENDIENTE`. Los KPI y tablas salen exclusivamente de `EnvioFueraLinea`; el dashboard y consultas existentes no se tocan.

---

## 1. Estado actual verificado

### 1.1 Cuándo se crea una fila

Solo hay dos puntos de entrada, ambos a `EnvioFueraLineaService.registrarDocumentoFirmado(...)` (`Services/EnvioFueraLineaService.java:287-352`):

| Origen | Call site | Condición | `situacion` en la clave |
|---|---|---|---|
| `OFFLINE_SITUACION_3` | `ComprobanteService.enviarComprobanteCreado()` — `Services/ComprobanteService.java:734-749` | el sondeo de la fase (a) dio `false` (`hayConectividad == false`, decidido en `crearComprobante` líneas 421-424) | `"3"` |
| `FALLO_ENVIO_INMEDIATO` | mismo método, líneas 776-784 (rechazo de fondo excluido: se detecta en 760-769 y **no** se reencola) | Hacienda alcanzable pero el envío inmediato falló o lanzó | `"1"` si el sondeo fue positivo |
| `FALLO_ENVIO_INMEDIATO` | `DevolucionesResource.registrarPendienteDeEnvioNc()` — `Controllers/Api/App/DevolucionesResource.java:1226-1261` | NC de una devolución: firma y encola siempre por este camino | el de la NC |

Propiedades de la escritura (`registrarDocumentoFirmado`):

- **Idempotente por `clave`**: si ya existe fila para la clave devuelve la existente sin insertar (`:304-310`); la tabla tiene `uniqueConstraints = @UniqueConstraint(columnNames = {"clave"})` (`Models/EnvioFueraLinea.java:47-50`).
- Sin `clave` no hay fila: `LOG.warn` y `null` (`:296-302`).
- El XML XAdES se guarda **dos veces**: `xmlFirmado` (texto, auditable) y `xmlFirmadoBytes` (bytes UTF-8, los que se transmiten) — `Models/EnvioFueraLinea.java:106-124`.
- Se congela el sobre emisor/receptor (`congelarEmisorReceptor`, `:360-390`) porque esos segmentos están dentro del XML firmado.
- `intentos = 0`, `estado = PENDIENTE`, `fechaCreacion = now`, `proximoIntento = now`.

### 1.2 Cómo se calculan `vencimiento` y `proximoIntento`

- `vencimiento = calcularVencimiento(fechaEmision)` (`:156-169`): `PLAZO_DIAS_HABILES = 2` (`:89`) días **lun-vie**, conservando la hora del día. Siempre estrictamente posterior a la emisión. Se **persiste** (`:333`) para que un cambio de reloj o de configuración no extienda un plazo ya comunicado al operador (documentado en la entidad, `:169-176`).
- No hay calendario de feriados: el vencimiento es una **cota superior** y puede caer un día antes en semana con feriado. Es la dirección segura (transmitir antes, nunca después).
- `proximoIntento = ahora` al encolar (`:332`): la primera pasada del job puede tomarla de inmediato.
- Tras un fallo: `calcularProximoIntento(ahora, intentos, vencimiento)` (`:179-191`), backoff `15m → 1h → 4h → 8h → 12h`, luego plano 12h, **recortado al vencimiento** (`siguiente.isBefore(vencimiento) ? siguiente : vencimiento`).

### 1.3 Qué pasa exactamente al llegar a `MAX_INTENTOS`

`MAX_INTENTOS = 8` (`:96`). En `registrarIntentoFallido` (`:539-570`) hay **dos disparadores independientes** de escalado:

1. `intentos >= MAX_INTENTOS` → "Se agotaron los 8 intentos sin confirmación de Hacienda".
2. `sinTiempo`: `calcularProximoIntento(ahora, intentos, vencimiento)` no cae después de `ahora` → no cabe otro reintento dentro del plazo legal.

Ambos llaman `escalarVencido` (`:577-598`): `estado = VENCIDO`, `proximoIntento = null`, `ultimoError = motivo`, `LOG.error` con clave/tipo/consecutivo/situación/origen y correo a `ConfiguracionAplicacion.correoNotificaciones` (`notificarVencimiento`, `:636-666`, ignora el flag `notificarRechazos` porque es un incumplimiento legal).

Además, en `reintentar` el **vencimiento se evalúa antes de la red** (`:473-480`): una fila vencida se escala sin intentar transmitir. Un documento fuera de plazo se reporta como incumplimiento, no se envía en silencio.

Estados terminales: `ENVIADO`, `RECHAZADO`, `VENCIDO` (`:466-471`) devuelven `omitido = true` y nunca se reintentan.

> **`RECHAZADO` no lo escribe el outbox.** El estado existe y es terminal, pero ningún camino de `EnvioFueraLineaService` lo asigna: un rechazo de Hacienda se detecta antes de encolar (`ComprobanteService:760-769`) y se queda en `ComprobantesEmitidos`/`Encabezado`. La pestaña "Rechazados" de esta pantalla está, por tanto, casi siempre vacía; se incluye por completitud del modelo de estados y **no debe presentarse como "rechazados por Hacienda"** (ver §3.5).

### 1.4 Qué es "contingencia" aquí — y qué no

**Sí es:** `Situacion = "3"` en la posición 42 de la clave de 50 caracteres, Art. 21 ¶3 de la Ley 6828: el documento se generó y firmó en el momento de la venta, no pudo transmitirse por falta de conectividad y debe transmitirse a más tardar en dos días hábiles. El payload legal es el XML firmado en la venta, reenviado byte a byte (por eso `xmlFirmadoBytes`).

**No es:**
- ni un modo "contingencia" genérico de Hacienda (no existe en este código);
- ni un estado de `ComprobantesEmitidos`;
- ni un sinónimo de "rechazado" (un rechazado **llegó** a Hacienda; un vencido **nunca llegó** a tiempo);
- ni el estado `ORIGEN_FALLO_ENVIO_INMEDIATO` (ese es el motivo de encolado, no la situación fiscal).

La UI debe nombrar la columna como "Situación" con chip `3` y tooltip legal, nunca como "contingencia" a secas en el título de la página.

### 1.5 Qué información del outbox no está en ninguna pantalla

No existe pantalla de `EnvioFueraLinea`. El dashboard y consultas (`Controllers/Api/App/Reportes/TributacionPagesResource.java`) cuentan y listan por `ComprobantesEmitidos.haciendaEstado`; los KPI "pendientes" y "rechazados" de hoy **no** son esta cola. Sin exponer: `intentos`, `proximoIntento`, `vencimiento`, `ultimoError`, `origen`, `situacion`, `fechaEmision`, `fechaUltimoIntento`, `fechaEnvio`, `fechaCreacion`, `comprobanteId`, `sucursal`/`terminal`, y la existencia misma de filas `VENCIDO`.

### 1.6 Contrato de servicio disponible

Ya existen y se reutilizan tal cual:

| Método | Uso en la pantalla |
|---|---|
| `findPendientes()` (`:713-726`) | no se usa: hidrata todas las pendientes sin tope |
| `findByEstado(estado, limite)` | pendientes/vencidos/rechazados con tope |
| `contarPorEstado(estado)` | KPI pendientes/rechazados |
| `contarVencidos()` (`:729-740`) | KPI vencidos (reutilizar este, no `contarPorEstado`) |
| `reintentar(registro)` (`:442-444`) | acción por fila |
| `procesarPendientes()` (`:407-410`) | lote (scheduler) |
| `find(id)` (heredado de `GService`, `Services/GService.java:195`) | carga por id en el POST por fila |
| `hayConectividadConHacienda()` (`:218-252`) | KPI de conexión, solo bajo acción explícita |
| `buscarPorClave(clave)` (`:673-686`) | no se usa en esta entrega |

**Fase 0 obligatoria (30 min):** confirmar en el build las firmas exactas de `findByEstado(String, int)` y `contarPorEstado(String)` y su `ORDER BY` antes de escribir la UI. Si el build no las expone, añadirlas con el patrón de la casa (try/catch `PersistenceException` + `LOG.warn` + retorno vacío) y estas queries:

```java
// findByEstado: tope obligatorio, orden estable por vencimiento
"SELECT e FROM EnvioFueraLinea e WHERE e.estado = :estado "
+ "ORDER BY e.vencimiento ASC, e.proximoIntento ASC"
// setMaxResults(limite)

// contarPorEstado
"SELECT COUNT(e) FROM EnvioFueraLinea e WHERE e.estado = :estado"
```

Se añaden **dos** métodos nuevos, ambos aditivos y sin tocar la lógica existente:

```java
/** Enviados con fechaEnvio >= desde. Para el KPI "enviados hoy" sin hidratar filas. */
public long contarEnviadosDesde(@Nonnull LocalDateTime desde)

/** Variante acotada del lote para una acción de UI; el job sigue usando la de LOTE_MAXIMO. */
public int procesarPendientes(@Nonnull LocalDateTime ahora, int limite)
```

`procesarPendientes()` pasa a delegar en `procesarPendientes(ahora, LOTE_MAXIMO)` sin cambiar su comportamiento observable.

### 1.7 Webhooks de Fides: decisión y por qué

**No se consumen. Se mantiene el polling.**

1. **Alcanzabilidad.** Mercurius es on-premise; un webhook exige que Fides inicie una conexión a Mercurius (URL pública o VPN, certificado válido, firewall, puerto abierto). No es garantizable y no puede ser un requisito de una pantalla de consulta.
2. **El outbox no pasa por Fides.** El Javadoc de `EnvioFueraLineaService` (líneas 54-59) lo dice: "The Fides provider is bypassed for these rows by design: Fides signs documents itself, and Art. 21 ¶3 requires transmitting *the document signed at the sale*, which Fides cannot do." El reintento llama `HaciendaApiService.submitAndWait` directamente (`:503-506`), no `HaciendaServiceFacade.submitDocument` (`Services/HaciendaServiceFacade.java:120-126`). Los eventos `document.accepted|rejected|submitted|cancelled|validation.failed` de Fides describen documentos que Fides firmó; **no** describen estas filas.
3. **Ya hay un canal de verdad.** `submitAndWait` devuelve el resultado de forma sincrónica en cada reintento, y el job barre la cola cada 15 min. El estado no se "empuja": se consulta.
4. **Coste de seguridad sin beneficio.** Habría que añadir un endpoint público con HMAC-SHA256 y anti-replay (`x-webhook-nonce`, `x-webhook-timestamp`) a una aplicación que hoy no tiene ninguno.

Consecuencia de diseño: la pantalla **no** promete tiempo real. El poll es de 30 s y el dato cambia como máximo cada 15 min (o cuando el operador reintenta). Se muestra la antigüedad del dato con `data-poll-age` (ver §4.5).

---

## 2. Diseño de la pantalla

### 2.1 Ruta, rol y navegación

- **Ruta nueva:** `GET /app/tributacion/cola-envio`.
- **Roles:** `@RolesAllowed({"admin", "tributacion"})` — idéntico a `TributacionPagesResource` (`:68`) y a `TributacionResource` (`:121`).
- **Navbar:** entrada "Cola de envío" bajo el bloque Hacienda de `fragments/app-sidebar.html` (líneas 80-92, gate `hasRole('tributacion')`), con `data-planned-route="/app/tributacion/cola-envio"`. Es un cambio de una línea en un template; se ejecuta en la fase 2 y no bloquea las demás.
- **Nombre visible:** "Cola de envío a Hacienda". El subtítulo dice el plazo: "Documentos firmados pendientes de transmitir · Art. 21 ¶3, dos días hábiles".

### 2.2 KPIs

Fila superior con `_kit/metric`. Cinco tarjetas, todas pasivas (`<div class="metric-card">`), sin `href`:

| KPI | Etiqueta | Fuente exacta | Detalle |
|---|---|---|---|
| Pendientes | `PENDIENTES` | `contarPorEstado("PENDIENTE")` | `detail`: "{n} vencen hoy" si aplica, si no "en plazo" |
| Vencidos | `VENCIDOS` | `contarVencidos()` | `detail`: "requieren envío manual" |
| Rechazados en cola | `RECHAZADOS` | `contarPorEstado("RECHAZADO")` | `detail`: "estado no emitido por el outbox" (ver §1.3) |
| Enviados hoy | `ENVIADOS HOY` | `contarEnviadosDesde(LocalDate.now().atStartOfDay())` | `detail`: "desde 00:00 (reloj del servidor)" |
| Intentos máx. | `TOPE DE INTENTOS` | constante `MAX_INTENTOS` | `detail`: "el lote corre cada 15 min" |

> **Reloj de "hoy".** `fechaEnvio` se escribe con `LocalDateTime.now()` (reloj del host). El KPI usa el día del **mismo reloj** (`LocalDate.now()`), no `America/Costa_Rica`: mezclar zonas desplazaría la ventana y mostraría cero a primera hora. Si el host corre en UTC, la ventana es el día UTC; se indica en el `detail` ("reloj del servidor"). Alternativa aceptada si el equipo prefiere evitar la ambigüedad: rotular el KPI "Enviados (24 h)" y contar desde `LocalDateTime.now().minusHours(24)`.

**Conexión (KPI separado, bajo demanda).** No forma parte de la fila de métricas automática. Se renderiza en su propia `_kit/surface` "Conectividad con Hacienda" con un chip y un botón "Verificar conexión":

| Estado | Chip | Texto |
|---|---|---|
| No verificado (inicial) | `is-info` | "Sin verificar. La verificación hace una conexión TCP a Hacienda (hasta 2,5 s)." |
| `true` | `is-success` | "Alcanzable (verificado a las HH:mm)" |
| `false` | `is-danger` | "No alcanzable: las ventas nuevas se emitirán con Situación 3." |

Reglas:
- El `GET` de la página **no** llama al probe. Solo `hx-get="/app/tributacion/cola-envio/conexion"` (botón explícito) lo hace.
- El resultado **no** se cachea en el modelo de la página: es una foto de ese instante. El TTL interno de 60 s ya evita ráfagas.
- Motivo (documentado en el Javadoc del endpoint): el método es `synchronized` y la venta lo invoca dentro de la transacción que retiene `PESSIMISTIC_WRITE` sobre el consecutivo (`ComprobanteService:421-424`); un poll automático convertiría cada minuto de navegación en hasta 2,5 s de bloqueo para las ventas concurrentes.
- El probe **falla abierto** (`:213-217`): un `true` no prueba que el API responda, solo que el host acepta TCP. El texto del chip lo dice ("TCP alcanzable") para no sobreprometer.

### 2.3 Tabla de pendientes

Origen: `findByEstado("PENDIENTE", 50)`, orden servidor por `vencimiento ASC` (el más urgente primero). Si `contarPorEstado("PENDIENTE") > 50` se muestra una nota al pie: "Se muestran los 50 con vencimiento más próximo de {total}".

| Columna | Clave del modelo | Render |
|---|---|---|
| Tipo | `tipoDocumentoTexto` | "FE", "TE", "NC", "ND", "FEE", "FEC", "REP" (mapa de `tipoDocumento`) + `title` con el código |
| Consecutivo | `consecutivo` | `.kit-code` |
| Clave | `claveCorta` | `.kit-code`, 12+…+6 con `title="{clave}"` completo |
| Emisión | `fechaEmision` | `time:format(..., 'dd/MM/yy HH:mm')` |
| Vence | `vencimiento` | `dd/MM/yy HH:mm` + chip `is-warning` si `porVencer` (≤ 8 h), `is-danger` si `fueraDePlazo` |
| Intentos | `intentosTexto` | `3/8`; chip `is-danger` si `intentos >= maxIntentos` |
| Próximo intento | `proximoIntento` | hora, o `—` si `null`; "ahora" si ya venció el turno |
| Origen | `origenTexto` + `origenChipClass` | "Sin conexión" (`OFFLINE_SITUACION_3`) / "Fallo de envío" (`FALLO_ENVIO_INMEDIATO`) |
| Último error | `ultimoErrorResumen` | 140 caracteres; si hay más, `<details>` con `ultimoErrorCompleto` en `<pre>` |
| Situación | `situacion` | chip `is-warning` "3" con `title` de Art. 21 ¶3; "1" en texto plano |
| Acciones | — | botón "Reintentar" o texto `motivoNoReintentable` |

Derivaciones (servidor, no Qute — `_kit` no lleva lógica):

```
porVencer        = vencimiento != null && vencimiento > ahora && vencimiento <= ahora.plusHours(8)
fueraDePlazo     = vencimiento != null && !ahora.isBefore(vencimiento)
puedeReintentar  = estado == PENDIENTE && !fueraDePlazo
                   && (proximoIntento == null || !ahora.isBefore(proximoIntento))
motivoNoReintentable =
    fueraDePlazo            -> "Fuera de plazo: se escalará a VENCIDO sin transmitir"
    proximoIntento futuro   -> "Próximo intento programado para dd/MM HH:mm"
    intentos >= MAX_INTENTOS-> "Sin intentos disponibles"
```

El botón de fila es `POST` con `hx-disabled-elt="this"` y `hx-confirm` que nombra el consecutivo. Cuando `puedeReintentar == false` no se renderiza botón: se renderiza el motivo como texto (`help`), no como botón deshabilitado (un botón muerto sin explicación es peor que una frase).

### 2.4 Vencidos y rechazados

- **Vencidos**: `findByEstado("VENCIDO", 20)`, invertido en memoria (el orden del servicio es `vencimiento ASC` y aquí interesa el incumplimiento más reciente primero; ≤20 filas, coste despreciable). Columnas: tipo, consecutivo, clave, emisión, venció, intentos, origen, motivo (`ultimoErrorCompleto` en `<details>`), y un enlace best-effort "Buscar en consultas" a `/app/tributacion/consultas?q={clave}`.
  **Sin reintento**: es terminal por diseño (`:466-471`). La ayuda dice: "El plazo legal venció. El documento debe enviarse manualmente a Hacienda y regularizarse; reintentarlo automáticamente solo repetiría el incumplimiento."
- **Rechazados en cola**: `findByEstado("RECHAZADO", 20)`. Con el texto de ayuda de §1.3: "El outbox no produce este estado; si aparece, lo escribió un proceso externo." Se mantiene porque el modelo de estados lo contempla y el operador debe poder verlo si ocurre.

### 2.5 Estados vacíos, error y contingencia

| Situación | Render |
|---|---|
| Sin pendientes | `_kit/empty-state` `title="No hay documentos en cola"` + `message="La cola solo se llena cuando una venta no puede transmitirse a Hacienda (sin conexión o fallo de envío). Si esperabas documentos aquí, revisa Configuración › Hacienda."` icono `check` |
| Sin vencidos | Variante de éxito: `title="Ningún documento ha incumplido el plazo"`, **no** un empty-state neutro: la ausencia de incumplimientos es una buena noticia y debe leerse como tal |
| Sin rechazados | Empty-state neutro breve |
| Hay filas `situacion = "3"` en pendientes | Banner (`_kit/surface` con chip `is-warning`) sobre la tabla: "N documento(s) se emitieron con Situación 3 (Art. 21 ¶3). Deben transmitirse antes de su vencimiento; un vencido es un incumplimiento legal." |
| Probe `false` | Banner `is-danger` en la surface de conectividad, no bloqueante: la pantalla sigue siendo usable porque lee de la base, no de la red |
| Excepción al armar el modelo | `LOG.error` con `source` + render de la **página completa** con `errorGeneral` (surface `is-danger`, texto "No se pudo leer la cola. Los datos no se modificaron.") y HTTP 500; para peticiones `HX-Request` se devuelve 500 también y el handler global de `web/index.js:624-628` muestra el toast de error (no se hace *swap* de un fragmento de error: un 200 con contenido de error se confundiría con éxito y rompería la antigüedad del poll) |
| Acción rechazada por regla de negocio (terminal, aún no le toca, lote en curso) | **200** + fragmento `tablero` + toast OOB `is-warning` con el motivo exacto. Es un resultado esperado, no un fallo del servidor |

### 2.6 Polling, accesibilidad y responsive

- **30 s**, no 5 s: `docs/ui-kit.md §8.3` reserva los 5 s para la cuenta regresiva de tributación; el outbox cambia como máximo cada 15 min.
- Sin WebSocket. Un `<div id="cola-tablero">` estable con `hx-get` + `hx-trigger="every 30s"` + `hx-swap="innerHTML"` + `data-poll data-poll-age="#cola-edad"`; el fragmento reemplaza **solo el contenido**, así el contenedor y su intervalo sobreviven (mismo patrón que el span del countdown de consultas). El JS compartido (`kitPolling`) añade la etiqueta "Actualizado hace N s" y hace catch-up al volver a la pestaña.
- Nada de `aria-live` sobre la tabla: anunciar 50 filas cada 30 s es ruido. La etiqueta de antigüedad es el único texto vivo (`aria-live="polite"`), y los toasts ya viven en el live region global.
- `<caption>` en cada tabla vía slot de `_kit/data-table` ("Documentos pendientes de transmisión", etc.). Los botones llevan nombre accesible con el consecutivo ("Reintentar el documento 5060101…"), no solo "Reintentar".
- Responsive: 11 columnas → scroll horizontal dentro de `.table-container` (el pager ya queda fuera del scroller, por diseño del kit). La clave se trunca con `title`, no se oculta la columna: `_kit/data-table` no permite marcar columnas responsive y añadir esa capacidad al kit está fuera de alcance. La tabla es un registro de auditoría; ocultar la clave es peor que desplazarse.

---

## 3. Contrato técnico

### 3.1 Endpoints

Dos clases nuevas, una por superficie. Se mantiene la separación del repo: `/app/...` sirve HTML, `/api/app/...` muta.

**`Controllers/Api/App/Reportes/ColaEnvioPagesResource.java`** — `@Path("/app/tributacion/cola-envio")`, `@Produces(TEXT_HTML)`, `@RolesAllowed({"admin","tributacion"})`:

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/app/tributacion/cola-envio` | página completa; con `HX-Request` solo el fragmento `tablero` |
| GET | `/app/tributacion/cola-envio/conexion` | solo el fragmento `conexion` (única ruta que ejecuta `hayConectividadConHacienda()`) |

**`Controllers/Api/App/ColaEnvioResource.java`** — `@Path("/api/app/tributacion/cola-envio")`, `@RolesAllowed({"admin","tributacion"})`:

| Método | Ruta | Semántica | Códigos |
|---|---|---|---|
| GET | `/api/app/tributacion/cola-envio` | JSON `ColaEnvioVista` (gemelo de la pantalla; útil para pruebas y automatización) | 200, 500 |
| POST | `/api/app/tributacion/cola-envio/{id}/reintentar` | carga por `find(id)` y llama `reintentar(registro)` | 200 (fragmento+toast), 200 con toast warning (terminal / no le toca / fuera de plazo), 404, 500 |
| POST | `/api/app/tributacion/cola-envio/reintentar-todos` | lote acotado bajo candado | 200 (fragmento+toast), 200 toast warning (lote en curso / nada que hacer), 500 |

Detalles obligatorios:

- CSRF: los POST de HTMX heredan el token de `layout.html` (`<body hx-headers>`); no se añade nada por elemento (ui-kit §2, regla 2).
- `HX-Request` presente ⇒ respuesta HTML (`getFragment("tablero")` + toast OOB); ausente ⇒ `ApiResponse.ok(dto)`.
- Sin `hxRedirect` en los POST: la pantalla ya tiene un fragmento polled y un redirect tiraría el estado del tab y la antigüedad. Es una desviación deliberada del patrón de `TributacionResource.enviarPendientes` (`:374-376`), donde no existía fragmento que refrescar.
- `POST /{id}/reintentar` revalida en servidor **todo** el gating de la UI antes de llamar al servicio (estado, vencimiento, `proximoIntento`). La comprobación del cliente es cosmética.

### 3.2 DTOs (records, estilo `TributacionResource`)

```java
/** KPI + estado de conexión. `haciendaAlcanzable` es null cuando no se ha verificado. */
public record ColaKpisDTO(long pendientes, long vencidos, long rechazados,
                          long enviadosHoy, int maxIntentos,
                          Boolean haciendaAlcanzable) {}

/** Una fila del outbox tal como la consume la tabla (sin XML). */
public record ColaFilaDTO(long id, String tipoDocumento, String tipoDocumentoTexto,
                          String consecutivo, String clave, LocalDateTime fechaEmision,
                          LocalDateTime vencimiento, int intentos, int maxIntentos,
                          LocalDateTime proximoIntento, String origen, String origenTexto,
                          String situacion, String ultimoError,
                          LocalDateTime fechaUltimoIntento, LocalDateTime fechaEnvio,
                          String estado, boolean porVencer, boolean fueraDePlazo,
                          boolean puedeReintentar, String motivoNoReintentable) {}

/** Vista completa: gemelo JSON de la pantalla. */
public record ColaEnvioVista(ColaKpisDTO kpis, boolean contingencia, int contingenciaFilas,
                             long totalPendientes, List<ColaFilaDTO> pendientes,
                             List<ColaFilaDTO> vencidos, List<ColaFilaDTO> rechazados) {}

/** Resultado de POST /{id}/reintentar. `estadoFinal` es el estado leído tras la llamada. */
public record ReintentoFilaDTO(long id, boolean enviado, boolean escalado, boolean omitido,
                               String mensaje, String estadoFinal, String severity) {}

/** Resultado de POST /reintentar-todos. */
public record ReintentoLoteDTO(int procesados, int enviados, int escalados, int omitidos,
                               int limite, String mensaje, String severity) {}
```

`severity` usa el vocabulario del kit (`success|warning|danger|info`, ui-kit §2 regla 6) para que el toast se pinte sin traducción.

### 3.3 Modelo Qute

Claves que el template puede referenciar (Qute es estricto: una clave ausente **es** un error, así que se emiten todas; las opcionales se guardan con `??`):

```
titulo, baseUrl, intervaloPoll, ahoraTexto
pendientes, vencidos, rechazados, enviadosHoy, maxIntentos, loteMaximo, plazoDias
contingencia (boolean), contingenciaFilas (int)
conexionVerificada (boolean), haciendaAlcanzable (Boolean|null),
conexionTexto, conexionChipClass, conexionDetalle
filasPendientes, filasVencidos, filasRechazados
contadorPendientes, contadorVencidos, contadorRechazados
headersPendientes, headersVencidos, headersRechazados
pendientesDebidos (int), puedeReintentarTodos (boolean), motivoNoLote (String|null)
notaTope (String|null)          // "Se muestran los 50 con vencimiento más próximo de N"
toastSeverity, toastMessage     // opcionales
errorGeneral                    // opcional
```

Cabeceras con `col(label, null)` (`AppRecursoBase:131-136`) ⇒ columnas no ordenables, coherente con los snapshots acotados del dashboard (`TributacionPagesResource:353-366`). No se usa `page/size/sort/dir`: no hay paginador, y esos son claves reservadas del kit.

### 3.4 Fragmentos y estructura del template

Archivo nuevo: `app/src/main/resources/templates/pages/tributacion/cola-envio.html`.

```
{#include layout}
  {#body}
    {#include _kit/confirm /}
    {#include _kit/page-header title="Cola de envío a Hacienda" subtitle="..."}
      {#actions}
        <button class="button" hx-get="/app/tributacion/cola-envio"
                hx-target="#cola-tablero" hx-swap="innerHTML"
                hx-disabled-elt="this">Actualizar</button>
        <button class="button is-primary" hx-post="/api/app/tributacion/cola-envio/reintentar-todos"
                hx-target="#cola-tablero" hx-swap="innerHTML"
                hx-disabled-elt="this" hx-indicator="#cola-lote-ind"
                hx-confirm="¿Reintentar ahora todos los documentos cuyo turno ya venció? Máximo {loteMaximo} por lote."
                {#if !puedeReintentarTodos}disabled{/if}>Reintentar el lote</button>
      {/actions}
    {/include}

    {#include _kit/surface id="cola-conexion" title="Conectividad con Hacienda"}
      {#actions}<span id="cola-conexion-chip" class="status-chip {conexionChipClass}">{conexionTexto}</span>{/actions}
      {#content}
        <div id="cola-conexion-detalle">
          <p class="help">{conexionDetalle}</p>
          <button class="button is-small" hx-get="/app/tributacion/cola-envio/conexion"
                  hx-target="#cola-conexion-detalle" hx-swap="outerHTML">Verificar conexión</button>
        </div>
      {/content}
    {/include}

    <div id="cola-tablero"
         hx-get="/app/tributacion/cola-envio" hx-trigger="every {intervaloPoll}"
         hx-target="#cola-tablero" hx-swap="innerHTML"
         data-poll data-poll-age="#cola-edad">
      {#fragment id=tablero}
        ... KPI strip (_kit/metric x5) ...
        ... banner de contingencia ...
        ... _kit/view-tabs (Alpine) + tres paneles ...
        ... _kit/action-bar con <span id="cola-edad"> ...
      {/fragment}
    </div>

    {#if toastMessage??}
    <div hx-swap-oob="beforeend:#toast-container">
      {#include _kit/toast-item severity=toastSeverity message=toastMessage /}
    </div>
    {/if}
  {/body}
{/include}
```

Notas de Qute que rompen si se ignoran:
- Un `<div id="cola-tablero">` **fuera** del `{#fragment tablero}`: el fragmento devuelve solo su contenido interior y el contenedor con `hx-trigger` debe sobrevivir al swap.
- El toast OOB es hijo de nivel superior del body (htmx solo procesa swaps OOB en elementos raíz), igual que `pages/caja/estado-caja.html:79-83`.
- Llaves de Alpine con espacio (`{ tab: 'pendientes' }`), ui-kit §2 regla 3.
- `hx-trigger="every {intervaloPoll}"` no compone bien con la expresión de Qute dentro de un atributo: **el valor se emite como literal completo** desde el modelo (`intervaloPoll = "every 30s"`), no concatenado en el template.

Pestañas (Alpine, sin recargar): Pendientes (`contadorPendientes`), Vencidos (`contadorVencidos`), Rechazados (`contadorRechazados`) con `_kit/view-tabs` en modo slot. La tabla activa se decide en cliente porque las tres ya viajan en el mismo fragmento (≤90 filas) y así el poll no pierde la pestaña; el contrato del kit lo permite explícitamente ("purely local view → Alpine `x-show` panels").

### 3.5 Reutilización de `_kit/`

| Componente | Uso |
|---|---|
| `_kit/page-header` | título, subtítulo y las dos acciones globales |
| `_kit/metric` | las 5 tarjetas KPI (pasivas) |
| `_kit/surface` | conectividad, banner de contingencia, panel de tablas (`flush=true`) |
| `_kit/data-table` | las tres tablas, `baseUrl=baseUrl`, cabeceras label-only, slot `{#rows}` con `{#for}`/`{#else}` (cada tabla mantiene su propio empty-state) |
| `_kit/empty-state` | vacíos de pendientes/vencidos/rechazados |
| `_kit/view-tabs` | navegación entre las tres tablas |
| `_kit/action-bar` | conteo + etiqueta de antigüedad del poll |
| `_kit/toast-item` | toast OOB de resultados |
| `_kit/confirm` | intercepta los `hx-confirm` con el diálogo del sistema |
| `_kit/icon` | iconos de los empty-states (`check`, `alert`, `clock`) |

No se crean componentes nuevos en `_kit/`.

### 3.6 Logging

`org.jboss.logging.Logger` en ambas clases, con el formato de la casa (`| source=... | despues=...`):

- `LOG.info` en cada acción: `"Reintento manual del documento {clave} (intento n/8) | user=... | source=ColaEnvioResource.reintentarFila() | despues=estado={estado}"`.
- `LOG.info` del lote: procesados/enviados/escalados/omitidos.
- `LOG.warn` en rechazos de regla de negocio (404/409 lógicos) con el motivo.
- `LOG.error` con stacktrace en `RuntimeException` del modelo o de la acción.
- Nunca se loguea `xmlFirmado` ni bytes del documento (volumen y contenido fiscal). La clave sí: es pública y va impresa en la factura.
- Usuario vía `Utils.LogUsers.nombre(currentUser())`, mismo helper que `TributacionResource:368`.

---

## 4. Fases, criterios de aceptación y esfuerzo

### Fase 0 — Verificación de contrato (0,5 día)
Verificar en el build `findByEstado(String,int)` y `contarPorEstado(String)` (firma, tope, orden). Añadir si faltan, más `contarEnviadosDesde(LocalDateTime)` y `procesarPendientes(LocalDateTime, int)`.
**Aceptación:** `mvn -q test -Dtest=EnvioFueraLineaTest` verde; los métodos nuevos siguen el patrón `PersistenceException` + `LOG.warn` + retorno vacío/0; ningún método existente cambia de firma ni de semántica.

### Fase 1 — Modelo de vista y mapper (0,5 día)
Clase `ColaEnvioModelo` (helper estático en `Controllers/Api/App/Reportes/`, junto al recurso de página) que traduce entidades a `Map<String,Object>` con todas las derivaciones de §2.3, y a `ColaFilaDTO` para el JSON. Una sola implementación para HTML y JSON.
**Aceptación:** test unitario de mapper con: fila recién encolada (`puedeReintentar=true`), fila con `proximoIntento` futuro (`false` + motivo), fila con `vencimiento` pasado (`fueraDePlazo=true`, `puedeReintentar=false`), `intentos=8` (`motivo` = sin intentos), `situacion="3"` (`esContingencia`), `ultimoError` de 2000 caracteres (resumen 140 + completo intacto).

### Fase 2 — Página y fragmentos (1,5 días)
`ColaEnvioPagesResource`, template `cola-envio.html`, fragmentos `tablero`, `conexion`; entrada del sidebar.
**Aceptación:** `GET /app/tributacion/cola-envio` 200 con las 5 métricas, las 3 tablas y el banner de contingencia cuando aplique; con `HX-Request` devuelve **solo** el fragmento (sin `<html>`, sin layout); `GET .../conexion` devuelve solo la surface de conectividad; sin rol → 403; base de datos con cero filas → tres empty-states, ninguno con `colspan` desalineado; `RootPathNormalizationTest` verde (ninguna URL con root literal).

### Fase 3 — Acciones (1 día)
`ColaEnvioResource`: GET JSON, POST por fila, POST de lote con `ReentrantLock.tryLock()` compartido; candado integrado en `procesarPendientes(ahora, limite)`.
**Aceptación:** fila apta → `reintentar` invocado **una** vez y toast `success`; fila terminal o fuera de plazo → 200 + toast `warning`, servicio **no** invocado; id inexistente → 404; excepción → 500 + `LOG.error`; lote → una sola llamada a `procesarPendientes(ahora, limiteUi)`; segundo lote concurrente → toast "Ya hay un lote en curso" y **cero** envíos; con el candado sin implementar, el botón no se renderiza (test que lo verifica).

### Fase 4 — Pruebas y pulido (1 día)
Unitarias + `@QuarkusTest` de página y acciones, revisión de accesibilidad/responsive, textos legales.
**Aceptación:** suite nueva verde y sin regresiones en `TributacionResourceTest`/`TributacionPagesTest`; página usable a 360 px (scroll de tabla, acciones apiladas); los textos de Art. 21 ¶3 aparecen literalmente donde §2.5 los exige.

**Total: 4,5 días.** Sin migración de esquema (`quarkus.hibernate-orm.schema-management.strategy=update` ya cubre la tabla).

---

## 5. Pruebas

### 5.1 Unitarias (Mockito, patrón de `EnvioFueraLineaTest`)
- Mapper: matriz de §2.3 (fechas con `LocalDateTime` fijo, nunca `now()` dentro del test).
- Mapper: `tipoDocumento` desconocido → se muestra el código crudo, no `null`.
- `claveCorta`: clave de 50 caracteres → 12 + "…" + 6; clave corta/null → sin truncar y `—`.
- Formateo de error: 139 caracteres → sin `<details>`; 141 → `<details>` con el texto completo.

### 5.2 `@QuarkusTest` (patrón `TributacionPagesTest` / `@TestSecurity`)
- Página completa: contiene "Cola de envío a Hacienda", los 5 labels de KPI y el `id="cola-tablero"`.
- Fragmento: `HX-Request: true` → **no** contiene `<!DOCTYPE` ni `id="cola-tablero"` (el contenedor vive en la página, y si el fragmento lo re-emitiera se duplicaría el intervalo del poll); el contenido empieza en el strip de métricas, y se asserta explícitamente.
- `conexion`: con el servicio mockeado a `false`, el chip es `is-danger` y el texto menciona Situación 3; **assert `hayConectividadConHacienda()` invocado exactamente 1 vez** y **no invocado** durante el GET de la página.
- Autorización: sin rol → 403 en página y en POST.
- POST por fila: 200 + `data-toast-severity="success"` para fila apta; `warning` para terminal; 404 para id inexistente; `verify(service, never()).reintentar(...)` en los casos rechazados por regla.
- POST de lote: `verify(service, times(1)).procesarPendientes(any(), eq(limiteUi))`; mensaje con los cuatro conteos.
- Qute estricto: renderizar el template con el modelo mínimo no debe lanzar `TemplateException` (clave ausente).

### 5.3 Integración / E2E manual (sin automatizar navegador)
1. Venta con Hacienda inalcanzable (host bloqueado en `/etc/hosts` o firewall) → fila `PENDIENTE`, `situacion=3`, `intentos=0`, `proximoIntento≈ahora`; aparece en la pantalla con chip "Sin conexión".
2. "Reintentar" con la red aún caída → `intentos=1`, `ultimoError` poblado, `proximoIntento=+15m`; el botón desaparece hasta que llegue el turno (texto de motivo visible).
3. Restaurar conectividad, esperar el turno, "Reintentar" → fila fuera de pendientes, KPI "Enviados hoy" +1, comprobante en `ACEPTADO` (verificable en `/app/tributacion/consultas`).
4. Forzar `vencimiento` al pasado en base de datos → la fila muestra "Fuera de plazo" sin botón; al pulsar "Reintentar el lote" pasa a `VENCIDO` **sin** tráfico de red (verificar con el log de `escalarVencido` y sin entrada en `HaciendaApiService`), y llega el correo si `correoNotificaciones` está configurado.

---

## 6. Riesgos y mitigaciones

| Riesgo | Por qué es real | Mitigación en este plan |
|---|---|---|
| **Reintentar un vencido** | `reintentar` escala a `VENCIDO` sin red (`:473-480`); un botón ingenuo convertiría una fila en incumplimiento al hacer clic | El botón no existe si `fueraDePlazo`; el POST revalida y responde 200 + toast warning explicando que escalará; nunca se envía fuera de plazo |
| **Doble envío (fila)** | Dos clics o un clic + tick del job pueden llamar `submitAndWait` dos veces con la misma clave | `hx-disabled-elt`, gating por `proximoIntento` (tras el primer intento queda en futuro), revalidación servidor, y `reintentar` salta estados terminales. Residual: dos procesos concurrentes sobre la misma fila; se cubre con el candado de lote (§Riesgo siguiente) |
| **Doble envío (lote)** | `@Scheduled(SKIP)` no impide scheduler vs HTTP; ambos cargan las mismas filas `PENDIENTE` | `ReentrantLock.tryLock()` en `procesarPendientes(ahora, limite)`: la segunda entrada no espera, informa "lote en curso". Garantía de **una sola JVM** (on-premise); multi-nodo requeriría bloqueo en base (`SELECT ... FOR UPDATE SKIP LOCKED`), documentado como deuda |
| **Bloqueo de ventas por el probe** | `hayConectividadConHacienda()` es `synchronized` y la venta lo llama dentro de la transacción con el consecutivo bloqueado; timeout 2,5 s | Cero probes automáticos; solo el botón "Verificar conexión". El poll de 30 s no toca la red |
| **Petición HTTP larga en "Reintentar el lote"** | Cada fila puede consumir el poll de `submitAndWait` (decenas de segundos); 50 filas agotan el timeout del proxy | Límite de lote para UI (`limiteUi = 10`), `hx-indicator`, y expectativa documentada de que la acción puede tardar; el job conserva `LOTE_MAXIMO = 50` |
| **Hidratación de LOBs** | `findByEstado` devuelve entidades con `xmlFirmado` y `xmlFirmadoBytes`; 50 filas × XML de factura ≈ MB por render, y el poll repite cada 30 s | Se topa a 50/20/20; el XML no se renderiza ni se serializa en el JSON. Si aparece lentitud: proyección/DTO en la query (deuda anotada, fuera de alcance) |
| **`RECHAZADO` casi siempre vacío** | El outbox no escribe ese estado (§1.3) | Texto de ayuda explícito; el KPI no se rotula "rechazados por Hacienda" |
| **Feriados no modelados** | `calcularVencimiento` solo salta sáb/dom | Se muestra el `vencimiento` persistido, sin recalcular; nota en la ayuda del panel: el plazo puede vencer un día antes en semana con feriado |
| **Zona horaria de "hoy"** | `fechaEnvio` es `LocalDateTime` del host; el resto de la UI usa `America/Costa_Rica` | El KPI usa el mismo reloj que escribió la columna y lo dice en el `detail`; alternativa "últimas 24 h" documentada |
| **Mezclar esta cola con el dashboard** | Dos definiciones de "pendiente" conviviendo | Títulos y ayudas distintos ("Cola de envío" vs "Pendientes de Hacienda"); cero reutilización de contadores de `ComprobantesEmitidosService` |
| **Probe que miente** | Falla abierto (`:213-217`): un `true` solo prueba TCP | Texto "TCP alcanzable"; la señal real es la cola (`fechaUltimoIntento`, `ultimoError`), no el chip |

---

## 7. Fuera de alcance

- Reenvío manual de un `VENCIDO` desde esta pantalla (requiere decisión legal y un flujo de regularización, no un botón).
- Edición o re-firma de `xmlFirmado`: el outbox reenvía bytes exactos por diseño.
- Consumo de webhooks de Fides (decisión §1.7).
- Filtros, búsqueda y paginación de la cola: los topes de 50/20/20 cubren el caso operativo; añadir paginación obligaría a `page/size/sort` y a un contrato de orden que hoy no existe.
- Tocar `ComprobanteService`, `EnvioFueraLineaRetryJob`, `HaciendaServiceFacade`, `FidesApiService` o `TributacionPagesResource`.
- Migración de datos o de esquema.

---

## 8. Archivos

**Crear**
- `app/src/main/java/Controllers/Api/App/Reportes/ColaEnvioPagesResource.java`
- `app/src/main/java/Controllers/Api/App/Reportes/ColaEnvioModelo.java`
- `app/src/main/java/Controllers/Api/App/ColaEnvioResource.java`
- `app/src/main/resources/templates/pages/tributacion/cola-envio.html`
- `app/src/test/java/Controllers/Api/App/ColaEnvioResourceTest.java`
- `app/src/test/java/Controllers/Api/App/Reportes/ColaEnvioPagesTest.java`

**Modificar (mínimo y justificado)**
- `app/src/main/java/Services/EnvioFueraLineaService.java`: `contarEnviadosDesde`, `procesarPendientes(ahora, limite)` + candado, y `findByEstado`/`contarPorEstado` **solo si la fase 0 confirma que faltan**.
- `app/src/main/resources/templates/fragments/app-sidebar.html`: entrada "Cola de envío".
- `app/src/test/java/Services/EnvioFueraLineaTest.java`: cobertura de los métodos nuevos.
