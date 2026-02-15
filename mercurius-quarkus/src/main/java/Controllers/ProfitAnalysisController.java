package Controllers;

import Models.Articulos.Articulos;
import Models.Departamento;
import Models.Familia;
import Models.ProfitMarginHistory;
import Models.ProfitMarginSnapshot;
import Models.Users;
import Services.AlertasService;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.ProfitAnalysisService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 * Controller for managing profit margin analysis and reporting
 */
@Data
@Named(value = "profitAnalysisController")
@ViewScoped
public class ProfitAnalysisController implements Serializable {

    @Inject
    private ProfitAnalysisService profitAnalysisService;

    @Inject
    private ArticulosService articulosService;

    @Inject
    private DepartamentoService departamentoService;

    @Inject
    private FamiliaService familiaService;

    @Inject
    private AlertasService alertasService;

    @Inject
    private SessionController currentSession;

    // Filter and search properties
    private Date startDate;
    private Date endDate;
    private String selectedDepartment;
    private String selectedFamily;
    private String articleFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    // Data lists
    private List<ProfitMarginHistory> marginHistory;
    private List<ProfitMarginSnapshot> marginSnapshots;
    private List<Articulos> topMarginArticles;
    private List<Articulos> worstMarginArticles;
    private Map<String, BigDecimal> departmentComparisons;

    // Summary statistics
    private BigDecimal averageMargin;
    private BigDecimal totalProfit;
    private BigDecimal totalRevenue;

    public ProfitAnalysisController() {
        // Initialize dates to last 30 days
        Calendar cal = Calendar.getInstance();
        endDate = new Date();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        startDate = cal.getTime();
    }

    @PostConstruct
    public void init() {
        filterBy = new ArrayList<>();
        loadProfitAnalysis();
    }

