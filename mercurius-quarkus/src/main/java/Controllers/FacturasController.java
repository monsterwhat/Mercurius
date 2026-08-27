package Controllers;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Detalles.LineaDetalle;
import Models.Detalles.CodigoComercial;
import Models.ComprobantesRecibidos;
import Models.AppSettings;

import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Departamento;
import Utils.DiffUtils;
import Models.Inventario;
import Services.AlertasService;
import Services.ArticuloPrecioService;
import Services.ComprobantesRecibidosService;
import Services.AppSettingsService;
import Services.ComprobanteService;
import Services.DepartamentoService;
import Services.Facturas.*;
import Services.HaciendaApiService;
import Services.HaciendaSigner;
import Services.InventarioService;
import Utils.Parsers.Parser;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 * Legacy JSF backing bean for received invoices — PARTIALLY STRIPPED by
 * plan task T36 (JSF→API migration).
 *
 * <p>The received-invoice-PROCESSING subset lived here until T36 and now
 * lives in {@code Controllers.Api.App.FacturasRecibidasResource}
 * (/api/app/facturas-recibidas): XML upload → Parser, inbox lists/detail,
 * line review corrections, prevalidation panel
 * (ComprobantesRecibidosPrevalidationService), Mensaje Receptor send
 * (aceptar/rechazar/parcial via MensajeReceptorService) and the
 * ConsecutivoReceptor assignment preview. The corresponding methods, fields
 * and injections were removed from this class.</p>
 *
 * <p>OWNERSHIP of what remains:</p>
 * <ul>
 *   <li>Receipt actions (paySelectedFactura, processSelectedFactura/
 *       processFactura article processing, deleteFactura, toggleFactura)
 *       belong to the T27 Recibos lane.</li>
 *   <li>Report methods (initReport/loadReportData/generarReporteReport/
 *       summary ranges) belong to the T20 reportes lane.</li>
 *   <li>The POS checkout (pages/Facturas/Facturas/factura.xhtml) belongs to
 *       the future T37 lane.</li>
 * </ul>
 *
 * <p>This class stays ALIVE because legacy views still EL-reference it:
 * secured/pages/Recibos/index.xhtml and secured/fragments/Facturas/Recibos/*
 * (T27 scope — they also call the REMOVED aceptarFacturaRecibida/
 * rechazarFacturaRecibida/showDetailsDialog, so those legacy buttons are
 * dead until T27 replaces that surface). Delete this class in T27/T39 once
 * those views are migrated.</p>
 */
@Named
@Getter @Setter @ToString @EqualsAndHashCode
@ViewScoped
public class FacturasController implements Serializable {

    @Inject @Nonnull
    ComprobantesRecibidosService facturaService;
    @Inject @Nonnull
    LineaDetalleService lineaDetalleService;
    @Inject @Nonnull
    SessionController currentSession;
    @Inject @Nonnull
    ArticulosController articuloController;
    @Inject @Nonnull
    InventarioService inventarioService;
    @Inject @Nonnull
    DepartamentoService departamentoService;
    @Inject @Nonnull
    ComprobantesRecibidosService comprobantesRecibidosService;
    @Inject @Nonnull
    MedioPagoService medioPagoService;
    @Inject @Nonnull
    ArticuloPrecioService precioService;
    @Inject @Nonnull
    Parser parser;
    @Inject @Nonnull
    AlertasService alertas;
    @Inject @Nonnull
    AppSettingsService appSettingsService;
    @Inject @Nonnull
    ComprobanteService comprobanteService;
    @Inject @Nonnull
    HaciendaApiService haciendaApiService;
    @Inject @Nonnull
    HaciendaSigner haciendaSigner;

    @Nullable
    private List<ComprobantesRecibidos> facturas;
    @Nullable
    private List<ComprobantesRecibidos> facturasDetalladas;
    @Nullable
    private List<ComprobantesRecibidos> facturasVencidas;
    @Nullable
    private List<ComprobantesRecibidos> facturasPendientes;

