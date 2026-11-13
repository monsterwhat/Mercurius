package Controllers.Api.App.Reportes;

import static Controllers.Api.App.AppRecursoBase.*;

import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import Models.EnvioFueraLinea;
import Models.Usuarios;
import Services.ComprobantesEmitidosService;
import Services.EnvioFueraLineaService;
import Services.LoginService;
import Services.ReversionInventarioService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Anulacion de una factura que aun NO existe en Hacienda.
 *
 * <p>Reglas fiscales que este recurso hace cumplir:</p>
 * <ul>
 *   <li>Un comprobante ACEPTADO no se anula: se revierte con nota de credito
 *       ({@code /app/devoluciones}).</li>
 *   <li>Un comprobante RECHAZADO se reemite
 *       ({@code POST /api/app/tributacion/consultas/{id}/corregir}).</li>
 *   <li>Un comprobante firmado y en cola (Situacion 3) tampoco se anula: hay
 *       que transmitirlo dentro del plazo del Art. 21. Se ofrece "Enviar
 *       ahora" y "Ver cola".</li>
 *   <li>Solo un pendiente local (sin fila de outbox activa) puede anularse. Se
 *       revierte el inventario, se marca {@code ANULADO} y se cancela la fila
 *       del outbox si existiera. El comprobante no se borra (auditoria) y el
 *       consecutivo no se reutiliza.</li>
 * </ul>
 */
@Path("/app/tributacion/consulta")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
public class ConsultaAnulacionResource {

    private static final Logger LOG = Logger.getLogger(ConsultaAnulacionResource.class);

    @Inject
    ComprobantesEmitidosService emitidosService;

    @Inject
    EnvioFueraLineaService envioFueraLineaService;

    @Inject
    ReversionInventarioService reversionInventarioService;

    @Inject
    ConsultaDetalleModel detalleModel;

    @Inject
    LoginService loginService;

    @Inject
    SecurityContext securityContext;

    @Inject
    @Location("pages/tributacion/consulta-detalle")
    Template detalleTemplate;

