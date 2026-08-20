package Services;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Clients;
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
import Models.PagoEntry;
import Models.AppSettings;
import Models.Articulos.Promocion;
import Models.Users;
import Services.Facturas.EncabezadoService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.ResumenFacturaService;
import Services.Facturas.EmisorService;
import Services.Facturas.ReceptorService;
import Services.Facturas.DescuentoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import Services.LoyaltyService;
import Models.PuntosTransaccion;
import Utils.CarritoCalculations;
import Utils.PDFGenerator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.faces.view.ViewScoped;
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
import Services.EmailService;
import Services.AppSettingsService;
import Services.ConsecutivoEmitidoService;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import Models.AppSettings;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Clients;
import Models.Users;

@Named("comprobanteService")
@ViewScoped
public class ComprobanteService implements Serializable {

    @Inject
    private @Nonnull HaciendaServiceFacade haciendaServiceFacade;

    @Inject
    private @Nonnull AlertasService alertasService;
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

    public static class CrearComprobanteResult {
        public ComprobantesEmitidos comprobante;
        public boolean haciendaEnviado;
        public String haciendaMensaje;
    }

    public @Nullable CrearComprobanteResult crearComprobante(@Nonnull AppSettings appSettings, @Nonnull List<ArticuloCarrito> carrito,
                                                    @Nullable Clients selectedClient, @Nullable Clients cliente, @Nonnull Users currentUser,
                                                    @Nonnull DocumentoStrategy strategy, @Nonnull List<PagoEntry> pagos) {
        CrearComprobanteResult result = new CrearComprobanteResult();
        result.haciendaEnviado = false;
        
        try {
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

            // Use strategy to build the type-specific encabezado
            Encabezado encabezado = strategy.buildEncabezado(appSettings, selectedClient);
            encabezado.setNumeroConsecutivo(numeroConsecutivo);

            List<MedioPago> medioPagoList = new ArrayList<>();
            for (PagoEntry entry : pagos) {
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
            
            // Generate the Hacienda document key (50-digit clave with check digit)
            String clave = haciendaSigner.generateInvoiceKey(
                appSettings.getIdentificacion(),
                numeroConsecutivo,
                "1",
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
            for (PagoEntry entry : pagos) {
                if (entry.getMonto() == null || entry.getMonto().compareTo(BigDecimal.ZERO) <= 0) continue;
                MedioPagoR medioR = new MedioPagoR();
                medioR.setTipoMedioPago(entry.getMetodoPago());
                medioR.setTotalMedioPago(entry.getMonto());
                medioR.setResumenFactura(resumen);
                mediosPagoResumen.add(medioR);
                sumaPagos = sumaPagos.add(entry.getMonto());
                pagoCount++;
            }
            // Hacienda v4.4 requires sum of TotalMedioPago == TotalComprobante
            if (pagoCount > 0 && sumaPagos.compareTo(totalComprobante) != 0) {
                // Adjust last entry to match total — prevents rounding mismatch
                MedioPagoR last = mediosPagoResumen.get(pagoCount - 1);
                last.setTotalMedioPago(last.getTotalMedioPago().add(totalComprobante.subtract(sumaPagos)));
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
            validarTotalMedioPago(resumen, numeroConsecutivo);

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

            // Attempt immediate send to Hacienda per CR 2176 §5.6
            // On failure the comprobante stays PENDIENTE and the 48h batch scheduler retries
            result.haciendaEnviado = enviarComprobanteAHacienda(tiqueteElectronico);
            if (result.haciendaEnviado) {
                result.haciendaMensaje = "Comprobante creado y enviado a Hacienda";
            } else {
                result.haciendaMensaje = "Comprobante creado - pendiente de envío a Hacienda";
            }
            
            // Add loyalty points for the sale if client exists
            if (selectedClient != null && currentUser != null) {
                BigDecimal totalAmount = resumen.getTotalVentaNeta();
                String facturaReferencia = "FACT-" + consecutivo;
                
                try {
                    loyaltyService.earnPoints(selectedClient, totalAmount, facturaReferencia, currentUser);
                } catch (RuntimeException e) {
                    alertasService.registrarAlerta("Error Loyalty", "Error al agregar puntos de lealtad: " + e.getMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
                    alertasService.registrarAlerta("Error", "Error adding loyalty points: " + e.getMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
                }
            }
            
            return result;
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error Comprobante", "Error al crear comprobante: " + e.getMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
            alertasService.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
            return null;
        }

    }

    /**
     * Sends a comprobante to Hacienda and returns whether it was accepted.
     * <p>
     * On failure the comprobante remains PENDIENTE and the 48h batch scheduler
     * will retry. On success the estado is updated to ACEPTADO and the method
     * returns true.
     */
    public boolean enviarComprobanteAHacienda(ComprobantesEmitidos comprobante) {
        try {
            AppSettings appSettings = appSettingsService.returnCurrent();
            if (appSettings == null) {
                alertasService.registrarAlerta("Error", "No hay configuracion de Hacienda para enviar comprobante", null, 0,
                    "ComprobanteService.enviarComprobanteAHacienda()", null, null);
                return false;
            }

            String clave = comprobante.getHaciendaClave();
            if (clave == null || clave.isEmpty()) {
                alertasService.registrarAlerta("Error", "Comprobante sin clave de Hacienda", null, 0,
                    "ComprobanteService.enviarComprobanteAHacienda()", null, null);
                return false;
            }

            // ── Route through HaciendaServiceFacade ────────────────────────
            // The facade checks AppSettings.useFides and chooses the active provider:
            //   Fides API   → FidesApiService (auth → create → sign → submit → poll)
            //   Direct Hacienda → XML build → sign → HaciendaApiService.submitAndWait
            // ──────────────────────────────────────────────────────────────

            String provider = haciendaServiceFacade.isFidesEnabled() ? "Fides" : "Hacienda directa";
            alertasService.registrarAlerta("Hacienda", "Enviando comprobante " + clave + " via " + provider,
                null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, null);

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
                alertasService.registrarAlerta("Hacienda", "Comprobante " + (comprobante.getEncabezado() != null ? comprobante.getEncabezado().getNumeroConsecutivo() : clave) + " aceptado por Hacienda",
                    null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, null);
                return true;
            } else {
                if (comprobante.getEncabezado() != null) {
                    comprobante.getEncabezado().setEstado("RECHAZADO");
                    comprobante.getEncabezado().setMotivoRechazo(result.errorMessage);
                }
                comprobantesEmitidosService.update(comprobante);
                alertasService.registrarAlerta("Hacienda", "Hacienda rechazo comprobante: " + result.errorMessage,
                    null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, result.errorMessage);
                return false;
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error al enviar comprobante a Hacienda: " + e.getMessage(),
                null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, e.getMessage());
            return false;
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
                        // Unknown tax rate — skip silently; total tax is still reported in resumen
                    }
                }
                resumen.setTotalDesgloseImpuestos(desgloseList);
            }
            return resumen;
        } catch (RuntimeException e) {
alertasService.registrarAlerta("Error Resumen", "Error al crear resumen de tiquete: " + e.getMessage(), null, 0, "resumenComprobante()", null, e.getMessage());
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
            @Nonnull List<PagoEntry> pagos) {
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
                            descuentoService.create(descuento);
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
                if (!articulo.getTotalImpuesto().equals(BigDecimal.ZERO)) {
                    Impuesto impuesto = new Impuesto();
                    String codigoImpuesto = articulo.getArticulo().getCodigoCabys().getImpuesto();
                    if (codigoImpuesto == null || codigoImpuesto.isEmpty()) codigoImpuesto = "0";
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
                    impuestoService.create(impuesto);
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

                lineaService.create(linea);
                linea.setDetalleServicio(detalles);
                lineasDetalle.add(linea);
            }
            detalles.setLineasDetalle(lineasDetalle);
            detalles.setStatus(true);
            return detalles;
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error Detalles", "Error al crear detalles de tiquete: " + e.getMessage(), null, 0, "detallesComprobante()", null, e.getMessage());
            return null;
        }

    }

    public String generateMensajeReceptorXml(AppSettings settings, String clave, String numeroCedulaEmisor,
                                              String numeroCedulaReceptor,
                                              LocalDateTime fechaEmisionDoc, int codigoMensaje, String detalleMensaje,
                                              BigDecimal montoTotalImpuesto, BigDecimal totalFactura,
                                              String numeroConsecutivoReceptor) {
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
            alertasService.registrarAlerta("Error", "Error generating MensajeReceptor XML: " + e.getMessage(), null, 0, "ComprobanteService.generateMensajeReceptorXml()", null, e.getMessage());
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

    public void enviarFacturaACliente(ComprobantesEmitidos tiqueteElectronico, Clients cliente, Users user, BigDecimal pago, BigDecimal vuelto, List<PagoEntry> pagos) {
        try {
            if (cliente == null || cliente.getEmail() == null || cliente.getEmail().isEmpty()) {
                alertasService.registrarAlerta("Info", "Cliente sin email, no se envia factura: " + tiqueteElectronico.getEncabezado().getNumeroConsecutivo(), null, 0, "ComprobanteService.enviarFacturaACliente()", null, null);
                return;
            }

            AppSettings settings = appSettingsService.returnCurrent();
            if (settings == null) {
                alertasService.registrarAlerta("Error", "No hay configuracion de Hacienda para enviar factura", null, 0, "ComprobanteService.enviarFacturaACliente()", null, null);
                return;
            }

            // Generate PDF
            pdfGenerator.generarPDFTiqueteElectronico(tiqueteElectronico, settings, 
                new ArrayList<>(), cliente, user, pago, vuelto, pagos);
            String pdfUrl = pdfGenerator.getPdfUrl();
            if (pdfUrl == null || pdfUrl.isEmpty()) {
                alertasService.registrarAlerta("Error", "No se pudo generar PDF para envio", null, 0, "ComprobanteService.enviarFacturaACliente()", null, null);
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
                alertasService.registrarAlerta("Error", "Error generating XML for invoice: " + e.getMessage(), null, 0,
                    "ComprobanteService.enviarFacturaACliente()", null, e.getMessage());
                return;
            }
            if (xmlContent == null) {
                alertasService.registrarAlerta("Error", "No se pudo generar XML para envio", null, 0, "ComprobanteService.enviarFacturaACliente()", null, null);
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
                    alertasService.registrarAlerta("Email", "Resultado envio factura a cliente: " + result, null, 0, "ComprobanteService.enviarFacturaACliente()", null, null);
                    // Clean up temp files
                    pdfFile.delete();
                    xmlFile.delete();
                });

        } catch (IOException | RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error enviando factura a cliente: " + e.getMessage(), null, 0, "ComprobanteService.enviarFacturaACliente()", null, e.getMessage());
        }
    }

    private void validarTotalMedioPago(ResumenFactura resumen, String numeroConsecutivo) {
        if (resumen == null) return;
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
