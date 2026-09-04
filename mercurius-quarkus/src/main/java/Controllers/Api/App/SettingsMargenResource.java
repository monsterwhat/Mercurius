package Controllers.Api.App;

import Models.ConfiguracionMargen;
import Models.DTO.ApiResponse;
import Models.Users;
import Services.ConfiguracionMargenService;
import Services.LoginService;
import Utils.DiffUtils;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Configuración global de márgenes de ganancia.
 * Admin-only, endpoints RESTful. Cada guardado crea un NUEVO registro
 * (patrón INSERT-only) — el historial es la tabla misma.
 */
@Path("/api/app/settings/margen")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("admin")
public class SettingsMargenResource {

    private static final Logger LOG = Logger.getLogger(SettingsMargenResource.class);

    @Nonnull
    @Inject
    ConfiguracionMargenService margenService;

    @Nonnull
    @Inject
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /**
     * GET /api/app/settings/margen — JSON con configuración actual e historial.
     */
    @GET
    public Response data() {
        ConfiguracionMargen actual = margenService.findOrCreateDefault();
        List<ConfiguracionMargen> historial = margenService.listAllOrderById();
        return Response.ok(ApiResponse.ok(Map.of(
                "actual", Map.of(
                        "id", actual.getId(),
                        "margenBase", actual.getMargenBase(),
                        "ajusteRefrigerado", actual.getAjusteRefrigerado(),
                        "ajusteCongelado", actual.getAjusteCongelado(),
                        "fechaCreacion", actual.getFechaCreacion()
                ),
                "historial", historial
        ))).build();
    }

    /**
     * POST /api/app/settings/margen — guarda una nueva configuración.
     * Siempre se inserta un nuevo registro (INSERT-only).
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response guardar(
            @FormParam("margenBase") @Nullable String margenBase,
            @FormParam("ajusteRefrigerado") @Nullable String ajusteRefrigerado,
            @FormParam("ajusteCongelado") @Nullable String ajusteCongelado) {
        try {
            ConfiguracionMargen antes = margenService.getConfiguracionActual();

            ConfiguracionMargen nuevo = new ConfiguracionMargen();
            nuevo.setMargenBase(parseDecimal(margenBase, new BigDecimal("25.00")));
            nuevo.setAjusteRefrigerado(parseDecimal(ajusteRefrigerado, new BigDecimal("5.00")));
            nuevo.setAjusteCongelado(parseDecimal(ajusteCongelado, new BigDecimal("10.00")));
            nuevo.setUsuario(currentUserOrNull());

            margenService.create(nuevo);

            LOG.info("Configuración de márgenes actualizada | user=" + String.valueOf(currentUserOrNull())
                    + " | source=SettingsMargenResource.guardar()"
                    + " | antes=" + (antes == null ? "(sin config previa)" : DiffUtils.snapshotEntity(antes))
                    + " | despues=" + DiffUtils.snapshotEntity(nuevo));

            return Response.ok(ApiResponse.ok(Map.of(
                    "success", true,
                    "id", nuevo.getId(),
                    "margenBase", nuevo.getMargenBase(),
                    "ajusteRefrigerado", nuevo.getAjusteRefrigerado(),
                    "ajusteCongelado", nuevo.getAjusteCongelado()
            ))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error guardando configuración de márgenes", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error guardando la configuración de márgenes"))
                    .build();
        }
    }

    @Nullable
    private static BigDecimal parseDecimal(@Nullable String raw, @Nonnull BigDecimal fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Nullable
    private Users currentUserOrNull() {
        try {
            if (securityIdentity == null || securityIdentity.isAnonymous()
                    || securityIdentity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(securityIdentity.getPrincipal().getName());
        } catch (Exception e) {
            return null;
        }
    }
}
