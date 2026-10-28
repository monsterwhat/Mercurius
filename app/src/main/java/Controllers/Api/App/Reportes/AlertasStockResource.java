package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.Departamento;
import Models.Familia;
import Models.SugerenciaReposicion;
import Models.AlertaStock;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.StockAlertService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Alertas de Stock report for the NEW app surface — port of
 * {@code secured/pages/Inventario/Reportes/Alertas/index.xhtml}
 * (plan task T19, READ-ONLY).
 *
 * <p>Backed DIRECTLY by the existing {@link StockAlertService}:
 * {@link StockAlertService#countReportAlerts} /
 * {@link StockAlertService#pageReportAlerts} for the alert table,
 * {@link StockAlertService#getAllReorderSuggestions()} /
 * {@link StockAlertService#getReorderSuggestionsByPriority(String)} for the
 * sugerencias section and {@link StockAlertService#getAlertStatistics()} for
 * the header cards — the same reads the legacy
 * {@code stockAlertController.init()} performed, mapped into display rows with
 * the same field flattening as the pre-existing read-side StockAlertDTO.</p>
 *
 * <p>READ-ONLY by design: the legacy acknowledge/resolve/check actions are
 * mutations and stay with the legacy controller until plan task T33 ports the
 * full AlertaStock module. The export button posts the T17-registered
 * {@code stock-alerts} dataset to {@code POST /api/app/export}.</p>
 *
 * <p>The alertas table is filtered, sorted and paged in SQL through
 * {@link StockAlertService#countReportAlerts} /
 * {@link StockAlertService#pageReportAlerts}: the page used to materialise the
 * whole active-alert set twice (rows + "Total alertas") and slice it in
 * memory, which cost ~15.7 s with 200k {@code stock_alerts} rows. The
 * sugerencias section still runs its legacy in-memory pipeline (different
 * table, no hot path).</p>
 */
@Path("/app/reportes/inventario/alertas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class AlertasStockResource {

    private static final String BASE_URL = "/app/reportes/inventario/alertas";

    @Nonnull
    @Inject
    StockAlertService stockAlertService;

    @Nonnull
    @Inject
    DepartamentoService departamentoService;

    @Nonnull
    @Inject
    FamiliaService familiaService;

    @Location("pages/reportes/alertas")
    @Inject
    Template pagina;

    @Location("pages/reportes/_tablas/alertas")
    @Inject
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("departamento") @Nullable String departamento,
            @QueryParam("prioridad") @Nullable String prioridad,
            @QueryParam("tipo") @Nullable String tipo,
            @QueryParam("estado") @Nullable String estado,
            @QueryParam("q") @Nullable String q,
            @QueryParam("seccion") @Nullable String seccion) {

        String vista = "sugerencias".equals(seccion) ? "sugerencias" : "alertas";
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("departamento", departamento);
        filtros.put("prioridad", prioridad);
        filtros.put("tipo", tipo);
        filtros.put("estado", estado);
        filtros.put("q", q);
        filtros.put("seccion", vista);

        List<Map<String, Object>> filas;
        long totalFilas;
        if ("sugerencias".equals(vista)) {
            // Sugerencias still comes from its own table through the legacy
            // read; filter/sort/page stay in memory (not the stock_alerts hot
            // path).
            List<Map<String, Object>> sugerenciasFilas = new ArrayList<>();
            List<SugerenciaReposicion> sugerencias =
                    prioridad != null && !prioridad.isBlank()
                            ? stockAlertService.getReorderSuggestionsByPriority(prioridad.trim())
                            : stockAlertService.getAllReorderSuggestions();
            if (sugerencias != null) {
                for (SugerenciaReposicion sugerencia : sugerencias) {
                    if (departamento != null && !departamento.isBlank()) {
                        Departamento depto = departamentoService.findByName(departamento.trim());
                        if (depto == null
                                || sugerencia.getDepartamento() == null
                                || depto.getId() != sugerencia.getDepartamento().getId()) {
                            continue;
                        }
                    }
                    sugerenciasFilas.add(Tablas.fila(
                            "articulo", sugerencia.getArticulo() != null
                                    ? sugerencia.getArticulo().getNombre() : "-",
                            "codigoBarra", sugerencia.getArticulo() != null
                                    ? sugerencia.getArticulo().getCodigoBarra() : "-",
                            "cantidadSugerida", sugerencia.getCantidadSugerida() != null
                                    ? sugerencia.getCantidadSugerida().toString() : "-",
                            "prioridad", etiquetaPrioridad(sugerencia.getPrioridad()),
                            "costoTotalEstimado",
                            Tablas.fmtColones(sugerencia.getCostoTotalEstimado()),
                            "promedioVentasMensual",
                            Tablas.fmtNumero(sugerencia.getPromedioVentasMensual()),
                            "fechaCreacion", Tablas.fmtFechaHora(sugerencia.getFechaCreacion())));
                }
            }
            Tablas.ordenar(sugerenciasFilas, sort, dir);
            totalFilas = sugerenciasFilas.size();
            filas = Tablas.paginaDe(sugerenciasFilas, page, size);
        } else {
            // Alertas: COUNT + one page in SQL. The clamping mirrors
            // Tablas.paginaDe (size 1..200, page >= 1) so the pager keeps the
            // same window semantics.
            int tamPagina = Math.min(Math.max(size, 1), 200);
            long desde = (long) (Math.max(page, 1) - 1) * tamPagina;
            int offset = desde > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) desde;
            totalFilas = stockAlertService.countReportAlerts(q, tipo, estado, departamento);
            List<AlertaStock> pagina = stockAlertService.pageReportAlerts(
                    q, tipo, estado, departamento, sort, dir, offset, tamPagina);
            filas = new ArrayList<>(pagina.size());
            for (AlertaStock alerta : pagina) {
                filas.add(toFila(alerta));
            }
        }

        int totalPages = Tablas.totalPaginas(totalFilas, size);

        // Header stats (legacy stat-cards). getAlertStatistics is already a
        // GROUP BY; "Total alertas" used to be getActiveStockAlerts().size().
        Map<String, Integer> estadisticas = stockAlertService.getAlertStatistics();
        int sinStock = valor(estadisticas, "out_of_stock");
        int stockBajo = valor(estadisticas, "low_stock");
        int sobrestock = valor(estadisticas, "overstock");
        int sugerenciasCount = valor(estadisticas, "reorder_suggestion");
        int totalAlertas;
        boolean sinFiltros = esBlanco(q) && esBlanco(tipo) && esBlanco(estado) && esBlanco(departamento);
        if (sinFiltros && "alertas".equals(vista)) {
            // Same predicate as the table COUNT: reuse it instead of a second
            // full scan just for the KPI card.
            totalAlertas = aInt(totalFilas);
        } else {
            totalAlertas = aInt(stockAlertService.countReportAlerts(null, null, null, null));
        }

        List<String> nombresDepartamentos = new ArrayList<>();
        List<Departamento> departamentosEntidad = departamentoService.listAll();
        if (departamentosEntidad != null) {
            for (Departamento entidad : departamentosEntidad) {
                nombresDepartamentos.add(entidad.getNombre());
            }
        }
        List<String> nombresFamilias = new ArrayList<>();
        List<Familia> familiasEntidad = familiaService.listAll();
        if (familiasEntidad != null) {
            for (Familia entidad : familiasEntidad) {
                nombresFamilias.add(entidad.getNombre());
            }
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Alertas de Stock");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas(vista));
        model.put("filas", filas);
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", Math.max(page, 1));
        model.put("size", size);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(page, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);
        model.put("seccion", vista);
        model.put("departamentos", nombres(nombresDepartamentos));
        model.put("familias", nombres(nombresFamilias));
        model.put("sinStock", sinStock);
        model.put("stockBajo", stockBajo);
        model.put("sobrestock", sobrestock);
        model.put("sugerencias", sugerenciasCount);
        model.put("totalAlertas", totalAlertas);

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /** Read-side mapping identical in spirit to the pre-existing StockAlertDTO. */
    @Nonnull
    private static Map<String, Object> toFila(@Nonnull AlertaStock alerta) {
        return Tablas.fila(
                "tipo", etiquetaTipo(alerta.getTipoAlerta()),
                "articulo", alerta.getArticulo() != null
                        ? alerta.getArticulo().getNombre() : "-",
                "codigoBarra", alerta.getArticulo() != null
                        ? alerta.getArticulo().getCodigoBarra() : "-",
                "cantidadActual", alerta.getCantidadActual() != null
                        ? alerta.getCantidadActual().toString() : "-",
                "cantidadMinima", alerta.getCantidadMinima() != null
                        ? alerta.getCantidadMinima().toString() : "-",
                "stockOptimo", alerta.getArticulo() != null
                        && alerta.getArticulo().getStockOptimo() != null
                        ? alerta.getArticulo().getStockOptimo().toString() : "-",
                "fechaCreacion", Tablas.fmtFechaHora(alerta.getFechaCreacion()),
                "estado", etiquetaEstado(alerta.getEstado()));
    }

    @Nonnull
    private static String etiquetaTipo(@Nullable String tipo) {
        if (tipo == null) {
            return "-";
        }
        return switch (tipo) {
            case "out_of_stock" -> "Sin Stock";
            case "low_stock" -> "Stock Bajo";
            case "overstock" -> "Sobrestock";
            case "reorder_suggestion" -> "Sugerencia";
            default -> tipo;
        };
    }

    @Nonnull
    private static String etiquetaEstado(@Nullable String estado) {
        if (estado == null) {
            return "-";
        }
        return switch (estado) {
            case "active" -> "Activa";
            case "acknowledged" -> "Reconocida";
            case "resolved" -> "Resuelta";
            default -> estado;
        };
    }

    @Nonnull
    private static String etiquetaPrioridad(@Nullable String prioridad) {
        if (prioridad == null) {
            return "-";
        }
        return switch (prioridad) {
            case "urgent" -> "Urgente";
            case "high" -> "Alta";
            case "medium" -> "Media";
            case "low" -> "Baja";
            default -> prioridad;
        };
    }

    private static int valor(@Nullable Map<String, Integer> estadisticas, @Nonnull String clave) {
        if (estadisticas == null) {
            return 0;
        }
        Integer valor = estadisticas.get(clave);
        return valor != null ? valor : 0;
    }

    /** Saturating long → int for the KPI counter (counts only, never negative). */
    private static int aInt(long valor) {
        return valor > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(valor, 0L);
    }

    private static boolean esBlanco(@Nullable String valor) {
        return valor == null || valor.isBlank();
    }

    @Nonnull
    private static List<String> nombres(@Nullable List<String> nombresEntidad) {
        List<String> limpios = new ArrayList<>();
        if (nombresEntidad != null) {
            for (String nombre : nombresEntidad) {
                if (nombre != null && !nombre.isBlank() && !limpios.contains(nombre)) {
                    limpios.add(nombre);
                }
            }
        }
        return limpios.stream().sorted().toList();
    }

    @Nonnull
    private static List<Map<String, Object>> columnas(@Nonnull String vista) {
        if ("sugerencias".equals(vista)) {
            return List.of(
                    Map.of("label", "Artículo", "key", "articulo"),
                    Map.of("label", "Código", "key", "codigoBarra"),
                    Map.of("label", "Cantidad Sugerida", "key", "cantidadSugerida"),
                    Map.of("label", "Prioridad", "key", "prioridad"),
                    Map.of("label", "Costo Estimado", "key", "costoTotalEstimado"),
                    Map.of("label", "Promedio Ventas Mensual", "key", "promedioVentasMensual"),
                    Map.of("label", "Fecha de creación", "key", "fechaCreacion"));
        }
        return List.of(
                Map.of("label", "Tipo", "key", "tipo"),
                Map.of("label", "Artículo", "key", "articulo"),
                Map.of("label", "Código", "key", "codigoBarra"),
                Map.of("label", "Stock Actual", "key", "cantidadActual"),
                Map.of("label", "Stock Mínimo", "key", "cantidadMinima"),
                Map.of("label", "Stock Óptimo", "key", "stockOptimo"),
                Map.of("label", "Fecha de creación", "key", "fechaCreacion"),
                Map.of("label", "Estado", "key", "estado"));
    }
}