    /**
     * Load comprehensive profit analysis
     */
    public void loadProfitAnalysis() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        try {
            // Load margin history
            if (selectedDepartment != null && !selectedDepartment.isEmpty()) {
                loadDepartmentAnalysis();
            } else if (selectedFamily != null && !selectedFamily.isEmpty()) {
                loadFamilyAnalysis();
            } else {
                loadGeneralAnalysis();
            }

            // Calculate summary statistics
            calculateSummaryStatistics();

        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo cargar el análisis de márgenes: " + e.getMessage()));
        }
    }

    /**
     * Load general profit analysis for all articles
     */
    private void loadGeneralAnalysis() {
        // Get average margin
        averageMargin = profitAnalysisService.getAverageProfitMargin(startDate, endDate);
        
        // Get top and worst performing articles
        topMarginArticles = profitAnalysisService.getTopProfitMarginArticles(10, startDate, endDate);
        worstMarginArticles = profitAnalysisService.getWorstProfitMarginArticles(10, startDate, endDate);
        
        // Get department comparisons
        departmentComparisons = profitAnalysisService.getDepartmentMarginComparison(startDate, endDate);
        
        // Load snapshots for trends
        marginSnapshots = profitAnalysisService.getMarginTrend(null, "department", startDate, endDate);
    }

    /**
     * Load department-specific analysis
     */
    private void loadDepartmentAnalysis() {
        marginSnapshots = profitAnalysisService.getMarginTrend(selectedDepartment, "department", startDate, endDate);
    }

    /**
     * Load family-specific analysis
     */
    private void loadFamilyAnalysis() {
        marginSnapshots = profitAnalysisService.getMarginTrend(selectedFamily, "family", startDate, endDate);
    }

    /**
     * Load detailed margin history for a specific article
     */
    public void loadArticleMarginHistory(Articulos articulo) {
        if (articulo != null) {
            marginHistory = profitAnalysisService.getArticleMarginHistory(articulo, startDate, endDate);
            
            alertasService.registrarAlerta(
                "Análisis de Margen Consultado", 
                "Se consultó historial de márgenes para el artículo: " + articulo.getNombre(), 
                currentSession.getCurrentUser(), 
                0, 
                "ProfitAnalysisController.loadArticleMarginHistory", 
                articulo.toString(), 
                null
            );
        }
    }

    /**
     * Generate profit margin report for export
     */
    public void generateProfitReport() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        try {
            // This would integrate with existing Excel/PDF export functionality
            Map<String, Object> reportData = new HashMap<>();
            reportData.put("startDate", startDate);
            reportData.put("endDate", endDate);
            reportData.put("averageMargin", averageMargin);
            reportData.put("departmentComparisons", departmentComparisons);
            reportData.put("topMarginArticles", topMarginArticles);
            reportData.put("worstMarginArticles", worstMarginArticles);

            alertasService.registrarAlerta(
                "Reporte de Márgenes Generado", 
                "Se generó reporte de análisis de márgenes de utilidad", 
                currentSession.getCurrentUser(), 
                0, 
                "ProfitAnalysisController.generateProfitReport", 
                null, 
                reportData.toString()
            );

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Reporte Generado", "El reporte de márgenes se ha generado exitosamente"));

        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo generar el reporte: " + e.getMessage()));
        }
    }

    /**
     * Calculate summary statistics from current data
     */
    private void calculateSummaryStatistics() {
        if (marginSnapshots != null && !marginSnapshots.isEmpty()) {
            totalRevenue = marginSnapshots.stream()
                .map(ProfitMarginSnapshot::getTotalVentas)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
                
            totalProfit = marginSnapshots.stream()
                .map(ProfitMarginSnapshot::getTotalUtilidad)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * Export profit analysis data to Excel
     */
    public void exportToExcel() {
        try {
            // Integration with existing ExcelExporter
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Iniciada", "Exportando datos de análisis de márgenes a Excel..."));
                
            // TODO: Implement Excel export using existing ExcelExporter utility
        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error de Exportación", "No se pudo exportar a Excel: " + e.getMessage()));
        }
    }

    /**
     * Export profit analysis data to PDF
     */
    public void exportToPDF() {
        try {
            // Integration with existing PDFGenerator
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Iniciada", "Exportando datos de análisis de márgenes a PDF..."));
                
            // TODO: Implement PDF export using existing PDFGenerator utility
        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error de Exportación", "No se pudo exportar a PDF: " + e.getMessage()));
        }
    }

    /**
     * Get margin color indicator for display
     */
    public String getMarginColor(BigDecimal margin) {
        if (margin == null) {
            return "#cccccc";
        }
        
        if (margin.compareTo(BigDecimal.valueOf(20)) >= 0) {
            return "#d4edda"; // Green for good margins
        } else if (margin.compareTo(BigDecimal.valueOf(10)) >= 0) {
            return "#fff3cd"; // Yellow for moderate margins
        } else {
            return "#f8d7da"; // Red for low margins
        }
    }

    /**
     * Get margin performance label
     */
    public String getMarginPerformanceLabel(BigDecimal margin) {
        if (margin == null) {
            return "Sin Datos";
        }
        
        if (margin.compareTo(BigDecimal.valueOf(20)) >= 0) {
            return "Excelente";
        } else if (margin.compareTo(BigDecimal.valueOf(15)) >= 0) {
            return "Bueno";
        } else if (margin.compareTo(BigDecimal.valueOf(10)) >= 0) {
            return "Regular";
        } else if (margin.compareTo(BigDecimal.ZERO) > 0) {
            return "Bajo";
        }
        return "Sin Utilidad";
    }

    /**
     * Get list of departments for filter dropdown
     */
    public List<String> getDepartments() {
        return departamentoService.listAll().stream()
                .map(Departamento::getNombre)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get list of families for filter dropdown
     */
    public List<String> getFamilies() {
        return familiaService.listAll().stream()
                .map(Familia::getNombre)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get filtered articles for display
     */
    public List<Articulos> getFilteredArticles() {
        List<Articulos> articles = new ArrayList<>();
        articles.addAll(topMarginArticles);
        articles.addAll(worstMarginArticles);
        
        if (articleFilter != null && !articleFilter.isEmpty()) {
            return articles.stream()
                    .filter(article -> globalFilterFunction(article, articleFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        }
        
        return articles.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Global filter function for articles
     */
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Articulos article = (Articulos) value;
        return article.getNombre().toLowerCase().contains(filterText)
                || (article.getCodigoBarra() != null && article.getCodigoBarra().toLowerCase().contains(filterText))
                || String.valueOf(article.getCodigo()).contains(filterText);
    }

    /**
     * Clear all filters and refresh data
     */
    public void clearFilters() {
        selectedDepartment = null;
        selectedFamily = null;
        articleFilter = null;
        
        // Reset dates to last 30 days
        Calendar cal = Calendar.getInstance();
        endDate = new Date();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        startDate = cal.getTime();
        
        loadProfitAnalysis();
    }

    /**
     * Refresh data with current filters
     */
    public void refreshData() {
        loadProfitAnalysis();
    }

    // Getters for view
    public List<ProfitMarginHistory> getMarginHistory() {
        return marginHistory;
    }

    public List<ProfitMarginSnapshot> getMarginSnapshots() {
        return marginSnapshots;
    }

    public List<Articulos> getTopMarginArticles() {
        return topMarginArticles;
    }

    public List<Articulos> getWorstMarginArticles() {
        return worstMarginArticles;
    }

    public Map<String, BigDecimal> getDepartmentComparisons() {
        return departmentComparisons;
    }

    public BigDecimal getAverageMargin() {
        return averageMargin;
    }

    public BigDecimal getTotalProfit() {
        return totalProfit;
    }

    public BigDecimal getTotalRevenue() {
        return totalRevenue;
    }
}