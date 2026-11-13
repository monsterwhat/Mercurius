package Controllers.Api.App.Reportes;

import static Controllers.Api.App.AppRecursoBase.*;

import Services.EnvioFueraLineaService;
import Services.EnvioFueraLineaService.ColaFila;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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
import org.jboss.logging.Logger;

/**
 * Pantalla de solo lectura de la cola de envío a Hacienda
 * ({@link EnvioFueraLineaService}), el outbox de Art. 21 ¶3.
 *
 * <p>El dashboard y las consultas de tributación muestran estados de
 * {@code ComprobantesEmitidos}; esta pantalla es la única que expone la
 * outbox real: intentos, próximo intento, vencimiento, origen y último error.
 * No promete tiempo real: el fragmento se refresca por polling y el dato
 * cambia como máximo cada 15 minutos (o cuando el operador reintenta).</p>
 *
 * <p><b>Conectividad:</b> el GET de la página NO ejecuta
 * {@link EnvioFueraLineaService#hayConectividadConHacienda()}; ese método es
 * {@code synchronized} y hace un connect de hasta 2,5 s, y la venta lo llama
 * dentro de la transacción que retiene el bloqueo del consecutivo. Solo el
 * botón explícito ({@code GET /conexion}) lo invoca.</p>
 */
@Path("/app/tributacion/cola-envio")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
public class ColaEnvioPagesResource {

    private static final Logger LOG = Logger.getLogger(ColaEnvioPagesResource.class);

    /** Filas visibles por tabla; el resto se resume en una nota. */
    public static final int LIMITE_PENDIENTES = 50;
    public static final int LIMITE_TERMINALES = 20;
    /** Tope de la acción manual "Reintentar el lote" (menor que el del job). */
    public static final int LOTE_UI = 10;

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    @Inject
    EnvioFueraLineaService service;

    @Inject
    HttpHeaders httpHeaders;

    @Inject
    @Location("pages/tributacion/cola-envio")
    Template page;

    /** Página completa; con {@code HX-Request} solo el fragmento {@code tablero}. */
    @GET
    public Response page() {
        try {
            TemplateInstance instance = isHxRequest()
                    ? page.getFragment("tablero").instance()
                    : page.instance();
            buildModel(service, false, null, null).forEach(instance::data);
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la cola de envío a Hacienda", e);
            return serverError("No se pudo cargar la cola de envío");
        }
    }

    /** Única ruta que ejecuta el probe TCP; devuelve solo el fragmento de conexión. */
    @GET
    @Path("/conexion")
    public Response conexion() {
        try {
            TemplateInstance instance = page.getFragment("conexion").instance();
            buildModel(service, true, null, null).forEach(instance::data);
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.warn("Error verificando la conectividad con Hacienda", e);
            return serverError("No se pudo verificar la conectividad");
        }
    }

    private boolean isHxRequest() {
        return ReportePageSupport.isHxRequest(httpHeaders);
    }

    private static Response serverError(@Nonnull String mensaje) {
        return Response.serverError()
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity("<p>" + mensaje + "</p>")
                .build();
    }

