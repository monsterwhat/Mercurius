package Controllers.Api.App;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import Models.Articulos.Articulos;
import Models.Departamento;
import Models.DTO.ApiResponse;
import Models.DTO.StockAlertConfigDTO;
import Models.DTO.StockAlertDTO;
import Models.StockAlert;
import Services.ArticulosService;
import Services.StockAlertService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Threshold-configuration surface for the stock-alert engine — plan task T33
 * (StockAlert CONFIG portion of the Qute/HTMX migration).
 *
 * <p>{@link StockAlertService} manages thresholds at two levels and nothing
 * else (see the note in {@code AppSettingsDTO}: there is NO global settings
 * row): engine constants hardcoded in {@code calculateOptimalStock()} /
 * {@code calculateReorderQuantity()}, exposed READ-ONLY by GET; and the
 * per-article thresholds {@code Articulos.diasStockSeguridad} /
 * {@code estadoAlertas} (+ service-written-back {@code stockOptimo}), which are
 * the writable surface of PUT {@code ?articulo=}.</p>
 *
 * <p><b>Guard semantics mirrored verbatim from the legacy
 * {@code Controllers.StockAlertController}:</b> its
 * {@code if (!currentSession.isValid())} → FacesMessage("Sesión Inválida",
 * "No tiene permisos para realizar esta acción") becomes
 * {@code @RolesAllowed({"admin","inventario"})} plus an explicit anonymous
 * check → 401 {@code SESSION_INVALID} with the SAME Spanish message; its
 * {@code catch (RuntimeException)} → FacesMessage(ERROR, ...) becomes 500
 * {@code INTERNAL_ERROR} with the same message shape. The legacy controller has
 * NO threshold-update action post-T17, so there is no legacy audit call
 * ({@code alertasService.registrarAlerta}) to mirror for PUT — persistence goes
 * through the existing {@link ArticulosService#update(Articulos)} merge, never
 * through service edits.</p>
 *
 * <p><b>Dual channel (ui-kit §5 Pattern A + golden rule #9):</b> API consumers
 * get JSON {@link ApiResponse} envelopes; HTMX requests ({@code HX-Request}
 * header) get the re-rendered {@code #alerta-config-forma} fragment plus an
 * out-of-band kit toast instead. CSRF is inherited from the layout's
 * {@code hx-headers}; the form-urlencoded PUT channel additionally satisfies
 * the rest-csrf form-field rule.</p>
 */
@Path("/api/app/stock-alert-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
public class StockAlertConfigResource {

    private static final Logger LOG = Logger.getLogger(StockAlertConfigResource.class);

    /** Page/fragment endpoint serving templates/pages/inventario/stock-alert-config.html. */
    private static final String PAGINA_URL = "/api/app/stock-alert-config/pagina";

    // ── Engine constants: mirrors of StockAlertService math ──────────────────

    /** calculateOptimalStock(): last 30 days of inventory movements. */
    private static final int VENTANA_VELOCIDAD_DIAS = 30;
    /** calculateOptimalStock(): "Assume 3 days lead time for most suppliers". */
    private static final int PLAZO_ENTREGA_DIAS = 3;
    /** calculateOptimalStock(): default when Articulos.diasStockSeguridad is null. */
    private static final int DIAS_STOCK_SEGURIDAD_POR_DEFECTO = 7;
    /** calculateOptimalStock(): fallback optimal when no movements exist
     *  (diasStockSeguridad * 2, or 14 days when that is null too). */
    private static final int STOCK_OPTIMO_RESPALDO_DIAS = 14;
    /** calculateReorderQuantity(): "optimal stock + 30 days buffer". */
    private static final int BUFFER_REORDEN_DIAS = 30;

    @Nonnull
    @Inject
    StockAlertService stockAlertService;

    @Nonnull
    @Inject
    ArticulosService articulosService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    @Location("pages/inventario/stock-alert-config")
    @Inject
    Template pagina;

    // ══════════════════════════════════════════════════════════════════
    // GET current config (global engine view, or per-article ?articulo=)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Current threshold configuration. Without {@code ?articulo=} returns the
     * global engine view (constants + defaults); with it, the per-article
     * thresholds exactly as {@link StockAlertService} reads them.
     */
    @GET
    @Transactional
    public Response obtener(@QueryParam("articulo") @Nullable String articulo) {
        Response guard = sesionInvalida();
        if (guard != null) {
            return guard;
        }
        try {
            if (articulo == null || articulo.isBlank()) {
                return Response.ok(ApiResponse.ok(vistaGlobal())).build();
            }
            Long codigo = parseCodigo(articulo);
            if (codigo == null) {
                return respuestaValidacion("El código del artículo debe ser numérico");
            }
            Articulos articuloEntidad = articulosService.find(codigo);
            if (articuloEntidad == null) {
                return respuestaNoEncontrado(articulo.trim());
            }
            return Response.ok(ApiResponse.ok(vistaArticulo(articuloEntidad))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error leyendo la configuración de alertas", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar los umbrales: " + e.getMessage()))
                    .build();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PUT update (?articulo=) — JSON channel for API consumers
    // ══════════════════════════════════════════════════════════════════

    /**
     * Updates the per-article thresholds. Null fields in the payload leave the
     * stored value untouched; {@code stockOptimoActual} is never accepted here
     * (the service recomputes it during {@code checkAndCreateStockAlerts()}).
     */
    @PUT
    @Transactional
    public Response actualizar(@QueryParam("articulo") @Nullable String articulo,
                               @Nullable UmbralUpdate cambios) {
        Integer dias = cambios != null ? cambios.getDiasStockSeguridad() : null;
        Boolean estado = cambios != null ? cambios.getEstadoAlertas() : null;
        return actualizarCore(articulo, dias, estado, false, null);
    }

    /**
     * Same update over the HTMX form channel (urlencoded): the kit-styled form
     * posts {@code diasStockSeguridad}, the {@code estadoAlertasPresente}
     * marker plus the {@code estadoAlertas} checkbox (unchecked boxes are
     * absent, hence the marker), and receives the re-rendered fragment + OOB
     * toast back.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response actualizarFormulario(@QueryParam("articulo") @Nullable String articulo,
                                         @FormParam("diasStockSeguridad") @Nullable String diasTexto,
                                         @FormParam("estadoAlertasPresente") @Nullable String presente,
                                         @FormParam("estadoAlertas") @Nullable String marcado,
                                         @Context @Nonnull HttpHeaders headers) {
        Integer dias = null;
        if (diasTexto != null && !diasTexto.isBlank()) {
            try {
                dias = Integer.valueOf(diasTexto.trim());
            } catch (NumberFormatException e) {
                return respuestaValidacion("Los días de stock de seguridad deben ser un número entero");
            }
        }
        Boolean estado = presente != null ? Boolean.valueOf(marcado != null) : null;
        boolean hx = headers.getHeaderString("HX-Request") != null;
        return actualizarCore(articulo, dias, estado, hx, articulo);
    }

    /** Shared guard-validate-apply-persist pipeline for both PUT channels. */
    private Response actualizarCore(@Nullable String articulo, @Nullable Integer dias,
                                    @Nullable Boolean estado, boolean hx,
                                    @Nullable String codigoEntrada) {
        Response guard = sesionInvalida();
        if (guard != null) {
            return guard;
        }
        try {
            if (articulo == null || articulo.isBlank()) {
                return respuestaValidacion("Debe indicar el artículo a configurar");
            }
            Long codigo = parseCodigo(articulo);
            if (codigo == null) {
                return respuestaValidacion("El código del artículo debe ser numérico");
            }
            if (dias != null && dias < 0) {
                return respuestaValidacion("Los días de stock de seguridad no pueden ser negativos");
            }
            Articulos entidad = articulosService.find(codigo);
            if (entidad == null) {
                return respuestaNoEncontrado(articulo.trim());
            }

            if (dias != null) {
                entidad.setDiasStockSeguridad(dias);
            }
            if (estado != null) {
                entidad.setEstadoAlertas(estado);
            }
            articulosService.update(entidad);

            StockAlertConfigDTO actualizado = vistaArticulo(entidad);
            if (hx) {
                Map<String, Object> model = modeloBase();
                model.put("umbral", actualizado);
                model.put("articuloCodigoInput", codigoEntrada);
                model.put("cargado", true);
                model.put("mensajeToast", "Umbrales actualizados para "
                        + (actualizado.getArticuloNombre() != null
                                ? actualizado.getArticuloNombre() : "el artículo"));
                model.put("toastSeveridad", "success");
                return respuestaFragmento(model);
            }
            return Response.ok(ApiResponse.ok(actualizado)).build();
        } catch (RuntimeException e) {
            LOG.warn("Error guardando los umbrales del artículo " + articulo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron guardar los umbrales: " + e.getMessage()))
                    .build();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // GET /triggered — currently-triggered alerts (read-only listing)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Alerts currently triggered ({@code estado = 'active'}), newest first, via
     * {@link StockAlertService#getActiveStockAlerts()}. READ-ONLY: rendering of
     * the Alertas REPORT page belongs to the T19 lane
     * ({@code /app/reportes/inventario/alertas}); this listing only feeds the
     * config page's snapshot table and API consumers.
     */
    @GET
    @Path("/triggered")
    @Transactional
    public Response disparadas() {
        Response guard = sesionInvalida();
        if (guard != null) {
            return guard;
        }
        try {
            List<StockAlert> activas = stockAlertService.getActiveStockAlerts();
            List<StockAlertDTO> datos = new ArrayList<>();
            if (activas != null) {
                for (StockAlert alerta : activas) {
                    datos.add(aDTO(alerta));
                }
            }
            return Response.ok(ApiResponse.ok(datos)).build();
        } catch (RuntimeException e) {
            LOG.warn("Error listando las alertas disparadas", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar las alertas de stock: " + e.getMessage()))
                    .build();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // GET /pagina — config page (full page, or forma fragment when HX)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Renders templates/pages/inventario/stock-alert-config.html: engine
     * constants panel, per-article threshold form (hx-put save w/ toast) and a
     * read-only kit table snapshot of currently-triggered alerts. With the
     * {@code HX-Request} header renders ONLY the {@code forma} fragment so the
     * article loader can swap it in place.
     */
    @GET
    @Path("/pagina")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response pagina(@Context @Nonnull HttpHeaders headers,
                           @QueryParam("articulo") @Nullable String articulo,
                           @QueryParam("page") @DefaultValue("1") int page,
                           @QueryParam("size") @DefaultValue("10") int size) {
        boolean hx = headers.getHeaderString("HX-Request") != null;
        try {
            Map<String, Object> model = modeloBase();

            StockAlertConfigDTO umbral = null;
            boolean cargado = false;
            if (articulo != null && !articulo.isBlank()) {
                Long codigo = parseCodigo(articulo);
                if (codigo != null) {
                    Articulos entidad = articulosService.find(codigo);
                    if (entidad != null) {
                        umbral = vistaArticulo(entidad);
                        cargado = true;
                    }
                }
                if (!cargado && hx) {
                    // Toast only rides swapped fragments; a full page load shows
                    // the empty-form state instead.
                    model.put("mensajeToast", "No se encontró el artículo: " + articulo.trim());
                    model.put("toastSeveridad", "error");
                }
            }
            model.put("umbral", umbral);
            model.put("articuloCodigoInput", articulo);
            model.put("cargado", cargado);

            // Triggered-alerts snapshot table (kit data-table, slot mode).
            int paginaSegura = Math.max(page, 1);
            int tamanoSeguro = Math.min(Math.max(size, 1), 100);
            List<StockAlert> activas = stockAlertService.getActiveStockAlerts();
            List<Map<String, Object>> filas = new ArrayList<>();
            if (activas != null) {
                for (StockAlert alerta : activas) {
                    filas.add(fila(alerta));
                }
            }
            long total = filas.size();
            int desde = (int) Math.min((paginaSegura - 1L) * tamanoSeguro, total);
            int hasta = (int) Math.min(desde + (long) tamanoSeguro, total);
            int totalPaginas = Math.max(1,
                    (int) ((total + tamanoSeguro - 1) / tamanoSeguro));
            model.put("columnas", columnas());
            model.put("filas", filas.subList(desde, hasta));
            model.put("page", paginaSegura);
            model.put("size", tamanoSeguro);
            model.put("total", total);
            model.put("totalPages", totalPaginas);
            model.put("paginas", ventanaPaginas(paginaSegura, totalPaginas));

            if (hx) {
                return respuestaFragmento(model);
            }
            String html = pagina.data(model).render();
            return Response.ok(html)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de configuración", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudo cargar la página de configuración: " + e.getMessage()))
                    .build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Legacy guard, mirrored verbatim: {@code currentSession.isValid()} failing
     * produced FacesMessage("Sesión Inválida", "No tiene permisos para realizar
     * esta acción"); here the same message rides a 401 SESSION_INVALID envelope.
     * Returns null when the session is valid.
     */
    private Response sesionInvalida() {
        if (securityIdentity == null || securityIdentity.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("SESSION_INVALID",
                            "No tiene permisos para realizar esta acción"))
                    .build();
        }
        return null;
    }

    private static Response respuestaValidacion(@Nonnull String mensaje) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", mensaje))
                .build();
    }

    private static Response respuestaNoEncontrado(@Nonnull String codigo) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", "No se encontró el artículo: " + codigo))
                .build();
    }

    /** Null when blank/non-numeric (caller decides which validation to report). */
    private static Long parseCodigo(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(texto.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Global engine view: constants + defaults, no article scope. */
    private static StockAlertConfigDTO vistaGlobal() {
        return new StockAlertConfigDTO(VENTANA_VELOCIDAD_DIAS, PLAZO_ENTREGA_DIAS,
                DIAS_STOCK_SEGURIDAD_POR_DEFECTO, STOCK_OPTIMO_RESPALDO_DIAS,
                BUFFER_REORDEN_DIAS, null, null, null, true, null);
    }

    /** Per-article view as StockAlertService reads/writes the row. */
    private static StockAlertConfigDTO vistaArticulo(@Nonnull Articulos entidad) {
        return new StockAlertConfigDTO(VENTANA_VELOCIDAD_DIAS, PLAZO_ENTREGA_DIAS,
                DIAS_STOCK_SEGURIDAD_POR_DEFECTO, STOCK_OPTIMO_RESPALDO_DIAS,
                BUFFER_REORDEN_DIAS, entidad.getCodigo(), entidad.getNombre(),
                entidad.getDiasStockSeguridad(),
                entidad.getEstadoAlertas() == null || entidad.getEstadoAlertas(),
                entidad.getStockOptimo());
    }

    /** Read-side mapping onto the pre-existing StockAlertDTO contract. */
    private static StockAlertDTO aDTO(@Nonnull StockAlert alerta) {
        Articulos articulo = alerta.getArticulo();
        Departamento departamento = alerta.getDepartamento();
        return new StockAlertDTO(
                alerta.getId(),
                articulo != null ? articulo.getCodigo() : null,
                articulo != null ? articulo.getNombre() : null,
                null, null,
                articulo != null ? articulo.getStockOptimo() : null,
                alerta.getTipoAlerta(),
                alerta.getCantidadActual(),
                alerta.getCantidadMinima(),
                alerta.getSugeridoReordenar(),
                departamento != null ? departamento.getNombre() : null,
                alerta.getEstado(),
                alerta.getFechaCreacion(),
                alerta.getFechaResolucion(), null, null,
                alerta.getNotas());
    }

    /** Base page model shared by full-page and fragment renders. */
    private Map<String, Object> modeloBase() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Configuración de Alertas de Stock");
        model.put("usuario", nombreUsuario());
        model.put("global", vistaGlobal());
        model.put("baseUrl", PAGINA_URL);
        return model;
    }

    private String nombreUsuario() {
        try {
            return securityIdentity.getPrincipal().getName();
        } catch (RuntimeException e) {
            return "Usuario";
        }
    }

    /** Fragment-only render of the {#fragment forma} section + OOB toast. */
    private Response respuestaFragmento(@Nonnull Map<String, Object> model) {
        String html = pagina.getFragment("forma").data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /** Display row for the read-only triggered-alerts table. */
    private static Map<String, Object> fila(@Nonnull StockAlert alerta) {
        Articulos articulo = alerta.getArticulo();
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("tipo", etiquetaTipo(alerta.getTipoAlerta()));
        fila.put("articulo", articulo != null && articulo.getNombre() != null
                ? articulo.getNombre() : "-");
        fila.put("cantidadActual", alerta.getCantidadActual() != null
                ? alerta.getCantidadActual().toString() : "-");
        fila.put("cantidadMinima", alerta.getCantidadMinima() != null
                ? alerta.getCantidadMinima().toString() : "-");
        fila.put("sugeridoReordenar", alerta.getSugeridoReordenar() != null
                ? alerta.getSugeridoReordenar().toString() : "-");
        fila.put("fechaCreacion", fmtFechaHora(alerta.getFechaCreacion()));
        fila.put("estado", etiquetaEstado(alerta.getEstado()));
        return fila;
    }

    /** Non-sortable columns: the snapshot is read-only by design. */
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Tipo"),
                Map.of("label", "Artículo"),
                Map.of("label", "Stock Actual"),
                Map.of("label", "Stock Mínimo"),
                Map.of("label", "Sugerido Reordenar"),
                Map.of("label", "Fecha Creación"),
                Map.of("label", "Estado"));
    }

    /** Up-to-5-pages window centered on the current page (Qute has no division). */
    private static List<Integer> ventanaPaginas(int page, int totalPages) {
        List<Integer> paginas = new ArrayList<>();
        if (totalPages <= 0) {
            paginas.add(1);
            return paginas;
        }
        int inicio = Math.max(1, page - 2);
        int fin = Math.min(totalPages, inicio + 4);
        inicio = Math.max(1, fin - 4);
        for (int p = inicio; p <= fin; p++) {
            paginas.add(p);
        }
        return paginas;
    }

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

    private static String fmtFechaHora(@Nullable Date fecha) {
        return fecha != null ? new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fecha) : "-";
    }

    /** PUT request body (JSON channel); null fields mean "leave unchanged". */
    public static class UmbralUpdate {

        @Nullable
        private Integer diasStockSeguridad;

        @Nullable
        private Boolean estadoAlertas;

        public UmbralUpdate() {
        }

        @Nullable
        public Integer getDiasStockSeguridad() {
            return diasStockSeguridad;
        }

        public void setDiasStockSeguridad(@Nullable Integer diasStockSeguridad) {
            this.diasStockSeguridad = diasStockSeguridad;
        }

        @Nullable
        public Boolean getEstadoAlertas() {
            return estadoAlertas;
        }

        public void setEstadoAlertas(@Nullable Boolean estadoAlertas) {
            this.estadoAlertas = estadoAlertas;
        }
    }
}
