package Services;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Detalles.CodigoComercial;
import Models.Detalles.Descuento;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Detalles.OtroCargo;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Fax;
import Models.Encabezado.IdentificacionEmisor;
import Models.Encabezado.IdentificacionReceptor;
import Models.Encabezado.MedioPago;
import Models.Encabezado.Receptor;
import Models.Encabezado.Telefono;
import Models.Encabezado.Ubicacion;
import Models.Resumen.ResumenFactura;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Enums.Tipo_CondicionVenta;
import Models.Enums.Tipo_MedioPago;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import Services.EmailService;
import Services.AppSettingsService;
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

    public static class CrearComprobanteResult {
        public ComprobantesEmitidos comprobante;
        public boolean haciendaEnviado;
        public String haciendaMensaje;
    }

    public CrearComprobanteResult crearComprobante(AppSettings appSettings, List<ArticuloCarrito> carrito, Clients selectedClient, Clients cliente, Users currentUser) {
        CrearComprobanteResult result = new CrearComprobanteResult();
        result.haciendaEnviado = false;
        
        try {
            // Generate consecutive number
            int consecutivo = (appSettings.getUltimoConsecutivo() != null ? appSettings.getUltimoConsecutivo() : 0) + 1;
            appSettings.setUltimoConsecutivo(consecutivo);
            String numeroConsecutivo = String.format("%s%s%010d",
                appSettings.getCodigoSucursal() != null ? appSettings.getCodigoSucursal() : "001",
                appSettings.getCodigoTerminal() != null ? appSettings.getCodigoTerminal() : "001",
                consecutivo);

            Encabezado encabezado = encabezadoTiqueteElectronico(appSettings, selectedClient);
            encabezado.setNumeroConsecutivo(numeroConsecutivo);
            
            // Generate the Hacienda document key
            String securityCode = String.format("%08d", (int)(Math.random() * 100000000));
            String clave = haciendaSigner.generateInvoiceKey(
                appSettings.getIdentificacion(),
                "01", // Factura electronica
                appSettings.getCodigoSucursal() != null ? appSettings.getCodigoSucursal() : "001",
                appSettings.getCodigoTerminal() != null ? appSettings.getCodigoTerminal() : "001",
                String.format("%010d", consecutivo),
                securityCode
            );
            encabezado.setClave(clave);
            
            encabezadoService.create(encabezado);
            DetalleServicio detalles = detallesTiqueteElectronico(carrito);
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
            
            // Persist the comprobante
            comprobantesEmitidosService.createAndReturn(tiqueteElectronico);

            // Try to sign and submit to Hacienda
            try {
                JAXBContext context = JAXBContext.newInstance(ComprobantesEmitidos.class);
                Marshaller marshaller = context.createMarshaller();
                marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
                StringWriter sw = new StringWriter();
                marshaller.marshal(tiqueteElectronico, sw);
                String xmlContent = sw.toString();
                
                HaciendaSigner.SignResult signResult = haciendaSigner.signXml(xmlContent);
                if (signResult.success) {
                    HaciendaApiService.ApiResponse apiResponse = haciendaApiService.sendInvoice(clave, signResult.signedXml);
                    if (apiResponse.isSuccess()) {
                        tiqueteElectronico.setHaciendaEstado("ENVIADO");
                        tiqueteElectronico.setHaciendaFechaEnvio(LocalDateTime.now());
                        if (encabezado != null) encabezado.setEstado("ENVIADO");
                        result.haciendaEnviado = true;
                        result.haciendaMensaje = "Factura enviada a Hacienda exitosamente";
                        alertasService.registrarAlerta("Hacienda", "Factura " + numeroConsecutivo + " enviada a Hacienda", currentUser, 0, "crearComprobante()", null, null);
                    } else {
                        encabezado.setEstado("RECHAZADO");
                        encabezado.setMotivoRechazo(apiResponse.errorMessage);
                        result.haciendaMensaje = "Hacienda rechazo la factura: " + apiResponse.errorMessage;
                        alertasService.registrarAlerta("Hacienda", "Hacienda rechazo factura " + numeroConsecutivo + ": " + apiResponse.errorMessage, currentUser, 0, "crearComprobante()", null, apiResponse.errorMessage);
                    }
                } else {
                    result.haciendaMensaje = "Error al firmar XML: " + signResult.errorMessage;
                    alertasService.registrarAlerta("Hacienda", "Error al firmar factura " + numeroConsecutivo + ": " + signResult.errorMessage, currentUser, 0, "crearComprobante()", null, signResult.errorMessage);
                }
            } catch (Exception e) {
                result.haciendaMensaje = "Error de comunicacion con Hacienda: " + e.getMessage();
                alertasService.registrarAlerta("Hacienda", "Error al enviar factura " + numeroConsecutivo + " a Hacienda: " + e.getMessage(), currentUser, 0, "crearComprobante()", null, e.getMessage());
                // Don't block the sale — Hacienda submission is best-effort
            }
            
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
            resumen.setTotalGravado(totalMercanciasGravadas);
            resumen.setTotalExento(totalExento);
            resumen.setTotalExonerado(totalExonerado);
            resumen.setTotalVenta(totalVenta);
            resumen.setTotalDescuentos(totalDescuentos);
            resumen.setTotalVentaNeta(totalVentaNeta);
            resumen.setTotalImpuesto(totalImpuesto);
            resumen.setTotalIVADevuelto(totalIVADevuelto);
            resumen.setTotalOtrosCargos(totalOtrosCargos);
            resumen.setTotalComprobante(totalComprobante);
            return resumen;
        } catch (Exception e) {
alertasService.registrarAlerta("Error Resumen", "Error al crear resumen de tiquete: " + e.getMessage(), null, 0, "resumenTiqueteElectronico()", null, e.getMessage());
            return null;
        }

    }

    public DetalleServicio detallesTiqueteElectronico(List<ArticuloCarrito> carrito) {
        try {
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
                var precioUnitario = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();
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
                            descuento.setNaturalezaDescuento(promocion.getNombre());
                            descuentoService.create(descuento);
                            descuentos.add(descuento);
                        }
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
                String clave = "";
                encabezado.setClave(clave);
                String numeroConsecutivo = "";
                encabezado.setNumeroConsecutivo(numeroConsecutivo);
                LocalDateTime emision = LocalDateTime.now().withNano(0);
                encabezado.setFechaEmision(emision);
                String condicionVenta = Tipo_CondicionVenta.OTROS.getCodigo();
                encabezado.setCondicionVenta(condicionVenta);
                String plazoCredito = "";
                encabezado.setPlazoCredito(plazoCredito);
                List<MedioPago> medioPago = new ArrayList<>();
                MedioPago medio = new MedioPago();
                medio.setMedioPago(Tipo_MedioPago.EFECTIVO.getCodigo());
                medio.setComprobante(encabezado);
                medioPago.add(medio);
                encabezado.setMedioPago(medioPago);
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
                Fax emisorFax = new Fax();
                emisorFax.setCodigoPais(appSettings.getCodigoPaisFax());
                emisorFax.setNumeroFax(appSettings.getTelefonoFax());
                List<CorreoElectronicoEmisor> correosElectronicos = new ArrayList<>();
                CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                correo.setCorreo(appSettings.getCorreoElectronicoTributacion());
                correo.setEmisor(emisor);
                correosElectronicos.add(correo);
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
                return encabezado;
            }
            return null;
        } catch (Exception e) {
 alertasService.registrarAlerta("Error Encabezado", "Error al crear encabezado de tiquete: " + e.getMessage(), null, 0, "encabezadoTiqueteElectronico()", null, e.getMessage());
            return null;
        }
     }

    public String generateMensajeReceptorXml(AppSettings settings, String clave, String numeroCedulaReceptor, 
                                              LocalDateTime fechaEmisionDoc, int codigoMensaje, String detalleMensaje,
                                              BigDecimal montoTotalImpuesto, BigDecimal montoTotalFactura) {
        try {
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            xml.append("<MensajeReceptor xmlns=\"https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/mensajeReceptor\">");
            xml.append("<Clave>").append(clave).append("</Clave>");
            xml.append("<NumeroCedulaReceptor>").append(numeroCedulaReceptor).append("</NumeroCedulaReceptor>");
            if (fechaEmisionDoc != null) {
                xml.append("<FechaEmisionDoc>").append(fechaEmisionDoc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("</FechaEmisionDoc>");
            }
            xml.append("<Mensaje>");
            xml.append("<CodigoMensaje>").append(codigoMensaje).append("</CodigoMensaje>");
            if (detalleMensaje != null && !detalleMensaje.isEmpty()) {
                xml.append("<DetalleMensaje>").append(escapeXml(detalleMensaje)).append("</DetalleMensaje>");
            }
            if (montoTotalImpuesto != null) {
                xml.append("<MontoTotalImpuesto>").append(montoTotalImpuesto.toPlainString()).append("</MontoTotalImpuesto>");
            }
            if (montoTotalFactura != null) {
                xml.append("<MontoTotalFactura>").append(montoTotalFactura.toPlainString()).append("</MontoTotalFactura>");
            }
            xml.append("</Mensaje>");
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
