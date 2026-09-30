package Services;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Clientes;
import Models.ComprobantesEmitidos;
import Models.Detalles.CodigoComercial;
import Models.Detalles.Descuento;
import Models.Detalles.DetalleServicio;
import Models.Detalles.DetalleSurtido;
import Models.Detalles.Exoneracion;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Detalles.LineaDetalleSurtido;
import Models.ProductoExoneracion;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.IdentificacionEmisor;
import Models.Encabezado.IdentificacionReceptor;
import Models.Encabezado.MedioPago;
import Models.Encabezado.Receptor;
import Models.Encabezado.Telefono;
import Models.Encabezado.Ubicacion;
import Models.Resumen.MedioPagoR;
import Models.Resumen.ResumenFactura;
import Models.Resumen.TotalDesgloseImpuesto;
import Models.Resumen.CodigoTipoMoneda;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Enums.Tipo_CondicionVenta;
import Models.Enums.Tipo_MedioPago;
import Models.Enums.Tipo_Codigo_Descuento;
import Models.Enums.Tipo_TarifaIVA;
import Models.EntradaPago;
import Models.ConfiguracionAplicacion;
import Models.EnvioFueraLinea;
import Models.Articulos.Promocion;
import Models.Usuarios;
import Services.Facturas.EncabezadoService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.ResumenFacturaService;
import Services.Facturas.EmisorService;
import Services.Facturas.ReceptorService;
import Services.Facturas.DescuentoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import Services.LoyaltyService;
import Services.CarritoService;
import Models.PuntosTransaccion;
import Utils.CarritoCalculations;
import Utils.PDFGenerator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.io.Serializable;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import Services.EmailService;
import Services.AppSettingsService;
import Services.ConsecutivoEmitidoService;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import Models.ConfiguracionAplicacion;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Clientes;
import Models.Usuarios;

@Named("comprobanteService")
@ApplicationScoped
public class ComprobanteService implements Serializable {

    private static final Logger LOG = Logger.getLogger(ComprobanteService.class);

    /**
     * Patrón exacto de NumeroCedulaReceptor en MensajeReceptor_V4.4.xsd: \d{9,12}
     * (solo dígitos). El XSD oficial v4.4 mantiene esta restricción aunque Clave ya sea
     * [a-zA-Z0-9]{50,50} y el Registro Nacional comience a emitir identificadores
     * alfanuméricos para personas jurídicas (p.ej. 3-101-A00001).
     */
    private static final java.util.regex.Pattern CEDULA_MENSAJE_RECEPTOR =
        java.util.regex.Pattern.compile("\\d{9,12}");

    @Inject
    private @Nonnull HaciendaServiceFacade haciendaServiceFacade;

    @Inject
    private @Nonnull EncabezadoService encabezadoService;
    @Inject
    private @Nonnull DetalleServicioService detallesService;
    @Inject
    private @Nonnull ResumenFacturaService resumenService;
    @Inject
    private @Nonnull EmisorService emisorService;
    @Inject
    private @Nonnull ReceptorService receptorService;
    @Inject
    private @Nonnull DescuentoService descuentoService;
    @Inject
    private @Nonnull ImpuestoService impuestoService;
    @Inject
    private @Nonnull LineaDetalleService lineaService;
    @Inject
    private @Nonnull LoyaltyService loyaltyService;

    @Inject
    private @Nonnull HaciendaSigner haciendaSigner;
    
    @Inject
    private @Nonnull ComprobantesEmitidosService comprobantesEmitidosService;

    @Inject
    private @Nonnull EmailService emailService;

    @Inject
    private @Nonnull PDFGenerator pdfGenerator;

    @Inject
    private @Nonnull AppSettingsService appSettingsService;

    @Inject
    private @Nonnull DocumentoStrategyFactory strategyFactory;

    @Inject
    private @Nonnull ConsecutivoEmitidoService consecutivoEmitidoService;

    @Inject
    private @Nonnull ProductoExoneracionService productoExoneracionService;

    @Inject
    private @Nonnull SustitucionComprobanteService sustitucionComprobanteService;

    @Inject
    private @Nonnull EnvioFueraLineaService envioFueraLineaService;

    @Inject
    private @Nonnull CarritoService carritoService;

