package Controllers;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Detalles.LineaDetalle;
import Models.Detalles.CodigoComercial;
import Models.ComprobantesRecibidos;
import Models.AppSettings;
import Models.Validacion.PrevalidationResult;
import Models.Validacion.ValidationError;

import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Departamento;
import Models.Inventario;
import Services.AlertasService;
import Services.ArticuloPrecioService;
import Services.ComprobantesRecibidosPrevalidationService;
import Services.ComprobantesRecibidosService;
import Services.AppSettingsService;
import Services.ComprobanteService;
import Services.Facturas.*;
import Services.HaciendaApiService;
import Services.HaciendaSigner;
import Services.ConsecutivoReceptorService;
import Utils.Parsers.Parser;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList; 
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.file.UploadedFile;
import org.primefaces.util.LangUtils;

@Named
@Data
@ViewScoped
public class FacturasController implements Serializable {
    
    private static final Object fileUploadLock = new Object();

    @Inject
    ComprobantesRecibidosService facturaService;
    @Inject
    LineaDetalleService lineaDetalleService;
    @Inject
    SessionController currentSession;
    @Inject
    ArticulosController articuloController;
    @Inject
    InventarioController inventarioController;
    @Inject
    DepartamentoController departamentosController;
    @Inject
    ComprobantesRecibidosService comprobantesRecibidosService;
    @Inject
    MedioPagoService medioPagoService;
    @Inject
    ArticuloPrecioService precioService;
    @Inject
    Parser parser;
    @Inject
    AlertasService alertas;
    @Inject
    AppSettingsService appSettingsService;
    @Inject
    ComprobanteService comprobanteService;
    @Inject
    HaciendaApiService haciendaApiService;
    @Inject
    HaciendaSigner haciendaSigner;
    @Inject
    ComprobantesRecibidosPrevalidationService prevalidationService;
    @Inject
    ConsecutivoReceptorService consecutivoReceptorService;

    private PrevalidationResult prevalidationResult;

    private List<UploadedFile> files;
    private List<ComprobantesRecibidos> facturas;
    private List<ComprobantesRecibidos> facturasDetalladas;
    private List<ComprobantesRecibidos> facturasVencidas;
    private List<ComprobantesRecibidos> facturasPendientes;

    private LineaDetalle lineaDetalle;

    private Set<Long> lineasAceptadas = new HashSet<>();

    private ComprobantesRecibidos selectedFactura;
    private String facturaFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    // Report fields
    private Date reportFechaInicio;
    private Date reportFechaFin;
    private BigDecimal reportTotalComprobantes;
    private BigDecimal reportTotalImpuesto;
    private BigDecimal reportTotalBaseImponible;

