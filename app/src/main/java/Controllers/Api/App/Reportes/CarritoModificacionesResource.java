package Controllers.Api.App.Reportes;

import Models.CarritoModificacion;
import Services.CarritoModificacionService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporte de cambios de carrito del POS.
 *
 * <p>Muestra la bitacora interna que llena {@link CarritoModificacionService}:
 * lineas que el cajero quito, ventas a medio hacer que cancelo y precios que
 * ajusto. Solo lectura; no toca Hacienda ni inventario.</p>
 */
@Path("/app/reportes/carrito-modificaciones")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "registro"})
public class CarritoModificacionesResource {

    private static final String BASE_URL = "/app/reportes/carrito-modificaciones";

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final List<Map<String, String>> ACCIONES = List.of(
            Map.of("valor", CarritoModificacion.ACCION_LINEA_ELIMINADA,
                    "etiqueta", "Linea eliminada"),
            Map.of("valor", CarritoModificacion.ACCION_CARRITO_CANCELADO,
                    "etiqueta", "Carrito cancelado"),
            Map.of("valor", CarritoModificacion.ACCION_PRECIO_AJUSTADO,
                    "etiqueta", "Precio ajustado"));

    @Inject
    CarritoModificacionService service;

    @Inject
    @Location("pages/reportes/carrito-modificaciones")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/carrito-modificaciones")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("25") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("desc") String dir,
            @QueryParam("desde") @Nullable String desde,
            @QueryParam("hasta") @Nullable String hasta,
            @QueryParam("usuario") @Nullable String usuario,
            @QueryParam("accion") @Nullable String accion,
            @QueryParam("q") @Nullable String q) {

        LocalDate fechaDesde = fecha(desde);
        LocalDate fechaHasta = fecha(hasta);
        LocalDateTime desdeTs = fechaDesde != null ? fechaDesde.atStartOfDay() : null;
        LocalDateTime hastaTs = fechaHasta != null ? fechaHasta.plusDays(1).atStartOfDay() : null;

        int tamPagina = Math.min(Math.max(size, 1), 200);
        int paginaActual = Math.max(page, 1);
        long total = service.contar(desdeTs, hastaTs, usuario, accion, q);
        long desplazamiento = (long) (paginaActual - 1) * tamPagina;
        int offset = desplazamiento > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) desplazamiento;

        List<Map<String, Object>> filas = new ArrayList<>();
        for (CarritoModificacion fila : service.pagina(desdeTs, hastaTs, usuario, accion, q,
                sort, dir, offset, tamPagina)) {
            filas.add(toFila(fila));
        }

        Map<String, Long> conteo = service.conteoPorAccion(desdeTs, hastaTs, usuario, accion, q);

        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("desde", fechaDesde != null ? fechaDesde.toString() : null);
        filtros.put("hasta", fechaHasta != null ? fechaHasta.toString() : null);
        filtros.put("usuario", usuario);
        filtros.put("accion", accion);
        filtros.put("q", q);

        int totalPages = Tablas.totalPaginas(total, tamPagina);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Cambios de carrito");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
        model.put("filas", filas);
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", paginaActual);
        model.put("size", tamPagina);
        model.put("total", total);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(paginaActual, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);
        model.put("usuarios", service.usuarios());
        model.put("acciones", ACCIONES);
        model.put("totalLineas", conteo.getOrDefault(CarritoModificacion.ACCION_LINEA_ELIMINADA, 0L));
        model.put("totalCarritos", conteo.getOrDefault(CarritoModificacion.ACCION_CARRITO_CANCELADO, 0L));
        model.put("totalPrecios", conteo.getOrDefault(CarritoModificacion.ACCION_PRECIO_AJUSTADO, 0L));

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static Map<String, Object> toFila(@Nonnull CarritoModificacion m) {
        return Tablas.fila(
                "fecha", m.getFecha() != null ? FECHA.format(m.getFecha()) : "-",
                "usuario", texto(m.getUsuario()),
                "accion", etiquetaAccion(m.getAccion()),
                "articulo", texto(m.getArticuloNombre()),
                "codigo", texto(m.getArticuloCodigo()),
                "cantidad", Tablas.fmtNumero(m.getCantidad()),
                "precio", Tablas.fmtColones(m.getPrecioUnitario()),
                "importe", Tablas.fmtColones(m.getImporte()),
                "cliente", texto(m.getCliente()),
                "detalle", texto(m.getDetalle()));
    }

    @Nonnull
    private static String etiquetaAccion(@Nullable String accion) {
        if (accion == null) {
            return "-";
        }
        return switch (accion) {
            case CarritoModificacion.ACCION_LINEA_ELIMINADA -> "Linea eliminada";
            case CarritoModificacion.ACCION_CARRITO_CANCELADO -> "Carrito cancelado";
            case CarritoModificacion.ACCION_PRECIO_AJUSTADO -> "Precio ajustado";
            default -> accion;
        };
    }

    @Nonnull
    private static String texto(@Nullable String valor) {
        return valor == null || valor.isBlank() ? "-" : valor;
    }

    @Nullable
    private static LocalDate fecha(@Nullable String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nonnull
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Fecha", "key", "fecha"),
                Map.of("label", "Usuario", "key", "usuario"),
                Map.of("label", "Accion", "key", "accion"),
                Map.of("label", "Articulo", "key", "articulo"),
                Map.of("label", "Codigo", "key", "codigo"),
                Map.of("label", "Cantidad", "key", "cantidad", "num", true),
                Map.of("label", "Precio", "key", "precio", "num", true),
                Map.of("label", "Importe", "key", "importe", "num", true),
                Map.of("label", "Cliente", "key", "cliente"),
                Map.of("label", "Detalle"));
    }
}
