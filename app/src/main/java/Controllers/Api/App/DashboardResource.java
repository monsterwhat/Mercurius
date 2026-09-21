package Controllers.Api.App;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import Models.DTO.ApiResponse;
import Models.Users;
import Services.DashboardMetricsService;
import Services.DashboardService;
import Services.LoginService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Dashboard page + JSON feeds for the NEW app surface — plan task T29 port of
 * the legacy {@code Controllers.DashboardController} (@Named "DashboardController",
 * deleted by this task) and its {@code secured/index.xhtml} landing.
 *
 * <p><b>Proxy decision (documented in .omo/evidence/t29/):</b> the legacy analytics
 * HTTP routes ({@code /api/dashboard}, {@code /api/sales-trend}) are guarded by
 * {@code Controllers.filters.PublicApiJwtFilter}, which accepts ONLY
 * {@code Authorization: Bearer} tokens and aborts cookie-authenticated browser
 * requests with 401. This resource therefore injects the analytics SERVICES
 * directly ({@link DashboardMetricsService}, {@link DashboardService}) instead of
 * self-calling those routes, and serves same-origin cookie-protected feeds under
 * {@code /app/dashboard/data/*} (governed by the T13 {@code authenticated}
 * permission policy on {@code /app/*}, which the form cookie satisfies).</p>
 *
 * <p>Feed payload convention: {@code hourly}/{@code weekly} return RAW arrays
 * (Chart.js consumes them directly); {@code kpi} wraps the object in the standard
 * {@link ApiResponse} envelope like the rest of the App resources.</p>
 *
 * <p>Role gating mirrors the legacy page: KPI grid + charts for
 * {@code admin|facturacion}, basic "Resumen de Hoy" panel for other roles,
 * top-products table for {@code admin|inventario}.</p>
 */
@Path("/app/dashboard")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "facturacion", "inventario", "usuario", "tributacion", "registro"})
public class DashboardResource {

    private static final Logger LOG = Logger.getLogger(DashboardResource.class);

    /** Fixed hour labels — byte-for-byte port of the legacy HOUR_LABELS constant. */
    private static final String[] HOUR_LABELS = {
        "00:00", "01:00", "02:00", "03:00", "04:00", "05:00",
        "06:00", "07:00", "08:00", "09:00", "10:00", "11:00",
        "12:00", "13:00", "14:00", "15:00", "16:00", "17:00",
        "18:00", "19:00", "20:00", "21:00", "22:00", "23:00"
    };

    /** Fixed day labels — byte-for-byte port of the legacy DAY_NAMES constant. */
    private static final String[] DAY_NAMES = {
        "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"
    };

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    @Nonnull
    DashboardMetricsService dashboardMetricsService;

    @Inject
    @Nonnull
    DashboardService dashboardService;

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    @Inject
    @Location("pages/dashboard/index")
    Template pagina;

    // ════════════════════════════════════════════════════════════════════
    // Full page
    // ════════════════════════════════════════════════════════════════════

