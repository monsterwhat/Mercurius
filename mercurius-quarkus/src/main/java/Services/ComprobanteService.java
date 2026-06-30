package Services;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Detalles.CodigoComercial;
import Models.Detalles.Descuento;
import Models.Detalles.DetalleServicio;
import Models.Detalles.DetalleSurtido;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Detalles.OtroCargo;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.IdentificacionEmisor;
import Models.Encabezado.IdentificacionReceptor;
import Models.Encabezado.MedioPago;
import Models.Encabezado.Receptor;
import Models.Encabezado.Telefono;
import Models.Encabezado.Ubicacion;
import Models.Resumen.ResumenFactura;
import Models.Resumen.TotalDesgloseImpuesto;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Enums.Tipo_CondicionVenta;
import Models.Enums.Tipo_MedioPago;
import Models.Enums.Tipo_Codigo_Descuento;
import Models.Enums.Tipo_TarifaIVA;
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
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.io.Serializable;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import Services.EmailService;
import Services.AppSettingsService;
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
    private AlertasService alertasService;
    @Inject
    private EncabezadoService encabezadoService;
    @Inject
    private DetalleServicioService detallesService;
    @Inject
    private ResumenFacturaService resumenService;
    @Inject
    private EmisorService emisorService;
    @Inject
    private ReceptorService receptorService;
    @Inject
    private DescuentoService descuentoService;
    @Inject
    private ImpuestoService impuestoService;
    @Inject
    private LineaDetalleService lineaService;
    @Inject
    private LoyaltyService loyaltyService;
    
    @Inject
    private HaciendaApiService haciendaApiService;
    
    @Inject
    private HaciendaSigner haciendaSigner;
    
    @Inject
    private ComprobantesEmitidosService comprobantesEmitidosService;

    @Inject
    private EmailService emailService;

    @Inject
    private PDFGenerator pdfGenerator;

    @Inject
    private AppSettingsService appSettingsService;

    @Inject
    private DocumentoStrategyFactory strategyFactory;

    public static class CrearComprobanteResult {
        public ComprobantesEmitidos comprobante;
        public boolean haciendaEnviado;
        public String haciendaMensaje;
    }

    public CrearComprobanteResult crearComprobante(AppSettings appSettings, List<ArticuloCarrito> carrito,
                                                    Clients selectedClient, Clients cliente, Users currentUser,
                                                    DocumentoStrategy strategy, String medioPago) {
        CrearComprobanteResult result = new CrearComprobanteResult();
        result.haciendaEnviado = false;
        
        try {
            // Generate consecutive number
            int consecutivo = (appSettings.getUltimoConsecutivo() != null ? appSettings.getUltimoConsecutivo() : 0) + 1;
            appSettings.setUltimoConsecutivo(consecutivo);
            String tipoDocumento = strategy.getCodigoDocumento();
            String sucursal = String.format("%03d", Integer.parseInt(
                appSettings.getCodigoSucursal() != null ? appSettings.getCodigoSucursal() : "001"));
            String terminal = String.format("%05d", Integer.parseInt(
                appSettings.getCodigoTerminal() != null ? appSettings.getCodigoTerminal() : "001"));
            String numeroConsecutivo = String.format("%s%s%s%010d",
                sucursal, terminal,
                tipoDocumento != null ? tipoDocumento : "04",
                consecutivo);

            // Use strategy to build the type-specific encabezado
            Encabezado encabezado = strategy.buildEncabezado(appSettings, selectedClient);
            encabezado.setNumeroConsecutivo(numeroConsecutivo);

            // Set medioPago from user selection (strategies no longer hardcode it)
            List<MedioPago> medioPagoList = new ArrayList<>();
            MedioPago medio = new MedioPago();
            medio.setMedioPago(medioPago);
            medio.setComprobante(encabezado);
            medioPagoList.add(medio);
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
            DetalleServicio detalles = detallesTiqueteElectronico(carrito, tipoDocumento);

            // REP V4.4: DetalleServicio is MANDATORY (minOccurs="1")
            if ("10".equals(tipoDocumento) && (detalles == null
                || detalles.getLineasDetalle() == null || detalles.getLineasDetalle().isEmpty())) {
                throw new IllegalArgumentException(
                    "REP requiere al menos una línea de detalle (DetalleServicio es obligatorio)"
                );
            }

            detallesService.create(detalles);
            ResumenFactura resumen = resumenTiqueteElectronico(carrito);
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
            
            // Persist the comprobante (pending Hacienda submission — batched every 48h)
            comprobantesEmitidosService.createAndReturn(tiqueteElectronico);
            result.haciendaMensaje = "Comprobante creado - pendiente de envío a Hacienda";
            
            // Add loyalty points for the sale if client exists
            if (selectedClient != null && currentUser != null) {
                BigDecimal totalAmount = resumen.getTotalVentaNeta();
                String facturaReferencia = "FACT-" + consecutivo;
                
                try {
                    loyaltyService.earnPoints(selectedClient, totalAmount, facturaReferencia, currentUser);
                } catch (Exception e) {
                    alertasService.registrarAlerta("Error Loyalty", "Error al agregar puntos de lealtad: " + e.getMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
                    alertasService.registrarAlerta("Error", "Error adding loyalty points: " + e.getMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
                }
            }
            
            return result;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error Comprobante", "Error al crear comprobante: " + e.getMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
            alertasService.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
            return null;
        }

    }

    public void enviarComprobanteAHacienda(ComprobantesEmitidos comprobante) {
        try {
            AppSettings appSettings = appSettingsService.returnCurrent();
            if (appSettings == null) {
                alertasService.registrarAlerta("Error", "No hay configuracion de Hacienda para enviar comprobante", null, 0,
                    "ComprobanteService.enviarComprobanteAHacienda()", null, null);
                return;
            }

            String clave = comprobante.getHaciendaClave();
            if (clave == null || clave.isEmpty()) {
                alertasService.registrarAlerta("Error", "Comprobante sin clave de Hacienda", null, 0,
                    "ComprobanteService.enviarComprobanteAHacienda()", null, null);
                return;
            }

            // Determine document type from the encabezado and build type-specific XML
            String docCode = comprobante.getEncabezado() != null
                ? comprobante.getEncabezado().getCodigoDocumento() : null;
            DocumentoStrategy strategy = strategyFactory.forCode(docCode);
            String xmlContent = strategy.buildXml(comprobante);

            HaciendaSigner.SignResult signResult = haciendaSigner.signXml(xmlContent);
            if (!signResult.success) {
                alertasService.registrarAlerta("Hacienda", "Error al firmar comprobante: " + signResult.errorMessage,
                    null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, signResult.errorMessage);
                return;
            }

            String emisorTipo = appSettings.getTipoIdentificacion();
            String emisorNumero = appSettings.getIdentificacion();
            String receptorTipo = "01";
            String receptorNumero = "000000000";
            if (comprobante.getEncabezado() != null && comprobante.getEncabezado().getReceptor() != null
                && comprobante.getEncabezado().getReceptor().getIdentificacion() != null) {
                receptorTipo = comprobante.getEncabezado().getReceptor().getIdentificacion().getTipo();
                receptorNumero = comprobante.getEncabezado().getReceptor().getIdentificacion().getNumero();
            }

            alertasService.registrarAlerta("Hacienda XML", "Enviando comprobante " + clave + " a Hacienda:\n" + signResult.signedXml,
                null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, null);
            HaciendaApiService.ApiResponse apiResponse = haciendaApiService.submitAndWait(
                clave, signResult.signedXml,
                emisorTipo, emisorNumero, receptorTipo, receptorNumero);

            if (apiResponse.isSuccess()) {
                comprobante.setHaciendaEstado("ACEPTADO");
                comprobante.setHaciendaFechaEnvio(LocalDateTime.now());
                comprobante.setHaciendaFechaRespuesta(LocalDateTime.now());
                if (comprobante.getEncabezado() != null) {
                    comprobante.getEncabezado().setEstado("ACEPTADO");
                }
                comprobantesEmitidosService.update(comprobante);
                alertasService.registrarAlerta("Hacienda", "Comprobante " + (comprobante.getEncabezado() != null ? comprobante.getEncabezado().getNumeroConsecutivo() : clave) + " aceptado por Hacienda",
                    null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, null);
            } else {
                if (comprobante.getEncabezado() != null) {
                    comprobante.getEncabezado().setEstado("RECHAZADO");
                    comprobante.getEncabezado().setMotivoRechazo(apiResponse.errorMessage);
                }
                comprobantesEmitidosService.update(comprobante);
                alertasService.registrarAlerta("Hacienda", "Hacienda rechazo comprobante: " + apiResponse.errorMessage,
                    null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, apiResponse.errorMessage);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error al enviar comprobante a Hacienda: " + e.getMessage(),
                null, 0, "ComprobanteService.enviarComprobanteAHacienda()", null, e.getMessage());
        }
    }

    public ResumenFactura resumenTiqueteElectronico(List<ArticuloCarrito> carrito) {
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
            BigDecimal totalIVADevuelto = BigDecimal.ZERO;
            BigDecimal totalOtrosCargos = BigDecimal.ZERO;
            BigDecimal totalComprobante = BigDecimal.ZERO;
            for (ArticuloCarrito articuloCarrito : carrito) {
                var articulo = articuloCarrito;
                var precioFinal = articuloCarrito.getTotalArticulos();
                var impuesto = BigDecimal.valueOf(articulo.getArticulo().getCodigoCabys().getImpuesto()).divide(BigDecimal.valueOf(100));
                var totalImpuestoArticulo = precioFinal.multiply(impuesto);
                if (articulo.getArticulo().getCodigoCabys().getImpuesto() != 0) {
                    totalServGravados = totalServGravados.add(precioFinal);
                    totalImpuesto = totalImpuesto.add(totalImpuestoArticulo);
                } else if (articulo.getArticulo().getCodigoCabys().getImpuesto() == 0) {
                    totalServExentos = totalServExentos.add(precioFinal);
                }
                if (articulo.getArticulo().getCodigoCabys().getImpuesto() != 0) {
                    totalMercanciasGravadas = totalMercanciasGravadas.add(precioFinal);
                } else if (articulo.getArticulo().getCodigoCabys().getImpuesto() == 0) {
                    totalMercanciasExentas = totalMercanciasExentas.add(precioFinal);
                }
                totalVenta = totalVenta.add(precioFinal);
                totalDescuentos = totalDescuentos.add(articuloCarrito.getTotalDescuento());
            }
            totalVentaNeta = totalVenta.subtract(totalDescuentos);
            totalComprobante = totalVentaNeta.add(totalImpuesto);
            ResumenFactura resumen = new ResumenFactura();
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
            resumen.setTotalIVADevuelto(totalIVADevuelto);
            resumen.setTotalOtrosCargos(totalOtrosCargos);
            resumen.setTotalComprobante(totalComprobante);
            // Wire up TotalDesgloseImpuesto per tax rate (minOccurs="0" in XSD v4.4)
            Map<Integer, BigDecimal> taxByRate = CarritoCalculations.calculateTotalTaxByRate(carrito);
            if (!taxByRate.isEmpty()) {
                List<TotalDesgloseImpuesto> desgloseList = new ArrayList<>();
                for (Map.Entry<Integer, BigDecimal> entry : taxByRate.entrySet()) {
                    String rateStr = String.valueOf(entry.getKey());
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
        } catch (Exception e) {
alertasService.registrarAlerta("Error Resumen", "Error al crear resumen de tiquete: " + e.getMessage(), null, 0, "resumenTiqueteElectronico()", null, e.getMessage());
            return null;
        }

    }

    public DetalleServicio detallesTiqueteElectronico(List<ArticuloCarrito> carrito, String tipoDocumento) {
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

            DetalleServicio detalles = new DetalleServicio();
            List<OtroCargo> otrosCargos = new ArrayList<>();
            List<LineaDetalle> lineasDetalle = new ArrayList<>();
            for (int i = 0; i < carrito.size(); i++) {
                ArticuloCarrito articulo = carrito.get(i);
                LineaDetalle linea = new LineaDetalle();
                linea.setNumeroLinea(i);
                linea.setCodigoCabys(articulo.getArticulo().getCodigoCabys().getCodigo());
                List<CodigoComercial> codigosComerciales = new ArrayList<>();
                CodigoComercial codigoComercial = new CodigoComercial();
                codigoComercial.setTipo("04");
                codigoComercial.setCodigo(articulo.getArticulo().getCodigoBarra());
                codigosComerciales.add(codigoComercial);
                linea.setCodigosComerciales(codigosComerciales);
                var Cantidad = articulo.getCantidad();
                linea.setCantidad(Cantidad);
                linea.setUnidadMedida(articulo.getArticulo().getUnidadMedida());
                linea.setUnidadMedidaComercial(articulo.getArticulo().getUnidadMedidaComercial());
                linea.setDetalle(articulo.getArticulo().getNombre());
                var precioUnitario = articulo.getPrecioEfectivo();
                linea.setPrecioUnitario(precioUnitario);
                var montoTotal = precioUnitario.multiply(Cantidad);
                linea.setMontoTotal(montoTotal);
                linea.setSubTotal(montoTotal);
                List<Descuento> descuentos = new ArrayList<>();
                if (articulo.isPromo()) {
                    List<Promocion> promociones = articulo.getPromociones();
                    if (promociones != null && !promociones.isEmpty()) {
                        for (Promocion promocion : promociones) {
                            Descuento descuento = new Descuento();
                            descuento.setMontoDescuento(articulo.getTotalDescuento());
                            descuento.setCodigoDescuento(Tipo_Codigo_Descuento.DESCUENTO_PROMOCIONAL.getCodigo());
                            descuento.setNaturalezaDescuento(promocion.getNombre());
                            descuentoService.create(descuento);
                            descuentos.add(descuento);
                        }
                    }
                }
                // Populate DetallesSurtidos for promo/combo items
                if (articulo.isPromo()) {
                    List<Promocion> promociones = articulo.getPromociones();
                    if (promociones != null && !promociones.isEmpty()) {
                        List<DetalleSurtido> surtidos = new ArrayList<>();
                        for (Promocion promocion : promociones) {
                            if (promocion.getArticulosCarrito() != null && !promocion.getArticulosCarrito().isEmpty()) {
                                for (ArticuloCarrito compArticulo : promocion.getArticulosCarrito()) {
                                    DetalleSurtido surtido = new DetalleSurtido();
                                    surtido.setCodigoCabysSurtido(compArticulo.getArticulo().getCodigoCabys().getCodigo());
                                    surtido.setCantidadSurtido(compArticulo.getCantidad());
                                    surtido.setUnidadMedidaSurtido(compArticulo.getArticulo().getUnidadMedida());
                                    surtido.setDetalleSurtido(compArticulo.getArticulo().getNombre());
                                    surtido.setPrecioUnitarioSurtido(compArticulo.getPrecioEfectivo());
                                    surtido.setMontoTotalSurtido(compArticulo.getTotalArticulo());
                                    surtido.setSubTotalSurtido(compArticulo.getTotalArticulo());
                                    surtidos.add(surtido);
                                }
                            }
                        }
                        linea.setDetallesSurtidos(surtidos);
                    }
                }
                linea.setDescuentos(descuentos);
                List<Impuesto> impuestos = new ArrayList<>();
                if (!articulo.getTotalImpuesto().equals(BigDecimal.ZERO)) {
                    Impuesto impuesto = new Impuesto();
                    String codigoImpuesto = String.valueOf(articulo.getArticulo().getCodigoCabys().getImpuesto());
                    impuesto.setCodigo("01");
                    Tipo_TarifaIVA tarifa = Tipo_TarifaIVA.getTarifa(codigoImpuesto);
                    impuesto.setCodigoTarifaIVA(tarifa.getCodigo());
                    impuesto.setTarifa(new BigDecimal(codigoImpuesto));
                    impuesto.setMonto(articulo.getTotalImpuesto());
                    impuestoService.create(impuesto);
                    impuestos.add(impuesto);
                }
                OtroCargo otroCargo = new OtroCargo();
                otrosCargos.add(otroCargo);
                linea.setMontoTotalLinea(montoTotal);
                linea.setImpuestos(impuestos);
                lineaService.create(linea);
                linea.setDetalleServicio(detalles);
                lineasDetalle.add(linea);
            }
            detalles.setLineasDetalle(lineasDetalle);
            detalles.setOtrosCargos(otrosCargos);
            detalles.setStatus(true);
            return detalles;
        } catch (Exception e) {
alertasService.registrarAlerta("Error Detalles", "Error al crear detalles de tiquete: " + e.getMessage(), null, 0, "detallesTiqueteElectronico()", null, e.getMessage());
            return null;
        }

    }

    public Encabezado encabezadoTiqueteElectronico(AppSettings appSettings, Clients selectedClient) {
        try {
            if (!Objects.equals(appSettings.getEstatus(), Boolean.FALSE)) {
                Encabezado encabezado = new Encabezado();
                String codigoActividad = appSettings.getCodigoActividad();
                encabezado.setCodigoActividadEmisor(codigoActividad);
                encabezado.setProveedorSistemas(appSettings.getProvedor());
                // clave and numeroConsecutivo are set in crearComprobante after calling this method
                String numeroConsecutivo = "";
                encabezado.setNumeroConsecutivo(numeroConsecutivo);
                LocalDateTime emision = LocalDateTime.now().withNano(0);
                encabezado.setFechaEmision(emision);
                String condicionVenta = Tipo_CondicionVenta.CONTADO.getCodigo();
                encabezado.setCondicionVenta(condicionVenta);

                // PlazoCredito for credit terms
                if (Tipo_CondicionVenta.CREDITO.getCodigo().equals(encabezado.getCondicionVenta())
                    || Tipo_CondicionVenta.VENTA_CREDITO_IVA_HASTA_90_DIAS.getCodigo().equals(encabezado.getCondicionVenta())) {
                    encabezado.setPlazoCredito("30");
                }

                // CondicionVentaOtros when "99"
                if ("99".equals(encabezado.getCondicionVenta())) {
                    encabezado.setCondicionVentaOtros("Condicion de venta no especificada");
                }

                // Use configured document type (01=FE, 04=TE, etc.), default to TE if not set
                String docType = appSettings.getTipoDocumento();
                if (docType == null || docType.isEmpty()) {
                    docType = "04";
                }
                encabezado.setCodigoDocumento(docType);
                Emisor emisor = new Emisor();
                emisor.setNombre(appSettings.getNombre());
                IdentificacionEmisor emisorId = new IdentificacionEmisor();
                emisorId.setNumero(appSettings.getIdentificacion());
                emisorId.setTipo(appSettings.getTipoIdentificacion());
                emisor.setIdentificacion(emisorId);
                emisor.setNombreComercial(appSettings.getNombreNegocio());
                Ubicacion emisorUbicacion = new Ubicacion();
                emisorUbicacion.setProvincia(appSettings.getProvincia());
                emisorUbicacion.setCanton(appSettings.getCanton());
                emisorUbicacion.setDistrito(appSettings.getDistrito());
                emisorUbicacion.setBarrio(appSettings.getBarrio());
                emisorUbicacion.setOtrasSenas(appSettings.getDireccionCompleta());
                emisor.setUbicacion(emisorUbicacion);
                Telefono emisorTelefono = new Telefono();
                emisorTelefono.setCodigoPais(appSettings.getCodigoPais());
                emisorTelefono.setNumeroTelefono(appSettings.getTelefono());
                List<CorreoElectronicoEmisor> correosElectronicos = new ArrayList<>();

                if (appSettings.getCorreoElectronicoTributacion() != null && !appSettings.getCorreoElectronicoTributacion().trim().isEmpty()) {
                    CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                    correo.setCorreo(appSettings.getCorreoElectronicoTributacion());
                    correo.setEmisor(emisor);
                    correosElectronicos.add(correo);
                }

                if (appSettings.getCorreoElectronicoTributacion2() != null && !appSettings.getCorreoElectronicoTributacion2().trim().isEmpty()) {
                    CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                    correo.setCorreo(appSettings.getCorreoElectronicoTributacion2());
                    correo.setEmisor(emisor);
                    correosElectronicos.add(correo);
                }
                if (appSettings.getCorreoElectronicoTributacion3() != null && !appSettings.getCorreoElectronicoTributacion3().trim().isEmpty()) {
                    CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                    correo.setCorreo(appSettings.getCorreoElectronicoTributacion3());
                    correo.setEmisor(emisor);
                    correosElectronicos.add(correo);
                }
                if (appSettings.getCorreoElectronicoTributacion4() != null && !appSettings.getCorreoElectronicoTributacion4().trim().isEmpty()) {
                    CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                    correo.setCorreo(appSettings.getCorreoElectronicoTributacion4());
                    correo.setEmisor(emisor);
                    correosElectronicos.add(correo);
                }

                emisor.setCorreosElectronicos(correosElectronicos);
                encabezado.setEmisor(emisor);
                emisorService.create(emisor);
                Receptor receptor = new Receptor();
                if (selectedClient != null) {
                    if (selectedClient.getName() != null) {
                        receptor.setNombre(selectedClient.getName());
                        receptor.setNombreComercial(selectedClient.getName());
                        if (!"nacional".equals(selectedClient.getIdType().toLowerCase())) {
                            String idNumber = String.valueOf(selectedClient.getIdNumber());
                            receptor.setIdentificacionExtranjero(idNumber);
                        } else {
                            String idNumber = String.valueOf(selectedClient.getIdNumber());
                            IdentificacionReceptor id = new IdentificacionReceptor();
                            id.setNumero(idNumber);
                            id.setTipo(selectedClient.getTipoIdentificacion());
                            receptor.setIdentificacion(id);
                        }
                        encabezado.setReceptor(receptor);
                        receptorService.createIfNotExist(receptor);
                    }
                }

                // CodigoActividadReceptor from selectedClient
                if (selectedClient != null && selectedClient.getCodigoActividadComercial() != null
                    && !selectedClient.getCodigoActividadComercial().trim().isEmpty()) {
                    encabezado.setCodigoActividadReceptor(selectedClient.getCodigoActividadComercial());
                }

                return encabezado;
            }
            return null;
        } catch (Exception e) {
 alertasService.registrarAlerta("Error Encabezado", "Error al crear encabezado de tiquete: " + e.getMessage(), null, 0, "encabezadoTiqueteElectronico()", null, e.getMessage());
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
            xml.append("<MensajeReceptor xmlns=\"https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/mensajeReceptor\">");
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
        } catch (Exception e) {
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

    public void enviarFacturaACliente(ComprobantesEmitidos tiqueteElectronico, Clients cliente, Users user, BigDecimal pago, BigDecimal vuelto) {
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
                new ArrayList<>(), cliente, user, pago, vuelto);
            String pdfUrl = pdfGenerator.getPdfUrl();
            if (pdfUrl == null || pdfUrl.isEmpty()) {
                alertasService.registrarAlerta("Error", "No se pudo generar PDF para envio", null, 0, "ComprobanteService.enviarFacturaACliente()", null, null);
                return;
            }

            // Generate XML
            String xmlContent = HaciendaSigner.marshalComprobante(tiqueteElectronico);
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

        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error enviando factura a cliente: " + e.getMessage(), null, 0, "ComprobanteService.enviarFacturaACliente()", null, e.getMessage());
        }
    }
}
