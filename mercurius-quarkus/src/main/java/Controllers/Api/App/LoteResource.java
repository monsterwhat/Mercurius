package Controllers.Api.App;

import Models.Articulos.Articulos;
import Models.DTO.ApiResponse;
import Models.DTO.LoteDTO;
import Models.DTO.PagedResponse;
import Models.Lote;
import Models.Users;
import Services.ArticulosService;
import Services.LoginService;
import Services.LoteService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Lotes (batch/expiry) module for the JSON API surface — port of the legacy
 * JSF pair {@code Controllers.LotesController} (próximos a vencer / vencidos
 * tables + {@code ingresarLote()} dialog).
 *
 * <p><b>Behavior parity contract:</b></p>
 * <ul>
 *   <li>List {@code proximosVencer=true} delegates verbatim to
 *       {@link LoteService#listProximosVencer(int)} — status = true,
 *       cantidadActual &gt; 0, fechaVencimiento ≤ hoy + dias, ordered by
 *       fechaVencimiento ASC. {@code dias} defaults to 7, mirroring
 *       {@code Utils.ProgramadorTareas.notificarLotesProximosVencer()}; the
 *       legacy page default was 30 ({@code diasAlerta}).</li>
 *   <li>Plain list delegates to {@link LoteService#listAll()} (GService);
 *       {@code q} reproduces the app-kit global filter over numeroLote,
 *       artículo nombre/código/código de barra, notas and usuario.</li>
 *   <li>Create mirrors {@code LotesController.ingresarLote()} guard-for-guard:
 *       status forced true, fechaIngreso defaulted to now when absent,
 *       cantidadActual defaulted to cantidadInicial when absent, persisted
 *       through {@link LoteService#create}, with the same audit alerta texts
 *       ("Creacion" / "Lote creado: … para articulo …" and the "Error al
 *       crear lote" failure branch).</li>
 *   <li>Update/delete have NO legacy counterpart (the JSF page was
 *       create/read-only); they delegate to the inherited GService
 *       {@code update}/{@code delete} with explicit find-first guards.</li>
 * </ul>
 *
 * <p>Article resolution scans {@link ArticulosService#ListAllEnabled()} by
 * {@code codigo} (Long equality) — the same selectable universe the legacy
 * lotes/etiquetas views offered — avoiding the Integer/Long id mismatch of
 * {@code ArticulosService.findById(Integer)}.</p>
 */
@Path("/api/app/lotes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Lotes")
public class LoteResource {

    private static final Logger LOG = Logger.getLogger(LoteResource.class.getName());

    /** Legacy ingresarLote() failure message (FacesMessage severity error). */
    private static final String MSG_ERROR_CREAR = "Error al crear lote";

    @Nonnull
    LoteService loteService;

    @Nonnull
    ArticulosService articulosService;

    
    @Nonnull
    LoginService loginService;

    @Nonnull
    SecurityIdentity identity;

    // ════════════════════════════════════════════════════════════════════
    // Reads
    // ════════════════════════════════════════════════════════════════════

    /**
     * Paginated lote list. {@code proximosVencer=true} switches the source to
     * {@link LoteService#listProximosVencer(int)} (ProgramadorTareas
     * semantics); otherwise every lote is listed (the DTO carries the
     * activo/inactivo chip). Filtering/sorting/paging is computed in memory
     * because the Services layer is frozen for this task.
     */
    @GET
    @Transactional
    @Operation(summary = "List lotes with pagination, sorting and global filter")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paginated lotes"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("1") @Parameter(description = "Page number (1-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size,
            @QueryParam("sort") @Nullable @Parameter(description = "Sort key: id|numeroLote|fechaVencimiento|cantidadInicial|cantidadActual|fechaIngreso|articulo|usuario") String sort,
            @QueryParam("dir") @DefaultValue("asc") @Parameter(description = "Sort direction: asc|desc") String dir,
            @QueryParam("q") @Nullable @Parameter(description = "Global filter text") String q,
            @QueryParam("proximosVencer") @Nullable @Parameter(description = "true → only lotes within 'dias' days of expiry (or already expired)") Boolean proximosVencer,
            @QueryParam("dias") @DefaultValue("7") @Parameter(description = "Expiry window in days for proximosVencer (ProgramadorTareas default: 7)") int dias) {
        try {
            List<Lote> source = Boolean.TRUE.equals(proximosVencer)
                    ? orEmpty(loteService.listProximosVencer(dias))
                    : orEmpty(loteService.listAll());
            List<Lote> filtered = filterLotes(source, q);
            sortLotes(filtered, sort, dir);
            long total = filtered.size();
            Window w = windowOf(total, page, size);
            List<LoteDTO> data = filtered.subList(w.from(), w.to()).stream()
                    .map(LoteResource::toDTO).toList();
            return Response.ok(new PagedResponse<>(data, total, w.page(), w.size())).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listando los lotes", e);
            return serverError("Error listando los lotes");
        }
    }

    /** Lote detail by id. */
    @GET
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Lote detail")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Lote"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response detalle(@PathParam("id") long id) {
        try {
            Lote lote = loteService.find(id);
            if (lote == null) {
                return notFound("No se encontró el lote solicitado");
            }
            return Response.ok(ApiResponse.ok(toDTO(lote))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error leyendo el lote " + id, e);
            return serverError("Error leyendo el lote");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Create (legacy LotesController.ingresarLote parity)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Creates a lote — {@code LotesController.ingresarLote()} parity:
     * required articulo/numeroLote/fechaVencimiento/cantidadInicial, then the
     * legacy stamp chain (status=true, fechaIngreso=now?, cantidadActual=
     * cantidadInicial?) before {@link LoteService#create}.
     */
    @POST
    @Operation(summary = "Create a lote (LotesController.ingresarLote parity)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Missing or invalid fields"),
        @APIResponse(responseCode = "404", description = "Unknown articuloCodigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response crear(@Nullable LoteRequest body) {
        if (body == null) {
            return badRequest("VALIDATION_ERROR", "El cuerpo de la petición es obligatorio");
        }
        if (body.articuloCodigo() == null) {
            return badRequest("VALIDATION_ERROR", "El artículo es obligatorio");
        }
        if (body.numeroLote() == null || body.numeroLote().isBlank()) {
            return badRequest("VALIDATION_ERROR", "El número de lote es obligatorio");
        }
        Date fechaVencimiento = parseFechaIso(body.fechaVencimiento());
        if (body.fechaVencimiento() != null && !body.fechaVencimiento().isBlank()
                && fechaVencimiento == null) {
            return badRequest("VALIDATION_ERROR", "La fecha de vencimiento es inválida (yyyy-MM-dd)");
        }
        if (fechaVencimiento == null) {
            return badRequest("VALIDATION_ERROR", "La fecha de vencimiento es obligatoria");
        }
        if (body.cantidadInicial() == null) {
            return badRequest("VALIDATION_ERROR", "La cantidad inicial es obligatoria");
        }
        try {
            Articulos articulo = resolveArticulo(body.articuloCodigo());
            if (articulo == null) {
                return notFound("No se encontró el artículo solicitado");
            }
            Lote nuevo = new Lote();
            nuevo.setArticulo(articulo);
            nuevo.setNumeroLote(body.numeroLote().trim());
            nuevo.setFechaVencimiento(fechaVencimiento);
            nuevo.setCantidadInicial(body.cantidadInicial());
            if (body.cantidadActual() != null) {
                // Legacy keeps an explicit cantidadActual; the default below only fills null.
                nuevo.setCantidadActual(body.cantidadActual());
            }
            nuevo.setNotas(emptyToNull(body.notas()));
            // Legacy guardar chain, verbatim:
            nuevo.setStatus(true);
            if (nuevo.getFechaIngreso() == null) {
                nuevo.setFechaIngreso(new Date());
            }
            if (nuevo.getCantidadActual() == null) {
                nuevo.setCantidadActual(nuevo.getCantidadInicial());
            }
            loteService.create(nuevo);
                        LOG.info("Lote creado: " + nuevo.getNumeroLote()
                    + " para articulo " + articulo.getNombre() + " | user=" + String.valueOf(currentUser()) + " | source=" + "LoteResource.ingresarLote()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(nuevo))).build();
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, MSG_ERROR_CREAR + ": " + e.getMessage() + " | user=" + String.valueOf(currentUser()) + " | source=" + "LoteResource.ingresarLote()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            LOG.log(Level.WARNING, "Error creando el lote", e);
            return serverError(MSG_ERROR_CREAR);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Update / delete (no legacy counterpart — GService delegation)
    // ════════════════════════════════════════════════════════════════════

    /** Partial update: only the provided fields are applied, then GService merge. */
    @PUT
    @Path("/{id}")
    @Operation(summary = "Update a lote (partial; no legacy counterpart)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Invalid field values"),
        @APIResponse(responseCode = "404", description = "Unknown id or articuloCodigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response actualizar(@PathParam("id") long id, @Nullable LoteRequest body) {
        if (body == null) {
            return badRequest("VALIDATION_ERROR", "El cuerpo de la petición es obligatorio");
        }
        try {
            Lote lote = loteService.find(id);
            if (lote == null) {
                return notFound("No se encontró el lote solicitado");
            }
            if (body.articuloCodigo() != null) {
                Articulos articulo = resolveArticulo(body.articuloCodigo());
                if (articulo == null) {
                    return notFound("No se encontró el artículo solicitado");
                }
                lote.setArticulo(articulo);
            }
            if (body.numeroLote() != null && !body.numeroLote().isBlank()) {
                lote.setNumeroLote(body.numeroLote().trim());
            }
            if (body.fechaVencimiento() != null && !body.fechaVencimiento().isBlank()) {
                Date fechaVencimiento = parseFechaIso(body.fechaVencimiento());
                if (fechaVencimiento == null) {
                    return badRequest("VALIDATION_ERROR", "La fecha de vencimiento es inválida (yyyy-MM-dd)");
                }
                lote.setFechaVencimiento(fechaVencimiento);
            }
            if (body.cantidadInicial() != null) {
                lote.setCantidadInicial(body.cantidadInicial());
            }
            if (body.cantidadActual() != null) {
                lote.setCantidadActual(body.cantidadActual());
            }
            if (body.notas() != null) {
                lote.setNotas(emptyToNull(body.notas()));
            }
            loteService.update(lote);
                        LOG.info("Lote actualizado: " + lote.getNumeroLote() + " | user=" + String.valueOf(currentUser()) + " | source=" + "LoteResource.update()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            return Response.ok(ApiResponse.ok(toDTO(lote))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error actualizando el lote " + id, e);
            return serverError("Error actualizando el lote");
        }
    }

    /** Hard delete through GService.delete (find-first guard). */
    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a lote (no legacy counterpart)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Deleted"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response eliminar(@PathParam("id") long id) {
        try {
            Lote lote = loteService.find(id);
            if (lote == null) {
                return notFound("No se encontró el lote solicitado");
            }
            String numeroLote = lote.getNumeroLote();
            loteService.delete(lote);
                        LOG.info("Lote eliminado: " + numeroLote + " | user=" + String.valueOf(currentUser()) + " | source=" + "LoteResource.delete()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            return Response.ok(ApiResponse.ok(Map.of("mensaje", "Lote eliminado"))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error eliminando el lote " + id, e);
            return serverError("Error eliminando el lote");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Resolves an article by codigo scanning {@link ArticulosService#ListAllEnabled()}
     * (Long-safe; the enabled universe is the selectable one in both legacy
     * views). Null when the service failed or the codigo is unknown.
     */
    @Nullable
    private Articulos resolveArticulo(@Nonnull Long codigo) {
        List<Articulos> articulos = articulosService.ListAllEnabled();
        if (articulos == null) {
            return null;
        }
        for (Articulos articulo : articulos) {
            if (codigo.equals(articulo.getCodigo())) {
                return articulo;
            }
        }
        return null;
    }

    /** Global filter over numeroLote, artículo nombre/código/código de barra, notas, usuario. */
    private static List<Lote> filterLotes(@Nonnull List<Lote> source, @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Lote> out = new ArrayList<>();
        for (Lote lote : source) {
            Articulos articulo = lote.getArticulo();
            Users usuario = lote.getUsuario();
            if (matches(lote.getNumeroLote(), needle)
                    || (articulo != null && matches(articulo.getNombre(), needle))
                    || (articulo != null && String.valueOf(articulo.getCodigo()).contains(needle))
                    || (articulo != null && matches(articulo.getCodigoBarra(), needle))
                    || matches(lote.getNotas(), needle)
                    || (usuario != null && matches(usuario.getUsername(), needle))) {
                out.add(lote);
            }
        }
        return out;
    }

    private static boolean matches(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Typed comparator dispatch over a whitelisted key set. */
    private static void sortLotes(@Nonnull List<Lote> lotes, @Nullable String sort,
                                  @Nullable String dir) {
        if (lotes.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Lote> cmp = comparatorFor(sort);
        if (cmp != null) {
            lotes.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    @Nullable
    private static Comparator<Lote> comparatorFor(@Nonnull String sort) {
        return switch (sort) {
            case "id" -> Comparator.comparing(Lote::getId, Comparator.nullsLast(Comparator.naturalOrder()));
            case "numeroLote" -> Comparator.comparing(Lote::getNumeroLote,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "fechaVencimiento" -> Comparator.comparing(Lote::getFechaVencimiento,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "cantidadInicial" -> Comparator.comparing(Lote::getCantidadInicial,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "cantidadActual" -> Comparator.comparing(Lote::getCantidadActual,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "fechaIngreso" -> Comparator.comparing(Lote::getFechaIngreso,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "articulo" -> Comparator.comparing(LoteResource::nombreArticuloDe,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "usuario" -> Comparator.comparing(LoteResource::usernameDe,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> null;
        };
    }

    @Nullable
    private static String nombreArticuloDe(@Nullable Lote lote) {
        return lote != null && lote.getArticulo() != null ? lote.getArticulo().getNombre() : null;
    }

    @Nullable
    private static String usernameDe(@Nullable Lote lote) {
        return lote != null && lote.getUsuario() != null ? lote.getUsuario().getUsername() : null;
    }

    /** DTO mapper (manual, repo convention) — LoteDTO field order preserved. */
    @Nonnull
    private static LoteDTO toDTO(@Nonnull Lote lote) {
        Articulos articulo = lote.getArticulo();
        Users usuario = lote.getUsuario();
        return new LoteDTO(
                lote.getId(),
                articulo != null ? articulo.getCodigo() : null,
                articulo != null ? articulo.getNombre() : null,
                lote.getNumeroLote(),
                lote.getFechaVencimiento(),
                lote.getCantidadInicial(),
                lote.getCantidadActual(),
                lote.getFechaIngreso(),
                usuario != null ? usuario.getId() : null,
                usuario != null ? usuario.getUsername() : null,
                lote.getNotas(),
                lote.getStatus());
    }

    /** ISO yyyy-MM-dd → start-of-day Date; null when blank/invalid (Tablas.fecha parity). */
    @Nullable
    private static Date parseFechaIso(@Nullable String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(iso.trim());
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private record Window(int page, int size, int from, int to, int totalPages) {}

    /** Clamped 1-based window over an in-memory result (InventarioResource parity). */
    private static Window windowOf(long total, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);
        return new Window(p, s, from, to, totalPages);
    }

    // ── Shared responses ────────────────────────────────────────────────

    private static Response badRequest(@Nonnull String code, @Nonnull String mensaje) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error(code, mensaje)).build();
    }

    private Response notFound(@Nonnull String mensaje) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", mensaje)).build();
    }

    private Response serverError(@Nonnull String mensaje) {
        return Response.serverError()
                .entity(ApiResponse.error("INTERNAL_ERROR", mensaje)).build();
    }

    /**
     * Resolves the authenticated {@link Users} row for audit alertas; null
     * for anonymous/system contexts (alertas accepts null, mirroring the
     * legacy null-session branches).
     */
    @Nullable
    private Users currentUser() {
        try {
            if (identity.isAnonymous() || identity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(identity.getPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "No current user resolvable", e);
            return null;
        }
    }

    // ── Small value carriers ────────────────────────────────────────────

    /**
     * Create/update request body. {@code fechaVencimiento} is ISO yyyy-MM-dd.
     * On create: articuloCodigo, numeroLote, fechaVencimiento and
     * cantidadInicial are required; cantidadActual defaults to cantidadInicial
     * and status is forced true (legacy chain). On update every field is
     * optional (partial semantics).
     */
    public record LoteRequest(Long articuloCodigo, String numeroLote, String fechaVencimiento,
                              BigDecimal cantidadInicial, BigDecimal cantidadActual, String notas) {}
}