    /**
     * Modelo compartido por la página y por las acciones POST (que re-renderizan
     * el tablero con un toast). Qute es estricto: todas las claves se emiten
     * siempre; las opcionales se guardan con {@code ??} en el template.
     */
    public static Map<String, Object> buildModel(@Nonnull EnvioFueraLineaService service,
                                                 boolean verificarConexion,
                                                 @Nullable String toastSeverity,
                                                 @Nullable String toastMessage) {
        LocalDateTime ahora = LocalDateTime.now();
        long totalPendientes = service.contarPorEstado(EnvioFueraLineaService.ESTADO_PENDIENTE);
        long totalVencidos = service.contarVencidos();
        long totalRechazados = service.contarPorEstado(EnvioFueraLineaService.ESTADO_RECHAZADO);
        long enviadosHoy = service.contarEnviadosDesde(LocalDate.now().atStartOfDay());

        List<ColaFila> pendientes = service.findByEstado(
                EnvioFueraLineaService.ESTADO_PENDIENTE, LIMITE_PENDIENTES);
        List<ColaFila> vencidos = service.findByEstado(
                EnvioFueraLineaService.ESTADO_VENCIDO, LIMITE_TERMINALES);
        List<ColaFila> rechazados = service.findByEstado(
                EnvioFueraLineaService.ESTADO_RECHAZADO, LIMITE_TERMINALES);

        List<Map<String, Object>> filasPendientes = filas(pendientes, ahora);
        List<Map<String, Object>> filasVencidos = filas(vencidos, ahora);
        List<Map<String, Object>> filasRechazados = filas(rechazados, ahora);

        long contingenciaFilas = pendientes.stream()
                .filter(p -> EnvioFueraLineaService.SITUACION_FUERA_LINEA.equals(p.situacion()))
                .count();
        long pendientesDebidos = pendientes.stream()
                .filter(p -> puedeReintentar(p, ahora))
                .count();

        Boolean alcanzable = null;
        boolean verificada = false;
        boolean verificando = false;
        if (verificarConexion) {
            alcanzable = service.conectividadCacheada();
            if (alcanzable == null) {
                service.refrescarConectividadAsync();
                verificando = true;
            } else {
                verificada = true;
            }
        }

        String conexionTexto;
        String conexionChip;
        String conexionDetalle;
        if (verificando) {
            conexionTexto = "Verificando…";
            conexionChip = "is-info";
            conexionDetalle = "Comprobando la conexión TCP en segundo plano. "
                    + "Puede seguir vendiendo; la venta no espera este sondeo.";
        } else if (!verificada) {
            conexionTexto = "Sin verificar";
            conexionChip = "is-info";
            conexionDetalle = "La verificación corre en segundo plano (hasta 2,5 s). "
                    + "No bloquea ventas.";
        } else if (Boolean.TRUE.equals(alcanzable)) {
            conexionTexto = "TCP alcanzable";
            conexionChip = "is-success";
            conexionDetalle = "Verificado a las " + ahora.format(HORA)
                    + ". TCP alcanzable no garantiza que el API de Hacienda responda.";
        } else {
            conexionTexto = "No alcanzable";
            conexionChip = "is-danger";
            conexionDetalle = "Las ventas nuevas se emitirán con Situación 3 "
                    + "(contingencia, Art. 21 ¶3).";
        }

        String notaTope = totalPendientes > LIMITE_PENDIENTES
                ? "Se muestran los " + LIMITE_PENDIENTES
                        + " con vencimiento más próximo de " + totalPendientes
                : null;

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Cola de envío a Hacienda");
        model.put("baseUrl", "/app/tributacion/cola-envio");
        model.put("intervaloPoll", "30s");
        model.put("ahoraTexto", ahora.format(FECHA_HORA));
        model.put("totalPendientes", totalPendientes);
        model.put("totalVencidos", totalVencidos);
        model.put("totalRechazados", totalRechazados);
        model.put("enviadosHoy", enviadosHoy);
        model.put("maxIntentos", EnvioFueraLineaService.MAX_INTENTOS);
        model.put("loteMaximo", LOTE_UI);
        model.put("plazoDias", EnvioFueraLineaService.PLAZO_DIAS_HABILES);
        model.put("contingencia", contingenciaFilas > 0);
        model.put("contingenciaFilas", contingenciaFilas);
        model.put("conexionVerificada", verificada);
        model.put("verificando", verificando);
        model.put("haciendaAlcanzable", alcanzable);
        model.put("conexionTexto", conexionTexto);
        model.put("conexionChipClass", conexionChip);
        model.put("conexionDetalle", conexionDetalle);
        model.put("filasPendientes", filasPendientes);
        model.put("filasVencidos", filasVencidos);
        model.put("filasRechazados", filasRechazados);
        model.put("contadorPendientes", totalPendientes);
        model.put("contadorVencidos", totalVencidos);
        model.put("contadorRechazados", totalRechazados);
        model.put("pendientesDebidos", pendientesDebidos);
        model.put("puedeReintentarTodos", pendientesDebidos > 0);
        model.put("motivoNoLote", pendientesDebidos > 0 ? null
                : "No hay documentos cuyo turno haya vencido.");
        model.put("notaTope", notaTope);
        model.put("toastSeverity", toastSeverity);
        model.put("toastMessage", toastMessage);
        model.put("errorGeneral", null);
        return model;
    }

    /** ¿El botón por fila puede ejecutar un reintento real (no solo escalar)? */
    public static boolean puedeReintentar(@Nonnull ColaFila e, @Nonnull LocalDateTime ahora) {
        if (!EnvioFueraLineaService.ESTADO_PENDIENTE.equals(e.estado())) {
            return false;
        }
        if (e.vencimiento() != null && !ahora.isBefore(e.vencimiento())) {
            return false;
        }
        return e.proximoIntento() == null || !ahora.isBefore(e.proximoIntento());
    }

