package Controllers.Api.Marketplace;

import Models.Clients;
import Models.DTO.ProfileDTO;
import Models.DTO.UpdateProfileRequest;
import Services.ClientService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/api/marketplace/profile")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProfileController {

    private static final Logger LOG = Logger.getLogger(ProfileController.class.getName());

    @Inject
    @Nonnull
    ClientService clientService;

    @Context
    SecurityContext securityContext;

    @GET
    @Nonnull
    public Response getProfile() {
        try {
            int clientCode = getClientCode();
            Clients client = clientService.find(clientCode);
            if (client == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Cliente no encontrado\"}")
                        .build();
            }
            return Response.ok(toProfileDTO(client)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error getting profile", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al obtener perfil\"}")
                    .build();
        }
    }

    @PUT
    @Nonnull
    public Response updateProfile(@Nonnull UpdateProfileRequest request) {
        try {
            int clientCode = getClientCode();
            Clients client = clientService.find(clientCode);
            if (client == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Cliente no encontrado\"}")
                        .build();
            }

            if (request.getName() != null) client.setName(request.getName());
            if (request.getEmail() != null) client.setEmail(request.getEmail());
            if (request.getPhoneNumber() != null) client.setPhoneNumber(request.getPhoneNumber());
            if (request.getAddress() != null) client.setAddress(request.getAddress());
            if (request.getIdType() != null) client.setIdType(request.getIdType());
            if (request.getIdNumber() != null) client.setIdNumber(request.getIdNumber());

            clientService.update(client);
            return Response.ok(toProfileDTO(client)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error updating profile", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al actualizar perfil\"}")
                    .build();
        }
    }

    @Nonnull
    private static ProfileDTO toProfileDTO(@Nonnull Clients client) {
        ProfileDTO dto = new ProfileDTO();
        dto.setCode(client.getCode());
        dto.setName(client.getName());
        dto.setEmail(client.getEmail());
        dto.setPhoneNumber(client.getPhoneNumber());
        dto.setAddress(client.getAddress());
        dto.setIdType(client.getIdType());
        dto.setIdNumber(client.getIdNumber());
        dto.setBirthDate(client.getBirthDate());
        dto.setPuntosAcumulados(client.getPuntosAcumulados());
        dto.setStatusPuntos(client.getStatusPuntos());
        return dto;
    }

    private int getClientCode() {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new RuntimeException("No autenticado");
        }
        return Integer.parseInt(securityContext.getUserPrincipal().getName());
    }
}
