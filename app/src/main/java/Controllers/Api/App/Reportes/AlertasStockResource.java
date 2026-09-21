package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.Departamento;
import Models.Familia;
import Models.ReorderSuggestion;
import Models.StockAlert;
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
 * {@link StockAlertService#getActiveStockAlerts()},
 * {@link StockAlertService#getAllReorderSuggestions()} /
 * {@link StockAlertService#getReorderSuggestionsByPriority(String)} and
 * {@link StockAlertService#getAlertStatistics()} — the same reads the legacy
 * {@code stockAlertController.init()} performed, mapped into display rows with
 * the same field flattening as the pre-existing read-side StockAlertDTO.</p>
 *
 * <p>READ-ONLY by design: the legacy acknowledge/resolve/check actions are
 * mutations and stay with the legacy controller until plan task T33 ports the
 * full StockAlert module. The export button posts the T17-registered
 * {@code stock-alerts} dataset to {@code POST /api/app/export}.</p>
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
            @QueryParam("seccion") @Nullable String seccion) {

        String vista = "sugerencias".equals(seccion) ? "sugerencias" : "alertas";
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("departamento", departamento);
        filtros.put("prioridad", prioridad);
        filtros.put("seccion", vista);

        List<Map<String, Object>> filas = new ArrayList<>();
        if ("sugerencias".equals(vista)) {
            List<ReorderSuggestion> sugerencias =
                    prioridad != null && !prioridad.isBlank()
                            ? stockAlertService.getReorderSuggestionsByPriority(prioridad.trim())
                            : stockAlertService.getAllReorderSuggestions();
            if (sugerencias != null) {
                for (ReorderSuggestion sugerencia : sugerencias) {
                    filas.add(Tablas.fila(
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
        } else {
            List<StockAlert> alertas = stockAlertService.getActiveStockAlerts();
            if (alertas != null) {
                for (StockAlert alerta : alertas) {
                    filas.add(toFila(alerta));
                }
            }
        }

        // Department filter mirrors the legacy getStockAlertsByDepartment path.
        if (departamento != null && !departamento.isBlank() && "alertas".equals(vista)) {
            Departamento depto = departamentoService.findByName(departamento.trim());
            if (depto != null) {
                List<StockAlert> filtradas = stockAlertService.getStockAlertsByDepartment(depto);
                filas.clear();
                if (filtradas != null) {
                    for (StockAlert alerta : filtradas) {
                        filas.add(toFila(alerta));
                    }
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        // Header stats (legacy stat-cards).
        Map<String, Integer> estadisticas = stockAlertService.getAlertStatistics();
        int sinStock = valor(estadisticas, "out_of_stock");
        int stockBajo = valor(estadisticas, "low_stock");
        int sugerenciasCount = valor(estadisticas, "reorder_suggestion");
        List<StockAlert> alertasActivas = stockAlertService.getActiveStockAlerts();
        int totalAlertas = alertasActivas != null ? alertasActivas.size() : 0;

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
        model.put("filas", Tablas.paginaDe(filas, page, size));
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
    private static Map<String, Object> toFila(@Nonnull StockAlert alerta) {
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
                    Map.of("label", "Fecha Creación", "key", "fechaCreacion"));
        }
        return List.of(
                Map.of("label", "Tipo", "key", "tipo"),
                Map.of("label", "Artículo", "key", "articulo"),
                Map.of("label", "Código", "key", "codigoBarra"),
                Map.of("label", "Stock Actual", "key", "cantidadActual"),
                Map.of("label", "Stock Mínimo", "key", "cantidadMinima"),
                Map.of("label", "Stock Óptimo", "key", "stockOptimo"),
                Map.of("label", "Fecha Creación", "key", "fechaCreacion"),
                Map.of("label", "Estado", "key", "estado"));
    }
}
