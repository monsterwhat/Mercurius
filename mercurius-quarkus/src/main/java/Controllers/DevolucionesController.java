package Controllers;

import Controllers.SessionController;
import Models.AppSettings;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Users;
import Models.Detalles.CodigoComercial;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Descuento;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Encabezado.MedioPago;
import Models.Inventario;
import Models.NotaCredito;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.ResumenFactura;
import Models.Resumen.CodigoTipoMoneda;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ClientService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.ConsecutivoEmitidoService;
import Services.InventarioService;
import Services.NotaCreditoService;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import jakarta.annotation.PostConstruct;
import java.math.RoundingMode;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString @EqualsAndHashCode
@Named("devolucionesController")
@ViewScoped
public class DevolucionesController implements Serializable {

    @Inject @Nonnull
    private ComprobantesEmitidosService comprobantesService;

    @Inject @Nonnull
    private NotaCreditoService notaCreditoService;

    @Inject @Nonnull
    private InventarioService inventarioService;

    @Inject @Nonnull
    private ClientService clientService;

    @Inject @Nonnull
    private SessionController sessionController;

    @Inject @Nonnull
    private AlertasService alertasService;

    @Inject @Nonnull
    private AppSettingsService appSettingsService;

    @Inject @Nonnull
    private DocumentoStrategyFactory strategyFactory;

    @Inject @Nonnull
    private Services.HaciendaSigner haciendaSigner;

    @Inject @Nonnull
    private ComprobanteService comprobanteService;

    @Inject @Nonnull
    private ConsecutivoEmitidoService consecutivoEmitidoService;

    @Nullable
    private String criterioBusqueda;
    @Nonnull
    private String tipoBusqueda;
    @Nullable
    private ComprobantesEmitidos facturaSeleccionada;
    @Nonnull
    private List<ComprobantesEmitidos> facturasEncontradas;
    @Nullable
    private List<LineaDevolucion> lineasDevolucion;
    @Nullable
    private String motivo;
    @Nonnull
    private BigDecimal totalDevolucion;
    @Nonnull
    private List<NotaCredito> historialNotas;

    @Nullable
    private String authUsername;
    @Nullable
    private String authPassword;
    @Nullable
    private String authorizedBy;

    @PostConstruct
    public void init() {
        lineasDevolucion = new ArrayList<>();
        facturasEncontradas = new ArrayList<>();
        historialNotas = notaCreditoService.listAll();
        totalDevolucion = BigDecimal.ZERO;
        tipoBusqueda = "consecutivo";
    }

    public void buscarFactura() {
        if (criterioBusqueda == null || criterioBusqueda.trim().isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Busqueda", "Ingrese un criterio de busqueda"));
            return;
        }

        if ("consecutivo".equals(tipoBusqueda)) {
            List<ComprobantesEmitidos> todas = comprobantesService.listAll();
            facturasEncontradas = new ArrayList<>();
            if (todas != null) {
                for (ComprobantesEmitidos f : todas) {
                    if (f.getEncabezado() != null
                        && f.getEncabezado().getNumeroConsecutivo() != null
                        && f.getEncabezado().getNumeroConsecutivo().contains(criterioBusqueda)) {
                        facturasEncontradas.add(f);
                    }
                }
            }
        } else {
            List<Clients> clients = clientService.searchByName(criterioBusqueda);
            if (clients != null && !clients.isEmpty()) {
                Clients client = clients.get(0);
                facturasEncontradas = comprobantesService.listAll();
                if (facturasEncontradas != null) {
                    facturasEncontradas.removeIf(f ->
                        f.getEncabezado() == null
                        || f.getEncabezado().getReceptor() == null
                        || !criterioBusqueda.toLowerCase().contains(
                            f.getEncabezado().getReceptor().getNombre().toLowerCase()));
                }
            }
        }

        if (facturasEncontradas == null || facturasEncontradas.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Sin resultados", "No se encontraron facturas"));
        }
    }

