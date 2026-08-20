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
import Utils.DiffUtils;
import Models.Inventario;
import Services.AlertasService;
import Services.ArticuloPrecioService;
import Services.ComprobantesRecibidosPrevalidationService;
import Services.ComprobantesRecibidosService;
import Services.AppSettingsService;
import Services.ComprobanteService;
import Services.Strategies.DocumentoStrategyFactory;
import Services.Facturas.*;
import Services.HaciendaApiService;
import Services.HaciendaSigner;
import Services.ConsecutivoReceptorService;
import Services.MensajeReceptorService;
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
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.file.UploadedFile;
import org.primefaces.util.LangUtils;

@Named
@Getter @Setter @ToString @EqualsAndHashCode
@ViewScoped
public class FacturasController implements Serializable {
    
    private static final Object fileUploadLock = new Object();

    @Inject @Nonnull
    ComprobantesRecibidosService facturaService;
    @Inject @Nonnull
    LineaDetalleService lineaDetalleService;
    @Inject @Nonnull
    SessionController currentSession;
    @Inject @Nonnull
    ArticulosController articuloController;
    @Inject @Nonnull
    InventarioController inventarioController;
    @Inject @Nonnull
    DepartamentoController departamentosController;
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
    DocumentoStrategyFactory strategyFactory;
    @Inject @Nonnull
    HaciendaApiService haciendaApiService;
    @Inject @Nonnull
    HaciendaSigner haciendaSigner;
    @Inject @Nonnull
    ComprobantesRecibidosPrevalidationService prevalidationService;
    @Inject @Nonnull
    ConsecutivoReceptorService consecutivoReceptorService;
    @Inject @Nonnull
    MensajeReceptorService mensajeReceptorService;

    @Nullable
    private PrevalidationResult prevalidationResult;

    @Nonnull
    private List<UploadedFile> files;
    @Nullable
    private List<ComprobantesRecibidos> facturas;
    @Nullable
    private List<ComprobantesRecibidos> facturasDetalladas;
    @Nullable
    private List<ComprobantesRecibidos> facturasVencidas;
    @Nullable
    private List<ComprobantesRecibidos> facturasPendientes;

    @Nullable
    private LineaDetalle lineaDetalle;

    @Nonnull
    private Set<Long> lineasAceptadas = new HashSet<>();

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

    public void addFile(@Nonnull UploadedFile file) {
        if (files == null) {
            files = new ArrayList<>();
        }
        files.add(file);
    }

    public void parseXMLFromUploadedFile(@Nullable UploadedFile uploadedFile) {
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
        } catch (RuntimeException e) {
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
                var totalUnitario = cantidad != null && cantidad.compareTo(BigDecimal.ZERO) != 0
                    ? montoTotalLinea.divide(cantidad, 20, RoundingMode.HALF_UP) : BigDecimal.ZERO;
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

    @Nullable
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
            ? selectedFactura.getResumen().getTotalComprobante() : BigDecimal.ZERO;
        validarCondicionVentaFactura(
            selectedFactura.getEncabezado() != null ? selectedFactura.getEncabezado().getCondicionVenta() : null,
            selectedFactura.getEncabezado() != null ? selectedFactura.getEncabezado().getCodigoDocumento() : null);
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
            ? selectedFactura.getResumen().getTotalComprobante() : BigDecimal.ZERO;
        validarCondicionVentaFactura(
            selectedFactura.getEncabezado() != null ? selectedFactura.getEncabezado().getCondicionVenta() : null,
            selectedFactura.getEncabezado() != null ? selectedFactura.getEncabezado().getCodigoDocumento() : null);
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

    public boolean isLineaAceptada(@Nullable LineaDetalle linea) {
        return linea != null && linea.getId() != null && lineasAceptadas.contains(linea.getId());
    }

    public boolean isLineaRechazada(@Nullable LineaDetalle linea) {
        return linea != null && linea.getId() != null && !lineasAceptadas.contains(linea.getId());
    }

    public @Nonnull Set<Long> getLineasAceptadasSet() {
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

        validarCondicionVentaFactura(
            selectedFactura.getEncabezado() != null ? selectedFactura.getEncabezado().getCondicionVenta() : null,
            selectedFactura.getEncabezado() != null ? selectedFactura.getEncabezado().getCodigoDocumento() : null);
        procesarMensajeReceptor(2, "Aceptado Parcial", totalImpuesto, totalFactura);
        lineasAceptadas = new HashSet<>();
    }

    private void procesarMensajeReceptor(int codigoMensaje, String accion,
                                          BigDecimal montoTotalImpuesto, BigDecimal montoTotalFactura) {
        try {
            MensajeReceptorService.MRResult result = mensajeReceptorService.enviarMensajeReceptor(
                selectedFactura, codigoMensaje, accion, montoTotalImpuesto, montoTotalFactura);

            if (result.success) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", result.message));
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", result.message));
            }
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al procesar Mensaje Receptor: " + e.getMessage()));
        }
    }

    /**
     * Validates CondicionVenta against the allowed values for the given document type.
     * Uses DocumentoStrategyFactory to resolve the correct strategy and its permitted codes.
     * Throws IllegalArgumentException if condicionVenta is not in the allowed list.
     */
    private void validarCondicionVentaFactura(String condicionVenta, String codigoDocumento) {
        if (condicionVenta == null) {
            return; // null CondicionVenta is handled by Strategy defaults
        }
        var strategy = strategyFactory.forCode(codigoDocumento);
        java.util.Set<String> permitidas = strategy.getCondicionVentaPermitidas();
        if (!permitidas.contains(condicionVenta)) {
            throw new IllegalArgumentException(
                "CondicionVenta código " + condicionVenta + " no permitido para tipo documento "
                + codigoDocumento + ". Códigos permitidos: " + permitidas);
        }
    }

}
