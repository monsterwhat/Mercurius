package Utils.Parsers;

import Controllers.SessionController;
import Models.ComprobantesV44.ComprobantesRecibidos;
import Models.ComprobantesV44.ComprobantesEmitidos;
import Models.ComprobantesV44.Detalles.CodigoComercial;
import Models.ComprobantesV44.Detalles.Descuento;
import Models.ComprobantesV44.Detalles.DetalleServicio;
import Models.ComprobantesV44.Detalles.Impuesto;
import Models.ComprobantesV44.Detalles.LineaDetalle;
import Models.ComprobantesV44.Encabezado.CorreoElectronicoEmisor;
import Models.ComprobantesV44.Encabezado.Emisor;
import Models.ComprobantesV44.Encabezado.Encabezado;
import Models.ComprobantesV44.Encabezado.Fax;
import Models.ComprobantesV44.Encabezado.IdentificacionEmisor;
import Models.ComprobantesV44.Encabezado.IdentificacionReceptor;
import Models.ComprobantesV44.Encabezado.MedioPago;
import Models.ComprobantesV44.Encabezado.Receptor;
import Models.ComprobantesV44.Encabezado.Telefono;
import Models.ComprobantesV44.Encabezado.Ubicacion;
import Models.ComprobantesV44.Enums.Tipo_MedioPago;
import Models.ComprobantesV44.Resumen.CodigoTipoMoneda;
import Models.ComprobantesV44.Resumen.ResumenFactura;
import Services.ComprobantesRecibidosService;
import Services.ComprobantesEmitidosService;
import Services.Facturas.DescuentoService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.EmisorService;
import Services.Facturas.EncabezadoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import Services.Facturas.ReceptorService;
import Services.Facturas.ResumenFacturaService;
import Services.AlertasService;
import Models.Registros.Alertas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Al
 */
@ApplicationScoped
public class Parser {

    private static final Object lock = new Object();

    @Inject
    DetalleServicioService detalleServicioService;
    @Inject
    EmisorService emisorService;
    @Inject
    ComprobantesRecibidosService facturaService;
    @Inject
    ComprobantesEmitidosService comprobantesEmitidosService;
    @Inject
    ImpuestoService impuestoService;
    @Inject
    DescuentoService descuentoService;
    @Inject
    ReceptorService receptorService;
    @Inject
    ResumenFacturaService resumenFacturaService;
    @Inject
    EncabezadoService encabezadoService;
    @Inject
    SessionController currentSession;
    @Inject
    LineaDetalleService lineaDetalleService;
    @Inject
    AlertasService alertasService;

    public ComprobantesRecibidos parseComprobanteXML(File xmlFile) {
        try {
            JAXBContext context = JAXBContext.newInstance(ComprobantesRecibidos.class);

            // Create an unmarshaller instance
            Unmarshaller unmarshaller = context.createUnmarshaller();

            // Parse the XML file into the ComprobantesRecibidos object
            return (ComprobantesRecibidos) unmarshaller.unmarshal(xmlFile);
        } catch (JAXBException e) {
            System.out.println("Error!" + e.getLocalizedMessage());
            return null;
        }
    }

    public LocalDateTime parseFechaEmision(String fechaEmision) {
        try {
            if (fechaEmision == null || fechaEmision.isEmpty()) {
                return null;
            }

            DateTimeFormatter[] formatters = {
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME
            };

            for (DateTimeFormatter formatter : formatters) {
                try {
                    return LocalDateTime.parse(fechaEmision, formatter);
                } catch (Exception ex) {
                    // Ignore and try the next format
                }
            }

            // If no format works, throw an exception
            return null;
        } catch (Exception e) {
            System.out.println("Error parsing fecha emision: " + e.getMessage());
            return null;
        }
    }

    public Emisor parseEmisor(JsonNode emisorNode) {
        try {
            String nombre = emisorNode.path("Nombre").asText();
            String identificacionTipo = emisorNode.path("Identificacion").path("Tipo").asText();
            String identificacionNumero = emisorNode.path("Identificacion").path("Numero").asText();
            String nombreComercial = emisorNode.path("NombreComercial").asText();
            Ubicacion ubicacion = new Ubicacion();
            Telefono telefono = new Telefono();
            Fax fax = new Fax();
            // Parse Ubicacion si existe
            if (!emisorNode.path("Ubicacion").isMissingNode()) {
                ubicacion = parseUbicacion(emisorNode.path("Ubicacion"));
                if (ubicacion == null) {
                    return null;
                }
            }
            // Parse Telefono si existe
            if (!emisorNode.path("Telefono").isMissingNode()) {
                telefono = parseTelefono(emisorNode.path("Telefono"));
                if (telefono == null) {
                    return null;
                }
            }
            // Parse Fax si existe
            if (!emisorNode.path("Fax").isMissingNode()) {
                fax = parseFax(emisorNode.path("Fax"));
                if (fax == null) {
                    return null;
                }
            }

            Emisor emisor = new Emisor();
            emisor.setNombre(nombre);

            List<CorreoElectronicoEmisor> correosElectronicos = new ArrayList<>();
            List<String> Correos = parseEmail(emisorNode.path("CorreoElectronico"));
            for (String Correo : Correos) {
                CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                correo.setCorreo(Correo);
                correo.setEmisor(emisor);
                correosElectronicos.add(correo);
            }

            IdentificacionEmisor idEmisor = new IdentificacionEmisor();
            idEmisor.setNumero(identificacionNumero);
            idEmisor.setTipo(identificacionTipo);

            emisor.setIdentificacion(idEmisor);
            emisor.setNombreComercial(nombreComercial);
            emisor.setUbicacion(ubicacion);
            emisor.setTelefono(telefono);
            emisor.setFax(fax);
            emisor.setCorreosElectronicos(correosElectronicos);

            return emisor;
        } catch (Exception e) {
            System.out.println("Error Parsing Emisor: " + e.getLocalizedMessage());
            return null;
        }
    }

