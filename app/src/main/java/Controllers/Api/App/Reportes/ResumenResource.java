package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.Articulos.ArticuloStock;
import Models.Articulos.Articulos;
import Models.DTO.ReportChartPayload;
import Models.DTO.ReportChartSeries;
import Models.Departamento;
import Models.Familia;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.InventarioService;
import Utils.ReportChartJson;
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
 * Resumen de Inventario for the NEW app surface — port of
 * {@code secured/pages/Inventario/Resumen/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Backed DIRECTLY by the same services the legacy
 * {@code InventarioResumenController} used: {@link ArticulosService},
 * {@link FamiliaService}, {@link DepartamentoService} and
 * {@link InventarioService#getAllStock()} (stock matched by barcode, exactly
 * like the legacy {@code getStockForArticulo}). The legacy p:tabView becomes a
 * {@code vista} query param (todos|cero|negativos); the stat-cards are links
 * that switch it server-side.</p>
 */
@Path("/app/reportes/inventario/resumen")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class ResumenResource {

    private static final String BASE_URL = "/app/reportes/inventario/resumen";

    @Inject
    @Nonnull
    ArticulosService articulosService;

    @Inject
    @Nonnull
    FamiliaService familiaService;

    @Inject
    @Nonnull
    DepartamentoService departamentoService;

    @Inject
    @Nonnull
    InventarioService inventarioService;

    @Inject
    @Location("pages/reportes/resumen")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/resumen")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("15") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q,
            @QueryParam("familia") @Nullable String familia,
            @QueryParam("departamento") @Nullable String departamento,
            @QueryParam("vista") @Nullable String vista) {

        String tipoVista = normalizarVista(vista);
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("q", q);
        filtros.put("familia", familia);
        filtros.put("departamento", departamento);
        filtros.put("vista", tipoVista);

        List<Familia> familiasEntidad = familiaService.listAll();
        List<Departamento> departamentosEntidad = departamentoService.listAll();
        if (familiasEntidad == null) {
            familiasEntidad = new ArrayList<>();
        }
        if (departamentosEntidad == null) {
            departamentosEntidad = new ArrayList<>();
        }
        List<ArticuloStock> stocks = inventarioService.getAllStock();
        List<Articulos> articulos = articulosService.ListAllEnabled();

        // The stock snapshot is matched by barcode. Looking it up with a
        // stream per article made this report O(articles × stock) — at 252k
        // articles it never finished. One map turns it into O(n).
        Map<String, BigDecimal> stockPorBarcode = new HashMap<>();
        if (stocks != null) {
            for (ArticuloStock stock : stocks) {
                if (stock.getCodigoBarra() != null) {
                    stockPorBarcode.put(stock.getCodigoBarra(), stock.getStock());
                }
            }
        }

        Integer familiaId = entero(familia);
        Integer departamentoId = entero(departamento);
        String filtro = q != null ? q.trim().toLowerCase() : null;

        List<Map<String, Object>> filas = new ArrayList<>();
        long ceroCount = 0;
        long negativoCount = 0;
        Map<String, BigDecimal> stockPorFamilia = new LinkedHashMap<>();
        if (articulos != null) {
            for (Articulos articulo : articulos) {
                BigDecimal stock = articulo.getCodigoBarra() == null
                        ? BigDecimal.ZERO
                        : stockPorBarcode.getOrDefault(articulo.getCodigoBarra(), BigDecimal.ZERO);
                if (stock.compareTo(BigDecimal.ZERO) == 0) {
                    ceroCount++;
                } else if (stock.compareTo(BigDecimal.ZERO) < 0) {
                    negativoCount++;
                }
                if (!pasaVista(tipoVista, stock)
                        || !pasaFamilia(articulo, familiaId)
                        || !pasaDepartamento(articulo, departamentoId)
                        || !pasaTexto(articulo, filtro)) {
                    continue;
                }
                filas.add(Tablas.fila(
                        "codigo", articulo.getCodigo(),
                        "nombre", articulo.getNombre(),
                        "codigoBarra", articulo.getCodigoBarra(),
                        "familia", articulo.getFamilia() != null
                                ? articulo.getFamilia().getNombre() : "-",
                        "departamento", articulo.getDepartamento() != null
                                ? articulo.getDepartamento().getNombre() : "-",
                        "stockActual", Tablas.fmtNumero(stock),
                        "stockOptimo", articulo.getStockOptimo() != null
                                ? articulo.getStockOptimo().toString() : "-",
                        // getLastPrecioArticulo() NPEs on price-less articles; guard it.
                        "precio", articulo.getLastPrecio() != null
                                && articulo.getLastPrecio().getPrecioFinal() != null
                                ? Tablas.fmtColones(articulo.getLastPrecio().getPrecioFinal())
                                : "-"));
                String familiaNombre = articulo.getFamilia() != null
                        && articulo.getFamilia().getNombre() != null
                        && !articulo.getFamilia().getNombre().isBlank()
                        ? articulo.getFamilia().getNombre()
                        : "Sin familia";
                BigDecimal acumulado = stockPorFamilia.getOrDefault(familiaNombre, BigDecimal.ZERO);
                stockPorFamilia.put(familiaNombre, acumulado.add(stock));
            }
        }

        List<String> chartLabels = new ArrayList<>();
        List<BigDecimal> chartValues = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : stockPorFamilia.entrySet()) {
            chartLabels.add(entry.getKey());
            chartValues.add(entry.getValue());
        }

        // P2 Phase 2.1 contract: one typed payload, serialized once by the
        // shared helper. The template only carries the marker + JSON script tag;
        // it never concatenates labels or values into JavaScript.
        ReportChartPayload chartPayload = new ReportChartPayload(
                "datos-grafica-resumen",
                "bar",
                "Stock Actual",
                chartLabels,
                List.of(new ReportChartSeries("Stock Actual", chartValues)),
                false,
                "Sin datos para el período");

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Resumen de Inventario");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
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
        model.put("vista", tipoVista);
        model.put("chartPayload", chartPayload);
        model.put("chartJson", ReportChartJson.toScriptJson(chartPayload));
        model.put("todosCount", articulosService.countActivos());
        model.put("ceroCount", ceroCount);
        model.put("negativoCount", negativoCount);
        model.put("familias", opciones(familiasEntidad.stream()
                .map(Familia::getId).toList(), familiasEntidad.stream()
                .map(Familia::getNombre).toList()));
        model.put("departamentos", opciones(departamentosEntidad.stream()
                .map(Departamento::getId).toList(), departamentosEntidad.stream()
                .map(Departamento::getNombre).toList()));

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static String normalizarVista(@Nullable String vista) {
        if (vista == null) {
            return "todos";
        }
        return switch (vista) {
            case "cero", "negativos" -> vista;
            default -> "todos";
        };
    }

    private static boolean pasaVista(@Nonnull String vista, @Nonnull BigDecimal stock) {
        return switch (vista) {
            case "cero" -> stock.compareTo(BigDecimal.ZERO) == 0;
            case "negativos" -> stock.compareTo(BigDecimal.ZERO) < 0;
            default -> true;
        };
    }

    private static boolean pasaFamilia(@Nonnull Articulos articulo, @Nullable Integer familiaId) {
        if (familiaId == null || familiaId <= 0) {
            return true;
        }
        return articulo.getFamilia() != null
                && articulo.getFamilia().getId() == familiaId;
    }

    private static boolean pasaDepartamento(@Nonnull Articulos articulo,
            @Nullable Integer departamentoId) {
        if (departamentoId == null || departamentoId <= 0) {
            return true;
        }
        return articulo.getDepartamento() != null
                && articulo.getDepartamento().getId() == departamentoId;
    }

    /** Legacy globalFilterFunction parity over codigo/nombre/barra/familia/depto. */
    private static boolean pasaTexto(@Nonnull Articulos articulo, @Nullable String filtro) {
        if (filtro == null || filtro.isEmpty()) {
            return true;
        }
        return String.valueOf(articulo.getCodigo()).contains(filtro)
                || (articulo.getNombre() != null
                        && articulo.getNombre().toLowerCase().contains(filtro))
                || (articulo.getCodigoBarra() != null
                        && articulo.getCodigoBarra().toLowerCase().contains(filtro))
                || (articulo.getFamilia() != null
                        && articulo.getFamilia().getNombre() != null
                        && articulo.getFamilia().getNombre().toLowerCase().contains(filtro))
                || (articulo.getDepartamento() != null
                        && articulo.getDepartamento().getNombre() != null
                        && articulo.getDepartamento().getNombre().toLowerCase().contains(filtro));
    }

    @Nullable
    private static Integer entero(@Nullable String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nonnull
    private static List<Map<String, Object>> opciones(@Nonnull List<?> ids,
            @Nonnull List<String> nombres) {
        List<Map<String, Object>> opciones = new ArrayList<>();
        for (int i = 0; i < ids.size() && i < nombres.size(); i++) {
            if (nombres.get(i) != null) {
                opciones.add(Tablas.fila("id", ids.get(i), "nombre", nombres.get(i)));
            }
        }
        return opciones;
    }

    @Nonnull
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Codigo", "key", "codigo"),
                Map.of("label", "Nombre", "key", "nombre"),
                Map.of("label", "Codigo Barra", "key", "codigoBarra"),
                Map.of("label", "Familia", "key", "familia"),
                Map.of("label", "Departamento", "key", "departamento"),
                Map.of("label", "Stock Actual", "key", "stockActual"),
                Map.of("label", "Stock Optimo", "key", "stockOptimo"),
                Map.of("label", "Precio", "key", "precio"));
    }
}
