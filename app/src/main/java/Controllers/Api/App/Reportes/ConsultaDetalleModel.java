package Controllers.Api.App.Reportes;

import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.EnvioFueraLinea;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.ResumenFactura;
import Services.EnvioFueraLineaService;
import Utils.FormatoVista;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modelo del drawer de una factura emitida en Consultas.
 *
 * <p>Lo comparten la vista de detalle
 * ({@link ConsultaDetalleResource}) y la anulacion
 * ({@link ConsultaAnulacionResource}): al anular, la respuesta vuelve a pintar
 * el mismo cuerpo del drawer con el estado ya actualizado.</p>
 */
@ApplicationScoped
public class ConsultaDetalleModel {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Inject
    EnvioFueraLineaService envioFueraLineaService;

    /** Modelo del drawer con el estado actual del comprobante. */
    public @Nonnull Map<String, Object> modelo(@Nonnull ComprobantesEmitidos f) {
        return modelo(f, Map.of());
    }

    /**
     * Modelo del drawer con avisos adicionales.
     *
     * @param extras claves extra para la plantilla ({@code avisoOk},
     *               {@code avisoError}); se copian al final del modelo
     */
    public @Nonnull Map<String, Object> modelo(@Nonnull ComprobantesEmitidos f,
                                               @Nonnull Map<String, Object> extras) {
        Encabezado enc = f.getEncabezado();
        ResumenFactura res = f.getResumen();
        String moneda = res != null && res.getCodigoMoneda() != null
                && res.getCodigoMoneda().getCodigoMoneda() != null
                ? res.getCodigoMoneda().getCodigoMoneda() : "CRC";
        String estado = f.getHaciendaEstado() != null
                ? f.getHaciendaEstado()
                : (enc != null ? enc.getEstado() : null);

        boolean aceptado = "ACEPTADO".equalsIgnoreCase(estado);
        boolean rechazado = "RECHAZADO".equalsIgnoreCase(estado);
        boolean anulado = "ANULADO".equalsIgnoreCase(estado);
        EnvioFueraLinea outbox = envioFueraLineaService.buscarPorComprobanteId(f.getId());
        boolean enCola = outbox != null
                && EnvioFueraLineaService.ESTADO_PENDIENTE.equals(outbox.getEstado());
        boolean pendienteLocal = !aceptado && !rechazado && !anulado && !enCola;

        List<Map<String, Object>> lineas = new ArrayList<>();
        if (f.getDetalles() != null && f.getDetalles().getLineasDetalle() != null) {
            for (LineaDetalle l : f.getDetalles().getLineasDetalle()) {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("cantidad", l.getCantidad());
                fila.put("detalle", l.getDetalle());
                fila.put("servicio", Boolean.TRUE.equals(l.getEsServicio()));
                fila.put("precio", FormatoVista.moneda(l.getPrecioUnitario(), moneda));
                fila.put("total", FormatoVista.moneda(
                        l.getMontoTotalLinea() != null ? l.getMontoTotalLinea() : l.getMontoTotal(),
                        moneda));
                lineas.add(fila);
            }
        }

        List<Map<String, Object>> timeline = new ArrayList<>();
        if (enc != null && enc.getFechaEmision() != null) {
            timeline.add(evento("Emitida", enc.getFechaEmision(), "Comprobante generado"));
        }
        if (f.getHaciendaFechaEnvio() != null) {
            timeline.add(evento("Enviada a Hacienda", f.getHaciendaFechaEnvio(), "Transmisión"));
        }
        if (f.getHaciendaFechaRespuesta() != null) {
            timeline.add(evento("Respuesta de Hacienda", f.getHaciendaFechaRespuesta(), estado));
        }
        if (enc != null && enc.getMotivoRechazo() != null) {
            timeline.add(evento("Motivo de rechazo", null, enc.getMotivoRechazo()));
        }
        if (outbox != null) {
            timeline.add(evento("Cola de envío", outbox.getFechaCreacion(),
                    outbox.getEstado() + " · intentos " + outbox.getIntentos()));
        }

        List<Map<String, Object>> referencias = new ArrayList<>();
        if (f.getInformacionReferencia() != null) {
            for (InformacionReferencia r : f.getInformacionReferencia()) {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("tipo", r.getTipoDoc());
                fila.put("numero", r.getNumero());
                fila.put("fecha", r.getFechaEmision() != null ? FECHA.format(r.getFechaEmision()) : null);
                fila.put("codigo", r.getCodigo());
                fila.put("razon", r.getRazon());
                referencias.add(fila);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
        m.put("clave", f.getHaciendaClave() != null
                ? f.getHaciendaClave() : (enc != null ? enc.getClave() : null));
        m.put("fecha", enc != null && enc.getFechaEmision() != null
                ? FECHA.format(enc.getFechaEmision()) : null);
        m.put("cliente", enc != null && enc.getReceptor() != null
                ? enc.getReceptor().getNombre() : "Cliente contado");
        m.put("estado", estado);
        m.put("moneda", moneda);
        m.put("total", res != null
                ? FormatoVista.moneda(res.getTotalComprobante(), moneda) : null);
        m.put("totalImpuesto", res != null
                ? FormatoVista.moneda(res.getTotalImpuesto(), moneda) : null);
        m.put("totalDescuentos", res != null
                ? FormatoVista.moneda(res.getTotalDescuentos(), moneda) : null);
        m.put("lineas", lineas);
        m.put("timeline", timeline);
        m.put("referencias", referencias);
        m.put("aceptado", aceptado);
        m.put("rechazado", rechazado);
        m.put("anulado", anulado);
        m.put("pendienteLocal", pendienteLocal);
        m.put("enCola", enCola);
        m.put("outboxId", outbox != null ? outbox.getId() : null);
        m.put("outboxEstado", outbox != null ? outbox.getEstado() : null);
        m.put("ncUrl", "/app/devoluciones?facturaId=" + f.getId());
        m.put("colaUrl", "/app/tributacion/cola-envio");
        m.put("avisoOk", null);
        m.put("avisoError", null);
        m.putAll(extras);
        return m;
    }

    private static Map<String, Object> evento(@Nonnull String nombre,
                                              @Nullable LocalDateTime fecha,
                                              @Nullable String detalle) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("evento", nombre);
        e.put("fecha", fecha != null ? FECHA.format(fecha) : null);
        e.put("detalle", detalle);
        return e;
    }
}