    public Receptor parseReceptor(JsonNode receptorNode) {
        try {
            String nombre = receptorNode.path("Nombre").asText();
            String identificacionTipo = receptorNode.path("Identificacion").path("Tipo").asText();
            String identificacionNumero = receptorNode.path("Identificacion").path("Numero").asText();
            String nombreComercial = receptorNode.path("NombreComercial").asText();
            Ubicacion ubicacion = new Ubicacion();
            Telefono telefono = new Telefono();
            Fax fax = new Fax();

            if (!receptorNode.path("Ubicacion").isMissingNode()) {
                ubicacion = parseUbicacion(receptorNode.path("Ubicacion"));
            }
            if (!receptorNode.path("Telefono").isMissingNode()) {
                telefono = parseTelefono(receptorNode.path("Telefono"));
            }
            if (!receptorNode.path("Fax").isMissingNode()) {
                fax = parseFax(receptorNode.path("Fax"));
            }

            String correoElectronico = receptorNode.path("CorreoElectronico").asText();

            Receptor receptor = new Receptor();
            receptor.setNombre(nombre);

            IdentificacionReceptor idReceptor = new IdentificacionReceptor();
            idReceptor.setNumero(identificacionNumero);
            idReceptor.setTipo(identificacionTipo);

            receptor.setIdentificacion(idReceptor);
            receptor.setNombreComercial(nombreComercial);
            receptor.setUbicacion(ubicacion);
            receptor.setTelefono(telefono);
            receptor.setFax(fax);
            receptor.setCorreoElectronico(correoElectronico);

            return receptor;
        } catch (Exception e) {
            System.out.println("Error parsing receptor: " + e.getLocalizedMessage());
            return null;
        }
    }

    public Ubicacion parseUbicacion(JsonNode ubicacionNode) {
        try {
            String provincia = ubicacionNode.path("Provincia").asText();
            String Canton = ubicacionNode.path("Canton").asText();
            String Distrito = ubicacionNode.path("Distrito").asText();
            String Barrio = ubicacionNode.path("Barrio").asText();
            String OtrasSenas = ubicacionNode.path("OtrasSenas").asText();

            Ubicacion parsedUbicacion = new Ubicacion();
            parsedUbicacion.setProvincia(provincia);
            parsedUbicacion.setCanton(Canton);
            parsedUbicacion.setDistrito(Distrito);
            parsedUbicacion.setBarrio(Barrio);
            parsedUbicacion.setOtrasSenas(OtrasSenas);

            return parsedUbicacion;

        } catch (Exception e) {
            System.out.println("Error parsing ubicacion: " + e.getLocalizedMessage());
            return null;
        }
    }

    public Telefono parseTelefono(JsonNode telefonoNode) {
        try {
            String codigoPais = telefonoNode.path("CodigoPais").asText();
            String numTelefono = telefonoNode.path("NumTelefono").asText();

            Telefono telefono = new Telefono();
            telefono.setCodigoPais(codigoPais);
            telefono.setNumeroTelefono(numTelefono);

            return telefono;
        } catch (Exception e) {
            System.out.println("Error parsing telefono: " + e.getLocalizedMessage());
            return null;
        }
    }

    public Fax parseFax(JsonNode faxNode) {
        try {
            String codigoPais = faxNode.path("CodigoPais").asText();
            String numTelefono = faxNode.path("NumFax").asText();

            Fax fax = new Fax();

            if (!codigoPais.isEmpty()) {
                fax.setCodigoPais(codigoPais);
            } else {
                fax.setCodigoPais(null);
            }

            if (!numTelefono.isEmpty()) {
                fax.setNumeroFax(numTelefono);
            } else {
                fax.setNumeroFax(null);
            }

            return fax;
        } catch (Exception e) {
            System.out.println("Error parsing fax: " + e.getLocalizedMessage());
            return null;
        }
    }

    public List<String> parseEmail(JsonNode emailNode) {
        try {
            List<String> emails = new ArrayList<>();
            if (emailNode.isArray()) {
                for (JsonNode email : emailNode) {
                    String emailAddress = email.asText();
                    emails.add(emailAddress);
                }
            } else if (!emailNode.isMissingNode()) {
                String emailAddress = emailNode.asText();
                emails.add(emailAddress);
            }
            return emails;
        } catch (Exception e) {
            System.out.println("Error parsing emails: " + e.getLocalizedMessage());
            return null;
        }
    }

