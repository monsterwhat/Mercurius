package Controllers.Api.App;

import Models.ConfiguracionPronostico;
import Models.DTO.ApiResponse;
import Models.Usuarios;
import Services.ConfiguracionPronosticoService;
import Services.LoginService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Parametros economicos del pronostico de demanda y de la reposicion.
 *
 * <p>Admin/inventario. Cada guardado crea una fila nueva (patron INSERT-only
 * de {@link Models.ConfiguracionMargen}); el historial es la propia tabla.
 * Los valores se leen desde {@link ConfiguracionPronosticoService} en cada
 * corrida del motor, de modo que un cambio en la pantalla aplica al siguiente
 * pronostico sin reiniciar la aplicacion.</p>
 */
@Path("/api/app/settings/pronostico")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
public class SettingsPronosticoResource {

    private static final Logger LOG = Logger.getLogger(SettingsPronosticoResource.class);

    private static final BigDecimal NIVEL_MINIMO = new BigDecimal("0.500");
    private static final BigDecimal NIVEL_MAXIMO = new BigDecimal("0.999");
    private static final BigDecimal COSTO_MAXIMO = new BigDecimal("1000000000");
    private static final int PLAZO_MAXIMO = 365;
    private static final int CANTIDAD_MAXIMA = 1000000;

    @Nonnull
    @Inject
    ConfiguracionPronosticoService pronosticoService;

    @Nonnull
    @Inject
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    @GET
    public Response data() {
        try {
            ConfiguracionPronostico actual = pronosticoService.findOrCreateDefault();
            List<ConfiguracionPronostico> historial = pronosticoService.listAllOrderById();
            return Response.ok(ApiResponse.ok(Map.of(
                    "actual", aMapa(actual),
                    "historial", historial.stream().map(SettingsPronosticoResource::aMapa).toList()
            ))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error leyendo la configuracion de pronostico", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error consultando la configuración de pronóstico"))
                    .build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response guardar(
            @FormParam("nivelServicio") @Nullable String nivelServicio,
            @FormParam("costoFaltante") @Nullable String costoFaltante,
            @FormParam("costoMantenimiento") @Nullable String costoMantenimiento,
            @FormParam("plazoReposicionDias") @Nullable String plazoReposicionDias,
            @FormParam("cantidadMinimaPedido") @Nullable String cantidadMinimaPedido,
            @FormParam("multiploPedido") @Nullable String multiploPedido) {
        try {
            ConfiguracionPronostico antes = pronosticoService.getConfiguracionActual();

            ConfiguracionPronostico nuevo = new ConfiguracionPronostico();
            nuevo.setNivelServicio(decimal(nivelServicio, new BigDecimal("0.950"),
                    "El nivel de servicio", NIVEL_MINIMO, NIVEL_MAXIMO));
            nuevo.setCostoFaltante(decimal(costoFaltante, new BigDecimal("10.00"),
                    "El costo de faltante", BigDecimal.ZERO, COSTO_MAXIMO));
            nuevo.setCostoMantenimiento(decimal(costoMantenimiento, new BigDecimal("1.00"),
                    "El costo de mantenimiento", BigDecimal.ZERO, COSTO_MAXIMO));
            nuevo.setPlazoReposicionDias(entero(plazoReposicionDias, 7,
                    "El plazo de reposición", 1, PLAZO_MAXIMO));
            nuevo.setCantidadMinimaPedido(entero(cantidadMinimaPedido, 0,
                    "La cantidad mínima de pedido", 0, CANTIDAD_MAXIMA));
            nuevo.setMultiploPedido(entero(multiploPedido, 1,
                    "El múltiplo de pedido", 1, CANTIDAD_MAXIMA));

            if (nuevo.getCostoFaltante().compareTo(BigDecimal.ZERO) == 0
                    && nuevo.getCostoMantenimiento().compareTo(BigDecimal.ZERO) == 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "Al menos uno de los dos costos debe ser mayor que cero."))
                        .build();
            }

            pronosticoService.guardar(nuevo, currentUserOrNull());

            LOG.info("Configuración de pronóstico actualizada"
                    + " | user=" + Utils.LogUsers.nombre(currentUserOrNull())
                    + " | source=SettingsPronosticoResource.guardar()"
                    + " | idAnterior=" + (antes == null ? "(sin config previa)" : antes.getId())
                    + " | idNuevo=" + nuevo.getId()
                    + " | nivel=" + nuevo.getNivelServicio()
                    + " | faltante=" + nuevo.getCostoFaltante()
                    + " | mantenimiento=" + nuevo.getCostoMantenimiento()
                    + " | plazo=" + nuevo.getPlazoReposicionDias()
                    + " | minimo=" + nuevo.getCantidadMinimaPedido()
                    + " | multiplo=" + nuevo.getMultiploPedido());

            return Response.ok(ApiResponse.ok(Map.of(
                    "success", true,
                    "actual", aMapa(nuevo)
            ))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", e.getMessage()))
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error guardando la configuracion de pronostico", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error guardando la configuración de pronóstico"))
                    .build();
        }
    }

    @Nonnull
    private static Map<String, Object> aMapa(@Nonnull ConfiguracionPronostico config) {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", config.getId());
        fila.put("nivelServicio", config.getNivelServicio());
        fila.put("costoFaltante", config.getCostoFaltante());
        fila.put("costoMantenimiento", config.getCostoMantenimiento());
        fila.put("plazoReposicionDias", config.getPlazoReposicionDias());
        fila.put("cantidadMinimaPedido", config.getCantidadMinimaPedido());
        fila.put("multiploPedido", config.getMultiploPedido());
        fila.put("fechaCreacion", config.getFechaCreacion());
        return fila;
    }

    @Nonnull
    private static BigDecimal decimal(@Nullable String raw,
                                      @Nonnull BigDecimal porDefecto,
                                      @Nonnull String etiqueta,
                                      @Nonnull BigDecimal minimo,
                                      @Nonnull BigDecimal maximo) {
        if (raw == null || raw.isBlank()) {
            return porDefecto;
        }
        BigDecimal valor;
        try {
            valor = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(etiqueta + " debe ser un número.");
        }
        if (valor.compareTo(minimo) < 0 || valor.compareTo(maximo) > 0) {
            throw new IllegalArgumentException(etiqueta + " debe estar entre "
                    + minimo.toPlainString() + " y " + maximo.toPlainString() + ".");
        }
        return valor;
    }

    private static int entero(@Nullable String raw,
                              int porDefecto,
                              @Nonnull String etiqueta,
                              int minimo,
                              int maximo) {
        if (raw == null || raw.isBlank()) {
            return porDefecto;
        }
        int valor;
        try {
            valor = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(etiqueta + " debe ser un número entero.");
        }
        if (valor < minimo || valor > maximo) {
            throw new IllegalArgumentException(etiqueta + " debe estar entre "
                    + minimo + " y " + maximo + ".");
        }
        return valor;
    }

    @Nullable
    private Usuarios currentUserOrNull() {
        try {
            if (securityIdentity == null || securityIdentity.isAnonymous()
                    || securityIdentity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(securityIdentity.getPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.debug("No se pudo resolver el usuario de la configuración de pronóstico", e);
            return null;
        }
    }
}
