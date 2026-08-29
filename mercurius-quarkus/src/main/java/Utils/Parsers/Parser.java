package Utils.Parsers;

import Models.ComprobantesRecibidos;
import Models.ComprobantesEmitidos;
import Models.Detalles.CodigoComercial;
import Models.Detalles.Descuento;
import Models.Detalles.DetalleServicio;
import Models.Detalles.DatosImpuestoEspecifico;
import Models.Detalles.Exoneracion;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Encabezado.CorreoElectronicoReceptor;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Fax;
import Models.Encabezado.IdentificacionEmisor;
import Models.Encabezado.IdentificacionReceptor;
import Models.Encabezado.MedioPago;
import Models.Encabezado.Receptor;
import Models.Resumen.MedioPagoR;
import Models.Encabezado.Telefono;
import Models.Encabezado.Ubicacion;
import Models.Enums.Tipo_MedioPago;
import Models.Resumen.CodigoTipoMoneda;
import Models.Resumen.ResumenFactura;
import Models.Validacion.PrevalidationResult;
import Services.ComprobantesRecibidosService;
import Services.ComprobantesEmitidosService;
import Services.ComprobanteService;
import Services.Facturas.DescuentoService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.EmisorService;
import Services.Facturas.EncabezadoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import Services.Facturas.ReceptorService;
import Services.Facturas.ResumenFacturaService;
import Models.Referencias.InformacionReferencia;
import Utils.ComprobanteFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import java.time.temporal.ChronoUnit;
import org.jboss.logging.Logger;

/**
 *
 * @author Al
 */
@ApplicationScoped
public class Parser {

    private static final Logger LOG = Logger.getLogger(Parser.class);

    @Inject @Nonnull
    DetalleServicioService detalleServicioService;
    @Inject @Nonnull
    EmisorService emisorService;
    @Inject @Nonnull
    ComprobantesRecibidosService facturaService;
    @Inject @Nonnull
    ComprobantesEmitidosService comprobantesEmitidosService;
    @Inject @Nonnull
    ComprobanteService comprobanteService;
    @Inject @Nonnull
    ImpuestoService impuestoService;
    @Inject @Nonnull
    DescuentoService descuentoService;
    @Inject @Nonnull
    ReceptorService receptorService;
    @Inject @Nonnull
    ResumenFacturaService resumenFacturaService;
    @Inject @Nonnull
    EncabezadoService encabezadoService;
    @Inject @Nonnull
    LineaDetalleService lineaDetalleService;

    @Nullable
    public ComprobantesRecibidos parseComprobanteXML(@Nonnull File xmlFile) {
        try {
            JAXBContext context = JAXBContext.newInstance(ComprobantesRecibidos.class);

            // Create an unmarshaller instance
            Unmarshaller unmarshaller = context.createUnmarshaller();

            // Parse the XML file into the ComprobantesRecibidos object
            return (ComprobantesRecibidos) unmarshaller.unmarshal(xmlFile);
        } catch (JAXBException e) {
            LOG.warn("failed to parse comprobante x m l");
            return null;
        }
    }