    public void seleccionarFactura(@Nonnull ComprobantesEmitidos factura) {
        this.facturaSeleccionada = factura;
        lineasDevolucion = new ArrayList<>();
        totalDevolucion = BigDecimal.ZERO;

        if (factura.getDetalles() != null && factura.getDetalles().getLineasDetalle() != null) {
            for (LineaDetalle linea : factura.getDetalles().getLineasDetalle()) {
                LineaDevolucion ld = new LineaDevolucion();
                ld.setLineaDetalle(linea);
                ld.setCantidadOriginal(linea.getCantidad());
                ld.setCantidadDevolver(BigDecimal.ZERO);
                ld.setSeleccionado(false);
                lineasDevolucion.add(ld);
            }
        }

        facturasEncontradas = new ArrayList<>();
    }

    public void recalcularTotal() {
        totalDevolucion = BigDecimal.ZERO;
        if (lineasDevolucion != null) {
            for (LineaDevolucion ld : lineasDevolucion) {
                if (ld.isSeleccionado() && ld.getCantidadDevolver() != null) {
                    BigDecimal importe = ld.getLineaDetalle().getPrecioUnitario()
                        .multiply(ld.getCantidadDevolver());
                    totalDevolucion = totalDevolucion.add(importe);
                }
            }
        }
    }

