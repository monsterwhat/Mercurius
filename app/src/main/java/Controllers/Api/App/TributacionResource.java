package Controllers.Api.App;

import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Models.DTO.ApiResponse;
import Models.DTO.DeclaracionIVARowDTO;
import Models.DTO.HaciendaDashboardDTO;
import Models.DTO.MensajeReceptorDTO;
import Models.Encabezado.Encabezado;
import Models.NotaCredito;
import Services.ClientService;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import Services.HaciendaServiceFacade;
import Services.LoginService;
import Services.NotaCreditoService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Tributación module for the NEW Qute/HTMX app surface (plan task T28):
 * JSON API + HTMX action endpoints replacing the legacy JSF quartet
 * {@code Controllers.Tributacion.TributacionController} (impuestos por
 * período), {@code Controllers.HaciendaDashboardController} (KPIs),
 * {@code Controllers.DeclaracionIVAController} (D-104) and the
 * Hacienda-consultas flows of {@code Controllers.ConsultasController}
 * (p:poll countdown → hx-trigger="every 5s", bulk send, correct-rejected).
 *
 * <p><b>Behavior parity contract</b> (ported from the legacy beans, receipts
 * in .omo/evidence/t28/):</p>
 * <ul>
 *   <li>Dashboard counters ({@link #dashboard()}): ACEPTADO / RECHAZADO by
 *       case-insensitive {@code haciendaEstado}; PENDIENTE = null, empty or
 *       "PENDIENTE" — exactly
 *       {@code HaciendaDashboardController.cargarDashboard()}.</li>
 *   <li>Countdown ({@link #countdown()}): {@code ReportesProgramadosService
 *       .findNextScheduledReport()}; display "Próximo envío: {date}" or
 *       "No hay envíos programados"; HH:mm:ss diff clamped at 00:00:00 when
 *       past — exactly {@code ConsultasController.actualizarCountdown()} /
 *       {@code getProximoEnvioDisplay()}.</li>
 *   <li>Bulk send ({@link #enviarPendientes}): iterates
 *       {@link ComprobantesEmitidosService#findFacturasPendientes()};
 *       missing clave counts as fallida; success → haciendaEstado/encabezado
 *       estado = ACEPTADO + fechas de envío/respuesta; failure → encabezado
 *       RECHAZADO + motivoRechazo; audit alert written. DELIBERATE DEVIATION:
 *       the legacy {@code CompletableFuture.runAsync} fire-and-forget is run
 *       synchronously so the HTTP response can carry the enviadas/fallidas
 *       summary (the legacy async lambda dereferenced FacesContext off-thread
 *       and its result was unobservable). Message text parity kept.</li>
 *   <li>Correct-rejected ({@link #corregirRechazada}): audit alert +
 *       idempotent automatic credit note via
 *       {@link NotaCreditoService} (skipped when one already exists,
 *       legacy {@code crearNotaCreditoAutomatica} parity) and a
 *       {@code CORREGIR_{id}} token. DEVIATION: the legacy cart pre-cloning
 *       ({@code clonarFacturaACarrito}) needs the POS session cart owned by
 *       T37; this endpoint emits the {@code HX-Trigger: mercurius:corregir}
 *       event with the token instead, and T37's POS will consume it.</li>
 *   <li>Declaración D-104 ({@link #declaracionResumen}): period window
 *       [first day 00:00, last day 23:59:59]; ventas/débito from emitidas
 *       resumen.totalVentaNeta/totalImpuesto; compras/crédito from recibidas;
 *       ivaNeto = débito − crédito — exactly
 *       {@code DeclaracionIVAController.calcular()} — resolved through
 *       {@link ComprobantesEmitidosService#listByDateRange(Date, Date)}
 *       (Date→LocalDateTime binding) plus the recibidos twin.</li>
 *   <li>Mensajes Receptor ({@link #mensajesReceptor}): read-side
 *       {@link MensajeReceptorDTO} view over
 *       {@link ComprobantesRecibidosService#listAll()} with the deadline
 *       indicator states ATENDIDO / VENCIDO / POR_VENCER / EN_TIEMPO derived
 *       from {@code isMensajeReceptorVencido()} /
 *       {@code getDiasRestantesMensajeReceptor()}.</li>
 * </ul>
 *
 * <p><b>Authorization:</b> {@code admin} or {@code tributacion}, mirroring
 * web.xml's "Tributacion Area" constraint on /secured/pages/Tributacion/*.
 * The /api/app/* surface additionally requires any authenticated user through
 * the T13 permission policy; mutating POSTs are CSRF-gated by quarkus-rest-csrf.</p>
 *
 * <p><b>NO direct Hacienda network calls</b>: every submission goes through
 * {@link HaciendaServiceFacade}, which tests replace with
 * {@code @InjectMock} stubs (no real network ever).</p>
 */
@Path("/api/app/tributacion")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "tributacion"})
@Tag(name = "App - Tributación")
public class TributacionResource {

    private static final Logger LOG = Logger.getLogger(TributacionResource.class);

    /** Legacy p:poll interval on Consultas/index.xhtml was 5 seconds. */
    public static final String COUNTDOWN_POLL_INTERVAL = "every 5s";

    /** Display cap for the Mensajes Receptor tab (legacy page had none; bounded for safety). */
    private static final int MENSAJES_LIMIT = 100;

    /** Legacy p:inputNumber bounds on the D-104 year field. */
    private static final int ANIO_MIN = 2020;
    private static final int ANIO_MAX = 2040;

    private static final String[] MESES = {
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Setiembre", "Octubre", "Noviembre", "Diciembre"
    };

    @Nonnull
    @Inject
    ComprobantesEmitidosService emitidosService;

    @Nonnull
    @Inject
    ComprobantesRecibidosService recibidosService;

    @Nonnull
    @Inject
    NotaCreditoService notaCreditoService;

    @Nonnull
    @Inject
    Services.Correos.ReportesProgramadosService reportesProgramadosService;

    @Nonnull
    @Inject
    HaciendaServiceFacade haciendaFacade;

    @Nonnull
    @Inject
    LoginService loginService;

    @Nonnull
    @Inject
    ClientService clientService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request headers (quarkus-rest injectable) — source of HX-Request. */
    @Context
    @Nonnull
    HttpHeaders httpHeaders;

    /**
     * Same root-path the _kit fragments resolve via
     * {config:['quarkus.http.root-path']} in Qute; needed here because the
     * countdown fragment is built in Java, where Qute expressions do not run.
     */
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
    String rootPath;

    // ════════════════════════════════════════════════════════════════════
    // Dashboard (legacy HaciendaDashboardController)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Aggregated KPI counts by Hacienda estado — payload contract is the
     * pre-existing {@link HaciendaDashboardDTO}.
     */
    @GET
    @Path("/dashboard")
    @Operation(summary = "Hacienda dashboard KPI counts (aceptado/rechazado/pendiente)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Counts computed over all comprobantes emitidos"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response dashboard() {
        try {
            List<ComprobantesEmitidos> todos = orEmpty(emitidosService.listAll());
            return Response.ok(ApiResponse.ok(new HaciendaDashboardDTO(
                    countEstado(todos, "ACEPTADO"),
                    countEstado(todos, "RECHAZADO"),
                    countPendientes(todos),
                    new Date()))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error calculando el dashboard de Hacienda", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "No se pudieron cargar los datos del dashboard"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Consultas (legacy ConsultasController — Hacienda portion)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Countdown + bucket counters feed of the consultas page. With the
     * {@code HX-Request} header returns ONLY the polling fragment (the
     * countdown span carrying {@code hx-trigger="every 5s"} plus an
     * out-of-band refresh of the stat counters); otherwise JSON.
     */
    @GET
    @Path("/consultas/countdown")
    @Operation(summary = "Polling countdown to the next scheduled report (+OOB counters)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment (HX-Request) or JSON envelope"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response countdown() {
        try {
            CountdownDTO dto = buildCountdown();
            if (isHxRequest()) {
                return htmlOk(countdownFragment(dto));
            }
            return Response.ok(ApiResponse.ok(dto)).build();
        } catch (RuntimeException e) {
            LOG.warn("Error calculando la cuenta regresiva de envíos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error calculando la cuenta regresiva"))
                    .build();
        }
    }

    /**
     * Bucket rows for the three consultas tabs. {@code bucket} is one of
     * {@code pendientes|aceptadas|rechazadas} backed by the exact legacy
     * service queries findFacturas{Pendientes,Aceptadas,Rechazadas}().
     */
    @GET
    @Path("/consultas/facturas")
    @Operation(summary = "Consultas bucket rows (pendientes|aceptadas|rechazadas)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paged bucket rows"),
        @APIResponse(responseCode = "400", description = "Unknown bucket"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response facturas(
            @QueryParam("bucket") @DefaultValue("pendientes") String bucket,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("q") @Nullable String q) {
        try {
            List<Map<String, Object>> rows = bucketRows(bucket, q);
            int safeSize = Math.min(Math.max(size, 1), 100);
            int totalPages = (int) Math.max(1L, ((long) rows.size() + safeSize - 1) / safeSize);
            int safePage = Math.min(Math.max(page, 1), totalPages);
            int from = Math.min((safePage - 1) * safeSize, rows.size());
            int to = Math.min(from + safeSize, rows.size());
            return Response.ok(new PagedBucket(rows.subList(from, to), rows.size(),
                    safePage, safeSize, totalPages, bucket)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", e.getMessage())).build();
        } catch (RuntimeException e) {
            LOG.warn("Error listando el bucket " + bucket, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando las facturas"))
                    .build();
        }
    }

    /**
     * Bulk-send of every pending factura through
     * {@link HaciendaServiceFacade#submitDocument} — services only, never a
     * direct network call (tests stub the facade).
     */
    @POST
    @Path("/consultas/enviar-pendientes")
    @Operation(summary = "Bulk-send pending comprobantes to Hacienda via the facade")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Summary of sent/failed (or refreshed fragment + toast)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response enviarPendientes() {
        try {
            List<ComprobantesEmitidos> pendientes = orEmpty(emitidosService.findFacturasPendientes());
            if (pendientes.isEmpty()) {
                BulkSendResult resultado = new BulkSendResult(0, 0, 0,
                        "Sin pendientes: No hay facturas pendientes de envio", "warn");
                if (isHxRequest()) {
                    return hxRedirect("/app/tributacion/consultas");
                }
                return Response.ok(ApiResponse.ok(resultado)).build();
            }

            int enviadas = 0;
            int fallidas = 0;
            for (ComprobantesEmitidos factura : pendientes) {
                String clave = factura.getHaciendaClave();
                if (clave == null || clave.isEmpty()) {
                    fallidas++;
                    continue;
                }
                try {
                    HaciendaServiceFacade.SubmitResult result = haciendaFacade.submitDocument(factura);
                    if (result.success) {
                        factura.setHaciendaEstado("ACEPTADO");
                        factura.setHaciendaFechaEnvio(LocalDateTime.now());
                        factura.setHaciendaFechaRespuesta(LocalDateTime.now());
                        if (factura.getEncabezado() != null) {
                            factura.getEncabezado().setEstado("ACEPTADO");
                        }
                        emitidosService.update(factura);
                        enviadas++;
                    } else {
                        factura.setHaciendaEstado("RECHAZADO");
                        factura.setHaciendaFechaRespuesta(LocalDateTime.now());
                        if (factura.getEncabezado() != null) {
                            factura.getEncabezado().setEstado("RECHAZADO");
                            factura.getEncabezado().setMotivoRechazo(result.errorMessage);
                        }
                        emitidosService.update(factura);
                        fallidas++;
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Fallo enviando comprobante " + factura.getId(), e);
                    factura.setHaciendaEstado("RECHAZADO");
                    factura.setHaciendaFechaRespuesta(LocalDateTime.now());
                    if (factura.getEncabezado() != null) {
                        factura.getEncabezado().setEstado("RECHAZADO");
                        factura.getEncabezado().setMotivoRechazo(e.getMessage());
                    }
                    try {
                        emitidosService.update(factura);
                    } catch (RuntimeException updateEx) {
                        LOG.warn("Error actualizando factura tras fallo " + factura.getId(), updateEx);
                    }
                    fallidas++;
                }
            }

                        LOG.info("Envio masivo de facturas: " + enviadas + " enviadas, " + fallidas
                            + " fallidas de " + pendientes.size() + " | user=" + String.valueOf(currentUser()) + " | source=" + "TributacionResource.enviarPendientes" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

            BulkSendResult resultado = new BulkSendResult(pendientes.size(), enviadas, fallidas,
                    "Envio completado: Enviadas: " + enviadas + ", Fallidas: " + fallidas
                            + " de " + pendientes.size(),
                    fallidas > 0 ? "warning" : "success");
            if (isHxRequest()) {
                return hxRedirect("/app/tributacion/consultas");
            }
            return Response.ok(ApiResponse.ok(resultado)).build();
        } catch (RuntimeException e) {
            LOG.warn("Error en el envío masivo de pendientes", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error en el envío masivo"))
                    .build();
        }
    }

    /**
     * Correct-rejected action for one RECHAZADO comprobante: writes the audit
     * alert, creates the automatic credit note when none exists yet and hands
     * back the {@code CORREGIR_{id}} correction token (HX callers additionally
     * get the {@code HX-Trigger: mercurius:corregir} event).
     */
    @POST
    @Path("/consultas/{id}/corregir")
    @Operation(summary = "Correct a rejected comprobante (auto credit note + correction token)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Correction prepared"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "409", description = "Comprobante is not RECHAZADO"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response corregirRechazada(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos factura = emitidosService.find(id);
            if (factura == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No se encontró la factura solicitada"))
                        .build();
            }
            boolean rechazada = factura.getEncabezado() != null
                    && "RECHAZADO".equalsIgnoreCase(factura.getEncabezado().getEstado());
            if (!rechazada) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(ApiResponse.error("NOT_REJECTED",
                                "Solo se pueden corregir facturas rechazadas por Hacienda"))
                        .build();
            }

            String consecutivo = factura.getEncabezado().getNumeroConsecutivo();
                        LOG.info("Se inició la corrección de la factura rechazada: " + consecutivo + " | user=" + String.valueOf(currentUser()) + " | source=" + "TributacionResource.corregirRechazada" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

            boolean notaCreada = crearNotaCreditoAutomatica(factura);

            CorregirResult resultado = new CorregirResult("CORREGIR_" + factura.getId(),
                    notaCreada,
                    "Se ha creado la nota de crédito y se preparó la corrección de la factura. "
                            + "Número original: " + consecutivo);
            if (isHxRequest()) {
                return Response.ok(ApiResponse.ok(resultado))
                        .header("HX-Trigger", "{\"mercurius:corregir\":{\"token\":\""
                                + resultado.token() + "\"}}")
                        .build();
            }
            return Response.ok(ApiResponse.ok(resultado)).build();
        } catch (RuntimeException e) {
            LOG.warn("Error corrigiendo la factura " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error al corregir la factura"))
                    .build();
        }
    }

    /**
     * Mensaje Receptor read model over the received invoices with the MR
     * deadline indicator state per row (plan QA scenario "MR deadline
     * indicator states").
     */
    @GET
    @Path("/consultas/mensajes")
    @Operation(summary = "Mensaje Receptor views with deadline indicator states")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "MR rows sorted by deadline"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response mensajesReceptor() {
        try {
            List<MensajeReceptorView> rows = new ArrayList<>();
            for (ComprobantesRecibidos r : orEmpty(recibidosService.listAll())) {
                rows.add(toMensajeView(r));
            }
            rows.sort(Comparator.comparing(v -> v.mensaje().getLimite(),
                    Comparator.nullsLast(Comparator.naturalOrder())));
            List<MensajeReceptorView> capped = rows.size() > MENSAJES_LIMIT
                    ? rows.subList(0, MENSAJES_LIMIT) : rows;
            return Response.ok(ApiResponse.ok(new MensajesResponse(capped.size(), capped))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error listando los mensajes receptor", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando los mensajes receptor"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Declaración IVA D-104 (legacy DeclaracionIVAController)
    // ════════════════════════════════════════════════════════════════════

    /**
     * D-104 summary for one fiscal period plus the twelve monthly
     * {@link DeclaracionIVARowDTO} filas of the requested year. Defaults to
     * the current month/year like the legacy {@code init()}; validates the
     * legacy p:inputNumber/selectOneMenu bounds (mes 1-12, anio 2020-2040).
     */
    @GET
    @Path("/declaracion/resumen")
    @Operation(summary = "Declaración IVA (D-104) period summary + monthly rows")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Period summary and monthly filas"),
        @APIResponse(responseCode = "400", description = "Period out of the legacy bounds"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response declaracionResumen(
            @QueryParam("mes") @Nullable Integer mes,
            @QueryParam("anio") @Nullable Integer anio) {
        try {
            LocalDate hoy = LocalDate.now();
            int m = mes == null ? hoy.getMonthValue() : mes;
            int y = anio == null ? hoy.getYear() : anio;
            if (m < 1 || m > 12) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR", "El mes debe estar entre 1 y 12"))
                        .build();
            }
            if (y < ANIO_MIN || y > ANIO_MAX) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El año debe estar entre " + ANIO_MIN + " y " + ANIO_MAX))
                        .build();
            }

            LocalDate inicio = LocalDate.of(y, m, 1);
            LocalDate finMes = inicio.plusMonths(1).minusDays(1);
            Date start = Date.from(inicio.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date end = Date.from(finMes.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant());

            List<ComprobantesEmitidos> emitidas = orEmpty(emitidosService.listByDateRange(start, end));
            List<ComprobantesRecibidos> recibidas = orEmpty(recibidosService.listByDateRange(start, end));

            BigDecimal totalVentas = BigDecimal.ZERO;
            BigDecimal ivaDebito = BigDecimal.ZERO;
            for (ComprobantesEmitidos f : emitidas) {
                if (f.getResumen() != null) {
                    if (f.getResumen().getTotalVentaNeta() != null) {
                        totalVentas = totalVentas.add(f.getResumen().getTotalVentaNeta());
                    }
                    if (f.getResumen().getTotalImpuesto() != null) {
                        ivaDebito = ivaDebito.add(f.getResumen().getTotalImpuesto());
                    }
                }
            }

            BigDecimal totalCompras = BigDecimal.ZERO;
            BigDecimal ivaCredito = BigDecimal.ZERO;
            for (ComprobantesRecibidos f : recibidas) {
                if (f.getResumen() != null) {
                    if (f.getResumen().getTotalVentaNeta() != null) {
                        totalCompras = totalCompras.add(f.getResumen().getTotalVentaNeta());
                    }
                    if (f.getResumen().getTotalImpuesto() != null) {
                        ivaCredito = ivaCredito.add(f.getResumen().getTotalImpuesto());
                    }
                }
            }

            // Monthly filas for the whole year: ONE wide window, grouped in
            // memory (the fixed Date→LocalDateTime binding makes the single
            // range query authoritative for both the period and the filas).
            Date yearStart = Date.from(LocalDate.of(y, 1, 1)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date yearEnd = Date.from(LocalDate.of(y, 12, 31).atTime(23, 59, 59)
                    .atZone(ZoneId.systemDefault()).toInstant());
            List<DeclaracionIVARowDTO> filas = monthlyFilas(
                    orEmpty(emitidosService.listByDateRange(yearStart, yearEnd)), y);

            DeclaracionResumen resumen = new DeclaracionResumen(
                    nombreMes(m) + " " + y, m, y,
                    totalVentas, totalCompras, ivaDebito, ivaCredito,
                    ivaDebito.subtract(ivaCredito),
                    emitidas.size(), recibidas.size(), filas);
            return Response.ok(ApiResponse.ok(resumen)).build();
        } catch (RuntimeException e) {
            LOG.warn("Error al calcular declaracion IVA: " + e.getMessage() + " | user=" + String.valueOf(currentUser()) + " | source=TributacionResource.declaracionResumen | antes=null | despues=" + e.getMessage(), e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error al calcular la declaración IVA"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Shared helpers
    // ════════════════════════════════════════════════════════════════════

    private boolean isHxRequest() {
        String header = httpHeaders.getHeaderString("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /** Legacy counter: case-insensitive estado match. */
    private static int countEstado(@Nonnull List<ComprobantesEmitidos> todos, @Nonnull String estado) {
        return (int) todos.stream()
                .filter(c -> estado.equalsIgnoreCase(c.getHaciendaEstado()))
                .count();
    }

    /** Legacy counter: null, empty or PENDIENTE estados are pending. */
    private static int countPendientes(@Nonnull List<ComprobantesEmitidos> todos) {
        return (int) todos.stream()
                .filter(c -> c.getHaciendaEstado() == null
                        || "PENDIENTE".equalsIgnoreCase(c.getHaciendaEstado())
                        || c.getHaciendaEstado().isEmpty())
                .count();
    }

    private CountdownDTO buildCountdown() {
        Models.Correos.ReporteProgramado proximo = reportesProgramadosService.findNextScheduledReport();
        Date nextScheduledTime = proximo != null ? proximo.getNextRunTime() : null;

        String display;
        if (proximo != null && nextScheduledTime != null) {
            long diff = nextScheduledTime.getTime() - System.currentTimeMillis();
            long seconds = Math.max(0, diff) / 1000;
            display = String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        } else {
            display = "00:00:00";
        }
        String proximoDisplay = proximo != null && nextScheduledTime != null
                ? "Próximo envío: " + nextScheduledTime
                : "No hay envíos programados";

        List<ComprobantesEmitidos> pendientes = orEmpty(emitidosService.findFacturasPendientes());
        List<ComprobantesEmitidos> aceptadas = orEmpty(emitidosService.findFacturasAceptadas());
        List<ComprobantesEmitidos> rechazadas = orEmpty(emitidosService.findFacturasRechazadas());

        return new CountdownDTO(proximo != null, proximoDisplay, display,
                (long) pendientes.size(), (long) aceptadas.size(), (long) rechazadas.size());
    }

    /**
     * Polling fragment: the countdown span (re-emitting its own
     * hx-get/hx-trigger so the every-5s poll survives each outerHTML swap)
     * plus an out-of-band counter refresh (ui-kit §3.5).
     */
    private String countdownFragment(@Nonnull CountdownDTO dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("<span id=\"consultas-countdown\" class=\"is-family-monospace has-text-weight-bold\"")
                .append(" data-countdown-display=\"").append(escape(dto.countdownDisplay())).append("\"")
                .append(" title=\"").append(escape(dto.proximoEnvioDisplay())).append("\"")
                .append(" hx-get=\"").append(rootPath).append("/api/app/tributacion/consultas/countdown\"")
                .append(" hx-trigger=\"every 5s\"")
                .append(" hx-swap=\"outerHTML\">")
                .append(escape(dto.proximoEnvioDisplay()))
                .append(" — ").append(escape(dto.countdownDisplay()))
                .append("</span>");
        sb.append("<div hx-swap-oob=\"true\" id=\"consultas-contadores\"")
                .append(" class=\"columns is-centered has-text-centered mb-4\">")
                .append("<span data-contador=\"pendientes\">").append(dto.contadorPendientes()).append("</span>")
                .append("<span data-contador=\"aceptadas\">").append(dto.contadorAceptadas()).append("</span>")
                .append("<span data-contador=\"rechazadas\">").append(dto.contadorRechazadas()).append("</span>")
                .append("</div>");
        return sb.toString();
    }

    private static String escape(@Nullable String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Rows for one consultas bucket as template-friendly maps. */
    private List<Map<String, Object>> bucketRows(@Nonnull String bucket, @Nullable String q) {
        List<ComprobantesEmitidos> source;
        switch (bucket == null ? "" : bucket.toLowerCase(Locale.ROOT)) {
            case "pendientes" -> source = orEmpty(emitidosService.findFacturasPendientes());
            case "aceptadas" -> source = orEmpty(emitidosService.findFacturasAceptadas());
            case "rechazadas" -> source = orEmpty(emitidosService.findFacturasRechazadas());
            default -> throw new IllegalArgumentException(
                    "bucket debe ser pendientes, aceptadas o rechazadas");
        }
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComprobantesEmitidos f : source) {
            Map<String, Object> row = filaMap(f);
            if (needle.isEmpty() || filaMatches(row, needle)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> filaMap(@Nonnull ComprobantesEmitidos f) {
        Encabezado enc = f.getEncabezado();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.getId());
        row.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
        row.put("fechaEmision", enc != null ? enc.getFechaEmision() : null);
        row.put("cliente", enc != null && enc.getReceptor() != null ? enc.getReceptor().getNombre() : null);
        row.put("total", f.getResumen() != null ? f.getResumen().getTotalComprobante() : null);
        row.put("motivoRechazo", enc != null ? enc.getMotivoRechazo() : null);
        row.put("intentos", f.getCorrectionAttempts() == null ? 0 : f.getCorrectionAttempts());
        row.put("ultimaCorreccion", f.getUltimaCorreccion());
        row.put("fechaRechazo", f.getHaciendaFechaRespuesta());
        return row;
    }

    private static boolean filaMatches(@Nonnull Map<String, Object> row, @Nonnull String needle) {
        for (Object value : row.values()) {
            if (value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Legacy {@code crearNotaCreditoAutomatica} parity: skip when a note
     * already exists for the comprobante; otherwise create it with the same
     * fields (motivo, monto from totalVentaNeta, client from receptor name,
     * usuario, status, haciendaEstado=PENDIENTE). Returns whether a NEW note
     * was created.
     */
    private boolean crearNotaCreditoAutomatica(@Nonnull ComprobantesEmitidos facturaRechazada) {
        if (facturaRechazada.getResumen() == null || facturaRechazada.getEncabezado() == null) {
            return false;
        }
        List<NotaCredito> existentes = notaCreditoService.listPorComprobante(facturaRechazada.getId());
        if (existentes != null && !existentes.isEmpty()) {
                        LOG.info("Nota de crédito ya existe para factura: " + facturaRechazada.getId() + " | user=" + String.valueOf(currentUser()) + " | source=" + "TributacionResource.crearNotaCreditoAutomatica()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            return false;
        }

        NotaCredito notaCredito = new NotaCredito();
        notaCredito.setComprobanteOriginal(facturaRechazada);
        notaCredito.setFecha(new Date());
        String motivoRechazo = facturaRechazada.getEncabezado().getMotivoRechazo();
        notaCredito.setMotivo("Corrección automática por rechazo de Hacienda: "
                + (motivoRechazo != null ? motivoRechazo : "Sin motivo especificado"));
        notaCredito.setMontoTotal(facturaRechazada.getResumen().getTotalVentaNeta());

        if (facturaRechazada.getEncabezado().getReceptor() != null) {
            String receptorNombre = facturaRechazada.getEncabezado().getReceptor().getNombre();
            if (receptorNombre != null) {
                List<Models.Clients> clients = clientService.searchByName(receptorNombre);
                if (clients != null && !clients.isEmpty()) {
                    notaCredito.setCliente(clients.get(0));
                }
            }
        }

        notaCredito.setUsuario(currentUser() != null ? currentUser().getUsername() : "system");
        notaCredito.setStatus(true);
        notaCredito.setHaciendaEstado("PENDIENTE");
        notaCreditoService.create(notaCredito);

                LOG.info("Nota de crédito creada automáticamente para factura rechazada: "
                        + facturaRechazada.getId() + " | user=" + String.valueOf(currentUser()) + " | source=" + "TributacionResource.crearNotaCreditoAutomatica()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        return true;
    }

    /** MR indicator state derivation (documented in the class javadoc). */
    private static MensajeReceptorView toMensajeView(@Nonnull ComprobantesRecibidos r) {
        Encabezado enc = r.getEncabezado();
        String emisorNombre = enc != null && enc.getEmisor() != null ? enc.getEmisor().getNombre() : null;
        String emisorId = enc != null && enc.getEmisor() != null && enc.getEmisor().getIdentificacion() != null
                ? enc.getEmisor().getIdentificacion().getNumero() : null;
        String receptorNombre = enc != null && enc.getReceptor() != null ? enc.getReceptor().getNombre() : null;
        String receptorId = enc != null && enc.getReceptor() != null && enc.getReceptor().getIdentificacion() != null
                ? enc.getReceptor().getIdentificacion().getNumero() : null;

        MensajeReceptorDTO dto = new MensajeReceptorDTO(
                enc != null ? enc.getNumeroConsecutivo() : null,
                enc != null ? enc.getClave() : null,
                enc != null ? enc.getFechaEmision() : null,
                r.getHaciendaMensajeReceptorEstado(),
                r.getHaciendaMensajeReceptorFecha(),
                r.getMensajeReceptorLimite(),
                r.getResumen() != null ? r.getResumen().getTotalImpuesto() : null,
                r.getResumen() != null ? r.getResumen().getTotalComprobante() : null,
                emisorNombre, emisorId, receptorNombre, receptorId);

        String indicador;
        LocalDate limite = dto.getLimite();
        long diasRestantes = limite == null ? -1 : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), limite);
        boolean vencido = limite != null && LocalDate.now().isAfter(limite);
        if (dto.getEstado() != null && !dto.getEstado().isBlank()) {
            indicador = "ATENDIDO";
        } else if (vencido) {
            indicador = "VENCIDO";
        } else if (diasRestantes >= 0 && diasRestantes <= 2) {
            indicador = "POR_VENCER";
        } else {
            indicador = "EN_TIEMPO";
        }
        return new MensajeReceptorView(dto, indicador, diasRestantes, vencido);
    }

    /** Twelve monthly rows (periodo label + ventas + impuesto sums). */
    private static List<DeclaracionIVARowDTO> monthlyFilas(
            @Nonnull List<ComprobantesEmitidos> emitidasDelAnio, int anio) {
        BigDecimal[] ventasPorMes = new BigDecimal[12];
        BigDecimal[] impuestoPorMes = new BigDecimal[12];
        for (int i = 0; i < 12; i++) {
            ventasPorMes[i] = BigDecimal.ZERO;
            impuestoPorMes[i] = BigDecimal.ZERO;
        }
        for (ComprobantesEmitidos f : emitidasDelAnio) {
            if (f.getResumen() == null || f.getEncabezado() == null
                    || f.getEncabezado().getFechaEmision() == null) {
                continue;
            }
            int mesIdx = f.getEncabezado().getFechaEmision().getMonthValue() - 1;
            if (f.getResumen().getTotalVentaNeta() != null) {
                ventasPorMes[mesIdx] = ventasPorMes[mesIdx].add(f.getResumen().getTotalVentaNeta());
            }
            if (f.getResumen().getTotalImpuesto() != null) {
                impuestoPorMes[mesIdx] = impuestoPorMes[mesIdx].add(f.getResumen().getTotalImpuesto());
            }
        }
        List<DeclaracionIVARowDTO> filas = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            filas.add(new DeclaracionIVARowDTO(nombreMes(i + 1) + " " + anio,
                    ventasPorMes[i], impuestoPorMes[i]));
        }
        return filas;
    }

    private static String nombreMes(int mes) {
        return MESES[mes - 1];
    }

    /**
     * Resolves the authenticated {@link Models.Users} row through the T12
     * identity provider's principal (SessionController.getCurrentUser parity);
     * null for anonymous/system contexts (alertas accepts null).
     */
    private Models.Users currentUser() {
        try {
            if (identity.isAnonymous() || identity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(identity.getPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.debug("No current user resolvable", e);
            return null;
        }
    }

    private static Response htmlOk(@Nonnull String html) {
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /** HTMX redirect: the client navigates and the page re-renders fresh. */
    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    // ── Small value carriers ────────────────────────────────────────────

    /** Payload of GET /consultas/countdown (JSON mode). */
    public record CountdownDTO(boolean hasProximoEnvio, String proximoEnvioDisplay,
                               String countdownDisplay, Long contadorPendientes,
                               Long contadorAceptadas, Long contadorRechazadas) {}

    /** Payload of POST /consultas/enviar-pendientes. */
    public record BulkSendResult(int total, int enviadas, int fallidas,
                                 String mensaje, String severity) {}

    /** Payload of POST /consultas/{id}/corregir. */
    public record CorregirResult(String token, boolean notaCreditoCreada, String mensaje) {}

    /** One Mensajes-Receptor tab row: DTO + derived indicator state. */
    public record MensajeReceptorView(MensajeReceptorDTO mensaje, String indicador,
                                      long diasRestantes, boolean vencido) {}

    /** Payload of GET /consultas/mensajes. */
    public record MensajesResponse(int total, List<MensajeReceptorView> mensajes) {}

    /** Payload of GET /consultas/facturas (bucket-paged envelope). */
    public record PagedBucket(List<Map<String, Object>> data, long total, int page,
                              int size, int totalPages, String bucket) {}

    /** Payload of GET /declaracion/resumen (D-104 period + monthly filas). */
    public record DeclaracionResumen(String periodo, int mes, int anio,
                                     BigDecimal totalVentas, BigDecimal totalCompras,
                                     BigDecimal ivaDebito, BigDecimal ivaCredito,
                                     BigDecimal ivaNeto, int totalFacturasEmitidas,
                                     int totalFacturasRecibidas,
                                     List<DeclaracionIVARowDTO> filas) {}
}
