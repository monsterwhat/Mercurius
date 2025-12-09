package Controllers.Tiquetes;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Clients;
import Models.ComprobantesV44.ComprobantesEmitidos;
import Models.ComprobantesV44.Detalles.CodigoComercial;
import Models.ComprobantesV44.Detalles.Descuento;
import Models.ComprobantesV44.Detalles.DetalleServicio;
import Models.ComprobantesV44.Detalles.Impuesto;
import Models.ComprobantesV44.Detalles.LineaDetalle;
import Models.ComprobantesV44.Detalles.OtroCargo;
import Models.ComprobantesV44.Encabezado.Emisor;
import Models.ComprobantesV44.Encabezado.Encabezado;
import Models.ComprobantesV44.Encabezado.Fax;
import Models.ComprobantesV44.Encabezado.IdentificacionEmisor;
import Models.ComprobantesV44.Encabezado.IdentificacionReceptor;
import Models.ComprobantesV44.Encabezado.MedioPago;
import Models.ComprobantesV44.Encabezado.Receptor;
import Models.ComprobantesV44.Encabezado.Telefono;
import Models.ComprobantesV44.Encabezado.Ubicacion;
import Models.ComprobantesV44.Resumen.ResumenFactura;
import Models.Articulos.Promocion;
import Models.ComprobantesV44.Encabezado.CorreoElectronicoEmisor;
import Models.ComprobantesV44.Enums.Tipo_CondicionVenta;
import Models.ComprobantesV44.Enums.Tipo_MedioPago;
import Models.ComprobantesV44.Enums.Tipo_TarifaIVA;
import Models.AppSettings;
import Models.Users;
import Services.Facturas.EncabezadoService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.ResumenFacturaService;
import Services.Facturas.EmisorService;
import Services.Facturas.ReceptorService;
import Services.Facturas.DescuentoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Named("comprobanteService")
@ViewScoped
public class ComprobanteService implements Serializable {

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

    public ComprobantesEmitidos crearComprobante(AppSettings appSettings, List<ArticuloCarrito> carrito, Clients selectedClient, Clients cliente, Users currentUser) {
        try {
            Encabezado encabezado = encabezadoTiqueteElectronico(appSettings, selectedClient);
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
            return tiqueteElectronico;
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
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
            System.out.println("Error: " + e.getLocalizedMessage());
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
            System.out.println("Error: " + e.getLocalizedMessage());
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
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
     }
}