    @Nullable
    public LocalDateTime parseFechaEmision(@Nonnull String fechaEmision) {
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
                } catch (java.time.format.DateTimeParseException ex) {
                    // Ignore and try the next format
                }
            }

            // If no format works, throw an exception
            return null;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse fecha emision", e);
            return null;
        }
    }

    @Nullable
    public Emisor parseEmisor(@Nonnull JsonNode emisorNode) {
        try {
            String nombre = emisorNode.path("Nombre").asText();
            String identificacionTipo = emisorNode.path("Identificacion").path("Tipo").asText();
            String identificacionNumero = emisorNode.path("Identificacion").path("Numero").asText();
            String nombreComercial = emisorNode.path("NombreComercial").asText();
            Ubicacion ubicacion = new Ubicacion();
            Telefono telefono = new Telefono();
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
            emisor.setCorreosElectronicos(correosElectronicos);

            String registroFiscal = emisorNode.path("Registrofiscal8707").asText();
            if (registroFiscal != null && !registroFiscal.isEmpty()) {
                emisor.setRegistrofiscal8707(registroFiscal);
            }

            String otrasSenasExtranjero = emisorNode.path("OtrasSenasExtranjero").asText();
            if (otrasSenasExtranjero != null && !otrasSenasExtranjero.isEmpty()) {
                emisor.setOtrasSenasExtranjero(otrasSenasExtranjero);
            }

            if (!emisorNode.path("Fax").isMissingNode()) {
                Fax fax = parseFax(emisorNode.path("Fax"));
                if (fax != null) {
                    emisor.setFax(fax);
                }
            }

            return emisor;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse emisor");
            return null;
        }
    }

    @Nullable
    public Receptor parseReceptor(@Nonnull JsonNode receptorNode) {
        try {
            String nombre = receptorNode.path("Nombre").asText();
            String identificacionTipo = receptorNode.path("Identificacion").path("Tipo").asText();
            String identificacionNumero = receptorNode.path("Identificacion").path("Numero").asText();
            String nombreComercial = receptorNode.path("NombreComercial").asText();
            Ubicacion ubicacion = new Ubicacion();
            Telefono telefono = new Telefono();

            if (!receptorNode.path("Ubicacion").isMissingNode()) {
                ubicacion = parseUbicacion(receptorNode.path("Ubicacion"));
            }
            if (!receptorNode.path("Telefono").isMissingNode()) {
                telefono = parseTelefono(receptorNode.path("Telefono"));
            }
            List<String> correosElectronicos = parseEmail(receptorNode.path("CorreoElectronico"));

            Receptor receptor = new Receptor();
            receptor.setNombre(nombre);

            IdentificacionReceptor idReceptor = new IdentificacionReceptor();
            idReceptor.setNumero(identificacionNumero);
            idReceptor.setTipo(identificacionTipo);

            receptor.setIdentificacion(idReceptor);
            receptor.setNombreComercial(nombreComercial);
            receptor.setUbicacion(ubicacion);
            receptor.setTelefono(telefono);
            if (correosElectronicos != null && !correosElectronicos.isEmpty()) {
                List<CorreoElectronicoReceptor> correoList = new ArrayList<>();
                for (String correoStr : correosElectronicos) {
                    CorreoElectronicoReceptor correo = new CorreoElectronicoReceptor();
                    correo.setCorreo(correoStr);
                    correo.setReceptor(receptor);
                    correoList.add(correo);
                }
                receptor.setCorreosElectronicos(correoList);
            }

            String otrasSenasExtranjero = receptorNode.path("OtrasSenasExtranjero").asText();
            if (otrasSenasExtranjero != null && !otrasSenasExtranjero.isEmpty()) {
                receptor.setOtrasSenasExtranjero(otrasSenasExtranjero);
            }

            if (!receptorNode.path("Fax").isMissingNode()) {
                Fax fax = parseFax(receptorNode.path("Fax"));
                if (fax != null) {
                    receptor.setFax(fax);
                }
            }

            return receptor;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse receptor");
            return null;
        }
    }

    @Nullable
    public Ubicacion parseUbicacion(@Nonnull JsonNode ubicacionNode) {
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

        } catch (RuntimeException e) {
            LOG.warn("failed to parse ubicacion");
            return null;
        }
    }

    @Nullable
    public Telefono parseTelefono(@Nonnull JsonNode telefonoNode) {
        try {
            String codigoPais = telefonoNode.path("CodigoPais").asText();
            String numTelefono = telefonoNode.path("NumTelefono").asText();

            Telefono telefono = new Telefono();
            telefono.setCodigoPais(codigoPais);
            telefono.setNumeroTelefono(numTelefono);

            return telefono;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse telefono");
            return null;
        }
    }

    @Nullable
    public Fax parseFax(@Nonnull JsonNode faxNode) {
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
        } catch (RuntimeException e) {
            LOG.warn("failed to parse fax");
            return null;
        }
    }

    @Nullable
    public List<String> parseEmail(@Nonnull JsonNode emailNode) {
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
        } catch (RuntimeException e) {
            LOG.warn("failed to parse email");
            return null;
        }
    }

    @Nullable
    public List<LineaDetalle> parseDetalleServicio(@Nonnull JsonNode detalleServicio) {
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
        } catch (RuntimeException e) {
            LOG.warn("failed to parse detalle servicio");
            return null;
        }

    }

    @Nullable
    public List<MedioPago> parseMedioPago(@Nonnull JsonNode medioPagoNode, @Nonnull Encabezado comprobante) {
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
        } catch (RuntimeException e) {
            LOG.warn("failed to parse medio pago");
            return null;
        }
    }

    @Nullable
    public LineaDetalle parseLineaDetalle(@Nonnull JsonNode lineaDetalleNode) {
        try {
            LineaDetalle lineaDetalle = new LineaDetalle();

            int numeroLinea = lineaDetalleNode.path("NumeroLinea").asInt();
            String codigo = lineaDetalleNode.path("CodigoCABYS").asText();

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
            String baseImponible = lineaDetalleNode.path("BaseImponible").asText();
            String impuestoNeto = lineaDetalleNode.path("ImpuestoNeto").asText();
            String tipoTransaccion = lineaDetalleNode.path("TipoTransaccion").asText();

            lineaDetalle.setNumeroLinea(numeroLinea);
            lineaDetalle.setCodigoCabys(codigo);
            lineaDetalle.setCodigosComerciales(codigosComerciales);
            lineaDetalle.setCantidad(new BigDecimal(cantidad));
            lineaDetalle.setUnidadMedida(unidadMedida);
            lineaDetalle.setTipoTransaccion(tipoTransaccion);
            lineaDetalle.setUnidadMedidaComercial(unidadMedidaComercial);
            lineaDetalle.setDetalle(detalle);
            lineaDetalle.setPrecioUnitario(new BigDecimal(precioUnitario));
            lineaDetalle.setMontoTotal(new BigDecimal(montoTotal));
            lineaDetalle.setDescuentos(descuentos);
            lineaDetalle.setSubTotal(new BigDecimal(subTotal));
            lineaDetalle.setBaseImponible(baseImponible.isEmpty() ? null : new BigDecimal(baseImponible));
            lineaDetalle.setImpuestoNeto(impuestoNeto.isEmpty() ? null : new BigDecimal(impuestoNeto));
            lineaDetalle.setImpuestos(impuestos);
            lineaDetalle.setMontoTotalLinea(new BigDecimal(montoTotalLinea));

            return lineaDetalle;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse linea detalle");
            return null;
        }
    }

    @Nullable
    public CodigoComercial parseCodigoComercial(@Nonnull JsonNode codigoComercialNode) {
        try {
            String tipo = codigoComercialNode.path("Tipo").asText();
            String codigo = codigoComercialNode.path("Codigo").asText();

            CodigoComercial codigoComercial = new CodigoComercial();
            codigoComercial.setTipo(tipo);
            codigoComercial.setCodigo(codigo);

            return codigoComercial;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse codigo comercial");
            return null;
        }
    }

    @Nullable
    public Impuesto parseImpuesto(@Nonnull JsonNode impuestoNode) {
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

            String codigoImpuestoOtro = impuestoNode.path("CodigoImpuestoOtro").asText();
            if (codigoImpuestoOtro != null && !codigoImpuestoOtro.isEmpty()) {
                impuesto.setCodigoImpuestoOtro(codigoImpuestoOtro);
            }

            String factorCalculoIVA = impuestoNode.path("FactorCalculoIVA").asText();
            if (factorCalculoIVA != null && !factorCalculoIVA.isEmpty()) {
                impuesto.setFactorCalculoIVA(new BigDecimal(factorCalculoIVA.trim()));
            }

            String montoExportacion = impuestoNode.path("MontoExportacion").asText();
            if (montoExportacion != null && !montoExportacion.isEmpty()) {
                impuesto.setMontoExportacion(new BigDecimal(montoExportacion.trim()));
            }

            if (!impuestoNode.path("DatosImpuestoEspecifico").isMissingNode()) {
                DatosImpuestoEspecifico datos = parseDatosImpuestoEspecifico(impuestoNode.path("DatosImpuestoEspecifico"));
                if (datos != null) {
                    impuesto.setDatosImpuestoEspeficio(datos);
                }
            }

            if (!impuestoNode.path("Exoneracion").isMissingNode()) {
                Exoneracion exoneracion = parseExoneracion(impuestoNode.path("Exoneracion"));
                if (exoneracion != null) {
                    exoneracion.setImpuesto(impuesto);
                    impuesto.setExoneracion(exoneracion);
                }
            }

            return impuesto;
        } catch (RuntimeException e) {
            String articleName = impuestoNode.path("Detalle").asText("unknown");
            LOG.warn("failed to parse impuesto");
            return null;
        }
    }

    @Nullable
    public DatosImpuestoEspecifico parseDatosImpuestoEspecifico(@Nonnull JsonNode datosNode) {
        try {
            DatosImpuestoEspecifico datos = new DatosImpuestoEspecifico();

            String cantidad = datosNode.path("CantidadUnidadMedida").asText();
            if (cantidad != null && !cantidad.isEmpty()) {
                datos.setCantidadUnidadMedida(new BigDecimal(cantidad.trim()));
            }

            String porcentaje = datosNode.path("Porcentaje").asText();
            if (porcentaje != null && !porcentaje.isEmpty()) {
                datos.setPorcentaje(new BigDecimal(porcentaje.trim()));
            }

            String proporcion = datosNode.path("Proporcion").asText();
            if (proporcion != null && !proporcion.isEmpty()) {
                datos.setProporcion(new BigDecimal(proporcion.trim()));
            }

            String volumen = datosNode.path("VolumenUnidadConsumo").asText();
            if (volumen != null && !volumen.isEmpty()) {
                datos.setVolumenUnidadConsumo(new BigDecimal(volumen.trim()));
            }

            String impuestoUnidad = datosNode.path("ImpuestoUnidad").asText();
            if (impuestoUnidad != null && !impuestoUnidad.isEmpty()) {
                datos.setImpuestoUnidad(new BigDecimal(impuestoUnidad.trim()));
            }

            return datos;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse datos impuesto especifico");
            return null;
        }
    }

    @Nullable
    public Exoneracion parseExoneracion(@Nonnull JsonNode exoneracionNode) {
        try {
            Exoneracion exoneracion = new Exoneracion();

            String tipoDocumento = exoneracionNode.path("TipoDocumentoEX1").asText();
            if (tipoDocumento != null && !tipoDocumento.isEmpty()) {
                exoneracion.setTipoDocumentoEX1(tipoDocumento);
            }

            String tipoDocumentoOtro = exoneracionNode.path("TipoDocumentoOtro").asText();
            if (tipoDocumentoOtro != null && !tipoDocumentoOtro.isEmpty()) {
                exoneracion.setTipoDocumentoOTRO(tipoDocumentoOtro);
            }

            String numeroDocumento = exoneracionNode.path("NumeroDocumento").asText();
            if (numeroDocumento != null && !numeroDocumento.isEmpty()) {
                exoneracion.setNumeroDocumento(numeroDocumento);
            }

            String articulo = exoneracionNode.path("Articulo").asText();
            if (articulo != null && !articulo.isEmpty()) {
                exoneracion.setArticulo(new BigDecimal(articulo.trim()));
            }

            String inciso = exoneracionNode.path("Inciso").asText();
            if (inciso != null && !inciso.isEmpty()) {
                exoneracion.setInciso(new BigDecimal(inciso.trim()));
            }

            String nombreInstitucion = exoneracionNode.path("NombreInstitucion").asText();
            if (nombreInstitucion != null && !nombreInstitucion.isEmpty()) {
                exoneracion.setNombreInstitucion(nombreInstitucion);
            }

            String nombreInstitucionOtros = exoneracionNode.path("NombreInstitucionOtros").asText();
            if (nombreInstitucionOtros != null && !nombreInstitucionOtros.isEmpty()) {
                exoneracion.setNombreInstitucionOtros(nombreInstitucionOtros);
            }

            String fechaEmision = exoneracionNode.path("FechaEmisionEx").asText();
            if (fechaEmision != null && !fechaEmision.isEmpty()) {
                exoneracion.setFechaEmisionEX(parseFechaEmision(fechaEmision));
            }

            String tarifaExonerada = exoneracionNode.path("TarifaExonerada").asText();
            if (tarifaExonerada != null && !tarifaExonerada.isEmpty()) {
                exoneracion.setTarifaExonerada(new BigDecimal(tarifaExonerada.trim()));
            }

            String montoExoneracion = exoneracionNode.path("MontoExoneracion").asText();
            if (montoExoneracion != null && !montoExoneracion.isEmpty()) {
                exoneracion.setMontoExoneracion(new BigDecimal(montoExoneracion.trim()));
            }

            return exoneracion;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse exoneracion");
            return null;
        }
    }

    @Nullable
    public ResumenFactura parseResumenFactura(@Nonnull JsonNode resumenFacturaNode) {
        try {
            JsonNode codigoMonedaPath = resumenFacturaNode.path("CodigoTipoMoneda");
            String codigoMonedaText = codigoMonedaPath.path("CodigoMoneda").asText();
            String tipoCambioText = codigoMonedaPath.path("TipoCambio").asText();

            CodigoTipoMoneda codigoMoneda = parseCodigoMoneda(codigoMonedaText, tipoCambioText);
            if (codigoMoneda == null) {
                LOG.warn("failed to parse resumen factura");
                return null;
            }

            // Extracting values
            String totalServiciosGravados = resumenFacturaNode.path("TotalServGravados").asText();
            String totalServiciosExentos = resumenFacturaNode.path("TotalServExentos").asText();
            String totalServiciosExonerados = resumenFacturaNode.path("TotalServExonerado").asText();
            String totalServNoSujeto = resumenFacturaNode.path("TotalServNoSujeto").asText();
            String totalMercanciasGravadas = resumenFacturaNode.path("TotalMercanciasGravadas").asText();
            String totalMercanciasExentas = resumenFacturaNode.path("TotalMercanciasExentas").asText();
            String totalMercanciaExonerada = resumenFacturaNode.path("TotalMercExonerada").asText();
            String totalMercNoSujeta = resumenFacturaNode.path("TotalMercNoSujeta").asText();
            String totalGravado = resumenFacturaNode.path("TotalGravado").asText();
            String totalExento = resumenFacturaNode.path("TotalExento").asText();
            String totalExonerado = resumenFacturaNode.path("TotalExonerado").asText();
            String totalNoSujeto = resumenFacturaNode.path("TotalNoSujeto").asText();
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
            BigDecimal bigDecimalTotalServNoSujeto = totalServNoSujeto.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalServNoSujeto);
            BigDecimal bigDecimalTotalMercanciasGravadas = totalMercanciasGravadas.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciasGravadas);
            BigDecimal bigDecimalTotalMercanciasExentas = totalMercanciasExentas.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciasExentas);
            BigDecimal bigDecimalTotalMercanciaExonerada = totalMercanciaExonerada.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciaExonerada);
            BigDecimal bigDecimalTotalMercNoSujeta = totalMercNoSujeta.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercNoSujeta);
            BigDecimal bigDecimalTotalGravado = totalGravado.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalGravado);
            BigDecimal bigDecimalTotalExento = totalExento.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalExento);
            BigDecimal bigDecimalTotalExonerado = totalExonerado.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalExonerado);
            BigDecimal bigDecimalTotalNoSujeto = totalNoSujeto.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalNoSujeto);
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
            resumenFactura.setTotalServNoSujeto(bigDecimalTotalServNoSujeto);
            resumenFactura.setTotalMercanciasGravadas(bigDecimalTotalMercanciasGravadas);
            resumenFactura.setTotalMercanciasExentas(bigDecimalTotalMercanciasExentas);
            resumenFactura.setTotalMercExonerada(bigDecimalTotalMercanciaExonerada);
            resumenFactura.setTotalMercNoSujeta(bigDecimalTotalMercNoSujeta);
            resumenFactura.setTotalGravado(bigDecimalTotalGravado);
            resumenFactura.setTotalExento(bigDecimalTotalExento);
            resumenFactura.setTotalExonerado(bigDecimalTotalExonerado);
            resumenFactura.setTotalNoSujeto(bigDecimalTotalNoSujeto);
            resumenFactura.setTotalVenta(bigDecimalTotalVenta);
            resumenFactura.setTotalDescuentos(bigDecimalTotalDescuentos);
            resumenFactura.setTotalVentaNeta(bigDecimalTotalVentaNeta);
            resumenFactura.setTotalImpuesto(bigDecimalTotalImpuesto);
            resumenFactura.setTotalIVADevuelto(bigDecimalTotalIVADevuelto);
            resumenFactura.setTotalOtrosCargos(bigDecimalTotalOtrosCargos);
            resumenFactura.setTotalComprobante(bigDecimalTotalComprobante);

            // V4.4: Parse MediosPago from ResumenFactura (moved from Encabezado per Bitácora 22/04/2026)
            List<MedioPagoR> mediosPagoResumen = parseMedioPagoR(resumenFacturaNode);
            if (mediosPagoResumen != null && !mediosPagoResumen.isEmpty()) {
                mediosPagoResumen.forEach(mp -> mp.setResumenFactura(resumenFactura));
                resumenFactura.setMediosPago(mediosPagoResumen);
            }

            return resumenFactura;

        } catch (RuntimeException e) {
            LOG.warn("failed to parse resumen factura");
            return null;
        }
    }

    /**
     * Parse MedioPago from ResumenFactura (V4.4 format).
     * Per the XSD, <MedioPago> appears directly inside <ResumenFactura> (no wrapper),
     * repeating up to 4 times. Each has TipoMedioPago, optional MedioPagoOtros, and
     * optional TotalMedioPago.
     */
    @Nullable
    public List<MedioPagoR> parseMedioPagoR(@Nonnull JsonNode resumenFacturaNode) {
        try {
            JsonNode medioPagoNode = resumenFacturaNode.path("MedioPago");
            if (medioPagoNode.isMissingNode()) {
                return new ArrayList<>();
            }

            List<MedioPagoR> mediosPagoR = new ArrayList<>();

            if (medioPagoNode.isArray()) {
                for (JsonNode medioPago : medioPagoNode) {
                    MedioPagoR mp = parseSingleMedioPagoR(medioPago);
                    if (mp != null) {
                        mediosPagoR.add(mp);
                    }
                }
            } else {
                MedioPagoR mp = parseSingleMedioPagoR(medioPagoNode);
                if (mp != null) {
                    mediosPagoR.add(mp);
                }
            }

            return mediosPagoR;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse medio pago r");
            return null;
        }
    }

    private MedioPagoR parseSingleMedioPagoR(JsonNode medioPagoNode) {
        try {
            String tipoMedioPago = medioPagoNode.path("TipoMedioPago").asText();
            if (tipoMedioPago.isEmpty()) {
                return null;
            }

            MedioPagoR mp = new MedioPagoR();
            mp.setTipoMedioPago(tipoMedioPago);

            String otros = medioPagoNode.path("MedioPagoOtros").asText();
            if (!otros.isEmpty()) {
                mp.setMedioPagoOtros(otros);
            }

            String total = medioPagoNode.path("TotalMedioPago").asText();
            if (!total.isEmpty()) {
                mp.setTotalMedioPago(new BigDecimal(total));
            }

            return mp;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse single medio pago r");
            return null;
        }
    }

    @Nullable
    public CodigoTipoMoneda parseCodigoMoneda(@Nonnull String codigo, @Nullable String tipo) {
        try {
            CodigoTipoMoneda codigoMoneda = new CodigoTipoMoneda();
            codigoMoneda.setCodigoMoneda(codigo);
            if (tipo == null || tipo.isEmpty()) {
                codigoMoneda.setTipoCambioMoneda(BigDecimal.ONE);
            } else {
                codigoMoneda.setTipoCambioMoneda(new BigDecimal(tipo));
            }

            return codigoMoneda;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse codigo moneda");
            return null;
        }
    }

    @Nullable
    public Descuento parseDescuento(@Nonnull JsonNode descuentoNode) {
        try {
            String montoDescuento = descuentoNode.path("MontoDescuento").asText();
            String naturalezaDescuento = descuentoNode.path("NaturalezaDescuento").asText();
            String codigoDescuento = descuentoNode.path("CodigoDescuento").asText();
            String codigoDescuentoOtro = descuentoNode.path("CodigoDescuentoOtro").asText();

            Descuento descuento = new Descuento();
            descuento.setMontoDescuento(new BigDecimal(montoDescuento));
            descuento.setNaturalezaDescuento(naturalezaDescuento);
            if (!codigoDescuento.isEmpty()) {
                descuento.setCodigoDescuento(codigoDescuento);
            }
            if (!codigoDescuentoOtro.isEmpty()) {
                descuento.setCodigoDescuentoOtro(codigoDescuentoOtro);
            }

            return descuento;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse descuento");
            return null;
        }
    }

    @Nonnull
    public BigDecimal parseUnidadMedida(@Nonnull String unidad, @Nonnull String unidadComercial) {
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

    @Nonnull
    public BigDecimal parseUnidadComercial(@Nonnull String unidadComercial) {
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

            LOG.warn("failed to source");
        } catch (RuntimeException e) {
            // Fallback to console logging if AlertasService fails
            LOG.warn("failed to log async error", e);
            LOG.warn("failed to log async error");
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
                    LOG.info("failed to handle duplicate key error");
                    Thread.sleep(delay);
                    return true; // Continue retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                LOG.warn("failed to handle duplicate key error", e);
                logAsyncError("ERROR", "Max retries reached for " + operation + ": " + e.getMessage(),
                        "Parser.handleDuplicateKeyError", "");
                return false;
            }
        }
        return false;
    }
 
    @Transactional
    public void parseXML(@Nonnull InputStream inputStream) {
        StringBuilder xmlContent = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    xmlContent.append(line).append("\n");
                }

                LOG.info("failed to parse x m l");
                LOG.info("failed to parse x m l");

                XmlMapper xmlMapper = new XmlMapper();
                JsonNode rootNode = xmlMapper.readTree(xmlContent.toString());

                LOG.info("failed to parse x m l");

                // Validate required fields first with improved NumeroConsecutivo extraction
