package Controllers.Api.App.Reportes;

import Controllers.Api.App.TributacionResource;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Models.Encabezado.Encabezado;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import Models.Correos.ReporteProgramado;
import Services.Correos.ReportesProgramadosService;
import io.quarkus.qute.Location;
import org.eclipse.microprofile.openapi.annotations.Operation;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * HTML pages of the Tributación module for the NEW Qute/HTMX app surface
 * (plan task T28): {@code GET /app/tributacion/dashboard},
 * {@code GET /app/tributacion/consultas} and
 * {@code GET /app/tributacion/declaracion} — exactly the routes the T11
 * navbar reserved ({@code data-planned-route}) for the legacy
 * secured/pages/Tributacion/{Dashboard,Consultas,Reportes,Declaracion}
 * xhtml views.
 *
 * <p>Server contract follows docs/ui-kit.md §2.9: every page endpoint checks
 * the {@code HX-Request} header and renders ONLY the requested
 * {@code {#fragment}} section when present (KPI grid, tabbed tables), the
 * whole layout page otherwise. The countdown polling span lives on the
 * consultas page and refreshes itself every 5 seconds against
 * {@link TributacionResource#countdown()} (JSON twin of this module); bulk
 * send / correct-rejected POSTs go to the same JSON resource which answers
 * HTMX callers with {@code HX-Redirect} back to these pages.</p>
 *
 * <p><b>Role gate</b> mirrors web.xml's "Tributacion Area"
 * ({@code admin} + {@code tributacion}). Read-only rendering — no mutation
 * happens here.</p>
 */
@Path("/app/tributacion")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
public class TributacionPagesResource {

    private static final Logger LOG = Logger.getLogger(TributacionPagesResource.class);

    private static final String[] MESES = {
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Setiembre", "Octubre", "Noviembre", "Diciembre"
    };

    /** Legacy p:dataTable rows=15 on the Consultas tabs. */
    private static final int CONSULTAS_PAGE_SIZE = 15;

    /** Legacy p:dataTable rows=10 on the Declaración detail tables. */
    private static final int DECLARACION_DETAIL_LIMIT = 10;

    /** Legacy caps from HaciendaDashboardController.cargarDashboard(). */
    private static final int PENDIENTES_ENVIO_LIMIT = 20;
    private static final int RECHAZADOS_RECIENTES_LIMIT = 10;

    @Inject
    @Nonnull
    ComprobantesEmitidosService emitidosService;

    @Inject
    @Nonnull
    ComprobantesRecibidosService recibidosService;

    @Inject
    @Nonnull
    ReportesProgramadosService reportesProgramadosService;

    @Inject
    @Nonnull
    HttpHeaders httpHeaders;

    @Inject
    @Nonnull
    @Location("pages/tributacion/dashboard")
    Template dashboardPage;

    @Inject
    @Nonnull
    @Location("pages/tributacion/consultas")
    Template consultasPage;

    @Inject
    @Nonnull
    @Location("pages/tributacion/declaracion")
    Template declaracionPage;

    @Inject
    @Nonnull
    @Location("pages/tributacion/index")
    Template indexPage;

    // ════════════════════════════════════════════════════════════════════
    // Landing page (legacy Tributacion/index.xhtml)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Gestión Tributaria landing page — the route the T11 navbar reserved
     * ({@code /app/tributacion}) before the sub-pages landed. Renders
     * {@code pages/tributacion/index.html} with the same invoice counters as
     * the dashboard.
     */
    @GET
    @Operation(summary = "Gestión Tributaria landing page")
    public Response index() {
        try {
            TemplateInstance instance = indexPage.instance();
            indexModel().forEach(instance::data);
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de gestión tributaria", e);
            return serverError("No se pudieron cargar los datos de tributación");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Dashboard Hacienda (legacy Dashboard/index.xhtml)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Dashboard page; with {@code HX-Request} returns only the
     * {@code kpis} fragment (the "Actualizar Dashboard" button swaps it).
     */
    @GET
    @Path("/dashboard")
    @Operation(summary = "Dashboard Hacienda page (HX-Request renders the kpis fragment)")
    public Response dashboard() {
        try {
            TemplateInstance instance = isHxRequest()
                    ? dashboardPage.getFragment("kpis").instance()
                    : dashboardPage.instance();
            dashboardModel().forEach(instance::data);
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando el dashboard de Hacienda", e);
            return serverError("No se pudieron cargar los datos del dashboard");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Consultas (legacy Consultas/index.xhtml)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Consultas page: stat counters, the every-5s countdown polling span,
     * Alpine tab navigation over Pendientes/Aceptadas/Rechazadas/Mensajes.
     * With {@code HX-Request} returns only the {@code tabla} fragment.
     */
    @GET
    @Path("/consultas")
    @Operation(summary = "Consultas page (countdown poll + bucket tabs)")
    public Response consultas(
            @QueryParam("tab") @DefaultValue("pendientes") String tab,
            @QueryParam("q") @Nullable String q) {
        try {
            TemplateInstance instance = isHxRequest()
                    ? consultasPage.getFragment("tabla").instance()
                    : consultasPage.instance();
            consultasModel(tab, q).forEach(instance::data);
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de consultas", e);
            return serverError("No se pudieron cargar los datos de consultas");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Declaración IVA D-104 (legacy DeclaracionIVA/index.xhtml)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Declaración page for one fiscal period (defaults to the current
     * month/year like the legacy bean init()). Plain GET form navigation
     * (ui-kit §5 Pattern B — no dialog interactivity worth boosting).
     */
    @GET
    @Path("/declaracion")
    @Operation(summary = "Declaración IVA (D-104) page for a fiscal period")
    public Response declaracion(
            @QueryParam("mes") @Nullable Integer mes,
            @QueryParam("anio") @Nullable Integer anio) {
        try {
            LocalDate hoy = LocalDate.now();
            int m = mes == null ? hoy.getMonthValue() : Math.min(Math.max(mes, 1), 12);
            int y = anio == null ? hoy.getYear() : anio;
            TemplateInstance instance = isHxRequest()
                    ? declaracionPage.getFragment("resumen").instance()
                    : declaracionPage.instance();
            declaracionModel(m, y).forEach(instance::data);
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la declaración IVA", e);
            return serverError("Error al calcular la declaración IVA");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Page models
    // ════════════════════════════════════════════════════════════════════

    /**
     * Landing model — the same invoice counters as the dashboard KPIs
     * (contract of pages/tributacion/index.html).
     */
    private Map<String, Object> indexModel() {
        List<ComprobantesEmitidos> todos = orEmpty(emitidosService.listAll());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("totalFacturas", (long) todos.size());
        model.put("totalEnviadas", countEstado(todos, "ENVIADO"));
        model.put("totalAceptadas", countEstado(todos, "ACEPTADO"));
        model.put("totalRechazadas", countEstado(todos, "RECHAZADO"));
        model.put("baseUrl", "/app/tributacion");
        return model;
    }

    /**
     * Dashboard model — counter/monto/tasa computations are a 1:1 port of
     * {@code HaciendaDashboardController.cargarDashboard()} (see the JSON
     * twin {@link TributacionResource#dashboard()} for the DTO contract).
     */
    private Map<String, Object> dashboardModel() {
        List<ComprobantesEmitidos> todos = orEmpty(emitidosService.listAll());

        long totalEmitidos = todos.size();
        long totalAceptados = countEstado(todos, "ACEPTADO");
        long totalRechazados = countEstado(todos, "RECHAZADO");
        long totalPendientes = countPendientes(todos);
        long totalEnviadosSinRespuesta = countEstado(todos, "ENVIADO");

        LocalDate hoy = LocalDate.now();
        LocalDateTime inicioHoy = hoy.atStartOfDay();
        LocalDateTime finHoy = hoy.plusDays(1).atStartOfDay();

        long emitidosHoy = todos.stream()
                .filter(c -> c.getEncabezado() != null && c.getEncabezado().getFechaEmision() != null)
                .filter(c -> !c.getEncabezado().getFechaEmision().isBefore(inicioHoy)
                        && c.getEncabezado().getFechaEmision().isBefore(finHoy))
                .count();
        long aceptadosHoy = todos.stream()
                .filter(c -> "ACEPTADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .filter(c -> c.getHaciendaFechaRespuesta() != null)
                .filter(c -> !c.getHaciendaFechaRespuesta().isBefore(inicioHoy)
                        && c.getHaciendaFechaRespuesta().isBefore(finHoy))
                .count();
        long rechazadosHoy = todos.stream()
                .filter(c -> "RECHAZADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .filter(c -> c.getHaciendaFechaRespuesta() != null)
                .filter(c -> !c.getHaciendaFechaRespuesta().isBefore(inicioHoy)
                        && c.getHaciendaFechaRespuesta().isBefore(finHoy))
                .count();

        BigDecimal montoAceptados = todos.stream()
                .filter(c -> "ACEPTADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .filter(c -> c.getResumen() != null && c.getResumen().getTotalComprobante() != null)
                .map(c -> c.getResumen().getTotalComprobante())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal montoPendientes = todos.stream()
                .filter(c -> c.getHaciendaEstado() == null
                        || "PENDIENTE".equalsIgnoreCase(c.getHaciendaEstado())
                        || c.getHaciendaEstado().isEmpty())
                .filter(c -> c.getResumen() != null && c.getResumen().getTotalComprobante() != null)
                .map(c -> c.getResumen().getTotalComprobante())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double tasaExito = totalEmitidos == 0 ? 100.0
                : Math.round(((double) totalAceptados / totalEmitidos) * 1000.0) / 10.0;

        // Últimos 7 días (legacy DailyStat rows).
        List<Map<String, Object>> dailyStats = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate dia = hoy.minusDays(i);
            LocalDateTime inicio = dia.atStartOfDay();
            LocalDateTime fin = dia.plusDays(1).atStartOfDay();
            long diaEmitidos = todos.stream()
                    .filter(c -> c.getEncabezado() != null && c.getEncabezado().getFechaEmision() != null)
                    .filter(c -> !c.getEncabezado().getFechaEmision().isBefore(inicio)
                            && c.getEncabezado().getFechaEmision().isBefore(fin))
                    .count();
            long diaAceptados = todos.stream()
                    .filter(c -> c.getHaciendaFechaRespuesta() != null)
                    .filter(c -> !c.getHaciendaFechaRespuesta().isBefore(inicio)
                            && c.getHaciendaFechaRespuesta().isBefore(fin))
                    .filter(c -> "ACEPTADO".equalsIgnoreCase(c.getHaciendaEstado()))
                    .count();
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("dia", dia.format(DateTimeFormatter.ofPattern("dd/MM")));
            fila.put("emitidos", diaEmitidos);
            fila.put("aceptados", diaAceptados);
            fila.put("tasa", diaEmitidos > 0 ? (int) (diaAceptados * 100 / diaEmitidos) : 0);
            dailyStats.add(fila);
        }

        List<Map<String, Object>> pendientesEnvio = todos.stream()
                .filter(c -> c.getHaciendaEstado() == null
                        || "PENDIENTE".equalsIgnoreCase(c.getHaciendaEstado())
                        || c.getHaciendaEstado().isEmpty())
                .sorted(Comparator.comparing(
                        (ComprobantesEmitidos c) -> c.getEncabezado() != null
                                ? c.getEncabezado().getFechaEmision() : LocalDateTime.MIN,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PENDIENTES_ENVIO_LIMIT)
                .map(TributacionPagesResource::filaMap)
                .toList();

        List<Map<String, Object>> rechazadosRecientes = todos.stream()
                .filter(c -> "RECHAZADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .sorted(Comparator.comparing(ComprobantesEmitidos::getHaciendaFechaRespuesta,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECHAZADOS_RECIENTES_LIMIT)
                .map(TributacionPagesResource::filaMap)
                .toList();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Dashboard Hacienda");
        model.put("totalEmitidos", totalEmitidos);
        model.put("totalAceptados", totalAceptados);
        model.put("totalRechazados", totalRechazados);
        model.put("totalPendientes", totalPendientes);
        model.put("totalEnviadosSinRespuesta", totalEnviadosSinRespuesta);
        model.put("emitidosHoy", emitidosHoy);
        model.put("aceptadosHoy", aceptadosHoy);
        model.put("rechazadosHoy", rechazadosHoy);
        model.put("montoAceptados", colones(montoAceptados));
        model.put("montoPendientes", colones(montoPendientes));
        model.put("tasaExito", tasaExito);
        model.put("dailyStats", dailyStats);
        model.put("pendientesEnvio", pendientesEnvio);
        model.put("rechazadosRecientes", rechazadosRecientes);
        // Bounded legacy lists: label-only headers (null key ⇒ non-sortable,
        // docs/ui-kit.md §3.1) — no pager needed for capped snapshots.
        model.put("pendingHeaders", List.of(
                Map.of("label", "Número Consecutivo"),
                Map.of("label", "Fecha Emisión"),
                Map.of("label", "Cliente"),
                Map.of("label", "Total"),
                Map.of("label", "Estado")));
        model.put("rejectedHeaders", List.of(
                Map.of("label", "Número Consecutivo"),
                Map.of("label", "Fecha Rechazo"),
                Map.of("label", "Motivo Rechazo"),
                Map.of("label", "Intentos"),
                Map.of("label", "Estado")));
        model.put("baseUrl", "/app/tributacion/dashboard");
        return model;
    }

    /** Consultas model: counters + countdown seed + first page of each bucket. */
    private Map<String, Object> consultasModel(@Nonnull String tab, @Nullable String q) {
        List<ComprobantesEmitidos> pendientes = orEmpty(emitidosService.findFacturasPendientes());
        List<ComprobantesEmitidos> aceptadas = orEmpty(emitidosService.findFacturasAceptadas());
        List<ComprobantesEmitidos> rechazadas = orEmpty(emitidosService.findFacturasRechazadas());

        ReporteProgramado proximo = reportesProgramadosService.findNextScheduledReport();
        Date nextScheduledTime = proximo != null ? proximo.getNextRunTime() : null;
        String countdownDisplay = "00:00:00";
        String proximoEnvioDisplay = "No hay envíos programados";
        boolean hasProximoEnvio = false;
        if (proximo != null && nextScheduledTime != null) {
            hasProximoEnvio = true;
            long diff = nextScheduledTime.getTime() - System.currentTimeMillis();
            long seconds = Math.max(0, diff) / 1000;
            countdownDisplay = String.format("%02d:%02d:%02d",
                    seconds / 3600, (seconds % 3600) / 60, seconds % 60);
            proximoEnvioDisplay = "Próximo envío: " + nextScheduledTime;
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Consultas de Facturas");
        model.put("tabActiva", tab);
        model.put("contadorPendientes", (long) pendientes.size());
        model.put("contadorAceptadas", (long) aceptadas.size());
        model.put("contadorRechazadas", (long) rechazadas.size());
        model.put("hasProximoEnvio", hasProximoEnvio);
        model.put("proximoEnvioDisplay", proximoEnvioDisplay);
        model.put("countdownDisplay", countdownDisplay);
        model.put("pollInterval", TributacionResource.COUNTDOWN_POLL_INTERVAL);
        model.put("q", q == null ? "" : q);
        model.put("baseUrlTabla", "/api/app/tributacion/consultas/facturas");
        // Label-only headers: the bounded first-page snapshots are not
        // server-sorted (docs/ui-kit.md §3.1 null key ⇒ non-sortable).
        model.put("headersConsulta", List.of(
                Map.of("label", "Número Consecutivo"),
                Map.of("label", "Fecha Emisión"),
                Map.of("label", "Cliente"),
                Map.of("label", "Total"),
                Map.of("label", "Estado")));
        model.put("headersRechazadas", List.of(
                Map.of("label", "Número Consecutivo"),
                Map.of("label", "Fecha Emisión"),
                Map.of("label", "Cliente"),
                Map.of("label", "Total"),
                Map.of("label", "Motivo Rechazo"),
                Map.of("label", "Intentos"),
                Map.of("label", "Fecha Rechazo"),
                Map.of("label", "Estado"),
                Map.of("label", "Acciones")));
        model.put("headersMensajes", List.of(
                Map.of("label", "Consecutivo"),
                Map.of("label", "Emisor"),
                Map.of("label", "Fecha Emisión"),
                Map.of("label", "Estado MR"),
                Map.of("label", "Límite"),
                Map.of("label", "Indicador"),
                Map.of("label", "Impuesto")));
        model.put("filasPendientes", pageOf(pendientes));
        model.put("filasAceptadas", pageOf(aceptadas));
        model.put("filasRechazadas", pageOf(rechazadas));

        List<Map<String, Object>> mensajes = new ArrayList<>();
        for (ComprobantesRecibidos r : orEmpty(recibidosService.listAll())) {
            mensajes.add(mensajeMap(r));
        }
        mensajes.sort(Comparator.comparing(m -> (java.time.LocalDate) m.get("limite"),
                Comparator.nullsLast(Comparator.naturalOrder())));
        model.put("filasMensajes", mensajes.size() > 100 ? mensajes.subList(0, 100) : mensajes);
        return model;
    }

    /** Declaración model — sums mirror DeclaracionIVAController.calcular(). */
    private Map<String, Object> declaracionModel(int mes, int anio) {
        LocalDate inicio = LocalDate.of(anio, mes, 1);
        LocalDate finMes = inicio.plusMonths(1).minusDays(1);
        Date start = Date.from(inicio.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        Date end = Date.from(finMes.atTime(23, 59, 59)
                .atZone(java.time.ZoneId.systemDefault()).toInstant());

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
        BigDecimal ivaNeto = ivaDebito.subtract(ivaCredito);

        List<Map<String, Object>> filasMensuales = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("periodo", nombreMes(i) + " " + anio);
            fila.put("mes", i);
            filasMensuales.add(fila);
        }
        // Monthly sums need the whole year through the fixed binding once.
        Date yearStart = Date.from(LocalDate.of(anio, 1, 1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        Date yearEnd = Date.from(LocalDate.of(anio, 12, 31).atTime(23, 59, 59)
                .atZone(java.time.ZoneId.systemDefault()).toInstant());
        BigDecimal[] ventasMes = new BigDecimal[12];
        BigDecimal[] impuestoMes = new BigDecimal[12];
        for (int i = 0; i < 12; i++) {
            ventasMes[i] = BigDecimal.ZERO;
            impuestoMes[i] = BigDecimal.ZERO;
        }
        for (ComprobantesEmitidos f : orEmpty(emitidosService.listByDateRange(yearStart, yearEnd))) {
            if (f.getResumen() == null || f.getEncabezado() == null
                    || f.getEncabezado().getFechaEmision() == null) {
                continue;
            }
            int idx = f.getEncabezado().getFechaEmision().getMonthValue() - 1;
            if (f.getResumen().getTotalVentaNeta() != null) {
                ventasMes[idx] = ventasMes[idx].add(f.getResumen().getTotalVentaNeta());
            }
            if (f.getResumen().getTotalImpuesto() != null) {
                impuestoMes[idx] = impuestoMes[idx].add(f.getResumen().getTotalImpuesto());
            }
        }
        for (int i = 0; i < 12; i++) {
            filasMensuales.get(i).put("totalVentas", ventasMes[i]);
            filasMensuales.get(i).put("totalImpuesto", impuestoMes[i]);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Declaración IVA (D-104)");
        model.put("periodo", nombreMes(mes) + " " + anio);
        model.put("mesSeleccionado", mes);
        model.put("anioSeleccionado", anio);
        model.put("meses", MESES);
        model.put("anios", aniosValidos());
        // Label-only headers (docs/ui-kit.md §3.1): fixed report snapshots,
        // no server-side sorting contract on this page.
        model.put("baseUrl", "/app/tributacion/declaracion");
        model.put("headersMensuales", List.of(
                Map.of("label", "Período"),
                Map.of("label", "Total Ventas"),
                Map.of("label", "Total Impuesto")));
        model.put("headersDetalle", List.of(
                Map.of("label", "Número Consecutivo"),
                Map.of("label", "Fecha"),
                Map.of("label", "Cliente"),
                Map.of("label", "Total Neto"),
                Map.of("label", "IVA")));
        model.put("headersRecibidas", List.of(
                Map.of("label", "Número Consecutivo"),
                Map.of("label", "Fecha"),
                Map.of("label", "Proveedor"),
                Map.of("label", "Total Neto"),
                Map.of("label", "IVA")));
        model.put("totalVentas", colones(totalVentas));
        model.put("totalCompras", colones(totalCompras));
        model.put("ivaDebito", colones(ivaDebito));
        model.put("ivaCredito", colones(ivaCredito));
        model.put("ivaNeto", colones(ivaNeto));
        model.put("ivaNetoPositivo", ivaNeto.compareTo(BigDecimal.ZERO) >= 0);
        model.put("totalFacturasEmitidas", emitidas.size());
        model.put("totalFacturasRecibidas", recibidas.size());
        model.put("filasEmitidas", emitidas.stream().map(TributacionPagesResource::filaMap)
                .limit(DECLARACION_DETAIL_LIMIT).toList());
        model.put("filasRecibidas", recibidas.stream()
                .map(TributacionPagesResource::recibidoMap)
                .limit(DECLARACION_DETAIL_LIMIT).toList());
        model.put("filasMensuales", filasMensuales);
        return model;
    }

    // ── Row mappers ─────────────────────────────────────────────────────

    private static Map<String, Object> filaMap(@Nonnull ComprobantesEmitidos f) {
        Encabezado enc = f.getEncabezado();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.getId());
        row.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
        row.put("fechaEmision", enc != null ? enc.getFechaEmision() : null);
        row.put("cliente", enc != null && enc.getReceptor() != null ? enc.getReceptor().getNombre() : null);
        row.put("total", f.getResumen() != null ? f.getResumen().getTotalComprobante() : null);
        row.put("impuesto", f.getResumen() != null ? f.getResumen().getTotalImpuesto() : null);
        row.put("motivoRechazo", enc != null ? enc.getMotivoRechazo() : null);
        row.put("intentos", f.getCorrectionAttempts() == null ? 0 : f.getCorrectionAttempts());
        row.put("ultimaCorreccion", f.getUltimaCorreccion());
        row.put("fechaRechazo", f.getHaciendaFechaRespuesta());
        return row;
    }

    private static Map<String, Object> recibidoMap(@Nonnull ComprobantesRecibidos f) {
        Encabezado enc = f.getEncabezado();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.getId());
        row.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
        row.put("fechaEmision", enc != null ? enc.getFechaEmision() : null);
        row.put("proveedor", enc != null && enc.getEmisor() != null ? enc.getEmisor().getNombre() : null);
        row.put("total", f.getResumen() != null ? f.getResumen().getTotalVentaNeta() : null);
        row.put("impuesto", f.getResumen() != null ? f.getResumen().getTotalImpuesto() : null);
        return row;
    }

    /** Mensajes-Receptor row with the derived deadline indicator state. */
    private static Map<String, Object> mensajeMap(@Nonnull ComprobantesRecibidos r) {
        Encabezado enc = r.getEncabezado();
        String estado = r.getHaciendaMensajeReceptorEstado();
        java.time.LocalDate limite = r.getMensajeReceptorLimite();
        long diasRestantes = r.getDiasRestantesMensajeReceptor();
        boolean vencido = r.isMensajeReceptorVencido();

        String indicador;
        String chipClass;
        if (estado != null && !estado.isBlank()) {
            indicador = "ATENDIDO";
            chipClass = "is-success";
        } else if (vencido) {
            indicador = "VENCIDO";
            chipClass = "is-danger";
        } else if (diasRestantes >= 0 && diasRestantes <= 2) {
            indicador = "POR_VENCER";
            chipClass = "is-warning";
        } else {
            indicador = "EN_TIEMPO";
            chipClass = "is-info";
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
        row.put("emisor", enc != null && enc.getEmisor() != null ? enc.getEmisor().getNombre() : null);
        row.put("fechaEmision", enc != null ? enc.getFechaEmision() : null);
        row.put("estado", estado);
        row.put("limite", limite);
        row.put("diasRestantes", diasRestantes);
        row.put("vencido", vencido);
        row.put("indicador", indicador);
        row.put("chipClass", chipClass);
        row.put("montoImpuesto", r.getResumen() != null ? r.getResumen().getTotalImpuesto() : null);
        row.put("montoFactura", r.getResumen() != null ? r.getResumen().getTotalComprobante() : null);
        return row;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean isHxRequest() {
        return ReportePageSupport.isHxRequest(httpHeaders);
    }

    /** First consultas page of a bucket (legacy rows=15 paginator parity). */
    private static List<Map<String, Object>> pageOf(@Nonnull List<ComprobantesEmitidos> source) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComprobantesEmitidos f : source) {
            rows.add(filaMap(f));
            if (rows.size() >= CONSULTAS_PAGE_SIZE) {
                break;
            }
        }
        return rows;
    }

    private static List<Integer> aniosValidos() {
        List<Integer> anios = new ArrayList<>();
        for (int y = 2020; y <= 2040; y++) {
            anios.add(y);
        }
        return Collections.unmodifiableList(anios);
    }

    private static long countEstado(@Nonnull List<ComprobantesEmitidos> todos, @Nonnull String estado) {
        return todos.stream()
                .filter(c -> estado.equalsIgnoreCase(c.getHaciendaEstado()))
                .count();
    }

    private static long countPendientes(@Nonnull List<ComprobantesEmitidos> todos) {
        return todos.stream()
                .filter(c -> c.getHaciendaEstado() == null
                        || "PENDIENTE".equalsIgnoreCase(c.getHaciendaEstado())
                        || c.getHaciendaEstado().isEmpty())
                .count();
    }

    /** Legacy getMontoTotal*Formateado parity: "₡" + %,.2f (default locale). */
    private static String colones(@Nullable BigDecimal monto) {
        if (monto == null) {
            return "₡0.00";
        }
        return "₡" + String.format("%,.2f", monto);
    }

    private static String nombreMes(int mes) {
        return MESES[mes - 1];
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    private static Response serverError(@Nonnull String mensaje) {
        return Response.serverError()
                .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR", mensaje))
                .build();
    }
}
