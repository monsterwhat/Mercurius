package Services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.jboss.logging.Logger;

import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.MedioPago;
import Models.Encabezado.Receptor;
import Models.Resumen.MedioPagoR;
import Models.Resumen.ResumenFactura;
import Utils.ComprobanteFactory;
import Utils.Parsers.Parser;

/**
 * Importación de comprobantes EMITIDOS históricos (migración desde el sistema
 * FoxPro "vsuper" y desde los XML firmados descargados de Hacienda).
 *
 * <p><b>Por qué existe:</b> {@link Parser#parseXML(java.io.InputStream)} persiste
 * SIEMPRE como {@code ComprobantesRecibidos} (compras). Los comprobantes que la
 * empresa emitió históricamente no tienen ningún endpoint de carga: sólo se
 * crean por el flujo de venta (POS). Este servicio reconstruye el histórico
 * desde el XML original.</p>
 *
 * <p><b>Garantías de seguridad (importante):</b></p>
 * <ul>
 *   <li>NUNCA se contacta a Hacienda: el documento se persiste con su estado
 *       real (ACEPTADO/RECHAZADO) y con {@code haciendaFechaEnvio} original.</li>
 *   <li>Se rechaza cualquier estado no terminal ({@code PENDIENTE}): un
 *       histórico en estado PENDIENTE sería capturado por el scheduler de
 *       reenvío ({@code Utils.ProgramadorTareas.enviarFacturasPendientes}) y
 *       volvería a enviarse a Hacienda, duplicando comprobantes.</li>
 *   <li>Duplicados por número consecutivo se omiten (idempotente).</li>
 *   <li>Tras persistir, el contador de consecutivos se eleva al valor
 *       histórico para que los próximos documentos no reutilicen números.</li>
 * </ul>
 */
@ApplicationScoped
public class HistoricoEmitidosService {

    private static final Logger LOG = Logger.getLogger(HistoricoEmitidosService.class);

    /** Estados que el scheduler de reenvío NO debe capturar. */
    private static final String ESTADO_ACEPTADO = "ACEPTADO";

    @Inject @Nonnull
    Parser parser;
    @Inject @Nonnull
    ComprobantesEmitidosService comprobantesEmitidosService;
    @Inject @Nonnull
    ConsecutivoEmitidoService consecutivoEmitidoService;
    @Inject @Nonnull
    Services.Facturas.EmisorService emisorService;
    @Inject @Nonnull
    Services.Facturas.ReceptorService receptorService;
    @Inject @Nonnull
    Services.Facturas.EncabezadoService encabezadoService;

    /**
     * Resultado de importar un documento histórico.
     *
     * @param clave       clave de 50 dígitos del comprobante
     * @param importado   true si se persistió un comprobante nuevo
     * @param duplicado   true si ya existía (idempotencia)
     * @param mensaje     detalle legible para el operador
     */
    public record Resultado(String clave, boolean importado, boolean duplicado, String mensaje) { }