// Detect document type first
                String documentType = rootNode.fieldNames().next();
                boolean isMensajeHacienda = "MensajeHacienda".equals(documentType);

                String codigoDocumento = mapRootElementToDocumentCode(documentType);

                String numeroConsecutivo = extractNumeroConsecutivo(rootNode);
                String clave = rootNode.path(documentType).path("Clave").asText();
                if (clave.isEmpty()) clave = rootNode.path("Clave").asText();

                LOG.info("failed to parse x m l");
                LOG.info("failed to parse x m l");
                LOG.info("failed to parse x m l");

                String schemaVersion = null;
                try {
                    schemaVersion = ComprobanteFactory.detectVersion(
                        new java.io.ByteArrayInputStream(xmlContent.toString().getBytes(StandardCharsets.UTF_8)));
                    LOG.info("failed to parse x m l");
                } catch (RuntimeException e) {
                    LOG.warn("failed to parse x m l", e);
                }

                if (isMensajeHacienda) {
                    processMensajeHacienda(clave, rootNode, xmlContent.toString());
                    return;
                }

                if (numeroConsecutivo.isEmpty()) {
                    String errorMsg = "XML inválido: falta el número consecutivo";
                    LOG.warn("failed to parse x m l");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML", xmlContent.toString());
                    return;
                }

                if (facturaService.findByNumeroConsecutivo(numeroConsecutivo)) {
                    LOG.info("failed to parse x m l");
                    logAsyncError("WARN", "Factura duplicada: " + numeroConsecutivo, "Parser.parseXML", xmlContent.toString());
                    return;
                }

                Encabezado encabezado = new Encabezado();
                DetalleServicio detalles = new DetalleServicio();

                LOG.info("failed to parse x m l");
                JsonNode emisorNode = rootNode.path(documentType).isMissingNode() ? rootNode.path("Emisor") : rootNode.path(documentType).path("Emisor");
                if (emisorNode.isMissingNode() || emisorNode.isEmpty()) emisorNode = rootNode.path("Emisor");
                Emisor emisor = parseEmisor(emisorNode);
                if (emisor == null) {
                    String errorMsg = "XML inválido: error en datos del emisor";
                    LOG.warn("failed to parse x m l");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.emisor", xmlContent.toString());
                    return;
                }

                LOG.info("failed to parse x m l");
                JsonNode receptorNode = rootNode.path(documentType).isMissingNode() ? rootNode.path("Receptor") : rootNode.path(documentType).path("Receptor");
                if (receptorNode.isMissingNode() || receptorNode.isEmpty()) receptorNode = rootNode.path("Receptor");
                Receptor receptor = parseReceptor(receptorNode);
                if (receptor == null) {
                    String errorMsg = "XML inválido: error en datos del receptor";
                    LOG.warn("failed to parse x m l");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.receptor", xmlContent.toString());
                    return;
                }

                String codigoActividad = rootNode.path(documentType).path("CodigoActividad").asText();
                if (codigoActividad.isEmpty()) codigoActividad = rootNode.path("CodigoActividad").asText();
                String fechaEmisionStr = rootNode.path(documentType).path("FechaEmision").asText();
                if (fechaEmisionStr.isEmpty()) fechaEmisionStr = rootNode.path("FechaEmision").asText();
                LOG.info("failed to parse x m l");

                LocalDateTime localDateTime = parseFechaEmision(fechaEmisionStr);
                if (localDateTime == null) {
                    String errorMsg = "XML inválido: error en fecha de emisión";
                    LOG.warn("failed to parse x m l");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.fechaEmision", xmlContent.toString());
                    return;
                }

                String condicionVenta = rootNode.path("CondicionVenta").asText();
                String plazoCredito = rootNode.path("PlazoCredito").asText();
                String condicionVentaOtros = rootNode.path("CondicionVentaOtros").asText();
                List<MedioPago> medioPago = parseMedioPago(rootNode.path("MedioPago"), encabezado);
                if (medioPago == null) {
                    String errorMsg = "XML inválido: error en medio de pago";
                    LOG.warn("failed to parse x m l");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.medioPago", xmlContent.toString());
                    return;
                }

                LOG.info("failed to parse x m l");
                ResumenFactura resumenFactura = parseResumenFactura(rootNode.path("ResumenFactura"));
                if (resumenFactura == null) {
                    String errorMsg = "XML inválido: error en resumen de factura";
                    LOG.warn("failed to parse x m l");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.resumenFactura", xmlContent.toString());
                    return;
                }

                // V4.4 Bitácora item 124/125: TotalComprobante must equal sum of TotalMedioPago
                validarTotalMedioPago(resumenFactura, numeroConsecutivo, xmlContent.toString());

                LOG.info("failed to parse x m l");
                List<LineaDetalle> lineas = parseDetalleServicio(rootNode.path("DetalleServicio"));
                if (lineas == null || lineas.isEmpty()) {
                    String errorMsg = "XML inválido: no se encontraron líneas de detalle";
                    LOG.warn("failed to parse x m l");
                    logAsyncError("ERROR", errorMsg, "Parser.parseXML.detalleServicio", xmlContent.toString());
                    return;
                }

                LOG.info("failed to parse x m l");