    /** Motivo legible para la UI cuando la fila no se puede reintentar. */
    public static String motivoNoReintentable(@Nonnull ColaFila e,
                                              @Nonnull LocalDateTime ahora) {
        boolean fueraDePlazo = e.vencimiento() != null && !ahora.isBefore(e.vencimiento());
        return motivoNoReintentable(e, ahora, fueraDePlazo);
    }

    private static List<Map<String, Object>> filas(@Nonnull List<ColaFila> registros,
                                                    @Nonnull LocalDateTime ahora) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ColaFila e : registros) {
            out.add(fila(e, ahora));
        }
        return out;
    }

    private static Map<String, Object> fila(@Nonnull ColaFila e,
                                            @Nonnull LocalDateTime ahora) {
        boolean fueraDePlazo = e.vencimiento() != null && !ahora.isBefore(e.vencimiento());
        boolean porVencer = !fueraDePlazo && e.vencimiento() != null
                && e.vencimiento().isBefore(ahora.plusHours(8));
        boolean puede = puedeReintentar(e, ahora);
        String motivo = puede ? "" : motivoNoReintentable(e, ahora, fueraDePlazo);
        String error = e.ultimoError();

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", e.id());
        f.put("tipoDocumento", e.tipoDocumento());
        f.put("tipoDocumentoTexto", tipoDocumentoTexto(e.tipoDocumento()));
        f.put("consecutivo", e.consecutivo());
        f.put("clave", e.clave());
        f.put("claveCorta", claveCorta(e.clave()));
        f.put("fechaEmision", e.fechaEmision());
        f.put("vencimiento", e.vencimiento());
        f.put("intentos", e.intentos() == null ? 0 : e.intentos());
        f.put("maxIntentos", EnvioFueraLineaService.MAX_INTENTOS);
        f.put("intentosTexto", (e.intentos() == null ? 0 : e.intentos())
                + "/" + EnvioFueraLineaService.MAX_INTENTOS);
        f.put("proximoIntento", e.proximoIntento());
        f.put("origen", e.origen());
        f.put("origenTexto", origenTexto(e.origen()));
        f.put("situacion", e.situacion());
        f.put("ultimoErrorResumen", recortar(error, 140));
        f.put("ultimoErrorCompleto", error);
        f.put("tieneError", error != null && !error.isBlank());
        f.put("porVencer", porVencer);
        f.put("fueraDePlazo", fueraDePlazo);
        f.put("puedeReintentar", puede);
        f.put("motivoNoReintentable", motivo);
        return f;
    }

    private static String motivoNoReintentable(@Nonnull ColaFila e,
                                               @Nonnull LocalDateTime ahora,
                                               boolean fueraDePlazo) {
        if (!EnvioFueraLineaService.ESTADO_PENDIENTE.equals(e.estado())) {
            return "Estado " + e.estado();
        }
        if (fueraDePlazo) {
            return "Plazo vencido: no transmisible (rechazado)";
        }
        if (e.proximoIntento() != null && ahora.isBefore(e.proximoIntento())) {
            return "Programado para " + e.proximoIntento().format(FECHA_HORA);
        }
        return "No reintentable";
    }

    private static String tipoDocumentoTexto(@Nullable String codigo) {
        if (codigo == null) {
            return "?";
        }
        return switch (codigo) {
            case "01" -> "FE";
            case "02" -> "NC";
            case "03" -> "ND";
            case "04" -> "TE";
            case "05" -> "FEE";
            case "08" -> "FEC";
            case "10" -> "REP";
            default -> codigo;
        };
    }

    private static String origenTexto(@Nullable String origen) {
        if (EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3.equals(origen)) {
            return "Sin conexión";
        }
        if (EnvioFueraLineaService.ORIGEN_FALLO_ENVIO_INMEDIATO.equals(origen)) {
            return "Fallo de envío";
        }
        return origen == null ? "—" : origen;
    }

    private static String claveCorta(@Nullable String clave) {
        if (clave == null || clave.isBlank()) {
            return "—";
        }
        if (clave.length() <= 20) {
            return clave;
        }
        return clave.substring(0, 12) + "…" + clave.substring(clave.length() - 6);
    }

    private static String recortar(@Nullable String texto, int max) {
        if (texto == null) {
            return "";
        }
        String limpio = texto.replace('\n', ' ').replace('\r', ' ').trim();
        return limpio.length() <= max ? limpio : limpio.substring(0, max) + "…";
    }
}
