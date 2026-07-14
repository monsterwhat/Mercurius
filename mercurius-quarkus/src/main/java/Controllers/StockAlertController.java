package Controllers;

import Models.Departamento;
import Models.Familia;
import Models.ReorderSuggestion;
import Models.StockAlert;
import Models.Users;
import Services.AlertasService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.StockAlertService;
import Utils.ExcelExporter;
import Utils.PDFGenerator;
import Controllers.Settings.SettingsDirController;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 * Controller for managing stock alerts and reordering system
 */
@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "stockAlertController")
@ViewScoped
public class StockAlertController implements Serializable {

    @Inject
    @Nonnull
    private StockAlertService stockAlertService;

    @Inject
    @Nonnull
    private DepartamentoService departamentoService;

    @Inject
    @Nonnull
    private FamiliaService familiaService;

    @Inject
    @Nonnull
    private AlertasService alertasService;

    @Inject
    @Nonnull
    private SessionController currentSession;

    @Inject
    @Nonnull
    private SettingsDirController dirController;

    // Filter properties
    @Nullable
    private String selectedDepartment;
    @Nullable
    private String selectedFamily;
    @Nullable
    private String selectedPriority;
    @Nullable
    private String alertFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    // Data lists
    @Nullable
    private List<StockAlert> activeAlerts;
    @Nullable
    private List<StockAlert> allAlerts;
    @Nullable
    private List<ReorderSuggestion> reorderSuggestions;
    @Nullable
    private Map<String, Integer> alertStatistics;

    // Form properties
    @Nullable
    private StockAlert selectedAlert;
    @Nullable
    private ReorderSuggestion selectedSuggestion;
    @Nullable
    private String resolutionNotes;

    public StockAlertController() {
    }

    @PostConstruct
    public void init() {
        filterBy = new ArrayList<>();
        loadActiveAlerts();
        loadReorderSuggestions();
        loadAlertStatistics();
    }