    @Nullable
    private ComprobantesRecibidos selectedFactura;
    @Nullable
    private String facturaFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    // Report fields
    @Nonnull
    private Date reportFechaInicio;
    @Nonnull
    private Date reportFechaFin;
    @Nonnull
    private BigDecimal reportTotalComprobantes;
    @Nonnull
    private BigDecimal reportTotalImpuesto;
    @Nonnull
    private BigDecimal reportTotalBaseImponible;
    private String selectedSummaryRange = "all";

    @PostConstruct
    public void init() {
        filterBy = new ArrayList<>();
        selectedFactura = new ComprobantesRecibidos();
        initReport();
    }
    
    // Report methods
    public void initReport() {
        reportFechaInicio = new Date();
        reportFechaFin = new Date();
        loadReportData();
    }
    
    public void loadReportData() {
        reportTotalComprobantes = BigDecimal.ZERO;
        reportTotalImpuesto = BigDecimal.ZERO;
        reportTotalBaseImponible = BigDecimal.ZERO;
        
        if (facturas == null) {
            facturas = facturaService.listAll();
        }

        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate cutoff = switch (selectedSummaryRange) {
            case "1m" -> now.minusMonths(1);
            case "3m" -> now.minusMonths(3);
            case "1y" -> now.minusYears(1);
            default -> null;
        };
        
        for (ComprobantesRecibidos factura : facturas) {
            if (cutoff != null) {
                if (factura.getEncabezado() == null || factura.getEncabezado().getFechaEmision() == null) {
                    continue;
                }
                java.time.LocalDate fecha = factura.getEncabezado().getFechaEmision().toLocalDate();
                if (fecha.isBefore(cutoff)) {
                    continue;
                }
            }
            if (factura.getResumen() != null) {
                if (factura.getResumen().getTotalComprobante() != null) {
                    reportTotalComprobantes = reportTotalComprobantes.add(factura.getResumen().getTotalComprobante());
                }
                if (factura.getResumen().getTotalImpuesto() != null) {
                    reportTotalImpuesto = reportTotalImpuesto.add(factura.getResumen().getTotalImpuesto());
                }
                if (factura.getResumen().getTotalMercanciasGravadas() != null) {
                    reportTotalBaseImponible = reportTotalBaseImponible.add(factura.getResumen().getTotalMercanciasGravadas());
                }
            }
        }
    }
    