    public List<LineaDetalle> parseDetalleServicio(JsonNode detalleServicio) {
        try {
            List<LineaDetalle> lineasDetalle = new ArrayList<>();

            JsonNode lineasDetalleNode = detalleServicio.path("LineaDetalle");
            if (lineasDetalleNode.isArray()) {
                for (JsonNode lineaDetalleNode : lineasDetalleNode) {
                    LineaDetalle lineaDetalle = parseLineaDetalle(lineaDetalleNode);
                    if (lineaDetalle != null) {
                        lineasDetalle.add(lineaDetalle);
                    }
                }
            } else if (!lineasDetalleNode.isMissingNode()) {
                LineaDetalle lineaDetalle = parseLineaDetalle(lineasDetalleNode);
                if (lineaDetalle != null) {
                    lineasDetalle.add(lineaDetalle);
                }
            }

            return lineasDetalle;
        } catch (Exception e) {
            System.out.println("Error parsing detalle Servicio: " + e.getLocalizedMessage());
            return null;
        }

    }

    public List<MedioPago> parseMedioPago(JsonNode medioPagoNode, Encabezado comprobante) {
        try {
            List<MedioPago> mediosPago = new ArrayList<>();

            if (medioPagoNode.isArray()) {
                for (JsonNode medioPago : medioPagoNode) {
                    var medioEnum = Tipo_MedioPago.fromCodigo(medioPago.asText());
                    MedioPago medioDePago = new MedioPago();
                    medioDePago.setMedioPago(medioEnum.getCodigo());
                    medioDePago.setComprobante(comprobante);
                    mediosPago.add(medioDePago);
                }
            } else if (!medioPagoNode.isMissingNode()) {
                var medioEnum = Tipo_MedioPago.fromCodigo(medioPagoNode.asText());
                MedioPago medioDePago = new MedioPago();
                medioDePago.setMedioPago(medioEnum.getCodigo());
                medioDePago.setComprobante(comprobante);
                mediosPago.add(medioDePago);
            }

            return mediosPago;
        } catch (Exception e) {
            System.out.println("Error parsing medio pago: " + e.getLocalizedMessage());
            return null;
        }
    }

    public LineaDetalle parseLineaDetalle(JsonNode lineaDetalleNode) {
        try {
            LineaDetalle lineaDetalle = new LineaDetalle();

            int numeroLinea = lineaDetalleNode.path("NumeroLinea").asInt();
            String codigo = lineaDetalleNode.path("Codigo").asText();

            List<CodigoComercial> codigosComerciales = new ArrayList<>();
            // Parse multiple CodigoComercial if present
            if (lineaDetalleNode.path("CodigoComercial").isArray()) {
                for (JsonNode codigoComercialNode : lineaDetalleNode.path("CodigoComercial")) {
                    CodigoComercial codigoComercial = new CodigoComercial();
                    codigoComercial.setLineaDetalle(lineaDetalle);
                    codigoComercial = parseCodigoComercial(codigoComercialNode);
                    if (codigoComercial != null) {
                        codigosComerciales.add(codigoComercial);
                    }
                }
            } else if (!lineaDetalleNode.path("CodigoComercial").isMissingNode()) {
                CodigoComercial codigoComercial = new CodigoComercial();
                codigoComercial.setLineaDetalle(lineaDetalle);
                codigoComercial = parseCodigoComercial(lineaDetalleNode.path("CodigoComercial"));
                if (codigoComercial != null) {
                    codigosComerciales.add(codigoComercial);
                }
            }

            String cantidad = lineaDetalleNode.path("Cantidad").asText();
            String unidadMedida = lineaDetalleNode.path("UnidadMedida").asText();
            String unidadMedidaComercial = lineaDetalleNode.path("UnidadMedidaComercial").asText();
            String detalle = lineaDetalleNode.path("Detalle").asText();
            String precioUnitario = lineaDetalleNode.path("PrecioUnitario").asText();
            String montoTotal = lineaDetalleNode.path("MontoTotal").asText();
            String subTotal = lineaDetalleNode.path("SubTotal").asText();
            List<Impuesto> impuestos = new ArrayList<>();
            List<Descuento> descuentos = new ArrayList<>();

            // Parse Impuesto if present
            if (lineaDetalleNode.path("Impuesto").isArray()) {
                for (JsonNode ImpuestoNode : lineaDetalleNode.path("Impuesto")) {
                    Impuesto impuesto = new Impuesto();
                    impuesto = parseImpuesto(ImpuestoNode);
                    if (impuesto != null) {
                        impuestos.add(impuesto);
                    }
                }
            } else if (!lineaDetalleNode.path("Impuesto").isMissingNode()) {
                Impuesto impuesto = new Impuesto();
                impuesto = parseImpuesto(lineaDetalleNode.path("Impuesto"));
                if (impuesto != null) {
                    impuestos.add(impuesto);
                }
            }

            // Parse Descuento if present
            if (lineaDetalleNode.path("Descuento").isArray()) {
                for (JsonNode DescuentoNode : lineaDetalleNode.path("Descuento")) {
                    Descuento descuento = new Descuento();
                    descuento = parseDescuento(DescuentoNode);
                    if (descuento != null) {
                        descuentos.add(descuento);
                    }
                }
            } else if (!lineaDetalleNode.path("Descuento").isMissingNode()) {
                Descuento descuento = new Descuento();
                descuento = parseDescuento(lineaDetalleNode.path("Descuento"));
                if (descuento != null) {
                    descuentos.add(descuento);
                }
            }

            for (CodigoComercial codigoComercial : codigosComerciales) {
                codigoComercial.setLineaDetalle(lineaDetalle);
            }

            for (Impuesto impuesto : impuestos) {
                impuesto.setLineaDetalle(lineaDetalle);
            }

            for (Descuento descuento : descuentos) {
                descuento.setLineaDetalle(lineaDetalle);
            }

            String montoTotalLinea = lineaDetalleNode.path("MontoTotalLinea").asText();

            lineaDetalle.setNumeroLinea(numeroLinea);
            lineaDetalle.setCodigoCabys(codigo);
            lineaDetalle.setCodigosComerciales(codigosComerciales);
            lineaDetalle.setCantidad(new BigDecimal(cantidad));
            lineaDetalle.setUnidadMedida(unidadMedida);
            lineaDetalle.setUnidadMedidaComercial(unidadMedidaComercial);
            lineaDetalle.setDetalle(detalle);
            lineaDetalle.setPrecioUnitario(new BigDecimal(precioUnitario));
            lineaDetalle.setMontoTotal(new BigDecimal(montoTotal));
            lineaDetalle.setDescuentos(descuentos);
            lineaDetalle.setSubTotal(new BigDecimal(subTotal));
            lineaDetalle.setImpuestos(impuestos);
            lineaDetalle.setMontoTotalLinea(new BigDecimal(montoTotalLinea));

            return lineaDetalle;
        } catch (Exception e) {
            System.out.println("Error parsing linea detalle: " + e.getLocalizedMessage());
            return null;
        }
    }