    /**
     * Renders the dashboard landing: welcome header, Bulma tile KPI grid and the
     * two Chart.js canvases replacing the legacy {@code p:chart} pair.
     */
    @GET
    @Transactional
    public Response pagina() {
        Users user = currentUser();
        boolean esKpi = securityIdentity.hasRole("admin") || securityIdentity.hasRole("facturacion");
        boolean esInventario = securityIdentity.hasRole("admin") || securityIdentity.hasRole("inventario");

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Dashboard");
        model.put("usuario", user != null ? user.getUsername() : "Usuario");
        model.put("fechaActual", LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        model.put("esKpi", esKpi);
        model.put("esInventario", esInventario);

        BigDecimal hoy = BigDecimal.ZERO;
        BigDecimal ayer = BigDecimal.ZERO;
        BigDecimal semana = BigDecimal.ZERO;
        BigDecimal mes = BigDecimal.ZERO;
        BigDecimal ticket = BigDecimal.ZERO;
        BigDecimal crecimiento = BigDecimal.ZERO;
        int transaccionesHoy = 0;
        if (user != null) {
            DashboardMetricsService.DashboardKPI kpi = dashboardMetricsService.getKPIs(user);
            hoy = kpi.getTodaySales();
            ayer = dashboardMetricsService.getYesterdaySales(user);
            semana = kpi.getWeekSales();
            mes = kpi.getMonthSales();
            ticket = kpi.getAverageTicket();
            crecimiento = kpi.getDailyGrowth();
            transaccionesHoy = kpi.getTodayTransactions();
        }
        int articulosVendidos = user != null ? dashboardService.getItemsSold(user) : 0;

        model.put("ventasHoy", formatoColones(hoy));
        model.put("ventasAyer", formatoColones(ayer));
        model.put("ventasSemana", formatoColones(semana));
        model.put("ventasMes", formatoColones(mes));
        model.put("ticketPromedio", formatoColones(ticket));
        model.put("crecimientoDiario", displayCrecimiento(crecimiento));
        model.put("crecimientoCss", cssCrecimiento(crecimiento));
        model.put("transaccionesHoy", transaccionesHoy);
        model.put("articulosVendidos", articulosVendidos);
        model.put("ultimaTransaccion", ultimaTransaccionDisplay(user));

        List<Map<String, Object>> topProductos = new ArrayList<>();
        if (user != null && esInventario) {
            for (DashboardMetricsService.TopProduct prod : dashboardMetricsService.getTopSellingProducts(user, 5)) {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("nombre", prod.getName());
                fila.put("unidades", prod.getQuantity());
                fila.put("ingresos", formatoColones(prod.getRevenue()));
                topProductos.add(fila);
            }
        }
        model.put("topProductos", topProductos);

        // Jackson-serialized chart datasets for the no-fetch fallback path.
        // ONLY fixed labels + service numbers — never user-controlled text (.raw safety rule).
        model.put("hourlyJson", toJson(construirHourly(user)));
        model.put("weeklyJson", toJson(construirWeekly(user)));

        String html = pagina.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // JSON feeds (same-origin, cookie-authenticated via /app/* policy)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Hourly sales distribution for today — raw array of 24 slots
     * {@code {hora, ventas, transacciones}} (legacy bar-chart feed).
     */
    @GET
    @Path("/data/hourly")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response datosPorHora() {
        return Response.ok(construirHourly(currentUser())).build();
    }

    /**
     * Weekly sales breakdown (last 7 days) — raw array of 7 slots
     * {@code {fecha, dia, ventas, transacciones}} (legacy line-chart feed).
     */
    @GET
    @Path("/data/weekly")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response datosSemanales() {
        return Response.ok(construirWeekly(currentUser())).build();
    }

    /**
     * KPI summary — ApiResponse envelope with
     * {@code data:{ventasHoy, ventasAyer, ventasSemana, ventasMes, transaccionesHoy,
     * ticketPromedio, crecimientoDiario, articulosVendidos}}.
     */
    @GET
    @Path("/data/kpi")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response datosKpi() {
        try {
            return Response.ok(ApiResponse.ok(construirKpi(currentUser()))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error building dashboard KPI feed", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error calculando los indicadores del dashboard"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Dataset builders (shared by page model + feeds)
    // ════════════════════════════════════════════════════════════════════

    @Nonnull
    private List<Map<String, Object>> construirHourly(@Nullable Users user) {
        List<Map<String, Object>> puntos = new ArrayList<>();
        List<DashboardMetricsService.HourlySales> distribucion = user != null
                ? dashboardMetricsService.getHourlySalesDistribution(user, LocalDate.now())
                : List.of();
        for (int hora = 0; hora < 24; hora++) {
            Map<String, Object> punto = new LinkedHashMap<>();
            punto.put("hora", HOUR_LABELS[hora]);
            punto.put("ventas", ventaEn(distribucion, hora));
            punto.put("transacciones", transaccionesEn(distribucion, hora));
            puntos.add(punto);
        }
        return puntos;
    }

    private static BigDecimal ventaEn(List<DashboardMetricsService.HourlySales> distribucion, int hora) {
        for (DashboardMetricsService.HourlySales hs : distribucion) {
            if (hs.getHour() == hora) {
                return hs.getTotalSales();
            }
        }
        return BigDecimal.ZERO;
    }

    private static int transaccionesEn(List<DashboardMetricsService.HourlySales> distribucion, int hora) {
        for (DashboardMetricsService.HourlySales hs : distribucion) {
            if (hs.getHour() == hora) {
                return hs.getTransactions();
            }
        }
        return 0;
    }

    @Nonnull
    private List<Map<String, Object>> construirWeekly(@Nullable Users user) {
        List<Map<String, Object>> puntos = new ArrayList<>();
        List<DashboardMetricsService.DailySales> desglose = user != null
                ? dashboardMetricsService.getWeeklySalesBreakdown(user)
                : List.of();
        for (DashboardMetricsService.DailySales ds : desglose) {
            Map<String, Object> punto = new LinkedHashMap<>();
            punto.put("fecha", ds.getDate().toString());
            punto.put("dia", DAY_NAMES[ds.getDate().getDayOfWeek().getValue() - 1]);
            punto.put("ventas", ds.getTotalSales());
            punto.put("transacciones", ds.getTransactions());
            puntos.add(punto);
        }
        return puntos;
    }

    @Nonnull
    private Map<String, Object> construirKpi(@Nullable Users user) {
        Map<String, Object> data = new LinkedHashMap<>();
        BigDecimal hoy = BigDecimal.ZERO;
        BigDecimal ayer = BigDecimal.ZERO;
        BigDecimal semana = BigDecimal.ZERO;
        BigDecimal mes = BigDecimal.ZERO;
        BigDecimal ticket = BigDecimal.ZERO;
        BigDecimal crecimiento = BigDecimal.ZERO;
        int transaccionesHoy = 0;
        if (user != null) {
            DashboardMetricsService.DashboardKPI kpi = dashboardMetricsService.getKPIs(user);
            hoy = kpi.getTodaySales();
            ayer = dashboardMetricsService.getYesterdaySales(user);
            semana = kpi.getWeekSales();
            mes = kpi.getMonthSales();
            ticket = kpi.getAverageTicket();
            crecimiento = kpi.getDailyGrowth();
            transaccionesHoy = kpi.getTodayTransactions();
        }
        data.put("ventasHoy", hoy);
        data.put("ventasAyer", ayer);
        data.put("ventasSemana", semana);
        data.put("ventasMes", mes);
        data.put("transaccionesHoy", transaccionesHoy);
        data.put("ticketPromedio", ticket);
        data.put("crecimientoDiario", crecimiento);
        data.put("articulosVendidos", user != null ? dashboardService.getItemsSold(user) : 0);
        return data;
    }

    // ════════════════════════════════════════════════════════════════════
    // Display helpers (legacy-formatted parity)
    // ════════════════════════════════════════════════════════════════════

    /** Legacy formatColones(): "₡" + grouped integer amount. */
    @Nonnull
    private static String formatoColones(@Nullable BigDecimal valor) {
        return "₡" + String.format("%,.0f", valor == null ? BigDecimal.ZERO : valor);
    }

    /** Legacy getDailyGrowthDisplay(): signed one-decimal percentage. */
    @Nonnull
    private static String displayCrecimiento(@Nullable BigDecimal crecimiento) {
        if (crecimiento == null) {
            return "0%";
        }
        return (crecimiento.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                + crecimiento.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Legacy getDailyGrowthCssClass(): success/danger/grey Bulma text classes. */
    @Nonnull
    private static String cssCrecimiento(@Nullable BigDecimal crecimiento) {
        if (crecimiento == null) {
            return "has-text-grey";
        }
        return crecimiento.compareTo(BigDecimal.ZERO) >= 0 ? "has-text-success" : "has-text-danger";
    }

    /**
     * Port of the legacy updateLastTransactionDisplay():
     * "Factura {consecutivo} - {total} colones a las {HH:mm}" or the empty-state text.
     */
    @Nonnull
    private String ultimaTransaccionDisplay(@Nullable Users user) {
        if (user == null) {
            return "No hay transacciones hoy";
        }
        try {
            Models.ComprobantesEmitidos ultima = dashboardService.getLastTransaction(user);
            if (ultima == null) {
                return "No hay transacciones hoy";
            }
            String consecutivo = ultima.getEncabezado().getNumeroConsecutivo();
            BigDecimal total = ultima.getResumen().getTotalComprobante();
            LocalDateTime fecha = ultima.getEncabezado().getFechaEmision();
            return String.format("Factura %s - %s colones a las %s",
                    consecutivo, total, fecha.format(DateTimeFormatter.ofPattern("HH:mm")));
        } catch (RuntimeException e) {
            LOG.warn("Error formatting last transaction", e);
            return "Error al cargar última transacción";
        }
    }

    @Nullable
    private Users currentUser() {
        if (securityIdentity.isAnonymous() || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return loginService.findByUsername(securityIdentity.getPrincipal().getName());
    }

    @Nonnull
    private static String toJson(@Nonnull List<Map<String, Object>> puntos) {
        try {
            return OBJECT_MAPPER.writeValueAsString(puntos);
        } catch (JsonProcessingException e) {
            LOG.warn("Error serializing dashboard dataset", e);
            return "[]";
        }
    }
}