    /**
     * Thrown when the comprobante could not be assembled and persisted.
     *
     * <p>Exists so {@link #crearComprobante} can roll back instead of
     * returning {@code null} from inside its own {@code @Transactional}.</p>
     *
     * <p>Why it matters: a {@code @Transactional} method that RETURNS after a
     * failure COMMITS whatever it already wrote. The old
     * {@code catch (RuntimeException) { return null; }} therefore persisted
     * {@code encabezado} + {@code detalles} + {@code resumen} and left no
     * {@code comprobantes_emitidos} row — an orphaned half-invoice that
     * {@code PosResource} reported as HTTP 500, and that every retry
     * multiplied. Rethrowing makes the rollback atomic; the caller still sees
     * the same 500 envelope, so the HTTP contract is unchanged.</p>
     */
    public static class ComprobanteNoCreadoException extends RuntimeException {
        public ComprobanteNoCreadoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when the loyalty-points redemption could not be applied.
     *
     * <p>Exists because the points discount is money the customer already
     * stopped paying at the register: the sale is only legitimate once the
     * debit exists. The redemption therefore runs INSIDE
     * {@link #crearComprobante}'s transaction, before the first write, so this
     * exception rolls back encabezado + detalles + resumen together with the
     * debit attempt. No invoice is created, the POS keeps the cart (no
     * idempotency stamp) and the cashier can retry or re-send the sale without
     * the discount.</p>
     *
     * <p>It is a distinct type (not a {@link ComprobanteNoCreadoException})
     * because the caller must tell the operator WHICH step failed: "the loyalty
     * subsystem would not debit" is a different action than "the invoice could
     * not be built".</p>
     */
    public static class PuntosNoCanjeadosException extends RuntimeException {
        public PuntosNoCanjeadosException(String message) {
            super(message);
        }

        public PuntosNoCanjeadosException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class CrearComprobanteResult {
        public ComprobantesEmitidos comprobante;
        public boolean haciendaEnviado;
        public String haciendaMensaje;
        /**
         * DOCUMENTED WIDENING (additive): false when the sale was invoiced and
         * charged but the earned points could NOT be credited, so the customer
         * is owed them. It can never be false for a rolled-back sale, because a
         * failed redemption aborts the whole transaction instead. The caller
         * MUST surface it: a swallowed {@code earnPoints} is exactly how earned
         * points used to vanish without a trace.
         */
        public boolean puntosOtorgados = true;
        /**
         * Human-readable reason plus the {@code FACT-<consecutivo>} reference
         * needed to credit the points by hand; null when nothing failed.
         */
        public String puntosMensaje;
        /**
         * DOCUMENTED WIDENING (additive, source-compatible): true when the
         * document was signed but not transmitted and now lives in the
         * {@link Models.EnvioFueraLinea} outbox awaiting automatic transmission
         * under Art. 21 ¶3. It separates "not submitted, will be retried" from
         * "submitted and rejected" for API callers; {@link #haciendaEnviado}
         * keeps its original meaning and stays false either way.
         */
        public boolean pendienteEnvio;
        /**
         * DOCUMENTED WIDENING (additive): lo que la fase (b)
         * ({@link #enviarComprobanteCreado}) necesita del armado para decidir y
         * para reencolar — tipo de documento, sucursal, terminal, la situacion
         * ya incrustada en la clave y el veredicto del sondeo de conectividad.
         * Viaja en el resultado porque son datos de SOLO lectura que la fase (a)
         * ya calculó dentro de la transacción y que no se pueden volver a pedir
         * afuera sin recomputar la clave. Es null únicamente si el documento no
         * llegó a persistirse.
         */
        public EnvioPendiente envio;
    }

    /**
     * Contexto de la fase de envío, capturado durante la fase (a).
     *
     * <p>Es un simple contenedor de datos: ni entidad ni efectos. La clave ya
     * está escrita con la {@code situacion} que se capturó aquí, así que la
     * fase (b) no puede recalcularla — solo puede usarla.</p>
     */
    public static class EnvioPendiente {
        /** "01"/"04"/… tal como se emitió. */
        public String tipoDocumento;
        /** 3 dígitos, como se imprimió en el consecutivo. */
        public String sucursal;
        /** 5 dígitos, como se imprimió en el consecutivo. */
        public String terminal;
        /** {@link EnvioFueraLineaService#SITUACION_NORMAL} o {@code SITUACION_FUERA_LINEA}. */
        public String situacion;
        /** Veredicto del sondeo, ya tomado dentro de la fase (a). */
        public boolean hayConectividad;
    }

    /**
     * FASE (a) — arma y CONFIRMA la factura, y nada más.
     *
     * <p><b>El punto del cambio: el bloqueo pesimista del consecutivo dura
     * únicamente esta fase.</b> El consecutivo se numera con
     * {@link jakarta.persistence.LockModeType#PESSIMISTIC_WRITE} sobre la fila
     * de {@code ConsecutivoEmitido}, así que el bloqueo dura lo que dure la
     * transacción que lo pide. Antes esta transacción incluía además el envío
     * HTTPS a Hacienda con su sondeo de estado (hasta
     * {@code mercatus.hacienda.poll.intentos} × {@code poll.intervalo-ms} de
     * esperas), de modo que <b>cada venta concurrente serializaba detrás de la
     * E/S de red de la anterior, con la fila del consecutivo bloqueada</b> y un
     * punto de conexión del pool tomado durante todo ese tiempo. Con el pool
     * en 20, veinte ventas simultáneas bastaban para agotarlo y dejar el POS
     * sin conexiones.
     *
     * <p>Ahora el envío es la fase (b) ({@link #enviarComprobanteCreado}), que
     * corre DESPUÉS de este commit, sin transacción: cada venta libera el
     * consecutivo al confirmarse y habla con Hacienda por su cuenta. La factura
     * queda escrita antes de hablar con Hacienda, que es el orden correcto:
     * el documento es un hecho local y la comunicación a Hacienda es un trámite
     * posterior con cola de reintento propia
     * ({@link EnvioFueraLineaService}, Art. 21 ¶3).</p>
     *
     * <p><b>Lo que sigue dentro de esta fase, en el mismo orden y por las
     * mismas razones:</b></p>
     * <ul>
     *   <li>{@code carritoService.ajustarInventario} y el canje de puntos, antes
     *       de la primera escritura, para que un fallo en cualquiera revierta
     *       stock + puntos + comprobante juntos y el reintento arranque limpio
     *       (ver {@link PuntosNoCanjeadosException}).</li>
     *   <li>el sondeo de conectividad —que NO puede salir de aquí: su veredicto
     *       decide la {@code situacion}, y la situacion va incrustada en la
     *       posición 42 de la clave. Es un connect() de 2,5 s como máximo
     *       cacheado 60 s
     *       ({@link EnvioFueraLineaService#hayConectividadConHacienda()}), así
     *       que no se paga por venta; lo caro —el envío y el sondeo de
     *       estado— es lo que sí salió.</li>
     *   <li>el otorgamiento de puntos ganados, porque es una escritura en el
     *       mismo libro que el canje: atómica con la factura en el mismo commit
     *       o, si falla, declarada en el resultado
     *       ({@code puntosOtorgados=false}) para que el operador la sepa. Su
     *       fallo nunca relanza, así que la semántica observable es idéntica a
     *       cuando corría después del envío.</li>
     * </ul>
     *
     * <p>El envío NO ocurre aquí, ni por éxito ni por excepción: quien lo pide
     * es {@link Controllers.Api.App.PosResource}, que estampa la
     * idempotencia entre una fase y otra y así no pierde la venta si el proceso
     * muere durante la E/S de Hacienda.</p>
     *
     * @see #enviarComprobanteCreado(CrearComprobanteResult)
     */
    @jakarta.transaction.Transactional
    public @Nullable CrearComprobanteResult crearComprobante(@Nonnull ConfiguracionAplicacion appSettings, @Nonnull List<ArticuloCarrito> carrito,
                                                    @Nullable Clientes selectedClient, @Nullable Clientes cliente, @Nonnull Usuarios currentUser,
                                                    @Nonnull DocumentoStrategy strategy, @Nonnull List<EntradaPago> pagos,
                                                    @Nullable BigDecimal puntosARedimir) {
        CrearComprobanteResult result = new CrearComprobanteResult();
        result.haciendaEnviado = false;
        result.pendienteEnvio = false;
        
        try {
            // ── Ajuste de inventario (PRIMERO, dentro de la transacción) ────
            // El stock se decrementaba en PosResource ANTES de llamar aquí, en
            // su propia transacción ya confirmada: si el canje de puntos o la
            // factura fallaban después, el reintento volvía a decrementar y la
            // venta quedaba con doble rebaja de inventario sin factura. Dentro
            // de ESTA transacción, cualquier fallo revierte stock + puntos +
            // comprobante juntos, y el reintento arranca limpio. Se conserva el
            // orden original (stock, luego canje, luego factura).
            carritoService.ajustarInventario(carrito, currentUser);

            // ── Canje de puntos (ANTES de la primera escritura) ─────────────
            // El descuento por puntos ya se aplicó al monto a pagar en el POS,
            // así que la venta solo es legítima si el débito existe. Va dentro
            // de ESTA transacción y antes de encabezado/detalles/resumen por
            // dos razones que no se pueden conseguir canjeando desde afuera:
            //  · si falla, la excepción revierte también el comprobante a medio
            //    escribir -> nunca queda una factura cobrada cuyo descuento no
            //    fue debitado (nunca cobrar sin canjear);
            //  · al ser atómico con la factura, el reintento del POS que no
            //    encuentra el sello de idempotencia vuelve a debitar lo mismo
            //    UNA sola vez (nunca debitar dos veces la misma venta).
            // El canje no necesita el id de la factura, así que no hay ningún
            // motivo para hacerlo después del commit.
            BigDecimal puntosACanjean = puntosARedimir == null
                    ? BigDecimal.ZERO : puntosARedimir.max(BigDecimal.ZERO);
            if (puntosACanjean.compareTo(BigDecimal.ZERO) > 0) {
                canjearPuntos(selectedClient, puntosACanjean);
            }

            String tipoDocumento = strategy.getCodigoDocumento();
            String sucursal = String.format("%03d", Integer.parseInt(
                appSettings.getCodigoSucursal() != null ? appSettings.getCodigoSucursal() : "001"));
            String terminal = String.format("%05d", Integer.parseInt(
                appSettings.getCodigoTerminal() != null ? appSettings.getCodigoTerminal() : "001"));
            long consecutivo = consecutivoEmitidoService.getNextSequential(sucursal, terminal, tipoDocumento);
            String numeroConsecutivo = String.format("%s%s%s%010d",
                sucursal, terminal,
                tipoDocumento != null ? tipoDocumento : "04",
                consecutivo);

            // Art. 21 ¶3 (Ley 6828): si el XML firmado no puede enviarse por
            // falta de conectividad, debe generarse y firmarse en el momento de
            // la venta y enviarse a más tardar dos días hábiles después, con
            // Situacion = 3. La situacion va incrustada en la clave (posición 42),
            // así que hay que decidirla ANTES de generar la clave: por eso el
            // sondeo de conectividad va aquí y no después del envío.
            // El sondeo falla abierto (devuelve true ante cualquier error), de modo
            // que un falso negativo no degrada la ruta normal a situacion 3.
            boolean hayConectividad = envioFueraLineaService.hayConectividadConHacienda();
            String situacion = hayConectividad
                ? EnvioFueraLineaService.SITUACION_NORMAL
                : EnvioFueraLineaService.SITUACION_FUERA_LINEA;

            // Use strategy to build the type-specific encabezado
            Encabezado encabezado = strategy.buildEncabezado(appSettings, selectedClient);
            encabezado.setNumeroConsecutivo(numeroConsecutivo);

            List<MedioPago> medioPagoList = new ArrayList<>();
            for (EntradaPago entry : pagos) {
                if (entry.getMonto() == null || entry.getMonto().compareTo(BigDecimal.ZERO) <= 0) continue;
                MedioPago medio = new MedioPago();
                medio.setMedioPago(entry.getMetodoPago());
                medio.setComprobante(encabezado);
                medioPagoList.add(medio);
            }
            if (medioPagoList.isEmpty()) {
                MedioPago medio = new MedioPago();
                medio.setMedioPago("01");
                medio.setComprobante(encabezado);
                medioPagoList.add(medio);
            }
            encabezado.setMedioPago(medioPagoList);
            
            // Generate the Hacienda document key (50-character clave with check digit)
            String clave = haciendaSigner.generateInvoiceKey(
                appSettings.getIdentificacion(),
                numeroConsecutivo,
                situacion,
                encabezado.getFechaEmision().toLocalDate()
            );
            encabezado.setClave(clave);
            
            encabezadoService.create(encabezado);
            DetalleServicio detalles = detallesComprobante(carrito, tipoDocumento);

            // REP V4.4: DetalleServicio is MANDATORY (minOccurs="1")
            if ("10".equals(tipoDocumento) && (detalles == null
                || detalles.getLineasDetalle() == null || detalles.getLineasDetalle().isEmpty())) {
                throw new IllegalArgumentException(
                    "REP requiere al menos una línea de detalle (DetalleServicio es obligatorio)"
                );
            }

            detallesService.create(detalles);
            ResumenFactura resumen = resumenComprobante(carrito);

            BigDecimal totalOtrosCargos = calcularTotalOtrosCargos(detalles);
            resumen.setTotalOtrosCargos(totalOtrosCargos);

            BigDecimal totalIVADevuelto = calcularTotalIVADevuelto(carrito, pagos);
            resumen.setTotalIVADevuelto(totalIVADevuelto);

            BigDecimal totalComprobante = resumen.getTotalVentaNeta()
                    .add(resumen.getTotalImpuesto())
                    .add(totalOtrosCargos)
                    .subtract(totalIVADevuelto);
            resumen.setTotalComprobante(totalComprobante);

            List<MedioPagoR> mediosPagoResumen = new ArrayList<>();
            BigDecimal sumaPagos = BigDecimal.ZERO;
            int pagoCount = 0;
            for (EntradaPago entry : pagos) {
                if (entry.getMonto() == null || entry.getMonto().compareTo(BigDecimal.ZERO) <= 0) continue;
                MedioPagoR medioR = new MedioPagoR();
                medioR.setTipoMedioPago(entry.getMetodoPago());
                medioR.setTotalMedioPago(entry.getMonto());
                medioR.setResumenFactura(resumen);
                mediosPagoResumen.add(medioR);
                sumaPagos = sumaPagos.add(entry.getMonto());
                pagoCount++;
            }
            String schemaVersion = resumen.getSchemaVersion();
            if (schemaVersion == null || schemaVersion.isBlank()) {
                if (encabezado != null && encabezado.getSchemaVersion() != null && !encabezado.getSchemaVersion().isBlank()) {
                    schemaVersion = encabezado.getSchemaVersion();
                } else {
                    schemaVersion = "4.4";
                }
            }
            resumen.setSchemaVersion(schemaVersion);
            // Hacienda v4.4 requires sum of TotalMedioPago == TotalComprobante
            if ("4.4".equals(schemaVersion)) {
                if (pagoCount > 0 && sumaPagos.compareTo(totalComprobante) != 0) {
                    // Adjust last entry to match total — prevents rounding mismatch
                    MedioPagoR last = mediosPagoResumen.get(pagoCount - 1);
                    last.setTotalMedioPago(last.getTotalMedioPago().add(totalComprobante.subtract(sumaPagos)));
                }
            }
            if (mediosPagoResumen.isEmpty()) {
                MedioPagoR medioR = new MedioPagoR();
                medioR.setTipoMedioPago("01");
                medioR.setTotalMedioPago(totalComprobante);
                medioR.setResumenFactura(resumen);
                mediosPagoResumen.add(medioR);
            }
            resumen.setMediosPago(mediosPagoResumen);

            // V4.4 Bitácora item 124/125: TotalComprobante must equal sum of TotalMedioPago
            if ("4.4".equals(schemaVersion)) {
                validarTotalMedioPago(resumen, numeroConsecutivo);
            }

            resumenService.create(resumen);
            
            ComprobantesEmitidos tiqueteElectronico = new ComprobantesEmitidos();
            tiqueteElectronico.setEncabezado(encabezado);
            tiqueteElectronico.setDetalles(detalles);
            tiqueteElectronico.setResumen(resumen);
            tiqueteElectronico.setUser(currentUser.getUsername());
            tiqueteElectronico.setStatus(true);
            tiqueteElectronico.setHaciendaClave(clave);
            tiqueteElectronico.setHaciendaEstado("PENDIENTE");
            encabezado.setEstado("PENDIENTE");
            
            result.comprobante = tiqueteElectronico;
            
            // Persist the comprobante first
            comprobantesEmitidosService.createAndReturn(tiqueteElectronico);

            // ── Fin de la fase (a): el documento está escrito ────────────────
            // A partir de acá NO se habla con Hacienda. Se deja capturado lo
            // que la fase (b) necesita para decidir y para reencolar, y se
            // devuelve. El bloqueo pesimista del consecutivo se libera con el
            // commit de esta transacción.
            EnvioPendiente envio = new EnvioPendiente();
            envio.tipoDocumento = tipoDocumento;
            envio.sucursal = sucursal;
            envio.terminal = terminal;
            envio.situacion = situacion;
            envio.hayConectividad = hayConectividad;
            result.envio = envio;
            // Los tres campos de estado del resultado los llena la fase (b); acá
            // arrancan en su valor neutro, idéntico al de antes del envío.
            result.haciendaEnviado = false;
            result.pendienteEnvio = false;
            result.haciendaMensaje = null;

            // ── Otorgar puntos de lealtad ─────────────────────────────────────
            // Aquí el camino es el inverso al canje y por eso NO se relanza:
            // el comprobante ya está escrito y firmado, el cliente ya pagó y la
            // fase (b) puede haberlo comunicado ya a Hacienda; revertir dejaría
            // una venta cobrada sin factura —o un documento comunicado que la
            // base ya no refleja—, que es peor que un punto faltante. Pero
            // tampoco puede quedar en un LOG.warn, porque eso es exactamente
            // como los puntos ganados desaparecían sin que nadie se enterara.
            // Por eso el fallo se DECLARA en el resultado (puntosOtorgados=false
            // + puntosMensaje con la referencia para acreditarlos a mano) y
            // PosResource lo devuelve al operador.
            if (selectedClient != null && currentUser != null) {
                BigDecimal totalAmount = resumen.getTotalVentaNeta();
                String facturaReferencia = "FACT-" + consecutivo;

                try {
                    loyaltyService.earnPoints(selectedClient, totalAmount, facturaReferencia, currentUser);
                } catch (RuntimeException e) {
                    result.puntosOtorgados = false;
                    result.puntosMensaje = "La venta se cobró y facturó, pero NO se otorgaron los puntos "
                            + "de lealtad al cliente. Acréditelos a mano con la referencia "
                            + facturaReferencia + " (total de la venta: " + totalAmount + ").";
                    LOG.error("Error al agregar puntos de lealtad: " + e.getMessage()
                            + " | cliente=" + selectedClient.getCode()
                            + " | referencia=" + facturaReferencia
                            + " | monto=" + totalAmount
                            + " | source=crearComprobante()"
                            + " | despues=la factura queda vigente; el operador debe acreditar los puntos", e);
                }
            }
            
            return result;
        } catch (PuntosNoCanjeadosException e) {
            // El canje falló y la transacción se revierte completa (ver el
            // javadoc de la excepción). Se relanza SIN envolver para que el
            // llamador lo distinga de un fallo de armado del comprobante y
            // pueda devolverle al operador el envelope PUNTOS_NO_CANJEADOS.
            throw e;
        } catch (ComprobanteNoCreadoException e) {
            // Already the signal we want; rethrow untouched so the transaction
            // rolls back and nothing double-wraps.
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Error al crear comprobante: " + e.getMessage() + " | source=crearComprobante() | despues=" + e.getMessage());
            LOG.warn("Error: " + e.getLocalizedMessage() + " | source=crearComprobante() | despues=" + e.getMessage());
            // Rethrow, do NOT return null: this method is @Transactional, and a
            // normal return COMMITS. Returning here left encabezado + detalles +
            // resumen persisted with no comprobantes_emitidos row (an orphaned
            // half-invoice) while the caller reported HTTP 500, and each retry
            // created another one. The caller catches this and maps it back to
            // the same COMPROBANTE_ERROR 500 it produced before.
            throw new ComprobanteNoCreadoException(
                    "No se pudo crear el comprobante: " + e.getMessage(), e);
        }

    }

    /**
     * FASE (b) — comunica a Hacienda un comprobante YA CONFIRMADO, fuera de
     * cualquier transacción.
     *
     * <p>Se llama después del commit de {@link #crearComprobante}, y esa es
     * toda la razón de existir: el envío (HTTPS + sondeo de estado) ocurre
     * fuera de la transacción que retiene el bloqueo pesimista del consecutivo,
     * así las ventas concurrentes no se serializan detrás de la E/S de red de
     * la anterior. Los tres campos de estado del resultado
     * ({@code haciendaEnviado}, {@code pendienteEnvio}, {@code haciendaMensaje})
     * los rellena ESTE método, con los mismos valores y los mismos dos finales
     * que producía cuando el envío vivía dentro del armado.</p>
     *
     * <p><b>Idempotencia y reintento.</b> Es idempotente respecto del POS: la
     * fila que sale de la fase (a) es la única, y si este envío falla, el XML
     * firmado queda en la bandeja de {@link EnvioFueraLineaService} con su
     * vencimiento de Art. 21 ¶3, y el documento queda en el estado que
     * corresponde —PENDIENTE tras un fallo de transporte, RECHAZADO con su
     * motivo tras un rechazo de fondo—, que es exactamente el conjunto que
     * barren el lote de 48 h y esa bandeja. El POS ya estampó su idempotency
     * stamp antes de llamar acá, así que un reintento del cajero nunca vuelve
     * a entrar por la fase (a).</p>
     *
     * <p><b>No relanza nunca.</b> La factura ya está cobrada y escrita: una
     * excepción acá no puede deshacerla, y convertirla en error HTTP haría que
     * el cajero creyera que no se vendió algo que sí se vendió. Se registra y
     * el documento queda pendiente.</p>
     *
     * <p>Transaccionalidad: cada sello de estado
     * (ENVIADO → ACEPTADO/RECHAZADO/PENDIENTE) lo escribe
     * {@link ComprobantesEmitidosService#update}, que es {@code @Transactional}
     * y sin transacción ambiente abre la suya propia. Son transacciones cortas,
     * una por sello, y ninguna contiene E/S de red.</p>
     *
     * <p>El camino Art. 21 ¶3 se conserva tal cual: si el sondeo de la fase (a)
     * fue negativo, el comprobante ya viene firmado con {@code situacion 3} y
     * no se intenta el envío inmediato —se encola; si el envío inmediato falla,
     * también se encola, para no dejar un documento fuera del plazo legal.</p>
     *
     * @param result el resultado devuelto por {@link #crearComprobante}
     */
    public void enviarComprobanteCreado(@Nonnull CrearComprobanteResult result) {
        if (result == null || result.comprobante == null || result.envio == null) {
            // Nada que enviar: o no se armó comprobante, o el resultado no viene
            // de la fase (a). No es un error del POS, así que no se propaga.
            return;
        }
        EnvioPendiente envio = result.envio;
        ComprobantesEmitidos comprobante = result.comprobante;
        try {
            if (!envio.hayConectividad) {
                // Hacienda inalcanzable en el momento de la venta: el documento
                // ya está firmado con situacion 3 y se transmite dentro de dos
                // días hábiles (Art. 21 párr. 3). No se intenta el envío.
                EnvioFueraLinea encolado = registrarPendienteDeEnvio(
                    comprobante, envio.tipoDocumento, envio.sucursal, envio.terminal, envio.situacion,
                    EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3);
                result.haciendaEnviado = false;
                result.pendienteEnvio = encolado != null;
                result.haciendaMensaje = encolado != null
                    ? "Comprobante creado y firmado con situacion 3 (sin conexión a Hacienda). "
                        + "Se enviará automáticamente antes del " + encolado.getVencimiento()
                        + " (Art. 21 párr. 3, Ley 6828)."
                    : "Comprobante creado y firmado con situacion 3, pero NO se pudo encolar para envío. "
                        + "Requiere envío manual a Hacienda antes de dos días hábiles (Art. 21 párr. 3).";
                return;
            }
            result.haciendaEnviado = enviarComprobanteAHacienda(comprobante);
            if (result.haciendaEnviado) {
                result.haciendaMensaje = "Comprobante creado y enviado a Hacienda";
                return;
            }
            // El envío inmediato no prosperó (rechazo, fallo de transporte o
            // preflight). El XML firmado se encola igual para que el documento
            // no se pierda: antes de este cambio quedaba en haciendaEstado
            // 'ENVIADO' sin estarlo, un falso éxito que además sacaba la fila de
            // los lotes de 48 h.
            EnvioFueraLinea encolado = registrarPendienteDeEnvio(
                comprobante, envio.tipoDocumento, envio.sucursal, envio.terminal, envio.situacion,
                EnvioFueraLineaService.ORIGEN_FALLO_ENVIO_INMEDIATO);
            result.pendienteEnvio = encolado != null;
            result.haciendaMensaje = encolado != null
                ? "Comprobante creado pero NO enviado a Hacienda. Se reintentará automáticamente "
                    + "antes del " + encolado.getVencimiento() + " (Art. 21 párr. 3, Ley 6828)."
                : "Comprobante creado pero NO enviado a Hacienda y sin cola de reintento. "
                    + "Requiere envío manual (Art. 21 párr. 3, Ley 6828).";
        } catch (RuntimeException e) {
            // La venta está cobrada y confirmada: no hay nada que deshacer acá.
            // Se deja constancia y el documento vuelve a PENDIENTE para el lote
            // de 48 h / la bandeja de Art. 21 párr. 3.
            result.haciendaEnviado = false;
            result.haciendaMensaje = "Comprobante creado pero NO enviado a Hacienda: " + e.getMessage()
                + ". Queda pendiente de reintento automático (Art. 21 párr. 3, Ley 6828).";
            LOG.error("Error en la fase de envío del comprobante: " + e.getMessage()
                + " | source=ComprobanteService.enviarComprobanteCreado()"
                + " | despues=la factura ya está confirmada; el documento queda pendiente de reintento", e);
        }
    }

    /**
     * Debita los puntos del cliente antes de que exista el comprobante.
     *
     * <p>Se ejecuta dentro de la transacción de
     * {@link #crearComprobante}, así que cualquier fallo revierte el canje Y
     * el comprobante a la vez: nunca queda una factura cuyo descuento no fue
     * debitado. Un fallo del subsistema de lealtad tampoco se traga — se
     * convierte en {@link PuntosNoCanjeadosException} para que el POS la
     * muestre y conserve el carrito.</p>
     *
     * <p>Un canje parcial también es un fallo: {@code redeemPoints} devuelve
     * {@link BigDecimal#ZERO} en vez de lanzar cuando el saldo no alcanza, y un
     * cero silencioso con descuento aplicado en caja es exactamente la pérdida
     * de puntos que este método existe para impedir.</p>
     */
    private void canjearPuntos(@Nullable Clientes cliente, @Nonnull BigDecimal puntos) {
        if (cliente == null) {
            throw new PuntosNoCanjeadosException(
                    "No hay cliente seleccionado para canjear " + puntos.toPlainString() + " puntos.");
        }
        BigDecimal canjeados;
        try {
            canjeados = loyaltyService.redeemPoints(cliente, puntos);
        } catch (RuntimeException e) {
            // LOG.error y no warn: acá todavía no hay una venta cobrada que
            // preservar, y el reintento del POS vuelve a intentar el canje,
            // pero el operador tiene que saber que el subsistema de lealtad
            // falló.
            LOG.error("No se pudo canjear los puntos de lealtad: " + e.getMessage()
                    + " | cliente=" + cliente.getCode()
                    + " | puntos=" + puntos.toPlainString()
                    + " | source=crearComprobante()"
                    + " | despues=la transacción se revierte; no hay comprobante y el carrito queda intacto", e);
            throw new PuntosNoCanjeadosException(
                    "No se pudo canjear los puntos de lealtad: " + e.getMessage(), e);
        }
        if (canjeados == null || canjeados.compareTo(puntos) != 0) {
            LOG.error("Canje de puntos incompleto: se pidieron " + puntos.toPlainString()
                    + " y se debitaron " + canjeados
                    + " | cliente=" + cliente.getCode()
                    + " | source=crearComprobante()"
                    + " | despues=la transacción se revierte; no hay comprobante y el carrito queda intacto");
            throw new PuntosNoCanjeadosException(
                    "El cliente no tiene saldo suficiente para canjear " + puntos.toPlainString() + " puntos.");
        }
    }

    /**
     * Sends a comprobante to Hacienda and returns whether it was accepted.
     * <p>
     * On success the estado is updated to ACEPTADO and the method returns true.
     * <p>
     * On failure the document is put back in PENDIENTE — not ENVIADO. The
     * pre-submission "ENVIADO" stamp is optimistic bookkeeping for the async
     * Hacienda response; leaving it behind when the send did not happen is a
     * false success, and it also removed the row from
     * {@code findFacturasPendientesEnvio()} (which filters on 'PENDIENTE'), so
     * neither the 48h batch nor any operator would pick it up again. The caller
     * additionally enqueues the signed payload in
     * {@link Models.EnvioFueraLinea} so the Art. 21 ¶3 two-business-day window
     * is tracked explicitly.
     */
    public boolean enviarComprobanteAHacienda(ComprobantesEmitidos comprobante) {
        // Preflight FUERA del try, igual que el de la cédula del Mensaje Receptor:
        // un documento con el bloque de referencia incompleto no puede ir a
        // Hacienda (nota 9 del XSD v4.4, códigos 13/15 obligatorios desde el
        // 2026-11-01) y es preferible que el operador reciba la causa. El
        // comprobante queda como estaba (no se envía) y se avisa por log.
        try {
            sustitucionComprobanteService.validarParejaAntesDeEnvio(comprobante);
        } catch (IllegalArgumentException e) {
            LOG.warn("Envío bloqueado: " + e.getMessage()
                + " | source=ComprobanteService.enviarComprobanteAHacienda()"
                + " | despues=comprobante no enviado, queda pendiente de regularizar");
            return false;
        }
        try {
            ConfiguracionAplicacion appSettings = appSettingsService.returnCurrent();
            if (appSettings == null) {
                LOG.warn("No hay configuracion de Hacienda para enviar comprobante"
                    + " | source=ComprobanteService.enviarComprobanteAHacienda()");
                return false;
            }

            String clave = comprobante.getHaciendaClave();
            if (clave == null || clave.isEmpty()) {
                LOG.warn("Comprobante sin clave de Hacienda"
                    + " | source=ComprobanteService.enviarComprobanteAHacienda()");
                return false;
            }

            // ── Route through HaciendaServiceFacade ────────────────────────
            // The facade checks ConfiguracionAplicacion.useFides and chooses the active provider:
            //   Fides API   → FidesApiService (auth → create → sign → submit → poll)
            //   Direct Hacienda → XML build → sign → HaciendaApiService.submitAndWait
            // ──────────────────────────────────────────────────────────────

            String provider = haciendaServiceFacade.isFidesEnabled() ? "Fides" : "Hacienda directa";
            LOG.info("Enviando comprobante " + clave + " via " + provider
                + " | source=ComprobanteService.enviarComprobanteAHacienda()");

            comprobante.setHaciendaEstado("ENVIADO");
            if (comprobante.getEncabezado() != null) {
                comprobante.getEncabezado().setEstado("ENVIADO");
            }
            comprobantesEmitidosService.update(comprobante);

            HaciendaServiceFacade.SubmitResult result = haciendaServiceFacade.submitDocument(comprobante);

            if (result.success) {
                comprobante.setHaciendaEstado("ACEPTADO");
                comprobante.setHaciendaFechaEnvio(LocalDateTime.now());
                comprobante.setHaciendaFechaRespuesta(LocalDateTime.now());
                if (comprobante.getEncabezado() != null) {
                    comprobante.getEncabezado().setEstado("ACEPTADO");
                }
                comprobantesEmitidosService.update(comprobante);
                LOG.info("Comprobante " + (comprobante.getEncabezado() != null ? comprobante.getEncabezado().getNumeroConsecutivo() : clave) + " aceptado por Hacienda"
                    + " | source=ComprobanteService.enviarComprobanteAHacienda()");
                return true;
            } else {
                // Un rechazo de Hacienda (validación de fondo) no se reintenta solo:
                // se conserva el motivo y el estado RECHAZADO para que el operador lo
                // regule. Un fallo de conectividad, en cambio, sí se reencola (lo
                // hace el llamador de esta fase de envío con el XML firmado).
                if (comprobante.getEncabezado() != null) {
                    comprobante.getEncabezado().setEstado("RECHAZADO");
                    comprobante.getEncabezado().setMotivoRechazo(result.errorMessage);
                }
                comprobantesEmitidosService.update(comprobante);
                LOG.info("Hacienda rechazo comprobante: " + result.errorMessage
                    + " | source=ComprobanteService.enviarComprobanteAHacienda()"
                    + " | despues=" + result.errorMessage);
                return false;
            }
        } catch (RuntimeException e) {
            // Excepción de transporte/firma: el documento NO llegó a enviarse, así que
            // se revierte el sello optimista de ENVIADO a PENDIENTE. Con ENVIADO la
            // fila desaparecía de findFacturasPendientesEnvio() y nadie la volvía a
            // intentar. El reencolado con el XML firmado lo hace el llamador.
            degradarEnvioAPendiente(comprobante);
            comprobantesEmitidosService.update(comprobante);
            LOG.warn("Error al enviar comprobante a Hacienda: " + e.getMessage()
                + " | source=ComprobanteService.enviarComprobanteAHacienda()"
                + " | despues=" + e.getMessage()
                + " | comprobante vuelve a PENDIENTE y queda encolado para Art. 21 párr. 3");
            return false;
        }
    }

    /**
     * Reverts the optimistic ENVIADO stamp after a transport failure so the
     * document returns to the PENDIENTE pool that the 48h batch scans.
     */
    private void degradarEnvioAPendiente(@Nonnull ComprobantesEmitidos comprobante) {
        if ("ACEPTADO".equals(comprobante.getHaciendaEstado())) {
            return;
        }
        comprobante.setHaciendaEstado("PENDIENTE");
        if (comprobante.getEncabezado() != null
            && !"RECHAZADO".equals(comprobante.getEncabezado().getEstado())) {
            comprobante.getEncabezado().setEstado("PENDIENTE");
        }
    }

    /**
     * Signs the document at the point of sale and hands the signed bytes to the
     * {@link Models.EnvioFueraLinea} outbox, which owns the Art. 21 ¶3
     * two-business-day transmission window.
     *
     * <p>The signed payload is the legally relevant artefact, so it must be
     * produced here — the submission path
     * ({@link #enviarComprobanteAHacienda(comprobante)}) signs internally and
     * discards the result. Re-signing on the way out is not an option: the
     * signature covers the bytes, so a re-marshalled document would no longer
     * match the one Hacienda is asked to accept for that clave.
     *
     * <p>Every failure is logged and swallowed. This runs after the comprobante
     * is already persisted, and losing the queued copy must never turn a saved
     * sale into a thrown exception that makes the caller believe nothing was
     * written.
     *
     * @return the queued row, or {@code null} when it could not be signed/queued
     */
    private @Nullable EnvioFueraLinea registrarPendienteDeEnvio(
            @Nonnull ComprobantesEmitidos comprobante, @Nonnull String tipoDocumento,
            @Nonnull String sucursal, @Nonnull String terminal, @Nonnull String situacion,
            @Nonnull String origen) {
        try {
            DocumentoStrategy strategyElegida = strategyFactory.forCode(tipoDocumento);
            String xml = strategyElegida.buildXml(comprobante);
            if (xml == null || xml.isBlank()) {
                LOG.warn("No se generó XML para encolar el envío diferido"
                    + " | source=ComprobanteService.registrarPendienteDeEnvio()"
                    + " | despues=el comprobante sigue PENDIENTE y el lote de 48h lo reintentará");
                return null;
            }
            HaciendaSigner.SignResult firmado = haciendaSigner.signXml(xml);
            if (!firmado.success || firmado.signedXml == null || firmado.signedXml.isBlank()) {
                LOG.warn("No se pudo firmar el XML para encolar el envío diferido: "
                    + (firmado.errorMessage != null ? firmado.errorMessage : "sin detalle")
                    + " | source=ComprobanteService.registrarPendienteDeEnvio()"
                    + " | despues=queda PENDIENTE; sin firma no hay documento que diferir");
                return null;
            }
            return envioFueraLineaService.registrarDocumentoFirmado(
                comprobante, tipoDocumento, sucursal, terminal, situacion,
                firmado.signedXml, origen);
        } catch (jakarta.xml.bind.JAXBException | RuntimeException e) {
            LOG.warn("Error encolando el envío diferido: " + e.getMessage()
                + " | source=ComprobanteService.registrarPendienteDeEnvio()"
                + " | despues=el comprobante queda PENDIENTE y el lote de 48h lo reintentará");
            return null;
        }
    }

    public ResumenFactura resumenComprobante(List<ArticuloCarrito> carrito) {
        try {
            BigDecimal totalServGravados = BigDecimal.ZERO;
            BigDecimal totalServExentos = BigDecimal.ZERO;
            BigDecimal totalServExonerado = BigDecimal.ZERO;
            BigDecimal totalMercanciasGravadas = BigDecimal.ZERO;
            BigDecimal totalMercanciasExentas = BigDecimal.ZERO;
            BigDecimal totalMercExonerada = BigDecimal.ZERO;
            BigDecimal totalGravado = BigDecimal.ZERO;
            BigDecimal totalExento = BigDecimal.ZERO;
            BigDecimal totalExonerado = BigDecimal.ZERO;
            BigDecimal totalVenta = BigDecimal.ZERO;
            BigDecimal totalDescuentos = BigDecimal.ZERO;
            BigDecimal totalVentaNeta = BigDecimal.ZERO;
            BigDecimal totalImpuesto = BigDecimal.ZERO;
            boolean esServicio = false;
            for (ArticuloCarrito articuloCarrito : carrito) {
                var articulo = articuloCarrito;
                BigDecimal precioFinal;
                if (articuloCarrito.isPromo()) {
                    BigDecimal precioUnit = articuloCarrito.getPrecioEfectivo();
                    BigDecimal desc = articuloCarrito.getDescuento() != null ? articuloCarrito.getDescuento() : BigDecimal.ZERO;
                    if (desc.compareTo(BigDecimal.valueOf(100)) > 0) desc = BigDecimal.valueOf(100);
                    precioFinal = precioUnit.multiply(BigDecimal.ONE.subtract(desc.divide(BigDecimal.valueOf(100), 5, RoundingMode.HALF_UP)))
                            .multiply(articuloCarrito.getCantidad());
                } else {
                    precioFinal = articuloCarrito.getTotalArticulos();
                }

                String impuestoStr = articulo.getArticulo().getCodigoCabys().getImpuesto();
                BigDecimal impuestoPct = BigDecimal.ZERO;
                if (impuestoStr != null && !impuestoStr.isEmpty()) {
                    try {
                        impuestoPct = new BigDecimal(impuestoStr);
                    } catch (NumberFormatException ignored) {
                        // Same rule as CarritoService: a non-numeric impuesto in
                        // the catalog must not silently zero the tax on a fiscal
                        // document. The 0% stands for this line, but it is loud.
                        LOG.warn("Impuesto no numerico en CABYS, se usa 0% para la linea: '"
                                + impuestoStr + "'"
                                + " | source=ComprobanteService.detallesComprobante()");
                    }
                }
                var impuesto = impuestoPct.divide(BigDecimal.valueOf(100), 5, RoundingMode.HALF_UP);
                var totalImpuestoArticulo = precioFinal.multiply(impuesto);

                ProductoExoneracion exoneracion = productoExoneracionService.findByArticuloCodigo(
                        articulo.getArticulo().getCodigo().toString());

                boolean isExonerado = exoneracion != null;
                if (impuestoPct.compareTo(BigDecimal.ZERO) != 0) {
                    if (esServicio) {
                        totalServGravados = totalServGravados.add(precioFinal);
                    }
                    if (!esServicio) {
                        totalMercanciasGravadas = totalMercanciasGravadas.add(precioFinal);
                    }
                    totalImpuesto = totalImpuesto.add(totalImpuestoArticulo);
                } else if (!isExonerado) {
                    if (esServicio) {
                        totalServExentos = totalServExentos.add(precioFinal);
                    }
                    if (!esServicio) {
                        totalMercanciasExentas = totalMercanciasExentas.add(precioFinal);
                    }
                }
                if (isExonerado) {
                    if (esServicio) {
                        totalServExonerado = totalServExonerado.add(precioFinal);
                    }
                    if (!esServicio) {
                        totalMercExonerada = totalMercExonerada.add(precioFinal);
                    }
                }
                totalVenta = totalVenta.add(precioFinal);
                totalDescuentos = totalDescuentos.add(
                    articuloCarrito.getTotalDescuento().multiply(articuloCarrito.getCantidad()));
            }
            totalVentaNeta = totalVenta.subtract(totalDescuentos);
            ResumenFactura resumen = new ResumenFactura();
            CodigoTipoMoneda moneda = new CodigoTipoMoneda();
            moneda.setCodigoMoneda("CRC");
            resumen.setCodigoMoneda(moneda);
            resumen.setTotalServGravados(totalServGravados);
            resumen.setTotalServExentos(totalServExentos);
            resumen.setTotalServExonerado(totalServExonerado);
            resumen.setTotalMercanciasGravadas(totalMercanciasGravadas);
            resumen.setTotalMercanciasExentas(totalMercanciasExentas);
            resumen.setTotalMercExonerada(totalMercExonerada);
            totalGravado = totalServGravados.add(totalMercanciasGravadas);
            totalExento = totalServExentos.add(totalMercanciasExentas);
            totalExonerado = totalServExonerado.add(totalMercExonerada);
            resumen.setTotalGravado(totalGravado);
            resumen.setTotalExento(totalExento);
            resumen.setTotalExonerado(totalExonerado);
            resumen.setTotalVenta(totalVenta);
            resumen.setTotalDescuentos(totalDescuentos);
            resumen.setTotalVentaNeta(totalVentaNeta);
            resumen.setTotalImpuesto(totalImpuesto);
            // TotalIVADevuelto, TotalOtrosCargos, TotalComprobante are set by crearComprobante()
            // Wire up TotalDesgloseImpuesto per tax rate (minOccurs="0" in XSD v4.4)
            Map<BigDecimal, BigDecimal> taxByRate = CarritoCalculations.calculateTotalTaxByRate(carrito);
            if (!taxByRate.isEmpty()) {
                List<TotalDesgloseImpuesto> desgloseList = new ArrayList<>();
                for (Map.Entry<BigDecimal, BigDecimal> entry : taxByRate.entrySet()) {
                    String rateStr = entry.getKey().toPlainString();
                    // Only include rates that map to a valid tariff
                    try {
                        Tipo_TarifaIVA tarifa = Tipo_TarifaIVA.getTarifa(rateStr);
                        TotalDesgloseImpuesto item = new TotalDesgloseImpuesto();
                        item.setCodigo("01"); // 01 = IVA standard tax code
                        item.setCodigoTarifaIVA(tarifa.getCodigo());
                        item.setTotalMontoImpuesto(entry.getValue().setScale(5, RoundingMode.HALF_UP));
                        item.setResumenFactura(resumen);
                        desgloseList.add(item);
                    } catch (IllegalArgumentException e) {
                        // An unknown tarifa still drops its desglose line while the
                        // total tax is reported in resumen, so the desglose no
                        // longer sums to the total. That is a catalog data problem
                        // (a rate with no Tipo_TarifaIVA mapping), and on a fiscal
                        // document it must be visible, not silent.
                        LOG.warn("Tarifa de IVA sin mapeo, su linea no ira en el desglose: '"
                                + rateStr + "'"
                                + " | source=ComprobanteService.resumenComprobante()");
                    }
                }
                resumen.setTotalDesgloseImpuestos(desgloseList);
            }
            return resumen;
        } catch (RuntimeException e) {
LOG.warn("Error al crear resumen de tiquete: " + e.getMessage() + " | source=resumenComprobante() | despues=" + e.getMessage());
            return null;
        }

    }

    /**
     * Sums all OtroCargo.MontoCargo from a DetalleServicio.
     * Returns ZERO when there are no OtrosCargos (the common case for retail).
     * Used to populate TotalOtrosCargos in ResumenFactura.
     */
    public static BigDecimal calcularTotalOtrosCargos(@Nullable DetalleServicio detalles) {
        if (detalles == null || detalles.getOtrosCargos() == null) return BigDecimal.ZERO;
        return detalles.getOtrosCargos().stream()
                .map(oc -> oc.getMontoCargo() != null ? oc.getMontoCargo() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Computes TotalIVADevuelto: the IVA amount that must be returned to the
     * customer when the invoice contains items at the 4% reduced medical rate
     * AND at least one payment method is a card (code "02").
     *
     * Per Hacienda v4.4 and Ley N.° 6826 Art. 11 inc. 1) subinc. b):
     *   Private medical services are taxed at 4%. When the patient pays with
     *   a credit/debit card, the provider must immediately refund that 4% IVA.
     *   TotalIVADevuelto records this refund.
     *
     * Returns ZERO when: no 4% items exist, or no card payment, or both.
     */
    public static BigDecimal calcularTotalIVADevuelto(
            @Nonnull List<ArticuloCarrito> carrito,
            @Nonnull List<EntradaPago> pagos) {
        boolean paidByCard = pagos.stream()
                .anyMatch(p -> "02".equals(p.getMetodoPago()));
        if (!paidByCard) return BigDecimal.ZERO;

        BigDecimal totalIVADevuelto = BigDecimal.ZERO;
        for (ArticuloCarrito articulo : carrito) {
            String impuestoStr = articulo.getArticulo().getCodigoCabys().getImpuesto();
            if (impuestoStr == null || impuestoStr.isEmpty()) continue;
            if (!"4".equals(impuestoStr)) continue;

            BigDecimal precioFinal;
            if (articulo.isPromo()) {
                BigDecimal precioUnit = articulo.getPrecioEfectivo();
                BigDecimal desc = articulo.getDescuento() != null ? articulo.getDescuento() : BigDecimal.ZERO;
                if (desc.compareTo(BigDecimal.valueOf(100)) > 0) desc = BigDecimal.valueOf(100);
                precioFinal = precioUnit.multiply(BigDecimal.ONE.subtract(
                        desc.divide(BigDecimal.valueOf(100), 5, RoundingMode.HALF_UP)))
                        .multiply(articulo.getCantidad());
            } else {
                precioFinal = articulo.getTotalArticulos();
            }
            BigDecimal iva = precioFinal.multiply(new BigDecimal("0.04"))
                    .setScale(5, RoundingMode.HALF_UP);
            totalIVADevuelto = totalIVADevuelto.add(iva);
        }
        return totalIVADevuelto;
    }

    public DetalleServicio detallesComprobante(List<ArticuloCarrito> carrito, String tipoDocumento) {
        try {
            // Enforce line limits per Hacienda v4.4 spec
            int maxLines;
            switch (tipoDocumento) {
                case "01": // FE (Factura Electronica)
                case "05": // FEE (Factura Exportacion Electronica)
                    maxLines = 60;
                    break;
                case "04": // TE (Tiquete Electronico)
                    maxLines = 1000;
                    break;
                case "02": // NC (Nota de Credito)
                case "03": // ND (Nota de Debito)
                case "08": // FEC (Factura Compra Electronica)
                    maxLines = 400;
                    break;
                default: // REP (Recibo Electronico de Pago) or unknown — no strict limit
                    maxLines = Integer.MAX_VALUE;
                    break;
            }
            if (carrito.size() > maxLines) {
                throw new IllegalArgumentException(
                    "El numero de lineas (" + carrito.size()
                    + ") excede el maximo permitido de " + maxLines
                    + " para el tipo de documento " + tipoDocumento);
            }

            boolean isRep = "10".equals(tipoDocumento);
            DetalleServicio detalles = new DetalleServicio();
            List<LineaDetalle> lineasDetalle = new ArrayList<>();
            for (int i = 0; i < carrito.size(); i++) {
                ArticuloCarrito articulo = carrito.get(i);
                var Cantidad = articulo.getCantidad();
                var precioUnitario = articulo.getPrecioEfectivo();
                var montoTotal = precioUnitario.multiply(Cantidad);

                LineaDetalle linea = new LineaDetalle();
                linea.setNumeroLinea(i);

                // Fields common to all document types (including REP)
                linea.setDetalle(articulo.getArticulo().getNombre());
                linea.setMontoTotal(montoTotal);
                linea.setSubTotal(montoTotal);

                // REP LineaDetalle is simplified per V4.4 XSD:
                // Only NumeroLinea, Detalle, MontoTotal, SubTotal, Impuesto, ImpuestoNeto, MontoTotalLinea.
                // CodigoCabys, CodigosComerciales, Cantidad, UnidadMedida, PrecioUnitario, Descuentos,
                // DetallesSurtidos, OtrosCargos are NOT in REP LineaDetalle.
                if (!isRep) {
                    linea.setCodigoCabys(articulo.getArticulo().getCodigoCabys().getCodigo());
                    List<CodigoComercial> codigosComerciales = new ArrayList<>();
                    CodigoComercial codigoComercial = new CodigoComercial();
                    codigoComercial.setTipo("04");
                    codigoComercial.setCodigo(articulo.getArticulo().getCodigoBarra());
                    codigosComerciales.add(codigoComercial);
                    linea.setCodigosComerciales(codigosComerciales);
                    linea.setCantidad(Cantidad);
                    linea.setUnidadMedida(articulo.getArticulo().getUnidadMedida());
                    linea.setUnidadMedidaComercial(articulo.getArticulo().getUnidadMedidaComercial());
                    linea.setPrecioUnitario(precioUnitario);
                }

                List<Descuento> descuentos = new ArrayList<>();
                if (articulo.isPromo()) {
                    List<Promocion> promociones = articulo.getPromociones();
                    if (promociones != null && !promociones.isEmpty()) {
                        for (Promocion promocion : promociones) {
                            Descuento descuento = new Descuento();
                            descuento.setMontoDescuento(articulo.getTotalDescuento().multiply(Cantidad));
                            // Use the promo's Nota 20 discount code, fall back to "06" if unset
                            String codigo = promocion.getCodigoDescuento();
                            if (codigo == null || codigo.isBlank()) {
                                codigo = Tipo_Codigo_Descuento.DESCUENTO_PROMOCIONAL.getCodigo();
                            }
                            descuento.setCodigoDescuento(codigo);
                            // Nota 20: when code=99, CodigoDescuentoOtro must contain the user-defined reason
                            if ("99".equals(codigo)) {
                                descuento.setCodigoDescuentoOtro(promocion.getNombre());
                            }
                            descuento.setNaturalezaDescuento(promocion.getNombre());
                            descuentos.add(descuento);
                        }
                    }
                }
                // Conditional DetalleSurtido for manufacturer-origin combos
                // Per Hacienda v4.4 (Tipo 03), only combos assembled at origin
                // (ensambladoOrigen=true) with own SKU/GTIN qualify for DetalleSurtido.
                // In-store bundles use discounts instead and must NOT emit DetalleSurtido.
                if (articulo.isPromo() && !isRep) {
                    List<Promocion> promociones = articulo.getPromociones();
                    if (promociones != null && !promociones.isEmpty()) {
                        for (Promocion promocion : promociones) {
                            if (promocion.isEnsambladoOrigen()
                                && promocion.getArticulosCarrito() != null
                                && !promocion.getArticulosCarrito().isEmpty()) {

                                List<LineaDetalleSurtido> surtidos = new ArrayList<>();
                                for (ArticuloCarrito compArticulo : promocion.getArticulosCarrito()) {
                                    LineaDetalleSurtido surtido = new LineaDetalleSurtido();
                                    surtido.setCodigoCabysSurtido(
                                        compArticulo.getArticulo().getCodigoCabys().getCodigo());
                                    surtido.setCantidadSurtido(compArticulo.getCantidad());
                                    surtido.setUnidadMedidaSurtido(
                                        compArticulo.getArticulo().getUnidadMedida());
                                    surtido.setDetalleSurtido(
                                        compArticulo.getArticulo().getNombre());
                                    surtido.setPrecioUnitarioSurtido(
                                        compArticulo.getPrecioEfectivo());
                                    surtido.setMontoTotalSurtido(
                                        compArticulo.getTotalArticulo());
                                    surtido.setSubTotalSurtido(
                                        compArticulo.getTotalArticulo());
                                    surtidos.add(surtido);
                                }
                                linea.setDetallesSurtidos(surtidos);
                                linea.setDetalleSurtido(new DetalleSurtido(surtidos));
                            }
                        }
                    }
                }
                if (!isRep) {
                    linea.setDescuentos(descuentos);
                }
                List<Impuesto> impuestos = new ArrayList<>();
                if (articulo.getTotalImpuesto().compareTo(BigDecimal.ZERO) != 0) {
                    Impuesto impuesto = new Impuesto();
                    String codigoImpuestoRaw = articulo.getArticulo().getCodigoCabys().getImpuesto();
                    String codigoImpuesto = normalizeImpuesto(codigoImpuestoRaw);
                    impuesto.setCodigo("01");
                    Tipo_TarifaIVA tarifa = Tipo_TarifaIVA.getTarifa(codigoImpuesto);
                    impuesto.setCodigoTarifaIVA(tarifa.getCodigo());
                    impuesto.setTarifa(new BigDecimal(codigoImpuesto));
                    impuesto.setMonto(articulo.getTotalImpuesto().multiply(Cantidad));
                    ProductoExoneracion exoneracion = productoExoneracionService.findByArticuloCodigo(
                            articulo.getArticulo().getCodigo().toString());
                    if (exoneracion != null) {
                        Exoneracion exoneracionEntity = new Exoneracion();
                        exoneracionEntity.setTipoDocumentoEX1(exoneracion.getTipoDocumentoEX1());
                        exoneracionEntity.setTipoDocumentoOTRO(exoneracion.getTipoDocumentoOTRO());
                        exoneracionEntity.setNumeroDocumento(exoneracion.getNumeroDocumento());
                        exoneracionEntity.setArticulo(exoneracion.getArticulo());
                        exoneracionEntity.setInciso(exoneracion.getInciso());
                        exoneracionEntity.setNombreInstitucion(exoneracion.getNombreInstitucion());
                        exoneracionEntity.setNombreInstitucionOtros(exoneracion.getNombreInstitucionOtros());
                        exoneracionEntity.setFechaEmisionEX(exoneracion.getFechaEmisionEX());
                        exoneracionEntity.setTarifaExonerada(exoneracion.getTarifaExonerada());
                        exoneracionEntity.setMontoExoneracion(exoneracion.getMontoExoneracion());
                        exoneracionEntity.setImpuesto(impuesto);
                        impuesto.setExoneracion(exoneracionEntity);
                    }
                    impuestos.add(impuesto);
                }
                linea.setMontoTotalLinea(montoTotal);
                linea.setImpuestos(impuestos);

                // ImpuestoNeto mandatory in all XSDs except FEE
                if (!"05".equals(tipoDocumento)) {
                    linea.setImpuestoNeto(articulo.getTotalImpuesto().multiply(Cantidad));
                }
                // BaseImponible mandatory for FE/TE/NC/ND/FEC — not in REP/FEE XSD
                if (!isRep && !"05".equals(tipoDocumento)) {
                    linea.setBaseImponible(montoTotal);
                }
                // ImpuestoAsumidoEmisorFabrica mandatory for FE/TE; optional for NC/ND
                if ("01".equals(tipoDocumento) || "04".equals(tipoDocumento)) {
                    linea.setImpuestoAsumidoEmisorFabrica(BigDecimal.ZERO);
                }

                linea.setDetalleServicio(detalles);
                lineasDetalle.add(linea);
            }
            detalles.setLineasDetalle(lineasDetalle);
            detalles.setStatus(true);
            return detalles;
        } catch (RuntimeException e) {
            LOG.warn("Error al crear detalles de tiquete: " + e.getMessage() + " | source=detallesComprobante() | despues=" + e.getMessage());
            return null;
        }

    }

    /**
     * Preflight del Mensaje Receptor: valida la cédula que se enviará como
     * &lt;NumeroCedulaReceptor&gt; contra el patrón declarado en el XSD oficial.
     * <p>
     * No altera lo que se envía a Hacienda ni inventa reglas fiscales: solo convierte un
     * rechazo opaco del validador ("cvc-datatype-valid" sobre NumeroCedulaReceptor, sin
     * causa visible) en un error local legible para el operador.
     *
     * @param cedula cédula del obligado tributario (quien responde el MR)
     * @throws IllegalArgumentException con mensaje en español si no cumple \d{9,12}
     */
    public static void validarCedulaMensajeReceptor(@Nullable String cedula) {
        String valor = cedula == null ? "" : cedula.trim();
        if (valor.isEmpty()) {
            throw new IllegalArgumentException(
                "No se puede enviar el Mensaje Receptor: la identificación del obligado tributario está vacía. "
                + "MensajeReceptor_V4.4.xsd exige <NumeroCedulaReceptor> con el patrón \\d{9,12}. "
                + "Configure la identificación en PUT /api/app/settings.");
        }
        if (!CEDULA_MENSAJE_RECEPTOR.matcher(valor).matches()) {
            throw new IllegalArgumentException(
                "No se puede enviar el Mensaje Receptor: la cédula \"" + valor + "\" del obligado tributario no cumple "
                + "el patrón \\d{9,12} (solo dígitos, de 9 a 12) que exige <NumeroCedulaReceptor> en "
                + "MensajeReceptor_V4.4.xsd. El esquema v4.4 no admite aquí identificadores alfanuméricos, "
                + "aunque el Registro Nacional ya los emite para personas jurídicas. "
                + "Corrija la identificación en PUT /api/app/settings antes de responder la factura.");
        }
    }

    public String generateMensajeReceptorXml(ConfiguracionAplicacion settings, String clave, String numeroCedulaEmisor,
                                              String numeroCedulaReceptor,
                                              LocalDateTime fechaEmisionDoc, int codigoMensaje, String detalleMensaje,
                                              BigDecimal montoTotalImpuesto, BigDecimal totalFactura,
                                              String numeroConsecutivoReceptor) {
        // Preflight FUERA del try a propósito: el catch de abajo convierte cualquier
        // RuntimeException en un null silencioso, y un null se reporta al operador como
        // "Mensaje Receptor encolado". Que la restricción del esquema falle aquí, con su
        // causa, es preferible a un envío que nunca podrá validarse.
        validarCedulaMensajeReceptor(numeroCedulaReceptor);
        try {
            StringBuilder xml = new StringBuilder();
            // NOTE: No XML declaration (<?xml?>) — Hacienda's MR parser rejects it
            xml.append("<MensajeReceptor xmlns=\"https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/mensajeReceptor\">");
            xml.append("<Clave>").append(escapeXml(clave)).append("</Clave>");
            // NumeroCedulaEmisor: the original invoice emitter (seller), NOT the MR sender
            xml.append("<NumeroCedulaEmisor>").append(escapeXml(numeroCedulaEmisor)).append("</NumeroCedulaEmisor>");
            if (fechaEmisionDoc != null) {
                // Append -06:00 (Costa Rica timezone) per FactuPOS recommendation: if no timezone, add -06:00
                xml.append("<FechaEmisionDoc>").append(fechaEmisionDoc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("-06:00</FechaEmisionDoc>");
            }
            // Mensaje is a simple integer: 1=Aceptado, 2=Aceptado parcialmente, 3=Rechazado
            xml.append("<Mensaje>").append(codigoMensaje).append("</Mensaje>");
            if (detalleMensaje != null && !detalleMensaje.isEmpty()) {
                // XSD v4.4 restricts DetalleMensaje to maxLength=160
                String truncated = detalleMensaje.length() > 160 ? detalleMensaje.substring(0, 160) : detalleMensaje;
                xml.append("<DetalleMensaje>").append(escapeXml(truncated)).append("</DetalleMensaje>");
            }
            if (montoTotalImpuesto != null) {
                xml.append("<MontoTotalImpuesto>").append(montoTotalImpuesto.toPlainString()).append("</MontoTotalImpuesto>");
            }
            // CodigoActividad from settings (optional)
            if (settings.getCodigoActividad() != null && !settings.getCodigoActividad().trim().isEmpty()) {
                xml.append("<CodigoActividad>").append(escapeXml(settings.getCodigoActividad())).append("</CodigoActividad>");
            }
            xml.append("<TotalFactura>").append(totalFactura.toPlainString()).append("</TotalFactura>");
            // NumeroCedulaReceptor: the original invoice receptor's ID
            xml.append("<NumeroCedulaReceptor>").append(escapeXml(numeroCedulaReceptor)).append("</NumeroCedulaReceptor>");
            // NumeroConsecutivoReceptor: 20-char consecutive for the MR
            xml.append("<NumeroConsecutivoReceptor>").append(escapeXml(numeroConsecutivoReceptor)).append("</NumeroConsecutivoReceptor>");
            xml.append("</MensajeReceptor>");
            return xml.toString();
        } catch (RuntimeException e) {
            LOG.warn("Error generating MensajeReceptor XML: " + e.getMessage() + " | source=ComprobanteService.generateMensajeReceptorXml() | despues=" + e.getMessage());
            return null;
        }
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    public void enviarFacturaACliente(ComprobantesEmitidos tiqueteElectronico, Clientes cliente, Usuarios user, BigDecimal pago, BigDecimal vuelto, List<EntradaPago> pagos) {
        try {
            if (cliente == null || cliente.getEmail() == null || cliente.getEmail().isEmpty()) {
                LOG.info("Cliente sin email, no se envia factura: " + tiqueteElectronico.getEncabezado().getNumeroConsecutivo() + " | source=ComprobanteService.enviarFacturaACliente()");
                return;
            }

            ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
            if (settings == null) {
                LOG.warn("No hay configuracion de Hacienda para enviar factura | source=ComprobanteService.enviarFacturaACliente()");
                return;
            }

            // Generate PDF
            pdfGenerator.generarPDFTiqueteElectronico(tiqueteElectronico, settings, 
                new ArrayList<>(), cliente, user, pago, vuelto, pagos);
            String pdfUrl = pdfGenerator.getPdfUrl();
            if (pdfUrl == null || pdfUrl.isEmpty()) {
                LOG.warn("No se pudo generar PDF para envio | source=ComprobanteService.enviarFacturaACliente()");
                return;
            }

            // Generate XML via type-specific strategy (proper root element, namespace, OtrosCargos)
            String docCode = tiqueteElectronico.getEncabezado() != null
                ? tiqueteElectronico.getEncabezado().getCodigoDocumento() : null;
            DocumentoStrategy strategy = strategyFactory.forCode(docCode);
            String xmlContent;
            try {
                xmlContent = strategy.buildXml(tiqueteElectronico);
            } catch (jakarta.xml.bind.JAXBException e) {
                LOG.warn("Error generating XML for invoice: " + e.getMessage()
                    + " | source=ComprobanteService.enviarFacturaACliente()"
                    + " | despues=" + e.getMessage());
                return;
            }
            if (xmlContent == null) {
                LOG.warn("No se pudo generar XML para envio | source=ComprobanteService.enviarFacturaACliente()");
                return;
            }

            // Save XML to temporary file
            File xmlFile = File.createTempFile("factura_" + tiqueteElectronico.getHaciendaClave(), ".xml");
            try (java.io.FileWriter writer = new java.io.FileWriter(xmlFile)) {
                writer.write(xmlContent);
            }

            // Download PDF to temporary file
            File pdfFile = File.createTempFile("factura_" + tiqueteElectronico.getHaciendaClave(), ".pdf");
            try (java.io.InputStream in = new java.net.URL(pdfUrl).openStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(pdfFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // Send email with both attachments
            String subject = "Factura Electronica " + tiqueteElectronico.getEncabezado().getNumeroConsecutivo() + " - " + settings.getNombreNegocio();
            String body = "Estimado/a " + cliente.getName() + ",\n\n"
                + "Adjuntamos su factura electronica " + tiqueteElectronico.getEncabezado().getNumeroConsecutivo() 
                + " aceptada por Hacienda.\n\n"
                + "Total: " + (tiqueteElectronico.getResumen() != null ? tiqueteElectronico.getResumen().getTotalVentaNeta() : "N/A") + "\n\n"
                + "Saludos cordiales,\n" + settings.getNombreNegocio();

            List<String> recipients = new ArrayList<>();
            recipients.add(cliente.getEmail());

            List<File> attachments = new ArrayList<>();
            attachments.add(pdfFile);
            attachments.add(xmlFile);

            emailService.sendEmailsWithAttachments(recipients, subject, body, 
                settings.getCorreoElectronico(), settings.getContrasenaCorreo(), 
                attachments, result -> {
                    LOG.info("Resultado envio factura a cliente: " + result + " | source=ComprobanteService.enviarFacturaACliente()");
                    // Clean up temp files
                    pdfFile.delete();
                    xmlFile.delete();
                });

        } catch (IOException | RuntimeException e) {
            LOG.warn("Error enviando factura a cliente: " + e.getMessage() + " | source=ComprobanteService.enviarFacturaACliente() | despues=" + e.getMessage());
        }
    }

    private void validarTotalMedioPago(ResumenFactura resumen, String numeroConsecutivo) {
        if (resumen == null) return;
        String schemaVersion = resumen.getSchemaVersion();
        if ("4.4".equals(schemaVersion)) {
            List<MedioPagoR> mediosPago = resumen.getMediosPago();
            if (mediosPago == null || mediosPago.isEmpty()) {
                throw new IllegalArgumentException(
                    "V4.4: MedioPago es obligatorio en ResumenFactura para comprobante " + numeroConsecutivo);
            }

            BigDecimal totalComprobante = resumen.getTotalComprobante();
            BigDecimal sumaMediosPago = mediosPago.stream()
                .map(mp -> mp.getTotalMedioPago() != null ? mp.getTotalMedioPago() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (sumaMediosPago.compareTo(totalComprobante) != 0) {
                throw new IllegalArgumentException(
                    "V4.4: Suma de TotalMedioPago (" + sumaMediosPago
                    + ") no coincide con TotalComprobante (" + totalComprobante
                    + ") para comprobante " + numeroConsecutivo
                    + ". Bitácora item 124/125: Total del Comprobante debe coincidir con sumatoria de montos por Medio de Pago.");
            }
        }
    }

    /**
     * Normalizes raw impuesto strings from Cabys (e.g. "13", "13.00", "13%", " 13 ", "0.00", "13.0")
     * to the canonical codes expected by {@link Tipo_TarifaIVA#getTarifa(String)}: "0", "0.5", "1", "2", "4", "8", "13".
     * Returns "0" for null/blank/unparseable input.
     */
    static String normalizeImpuesto(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return "0";
        String trimmed = raw.trim().replace("%", "").trim();
        try {
            BigDecimal bd = new BigDecimal(trimmed);
            bd = bd.stripTrailingZeros();
            String plain = bd.toPlainString();
            // Ensure canonical form: "0.0" -> "0", "13.00" -> "13"
            return plain;
        } catch (NumberFormatException e) {
            return "0";
        }
    }
}