    public CodigoComercial parseCodigoComercial(JsonNode codigoComercialNode) {
        try {
            String tipo = codigoComercialNode.path("Tipo").asText();
            String codigo = codigoComercialNode.path("Codigo").asText();

            CodigoComercial codigoComercial = new CodigoComercial();
            codigoComercial.setTipo(tipo);
            codigoComercial.setCodigo(codigo);

            return codigoComercial;
        } catch (Exception e) {
            System.out.println("Error parsing codigo comercial: " + e.getLocalizedMessage());
            return null;
        }
    }

    public Impuesto parseImpuesto(JsonNode impuestoNode) {
        try {
            String codigo = impuestoNode.path("Codigo").asText();
            String codigoTarifa = impuestoNode.path("CodigoTarifa").asText();
            String tarifa = impuestoNode.path("Tarifa").asText();
            String monto = impuestoNode.path("Monto").asText();

            Impuesto impuesto = new Impuesto();
            impuesto.setCodigo(codigo);
            impuesto.setCodigoTarifaIVA(codigoTarifa);
            impuesto.setTarifa(new BigDecimal(tarifa.trim()));
            impuesto.setMonto(new BigDecimal(monto.trim()));

            return impuesto;
        } catch (Exception e) {
            System.out.println("Error parsing impuestos: " + e.getLocalizedMessage());
            return null;
        }
    }

