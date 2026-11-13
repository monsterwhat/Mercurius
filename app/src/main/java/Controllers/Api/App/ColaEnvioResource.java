package Controllers.Api.App;

import static Controllers.Api.App.AppRecursoBase.*;

import Controllers.Api.App.Reportes.ColaEnvioPagesResource;
import Controllers.Api.App.Reportes.ReportePageSupport;
import Models.DTO.ApiResponse;
import Models.EnvioFueraLinea;
import Services.EnvioFueraLineaService;
import Services.EnvioFueraLineaService.ColaFila;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Acciones de la cola de envío a Hacienda. La lectura vive en
 * {@link ColaEnvioPagesResource}; aquí solo se muta.
 *
 * <p>Los POST de HTMX devuelven el fragmento {@code tablero} re-renderizado
 * (con un aviso) para no perder el estado de la pantalla. Sin
 * {@code HX-Request} responden JSON.</p>
 */
@Path("/api/app/tributacion/cola-envio")
@RolesAllowed({"admin", "tributacion"})
public class ColaEnvioResource {

    private static final Logger LOG = Logger.getLogger(ColaEnvioResource.class);

    @Inject
    EnvioFueraLineaService service;

    @Inject
    HttpHeaders httpHeaders;

    @Inject
    SecurityIdentity identity;

    @Inject
    @Location("pages/tributacion/cola-envio")
    Template page;

    /** Gemelo JSON de la pantalla, útil para pruebas y automatización. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response vista() {
        try {
            return Response.ok(ApiResponse.ok(
                    ColaEnvioPagesResource.buildModel(service, false, null, null))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error construyendo la vista JSON de la cola de envío", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudo cargar la cola de envío"))
                    .build();
        }
    }

    /** Reintenta una fila concreta; revalida el gating del servidor. */
    @POST
    @Path("/{id}/reintentar")
    public Response reintentar(@PathParam("id") long id) {
        try {
            ColaFila fila = service.findColaFila(id);
            if (fila == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND",
                                "No se encontró el documento encolado"))
                        .build();
            }
            LocalDateTime ahora = LocalDateTime.now();
            if (!ColaEnvioPagesResource.puedeReintentar(fila, ahora)) {
                return tablero("warning", "No se reintentó: "
                        + ColaEnvioPagesResource.motivoNoReintentable(fila, ahora));
            }
            EnvioFueraLinea registro = service.find(id);
            if (registro == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND",
                                "No se encontró el documento encolado"))
                        .build();
            }
            EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(registro);
            String severidad = resultado.enviado ? "success"
                    : (resultado.escalado ? "danger" : "warning");
            String mensaje = resultado.mensaje != null ? resultado.mensaje
                    : (resultado.enviado ? "Documento transmitido" : "No se transmitió");
            LOG.info("Reintento manual de la cola: id=" + id + " enviado=" + resultado.enviado
                    + " escalado=" + resultado.escalado + " omitido=" + resultado.omitido
                    + " | user=" + currentUsername()
                    + " | source=ColaEnvioResource.reintentar()");
            return tablero(severidad, mensaje);
        } catch (RuntimeException e) {
            LOG.warn("Error reintentando el documento encolado " + id + ": " + e.getMessage()
                    + " | source=ColaEnvioResource.reintentar()", e);
            return tablero("danger", "No se pudo reintentar el documento");
        }
    }

    /** Reintenta el lote acotado, bajo el mismo candado que el scheduler. */
    @POST
    @Path("/reintentar-todos")
    public Response reintentarTodos() {
        try {
            int enviados = service.procesarPendientesConLock(ColaEnvioPagesResource.LOTE_UI);
            if (enviados < 0) {
                return tablero("warning",
                        "Ya hay un barrido del buzón en curso; intente de nuevo en unos segundos.");
            }
            LOG.info("Reintento manual del lote: enviados=" + enviados
                    + " | user=" + currentUsername()
                    + " | source=ColaEnvioResource.reintentarTodos()");
            return tablero(enviados > 0 ? "success" : "info",
                    enviados > 0
                            ? "Lote procesado: " + enviados + " documento(s) transmitido(s)."
                            : "No había documentos cuyo turno hubiera vencido.");
        } catch (RuntimeException e) {
            LOG.warn("Error reintentando el lote de la cola: " + e.getMessage()
                    + " | source=ColaEnvioResource.reintentarTodos()", e);
            return tablero("danger", "No se pudo procesar el lote");
        }
    }

    private String currentUsername() {
        return identity == null || identity.isAnonymous() || identity.getPrincipal() == null
                ? "anonimo" : identity.getPrincipal().getName();
    }

    private Response tablero(@Nonnull String severidad, @Nonnull String mensaje) {
        if (ReportePageSupport.isHxRequest(httpHeaders)) {
            TemplateInstance instance = page.getFragment("tablero").instance();
            ColaEnvioPagesResource.buildModel(service, false, severidad, mensaje)
                    .forEach(instance::data);
            return htmlOk(instance);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("severity", severidad);
        body.put("mensaje", mensaje);
        return Response.ok(ApiResponse.ok(body)).build();
    }
}