    /**
     * Importa un comprobante emitido histórico desde su XML.
     *
     * @param xmlContenido  contenido XML del comprobante
     * @param estado        estado real en Hacienda (obligatorio, no PENDIENTE)
     * @param fechaEnvio    fecha original de envío (opcional)
     * @param usuario       usuario que ejecuta la importación
     */
    @Transactional
    public @Nonnull Resultado importar(@Nonnull String xmlContenido,
                                       @Nonnull String estado,
                                       @Nullable LocalDateTime fechaEnvio,
                                       @Nonnull String usuario) {
        if (estado == null || estado.isBlank() || "PENDIENTE".equalsIgnoreCase(estado.trim())) {
            throw new IllegalArgumentException(
                    "El estado es obligatorio y no puede ser PENDIENTE (un histórico pendiente "
                            + "sería reenviado a Hacienda por el scheduler).");
        }
        String xml = xmlContenido == null ? "" : xmlContenido.trim();
        if (xml.isEmpty()) {
            throw new IllegalArgumentException("El XML está vacío");
        }

        JsonNode rootNode;
        try {
            rootNode = new XmlMapper().readTree(xml);
        } catch (IOException e) {
            throw new IllegalArgumentException("XML mal formado: " + e.getMessage(), e);
        }
        if (rootNode == null || !rootNode.fieldNames().hasNext()) {
            throw new IllegalArgumentException("XML sin elemento raíz");
        }
        String documentType = rootNode.fieldNames().next();
        if ("MensajeHacienda".equals(documentType)) {
            throw new IllegalArgumentException(
                    "Los Mensaje Hacienda no se importan como comprobante: se aplican sobre el "
                            + "comprobante ya cargado (endpoint /estado-mensaje).");
        }

        String clave = rootNode.path(documentType).path("Clave").asText();
        if (clave.isEmpty()) {
            clave = rootNode.path("Clave").asText();
        }
        String numeroConsecutivo = parser.extractNumeroConsecutivo(rootNode);
        if (numeroConsecutivo.isEmpty()) {
            throw new IllegalArgumentException("XML inválido: falta el número consecutivo");
        }
        if (clave.isEmpty()) {
            throw new IllegalArgumentException("XML inválido: falta la clave");
        }

        // Idempotencia: el consecutivo ya importado se omite.
        if (encabezadoService.existsByNumeroConsecutivoWithValidComprobante(numeroConsecutivo)) {
            LOG.info("Comprobante histórico ya existente, se omite | user=" + usuario
                    + " | source=HistoricoEmitidosService.importar() | antes=" + numeroConsecutivo
                    + " | despues=" + clave);
            return new Resultado(clave, false, true, "Ya importado (consecutivo " + numeroConsecutivo + ")");
        }
        encabezadoService.cleanDuplicateEncabezados(numeroConsecutivo);

        // --- Construcción del grafo del comprobante -------------------------------
        Encabezado encabezado = new Encabezado();
        DetalleServicio detalles = new DetalleServicio();

        JsonNode emisorNode = rootNode.path(documentType).isMissingNode()
                ? rootNode.path("Emisor") : rootNode.path(documentType).path("Emisor");
        if (emisorNode.isMissingNode() || emisorNode.isEmpty()) {
            emisorNode = rootNode.path("Emisor");
        }
        Emisor emisor = parser.parseEmisor(emisorNode);
        if (emisor == null) {
            throw new IllegalArgumentException("XML inválido: error en datos del emisor");
        }

        JsonNode receptorNode = rootNode.path(documentType).isMissingNode()
                ? rootNode.path("Receptor") : rootNode.path(documentType).path("Receptor");
        if (receptorNode.isMissingNode() || receptorNode.isEmpty()) {
            receptorNode = rootNode.path("Receptor");
        }
        Receptor receptor = parser.parseReceptor(receptorNode);
        if (receptor == null) {
            throw new IllegalArgumentException("XML inválido: error en datos del receptor");
        }

        String codigoActividad = rootNode.path(documentType).path("CodigoActividadEmisor").asText();
        if (codigoActividad.isEmpty()) {
            codigoActividad = rootNode.path("CodigoActividadEmisor").asText();
        }
        String fechaEmisionStr = rootNode.path(documentType).path("FechaEmision").asText();
        if (fechaEmisionStr.isEmpty()) {
            fechaEmisionStr = rootNode.path("FechaEmision").asText();
        }
        LocalDateTime fechaEmision = parser.parseFechaEmision(fechaEmisionStr);
        if (fechaEmision == null) {
            throw new IllegalArgumentException("XML inválido: error en fecha de emisión");
        }

        ResumenFactura resumenFactura = parser.parseResumenFactura(rootNode.path("ResumenFactura"));
        if (resumenFactura == null) {
            throw new IllegalArgumentException("XML inválido: error en resumen de factura");
        }
        List<MedioPago> medioPago = parser.parseMedioPago(rootNode.path("MedioPago"), encabezado);
        if (medioPago == null) {
            throw new IllegalArgumentException("XML inválido: error en medio de pago");
        }
        if (medioPago.isEmpty() && resumenFactura.getMediosPago() != null) {
            // En el XML de Hacienda el MedioPago va DENTRO del ResumenFactura, no
            // en la raiz. El encabezado lo toma de ahi para que el comprobante
            // quede igual que el que crea el flujo de venta.
            for (MedioPagoR mp : resumenFactura.getMediosPago()) {
                if (mp == null || mp.getTipoMedioPago() == null || mp.getTipoMedioPago().isBlank()) {
                    continue;
                }
                MedioPago medio = new MedioPago();
                medio.setMedioPago(mp.getTipoMedioPago());
                medio.setComprobante(encabezado);
                medioPago.add(medio);
            }
        }
        List<LineaDetalle> lineas = parser.parseDetalleServicio(rootNode.path("DetalleServicio"));
        if (lineas == null || lineas.isEmpty()) {
            throw new IllegalArgumentException("XML inválido: no se encontraron líneas de detalle");
        }

        String schemaVersion;
        try {
            schemaVersion = ComprobanteFactory.detectVersion(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            schemaVersion = "4.4";
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = "4.4";
        }

        // V4.4: TotalComprobante debe igualar la suma de TotalMedioPago.
        if ("4.4".equals(schemaVersion)) {
            parser.validarTotalMedioPago(resumenFactura, numeroConsecutivo, xml);
        }

        detalles.setLineasDetalle(lineas);
        lineas.forEach(linea -> linea.setDetalleServicio(detalles));

        encabezado.setCodigoActividadEmisor(codigoActividad);
        encabezado.setNumeroConsecutivo(numeroConsecutivo);
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setCondicionVenta(rootNode.path("CondicionVenta").asText());
        String plazoCredito = rootNode.path("PlazoCredito").asText();
        if (!plazoCredito.isEmpty()) {
            encabezado.setPlazoCredito(plazoCredito);
        }
        String condicionVentaOtros = rootNode.path("CondicionVentaOtros").asText();
        if (!condicionVentaOtros.isEmpty()) {
            encabezado.setCondicionVentaOtros(condicionVentaOtros);
        }
        encabezado.setMedioPago(medioPago);
        encabezado.setClave(clave);
        encabezado.setCodigoDocumento(parser.mapRootElementToDocumentCode(documentType));
        encabezado.setEstado(estado);
        encabezado.setSchemaVersion(schemaVersion);
        resumenFactura.setSchemaVersion(schemaVersion);

        Emisor persistedEmisor = emisorService.createIfNotExist(emisor);
        Receptor persistedReceptor = receptorService.createIfNotExist(receptor);
        if (persistedEmisor == null || persistedReceptor == null) {
            throw new IllegalStateException("No se pudo guardar emisor/receptor del comprobante histórico");
        }
        encabezado.setEmisor(persistedEmisor);
        encabezado.setReceptor(persistedReceptor);

        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalles);
        comprobante.setResumen(resumenFactura);
        comprobante.setUser(usuario);
        comprobante.setStatus(true);
        comprobante.setSchemaVersion(schemaVersion);
        comprobante.setHaciendaClave(clave);
        comprobante.setHaciendaEstado(estado);
        if (fechaEnvio != null) {
            comprobante.setHaciendaFechaEnvio(fechaEnvio);
            comprobante.setHaciendaFechaRespuesta(fechaEnvio);
        }
        ComprobantesEmitidos guardado = comprobantesEmitidosService.createAndReturn(comprobante);
        if (guardado == null) {
            throw new IllegalStateException("No se pudo persistir el comprobante histórico " + clave);
        }

        // El consecutivo histórico debe quedar por debajo del contador actual.
        try {
            elevarContador(numeroConsecutivo);
        } catch (RuntimeException e) {
            LOG.warn("No se pudo elevar el contador de consecutivos | source=HistoricoEmitidosService.importar()"
                    + " | antes=" + numeroConsecutivo + " | despues=" + e.getMessage(), e);
        }

        LOG.info("Comprobante histórico importado | user=" + usuario + " | source=HistoricoEmitidosService.importar()"
                + " | antes=" + clave + " | despues=" + estado);
        return new Resultado(clave, true, false, "Importado (" + estado + ")");
    }