    public ResumenFactura parseResumenFactura(JsonNode resumenFacturaNode) {
        try {
            JsonNode codigoMonedaPath = resumenFacturaNode.path("CodigoTipoMoneda");
            String codigoMonedaText = codigoMonedaPath.path("CodigoMoneda").asText();
            String tipoCambioText = codigoMonedaPath.path("TipoCambio").asText();

            CodigoTipoMoneda codigoMoneda = parseCodigoMoneda(codigoMonedaText, tipoCambioText);
            if (codigoMoneda == null) {
                System.out.println("Error parsing moneda");
                return null;
            }

            // Extracting values
            String totalServiciosGravados = resumenFacturaNode.path("TotalServGravados").asText();
            String totalServiciosExentos = resumenFacturaNode.path("TotalServExentos").asText();
            String totalServiciosExonerados = resumenFacturaNode.path("TotalServExonerado").asText();
            String totalMercanciasGravadas = resumenFacturaNode.path("TotalMercanciasGravadas").asText();
            String totalMercanciasExentas = resumenFacturaNode.path("TotalMercanciasExentas").asText();
            String totalMercanciaExonerada = resumenFacturaNode.path("TotalMercExonerada").asText();
            String totalGravado = resumenFacturaNode.path("TotalGravado").asText();
            String totalExento = resumenFacturaNode.path("TotalExento").asText();
            String totalExonerado = resumenFacturaNode.path("TotalExonerado").asText();
            String totalVenta = resumenFacturaNode.path("TotalVenta").asText();
            String totalDescuentos = resumenFacturaNode.path("TotalDescuentos").asText();
            String totalVentaNeta = resumenFacturaNode.path("TotalVentaNeta").asText();
            String totalImpuesto = resumenFacturaNode.path("TotalImpuesto").asText();
            String totalIVADevuelto = resumenFacturaNode.path("TotalIVADevuelto").asText();
            String totalOtrosCargos = resumenFacturaNode.path("TotalOtrosCargos").asText();
            String totalComprobante = resumenFacturaNode.path("TotalComprobante").asText();

            // Check for null or empty values before converting to BigDecimal
            BigDecimal bigDecimalTotalServiciosGravados = totalServiciosGravados.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalServiciosGravados);
            BigDecimal bigDecimalTotalServiciosExentos = totalServiciosExentos.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalServiciosExentos);
            BigDecimal bigDecimalTotalServiciosExonerados = totalServiciosExonerados.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalServiciosExonerados);
            BigDecimal bigDecimalTotalMercanciasGravadas = totalMercanciasGravadas.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciasGravadas);
            BigDecimal bigDecimalTotalMercanciasExentas = totalMercanciasExentas.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciasExentas);
            BigDecimal bigDecimalTotalMercanciaExonerada = totalMercanciaExonerada.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciaExonerada);
            BigDecimal bigDecimalTotalGravado = totalGravado.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalGravado);
            BigDecimal bigDecimalTotalExento = totalExento.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalExento);
            BigDecimal bigDecimalTotalExonerado = totalExonerado.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalExonerado);
            BigDecimal bigDecimalTotalVenta = totalVenta.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalVenta);
            BigDecimal bigDecimalTotalDescuentos = totalDescuentos.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalDescuentos);
            BigDecimal bigDecimalTotalVentaNeta = totalVentaNeta.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalVentaNeta);
            BigDecimal bigDecimalTotalImpuesto = totalImpuesto.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalImpuesto);
            BigDecimal bigDecimalTotalIVADevuelto = totalIVADevuelto.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalIVADevuelto);
            BigDecimal bigDecimalTotalOtrosCargos = totalOtrosCargos.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalOtrosCargos);
            BigDecimal bigDecimalTotalComprobante = totalComprobante.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalComprobante);

            ResumenFactura resumenFactura = new ResumenFactura();

            resumenFactura.setCodigoMoneda(codigoMoneda);
            resumenFactura.setTotalServGravados(bigDecimalTotalServiciosGravados);
            resumenFactura.setTotalServExentos(bigDecimalTotalServiciosExentos);
            resumenFactura.setTotalServExonerado(bigDecimalTotalServiciosExonerados);
            resumenFactura.setTotalMercanciasGravadas(bigDecimalTotalMercanciasGravadas);
            resumenFactura.setTotalMercanciasExentas(bigDecimalTotalMercanciasExentas);
            resumenFactura.setTotalMercExonerada(bigDecimalTotalMercanciaExonerada);
            resumenFactura.setTotalGravado(bigDecimalTotalGravado);
            resumenFactura.setTotalExento(bigDecimalTotalExento);
            resumenFactura.setTotalExonerado(bigDecimalTotalExonerado);
            resumenFactura.setTotalVenta(bigDecimalTotalVenta);
            resumenFactura.setTotalDescuentos(bigDecimalTotalDescuentos);
            resumenFactura.setTotalVentaNeta(bigDecimalTotalVentaNeta);
            resumenFactura.setTotalImpuesto(bigDecimalTotalImpuesto);
            resumenFactura.setTotalIVADevuelto(bigDecimalTotalIVADevuelto);
            resumenFactura.setTotalOtrosCargos(bigDecimalTotalOtrosCargos);
            resumenFactura.setTotalComprobante(bigDecimalTotalComprobante);

            return resumenFactura;

        } catch (Exception e) {
            System.out.println("Error parsing resumen factura: " + e.getLocalizedMessage());
            return null;
        }
    }

    public CodigoTipoMoneda parseCodigoMoneda(String codigo, String tipo) {
        try {
            CodigoTipoMoneda codigoMoneda = new CodigoTipoMoneda();
            codigoMoneda.setCodigoMoneda(codigo);
            codigoMoneda.setTipoCambioMoneda(new BigDecimal(tipo));

            return codigoMoneda;
        } catch (Exception e) {
            System.out.println("Error parsing codigo moneda: " + e.getLocalizedMessage());
            return null;
        }
    }

    public Descuento parseDescuento(JsonNode descuentoNode) {
        try {
            String montoDescuento = descuentoNode.path("MontoDescuento").asText();
            String naturalezaDescuento = descuentoNode.path("NaturalezaDescuento").asText();

            Descuento descuento = new Descuento();
            descuento.setMontoDescuento(new BigDecimal(montoDescuento));
            descuento.setNaturalezaDescuento(naturalezaDescuento);

            return descuento;
        } catch (Exception e) {
            System.out.println("Error parsing descuento: " + e.getLocalizedMessage());
            return null;
        }
    }

    public BigDecimal parseUnidadMedida(String unidad, String unidadComercial) {
        switch (unidad) {
            case "Otros":
                return parseUnidadComercial(unidadComercial);
            case "Unid":
                return parseUnidadComercial(unidadComercial);
            case "g":
                return parseUnidadComercial(unidadComercial);
            default:
                return BigDecimal.ZERO;
        }
    } // Default to 0 if no specific conversion found

    public BigDecimal parseUnidadComercial(String unidadComercial) {
        switch (unidadComercial) {
            case "BOT":
                return BigDecimal.ONE;
            case "LT":
                return BigDecimal.ONE;
            case "ST":
                return BigDecimal.valueOf(6);
            case "Pieza":
                return BigDecimal.ONE;
            case "UN":
                return BigDecimal.ONE;
            case "Unid":
                return BigDecimal.ONE;
            case "UND":
                return BigDecimal.ONE;
            case "":
                return BigDecimal.ONE;
            default:
                return BigDecimal.ZERO;
        }
    } // Default to 0 if no conversion defined

    /**
     * Utility method to extract NumeroConsecutivo from JSON tree with fallback
     * logic
     */
    private String extractNumeroConsecutivo(JsonNode rootNode) {
        // Try direct path first
        String numeroConsecutivo = rootNode.path("NumeroConsecutivo").asText();

        if (!numeroConsecutivo.isEmpty() && !"null".equals(numeroConsecutivo)) {
            return numeroConsecutivo;
        }

        // Try nested paths - some XML structures might have different nesting
        JsonNode encabezadoNode = rootNode.path("Encabezado");
        if (encabezadoNode != null && !encabezadoNode.isMissingNode()) {
            numeroConsecutivo = encabezadoNode.path("NumeroConsecutivo").asText();
            if (!numeroConsecutivo.isEmpty() && !"null".equals(numeroConsecutivo)) {
                return numeroConsecutivo;
            }
        }

        // Try other common XML structures
        JsonNode facturaNode = rootNode.path("FacturaElectronica");
        if (facturaNode != null && !facturaNode.isMissingNode()) {
            numeroConsecutivo = facturaNode.path("NumeroConsecutivo").asText();
            if (!numeroConsecutivo.isEmpty() && !"null".equals(numeroConsecutivo)) {
                return numeroConsecutivo;
            }
        }

        // Try to find in any child node
        for (JsonNode child : rootNode) {
            numeroConsecutivo = child.path("NumeroConsecutivo").asText();
            if (!numeroConsecutivo.isEmpty() && !"null".equals(numeroConsecutivo)) {
                return numeroConsecutivo;
            }
        }

        return ""; // Return empty if not found
    }

    /**
     * Utility method to log errors asynchronously using Alertas entity
     */
    private void logAsyncError(String level, String message, String source, String xmlContent) {
        try {
            String currentUser = Utils.AsyncUserContext.getCurrentUser() != null
                    ? Utils.AsyncUserContext.getCurrentUser() : "system";

            alertasService.registrarAlerta(
                    level,
                    message,
                    null, // No session user in virtual threads - use null
                    0, // codigo
                    source,
                    xmlContent.length() > 1000 ? xmlContent.substring(0, 1000) : xmlContent, // antes
                    null // despues
            );
        } catch (Exception e) {
            // Fallback to console logging if AlertasService fails
            System.err.println("Failed to log async error: " + e.getMessage());
            System.err.println("Original error [" + level + "]: " + message);
        }
    }

    /**
     * Utility method to handle duplicate key errors with retries
     */
    private boolean handleDuplicateKeyError(Exception e, String operation, int attempt) {
        if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
            if (attempt < 3) {
                try {
                    // Exponential backoff: 100ms, 200ms, 400ms
                    long delay = 100 * (1L << (attempt - 1));
                    System.out.println("Duplicate key detected in " + operation + ", retry attempt " + attempt + " after " + delay + "ms...");
                    Thread.sleep(delay);
                    return true; // Continue retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                System.err.println("Max retries reached for " + operation + ": " + e.getMessage());
                logAsyncError("ERROR", "Max retries reached for " + operation + ": " + e.getMessage(),
                        "Parser.handleDuplicateKeyError", "");
                return false;
            }
        }
        return false;
    }

    @Transactional
    public void parseXML(InputStream inputStream) {
        synchronized (lock) {
            StringBuilder xmlContent = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    xmlContent.append(line);
                }

                System.out.println("XML Content length: " + xmlContent.length());
                System.out.println("XML Content preview: " + xmlContent.substring(0, Math.min(200, xmlContent.length())));

                XmlMapper xmlMapper = new XmlMapper();
                JsonNode rootNode = xmlMapper.readTree(xmlContent.toString());

                System.out.println("Root node: " + rootNode.getNodeType());

                // Validate required fields first with improved NumeroConsecutivo extraction
// Detect document type first
                String documentType = rootNode.fieldNames().next();
                boolean isMensajeHacienda = "MensajeHacienda".equals(documentType);

                String numeroConsecutivo = extractNumeroConsecutivo(rootNode);
                String clave = rootNode.path("Clave").asText();

                System.out.println("Root node: " + documentType);
                System.out.println("NumeroConsecutivo: '" + numeroConsecutivo + "'");
                System.out.println("Clave: '" + clave + "'");

                // Solo validar NumeroConsecutivo para Factura/Nota, no para MensajeHacienda
                if (!isMensajeHacienda && numeroConsecutivo.isEmpty()) {
                    String errorMsg = "XML inválido: falta el número consecutivo";
                    System.err.println(errorMsg);
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML", xmlContent.toString());
                    return;
                }

                if (facturaService.findByNumeroConsecutivo(numeroConsecutivo)) {
                    System.out.println("Factura duplicada: " + numeroConsecutivo);
                    logAsyncError("WARN", "Factura duplicada: " + numeroConsecutivo, "Parser.parseXML", xmlContent.toString());
                    return;
                }

                Encabezado encabezado = new Encabezado();
                DetalleServicio detalles = new DetalleServicio();

                System.out.println("Parsing emisor...");
                Emisor emisor = parseEmisor(rootNode.path("Emisor"));
                if (emisor == null) {
                    String errorMsg = "XML inválido: error en datos del emisor";
                    System.err.println("Error parsing emisor");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.emisor", xmlContent.toString());
                    return;
                }

                System.out.println("Parsing receptor...");
                Receptor receptor = parseReceptor(rootNode.path("Receptor"));
                if (receptor == null) {
                    String errorMsg = "XML inválido: error en datos del receptor";
                    System.err.println("Error parsing receptor");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.receptor", xmlContent.toString());
                    return;
                }

                String codigoActividad = rootNode.path("CodigoActividad").asText();
                String fechaEmisionStr = rootNode.path("FechaEmision").asText();
                System.out.println("FechaEmision: '" + fechaEmisionStr + "'");

                LocalDateTime localDateTime = parseFechaEmision(fechaEmisionStr);
                if (localDateTime == null) {
                    String errorMsg = "XML inválido: error en fecha de emisión";
                    System.err.println("Error parsing fecha emision");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.fechaEmision", xmlContent.toString());
                    return;
                }

                String condicionVenta = rootNode.path("CondicionVenta").asText();
                String plazoCredito = rootNode.path("PlazoCredito").asText();
                List<MedioPago> medioPago = parseMedioPago(rootNode.path("MedioPago"), encabezado);
                if (medioPago == null) {
                    String errorMsg = "XML inválido: error en medio de pago";
                    System.err.println("Error parsing medio pago");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.medioPago", xmlContent.toString());
                    return;
                }

                System.out.println("Parsing resumen factura...");
                ResumenFactura resumenFactura = parseResumenFactura(rootNode.path("ResumenFactura"));
                if (resumenFactura == null) {
                    String errorMsg = "XML inválido: error en resumen de factura";
                    System.err.println("Error parsing resumen factura");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.resumenFactura", xmlContent.toString());
                    return;
                }

                System.out.println("Parsing detalle servicio...");
                List<LineaDetalle> lineas = parseDetalleServicio(rootNode.path("DetalleServicio"));
                if (lineas == null || lineas.isEmpty()) {
                    String errorMsg = "XML inválido: no se encontraron líneas de detalle";
                    System.err.println("Error parsing detalle servicio - no lines found");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.detalleServicio", xmlContent.toString());
                    return;
                }

                System.out.println("Found " + lineas.size() + " lineas detalle");

// Check if encabezado already exists by numeroConsecutivo (before creating any entities)
                // Use new method that handles duplicates properly
                boolean existsByNumeroConsecutivo = encabezadoService.existsByNumeroConsecutivo(numeroConsecutivo);

                if (existsByNumeroConsecutivo) {
                    System.out.println("Skipping existing encabezado for: " + numeroConsecutivo);
                    System.out.println("Factura already processed: " + numeroConsecutivo);
                    return; // Skip processing if already exists
                }

                // Clean up any existing duplicates before proceeding
                int duplicatesCleaned = encabezadoService.cleanDuplicateEncabezados(numeroConsecutivo);
                if (duplicatesCleaned > 0) {
                    System.out.println("Cleaned " + duplicatesCleaned + " duplicate encabezados for: " + numeroConsecutivo);
                }

                detalles.setLineasDetalle(lineas);
                lineas.forEach(linea -> linea.setDetalleServicio(detalles));

                encabezado.setCodigoActividadEmisor(codigoActividad);
                encabezado.setNumeroConsecutivo(numeroConsecutivo);
                encabezado.setFechaEmision(localDateTime);
                encabezado.setCondicionVenta(condicionVenta);
                encabezado.setPlazoCredito(plazoCredito);
                encabezado.setMedioPago(medioPago);
                encabezado.setClave(clave);

                System.out.println("Creating emisor and receptor...");
                Emisor persistedEmisor = emisorService.createIfNotExist(emisor);
                Receptor persistedReceptor = receptorService.createIfNotExist(receptor);

                if (persistedEmisor != null) {
                    encabezado.setEmisor(persistedEmisor);
                } else {
                    System.out.println("Failed to create/find emisor");
                    return;
                }
                if (persistedReceptor != null) {
                    encabezado.setReceptor(persistedReceptor);
                } else {
                    System.out.println("Failed to create/find receptor");
                    return;
                }

                System.out.println("Creating detalle servicio...");

                // Set the lineasDetalle relationship before persisting
                detalles.setLineasDetalle(lineas);

                System.out.println("Creating resumen factura and encabezado...");

// For received documents, we don't create ComprobantesEmitidos
                // Only ComprobantesRecibidos should be created for uploaded XML files
                // This fixes the unique constraint violation issue
                
System.out.println("Creating comprobante recibido...");
                
                System.out.println("Creating comprobante recibido...");
                ComprobantesRecibidos factura = new ComprobantesRecibidos();
                factura.setEncabezado(encabezado);
                factura.setResumen(resumenFactura);
                factura.setUser(Utils.AsyncUserContext.getCurrentUser() != null ? Utils.AsyncUserContext.getCurrentUser() : "system");
                factura.setStatus(true);
                factura.setProcessed(false);

                // Create a new DetalleServicio for the Recibidos entity
                DetalleServicio nuevosDetalles = new DetalleServicio();
                nuevosDetalles.setStatus(true);
                
                // Copy lineasDetalle with proper bidirectional relationship
                List<LineaDetalle> nuevasLineas = new ArrayList<>();
                if (detalles.getLineasDetalle() != null) {
                    for (LineaDetalle original : detalles.getLineasDetalle()) {
                        LineaDetalle nueva = new LineaDetalle();
nueva.setCantidad(original.getCantidad());
                        nueva.setCodigoCabys(original.getCodigoCabys());
                        // codigosComerciales will be copied with deep copy below
                        nueva.setDetalle(original.getDetalle());
                        // descuentos will be copied with deep copy below
                        nueva.setMontoTotal(original.getMontoTotal()); 
                        nueva.setPrecioUnitario(original.getPrecioUnitario());
                        nueva.setSubTotal(original.getSubTotal());
                        nueva.setUnidadMedida(original.getUnidadMedida());
                        nueva.setUnidadMedidaComercial(original.getUnidadMedidaComercial());
                        nueva.setMontoTotalLinea(original.getMontoTotalLinea());
                        
// Copy collections with proper deep copies and bidirectional relationships
                        if (original.getCodigosComerciales() != null) {
                            List<CodigoComercial> newCodigosComerciales = new ArrayList<>();
                            for (CodigoComercial codigo : original.getCodigosComerciales()) {
                                CodigoComercial newCodigo = new CodigoComercial();
                                newCodigo.setTipo(codigo.getTipo());
                                newCodigo.setCodigo(codigo.getCodigo());
                                newCodigo.setLineaDetalle(nueva);
                                newCodigosComerciales.add(newCodigo);
                            }
                            nueva.setCodigosComerciales(newCodigosComerciales);
                        }
                        if (original.getDescuentos() != null) {
                            List<Descuento> newDescuentos = new ArrayList<>();
                            for (Descuento descuento : original.getDescuentos()) {
                                Descuento newDescuento = new Descuento();
                                newDescuento.setMontoDescuento(descuento.getMontoDescuento());
                                newDescuento.setCodigoDescuento(descuento.getCodigoDescuento());
                                newDescuento.setNaturalezaDescuento(descuento.getNaturalezaDescuento());
                                newDescuento.setLineaDetalle(nueva);
                                newDescuentos.add(newDescuento);
                            }
                            nueva.setDescuentos(newDescuentos);
                        }
                        if (original.getImpuestos() != null) {
                            List<Impuesto> newImpuestos = new ArrayList<>();
                            for (Impuesto impuesto : original.getImpuestos()) {
                                Impuesto newImpuesto = new Impuesto();
                                newImpuesto.setCodigo(impuesto.getCodigo());
                                newImpuesto.setCodigoImpuestoOtro(impuesto.getCodigoImpuestoOtro());
                                newImpuesto.setCodigoTarifaIVA(impuesto.getCodigoTarifaIVA());
                                newImpuesto.setTarifa(impuesto.getTarifa());
                                newImpuesto.setFactorCalculoIVA(impuesto.getFactorCalculoIVA());
                                newImpuesto.setDatosImpuestoEspeficio(impuesto.getDatosImpuestoEspeficio());
                                newImpuesto.setMonto(impuesto.getMonto());
                                newImpuesto.setLineaDetalle(nueva);
                                newImpuestos.add(newImpuesto);
                            }
                            nueva.setImpuestos(newImpuestos);
                        }
                        
                        // Set the bidirectional relationship
                        nueva.setDetalleServicio(nuevosDetalles);
                        nuevasLineas.add(nueva);
                    }
                }
                nuevosDetalles.setLineasDetalle(nuevasLineas);
                factura.setDetalles(nuevosDetalles);

facturaService.createWithRelatedEntities(factura, encabezado, resumenFactura);

                // Verify ID assignment after creation
                if (factura.getId() == null) {
                    System.err.println("ERROR: ComprobantesRecibidos created without ID for factura: " + factura.getEncabezado().getNumeroConsecutivo());
                } else {
                    System.out.println("Successfully created factura with ID: " + factura.getId() + " - " + factura.getEncabezado().getNumeroConsecutivo());
                }
                
                // Verify DetalleServicio ID assignment
                if (factura.getDetalles() != null) {
                    if (factura.getDetalles().getId() == null) {
                        System.err.println("ERROR: DetalleServicio created without ID for factura: " + factura.getEncabezado().getNumeroConsecutivo());
                    } else {
                        System.out.println("Successfully created DetalleServicio with ID: " + factura.getDetalles().getId());
                    }
                    
                    // Verify LineaDetalle IDs
                    if (factura.getDetalles().getLineasDetalle() != null) {
                        for (LineaDetalle linea : factura.getDetalles().getLineasDetalle()) {
                            if (linea.getId() == null) {
                                System.err.println("ERROR: LineaDetalle created without ID for factura: " + factura.getEncabezado().getNumeroConsecutivo());
                            } else {
                                System.out.println("Successfully created LineaDetalle with ID: " + linea.getId());
                            }
                        }
                    }
                }
                
                System.out.println("Successfully processed factura: " + factura.getEncabezado().getNumeroConsecutivo());
                // Note: No FacesContext calls in async context

            } catch (Exception e) {
                String errorMsg = "Error parsing XML: " + e.getLocalizedMessage();
                System.err.println(errorMsg);
                logAsyncError("ERROR", errorMsg, "Parser.parseXML.exception", xmlContent.toString());
            }
        }
    }
}