    @PostConstruct
    public void init() {
        files = new ArrayList<>();
        filterBy = new ArrayList<>();
        selectedFactura = new ComprobantesRecibidos();
        lineasAceptadas = new HashSet<>();
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
        
        for (ComprobantesRecibidos factura : facturas) {
            if (factura.getResumen() != null) {
                if (factura.getResumen().getTotalComprobante() != null) {
                    reportTotalComprobantes = reportTotalComprobantes.add(factura.getResumen().getTotalComprobante());
                }
                if (factura.getResumen().getTotalImpuesto() != null) {
                    reportTotalImpuesto = reportTotalImpuesto.add(factura.getResumen().getTotalImpuesto());
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
        initReport();
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

    public List<ComprobantesRecibidos> facturasList() {
        if (facturas == null) {
            facturas = facturaService.ListAllEnabled();
        }
        return facturas;
    }

    public List<ComprobantesRecibidos> facturasListDetalladas() {
        if (facturasDetalladas == null) {
            facturasDetalladas = facturaService.listAll();
        }
        return facturasDetalladas;
    }

    public List<ComprobantesRecibidos> facturasPenditenes() {
        if (facturasPendientes == null) {
            facturasPendientes = facturaService.listPendientes();
        }
        return facturasPendientes;
    }

    public List<ComprobantesRecibidos> facturasVencidas() {
        if (facturasVencidas == null) {
            facturasVencidas = facturaService.listVencidas();
        }
        return facturasVencidas;
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
        return facturasVencidas().size();
    }

    public void deleteFactura() {
        if (selectedFactura != null) {
            var oldFactura = selectedFactura;
            facturaService.softDelete(selectedFactura);
            alertas.registrarAlerta("Factura eliminada", "La factura #" + selectedFactura.getId() + "fue eliminada", currentSession.getCurrentUser(), 0, "deleteFactura()", oldFactura.toString(), selectedFactura.toString());
            clearFactura();
        }
    }

    public void toggleFactura() {
        if (selectedFactura != null) {
            var oldFactura = selectedFactura;
            facturaService.toggle(selectedFactura);
            alertas.registrarAlerta("Factura toggled", "Se cambio el estado de la factura", currentSession.getCurrentUser(), 0, "toggleFactura()", oldFactura.toString(), selectedFactura.toString());
        }
    }

    public void clearFactura() {
        selectedFactura = null;
        lineasAceptadas = new HashSet<>();
    }

    public void showDetailsDialog() {
        if (selectedFactura != null) {
            // Re-fetch with all relationships to ensure lazy loading works
            selectedFactura = comprobantesRecibidosService.findByIdWithDetails(selectedFactura.getId());
            
            // Additional null check after re-fetching
            if (selectedFactura != null && selectedFactura.getDetalles() != null) {
                List<LineaDetalle> lineasDetalle = selectedFactura.getDetalles().getLineasDetalle();
                if (lineasDetalle == null || lineasDetalle.isEmpty()) {
                    alertas.registrarAlerta("Info", "Fallback: lineasDetalle is empty, fetching manually", currentSession.getCurrentUser(), 0, "FacturasController.showDetailsDialog()", null, null);
                    lineasDetalle = lineaDetalleService.listAllWhereID(selectedFactura.getDetalles().getId());
                    if (lineasDetalle != null && !lineasDetalle.isEmpty()) {
                        selectedFactura.getDetalles().setLineasDetalle(lineasDetalle);
                        alertas.registrarAlerta("Info", "Fallback successful: populated " + lineasDetalle.size() + " lineasDetalle records", currentSession.getCurrentUser(), 0, "FacturasController.showDetailsDialog()", null, null);
                    } else {
                        alertas.registrarAlerta("Error", "Fallback failed: no lineasDetalle found with ID " + selectedFactura.getDetalles().getId(), currentSession.getCurrentUser(), 0, "FacturasController.showDetailsDialog()", null, null);
                    }
                }
            } else {
                alertas.registrarAlerta("Error", "selectedFactura or its detalles became null after re-fetching", currentSession.getCurrentUser(), 0, "FacturasController.showDetailsDialog()", null, null);
            }
        }
        PrimeFaces.current().executeScript("PF('selectedFacturaDetallado').show(); PF('selectedFacturaDetallado').toggleMaximize();");
    }

    public void clearCache() {
        facturas = null;
        facturasDetalladas = null;
    }

    public List<ComprobantesRecibidos> getFilteredFacturas() {
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

    public List<ComprobantesRecibidos> getFilteredFacturasDetallados() {
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
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasDetallados()", null, e.getLocalizedMessage());
            return new ArrayList<>();
        }
    }

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
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasPendientes()", null, e.getLocalizedMessage());
            return null;
        }
    }

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
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasVencidas()", null, e.getLocalizedMessage());
            return null;
        }
    }
    
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
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasActivas()", null, e.getLocalizedMessage());
            return null;
        }
    }
    
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
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasPagadas()", null, e.getLocalizedMessage());
            return null;
        }
    }
    
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
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "FacturasController.getFilteredFacturasProcesadas()", null, e.getLocalizedMessage());
            return null;
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
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

    public void addFile(UploadedFile file) {
        if (files == null) {
            files = new ArrayList<>();
        }
        files.add(file);
    }

    public void parseXMLFromUploadedFile(UploadedFile uploadedFile) {
        synchronized (fileUploadLock) {
        if (uploadedFile == null) {
            alertas.registrarAlerta("Error", "UploadedFile is null", null, 0, "FacturasController.parseXMLFromUploadedFile()", null, null);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El archivo subido es nulo");
            FacesContext.getCurrentInstance().addMessage(null, message);
            return;
        }
        
        alertas.registrarAlerta("Info", "File details: " + uploadedFile.getFileName() + " Size: " + uploadedFile.getSize() + " Type: " + uploadedFile.getContentType(), null, 0, "FacturasController.parseXMLFromUploadedFile()", null, null);
        
        if (uploadedFile.getSize() == 0) {
            alertas.registrarAlerta("Error", "File is empty: " + uploadedFile.getFileName(), null, 0, "FacturasController.parseXMLFromUploadedFile()", null, null);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El archivo está vacío: " + uploadedFile.getFileName());
            FacesContext.getCurrentInstance().addMessage(null, message);
            return;
        }
        
        try (InputStream inputStream = uploadedFile.getInputStream()) {
            alertas.registrarAlerta("Info", "Got input stream for file: " + uploadedFile.getFileName(), null, 0, "FacturasController.parseXMLFromUploadedFile()", null, null);
            
            // Mark the stream so we can reset after reading preview
            inputStream.mark(1024);
            
            // Read first few bytes to verify file content
            byte[] buffer = new byte[1024];
            int bytesRead = inputStream.read(buffer);
            alertas.registrarAlerta("Info", "Read " + bytesRead + " bytes from file", null, 0, "FacturasController.parseXMLFromUploadedFile()", null, null);
            
            if (bytesRead > 0) {
                String preview = new String(buffer, 0, Math.min(bytesRead, 200));
                alertas.registrarAlerta("Info", "File preview: " + preview, null, 0, "FacturasController.parseXMLFromUploadedFile()", null, null);
            }
            
            // Reset stream for parser
            inputStream.reset();
            
            parser.parseXML(inputStream);
            alertas.registrarAlerta("Info", "Successfully processed file: " + uploadedFile.getFileName(), null, 0, "FacturasController.parseXMLFromUploadedFile()", null, null);
        } catch (IOException e) {
            alertas.registrarAlerta("Error", "IOException processing file " + uploadedFile.getFileName() + ": " + e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "FacturasController.parseXMLFromUploadedFile()", null, e.getLocalizedMessage());
            alertas.registrarAlerta("Error al parsear xml de factura", e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "facturasController.parseXMLFromUploadedFile()", e.getLocalizedMessage(), null);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al procesar el archivo: " + e.getLocalizedMessage());
            FacesContext.getCurrentInstance().addMessage(null, message);
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Exception processing file " + uploadedFile.getFileName() + ": " + e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "FacturasController.parseXMLFromUploadedFile()", null, e.getLocalizedMessage());
            alertas.registrarAlerta("Error al parsear xml de factura", e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "facturasController.parseXMLFromUploadedFile()", e.getLocalizedMessage(), null);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al procesar el archivo XML: " + e.getLocalizedMessage());
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
        } // synchronized fileUploadLock
     }

    public void processFacturas() {
        // Files are now processed immediately during upload
        // This method just closes the dialog and refreshes the cache
        files.clear();
        clearCache();
        PrimeFaces.current().executeScript("PF('facturasUpload').hide();");
        
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Proceso completado", 
                    "Los archivos se procesaron durante la carga. Revise los mensajes individuales para ver los resultados."));
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
                var totalUnitario = montoTotalLinea.divide(cantidad, 20, RoundingMode.HALF_UP);
                var precioUnitario = totalUnitario;
                var UnidadesParseadas = parser.parseUnidadMedida(unidadMedida, unidadMedidaComercial).multiply(cantidad);

                Articulos articulo = new Articulos();

                Departamento departamento = new Departamento();
                departamento.setNombre(factura.getEncabezado().getEmisor().getNombre());
                departamento.setStatus(true);
                departamento.setUsuario(currentSession.getCurrentUser());
                Departamento persistedDepartamento = departamentosController.createSimpleDepartamento(departamento);

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

                inventarioController.createSimpleInventario(ajusteArticulo);
            }

            factura.setProcessed(true);
            facturaService.update(factura);
            alertas.registrarAlerta("Factura Procesada", "Se procesaron los artículos de la factura #" + factura.getId(), currentSession.getCurrentUser(), 0, "processFactura()", factura.toString(), null);
            clearCache();

        } catch (Exception e) { 
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

    public void prevalidarFacturaSeleccionada() {
        if (selectedFactura == null || selectedFactura.getId() == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Advertencia", "No hay factura seleccionada"));
            return;
        }
        selectedFactura = comprobantesRecibidosService.findByIdWithDetails(selectedFactura.getId());
        if (selectedFactura == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Factura no encontrada"));
            return;
        }
        prevalidationResult = prevalidationService.prevalidarCompleto(selectedFactura);

        int errCount = prevalidationResult.getErrorCount();
        int warnCount = prevalidationResult.getWarningCount();
        if (errCount == 0 && warnCount == 0) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Pre-validación",
                    "Factura válida — sin errores ni advertencias"));
        } else {
            String summary = errCount + " error(es), " + warnCount + " advertencia(s)";
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(errCount > 0 ? FacesMessage.SEVERITY_ERROR : FacesMessage.SEVERITY_WARN,
                    "Resultado de Pre-validación", summary));
        }
        alertas.registrarAlerta("Pre-validación",
            "Factura #" + selectedFactura.getId() + ": " + errCount + " errores, " + warnCount + " advertencias",
            currentSession.getCurrentUser(), 0, "FacturasController.prevalidarFacturaSeleccionada()",
            null, prevalidationResult.getAllIssues().toString());
    }

    public PrevalidationResult getPrevalidationResult() {
        return prevalidationResult;
    }

    public void aceptarFacturaRecibida() {
        if (selectedFactura == null || selectedFactura.getId() == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Advertencia", "No hay factura seleccionada"));
            return;
        }

        // Run prevalidation before allowing acceptance
        selectedFactura = comprobantesRecibidosService.findByIdWithDetails(selectedFactura.getId());
        if (selectedFactura == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Factura no encontrada"));
            return;
        }
        PrevalidationResult preResult = prevalidationService.prevalidarCompleto(selectedFactura);
        prevalidationResult = preResult;

        if (preResult.hasErrors()) {
            // ERROR-level issues → block acceptance
            String errSummary = preResult.getErrorCount() + " error(es) de pre-validación impiden aceptar la factura";
            for (ValidationError err : preResult.getErrors()) {
                alertas.registrarAlerta("Pre-validación (bloqueo)",
                    err.getField() + ": " + err.getMessage(),
                    currentSession.getCurrentUser(), 0,
                    "FacturasController.aceptarFacturaRecibida()", null, err.getMessage());
            }
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Pre-validación", errSummary));
            return;
        }

        if (preResult.hasWarnings()) {
            // WARNING-level → log but allow
            for (ValidationError warn : preResult.getWarnings()) {
                alertas.registrarAlerta("Pre-validación (advertencia)",
                    warn.getField() + ": " + warn.getMessage(),
                    currentSession.getCurrentUser(), 0,
                    "FacturasController.aceptarFacturaRecibida()", null, warn.getMessage());
            }
        }

        BigDecimal totalImpuesto = selectedFactura.getResumen() != null
            ? selectedFactura.getResumen().getTotalImpuesto() : BigDecimal.ZERO;
        BigDecimal totalFactura = selectedFactura.getResumen() != null
            ? selectedFactura.getResumen().getTotalVenta() : BigDecimal.ZERO;
        procesarMensajeReceptor(1, "Aceptado", totalImpuesto, totalFactura);
    }

    public void rechazarFacturaRecibida() {
        if (selectedFactura == null || selectedFactura.getId() == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Advertencia", "No hay factura seleccionada"));
            return;
        }
        BigDecimal totalImpuesto = selectedFactura.getResumen() != null
            ? selectedFactura.getResumen().getTotalImpuesto() : BigDecimal.ZERO;
        BigDecimal totalFactura = selectedFactura.getResumen() != null
            ? selectedFactura.getResumen().getTotalVenta() : BigDecimal.ZERO;
        procesarMensajeReceptor(3, "Rechazado", totalImpuesto, totalFactura);
    }

    // ─── Partial acceptance (codigoMensaje=2) ────────────────────────

    public void aceptarLinea() {
        if (lineaDetalle == null || lineaDetalle.getId() == null) return;
        lineasAceptadas.add(lineaDetalle.getId());
    }

    public void rechazarLinea() {
        if (lineaDetalle == null || lineaDetalle.getId() == null) return;
        lineasAceptadas.remove(lineaDetalle.getId());
    }

    public boolean isLineaAceptada(LineaDetalle linea) {
        return linea != null && linea.getId() != null && lineasAceptadas.contains(linea.getId());
    }

    public boolean isLineaRechazada(LineaDetalle linea) {
        return linea != null && linea.getId() != null && !lineasAceptadas.contains(linea.getId());
    }

    public Set<Long> getLineasAceptadasSet() {
        return lineasAceptadas;
    }

    public void enviarAceptacionParcial() {
        if (selectedFactura == null || selectedFactura.getId() == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Advertencia", "No hay factura seleccionada"));
            return;
        }
        if (lineasAceptadas.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Advertencia",
                    "Debe aceptar al menos una línea para enviar aceptación parcial"));
            return;
        }

        selectedFactura = comprobantesRecibidosService.findByIdWithDetails(selectedFactura.getId());
        if (selectedFactura == null || selectedFactura.getDetalles() == null) return;

        // Run prevalidation before allowing partial acceptance
        PrevalidationResult preResult = prevalidationService.prevalidarCompleto(selectedFactura);
        if (preResult.hasErrors()) {
            prevalidationResult = preResult;
            String errSummary = preResult.getErrorCount() + " error(es) de pre-validación impiden aceptar parcialmente";
            for (ValidationError err : preResult.getErrors()) {
                alertas.registrarAlerta("Pre-validación (bloqueo)",
                    err.getField() + ": " + err.getMessage(),
                    currentSession.getCurrentUser(), 0,
                    "FacturasController.enviarAceptacionParcial()", null, err.getMessage());
            }
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Pre-validación", errSummary));
            return;
        }

        List<LineaDetalle> lineas = selectedFactura.getDetalles().getLineasDetalle();
        if (lineas == null || lineas.isEmpty()) return;

        BigDecimal totalImpuesto = BigDecimal.ZERO;
        BigDecimal totalFactura = BigDecimal.ZERO;
        for (LineaDetalle linea : lineas) {
            if (linea.getId() != null && lineasAceptadas.contains(linea.getId())) {
                if (linea.getImpuestoNeto() != null) {
                    totalImpuesto = totalImpuesto.add(linea.getImpuestoNeto());
                }
                if (linea.getMontoTotal() != null) {
                    totalFactura = totalFactura.add(linea.getMontoTotal());
                }
            }
        }

        procesarMensajeReceptor(2, "Aceptado Parcial", totalImpuesto, totalFactura);
        lineasAceptadas = new HashSet<>();
    }

    private void procesarMensajeReceptor(int codigoMensaje, String accion,
                                          BigDecimal montoTotalImpuesto, BigDecimal montoTotalFactura) {
        try {
            if (selectedFactura.getEncabezado() == null) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Factura sin encabezado"));
                return;
            }

            AppSettings settings = appSettingsService.returnCurrent();
            if (settings == null) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No hay configuración de Hacienda"));
                return;
            }

            String clave = selectedFactura.getEncabezado().getClave();
            if (clave == null || clave.isEmpty()) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Factura sin clave Hacienda"));
                return;
            }

            // NumeroCedulaReceptor in MR = the original invoice receptor (buyer = system user)
            String receptorId = settings.getIdentificacion() != null ? settings.getIdentificacion() : "0";
            // NumeroCedulaEmisor in MR = the original invoice emitter (seller)
            String emisorId = "0";
            if (selectedFactura.getEncabezado().getEmisor() != null
                && selectedFactura.getEncabezado().getEmisor().getIdentificacion() != null
                && selectedFactura.getEncabezado().getEmisor().getIdentificacion().getNumero() != null) {
                emisorId = selectedFactura.getEncabezado().getEmisor().getIdentificacion().getNumero();
            }

            LocalDateTime fechaEmision = selectedFactura.getEncabezado().getFechaEmision();

            String codigoSucursal = settings.getCodigoSucursal() != null ? settings.getCodigoSucursal() : "001";
            String codigoTerminal = settings.getCodigoTerminal() != null ? settings.getCodigoTerminal() : "001";
            String mrType = codigoMensaje == 1 ? "05" : (codigoMensaje == 2 ? "06" : "07");
            String sucursalFmt = String.format("%03d", Integer.parseInt(codigoSucursal));
            String terminalFmt = String.format("%05d", Integer.parseInt(codigoTerminal));
            String seq = consecutivoReceptorService.getNextSequential(sucursalFmt, terminalFmt, mrType);
            String numeroConsecutivoReceptor = sucursalFmt + terminalFmt + mrType + seq;

            String xmlMensaje = comprobanteService.generateMensajeReceptorXml(
                settings, clave, emisorId, receptorId, fechaEmision, codigoMensaje,
                accion, montoTotalImpuesto, montoTotalFactura, numeroConsecutivoReceptor
            );

            if (xmlMensaje == null) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo generar el XML del Mensaje Receptor"));
                return;
            }

            HaciendaSigner.SignResult signResult = haciendaSigner.signXml(xmlMensaje);
            if (!signResult.success) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error firmando Mensaje Receptor: " + signResult.errorMessage));
                return;
            }

            String emisorTipoId = settings.getTipoIdentificacion();
            String emisorNumeroId = settings.getIdentificacion();
            String receptorTipoId = "01";
            String receptorNumeroId = "000000000";
            if (selectedFactura.getEncabezado() != null 
                && selectedFactura.getEncabezado().getEmisor() != null
                && selectedFactura.getEncabezado().getEmisor().getIdentificacion() != null) {
                receptorTipoId = selectedFactura.getEncabezado().getEmisor().getIdentificacion().getTipo();
                receptorNumeroId = selectedFactura.getEncabezado().getEmisor().getIdentificacion().getNumero();
            }

            HaciendaApiService.ApiResponse response;
            if (codigoMensaje == 1) {
                response = haciendaApiService.acceptInvoice(clave, signResult.signedXml,
                    emisorTipoId, emisorNumeroId, receptorTipoId, receptorNumeroId);
            } else {
                response = haciendaApiService.rejectInvoice(clave, signResult.signedXml,
                    emisorTipoId, emisorNumeroId, receptorTipoId, receptorNumeroId);
            }

            if (response.isSuccess()) {
                selectedFactura.setHaciendaMensajeReceptorEstado(accion.toUpperCase());
                selectedFactura.setHaciendaMensajeReceptorFecha(LocalDateTime.now());
                facturaService.update(selectedFactura);

                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito",
                        "Factura " + accion.toLowerCase() + " correctamente. Mensaje Receptor enviado a Hacienda."));
                alertas.registrarAlerta("Hacienda", "Mensaje Receptor " + accion + ": " + clave, currentSession.getCurrentUser(), 0, "FacturasController.procesarMensajeReceptor()", null, null);
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Hacienda rechazó el Mensaje Receptor: " + response.errorMessage));
            }

        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Error en Mensaje Receptor: " + e.getMessage(), currentSession.getCurrentUser(), 0, "FacturasController.procesarMensajeReceptor()", null, e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al procesar Mensaje Receptor: " + e.getMessage()));
        }
    }

}