    /**
     * Aplica la respuesta de Hacienda (Mensaje Hacienda) sobre un comprobante
     * emitido ya cargado: actualiza el estado y la fecha de respuesta.
     *
     * @param xmlContenido contenido del Mensaje Hacienda
     * @param usuario      usuario que ejecuta la operación
     * @return la clave del comprobante afectado
     */
    @Transactional
    public @Nonnull String aplicarMensajeHacienda(@Nonnull String xmlContenido, @Nonnull String usuario) {
        String xml = xmlContenido == null ? "" : xmlContenido.trim();
        if (xml.isEmpty()) {
            throw new IllegalArgumentException("El XML está vacío");
        }
        JsonNode rootNode;
        try {
            rootNode = new XmlMapper().readTree(xml);
        } catch (IOException e) {
            throw new IllegalArgumentException("XML mal formado: " + e.getMessage(), e);
        }
        String clave = rootNode.path("Clave").asText();
        if (clave.isEmpty()) {
            throw new IllegalArgumentException("El Mensaje Hacienda no trae Clave");
        }
        String codigo = rootNode.path("Mensaje").asText();
        if (codigo.isEmpty()) {
            codigo = rootNode.path("CodigoMensaje").asText();
        }
        if (codigo.isEmpty()) {
            throw new IllegalArgumentException("El Mensaje Hacienda no trae el código de mensaje");
        }
        String estado;
        switch (codigo.trim()) {
            case "1": estado = "ACEPTADO"; break;
            case "2": estado = "ACEPTACION_PARCIAL"; break;
            case "3": estado = "RECHAZADO"; break;
            default: estado = "DESCONOCIDO"; break;
        }
        List<ComprobantesEmitidos> encontrados = comprobantesEmitidosService.findByClave(clave);
        if (encontrados == null || encontrados.isEmpty()) {
            throw new IllegalStateException(
                    "No hay comprobante cargado con la clave " + clave
                            + " (importa primero el XML del comprobante)");
        }
        for (ComprobantesEmitidos comprobante : encontrados) {
            comprobante.setHaciendaEstado(estado);
            comprobante.setHaciendaFechaRespuesta(LocalDateTime.now());
            if (comprobante.getEncabezado() != null) {
                comprobante.getEncabezado().setEstado(estado);
            }
            comprobantesEmitidosService.update(comprobante);
        }
        LOG.info("Estado de Hacienda aplicado | user=" + usuario
                + " | source=HistoricoEmitidosService.aplicarMensajeHacienda()"
                + " | antes=" + clave + " | despues=" + estado);
        return clave;
    }