    public void authorize() {
        Users authUser = sessionController.authorizeAction(authUsername, authPassword);
        if (authUser != null) {
            authorizedBy = authUser.getUsername();
            alertasService.registrarAlerta("Autorización Exitosa",
                "Devolución autorizada por: " + authorizedBy,
                sessionController.getCurrentUser(), 0, "DevolucionesController.authorize()",
                null, null);
            procesarDevolucion();
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Autorización Fallida",
                    "Usuario o contraseña incorrectos"));
        }
    }

    public void procesarDevolucion() {
        if (authorizedBy == null) {
            org.primefaces.PrimeFaces.current().executeScript("PF('AuthDevolucionDialog').show();");
            return;
        }

        if (facturaSeleccionada == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Seleccione una factura primero"));
            return;
        }

        if (motivo == null || motivo.trim().isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Ingrese el motivo de la devolucion"));
            return;
        }

        boolean haySeleccion = false;
        for (LineaDevolucion ld : lineasDevolucion) {
            if (ld.isSeleccionado() && ld.getCantidadDevolver() != null
                && ld.getCantidadDevolver().compareTo(BigDecimal.ZERO) > 0) {
                haySeleccion = true;
                break;
            }
        }

        if (!haySeleccion) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error",
                    "Seleccione al menos un articulo y especifique cantidad a devolver"));
            return;
        }

        try {
            Clients notaCliente = null;
            if (facturaSeleccionada.getEncabezado() != null
                && facturaSeleccionada.getEncabezado().getReceptor() != null
                && facturaSeleccionada.getEncabezado().getReceptor().getNombre() != null) {
                List<Clients> found = clientService.searchByName(
                    facturaSeleccionada.getEncabezado().getReceptor().getNombre());
                if (found != null && !found.isEmpty()) {
                    notaCliente = found.get(0);
                }
            }

            NotaCredito nota = new NotaCredito();
            nota.setComprobanteOriginal(facturaSeleccionada);
            nota.setFecha(new Date());
            nota.setMotivo(motivo);
            nota.setMontoTotal(totalDevolucion);
            nota.setCliente(notaCliente);
            nota.setUsuario(sessionController.getCurrentUser().getUsername());
            nota.setStatus(true);
            nota.setHaciendaEstado("PENDIENTE");
            notaCreditoService.create(nota);

            for (LineaDevolucion ld : lineasDevolucion) {
                if (ld.isSeleccionado() && ld.getCantidadDevolver() != null
                    && ld.getCantidadDevolver().compareTo(BigDecimal.ZERO) > 0) {

                    Inventario inv = new Inventario();
                    inv.setArticulo(null);
                    inv.setCantidad(ld.getCantidadDevolver().negate());
                    inv.setTipoMovimiento("Devolucion");
                    inv.setUsuario(sessionController.getCurrentUser());
                    inv.setFechaMovimiento(new Date());
                    inv.setNotas("Devolucion factura: "
                        + facturaSeleccionada.getEncabezado().getNumeroConsecutivo()
                        + " - " + motivo);
                    inv.setStatus(true);
                    inv.setProcessed(true);
                    inventarioService.create(inv);
                }
            }

            // Generate Hacienda Nota de Credito Electronica
            try {
                AppSettings appSettings = appSettingsService.returnCurrent();
                if (appSettings != null && facturaSeleccionada.getEncabezado() != null) {
                    Clients client = null;
                    if (facturaSeleccionada.getEncabezado().getReceptor() != null
                        && facturaSeleccionada.getEncabezado().getReceptor().getNombre() != null) {
                        List<Clients> clients = clientService.searchByName(
                            facturaSeleccionada.getEncabezado().getReceptor().getNombre());
                        if (clients != null && !clients.isEmpty()) {
                            client = clients.get(0);
                        }
                    }

                    DocumentoStrategy ncStrategy = strategyFactory.forCode("03");
                    String sucursal = appSettings.getCodigoSucursal() != null ? appSettings.getCodigoSucursal() : "001";
                    String terminal = appSettings.getCodigoTerminal() != null ? appSettings.getCodigoTerminal() : "001";
                    long consecutivo = consecutivoEmitidoService.getNextSequential(sucursal, terminal, ncStrategy.getCodigoDocumento());
                    String numeroConsecutivo = String.format("%s%s%s%010d",
                        sucursal, terminal,
                        ncStrategy.getCodigoDocumento(), consecutivo);

                    Encabezado ncEncabezado = ncStrategy.buildEncabezado(appSettings, client);
                    ncEncabezado.setNumeroConsecutivo(numeroConsecutivo);

                    List<MedioPago> medioPagoList = new ArrayList<>();
                    MedioPago medio = new MedioPago();
                    medio.setMedioPago("01");
                    medio.setComprobante(ncEncabezado);
                    medioPagoList.add(medio);
                    ncEncabezado.setMedioPago(medioPagoList);

                    String clave = haciendaSigner.generateInvoiceKey(
                        appSettings.getIdentificacion(), numeroConsecutivo, "1",
                        ncEncabezado.getFechaEmision().toLocalDate());
                    ncEncabezado.setClave(clave);

                    DetalleServicio ncDetalles = new DetalleServicio();
                    List<LineaDetalle> ncLineas = new ArrayList<>();
                    int lineNum = 0;
                    for (LineaDevolucion ld : lineasDevolucion) {
                        if (ld.isSeleccionado() && ld.getCantidadDevolver() != null
                            && ld.getCantidadDevolver().compareTo(BigDecimal.ZERO) > 0) {
                            LineaDetalle ol = ld.getLineaDetalle();
                            LineaDetalle nl = new LineaDetalle();
                            nl.setNumeroLinea(lineNum++);
                            nl.setCodigoCabys(ol.getCodigoCabys());
                            if (ol.getCodigosComerciales() != null) {
                                List<CodigoComercial> ccs = new ArrayList<>();
                                for (CodigoComercial cc : ol.getCodigosComerciales()) {
                                    CodigoComercial ncc = new CodigoComercial();
                                    ncc.setTipo(cc.getTipo());
                                    ncc.setCodigo(cc.getCodigo());
                                    ncc.setLineaDetalle(nl);
                                    ccs.add(ncc);
                                }
                                nl.setCodigosComerciales(ccs);
                            }
                            nl.setCantidad(ld.getCantidadDevolver());
                            nl.setUnidadMedida(ol.getUnidadMedida());
                            nl.setUnidadMedidaComercial(ol.getUnidadMedidaComercial());
                            nl.setDetalle(ol.getDetalle());
                            nl.setPrecioUnitario(ol.getPrecioUnitario());
                            BigDecimal montoTotal = ol.getPrecioUnitario().multiply(ld.getCantidadDevolver());
                            nl.setMontoTotal(montoTotal);
                            nl.setSubTotal(montoTotal);
                            nl.setMontoTotalLinea(montoTotal);

                            BigDecimal factor = ld.getCantidadDevolver().divide(ol.getCantidad(), 6, RoundingMode.HALF_UP);
                            if (ol.getImpuestos() != null) {
                                List<Impuesto> imps = new ArrayList<>();
                                for (Impuesto imp : ol.getImpuestos()) {
                                    Impuesto ni = new Impuesto();
                                    ni.setCodigo(imp.getCodigo());
                                    ni.setCodigoTarifaIVA(imp.getCodigoTarifaIVA());
                                    ni.setTarifa(imp.getTarifa());
                                    ni.setMonto(imp.getMonto() != null
                                        ? imp.getMonto().multiply(factor).setScale(5, RoundingMode.HALF_UP)
                                        : BigDecimal.ZERO);
                                    ni.setLineaDetalle(nl);
                                    if (imp.getExoneracion() != null) {
                                        Models.Detalles.Exoneracion origExo = imp.getExoneracion();
                                        Models.Detalles.Exoneracion newExo = new Models.Detalles.Exoneracion();
                                        newExo.setTipoDocumentoEX1(origExo.getTipoDocumentoEX1());
                                        newExo.setTipoDocumentoOTRO(origExo.getTipoDocumentoOTRO());
                                        newExo.setNumeroDocumento(origExo.getNumeroDocumento());
                                        newExo.setArticulo(origExo.getArticulo());
                                        newExo.setInciso(origExo.getInciso());
                                        newExo.setNombreInstitucion(origExo.getNombreInstitucion());
                                        newExo.setNombreInstitucionOtros(origExo.getNombreInstitucionOtros());
                                        newExo.setFechaEmisionEX(origExo.getFechaEmisionEX());
                                        newExo.setTarifaExonerada(origExo.getTarifaExonerada());
                                        newExo.setMontoExoneracion(origExo.getMontoExoneracion());
                                        newExo.setImpuesto(ni);
                                        ni.setExoneracion(newExo);
                                    }
                                    imps.add(ni);
                                }
                                nl.setImpuestos(imps);
                            }
                            if (ol.getDescuentos() != null) {
                                List<Descuento> descs = new ArrayList<>();
                                for (Descuento d : ol.getDescuentos()) {
                                    Descuento nd = new Descuento();
                                    nd.setCodigoDescuento(d.getCodigoDescuento());
                                    nd.setNaturalezaDescuento(d.getNaturalezaDescuento());
                                    nd.setMontoDescuento(d.getMontoDescuento().multiply(factor).setScale(5, RoundingMode.HALF_UP));
                                    nd.setLineaDetalle(nl);
                                    descs.add(nd);
                                }
                                nl.setDescuentos(descs);
                            }
                            nl.setDetalleServicio(ncDetalles);
                            ncLineas.add(nl);
                        }
                    }
                    ncDetalles.setLineasDetalle(ncLineas);
                    ncDetalles.setStatus(true);

                    ResumenFactura ncResumen = new ResumenFactura();
                    CodigoTipoMoneda moneda = new CodigoTipoMoneda();
                    moneda.setCodigoMoneda("CRC");
                    ncResumen.setCodigoMoneda(moneda);
                    BigDecimal totalGravado = BigDecimal.ZERO;
                    BigDecimal totalExento = BigDecimal.ZERO;
                    BigDecimal totalExonerado = BigDecimal.ZERO;
                    BigDecimal totalServGravados = BigDecimal.ZERO;
                    BigDecimal totalMercGravadas = BigDecimal.ZERO;
                    BigDecimal totalServExentos = BigDecimal.ZERO;
                    BigDecimal totalMercExentas = BigDecimal.ZERO;
                    BigDecimal totalServExonerado = BigDecimal.ZERO;
                    BigDecimal totalMercExonerada = BigDecimal.ZERO;
                    BigDecimal totalVenta = BigDecimal.ZERO;
                    BigDecimal totalDescuento = BigDecimal.ZERO;
                    BigDecimal totalImpuesto = BigDecimal.ZERO;
                    java.util.Map<BigDecimal, BigDecimal> taxByRate = new java.util.HashMap<>();
                    BigDecimal totalIVADevuelto = BigDecimal.ZERO;
                    for (LineaDetalle linea : ncLineas) {
                        totalVenta = totalVenta.add(linea.getMontoTotal());
                        if (linea.getDescuentos() != null) {
                            totalDescuento = totalDescuento.add(linea.getDescuentos().stream()
                                .map(Descuento::getMontoDescuento).reduce(BigDecimal.ZERO, BigDecimal::add));
                        }
                        boolean hasTax = linea.getImpuestos() != null && !linea.getImpuestos().isEmpty();
                        boolean hasExoneracion = hasTax && linea.getImpuestos().stream()
                            .anyMatch(i -> i.getExoneracion() != null);
                        if (hasExoneracion) {
                            totalExonerado = totalExonerado.add(linea.getMontoTotal());
                            totalServExonerado = totalServExonerado.add(linea.getMontoTotal());
                            totalMercExonerada = totalMercExonerada.add(linea.getMontoTotal());
                        } else if (hasTax) {
                            totalGravado = totalGravado.add(linea.getMontoTotal());
                            totalMercGravadas = totalMercGravadas.add(linea.getMontoTotal());
                            for (Impuesto i : linea.getImpuestos()) {
                                if (i.getMonto() != null) {
                                    totalImpuesto = totalImpuesto.add(i.getMonto());
                                }
                                if (i.getTarifa() != null) {
                                    taxByRate.merge(i.getTarifa(), i.getMonto() != null ? i.getMonto() : BigDecimal.ZERO, BigDecimal::add);
                                    if ("04".equals(i.getTarifa().toPlainString())) {
                                        totalIVADevuelto = totalIVADevuelto.add(i.getMonto() != null ? i.getMonto() : BigDecimal.ZERO);
                                    }
                                }
                            }
                        } else {
                            totalExento = totalExento.add(linea.getMontoTotal());
                            totalServExentos = totalServExentos.add(linea.getMontoTotal());
                            totalMercExentas = totalMercExentas.add(linea.getMontoTotal());
                        }
                    }
                    BigDecimal totalVentaNeta = totalVenta.subtract(totalDescuento);
                    BigDecimal totalOtrosCargos = Services.ComprobanteService.calcularTotalOtrosCargos(ncDetalles);
                    BigDecimal totalComprobante = totalVentaNeta.add(totalImpuesto)
                            .add(totalOtrosCargos).subtract(totalIVADevuelto);
                    ncResumen.setTotalServGravados(totalServGravados);
                    ncResumen.setTotalServExentos(totalServExentos);
                    ncResumen.setTotalServExonerado(totalServExonerado);
                    ncResumen.setTotalMercanciasGravadas(totalMercGravadas);
                    ncResumen.setTotalMercanciasExentas(totalMercExentas);
                    ncResumen.setTotalMercExonerada(totalMercExonerada);
                    ncResumen.setTotalGravado(totalGravado);
                    ncResumen.setTotalExento(totalExento);
                    ncResumen.setTotalExonerado(totalExonerado);
                    ncResumen.setTotalVenta(totalVenta);
                    ncResumen.setTotalDescuentos(totalDescuento);
                    ncResumen.setTotalVentaNeta(totalVentaNeta);
                    ncResumen.setTotalImpuesto(totalImpuesto);
                    ncResumen.setTotalIVADevuelto(totalIVADevuelto);
                    ncResumen.setTotalOtrosCargos(totalOtrosCargos);
                    ncResumen.setTotalComprobante(totalComprobante);

                    if (!taxByRate.isEmpty()) {
                        java.util.List<Models.Resumen.TotalDesgloseImpuesto> desgloseList = new java.util.ArrayList<>();
                        for (java.util.Map.Entry<BigDecimal, BigDecimal> entry : taxByRate.entrySet()) {
                            try {
                                Models.Enums.Tipo_TarifaIVA tarifa = Models.Enums.Tipo_TarifaIVA.getTarifa(entry.getKey().toPlainString());
                                Models.Resumen.TotalDesgloseImpuesto item = new Models.Resumen.TotalDesgloseImpuesto();
                                item.setCodigo("01");
                                item.setCodigoTarifaIVA(tarifa.getCodigo());
                                item.setTotalMontoImpuesto(entry.getValue().setScale(5, java.math.RoundingMode.HALF_UP));
                                item.setResumenFactura(ncResumen);
                                desgloseList.add(item);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        ncResumen.setTotalDesgloseImpuestos(desgloseList);
                    }

                    InformacionReferencia ref = InformacionReferencia.from(facturaSeleccionada, "01", motivo);
                    List<InformacionReferencia> referencias = new ArrayList<>();
                    referencias.add(ref);

                    ComprobantesEmitidos ncComprobante = new ComprobantesEmitidos();
                    ncComprobante.setEncabezado(ncEncabezado);
                    ncComprobante.setDetalles(ncDetalles);
                    ncComprobante.setResumen(ncResumen);
                    ncComprobante.setInformacionReferencia(referencias);
                    ncComprobante.setUser(sessionController.getCurrentUser().getUsername());
                    ncComprobante.setStatus(true);
                    ncComprobante.setHaciendaClave(clave);
                    ncComprobante.setHaciendaEstado("PENDIENTE");
                    ncEncabezado.setEstado("PENDIENTE");

                    comprobantesService.createAndReturn(ncComprobante);

                    // Send NC immediately to Hacienda per CR 2176 §5.6
                    comprobanteService.enviarComprobanteAHacienda(ncComprobante);

                    alertasService.registrarAlerta("NC Electronica",
                        "Nota de Credito electronica " + numeroConsecutivo + " generada para devolucion",
                        sessionController.getCurrentUser(), 0, "DevolucionesController.procesarDevolucion()",
                        null, null);
                }
            } catch (RuntimeException eNC) {
                alertasService.registrarAlerta("Error NC",
                    "Error al generar Nota de Credito electronica: " + eNC.getMessage(),
                    sessionController.getCurrentUser(), 0, "DevolucionesController.procesarDevolucion()",
                    null, eNC.getMessage());
            }

            alertasService.registrarAlerta("Devolucion procesada",
                "Nota de credito creada por " + totalDevolucion + " - " + motivo,
                sessionController.getCurrentUser(), 0, "DevolucionesController.procesarDevolucion()",
                null, null);

            authorizedBy = null;
            facturaSeleccionada = null;
            lineasDevolucion = new ArrayList<>();
            motivo = null;
            totalDevolucion = BigDecimal.ZERO;
            historialNotas = notaCreditoService.listAll();

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito",
                    "Devolucion procesada correctamente"));

        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error devolucion",
                "Error al procesar devolucion: " + e.getMessage(),
                sessionController.getCurrentUser(), 0, "DevolucionesController.procesarDevolucion()",
                null, e.getMessage());

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error",
                    "Error al procesar devolucion: " + e.getMessage()));
        }
    }

    @Data
    public static class LineaDevolucion implements Serializable {
        @Nullable
        private LineaDetalle lineaDetalle;
        @Nullable
        private BigDecimal cantidadOriginal;
        @Nullable
        private BigDecimal cantidadDevolver;
        private boolean seleccionado;
    }
}