    public void generarReporteReport() {
        if (reportFechaInicio != null && reportFechaFin != null) {
            final Date fechaFinCalc = new Date(reportFechaFin.getTime() + 86400000);
            
            facturas = facturaService.listAll().stream()
                .filter(f -> {
                    if (f.getEncabezado() == null || f.getEncabezado().getFechaEmision() == null) {
                        return false;
                    }
                    Object fechaObj = f.getEncabezado().getFechaEmision();
                    Date fecha;
                    if (fechaObj instanceof java.time.LocalDateTime) {
                        fecha = Date.from(((java.time.LocalDateTime) fechaObj).atZone(java.time.ZoneId.systemDefault()).toInstant());
                    } else if (fechaObj instanceof Date) {
                        fecha = (Date) fechaObj;
                    } else {
                        return false;
                    }
                    return !fecha.before(reportFechaInicio) && fecha.before(fechaFinCalc);
                })
                .collect(Collectors.toList());
            loadReportData();
        }
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Reporte Generado", 
                "Se han calculado los impuestos de " + facturas.size() + " comprobantes"));
    }
    
    public void limpiarFiltrosReport() {
        reportFechaInicio = new Date();
        reportFechaFin = new Date();
        selectedSummaryRange = "all";
        loadReportData();
    }

    public void setSummaryRange(String range) {
        selectedSummaryRange = range;
        loadReportData();
    }
    
    public int getTotalFacturasReporte() {
        return facturas != null ? facturas.size() : 0;
    }
    
    public int getFacturasProcesadasReporte() {
        if (facturas == null) return 0;
        return (int) facturas.stream()
            .filter(f -> f.getProcessed() != null && f.getProcessed())
            .count();
    }
    
    public int getFacturasPendientesReporte() {
        if (facturas == null) return 0;
        return (int) facturas.stream()
            .filter(f -> f.getProcessed() == null || !f.getProcessed())
            .count();
    }

    public @Nullable List<ComprobantesRecibidos> facturasList() {
        if (facturas == null) {
            facturas = facturaService.ListAllEnabled();
        }
        return facturas;
    }

    public @Nullable List<ComprobantesRecibidos> facturasListDetalladas() {
        if (facturasDetalladas == null) {
            facturasDetalladas = facturaService.listAll();
        }
        return facturasDetalladas;
    }

    public @Nullable List<ComprobantesRecibidos> facturasPenditenes() {
        if (facturasPendientes == null) {
            facturasPendientes = facturaService.listPendientes();
        }
        return facturasPendientes;
    }

    public List<ComprobantesRecibidos> facturasVencidas() {
        if (facturasVencidas == null) {
            facturasVencidas = facturaService.listVencidas();
        }
        return facturasVencidas != null ? facturasVencidas : java.util.Collections.emptyList();
    }

    public long facturaCount() {
        return facturaService.count();
    }
    
    public long facturasActivasCount() {
        return facturasList().stream().filter(f -> f.getStatus() != null && f.getStatus()).count();
    }
    
    public long facturasInactivasCount() {
        return facturasList().stream().filter(f -> f.getStatus() == null || !f.getStatus()).count();
    }
    
    public long facturasPendientesCount() {
        return facturasPenditenes().size();
    }
    
    public long facturasPagadasCount() {
        return facturasList().stream().filter(f -> f.getPaid() != null && f.getPaid()).count();
    }
    
    public long facturasProcesadasCount() {
        return facturasList().stream().filter(f -> f.getProcessed() != null && f.getProcessed()).count();
    }
    
    public long facturasVencidasCount() {
        List<ComprobantesRecibidos> vencidas = facturasVencidas();
        return vencidas != null ? vencidas.size() : 0;
    }

    public void deleteFactura() {
        if (selectedFactura != null) {
            String antes = DiffUtils.snapshotEntity(selectedFactura);
            facturaService.softDelete(selectedFactura);
            alertas.registrarAlerta("Factura eliminada", "La factura #" + selectedFactura.getId() + "fue eliminada", currentSession.getCurrentUser(), 0, "deleteFactura()", antes, DiffUtils.snapshotEntity(selectedFactura));
            clearFactura();
        }
    }

    public void toggleFactura() {
        if (selectedFactura != null) {
            String antes = DiffUtils.snapshotEntity(selectedFactura);
            facturaService.toggle(selectedFactura);
            alertas.registrarAlerta("Factura toggled", "Se cambio el estado de la factura", currentSession.getCurrentUser(), 0, "toggleFactura()", antes, DiffUtils.snapshotEntity(selectedFactura));
        }
    }

    public void clearFactura() {
        selectedFactura = null;
    }

    public void clearCache() {
        facturas = null;
        facturasDetalladas = null;
        facturasPendientes = null;
        facturasVencidas = null;
    }

    public @Nonnull List<ComprobantesRecibidos> getFilteredFacturas() {
        if (facturas == null) {
            facturas = facturaService.ListAllEnabled();
        }
        if (facturas == null) {
            return new ArrayList<>();
        }
        if (facturaFilter != null && !facturaFilter.isEmpty()) {
            return facturasList().stream()
                    .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return facturasList();
        }
    }

    public @Nonnull List<ComprobantesRecibidos> getFilteredFacturasDetallados() {
        try {
            if (facturasDetalladas == null) {
                facturasDetalladas = facturaService.listAll();
            }
            if (facturasDetalladas == null) {
                return new ArrayList<>();
            }
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return facturasListDetalladas().stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return facturasListDetalladas();
            }
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasDetallados()", null, e.getLocalizedMessage());
            return new ArrayList<>();
        }
    }

    @Nullable
    public List<ComprobantesRecibidos> getFilteredFacturasPendientes() {
        try {
            if (facturasPendientes == null) {
                facturasPendientes = facturaService.listPendientes();
            }
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return facturasPenditenes().stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return facturasPenditenes();
            }
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasPendientes()", null, e.getLocalizedMessage());
            return null;
        }
    }

    @Nullable
    public List<ComprobantesRecibidos> getFilteredFacturasVencidas() {
        try {
            if (facturasVencidas == null) {
                facturasVencidas = facturaService.listVencidas();
            }
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return facturasVencidas().stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return facturasVencidas();
            }
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasVencidas()", null, e.getLocalizedMessage());
            return null;
        }
    }
    
    @Nullable
    public List<ComprobantesRecibidos> getFilteredFacturasActivas() {
        try {
            if (facturas == null) {
                facturas = facturaService.ListAllEnabled();
            }
            List<ComprobantesRecibidos> activas = facturasList().stream()
                    .filter(f -> f.getStatus() != null && f.getStatus())
                    .collect(Collectors.toList());
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return activas.stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return activas;
            }
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasActivas()", null, e.getLocalizedMessage());
            return null;
        }
    }
    
    @Nullable
    public List<ComprobantesRecibidos> getFilteredFacturasPagadas() {
        try {
            if (facturas == null) {
                facturas = facturaService.ListAllEnabled();
            }
            List<ComprobantesRecibidos> pagadas = facturasList().stream()
                    .filter(f -> f.getPaid() != null && f.getPaid())
                    .collect(Collectors.toList());
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return pagadas.stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return pagadas;
            }
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasPagadas()", null, e.getLocalizedMessage());
            return null;
        }
    }
    
    @Nullable
    public List<ComprobantesRecibidos> getFilteredFacturasProcesadas() {
        try {
            if (facturas == null) {
                facturas = facturaService.ListAllEnabled();
            }
            List<ComprobantesRecibidos> procesadas = facturasList().stream()
                    .filter(f -> f.getProcessed() != null && f.getProcessed())
                    .collect(Collectors.toList());
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return procesadas.stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return procesadas;
            }
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasProcesadas()", null, e.getLocalizedMessage());
            return null;
        }
    }

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        ComprobantesRecibidos factura = (ComprobantesRecibidos) value;
        Encabezado encabezado = factura.getEncabezado();
        Emisor emisor = encabezado.getEmisor();

        return containsIgnoreCase(encabezado.getCodigoActividadEmisor(), filterText)
                || containsIgnoreCase(encabezado.getCondicionVenta(), filterText)
                || containsIgnoreCase(emisor.getNombre(), filterText)
                || containsCorreoEmisorIgnoreCase(emisor.getCorreosElectronicos(), filterText)
                || containsIgnoreCase(emisor.getIdentificacion().getNumero(), filterText)
                || containsIgnoreCase(emisor.getNombreComercial(), filterText)
                || containsIgnoreCase(String.valueOf(encabezado.getFechaEmision()), filterText)
                || containsIgnoreCase(encabezado.getNumeroConsecutivo(), filterText);

    }

    // Helper for single strings
    private boolean containsIgnoreCase(String source, String filterText) {
        return source != null && filterText != null && source.toLowerCase().contains(filterText);
    }

    private boolean containsCorreoEmisorIgnoreCase(List<CorreoElectronicoEmisor> correos, String filterText) {
        if (correos == null || filterText == null) {
            return false;
        }
        return correos.stream()
                .map(CorreoElectronicoEmisor::getCorreo)
                .filter(Objects::nonNull)
                .anyMatch(correo -> correo.toLowerCase().contains(filterText));
    }

    public void processSelectedFactura() {
        if (selectedFactura != null) {
            if (!selectedFactura.getProcessed() && selectedFactura.getStatus()) {
                processFactura(selectedFactura);
                FacesMessage message = new FacesMessage("Exito", "Se procesaron los articulos de la factura!");
                FacesContext.getCurrentInstance().addMessage(null, message);
                alertas.registrarAlerta("Factura Procesada", "Se procesaron los articulos de la factura #" + selectedFactura.getId(), currentSession.getCurrentUser(), 0, "processSelectedFactura()", selectedFactura.toString(), null);
            } else {
                FacesMessage message = new FacesMessage("Oops!", "La factura ya fue procesada.");
                FacesContext.getCurrentInstance().addMessage(null, message);
            }
        } else {
            FacesMessage message = new FacesMessage("Error", "No hay una factura seleccionada");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }

    public void paySelectedFactura() {
        if (selectedFactura != null) {
            if (!selectedFactura.getPaid() && Boolean.TRUE.equals(selectedFactura.getStatus())) {
                selectedFactura.setPaid(Boolean.TRUE);
                facturaService.update(selectedFactura);
                clearCache();
                FacesMessage message = new FacesMessage("Exito", "Se marco la factura como pagada!");
                FacesContext.getCurrentInstance().addMessage(null, message);
                alertas.registrarAlerta("Factura Pagada", "Se marco la factura #" + selectedFactura.getId() + " como pagada", currentSession.getCurrentUser(), 0, "paySelectedFactura()", selectedFactura.toString(), null);
            } else {
                FacesMessage message = new FacesMessage("Oops!", "La factura ya fue pagada.");
                FacesContext.getCurrentInstance().addMessage(null, message);
            }
        } else {
            FacesMessage message = new FacesMessage("Error", "No hay una factura seleccionada");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }

    private void processFactura(ComprobantesRecibidos factura) {
        try {

            List<LineaDetalle> lineasDetalle = factura.getDetalles() != null ? 
                factura.getDetalles().getLineasDetalle() : new ArrayList<>();
            if (lineasDetalle.isEmpty()) {
                alertas.registrarAlerta("Info", "Empty factura?", currentSession.getCurrentUser(), 0, "FacturasController.processFactura()", null, null);
                lineasDetalle = lineaDetalleService.listAllWhereID(factura.getDetalles().getId());
                if (lineasDetalle.isEmpty() || lineasDetalle == null) {
                    return;
                }
            }

            for (LineaDetalle lineaDetalle : lineasDetalle) {
                String codigoBarra = "";
                String nombre = lineaDetalle.getDetalle();
                List<CodigoComercial> codigosComercialesLineaDetalle = lineaDetalle.getCodigosComerciales();

                for (CodigoComercial codigoComercial : codigosComercialesLineaDetalle) {
                    if (codigoComercial.getTipo().contains("03")) {
                        codigoBarra = codigoComercial.getCodigo();
                    }
                }

                Articulos articuloExistente = (codigoBarra.isEmpty())
                        ? articuloController.findArticuloByName(nombre)
                        : articuloController.findArticuloByBarCode(codigoBarra);

                var cantidad = lineaDetalle.getCantidad();
                String codigoCabys = lineaDetalle.getCodigoCabys();
                String unidadMedida = lineaDetalle.getUnidadMedida();
                String unidadMedidaComercial = lineaDetalle.getUnidadMedidaComercial();
                var montoTotalLinea = lineaDetalle.getMontoTotalLinea();
                var totalUnitario = cantidad != null && cantidad.compareTo(BigDecimal.ZERO) != 0
                    ? montoTotalLinea.divide(cantidad, 20, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                var precioUnitario = totalUnitario;
                var UnidadesParseadas = parser.parseUnidadMedida(unidadMedida, unidadMedidaComercial).multiply(cantidad);

                Articulos articulo = new Articulos();

                Departamento departamento = new Departamento();
                departamento.setNombre(factura.getEncabezado().getEmisor().getNombre());
                departamento.setStatus(true);
                departamento.setUsuario(currentSession.getCurrentUser());
                Departamento persistedDepartamento = departamentoService.createIfNotExist(departamento);

                if (articuloExistente == null) {
                    articulo.setNombre(nombre);
                    articulo.setCodigoBarra(codigoBarra);
                    articulo.setRecomendacionCabys(codigoCabys);
                    articulo.setDepartamento(persistedDepartamento);
                    articulo.setUnidadMedida(unidadMedida);
                    articulo.setUnidadMedidaComercial(unidadMedidaComercial);

                    ArticuloPrecio precioArticulo = new ArticuloPrecio();
                    precioArticulo.setArticulo(articulo);
                    precioArticulo.setPrecioCostoSinIVA(precioUnitario);

                    List<ArticuloPrecio> preciosArticulos = new ArrayList<>();
                    preciosArticulos.add(precioArticulo);

                    articulo.setPrecios(preciosArticulos);

                    articulo.setUsuario(currentSession.getCurrentUser());
                    articulo.setStatus(true);
                    articulo.setProcessed(false);
                    articuloController.createSimpleArticulo(articulo);
                } else {
                    articuloExistente.setRecomendacionCabys(codigoCabys);
                    articuloExistente.setUsuario(currentSession.getCurrentUser());
                    articuloExistente.setUnidadMedida(unidadMedida);
                    articuloExistente.setUnidadMedidaComercial(unidadMedidaComercial);
                    articuloExistente.setDepartamento(persistedDepartamento);

                    ArticuloPrecio precioArticulo = new ArticuloPrecio();

                    precioArticulo.setArticulo(articuloExistente);
                    precioArticulo.setPrecioCostoSinIVA(precioUnitario);
                    precioArticulo.setPrecioConUtilidad(BigDecimal.ZERO);
                    precioArticulo.setPrecioFinal(BigDecimal.ZERO);

                    List<ArticuloPrecio> preciosArticulos = articuloExistente.getPrecios();
                    if (preciosArticulos == null) {
                        preciosArticulos = new ArrayList<>();
                    }
                    preciosArticulos.add(precioArticulo);

                    articuloExistente.setPrecios(preciosArticulos);
                    articuloExistente.setStatus(true);
                    articuloExistente.setProcessed(false);
                    articuloController.updateSimpleArticulo(articuloExistente);
                }

                String codigoDocumento = factura.getEncabezado() != null ? factura.getEncabezado().getCodigoDocumento() : null;
                boolean isNotaCredito = "02".equals(codigoDocumento);

                Inventario ajusteArticulo = new Inventario();

                if (articuloExistente != null) {
                    ajusteArticulo.setArticulo(articuloExistente);
                } else {
                    ajusteArticulo.setArticulo(articulo);
                }
                ajusteArticulo.setUnidadesRecomendadasFactura(UnidadesParseadas);
                ajusteArticulo.setUsuario(currentSession.getCurrentUser());
                ajusteArticulo.setFechaMovimiento(new Date());
                ajusteArticulo.setTipoMovimiento(isNotaCredito ? "Egreso Automatico por nota de credito" : "Ingreso Automatico por factura");
                ajusteArticulo.setStatus(true);
                ajusteArticulo.setProcessed(false);
                ajusteArticulo.setCantidad(isNotaCredito ? cantidad.negate() : cantidad);
                ajusteArticulo.setNotas(isNotaCredito ? "Nota de credito - egreso de inventario" : "");

                // T35 removed Controllers.InventarioController; this call site kept its exact behavior through the service (createSimpleInventario delegated to inventarioService.create).
                inventarioService.create(ajusteArticulo);
            }

            factura.setProcessed(true);
            facturaService.update(factura);
            alertas.registrarAlerta("Factura Procesada", "Se procesaron los artículos de la factura #" + factura.getId(), currentSession.getCurrentUser(), 0, "processFactura()", factura.toString(), null);
            clearCache();

        } catch (RuntimeException e) { 
            alertas.registrarAlerta("Error al procesar factura", e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "facturasController.processFactura()", e.getLocalizedMessage(), null);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al procesar factura: " + e.getLocalizedMessage());
            FacesContext.getCurrentInstance().addMessage(null, message);
            alertas.registrarAlerta("Error", "Error processing factura: " + e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "FacturasController.processFactura()", null, e.getLocalizedMessage());
        }
    }

    public void cancel() {
        alertas.registrarAlerta("Info", "Cajero: " + currentSession.getCurrentUser().getUsername() + " cancelo factura", currentSession.getCurrentUser(), 0, "FacturasController.cancel()", null, null);
        alertas.registrarAlerta("Factura eliminada", "Se elimino una factura pendiente", currentSession.getCurrentUser(), 0, "cancel()", "", "");
    }

}