    /**
     * Resumen de la carga del histórico: total de comprobantes emitidos y
     * desglose por estado de Hacienda.
     */
    public @Nonnull java.util.Map<String, Object> resumen() {
        List<ComprobantesEmitidos> todos = comprobantesEmitidosService.listAll();
        java.util.Map<String, Long> porEstado = new java.util.TreeMap<>();
        int total = todos == null ? 0 : todos.size();
        if (todos != null) {
            for (ComprobantesEmitidos c : todos) {
                String estado = c.getHaciendaEstado() == null || c.getHaciendaEstado().isBlank()
                        ? "SIN_ESTADO" : c.getHaciendaEstado();
                porEstado.merge(estado, 1L, Long::sum);
            }
        }
        java.util.Map<String, Object> salida = new java.util.LinkedHashMap<>();
        salida.put("total", total);
        salida.put("porEstado", porEstado);
        return salida;
    }

    /**
     * Eleva el contador de consecutivos (sucursal/terminal/tipo) al valor del
     * documento histórico para que la numeración nueva no reutilice números
     * ya usados por el sistema anterior.
     */
    private void elevarContador(@Nonnull String numeroConsecutivo) {
        if (numeroConsecutivo.length() != 20) {
            return;
        }
        String sucursal = numeroConsecutivo.substring(0, 3);
        String terminal = numeroConsecutivo.substring(3, 8);
        String tipo = numeroConsecutivo.substring(8, 10);
        String secuenciaTexto = numeroConsecutivo.substring(10);
        if (!secuenciaTexto.matches("\\d{10}")) {
            return;
        }
        long secuencia = Long.parseLong(secuenciaTexto);
        consecutivoEmitidoService.asegurarSecuencialAlto(sucursal, terminal, tipo, secuencia);
    }
}