    /**
     * Load all active stock alerts
     */
    public void loadActiveAlerts() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        try {
            if (selectedDepartment != null && !selectedDepartment.isEmpty()) {
                activeAlerts = stockAlertService.getStockAlertsByDepartment(
                    departamentoService.findByName(selectedDepartment));
            } else {
                activeAlerts = stockAlertService.getActiveStockAlerts();
            }
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar las alertas de stock: " + e.getMessage()));
        }
    }

    /**
     * Load all stock alerts
     */
    public void loadAllAlerts() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        try {
            allAlerts = stockAlertService.getActiveStockAlerts();
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar todas las alertas: " + e.getMessage()));
        }
    }

    /**
     * Load reorder suggestions
     */
    public void loadReorderSuggestions() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        try {
            if (selectedPriority != null && !selectedPriority.isEmpty()) {
                reorderSuggestions = stockAlertService.getReorderSuggestionsByPriority(selectedPriority);
            } else {
                reorderSuggestions = stockAlertService.getAllReorderSuggestions();
            }
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar las sugerencias de reorden: " + e.getMessage()));
        }
    }

    /**
     * Load alert statistics
     */
    public void loadAlertStatistics() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        try {
            alertStatistics = stockAlertService.getAlertStatistics();
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar las estadísticas: " + e.getMessage()));
        }
    }

    /**
     * Manually trigger stock alert checking
     */
    public void checkStockAlerts() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        try {
            stockAlertService.checkAndCreateStockAlerts();
            loadActiveAlerts();
            loadReorderSuggestions();
            loadAlertStatistics();

            alertasService.registrarAlerta(
                "Verificación de Alertas de Stock Ejecutada", 
                "Se ejecutó manualmente la verificación de niveles de stock", 
                currentSession.getCurrentUser(), 
                0, 
                "StockAlertController.checkStockAlerts", 
                null, 
                null
            );

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Verificación Completada", 
                    "Se han verificado y actualizado los niveles de stock"));
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron verificar las alertas: " + e.getMessage()));
        }
    }

    /**
     * Acknowledge selected stock alert
     */
    public void acknowledgeAlert() {
        if (!currentSession.isValid() || selectedAlert == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Seleccione una alerta válida"));
            return;
        }

        try {
            stockAlertService.acknowledgeStockAlert(selectedAlert, currentSession.getCurrentUser(), resolutionNotes);
            loadActiveAlerts();
            loadAlertStatistics();

            alertasService.registrarAlerta(
                "Alerta de Stock Reconocida", 
                "Se reconoció la alerta: " + selectedAlert.getArticulo().getNombre(), 
                currentSession.getCurrentUser(), 
                selectedAlert.getId(), 
                "StockAlertController.acknowledgeAlert", 
                selectedAlert.toString(), 
                resolutionNotes
            );

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Alerta Reconocida", 
                    "La alerta ha sido reconocida exitosamente"));
            
            selectedAlert = null;
            resolutionNotes = null;
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo reconocer la alerta: " + e.getMessage()));
        }
    }

    /**
     * Resolve selected stock alert
     */
    public void resolveAlert() {
        if (!currentSession.isValid() || selectedAlert == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Seleccione una alerta válida"));
            return;
        }

        try {
            stockAlertService.resolveStockAlert(selectedAlert, currentSession.getCurrentUser(), resolutionNotes);
            loadActiveAlerts();
            loadAlertStatistics();

            alertasService.registrarAlerta(
                "Alerta de Stock Resuelta", 
                "Se resolvió la alerta: " + selectedAlert.getArticulo().getNombre(), 
                currentSession.getCurrentUser(), 
                selectedAlert.getId(), 
                "StockAlertController.resolveAlert", 
                selectedAlert.toString(), 
                resolutionNotes
            );

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Alerta Resuelta", 
                    "La alerta ha sido resuelta exitosamente"));
            
            selectedAlert = null;
            resolutionNotes = null;
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo resolver la alerta: " + e.getMessage()));
        }
    }

    /**
     * Get alert type color for display
     */
    @Nonnull
    public String getAlertTypeColor(@Nonnull String alertType) {
        switch (alertType) {
            case "out_of_stock":
                return "#dc3545"; // Red for critical
            case "low_stock":
                return "#ffc107"; // Amber for warning
            case "reorder_suggestion":
                return "#17a2b8"; // Blue for info
            default:
                return "#cccccc"; // Gray for unknown
        }
    }

    /**
     * Get priority color for display
     */
    @Nonnull
    public String getPriorityColor(@Nonnull String priority) {
        switch (priority) {
            case "urgent":
                return "#dc3545"; // Red
            case "high":
                return "#ff6b6b"; // Orange
            case "medium":
                return "#ffc107"; // Yellow
            case "low":
                return "#28a745"; // Green
            default:
                return "#cccccc"; // Gray
        }
    }

    /**
     * Get filtered alerts
     */
    @Nullable
    public List<StockAlert> getFilteredAlerts() {
        if (alertFilter != null && !alertFilter.isEmpty()) {
            return activeAlerts.stream()
                    .filter(alert -> globalFilterFunction(alert, alertFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        }
        return activeAlerts;
    }

    /**
     * Global filter function for alerts
     */
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        StockAlert alert = (StockAlert) value;
        return alert.getArticulo().getNombre().toLowerCase().contains(filterText)
                || (alert.getTipoAlerta() != null && alert.getTipoAlerta().toLowerCase().contains(filterText))
                || (alert.getNotas() != null && alert.getNotas().toLowerCase().contains(filterText));
    }

    /**
     * Clear all filters
     */
    public void clearFilters() {
        selectedDepartment = null;
        selectedPriority = null;
        alertFilter = null;
        loadActiveAlerts();
        loadReorderSuggestions();
        loadAlertStatistics();
    }

    /**
     * Export alerts to Excel
     */
    public void exportToExcel() {
        try {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Iniciada", 
                    "Exportando alertas de stock a Excel..."));

            if (activeAlerts == null || activeAlerts.isEmpty()) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Sin Datos", 
                        "No hay alertas de stock para exportar"));
                return;
            }

            ExcelExporter exporter = new ExcelExporter();
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd_MM_yyyy_HH_mm");
            String timestamp = dateFormat.format(new Date());
            String fileName = "StockAlerts_" + timestamp + ".xlsx";
            String filePath = dirController.getPDFDirPath() + File.separator + fileName;

            File excelFile = exporter.exportStockAlertsToExcel(activeAlerts, filePath);

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Exitosa", 
                    "Alertas exportadas a: " + excelFile.getAbsolutePath()));

        } catch (RuntimeException | IOException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error de Exportación", 
                    "No se pudo exportar a Excel: " + e.getMessage()));
        }
    }

    /**
     * Export alerts to PDF
     */
    public void exportToPDF() {
        try {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Iniciada", 
                    "Exportando alertas de stock a PDF..."));

            if (activeAlerts == null || activeAlerts.isEmpty()) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Sin Datos", 
                        "No hay alertas de stock para exportar"));
                return;
            }

            PDFGenerator pdfGenerator = new PDFGenerator();
            File pdfFile = pdfGenerator.generarPDFStockAlerts(activeAlerts);

            if (pdfFile != null && pdfFile.exists()) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Exitosa", 
                        "PDF generado: " + pdfFile.getAbsolutePath()));
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error de Exportación", 
                        "No se pudo generar el archivo PDF"));
            }

        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error de Exportación", 
                    "No se pudo exportar a PDF: " + e.getMessage()));
        }
    }

    /**
     * Get departments for filter dropdown
     */
    @Nonnull
    public List<String> getDepartments() {
        return departamentoService.listAll().stream()
                .map(Departamento::getNombre)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get families for filter dropdown
     */
    @Nonnull
    public List<String> getFamilies() {
        return familiaService.listAll().stream()
                .map(Familia::getNombre)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    // Getters for view
    @Nullable
    public List<StockAlert> getActiveAlerts() {
        return activeAlerts;
    }

    @Nullable
    public List<ReorderSuggestion> getReorderSuggestions() {
        return reorderSuggestions;
    }

    @Nullable
    public Map<String, Integer> getAlertStatistics() {
        return alertStatistics;
    }

    /**
     * Get count of active stock alerts for badge display
     */
    public int getActiveAlertsCount() {
        if (activeAlerts == null) {
            return 0;
        }
        return activeAlerts.size();
    }
}