// Check if encabezado already exists by numeroConsecutivo (before creating any entities)
                // Use new method that handles duplicates properly
                boolean existsByNumeroConsecutivo = encabezadoService.existsByNumeroConsecutivoWithValidComprobante(numeroConsecutivo);

                if (existsByNumeroConsecutivo) {
                    LOG.info("failed to parse x m l");
                    LOG.info("failed to parse x m l");
                    return; // Skip processing if already exists
                }

                // Clean up any existing duplicates before proceeding
                int duplicatesCleaned = encabezadoService.cleanDuplicateEncabezados(numeroConsecutivo);
                if (duplicatesCleaned > 0) {
                    LOG.info("failed to parse x m l");
                }

                detalles.setLineasDetalle(lineas);
                lineas.forEach(linea -> linea.setDetalleServicio(detalles));

                encabezado.setCodigoActividadEmisor(codigoActividad);
                encabezado.setNumeroConsecutivo(numeroConsecutivo);
                encabezado.setFechaEmision(localDateTime);
                encabezado.setCondicionVenta(condicionVenta);
                encabezado.setPlazoCredito(plazoCredito);
                if (condicionVentaOtros != null && !condicionVentaOtros.isEmpty()) {
                    encabezado.setCondicionVentaOtros(condicionVentaOtros);
                }
                encabezado.setMedioPago(medioPago);
                encabezado.setClave(clave);
                encabezado.setCodigoDocumento(codigoDocumento);

                LOG.info("failed to parse x m l");
                Emisor persistedEmisor = emisorService.createIfNotExist(emisor);
                Receptor persistedReceptor = receptorService.createIfNotExist(receptor);

                if (persistedEmisor != null) {
                    encabezado.setEmisor(persistedEmisor);
                } else {
                    LOG.warn("failed to parse x m l");
                    return;
                }
                if (persistedReceptor != null) {
                    encabezado.setReceptor(persistedReceptor);
                } else {
                    LOG.warn("failed to parse x m l");
                    return;
                }

                LOG.info("failed to parse x m l");

                // Set the lineasDetalle relationship before persisting
                detalles.setLineasDetalle(lineas);

                LOG.info("failed to parse x m l");