    @POST
    @Path("/{id}/anular")
    @Transactional
    public Response anular(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos factura = emitidosService.find(id);
            if (factura == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                        .entity("<p>No se encontró la factura.</p>")
                        .build();
            }

            String estado = estadoDe(factura);
            EnvioFueraLinea outbox = envioFueraLineaService.buscarPorComprobanteId(id);

            if ("ACEPTADO".equalsIgnoreCase(estado)) {
                return rechazo(factura, "Una factura aceptada por Hacienda no se anula: "
                        + "se revierte con una nota de crédito.", Response.Status.CONFLICT);
            }
            if ("RECHAZADO".equalsIgnoreCase(estado)) {
                return rechazo(factura, "Una factura rechazada se reemite con «Reemitir»; "
                        + "no se anula.", Response.Status.CONFLICT);
            }
            if ("ANULADO".equalsIgnoreCase(estado)) {
                return rechazo(factura, "La factura ya está anulada.", Response.Status.CONFLICT);
            }
            if (outbox != null && EnvioFueraLineaService.ESTADO_PENDIENTE.equals(outbox.getEstado())) {
                return rechazo(factura, "El documento ya está firmado y en cola (Situación 3): "
                        + "debe transmitirse dentro del plazo legal. Use «Enviar ahora» o «Ver cola».",
                        Response.Status.CONFLICT);
            }

            Usuarios usuario = currentUser();
            String consecutivo = factura.getEncabezado() != null
                    ? factura.getEncabezado().getNumeroConsecutivo() : String.valueOf(id);

            int movidas = reversionInventarioService.reingresarTodo(
                    factura.getDetalles() != null ? factura.getDetalles().getLineasDetalle() : null,
                    "Anulación factura: " + consecutivo,
                    usuario);

            if (outbox != null) {
                envioFueraLineaService.cancelarPorComprobante(id,
                        "Comprobante anulado localmente antes de transmitirse");
            }

            factura.setHaciendaEstado("ANULADO");
            factura.setStatus(Boolean.FALSE);
            Encabezado enc = factura.getEncabezado();
            if (enc != null) {
                enc.setEstado("ANULADO");
            }
            emitidosService.update(factura);

            LOG.info("Factura anulada localmente: " + consecutivo + " (id " + id + ")"
                    + ", lineas con inventario revertido: " + movidas
                    + ", fila de outbox cancelada: " + (outbox != null)
                    + " | user=" + (usuario != null ? usuario.getUsername() : "?")
                    + " | source=ConsultaAnulacionResource.anular()"
                    + " | despues=estado ANULADO, consecutivo no reutilizado");

            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("avisoOk", "Factura anulada. Se revirtieron " + movidas
                    + " línea(s) de inventario; el consecutivo no se reutiliza.");
            return htmlOk(detalleTemplate.data(detalleModel.modelo(factura, extras)).render());
        } catch (RuntimeException e) {
            LOG.error("Error anulando la factura " + id, e);
            return Response.serverError()
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity("<div class=\"notification is-danger\">No se pudo anular la factura. "
                            + "Revise el log del servidor.</div>")
                    .build();
        }
    }

    /**
     * "Enviar ahora" del drawer: transmite la fila en cola y vuelve a pintar
     * el mismo cuerpo con el resultado. Reutiliza el motor de reintento de la
     * cola; no crea ni borra documentos.
     */
    @POST
    @Path("/{id}/enviar-ahora")
    @Transactional
    public Response enviarAhora(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos factura = emitidosService.find(id);
            if (factura == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                        .entity("<p>No se encontró la factura.</p>")
                        .build();
            }
            EnvioFueraLinea outbox = envioFueraLineaService.buscarPorComprobanteId(id);
            if (outbox == null
                    || !EnvioFueraLineaService.ESTADO_PENDIENTE.equals(outbox.getEstado())) {
                return rechazo(factura, "La factura no tiene un documento pendiente en la cola.",
                        Response.Status.CONFLICT);
            }

            EnvioFueraLineaService.ResultadoReintento resultado =
                    envioFueraLineaService.reintentar(outbox);
            String mensaje = resultado.mensaje != null ? resultado.mensaje
                    : (resultado.enviado ? "Documento transmitido y aceptado por Hacienda."
                            : "No se transmitió el documento.");

            LOG.info("Envío manual desde el drawer de consultas: factura " + id
                    + ", enviado=" + resultado.enviado + ", escalado=" + resultado.escalado
                    + ", omitido=" + resultado.omitido
                    + " | source=ConsultaAnulacionResource.enviarAhora()");

            Map<String, Object> extras = new LinkedHashMap<>();
            if (resultado.enviado) {
                extras.put("avisoOk", mensaje);
            } else {
                extras.put("avisoError", mensaje);
            }
            return htmlOk(detalleTemplate.data(detalleModel.modelo(factura, extras)).render());
        } catch (RuntimeException e) {
            LOG.error("Error enviando ahora la factura " + id, e);
            return Response.serverError()
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity("<div class=\"notification is-danger\">No se pudo enviar el documento. "
                            + "Revise el log del servidor.</div>")
                    .build();
        }
    }

    private @Nonnull Response rechazo(@Nonnull ComprobantesEmitidos factura,
                                      @Nonnull String mensaje,
                                      @Nonnull Response.Status status) {
        LOG.warn("Anulación rechazada para la factura " + factura.getId() + ": " + mensaje
                + " | source=ConsultaAnulacionResource.anular()");
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("avisoError", mensaje);
        return Response.status(status)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(detalleTemplate.data(detalleModel.modelo(factura, extras)).render())
                .build();
    }

    private static @Nullable String estadoDe(@Nonnull ComprobantesEmitidos f) {
        if (f.getHaciendaEstado() != null) {
            return f.getHaciendaEstado();
        }
        return f.getEncabezado() != null ? f.getEncabezado().getEstado() : null;
    }

    private @Nullable Usuarios currentUser() {
        try {
            if (securityContext == null || securityContext.getUserPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(securityContext.getUserPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.debug("No se pudo resolver el usuario actual", e);
            return null;
        }
    }
}
