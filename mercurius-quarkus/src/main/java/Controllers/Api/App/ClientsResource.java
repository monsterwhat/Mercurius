package Controllers.Api.App;

import Models.ClienteActividad;
import Models.Clients;
import Models.DTO.ApiResponse;
import Models.DTO.ClientsDTO;
import Models.DTO.ClientsDetailDTO;
import Models.DTO.PagedResponse;
import Services.AlertasService;
import Services.ClientService;
import Utils.DiffUtils;
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
import java.util.ArrayList;
import java.util.List;
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

    @Inject
    @Nonnull
    ClientService clientService;

    @Inject
    @Nonnull
    AlertasService alertas;

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

            alertas.registrarAlerta("Se creo el cliente",
                    "Se creo el cliente: " + client.getName(),
                    null, 0, "ClientsResource.create()",
                    null, DiffUtils.snapshotEntity(client));

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
            alertas.registrarAlerta("Cliente Actualizado",
                    "Se actualizo el cliente: " + client.getName(),
                    null, 0, "ClientsResource.update()",
                    antes, DiffUtils.snapshotEntity(client));

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

            alertas.registrarAlerta("Se cambio el status del cliente",
                    "El estatus cambio",
                    null, 0, "ClientsResource.delete()",
                    antes, DiffUtils.snapshotEntity(client));

            return Response.ok(ApiResponse.ok(toDetailDTO(client))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error deleting client " + code, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error eliminando el cliente"))
                    .build();
        }
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
}
