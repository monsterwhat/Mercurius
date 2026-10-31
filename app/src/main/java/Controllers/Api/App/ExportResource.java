package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.Articulos.Articulos;
import Models.Articulos.Promocion;
import Models.CorteMargen;
import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Inventario;
import Models.ReportesFamiliasYDepartamentos;
import Models.Usuarios;
import Services.ArticulosService;
import Services.ArticuloPrecioService;
import Services.ClientService;
import Services.SalesTrendService;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import Services.Correos.ReportesProgramadosService;
import Services.DepartamentoService;
import Services.ShrinkageAnalysisService;
import Services.LoginService;
import Services.ProductPerformanceService;
import Services.FamiliaService;
import Services.InventarioService;
import Services.PromocionesService;
import Services.ProfitAnalysisService;
import Services.StockAlertService;
import Services.UserService;
import Services.StockForecastService;
import Services.SeasonalityService;
import Utils.ReportExporter;
import com.lowagie.text.DocumentException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.security.RolesAllowed;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Download endpoints for the NEW Qute/HTMX app surface (/app world): streams
 * report bytes produced by {@link ReportExporter} as HTTP attachments,
 * replacing the JSF dataExporter / ExternalContext download flows.
 *
 * <p>Dataset keys map 1:1 to the migrated datasets:</p>
 * <ul>
 *   <li>{@code articulos} → "Reporte de Articulos Activos" (pdf) / "Articulos" workbook (xlsx)</li>
 *   <li>{@code inventario} → "Reporte de Ajustes Activos" (pdf)</li>
 *   <li>{@code stock-alerts} → "Alertas de Stock" workbook (xlsx)</li>
 *   <li>{@code profit-margins} → "Snapshots Márgenes" workbook (xlsx)</li>
 *   <li>{@code familias} → "Familias" workbook (xlsx)</li>
 *   <li>{@code departamentos} → "Departamentos" workbook (xlsx)</li>
 *   <li>{@code reportes-familias} → "Ventas por Familia" workbook (xlsx)</li>
 *   <li>{@code reportes-departamentos} → "Ventas por Departamento" workbook (xlsx)</li>
 * </ul>
 *
 * <p>This resource carries its OWN authorization gate
 * ({@code @RolesAllowed({"admin","registro"})}) instead of depending on the HTTP
 * permission policy. That matters because the policy no longer blanket-covers
 * {@code /api/app/*}: it enumerates the real sub-APIs so an unknown path can
 * 404 as JSON, and this resource must therefore never rely on a prefix it is
 * not named after. {@code registro} is the role the export buttons check in the
 * templates, and {@code admin} is listed explicitly as in every other
 * {@code @RolesAllowed} of this surface (51 sites).</p>
 */
@Path("/api/app/export")
@RolesAllowed({"admin", "registro"})
@Produces(MediaType.APPLICATION_OCTET_STREAM)
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Tag(name = "App - Export")
public class ExportResource {

    private static final Logger LOG = Logger.getLogger(ExportResource.class);

    private static final String TYPE_XLSX = "xlsx";
    private static final String TYPE_PDF = "pdf";

    /** {dataset}-{yyyyMMdd HH:mm}.ext, quoted in Content-Disposition. */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd HH:mm").withLocale(Locale.ROOT);

    @Nonnull
    @Inject
    ArticulosService articulosService;

    @Nonnull
    @Inject
    ComprobantesEmitidosService comprobantesService;

    @Nonnull
    @Inject
    ComprobantesRecibidosService recibidosService;

    @Nonnull
    @Inject
    LoginService loginService;

    @Nonnull
    @Inject
    ProductPerformanceService productPerformanceService;

    @Nonnull
    @Inject
    SalesTrendService salesTrendService;

    @Nonnull
    @Inject
    ShrinkageAnalysisService shrinkageAnalysisService;

    @Nonnull
    @Inject
    StockForecastService stockForecastService;

    @Nonnull
    @Inject
    SeasonalityService seasonalityService;

    @Nonnull
    @Inject
    ArticuloPrecioService precioService;

    @Nonnull
    @Inject
    ClientService clientService;

    @Nonnull
    @Inject
    ReportesProgramadosService reportesProgramadosService;

    @Nonnull
    @Inject
    DepartamentoService departamentoService;

    @Nonnull
    @Inject
    FamiliaService familiaService;

    @Nonnull
    @Inject
    InventarioService inventarioService;

    @Nonnull
    @Inject
    PromocionesService promocionesService;

    @Nonnull
    @Inject
    UserService userService;

    @Nonnull
    @Inject
    StockAlertService stockAlertService;

    @Nonnull
    @Inject
    ProfitAnalysisService profitAnalysisService;

    /**
     * Streams one of the migrated datasets as an attachment download.
     *
     * @param type    export format: {@code xlsx} or {@code pdf}
     * @param dataset dataset key (see class doc); unknown keys yield 404
     * @param desde   optional ISO {@code yyyy-MM-dd} start bound for the
     *                {@code reportes-familias} / {@code reportes-departamentos}
     *                datasets (blank/invalid → empty workbook)
     * @param hasta   optional ISO {@code yyyy-MM-dd} end bound for the
     *                {@code reportes-familias} / {@code reportes-departamentos}
     *                datasets (blank/invalid → empty workbook)
     */
    @POST
    @Operation(summary = "Stream a dataset export (xlsx/pdf) as an attachment download")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Report bytes streamed as octet-stream attachment"),
        @APIResponse(responseCode = "400", description = "Missing or invalid parameters"),
        @APIResponse(responseCode = "404", description = "Unknown dataset or unsupported format for the dataset"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response download(
            @FormParam("type") @Nullable String type,
            @FormParam("dataset") @Nullable String dataset,
            @FormParam("desde") @Nullable String desde,
            @FormParam("hasta") @Nullable String hasta,
            @FormParam("q") @Nullable String q,
            @FormParam("seccion") @Nullable String seccion,
            @FormParam("dias") @Nullable String dias,
            @FormParam("month") @Nullable String month,
            @FormParam("day") @Nullable String day,
            @FormParam("yearsBack") @Nullable String yearsBack,
            @FormParam("dimension") @Nullable String dimension,
            @FormParam("items") @Nullable String items,
            @FormParam("tab") @Nullable String tab,
            @FormParam("usuario") @Nullable String usuario,
            @FormParam("familia") @Nullable String familia,
            @FormParam("departamento") @Nullable String departamento,
            @FormParam("vista") @Nullable String vista) {

        if (type == null || type.isBlank()) {
            return badRequest("Falta el parámetro 'type' (xlsx|pdf)");
        }
        if (dataset == null || dataset.isBlank()) {
            return badRequest("Falta el parámetro 'dataset'");
        }
        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_XLSX.equals(normalizedType) && !TYPE_PDF.equals(normalizedType)) {
            return badRequest("Tipo de exportación no soportado: " + type);
        }

        String key = dataset.trim().toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = buildExport(key, normalizedType, desde, hasta, q, seccion, dias, month, day, yearsBack, dimension, items,
                    tab, usuario, familia, departamento, vista);
        } catch (UnsupportedDatasetException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Error building export " + key + "/" + normalizedType, e);
            return serverError("No se pudo generar la exportación");
        }

        String fileName = key + "-" + LocalDateTime.now().format(FILE_STAMP) + "." + normalizedType;
        return Response.ok(bytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    private byte[] buildExport(@Nonnull String dataset, @Nonnull String type,
                               @Nullable String desde, @Nullable String hasta,
                               @Nullable String q, @Nullable String seccion,
                               @Nullable String dias, @Nullable String month,
                               @Nullable String day, @Nullable String yearsBack,
                               @Nullable String dimension, @Nullable String items,
                               @Nullable String tab, @Nullable String usuario,
                               @Nullable String familia, @Nullable String departamento,
                               @Nullable String vistaResumen)
            throws DocumentException, IOException, UnsupportedDatasetException {
        boolean wantsPdf = TYPE_PDF.equals(type);

        return switch (dataset) {
            case "correos" -> !wantsPdf
                    ? ReportExporter.exportCorreosExcel(orEmpty(reportesProgramadosService.listAll()))
                    : unsupported(dataset, type);
            case "precios" -> !wantsPdf
                    ? ReportExporter.exportPreciosExcel(orEmpty(precioService.listAll()))
                    : unsupported(dataset, type);
            case "clientes" -> !wantsPdf
                    ? ReportExporter.exportClientesExcel(orEmpty(clientService.listAll()))
                    : unsupported(dataset, type);
            case "recibos-pendientes" -> !wantsPdf
                    ? ReportExporter.exportRecibosExcel("Recibos Pendientes", orEmpty(recibidosService.listPendientes()))
                    : unsupported(dataset, type);
            case "usuarios" -> !wantsPdf
                    ? ReportExporter.exportUsuariosExcel(orEmpty(loginService.listAll()))
                    : unsupported(dataset, type);
            case "recibos-vencidos" -> !wantsPdf
                    ? ReportExporter.exportRecibosExcel("Recibos Vencidos", orEmpty(recibidosService.listVencidas()))
                    : unsupported(dataset, type);
            case "facturas" -> !wantsPdf
                    ? exportarFacturas(q)
                    : unsupported(dataset, type);
            case "merma" -> {
                Date inicio = fecha(desde, false);
                Date fin = fecha(hasta, true);
                if (inicio == null || fin == null) {
                    Calendar cal = Calendar.getInstance();
                    fin = new Date();
                    cal.add(Calendar.DAY_OF_MONTH, -30);
                    inicio = cal.getTime();
                }
                String vista = (seccion == null || seccion.isBlank()) ? "detalle" : seccion;
                if ("causas".equals(vista)) {
                    yield !wantsPdf
                            ? ReportExporter.exportMermaExcel(vista, null,
                                    shrinkageAnalysisService.getShrinkageByCause(inicio, fin),
                                    null, shrinkageAnalysisService.getTotalShrinkage(inicio, fin))
                            : unsupported(dataset, type);
                }
                if ("departamentos".equals(vista)) {
                    yield !wantsPdf
                            ? ReportExporter.exportMermaExcel(vista, null, null,
                                    shrinkageAnalysisService.getShrinkageByDepartment(inicio, fin),
                                    shrinkageAnalysisService.getTotalShrinkage(inicio, fin))
                            : unsupported(dataset, type);
                }
                yield !wantsPdf
                        ? ReportExporter.exportMermaExcel("detalle",
                                shrinkageAnalysisService.getShrinkageMovements(inicio, fin), null, null, null)
                        : unsupported(dataset, type);
            }
            case "tendencias" -> {
                Date inicio = fecha(desde, false);
                Date fin = fecha(hasta, true);
                if (inicio == null || fin == null) {
                    Calendar cal = Calendar.getInstance();
                    fin = new Date();
                    cal.add(Calendar.DAY_OF_MONTH, -90);
                    inicio = cal.getTime();
                }
                String vista = (seccion == null || seccion.isBlank()) ? "diarias" : seccion;
                if ("indicadores".equals(vista)) {
                    yield !wantsPdf
                            ? ReportExporter.exportTendenciasExcel(vista, null,
                                    salesTrendService.getGrowthMetrics(inicio, fin),
                                    salesTrendService.getTrendIndicators(inicio, fin))
                            : unsupported(dataset, type);
                }
                List<Services.SalesTrendService.TimeSeriesData> serie = switch (vista) {
                    case "semanales" -> salesTrendService.getWeeklySalesTimeSeries(inicio, fin);
                    case "mensuales" -> salesTrendService.getMonthlySalesTimeSeries(inicio, fin);
                    default -> salesTrendService.getDailySalesTimeSeries(inicio, fin);
                };
                yield !wantsPdf
                        ? ReportExporter.exportTendenciasExcel(vista, serie, null, null)
                        : unsupported(dataset, type);
            }
            case "rendimiento" -> {
                Date inicio = fecha(desde, false);
                Date fin = fecha(hasta, true);
                if (inicio == null || fin == null) {
                    Calendar cal = Calendar.getInstance();
                    fin = new Date();
                    cal.add(Calendar.DAY_OF_MONTH, -30);
                    inicio = cal.getTime();
                }
                String vista = (seccion == null || seccion.isBlank()) ? "mas" : seccion;
                if ("departamento".equals(vista)) {
                    yield !wantsPdf
                            ? ReportExporter.exportRendimientoExcel(vista, null,
                                    productPerformanceService.getDepartmentPerformance(inicio, fin))
                            : unsupported(dataset, type);
                }
                List<Services.ProductPerformanceService.ProductSalesSummary> resumen = switch (vista) {
                    case "menos" -> productPerformanceService.getWorstSellingProducts(inicio, fin, 10);
                    case "ingresos" -> productPerformanceService.getBestSellingProductsByRevenue(inicio, fin, 10);
                    default -> productPerformanceService.getBestSellingProducts(inicio, fin, 10);
                };
                yield !wantsPdf
                        ? ReportExporter.exportRendimientoExcel(vista, resumen, null)
                        : unsupported(dataset, type);
            }
            case "pronosticos" -> {
                int diasPronostico = 30;
                try {
                    int valor = Integer.parseInt(dias == null ? "30" : dias.trim());
                    if (valor == 7 || valor == 14 || valor == 30 || valor == 60) {
                        diasPronostico = valor;
                    }
                } catch (NumberFormatException e) {
                    LOG.debug("Exportacion de pronosticos: 'dias' invalido (" + dias
                            + "); se usa 30", e);
                }
                String vista = (seccion == null || seccion.isBlank()) ? "pronosticos" : seccion;
                // Una sola lectura del catalogo alimenta el pronostico y los
                // contadores de salud: antes cada uno pagaba la consulta
                // agregada completa.
                List<Services.StockForecastService.EstadisticaArticulo> catalogo =
                        stockForecastService.estadisticasCatalogo();
                Services.StockForecastService.InventoryHealthReport salud =
                        Services.StockForecastService.saludDe(catalogo);
                if ("salud".equals(vista)) {
                    yield !wantsPdf
                            ? ReportExporter.exportPronosticosExcel(vista, diasPronostico, null, salud)
                            : unsupported(dataset, type);
                }
                List<Services.StockForecastService.ProductForecast> pronosticos =
                        stockForecastService.generateBulkForecast(catalogo, diasPronostico);
                yield !wantsPdf
                        ? ReportExporter.exportPronosticosExcel(vista, diasPronostico, pronosticos, salud)
                        : unsupported(dataset, type);
            }
            case "estacionalidad" -> {
                Date end = new Date();
                Date start;
                Calendar cal = Calendar.getInstance();
                cal.setTime(end);
                cal.add(Calendar.MONTH, -12);
                start = cal.getTime();
                Date parsedStart = fecha(desde, false);
                Date parsedEnd = fecha(hasta, true);
                if (parsedStart != null) {
                    start = parsedStart;
                }
                if (parsedEnd != null) {
                    end = parsedEnd;
                }
                yield !wantsPdf
                        ? ReportExporter.exportEstacionalidadExcel(seasonalityService.getDailySales(start, end))
                        : unsupported(dataset, type);
            }
            case "loyalty" -> !wantsPdf
                    ? ReportExporter.exportLoyaltyExcel(orEmpty(clientService.listAll()))
                    : unsupported(dataset, type);
            case "etiquetas" -> wantsPdf
                    ? ReportExporter.exportEtiquetasPdf(orEmpty(articulosService.ListAllEnabled()), new java.util.HashMap<>())
                    : unsupported(dataset, type);
            case "ventas-fecha-historica" -> {
                int resolvedMes = 1;
                int resolvedDia = 1;
                int resolvedAnos = 6;
                try {
                    if (month != null && !month.isBlank()) {
                        resolvedMes = Integer.parseInt(month.trim());
                    }
                    if (day != null && !day.isBlank()) {
                        resolvedDia = Integer.parseInt(day.trim());
                    }
                    if (yearsBack != null && !yearsBack.isBlank()) {
                        resolvedAnos = Integer.parseInt(yearsBack.trim());
                    }
                } catch (NumberFormatException e) {
                    // keep defaults
                }
                if (resolvedMes < 1 || resolvedMes > 12) resolvedMes = 1;
                if (resolvedDia < 1 || resolvedDia > 31) resolvedDia = 1;
                if (resolvedAnos < 1 || resolvedAnos > 50) resolvedAnos = 6;
                String resolvedDimension = (dimension == null || dimension.isBlank()) ? "productos" : dimension.trim();
                List<String> selectedItems = new ArrayList<>();
                if (items != null && !items.isBlank()) {
                    for (String item : items.split("\\|")) {
                        String trimmed = item.trim();
                        if (!trimmed.isEmpty()) {
                            selectedItems.add(trimmed);
                        }
                    }
                }
                Services.SalesTrendService.SameDateHistory resultado;
                try {
                    resultado = salesTrendService.getSameDateSalesHistory(
                            resolvedMes, resolvedDia, resolvedAnos, resolvedDimension, selectedItems);
                } catch (RuntimeException e) {
                    resultado = null;
                }
                yield !wantsPdf
                        ? ReportExporter.exportVentasFechaHistoricaExcel(resultado)
                        : unsupported(dataset, type);
            }
            case "articulos" -> {
                List<Articulos> filas = fetchArticulos(tab, q);
                yield wantsPdf
                        ? ReportExporter.exportArticulosPdf(filas)
                        : ReportExporter.exportArticulosExcel(filas);
            }
            case "promociones" -> !wantsPdf
                    ? exportPromociones(q)
                    : unsupported(dataset, type);
            case "reportes-movimientos" -> !wantsPdf
                    ? exportMovimientos(desde, hasta, usuario)
                    : unsupported(dataset, type);
            case "inventario-resumen" -> !wantsPdf
                    ? exportInventarioResumen(vistaResumen, familia, departamento, q)
                    : unsupported(dataset, type);
            case "reportes-ventas" -> !wantsPdf
                    ? exportVentasCajero(usuario, desde, hasta)
                    : unsupported(dataset, type);
            case "inventario" -> wantsPdf
                    ? ReportExporter.exportInventarioPdf(orEmpty(inventarioService.ListAllEnabled()))
                    : unsupported(dataset, type);
            case "stock-alerts" -> !wantsPdf
                    ? ReportExporter.exportStockAlertsExcel(orEmpty(stockAlertService.getActiveStockAlerts()))
                    : unsupported(dataset, type);
            case "profit-margins" -> !wantsPdf
                    ? ReportExporter.exportProfitMarginSnapshotsExcel(marginSnapshotsLast30Days())
                    : unsupported(dataset, type);
            case "familias" -> !wantsPdf
                    ? ReportExporter.exportFamiliasExcel(orEmpty(familiaService.listAll()))
                    : unsupported(dataset, type);
            case "departamentos" -> !wantsPdf
                    ? ReportExporter.exportDepartamentosExcel(orEmpty(departamentoService.listAllActive()))
                    : unsupported(dataset, type);
            case "reportes-familias" -> !wantsPdf
                    ? ReportExporter.exportVentasPorFamiliaExcel(ventasPorFamilia(desde, hasta))
                    : unsupported(dataset, type);
            case "reportes-departamentos" -> !wantsPdf
                    ? ReportExporter.exportVentasPorDepartamentoExcel(ventasPorDepartamento(desde, hasta))
                    : unsupported(dataset, type);
            default -> unsupported(dataset, type);
        };
    }

    // ── D15: missing datasets and tab-aware Articles export ───────────────

    /** Fetches the Articles tab's rows (capped) so the export matches the table. */
    private @Nonnull List<Articulos> fetchArticulos(@Nullable String tab, @Nullable String q) {
        String key = normalizeArticulosTab(tab);
        int size = 1000;
        int cap = 50000;
        List<Articulos> filas = new ArrayList<>();
        int offset = 0;
        while (filas.size() < cap) {
            List<Articulos> page = articulosService.pageByTab(key, q, null, "asc", offset, size);
            if (page == null || page.isEmpty()) {
                break;
            }
            filas.addAll(page);
            offset += page.size();
            if (page.size() < size) {
                break;
            }
        }
        if (filas.size() >= cap) {
            LOG.warn("Export de artículos truncado a " + cap + " filas");
        }
        return filas;
    }

    private static @Nonnull String normalizeArticulosTab(@Nullable String tab) {
        if (tab == null || tab.isBlank()) {
            return "activos";
        }
        return switch (tab.trim().toLowerCase(Locale.ROOT)) {
            case "inactivos", "catalogo", "procesados", "pendientes", "activos" ->
                    tab.trim().toLowerCase(Locale.ROOT);
            default -> "activos";
        };
    }

    private byte[] exportPromociones(@Nullable String q) throws IOException {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Object[]> rows = new ArrayList<>();
        for (Promocion promocion : orEmpty(promocionesService.listAll())) {
            String nombre = promocion.getNombre() == null ? "" : promocion.getNombre();
            if (!needle.isEmpty() && !nombre.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            rows.add(new Object[]{
                    nombre,
                    promocion.getDescuento(),
                    fmtFecha(promocion.getFechaInicio()),
                    fmtFecha(promocion.getFechaFin()),
                    promocion.isActiva()
            });
        }
        return ReportExporter.exportGenericExcel("Promociones",
                new String[]{"Nombre", "Descuento", "Inicio", "Fin", "Activa"}, rows);
    }

    private byte[] exportMovimientos(@Nullable String desde, @Nullable String hasta,
                                     @Nullable String usuario) throws IOException {
        Date inicio = fecha(desde, false);
        Date fin = fecha(hasta, true);
        if (inicio == null) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -7);
            inicio = cal.getTime();
        }
        if (fin == null) {
            fin = new Date();
        }
        Long userId = parseLong(usuario);
        List<Object[]> rows = new ArrayList<>();
        for (Inventario movimiento : orEmpty(inventarioService.findByDateRangeAndUserId(inicio, fin, userId))) {
            rows.add(new Object[]{
                    fmtFechaHora(movimiento.getFechaMovimiento()),
                    movimiento.getArticulo() != null ? movimiento.getArticulo().getCodigoBarra() : "",
                    movimiento.getArticulo() != null ? movimiento.getArticulo().getNombre() : "",
                    movimiento.getCantidad(),
                    movimiento.getUsuario() != null ? movimiento.getUsuario().getUsername() : "",
                    movimiento.getTipoMovimiento(),
                    movimiento.getNotas()
            });
        }
        return ReportExporter.exportGenericExcel("Movimientos de Inventario",
                new String[]{"Fecha", "Cod. Barra", "Artículo", "Cantidad", "Usuario", "Tipo", "Detalles"}, rows);
    }

    private byte[] exportInventarioResumen(@Nullable String vista, @Nullable String familia,
                                           @Nullable String departamento, @Nullable String q) throws IOException {
        String vistaKey = vista == null || vista.isBlank() ? "todos" : vista.trim().toLowerCase(Locale.ROOT);
        if (!vistaKey.equals("todos") && !vistaKey.equals("cero") && !vistaKey.equals("negativos")) {
            vistaKey = "todos";
        }
        Integer familiaId = parseInt(familia);
        Integer departamentoId = parseInt(departamento);
        long total = inventarioService.countResumenInventario(vistaKey, familiaId, departamentoId, q);
        int cap = 50000;
        int size = 1000;
        List<Object[]> rows = new ArrayList<>();
        for (int offset = 0; offset < Math.min(total, cap); offset += size) {
            List<Object[]> page = inventarioService.pageResumenInventario(
                    vistaKey, familiaId, departamentoId, q, null, "asc", offset, size);
            if (page == null || page.isEmpty()) {
                break;
            }
            List<Long> codigos = new ArrayList<>(page.size());
            for (Object[] fila : page) {
                codigos.add(((Number) fila[0]).longValue());
            }
            Map<Long, BigDecimal> precios = inventarioService.ultimosPreciosPorArticulo(codigos);
            for (Object[] fila : page) {
                long codigo = ((Number) fila[0]).longValue();
                rows.add(new Object[]{fila[0], fila[1], fila[2], fila[3], fila[4], fila[5], fila[6], precios.get(codigo)});
            }
        }
        if (total > cap) {
            LOG.warn("Export de resumen de inventario truncado a " + cap + " filas de " + total);
        }
        return ReportExporter.exportGenericExcel("Resumen de Inventario",
                new String[]{"Código", "Nombre", "Código Barra", "Familia", "Departamento", "Stock Actual", "Stock Óptimo", "Precio"}, rows);
    }

    private byte[] exportVentasCajero(@Nullable String usuario, @Nullable String desde,
                                      @Nullable String hasta) throws IOException {
        Date inicio = fecha(desde, false);
        Date fin = fecha(hasta, true);
        if (inicio == null) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -30);
            inicio = cal.getTime();
        }
        if (fin == null) {
            fin = new Date();
        }
        Long userId = parseLong(usuario);
        List<Object[]> rows = new ArrayList<>();
        if (userId != null) {
            Usuarios cajero = userService.find(userId);
            if (cajero != null) {
                for (ComprobantesEmitidos comprobante : orEmpty(comprobantesService.listAllEmitidosBy(cajero, inicio, fin))) {
                    if (comprobante.getDetalles() == null || comprobante.getDetalles().getLineasDetalle() == null) {
                        continue;
                    }
                    for (LineaDetalle linea : comprobante.getDetalles().getLineasDetalle()) {
                        rows.add(new Object[]{linea.getDetalle(), linea.getCantidad(), linea.getMontoTotalLinea()});
                    }
                }
            }
        }
        return ReportExporter.exportGenericExcel("Ventas por Cajero",
                new String[]{"Nombre", "Cantidad", "Precio"}, rows);
    }

    private static @Nullable Long parseLong(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            LOG.debug("Export: valor numérico inválido: " + value);
            return null;
        }
    }

    private static @Nullable Integer parseInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            LOG.debug("Export: valor numérico inválido: " + value);
            return null;
        }
    }

    private static @Nonnull String fmtFechaHora(@Nullable Date date) {
        if (date == null) {
            return "";
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    private static @Nonnull String fmtFecha(@Nullable Date date) {
        if (date == null) {
            return "";
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /**
     * Mirrors ProfitAnalysisController's general-analysis defaults: margin
     * snapshots for the last 30 days (null name = all departments/families).
     */
    @Nonnull
    private List<CorteMargen> marginSnapshotsLast30Days() {
        Date endDate = new Date();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        Date startDate = cal.getTime();
        return orEmpty(profitAnalysisService.getMarginTrend(null, "department", startDate, endDate));
    }

    /**
     * Sales-by-family rows for the reportes-familias dataset, mirroring
     * FamiliasResource: both {@code desde} and {@code hasta} are required (the
     * report page shows an empty table otherwise); blank/invalid dates yield an
     * empty workbook.
     */
    @Nonnull
    private List<ReportesFamiliasYDepartamentos> ventasPorFamilia(@Nullable String desde, @Nullable String hasta) {
        Date inicio = fecha(desde, false);
        Date fin = fecha(hasta, true);
        if (inicio == null || fin == null) {
            return new ArrayList<>();
        }
        return orEmpty(inventarioService.getTotalSalesByFamilia(inicio, fin));
    }

    /** Sales-by-department twin of {@link #ventasPorFamilia}. */
    @Nonnull
    private List<ReportesFamiliasYDepartamentos> ventasPorDepartamento(@Nullable String desde, @Nullable String hasta) {
        Date inicio = fecha(desde, false);
        Date fin = fecha(hasta, true);
        if (inicio == null || fin == null) {
            return new ArrayList<>();
        }
        return orEmpty(inventarioService.getTotalSalesByDepartamento(inicio, fin));
    }

    /** Parses an ISO {@code yyyy-MM-dd} input into a Date; null when blank/invalid. */
    @Nullable
    private static Date fecha(@Nullable String iso, boolean finDeDia) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(iso.trim());
            if (finDeDia) {
                return Date.from(localDate.atTime(23, 59, 59)
                        .atZone(ZoneId.systemDefault()).toInstant());
            }
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException e) {
            // Intentionally silent (AGENTS.md logging rule): an unparsable or
            // out-of-range date is caller-data noise, not an application failure;
            // the returned null becomes the explicit "invalid date range" error
            // in the report builders, which is where it gets reported.
            return null;
        }
    }

    private static byte[] unsupported(@Nonnull String dataset, @Nonnull String type) throws UnsupportedDatasetException {
        throw new UnsupportedDatasetException(
                "El dataset '" + dataset + "' no soporta el tipo '" + type + "'");
    }

    /**
     * Exporta las facturas emitidas del reporte.
     *
     * <p>Con el historico completo el reporte tiene cientos de miles de
     * comprobantes: exportarlos todos no cabe en el tiempo ni en la memoria,
     * asi que se exporta el primer bloque y se deja constancia en el libro
     * de cuantas filas hay en total.</p>
     */
    private byte[] exportarFacturas(@Nullable String q) throws IOException {
        final int tope = 20000;
        long total = comprobantesService.countFacturasReporte(q);
        List<ComprobantesEmitidos> filas =
                orEmpty(comprobantesService.listFacturasReportePage(null, "asc", q, 0, tope));
        return ReportExporter.exportFacturasExcel(filas, total);
    }

    private static <T> @Nonnull List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }

    @Nonnull
    private static Response badRequest(@Nonnull String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error("VALIDATION_ERROR", message))
                .build();
    }

    @Nonnull
    private static Response notFound(@Nonnull String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error("NOT_FOUND", message))
                .build();
    }

    @Nonnull
    private static Response serverError(@Nonnull String message) {
        return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error("INTERNAL_ERROR", message))
                .build();
    }

    /** Signals an unknown dataset key or a format the dataset does not offer. */
    private static final class UnsupportedDatasetException extends Exception {
        UnsupportedDatasetException(@Nonnull String message) {
            super(message);
        }
    }
}
