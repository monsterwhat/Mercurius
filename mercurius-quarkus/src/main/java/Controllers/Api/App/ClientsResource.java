package Controllers.Api.App;

import Models.ClienteActividad;
import Models.Clients;
import Models.DTO.ApiResponse;
import Models.DTO.ClientsDTO;
import Models.DTO.ClientsDetailDTO;
import Models.DTO.PagedResponse;
import Services.ClientService;
import Utils.DiffUtils;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Client management endpoints for the NEW Qute/HTMX app surface (/app world).
 *
 * <p>Mirrors the legacy JSF {@code ClientsController} behaviors as REST:
 * listing/name-search ({@code clientsList()} / {@code ClientService.searchByName}),
 * full-detail lookup, creation with the cédula and name uniqueness guards
 * ({@code createClient()} parity), updates ({@code updateClient()} parity) and
 * the SOFT disable flow ({@code toggleClient()}/{@code disableCliente()} parity —
 * the legacy controller has no hard delete; it archives by flipping
 * {@code status} to {@code false}). Audit trails are preserved via
 * {@link AlertasService#registrarAlerta}.</p>
 *
 * <p>The {@code @RolesAllowed} gate is dormant until the form-cookie auth
 * block is enabled in application.properties (see {@link AppAuthResource});
 * once active, every path under {@code /api/app/*} requires an authenticated
 * user with the {@code admin} or {@code usuario} role.</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions. Write and detail-read methods are transactional so the
 * persistence context stays open while lazy relations ({@code actividades})
 * are mapped to DTOs.</p>
 */
@Path("/api/app/clientes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "usuario"})
@Tag(name = "App - Clientes")
public class ClientsResource {

    private static final Logger LOG = Logger.getLogger(ClientsResource.class.getName());

    @Nonnull
    ClientService clientService;

    
    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Nonnull
    RoutingContext routing;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as CategoriaResource, T18).
    @Nonnull
    @Location("pages/clientes/index.html")
    Template pageIndex;

    @Nonnull
    @Location("pages/clientes/tabla.html")
    Template tablaPage;

    @Nonnull
    @Location("pages/clientes/form.html")
    Template formCliente;

    @GET
    @Operation(summary = "List clients with pagination and optional name search")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size,
            @QueryParam("q") @Nullable @Parameter(description = "Search by name (case-insensitive contains)") String q) {

        // Clamp size to max 100 (SuppliersController convention)
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            if (q != null && !q.isBlank()) {
                // Delegate to ClientService.searchByName exactly like the legacy
                // filter path does; it returns the FULL match list (no paging),
                // so pagination happens in memory over its result.
                List<Clients> matches = clientService.searchByName(q.trim());
                List<Clients> safeMatches = (matches != null) ? matches : List.of();

                List<ClientsDTO> dtos = safeMatches.stream().map(this::toDTO).toList();
                List<ClientsDTO> data = paginate(dtos, page, size);
                return Response.ok(new PagedResponse<>(data, dtos.size(), page, size)).build();
            }

            long total = clientService.count();
            List<ClientsDTO> data = clientService.listPage(page * size, size).stream()
                    .map(this::toDTO)
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing clients", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando los clientes"))
                    .build();
        }
    }

    @GET
    @Path("/{code}")
    @Transactional
    @Operation(summary = "Get a client's full profile by code")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response get(@PathParam("code") @Parameter(description = "Client code") int code) {
        try {
            Clients client = clientService.find(code);
            if (client == null) {
                return notFound(code);
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(client))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting client " + code, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error obteniendo el cliente"))
                    .build();
        }
    }

    @POST
    @Transactional
    @Operation(summary = "Create a client from a full-profile payload")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "409", description = "Duplicate name or cédula"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response create(@Nullable ClientsDetailDTO payload) {
        try {
            if (payload == null) {
                return badRequest("El cuerpo de la petición es requerido.");
            }
            if (payload.getName() == null || payload.getName().isBlank()) {
                return badRequest("El nombre del cliente no puede estar vacío.");
            }
            String validationError = validateActividades(payload);
            if (validationError != null) {
                return badRequest(validationError);
            }

            // Guard order mirrors ClientsController.createClient(): cédula check
            // first (only when provided), then duplicate-name check.
            String idNumber = payload.getIdNumber();
            if (idNumber != null && !idNumber.isBlank()
                    && clientService.checkClientByIdNumber(idNumber)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(ApiResponse.error("ID_TAKEN",
                                "Ya existe un cliente con la cédula: " + idNumber))
                        .build();
            }
            if (clientService.checkClientName(payload.getName())) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(ApiResponse.error("NAME_TAKEN",
                                "Ya existe un cliente con el nombre: " + payload.getName()))
                        .build();
            }

            Clients client = new Clients();
            applyScalars(client, payload);
            client.setStatus(true); // parity: createClient() always enables new clients
            replaceActividades(client, payload.getActividades());
            // usuario attribution: legacy binds currentSession.getCurrentUser();
            // in the REST world there is no JSF session, so it stays null until
            // form auth lands (nullable FK).
            clientService.create(client);

                        LOG.info("Se creo el cliente: " + client.getName() + " | source=" + "ClientsResource.create()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(client)));

            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDetailDTO(client)))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error creating client", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error creando el cliente"))
                    .build();
        }
    }

    @PUT
    @Path("/{code}")
    @Transactional
    @Operation(summary = "Update a client's profile")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(
            @PathParam("code") @Parameter(description = "Client code") int code,
            @Nullable ClientsDetailDTO payload) {
        try {
            if (payload == null) {
                return badRequest("El cuerpo de la petición es requerido.");
            }
            if (payload.getName() != null && payload.getName().isBlank()) {
                return badRequest("El nombre del cliente no puede estar vacío.");
            }
            String validationError = validateActividades(payload);
            if (validationError != null) {
                return badRequest(validationError);
            }

            Clients client = clientService.find(code);
            if (client == null) {
                return notFound(code);
            }

            String antes = DiffUtils.snapshotEntity(client);
            applyScalars(client, payload);
            if (payload.getActividades() != null) {
                replaceActividades(client, payload.getActividades());
            }
            clientService.update(client);

            // Audit parity with ClientsController.updateClient()
                        LOG.info("Se actualizo el cliente: " + client.getName() + " | source=" + "ClientsResource.update()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(client)));

            return Response.ok(ApiResponse.ok(toDetailDTO(client))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating client " + code, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando el cliente"))
                    .build();
        }
    }

    @DELETE
    @Path("/{code}")
    @Transactional
    @Operation(summary = "Archive (soft-disable) a client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Archived (status set to false)"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response delete(@PathParam("code") @Parameter(description = "Client code") int code) {
        try {
            Clients client = clientService.find(code);
            if (client == null) {
                return notFound(code);
            }

            // Parity: ClientsController has NO hard delete — toggleClient()
            // disables via setStatus(false) + update, keeping the row archived.
            String antes = DiffUtils.snapshotEntity(client);
            client.setStatus(false);
            clientService.update(client);

                        LOG.info("El estatus cambio" + " | source=" + "ClientsResource.delete()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(client)));

            // HTMX callers get the refreshed table fragment + OOB toast
            // (ui-kit §7 update="table region"); JSON callers keep the exact
            // envelope below.
            if (isHxRequest()) {
                return htmlOk(tableInstance(1, 20, null, "asc", null, "warn",
                        "El cliente " + client.getName() + " fue archivado"));
            }

            return Response.ok(ApiResponse.ok(toDetailDTO(client))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error deleting client " + code, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error eliminando el cliente"))
                    .build();
        }
    }

    // ── W4B view-half: dual-mode table endpoint + dialog fragments ─────────

    /**
     * GET /table?page&size&sort&dir&q — with the {@code HX-Request} header
     * returns ONLY the data-table include (fragment swap into the page's
     * table container); without it renders the FULL clientes page. This
     * mirrors the SERVER-SIDE CONTRACT comment of
     * {@code templates/_kit/data-table.html} exactly: the same endpoint
     * renders page and fragments, all paging/sorting state lives in the URL,
     * and {@code page} is 1-based here (the JSON list endpoint above keeps
     * its own 0-based contract untouched). {@code q} keeps the list
     * endpoint's searchByName parity.
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full clientes page")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment or full page"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response table(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            if (isHxRequest()) {
                return htmlOk(tableInstance(page, size, sort, dir, q, null, null));
            }
            return htmlOk(renderFullPage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página de clientes", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    /** Empty client creation form (modal body). */
    @GET
    @Path("/formularios/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-client form fragment (modal body)")
    public Response formNueva() {
        return htmlOk(formInstance("crear", null, null, null, null, null));
    }

    /** Prefilled client edit form (modal body). */
    @GET
    @Path("/formularios/{code}")
    @Transactional
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-client form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown code")
    })
    public Response formEditar(@PathParam("code") int code) {
        Clients client = clientService.find(code);
        if (client == null) {
            return notFound(code);
        }
        return htmlOk(formInstance("editar", client, null, null, null, null));
    }

    /**
     * Form-urlencoded twin of {@link #create} for the HTMX dialog (JAX-RS
     * selects by Content-Type; the JSON contract above is untouched). Field
     * labels/requireds port CrearClientesDialog.xhtml; guard failures from
     * {@link #create} (duplicate cédula/nombre) are surfaced as a form
     * redisplay + out-of-band toast (ui-kit.md Pattern A).
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Create a client from an HTMX form", hidden = true)
    public Response createForm(
            @FormParam("name") @Nullable String name,
            @FormParam("email") @Nullable String email,
            @FormParam("address") @Nullable String address,
            @FormParam("phoneNumber") @Nullable String phoneNumber,
            @FormParam("zoneCode") @Nullable String zoneCode,
            @FormParam("idType") @Nullable String idType,
            @FormParam("idNumber") @Nullable String idNumber,
            @FormParam("birthDate") @Nullable String birthDate,
            @FormParam("taxpayer") @Nullable String taxpayer,
            @FormParam("actividad") @Nullable List<String> actividad) {
        String nombre = trimToEmpty(name);
        if (nombre.isEmpty()) {
            return redisplayForm("crear", null, "El nombre no puede estar vacío", null,
                    "error", "El nombre no puede estar vacío");
        }
        Date fechaNacimiento = parseIsoDate(birthDate);
        if (birthDate != null && !birthDate.isBlank() && fechaNacimiento == null) {
            return redisplayForm("crear", null, null, "La fecha de nacimiento no es válida",
                    "error", "La fecha de nacimiento no es válida");
        }

        Response result = create(buildDetailDTO(nombre, email, address, phoneNumber,
                zoneCode, idType, idNumber, fechaNacimiento, taxpayer, actividad));
        return handleFormMutationResult(result, "crear", null,
                "/api/app/clientes/table");
    }

    /**
     * Form-urlencoded twin of {@link #update} for the HTMX edit dialog.
     * Sparse-field semantics match {@link #update}: only provided fields are
     * applied; blank names are rejected with the legacy message.
     */
    @PUT
    @Path("/{code}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Update a client from an HTMX form", hidden = true)
    public Response updateForm(
            @PathParam("code") int code,
            @FormParam("name") @Nullable String name,
            @FormParam("email") @Nullable String email,
            @FormParam("address") @Nullable String address,
            @FormParam("phoneNumber") @Nullable String phoneNumber,
            @FormParam("zoneCode") @Nullable String zoneCode,
            @FormParam("idType") @Nullable String idType,
            @FormParam("idNumber") @Nullable String idNumber,
            @FormParam("birthDate") @Nullable String birthDate,
            @FormParam("taxpayer") @Nullable String taxpayer,
            @FormParam("actividad") @Nullable List<String> actividad) {
        String nombre = trimToEmpty(name);
        if (nombre.isEmpty()) {
            return redisplayForm("editar", clientService.find(code),
                    "El nombre del cliente no puede estar vacío.", null,
                    "error", "El nombre del cliente no puede estar vacío.");
        }
        Date fechaNacimiento = parseIsoDate(birthDate);
        if (birthDate != null && !birthDate.isBlank() && fechaNacimiento == null) {
            return redisplayForm("editar", clientService.find(code), null,
                    "La fecha de nacimiento no es válida",
                    "error", "La fecha de nacimiento no es válida");
        }

        Response result = update(code, buildDetailDTO(nombre, email, address, phoneNumber,
                zoneCode, idType, idNumber, fechaNacimiento, taxpayer, actividad));
        return handleFormMutationResult(result, "editar", clientService.find(code),
                "/api/app/clientes/table");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", message))
                .build();
    }

    private static Response notFound(int code) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", "No se encontró el cliente: " + code))
                .build();
    }

    /**
     * Applies the scalar profile fields of the payload onto the entity.
     * Null fields are skipped (merge-style) so sparse payloads never wipe
     * stored data; blank names are rejected upstream. Trade-off: explicit
     * null-clearing of a field is not supported through this endpoint.
     */
    private static void applyScalars(@Nonnull Clients target, @Nonnull ClientsDetailDTO dto) {
        if (dto.getName() != null) {
            target.setName(dto.getName().trim());
        }
        if (dto.getAddress() != null) {
            target.setAddress(dto.getAddress());
        }
        if (dto.getProvincia() != null) {
            target.setProvincia(dto.getProvincia());
        }
        if (dto.getCanton() != null) {
            target.setCanton(dto.getCanton());
        }
        if (dto.getDistrito() != null) {
            target.setDistrito(dto.getDistrito());
        }
        if (dto.getEmail() != null) {
            target.setEmail(dto.getEmail());
        }
        if (dto.getBirthDate() != null) {
            target.setBirthDate(dto.getBirthDate());
        }
        if (dto.getIdType() != null) {
            target.setIdType(dto.getIdType());
        }
        if (dto.getIdNumber() != null) {
            target.setIdNumber(dto.getIdNumber());
        }
        if (dto.getPhoneNumber() != null) {
            target.setPhoneNumber(dto.getPhoneNumber());
        }
        target.setTaxpayer(dto.isTaxpayer()); // primitive: always present
        target.setZoneCode(dto.getZoneCode()); // primitive: always present
        if (dto.getTipoIdentificacion() != null) {
            target.setTipoIdentificacion(dto.getTipoIdentificacion());
        }
        if (dto.getStatus() != null) {
            target.setStatus(dto.getStatus());
        }
        if (dto.getPuntosAcumulados() != null) {
            target.setPuntosAcumulados(dto.getPuntosAcumulados());
        }
        if (dto.getLastPurchaseDate() != null) {
            target.setLastPurchaseDate(dto.getLastPurchaseDate());
        }
        if (dto.getStatusPuntos() != null) {
            target.setStatusPuntos(dto.getStatusPuntos());
        }
    }

    /**
     * Replaces the actividad económica collection IN PLACE (clear + add on the
     * same list instance) as required for cascade=ALL + orphanRemoval=true to
     * track removals correctly under Hibernate.
     */
    private static void replaceActividades(@Nonnull Clients client,
                                           @Nullable List<ClientsDetailDTO.ActividadInfo> actividades) {
        client.getActividades().clear();
        if (actividades == null) {
            return;
        }
        for (ClientsDetailDTO.ActividadInfo info : actividades) {
            client.getActividades().add(new ClienteActividad(info.getCodigo(), info.getDescripcion(), client));
        }
    }

    /**
     * @return an error message when any actividad entry lacks a codigo, else null.
     */
    private static String validateActividades(@Nonnull ClientsDetailDTO dto) {
        if (dto.getActividades() == null) {
            return null;
        }
        for (ClientsDetailDTO.ActividadInfo info : dto.getActividades()) {
            if (info == null || info.getCodigo() == null || info.getCodigo().isBlank()) {
                return "El código de actividad económica no puede estar vacío.";
            }
        }
        return null;
    }

    private static <T> List<T> paginate(List<T> items, int page, int size) {
        int from = page * size;
        if (from >= items.size()) {
            return List.of();
        }
        return items.subList(from, Math.min(from + size, items.size()));
    }

    /** Row mapping for lists: identity, contact, loyalty points and status. */
    private ClientsDTO toDTO(Clients client) {
        return new ClientsDTO(client.getCode(), client.getName(), client.getAddress(),
                client.getIdType(), client.getIdNumber(), client.getEmail(),
                client.getPhoneNumber(), client.isTaxpayer(), client.getPuntosAcumulados(),
                client.getStatusPuntos(), client.getLastPurchaseDate(), client.getStatus());
    }

    /**
     * Full profile mapping including ubicacion (provincia/canton/distrito),
     * loyalty points state and flattened relations (usuario, actividades).
     * Must run inside a transaction: actividades is a LAZY collection.
     */
    private ClientsDetailDTO toDetailDTO(Clients client) {
        Models.Users usuario = client.getUsuario();
        List<ClientsDetailDTO.ActividadInfo> actividades = new ArrayList<>();
        if (client.getActividades() != null) {
            for (ClienteActividad actividad : client.getActividades()) {
                actividades.add(new ClientsDetailDTO.ActividadInfo(
                        actividad.getId(), actividad.getCodigo(), actividad.getDescripcion()));
            }
        }
        return new ClientsDetailDTO(client.getCode(), client.getName(), client.getAddress(),
                client.getProvincia(), client.getCanton(), client.getDistrito(),
                client.getEmail(), client.getBirthDate(), client.getIdType(),
                client.getIdNumber(), client.getPhoneNumber(), client.isTaxpayer(),
                client.getZoneCode(), client.getTipoIdentificacion(), client.getStatus(),
                client.getPuntosAcumulados(), client.getLastPurchaseDate(),
                client.getStatusPuntos(),
                usuario != null ? usuario.getId() : null,
                usuario != null ? usuario.getUsername() : null,
                actividades);
    }

    // ── W4B template-model helpers (CategoriaResource/T18 conventions) ──────

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    private static String trimToEmpty(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    @Nullable
    private static String emptyToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static int parseIntOrDefault(@Nullable String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** ISO yyyy-MM-dd (native date input) → java.util.Date; null when blank/unparseable. */
    @Nullable
    private static Date parseIsoDate(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Date.from(LocalDate.parse(raw.trim())
                    .atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ClientsDetailDTO buildDetailDTO(@Nonnull String nombre,
                                                   @Nullable String email,
                                                   @Nullable String address,
                                                   @Nullable String phoneNumber,
                                                   @Nullable String zoneCode,
                                                   @Nullable String idType,
                                                   @Nullable String idNumber,
                                                   @Nullable Date birthDate,
                                                   @Nullable String taxpayer,
                                                   @Nullable List<String> actividad) {
        ClientsDetailDTO dto = new ClientsDetailDTO();
        dto.setName(nombre);
        dto.setEmail(emptyToNull(email));
        dto.setAddress(emptyToNull(address));
        dto.setPhoneNumber(emptyToNull(phoneNumber));
        dto.setZoneCode(parseIntOrDefault(zoneCode, 0));
        dto.setIdType(emptyToNull(idType));
        dto.setIdNumber(emptyToNull(idNumber));
        dto.setBirthDate(birthDate);
        dto.setTaxpayer(taxpayer != null);
        List<ClientsDetailDTO.ActividadInfo> actividades = new ArrayList<>();
        if (actividad != null) {
            for (String codigo : actividad) {
                if (codigo != null && !codigo.isBlank()) {
                    actividades.add(new ClientsDetailDTO.ActividadInfo(null, codigo.trim(), null));
                }
            }
        }
        dto.setActividades(actividades);
        return dto;
    }

    /**
     * Shared tail of the HTMX form twins: success answers HX-Redirect so the
     * page reloads fresh (ui-kit §5); structured failures from the JSON
     * methods are converted into a 422 form redisplay + OOB toast
     * (ui-kit Pattern A); non-HTMX callers receive the original response.
     */
    private Response handleFormMutationResult(@Nonnull Response result,
                                              @Nonnull String modo,
                                              @Nullable Clients cliente,
                                              @Nonnull String redirectUrl) {
        if (!isHxRequest()) {
            return result;
        }
        int status = result.getStatus();
        if (status == Response.Status.CREATED.getStatusCode()
                || status == Response.Status.OK.getStatusCode()) {
            return hxRedirect(redirectUrl);
        }
        String mensaje = "No se pudo guardar el cliente";
        String severity = "error";
        if (result.getEntity() instanceof ApiResponse<?> api && api.getError() != null) {
            mensaje = api.getError().getMessage();
            String code = api.getError().getCode();
            if ("ID_TAKEN".equals(code) || "NAME_TAKEN".equals(code)) {
                severity = "warn";
            }
        }
        return redisplayForm(modo, cliente, null, mensaje, severity, mensaje);
    }

    private Response redisplayForm(@Nonnull String modo, @Nullable Clients cliente,
                                   @Nullable String errorNombre, @Nullable String errorGeneral,
                                   @Nullable String toastSeverity, @Nullable String toastMessage) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(formInstance(modo, cliente, errorNombre, errorGeneral,
                        toastSeverity, toastMessage).render())
                .build();
    }

    private TemplateInstance formInstance(@Nonnull String modo, @Nullable Clients cliente,
                                          @Nullable String errorNombre, @Nullable String errorGeneral,
                                          @Nullable String toastSeverity, @Nullable String toastMessage) {
        String birthDateIso = null;
        if (cliente != null && cliente.getBirthDate() != null) {
            birthDateIso = cliente.getBirthDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate().toString();
        }
        return formCliente
                .data("modo", modo)
                .data("cliente", cliente)
                .data("birthDateIso", birthDateIso)
                .data("errorNombre", errorNombre)
                .data("errorGeneral", errorGeneral)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TemplateInstance renderFullPage() {
        TableModel model = buildTableModel(1, 20, null, "asc", null);
        List<Clients> todos = orEmpty(clientService.listAll());
        long activos = todos.stream().filter(c -> c.getStatus() != null && c.getStatus()).count();
        return pageIndex
                .data("tablaClientes", model.asMap())
                .data("clientesTotal", model.total())
                .data("clientesActivosCount", activos)
                .data("clientesInactivosCount", todos.size() - activos);
    }

    private TemplateInstance tableInstance(int page, int size, @Nullable String sort,
                                           @Nullable String dir, @Nullable String q,
                                           @Nullable String toastSeverity,
                                           @Nullable String toastMessage) {
        TableModel model = buildTableModel(page, size, sort, dir, q);
        return tablaPage
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TableModel buildTableModel(int page, int size, @Nullable String sort,
                                       @Nullable String dir, @Nullable String q) {
        List<Clients> filas;
        if (q != null && !q.isBlank()) {
            List<Clients> matches = clientService.searchByName(q.trim());
            filas = new ArrayList<>(matches != null ? matches : List.of());
        } else {
            filas = new ArrayList<>(orEmpty(clientService.listAll()));
        }
        sortClients(filas, sort, dir);

        long total = filas.size();
        Window w = windowOf(total, page, size);
        filas = filas.subList(w.from(), w.to());

        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Estado", null));
        columnas.add(col("Código", "code"));
        columnas.add(col("Nombre", "name"));
        columnas.add(col("Dirección", "address"));
        columnas.add(col("Correo", "email"));
        columnas.add(col("Cédula", "idNumber"));
        columnas.add(col("Teléfono", "phoneNumber"));
        columnas.add(col("Tributario", null));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            filtros.put("q", q.trim());
        }

        return new TableModel("tabla-clientes", "/api/app/clientes/table", columnas, filas,
                sort, "desc".equalsIgnoreCase(dir) ? "desc" : "asc",
                w.page(), w.size(), total, w.totalPages(), pageWindow(w.page(), w.totalPages()),
                filtros, q);
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    private static void sortClients(@Nonnull List<Clients> rows, @Nullable String sort,
                                    @Nullable String dir) {
        if (rows.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Clients> cmp = switch (sort) {
            case "code" -> Comparator.comparingInt(Clients::getCode);
            case "name" -> Comparator.comparing(Clients::getName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "address" -> Comparator.comparing(Clients::getAddress,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "email" -> Comparator.comparing(Clients::getEmail,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "idNumber" -> Comparator.comparing(Clients::getIdNumber,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "phoneNumber" -> Comparator.comparing(Clients::getPhoneNumber,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(Clients::getStatus,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> null;
        };
        if (cmp != null) {
            rows.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    private static Map<String, Object> col(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

    private static List<Integer> pageWindow(int page, int totalPages) {
        if (totalPages <= 1) {
            return List.of(1);
        }
        List<Integer> pages = new ArrayList<>();
        int from = Math.max(1, page - 2);
        int to = Math.min(totalPages, page + 2);
        for (int i = from; i <= to; i++) {
            pages.add(i);
        }
        return pages;
    }

    private record Window(int page, int size, int from, int to, int totalPages) {}

    private static Window windowOf(long total, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);
        return new Window(p, s, from, to, totalPages);
    }

    /** Immutable view of everything pages/clientes/tabla.html needs. */
    public record TableModel(String id, String baseUrl, List<Map<String, Object>> columnas,
                             List<?> filas, String sortKey, String sortDir, int page, int size,
                             long total, int totalPages, List<Integer> paginas,
                             Map<String, Object> filtros, String q) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("baseUrl", baseUrl);
            map.put("columnas", columnas);
            map.put("filas", filas);
            map.put("sortKey", sortKey);
            map.put("sortDir", sortDir);
            map.put("page", page);
            map.put("size", size);
            map.put("total", total);
            map.put("totalPages", totalPages);
            map.put("paginas", paginas);
            map.put("filtros", filtros);
            map.put("q", q);
            return map;
        }
    }
}
