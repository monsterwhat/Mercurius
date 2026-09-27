package Services;

import Models.ComprobantesEmitidos;
import Models.ConfiguracionAplicacion;
import Models.Encabezado.Encabezado;
import Models.Encabezado.IdentificacionEmisor;
import Models.Encabezado.IdentificacionReceptor;
import Models.EnvioFueraLinea;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Outbox for electronic documents that were generated and signed at the point of
 * sale but could not be transmitted to Hacienda.
 *
 * <h3>Legal driver — CR Art. 21 ¶3 (Ley 6828)</h3>
 * A document whose signed XML cannot be sent for lack of connectivity must be
 * generated and signed at the moment of the sale and transmitted no later than
 * two business days afterwards, with {@code Situacion = 3} in the document key
 * (position 42 of the 50-character clave). The three obligations enforced here:
 * <ol>
 *   <li><b>Signed at the sale</b> — {@link #registrarDocumentoFirmado} stores the
 *       exact XAdES bytes, so a retry never re-marshals or re-signs.</li>
 *   <li><b>Sent within two business days</b> — {@link #calcularVencimiento}
 *       derives a hard {@code vencimiento}, persisted on the row so a later clock
 *       or configuration change cannot extend an already-communicated deadline.</li>
 *   <li><b>Escalated when that is impossible</b> — {@link #escalarVencido} moves
 *       the row to {@code VENCIDO}, logs at ERROR and notifies
 *       {@code ConfiguracionAplicacion.correoNotificaciones}.</li>
 * </ol>
 *
 * <h3>Business days</h3>
 * Monday–Friday. The repository has no holiday calendar, so public holidays are
 * not deducted: a deadline computed here is an upper bound that can expire a day
 * early in a holiday week. That is the safe direction for a legal deadline —
 * transmit sooner, never later.
 *
 * <h3>Submission</h3>
 * Replays go through {@link HaciendaApiService#submitAndWait}, the same primitive
 * {@link HaciendaServiceFacade} uses for direct Hacienda submissions, so there is
 * exactly one submission path in the codebase. The Fides provider is bypassed for
 * these rows by design: Fides signs documents itself, and Art. 21 ¶3 requires
 * transmitting <em>the document signed at the sale</em>, which Fides cannot do.
 */
@Named
@ApplicationScoped
public class EnvioFueraLineaService extends GService<EnvioFueraLinea> {

    private static final Logger LOG = Logger.getLogger(EnvioFueraLineaService.class);

    // ── Situacion codes (position 42 of the clave) ──────────────────────────

    /** Normal transmission. */
    public static final String SITUACION_NORMAL = "1";
    /** Art. 21 ¶3 — emitted while offline, transmitted within two business days. */
    public static final String SITUACION_FUERA_LINEA = "3";

    // ── Row states ─────────────────────────────────────────────────────────

    public static final String ESTADO_PENDIENTE = "PENDIENTE";
    public static final String ESTADO_ENVIADO = "ENVIADO";
    public static final String ESTADO_RECHAZADO = "RECHAZADO";
    public static final String ESTADO_VENCIDO = "VENCIDO";

    // ── Row origins ────────────────────────────────────────────────────────

    /** Hacienda is unreachable at the moment of the sale. */
    public static final String ORIGEN_OFFLINE_SITUACION_3 = "OFFLINE_SITUACION_3";
    /** Hacienda was reachable but the immediate submission failed. */
    public static final String ORIGEN_FALLO_ENVIO_INMEDIATO = "FALLO_ENVIO_INMEDIATO";

    /** Art. 21 ¶3: two business days. */
    public static final int PLAZO_DIAS_HABILES = 2;

    /**
     * Attempts after which a row is escalated even if the deadline has not
     * arrived, so a permanently unreachable Hacienda cannot generate one
     * undelivered document per sale forever.
     */
    public static final int MAX_INTENTOS = 8;

    /** Rows per scheduler tick, so one pass cannot run for hours. */
    public static final int LOTE_MAXIMO = 50;

    private static final String SANDBOX_BASE_URL =
            "https://api.comprobanteselectronicos.go.cr/recepcion-sandbox/v1";
    private static final String PRODUCTION_BASE_URL =
            "https://api.comprobanteselectronicos.go.cr/recepcion/v1";

    /** How long a reachability verdict is reused before probing again. */
    private static final Duration TTL_SONDEO = Duration.ofSeconds(60);
    private static final int TIMEOUT_SONDEO_MS = 2500;
    private static final int ERROR_MAXIMO = 2000;

    /** Defaults matching HaciendaServiceFacade's anonymous-receptor fallback. */
    private static final String TIPO_ANONIMO = "01";
    private static final String NUMERO_ANONIMO = "000000000";

    @Inject
    private @Nonnull AppSettingsService appSettingsService;

    @Inject
    private @Nonnull HaciendaApiService haciendaApiService;

    @Inject
    private @Nonnull EmailService emailService;

    private long sondeoVenceEnMillis;
    private boolean sondeoConectivo;

    @Override
    protected @Nonnull Class<EnvioFueraLinea> getEntityClass() {
        return EnvioFueraLinea.class;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Business-day and backoff arithmetic (pure, static, unit-tested)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Monday through Friday. Public holidays are not modelled — see the class
     * comment.
     */
    public static boolean esDiaHabil(@Nonnull LocalDate fecha) {
        DayOfWeek dow = fecha.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * Art. 21 ¶3 deadline: the same clock time, {@value #PLAZO_DIAS_HABILES}
     * business days after {@code desde}, skipping Saturdays and Sundays.
     *
     * <p>Emission time of day is preserved so a document sold at 23:50 is not
     * pulled forward to the next day's opening time. The result is always
     * strictly after {@code desde} (the loop advances at least one day), so a row
     * can never be enqueued already overdue.
     *
     * @throws IllegalArgumentException if {@code desde} is null
     */
    public static @Nonnull LocalDateTime calcularVencimiento(@Nonnull LocalDateTime desde) {
        if (desde == null) {
            throw new IllegalArgumentException("calcularVencimiento requiere la fecha de emision");
        }
        LocalDateTime vencimiento = desde;
        int habiles = 0;
        while (habiles < PLAZO_DIAS_HABILES) {
            vencimiento = vencimiento.plusDays(1);
            if (esDiaHabil(vencimiento.toLocalDate())) {
                habiles++;
            }
        }
        return vencimiento;
    }

    /**
     * Backoff for attempt number {@code intento} (1-based), clamped so the next
     * attempt is never scheduled after the Art. 21 ¶3 deadline.
     *
     * <p>15m → 1h → 4h → 8h → 12h, then a flat 12h. Long gaps matter:
     * {@code HaciendaApiService.sendInvoice} already retries internally on a
     * two-hour delay, so a tight outer loop would only pile up scheduler threads.
     */
    public static @Nonnull LocalDateTime calcularProximoIntento(
            @Nonnull LocalDateTime base, int intento, @Nonnull LocalDateTime vencimiento) {
        long minutos;
        switch (intento) {
            case 1 -> minutos = 15;
            case 2 -> minutos = 60;
            case 3 -> minutos = 240;
            case 4 -> minutos = 480;
            default -> minutos = 720;
        }
        LocalDateTime siguiente = base.plusMinutes(minutos);
        return siguiente.isBefore(vencimiento) ? siguiente : vencimiento;
    }

    private static String recortar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= ERROR_MAXIMO ? texto : texto.substring(0, ERROR_MAXIMO);
    }

    /** The column is NOT NULL, but a row written by an older build may predate it. */
    private static int intentosDe(@Nonnull EnvioFueraLinea registro) {
        return registro.getIntentos() == null ? 0 : registro.getIntentos();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Connectivity probe
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Whether Hacienda's host is reachable, cached for
     * {@value #TTL_SONDEO} seconds so a burst of sales costs one probe.
     *
     * <p><b>Fails open (returns {@code true}).</b> A false negative would make
     * every sale emit {@code Situacion = 3} and skip the immediate send — a
     * strictly worse outcome than attempting a send that may then fail. Only a
     * completed probe verdict is cached; an exception re-probes on the next sale.
     */
    public synchronized boolean hayConectividadConHacienda() {
        long ahora = System.currentTimeMillis();
        if (ahora < sondeoVenceEnMillis) {
            return sondeoConectivo;
        }
        URI uri;
        try {
            uri = URI.create(baseUrlSegunConfiguracion());
        } catch (RuntimeException e) {
            // The configuration could not even be read, so no host was identified to
            // probe: "unknown" is not "offline". Fail open and do NOT cache, so the
            // next sale re-evaluates once the configuration is readable again.
            LOG.warn("No se pudo resolver el host de Hacienda para el sondeo: " + e.getMessage()
                    + " | source=EnvioFueraLineaService.hayConectividadConHacienda()"
                    + " | despues=se asume alcanzable (ruta normal, situacion 1)");
            return true;
        }
        boolean resultado;
        try {
            int puerto = uri.getPort() > 0 ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), puerto), TIMEOUT_SONDEO_MS);
            }
            resultado = true;
        } catch (Exception e) {
            resultado = false;
            LOG.warn("Hacienda no alcanzable (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"
                    + " | source=EnvioFueraLineaService.hayConectividadConHacienda()"
                    + " | despues=comprobante se emite con situacion 3 y se encola (Art. 21 parrafo 3)");
        }
        this.sondeoConectivo = resultado;
        this.sondeoVenceEnMillis = ahora + TTL_SONDEO.toMillis();
        return resultado;
    }

    /**
     * Mirrors the private {@code HaciendaApiService.getBaseUrl()}. Duplicated on
     * purpose: that class is read-only for this change and its helper is not
     * exposed. If the two ever diverge, the probe would answer about a different
     * host than the one used to submit.
     */
    private String baseUrlSegunConfiguracion() {
        ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
        String entorno = settings != null ? settings.getHaciendaEnvironment() : null;
        return "production".equalsIgnoreCase(entorno) ? PRODUCTION_BASE_URL : SANDBOX_BASE_URL;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Enqueue
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Persists an already-signed document, ready for the two-business-day
     * transmission window.
     *
     * <p>Idempotent per {@code clave}: a second call for a clave that already has
     * a row returns the existing row untouched rather than inserting a duplicate.
     *
     * @param comprobante   the persisted document the payload belongs to
     * @param tipoDocumento "01"/"04"/… as emitted
     * @param sucursal      3-digit branch code, as a string
     * @param terminal      5-digit terminal code, as a string
     * @param situacion     {@link #SITUACION_NORMAL} or {@link #SITUACION_FUERA_LINEA}
     * @param xmlFirmado    XAdES-signed XML produced at the sale
     * @param origen        {@link #ORIGEN_OFFLINE_SITUACION_3} or
     *                      {@link #ORIGEN_FALLO_ENVIO_INMEDIATO}
     * @return the stored row, or {@code null} if it could not be persisted
     */
    @Transactional
    public @Nullable EnvioFueraLinea registrarDocumentoFirmado(
            @Nonnull ComprobantesEmitidos comprobante,
            @Nonnull String tipoDocumento,
            @Nonnull String sucursal,
            @Nonnull String terminal,
            @Nonnull String situacion,
            @Nonnull String xmlFirmado,
            @Nonnull String origen) {
        String clave = comprobante.getHaciendaClave();
        if (clave == null || clave.isBlank()) {
            LOG.warn("No se encola documento sin clave de Hacienda"
                    + " | source=EnvioFueraLineaService.registrarDocumentoFirmado()"
                    + " | despues=el comprobante queda como esta");
            return null;
        }
        try {
            EnvioFueraLinea existente = buscarPorClave(clave);
            if (existente != null) {
                LOG.info("Documento ya encolado para envio diferido: " + clave
                        + " | source=EnvioFueraLineaService.registrarDocumentoFirmado()"
                        + " | despues=estado=" + existente.getEstado());
                return existente;
            }

            LocalDateTime ahora = LocalDateTime.now();
            Encabezado encabezado = comprobante.getEncabezado();
            LocalDateTime emision = encabezado != null && encabezado.getFechaEmision() != null
                    ? encabezado.getFechaEmision() : ahora;

            EnvioFueraLinea registro = new EnvioFueraLinea();
            registro.setComprobanteId(comprobante.getId());
            registro.setTipoDocumento(tipoDocumento);
            registro.setClave(clave);
            registro.setConsecutivo(encabezado != null ? encabezado.getNumeroConsecutivo() : "");
            registro.setSucursal(sucursal);
            registro.setTerminal(terminal);
            registro.setSituacion(situacion);
            registro.setXmlFirmado(xmlFirmado);
            registro.setXmlFirmadoBytes(xmlFirmado.getBytes(StandardCharsets.UTF_8));
            registro.setIntentos(0);
            registro.setEstado(ESTADO_PENDIENTE);
            registro.setOrigen(origen);
            registro.setFechaEmision(emision);
            registro.setFechaCreacion(ahora);
            registro.setProximoIntento(ahora);
            registro.setVencimiento(calcularVencimiento(emision));

            congelarEmisorReceptor(registro, encabezado);

            em.persist(registro);
            em.flush();
            LOG.info("Documento encolado para envio diferido: " + clave
                    + " | situacion=" + situacion
                    + " | origen=" + origen
                    + " | vence=" + registro.getVencimiento()
                    + " | source=EnvioFueraLineaService.registrarDocumentoFirmado()"
                    + " | despues=Art. 21 parrafo 3, transmision en cola");
            return registro;
        } catch (PersistenceException e) {
            LOG.error("No se pudo encolar el documento " + clave + ": " + e.getMessage()
                    + " | source=EnvioFueraLineaService.registrarDocumentoFirmado()"
                    + " | despues=queda solo el reintento por lotes de 48h", e);
            return null;
        }
    }

    /**
     * Freezes the transmission envelope as it was at signing time. The
     * identification segments are part of the signed XML, so reading them back
     * from a reconfigured {@code ConfiguracionAplicacion} at retry time could
     * build an envelope that contradicts the signature.
     */
    private void congelarEmisorReceptor(@Nonnull EnvioFueraLinea registro, @Nullable Encabezado encabezado) {
        try {
            ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
            if (settings != null && settings.getIdentificacion() != null) {
                registro.setEmisorTipoId(settings.getTipoIdentificacion());
                registro.setEmisorNumeroId(settings.getIdentificacion());
            }
            if (encabezado != null && encabezado.getEmisor() != null) {
                IdentificacionEmisor id = encabezado.getEmisor().getIdentificacion();
                if (id != null && id.getNumero() != null) {
                    registro.setEmisorTipoId(id.getTipo());
                    registro.setEmisorNumeroId(id.getNumero());
                }
            }
            registro.setReceptorTipoId(TIPO_ANONIMO);
            registro.setReceptorNumeroId(NUMERO_ANONIMO);
            if (encabezado != null && encabezado.getReceptor() != null) {
                IdentificacionReceptor id = encabezado.getReceptor().getIdentificacion();
                if (id != null && id.getNumero() != null) {
                    registro.setReceptorTipoId(id.getTipo());
                    registro.setReceptorNumeroId(id.getNumero());
                }
            }
        } catch (RuntimeException e) {
            // Best-effort metadata: the signed payload is the part that must
            // never be lost and it is already set on the row.
            LOG.warn("No se pudo congelar emisor/receptor del documento: " + e.getMessage()
                    + " | source=EnvioFueraLineaService.congelarEmisorReceptor()"
                    + " | despues=el reintento usara los defaults anonimos");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Retry driver
    // ═════════════════════════════════════════════════════════════════════

    /** Outcome of one row's processing pass. */
    public static class ResultadoReintento {
        /** Hacienda accepted the document. */
        public boolean enviado;
        /** The row moved to {@code VENCIDO} and an operator must act. */
        public boolean escalado;
        /** Nothing was transmitted (not due, already terminal, no payload). */
        public boolean omitido;
        public String mensaje;
    }

    /** Processes the outbox as of the current instant. */
    public int procesarPendientes() {
        return procesarPendientes(LocalDateTime.now());
    }

    /**
     * Retries every row whose {@code proximoIntento} has come due, escalating
     * the ones past their deadline. Each row is isolated: a failure is recorded
     * on that row and the pass continues. Never throws — a sick outbox must not
     * take the scheduler down with it.
     *
     * @return how many rows were transmitted
     */
    @Transactional
    public int procesarPendientes(@Nonnull LocalDateTime ahora) {
        List<EnvioFueraLinea> candidatos = findListosParaReintento(ahora);
        if (candidatos.isEmpty()) {
            return 0;
        }
        int enviados = 0;
        for (EnvioFueraLinea registro : candidatos) {
            try {
                if (reintentar(registro, ahora).enviado) {
                    enviados++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Fallo al procesar un documento encolado: " + e.getMessage()
                        + " | source=EnvioFueraLineaService.procesarPendientes()"
                        + " | despues=se continua con el siguiente", e);
            }
        }
        return enviados;
    }

    /** Retries one row against the current instant. */
    public ResultadoReintento reintentar(@Nonnull EnvioFueraLinea registro) {
        return reintentar(registro, LocalDateTime.now());
    }

    /**
     * Retries one row. The order of checks is the safety argument:
     * <ol>
     *   <li>terminal state → skip; an accepted document is never replayed;</li>
     *   <li>deadline passed → escalate <em>without</em> touching the network, so
     *       a late document is reported as a breach rather than quietly sent;</li>
     *   <li>not due yet → skip;</li>
     *   <li>otherwise submit the stored bytes, then record the outcome and
     *       reschedule or escalate.</li>
     * </ol>
     */
    @Transactional
    public ResultadoReintento reintentar(@Nonnull EnvioFueraLinea registro, @Nonnull LocalDateTime ahora) {
        ResultadoReintento resultado = new ResultadoReintento();
        if (registro == null) {
            resultado.omitido = true;
            resultado.mensaje = "registro nulo";
            return resultado;
        }
        String estado = registro.getEstado();
        if (ESTADO_ENVIADO.equals(estado) || ESTADO_RECHAZADO.equals(estado)
                || ESTADO_VENCIDO.equals(estado)) {
            resultado.omitido = true;
            resultado.mensaje = "estado terminal: " + estado;
            return resultado;
        }

        LocalDateTime vencimiento = registro.getVencimiento();
        if (vencimiento != null && !ahora.isBefore(vencimiento)) {
            escalarVencido(registro, "Vencio el plazo de Art. 21 parrafo 3 (dos dias habiles) el "
                    + vencimiento + " sin confirmacion de Hacienda", ahora);
            resultado.escalado = true;
            resultado.mensaje = "plazo vencido";
            return resultado;
        }
        LocalDateTime proximo = registro.getProximoIntento();
        if (proximo != null && ahora.isBefore(proximo)) {
            resultado.omitido = true;
            resultado.mensaje = "aun no le toca: " + proximo;
            return resultado;
        }

        String payload = registro.getXmlFirmado();
        if (payload == null || payload.isBlank()) {
            resultado.omitido = true;
            resultado.mensaje = "el documento encolado no tiene XML firmado";
            return resultado;
        }

        LOG.info("Reintentando envio del documento " + registro.getClave()
                + " (intento " + (intentosDe(registro) + 1) + "/" + MAX_INTENTOS
                + ", situacion " + registro.getSituacion() + ")"
                + " | source=EnvioFueraLineaService.reintentar()");
        registro.setFechaUltimoIntento(ahora);

        HaciendaApiService.ApiResponse respuesta;
        try {
            respuesta = haciendaApiService.submitAndWait(
                    registro.getClave(), payload,
                    registro.getEmisorTipoId(), registro.getEmisorNumeroId(),
                    registro.getReceptorTipoId(), registro.getReceptorNumeroId());
        } catch (RuntimeException e) {
            respuesta = HaciendaApiService.ApiResponse.error(0, "Excepcion al enviar: " + e.getMessage());
        }

        registro.setIntentos(intentosDe(registro) + 1);

        if (respuesta != null && respuesta.isSuccess()) {
            marcarEnviado(registro, ahora);
            resultado.enviado = true;
            resultado.mensaje = "aceptado por Hacienda";
            return resultado;
        }
        return registrarIntentoFallido(registro,
                respuesta != null && respuesta.errorMessage != null
                        ? respuesta.errorMessage : "Respuesta sin detalle de Hacienda",
                ahora, resultado);
    }

    private void marcarEnviado(@Nonnull EnvioFueraLinea registro, @Nonnull LocalDateTime ahora) {
        registro.setEstado(ESTADO_ENVIADO);
        registro.setFechaEnvio(ahora);
        registro.setProximoIntento(null);
        registro.setUltimoError(null);
        em.merge(registro);
        em.flush();
        marcarComprobanteComoAceptado(registro, ahora);
        LOG.info("Documento enviado fuera de linea aceptado por Hacienda: " + registro.getClave()
                + " (" + intentosDe(registro) + " intento(s), situacion " + registro.getSituacion() + ")"
                + " | source=EnvioFueraLineaService.marcarEnviado()"
                + " | despues=Art. 21 parrafo 3 cumplido dentro del plazo");
    }

    private ResultadoReintento registrarIntentoFallido(
            @Nonnull EnvioFueraLinea registro, @Nonnull String causa,
            @Nonnull LocalDateTime ahora, @Nonnull ResultadoReintento resultado) {
        registro.setUltimoError(recortar(causa));
        LocalDateTime vencimiento = registro.getVencimiento();
        int intentos = intentosDe(registro);

        // Two independent escalation triggers: the attempt budget is gone, or
        // there is no longer room inside the legal window for the next backoff.
        boolean sinIntentos = intentos >= MAX_INTENTOS;
        boolean sinTiempo = vencimiento == null
                || !calcularProximoIntento(ahora, intentos, vencimiento).isAfter(ahora);

        if (sinIntentos || sinTiempo) {
            String motivo = sinIntentos
                    ? "Se agotaron los " + MAX_INTENTOS + " intentos sin confirmacion de Hacienda"
                    : "No cabe otro reintento antes del plazo de Art. 21 parrafo 3 (vence " + vencimiento + ")";
            escalarVencido(registro, motivo + ". Ultima causa: " + causa, ahora);
            resultado.escalado = true;
            resultado.mensaje = motivo;
            return resultado;
        }

        registro.setProximoIntento(calcularProximoIntento(ahora, intentos, vencimiento));
        em.merge(registro);
        em.flush();
        LOG.warn("Reintento fallido para " + registro.getClave() + ": " + causa
                + " | source=EnvioFueraLineaService.registrarIntentoFallido()"
                + " | despues=proximo intento " + registro.getProximoIntento());
        resultado.mensaje = causa;
        return resultado;
    }

    /**
     * Moves a row to {@code VENCIDO}, logs at ERROR and notifies
     * {@code correoNotificaciones}. This is the escalation path for a missed
     * Art. 21 ¶3 deadline: the document must now go to Hacienda manually.
     */
    public void escalarVencido(@Nonnull EnvioFueraLinea registro, @Nonnull String motivo,
                               @Nonnull LocalDateTime ahora) {
        registro.setEstado(ESTADO_VENCIDO);
        registro.setProximoIntento(null);
        registro.setUltimoError(recortar(motivo));
        try {
            em.merge(registro);
            em.flush();
        } catch (PersistenceException e) {
            LOG.error("No se pudo marcar VENCIDO el documento " + registro.getClave() + ": " + e.getMessage()
                    + " | source=EnvioFueraLineaService.escalarVencido()", e);
        }
        LOG.error("Documento NO transmitido dentro del plazo legal: " + registro.getClave()
                + " (tipo " + registro.getTipoDocumento()
                + ", consecutivo " + registro.getConsecutivo()
                + ", situacion " + registro.getSituacion()
                + ", origen " + registro.getOrigen() + ")"
                + " | source=EnvioFueraLineaService.escalarVencido()"
                + " | despues=" + motivo
                + ". Art. 21 parrafo 3 incumplido: requiere envio manual a Hacienda");
        notificarVencimiento(registro, motivo);
    }

    private void marcarComprobanteComoAceptado(@Nonnull EnvioFueraLinea registro, @Nonnull LocalDateTime ahora) {
        Long id = registro.getComprobanteId();
        if (id == null) {
            return;
        }
        try {
            ComprobantesEmitidos comprobante = em.find(ComprobantesEmitidos.class, id);
            if (comprobante == null) {
                LOG.warn("Documento aceptado sin comprobante local asociado: " + registro.getClave()
                        + " (id " + id + ")"
                        + " | source=EnvioFueraLineaService.marcarComprobanteComoAceptado()"
                        + " | despues=el buzon consta ENVIADO igual");
                return;
            }
            comprobante.setHaciendaEstado("ACEPTADO");
            comprobante.setHaciendaFechaEnvio(ahora);
            comprobante.setHaciendaFechaRespuesta(ahora);
            if (comprobante.getEncabezado() != null) {
                comprobante.getEncabezado().setEstado("ACEPTADO");
            }
            em.merge(comprobante);
            em.flush();
        } catch (RuntimeException e) {
            LOG.warn("No se pudo marcar ACEPTADO el comprobante " + id + ": " + e.getMessage()
                    + " | source=EnvioFueraLineaService.marcarComprobanteComoAceptado()"
                    + " | despues=la fila del buzon ya consta ENVIADO");
        }
    }

    /**
     * Escalation notice, sent whenever an address is configured. It ignores the
     * {@code notificarRechazos} flag on purpose: that flag governs rejection
     * digests, while a missed Art. 21 deadline is a legal breach the operator has
     * to see. The per-document ERROR line above remains the audit trail when no
     * address is configured.
     */
    private void notificarVencimiento(@Nonnull EnvioFueraLinea registro, @Nonnull String motivo) {
        try {
            ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
            if (settings == null) {
                return;
            }
            String destino = settings.getCorreoNotificaciones();
            if (destino == null || destino.isBlank()) {
                return;
            }
            String cuerpo = "No se pudo transmitir el comprobante electronico dentro del plazo legal "
                    + "de dos dias habiles (Art. 21 parrafo 3, Ley 6828).\n\n"
                    + "Clave: " + registro.getClave() + "\n"
                    + "Tipo: " + registro.getTipoDocumento() + "\n"
                    + "Consecutivo: " + registro.getConsecutivo() + "\n"
                    + "Sucursal/Terminal: " + registro.getSucursal() + "/" + registro.getTerminal() + "\n"
                    + "Situacion: " + registro.getSituacion() + "\n"
                    + "Intentos: " + intentosDe(registro) + "\n"
                    + "Motivo: " + motivo + "\n\n"
                    + "Debe enviarse manualmente a Hacienda antes de regularizar la infraccion.";
            emailService.sendEmails(List.of(destino),
                    "URGENTE: comprobante electronico sin enviar (Art. 21) - " + registro.getClave(),
                    cuerpo,
                    settings.getCorreoElectronico(), settings.getContrasenaCorreo(),
                    resultado -> LOG.info("aviso de documento vencido enviado: " + resultado));
        } catch (RuntimeException e) {
            LOG.warn("No se pudo enviar el aviso de documento vencido: " + e.getMessage()
                    + " | source=EnvioFueraLineaService.notificarVencimiento()"
                    + " | despues=queda el log ERROR como evidencia");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Queries
    // ═════════════════════════════════════════════════════════════════════

    /** The row for a clave, or {@code null} when there is none. */
    public @Nullable EnvioFueraLinea buscarPorClave(@Nonnull String clave) {
        try {
            return em.createQuery("SELECT e FROM EnvioFueraLinea e WHERE e.clave = :clave",
                            EnvioFueraLinea.class)
                    .setParameter("clave", clave)
                    .setMaxResults(1)
                    .getSingleResultOrNull();
        } catch (PersistenceException e) {
            LOG.warn("Error buscando el documento encolado por clave: " + e.getMessage()
                    + " | source=EnvioFueraLineaService.buscarPorClave()"
                    + " | despues=se intentara insertar y la clave unica lo impedira");
            return null;
        }
    }

    /**
     * Rows due for a transmission attempt, soonest deadline first. Already-overdue
     * rows are included so the deadline branch of
     * {@link #reintentar(EnvioFueraLinea, LocalDateTime)} can escalate them.
     */
    public @Nonnull List<EnvioFueraLinea> findListosParaReintento(@Nonnull LocalDateTime ahora) {
        try {
            return em.createQuery(
                            "SELECT e FROM EnvioFueraLinea e WHERE e.estado = :estado "
                                    + "AND (e.proximoIntento IS NULL OR e.proximoIntento <= :ahora) "
                                    + "ORDER BY e.vencimiento ASC, e.proximoIntento ASC",
                            EnvioFueraLinea.class)
                    .setParameter("estado", ESTADO_PENDIENTE)
                    .setParameter("ahora", ahora)
                    .setMaxResults(LOTE_MAXIMO)
                    .getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listando documentos encolados: " + e.getMessage()
                    + " | source=EnvioFueraLineaService.findListosParaReintento()"
                    + " | despues=se reintenta en la proxima ejecucion");
            return Collections.emptyList();
        }
    }

    /** Every row still inside the legal window, soonest deadline first. */
    public @Nonnull List<EnvioFueraLinea> findPendientes() {
        try {
            return em.createQuery(
                            "SELECT e FROM EnvioFueraLinea e WHERE e.estado = :estado "
                                    + "ORDER BY e.vencimiento ASC",
                            EnvioFueraLinea.class)
                    .setParameter("estado", ESTADO_PENDIENTE)
                    .getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listando documentos pendientes de envio: " + e.getMessage()
                    + " | source=EnvioFueraLineaService.findPendientes()");
            return Collections.emptyList();
        }
    }

    /** How many documents already breached the Art. 21 ¶3 deadline. */
    public long contarVencidos() {
        try {
            return em.createQuery("SELECT COUNT(e) FROM EnvioFueraLinea e WHERE e.estado = :estado",
                            Long.class)
                    .setParameter("estado", ESTADO_VENCIDO)
                    .getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error contando documentos vencidos: " + e.getMessage()
                    + " | source=EnvioFueraLineaService.contarVencidos()");
            return 0L;
        }
    }
}