// For received documents, we don't create ComprobantesEmitidos
                // Only ComprobantesRecibidos should be created for uploaded XML files
                // This fixes the unique constraint violation issue
                
LOG.info("failed to parse x m l");
                
                LOG.info("failed to parse x m l");
                ComprobantesRecibidos factura = new ComprobantesRecibidos();
                factura.setEncabezado(encabezado);
                factura.setResumen(resumenFactura);
                factura.setUser(Utils.AsyncUserContext.getCurrentUser() != null ? Utils.AsyncUserContext.getCurrentUser() : "system");
                factura.setStatus(true);
                factura.setProcessed(false);
                factura.setSchemaVersion(schemaVersion);
                encabezado.setSchemaVersion(schemaVersion);
                resumenFactura.setSchemaVersion(schemaVersion);

                // Create a new DetalleServicio for the Recibidos entity
                DetalleServicio nuevosDetalles = new DetalleServicio();
                nuevosDetalles.setStatus(true);
                
                // Copy lineasDetalle with proper bidirectional relationship
                List<LineaDetalle> nuevasLineas = new ArrayList<>();
                if (detalles.getLineasDetalle() != null) {
                    for (LineaDetalle original : detalles.getLineasDetalle()) {
                        LineaDetalle nueva = new LineaDetalle();
                        nueva.setNumeroLinea(original.getNumeroLinea());
                        nueva.setCantidad(original.getCantidad());
                        nueva.setCodigoCabys(original.getCodigoCabys());
                        nueva.setDetalle(original.getDetalle());
                        nueva.setMontoTotal(original.getMontoTotal()); 
                        nueva.setPrecioUnitario(original.getPrecioUnitario());
                        nueva.setSubTotal(original.getSubTotal());
                        nueva.setUnidadMedida(original.getUnidadMedida());
                        nueva.setTipoTransaccion(original.getTipoTransaccion());
                        nueva.setUnidadMedidaComercial(original.getUnidadMedidaComercial());
                        nueva.setMontoTotalLinea(original.getMontoTotalLinea());
                        nueva.setBaseImponible(original.getBaseImponible());
                        nueva.setImpuestoNeto(original.getImpuestoNeto());
                        nueva.setIvaCobradoFabrica(original.getIvaCobradoFabrica());
                        nueva.setImpuestoAsumidoEmisorFabrica(original.getImpuestoAsumidoEmisorFabrica());
                        
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
                                newDescuento.setCodigoDescuentoOtro(descuento.getCodigoDescuentoOtro());
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
                                newImpuesto.setMontoExportacion(impuesto.getMontoExportacion());
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

                // Parse InformacionReferencia if present (for NC/ND received documents)
                JsonNode infoRefNode = rootNode.path("InformacionReferencia");
                if (!infoRefNode.isMissingNode()) {
                    List<InformacionReferencia> referencias = new ArrayList<>();
                    if (infoRefNode.isArray()) {
                        for (JsonNode refNode : infoRefNode) {
                            InformacionReferencia ref = parseSingleInformacionReferencia(refNode);
                            if (ref != null) {
                                referencias.add(ref);
                            }
                        }
                    } else {
                        InformacionReferencia ref = parseSingleInformacionReferencia(infoRefNode);
                        if (ref != null) {
                            referencias.add(ref);
                        }
                    }
                    if (!referencias.isEmpty()) {
                        factura.setInformacionReferencia(referencias);
                    }
                }

                PrevalidationResult prevalidation = facturaService.createWithRelatedEntities(factura, encabezado, resumenFactura);

                if (prevalidation != null && prevalidation.hasErrors()) {
                    String errorSummary = prevalidation.getErrors().stream()
                        .map(e -> e.getField() + ": " + e.getMessage())
                        .collect(java.util.stream.Collectors.joining("; "));
                    LOG.info("failed to parse x m l");
                }

                // Verify ID assignment after creation
                if (factura.getId() == null) {
                    LOG.warn("failed to parse x m l");
                } else {
                    LOG.info("failed to parse x m l");
                }
                
                // Verify DetalleServicio ID assignment
                if (factura.getDetalles() != null) {
                    if (factura.getDetalles().getId() == null) {
                        LOG.warn("failed to parse x m l");
                    } else {
                        LOG.info("failed to parse x m l");
                    }
                    
                    // Verify LineaDetalle IDs
                    if (factura.getDetalles().getLineasDetalle() != null) {
                        for (LineaDetalle linea : factura.getDetalles().getLineasDetalle()) {
                            if (linea.getId() == null) {
                                LOG.warn("failed to parse x m l");
                            } else {
                                LOG.info("failed to parse x m l");
                            }
                        }
                    }
                }
                
                LOG.info("failed to parse x m l");
                // Note: No FacesContext calls in async context

            } catch (IOException | RuntimeException e) {
                String errorMsg = "Error parsing XML: " + e.getLocalizedMessage();
                LOG.warn("failed to parse x m l");
                logAsyncError("ERROR", errorMsg, "Parser.parseXML.exception", xmlContent.toString());
            }
    }

    private InformacionReferencia parseSingleInformacionReferencia(JsonNode node) {
        try {
            InformacionReferencia ref = new InformacionReferencia();
            ref.setTipoDoc(node.path("TipoDocIR").asText());
            ref.setNumero(node.path("Numero").asText());
            String fechaStr = node.path("FechaEmisionIR").asText();
            if (!fechaStr.isEmpty() && !"null".equals(fechaStr)) {
                ref.setFechaEmision(LocalDateTime.parse(fechaStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            }
            ref.setCodigo(node.path("Codigo").asText());
            ref.setRazon(node.path("Razon").asText());
            ref.setTipoDocRefOTRO(node.path("TipoDocRefOTRO").asText(null));
            ref.setCodigoReferenciaOTRO(node.path("CodigoReferenciaOTRO").asText(null));
            return ref;
        } catch (RuntimeException e) {
            LOG.warn("failed to parse single informacion referencia");
            return null;
        }
    }

    private void processMensajeHacienda(String clave, JsonNode rootNode, String rawXml) {
        try {
            if (clave == null || clave.isEmpty()) {
                LOG.warn("failed to process mensaje hacienda");
                return;
            }

            JsonNode mensajeNode = rootNode.path("Mensaje");
            String codigoMensaje = mensajeNode.path("CodigoMensaje").asText();
            String detalleMensaje = mensajeNode.path("DetalleMensaje").asText();

            String nuevoEstado;
            switch (codigoMensaje) {
                case "1": nuevoEstado = "ACEPTADO"; break;
                case "2": nuevoEstado = "ACEPTACION_PARCIAL"; break;
                case "3": nuevoEstado = "RECHAZADO"; break;
                default:  nuevoEstado = "DESCONOCIDO"; break;
            }

            List<ComprobantesEmitidos> resultados = comprobantesEmitidosService.findByClave(clave);
            if (resultados == null || resultados.isEmpty()) {
                LOG.info("failed to process mensaje hacienda");
                return;
            }

            for (ComprobantesEmitidos factura : resultados) {
                factura.setHaciendaEstado(nuevoEstado);
                factura.setHaciendaFechaRespuesta(LocalDateTime.now());
                if (factura.getEncabezado() != null) {
                    factura.getEncabezado().setEstado(nuevoEstado);
                    if ("RECHAZADO".equals(nuevoEstado) && !detalleMensaje.isEmpty()) {
                        factura.getEncabezado().setMotivoRechazo(detalleMensaje);
                    }
                }
                comprobantesEmitidosService.update(factura);
            }

            // If accepted, send invoice to client automatically
            if ("ACEPTADO".equals(nuevoEstado)) {
                for (ComprobantesEmitidos factura : resultados) {
                    try {
                        // Get client from the receptor
                        String receptorNombre = factura.getEncabezado() != null && factura.getEncabezado().getReceptor() != null
                            ? factura.getEncabezado().getReceptor().getNombre() : null;
                        if (receptorNombre != null) {
                            // Find client by name (this is a simplified lookup)
                            // In practice you might need to match by cedula
                            LOG.info("failed to process mensaje hacienda");
                            // The actual email sending would require client lookup, 
                            // which can be done in a separate process or by the controller
                        }
                    } catch (RuntimeException e) {
                        LOG.warn("failed to process mensaje hacienda", e);
                    }
                }
            }

            // If rejected, automatically create credit note and prepare correction
            if ("RECHAZADO".equals(nuevoEstado)) {
                for (ComprobantesEmitidos factura : resultados) {
                    try {
                        LOG.info("failed to process mensaje hacienda");
                        // The actual credit note creation and correction preparation 
                        // will be handled by the controller when the user clicks "Corregir"
                        // Here we just mark it for automatic correction
                        factura.getEncabezado().setMotivoRechazo(
                            (factura.getEncabezado().getMotivoRechazo() != null ? factura.getEncabezado().getMotivoRechazo() + " | " : "") 
                            + "CORRECCION_AUTOMATICA_PENDIENTE"
                        );
                        comprobantesEmitidosService.update(factura);
                    } catch (RuntimeException e) {
                        LOG.warn("failed to process mensaje hacienda", e);
                    }
                }
            }

            LOG.info("failed to process mensaje hacienda");
        } catch (RuntimeException e) {
            LOG.warn("failed to process mensaje hacienda", e);
        }
    }

    private String mapRootElementToDocumentCode(String rootElement) {
        if (rootElement == null) return "01";
        switch (rootElement) {
            case "FacturaElectronica":
            case "FacturaElectrónica":
                return "01";
            case "FacturaElectronicaCompra":
                return "08";
            case "FacturaElectronicaExportacion":
                return "09";
            case "NotaCreditoElectronica":
            case "NotaCréditoElectrónica":
            case "NotaCredito":
                return "03";
            case "NotaDebitoElectronica":
            case "NotaDébitoElectrónica":
            case "NotaDebito":
                return "02";
            case "ReciboElectronico":
            case "ReciboElectronicoPago":
                return "10";
            case "TiqueteElectronico":
            case "TiqueteElectrónico":
                return "04";
            default:
                return "01";
        }
    }

    private void validarTotalMedioPago(ResumenFactura resumenFactura, String numeroConsecutivo, String xmlContent) {
        if (resumenFactura == null) return;
        List<MedioPagoR> mediosPago = resumenFactura.getMediosPago();
        if (mediosPago == null || mediosPago.isEmpty()) return;

        BigDecimal totalComprobante = resumenFactura.getTotalComprobante();
        BigDecimal sumaMediosPago = mediosPago.stream()
            .map(mp -> mp.getTotalMedioPago() != null ? mp.getTotalMedioPago() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (sumaMediosPago.compareTo(totalComprobante) != 0) {
            String warnMsg = "TotalMedioPago sum (" + sumaMediosPago
                + ") no coincide con TotalComprobante (" + totalComprobante
                + ") para comprobante " + numeroConsecutivo;
            LOG.info("failed to validar total medio pago");
            logAsyncError("WARN", warnMsg, "Parser.validarTotalMedioPago", xmlContent);
        }
    }
}
