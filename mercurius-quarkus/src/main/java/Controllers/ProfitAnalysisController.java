package Controllers;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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
import Utils.PDFGenerator;
import Utils.ReportExporter;
import Controllers.Settings.SettingsDirController;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Controller for managing profit margin analysis and reporting
 */
@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "profitAnalysisController")
@ViewScoped
public class ProfitAnalysisController implements Serializable {

    @Inject @Nonnull
    private ProfitAnalysisService profitAnalysisService;

    @Inject @Nonnull
    private ArticulosService articulosService;

    @Inject @Nonnull
    private DepartamentoService departamentoService;

    @Inject @Nonnull
    private FamiliaService familiaService;

    @Inject @Nonnull
    private AlertasService alertasService;

    @Inject @Nonnull
    private SessionController currentSession;

    @Inject @Nonnull
    private SettingsDirController dirController;

    // Filter and search properties
    @Nonnull private Date startDate;
    @Nonnull private Date endDate;
    @Nullable private String selectedDepartment;
    @Nullable private String selectedFamily;
    @Nullable private String articleFilter;
    @Nonnull private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    // Data lists
    @Nullable private List<ProfitMarginHistory> marginHistory;
    @Nullable private List<ProfitMarginSnapshot> marginSnapshots;
    @Nullable private List<Articulos> topMarginArticles;
    @Nullable private List<Articulos> worstMarginArticles;
    @Nullable private Map<String, BigDecimal> departmentComparisons;

    // Summary statistics
    @Nullable private BigDecimal averageMargin;
    @Nullable private BigDecimal totalProfit;
    @Nullable private BigDecimal totalRevenue;

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

        } catch (RuntimeException e) {
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
    public void loadArticleMarginHistory(@Nullable Articulos articulo) {
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

        } catch (RuntimeException e) {
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
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Iniciada", "Exportando datos de análisis de márgenes a Excel..."));

            if (marginSnapshots == null || marginSnapshots.isEmpty()) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Sin Datos", 
                        "No hay datos de márgenes de ganancia para exportar"));
                return;
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd_MM_yyyy_HH_mm");
            String timestamp = dateFormat.format(new Date());
            String fileName = "ProfitMargins_" + timestamp + ".xlsx";
            String filePath = dirController.getPDFDirPath() + File.separator + fileName;

            // T17: bytes from ReportExporter must stay cell-identical to the old ExcelExporter output.
            byte[] excelBytes = ReportExporter.exportProfitMarginSnapshotsExcel(marginSnapshots);
            Files.write(Path.of(filePath), excelBytes);
            File excelFile = new File(filePath);

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Exitosa", 
                    "Datos exportados a: " + excelFile.getAbsolutePath()));

        } catch (RuntimeException | IOException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error de Exportación", "No se pudo exportar a Excel: " + e.getMessage()));
        }
    }

    /**
     * Export profit analysis data to PDF
     */
    public void exportToPDF() {
        try {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exportación Iniciada", "Exportando datos de análisis de márgenes a PDF..."));

            if (marginSnapshots == null || marginSnapshots.isEmpty()) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Sin Datos", 
                        "No hay datos de márgenes de ganancia para exportar"));
                return;
            }

            PDFGenerator pdfGenerator = new PDFGenerator();
            File pdfFile = pdfGenerator.generarPDFProfitMarginSnapshots(marginSnapshots);

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
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error de Exportación", "No se pudo exportar a PDF: " + e.getMessage()));
        }
    }

    /**
     * Get margin color indicator for display
     */
    @Nonnull
    public String getMarginColor(@Nullable BigDecimal margin) {
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
    @Nonnull
    public String getMarginPerformanceLabel(@Nullable BigDecimal margin) {
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
     * Get list of families for filter dropdown
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

    /**
     * Get filtered articles for display
     */
    @Nonnull
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
    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
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
    @Nullable
    public List<ProfitMarginHistory> getMarginHistory() {
        return marginHistory;
    }

    @Nullable
    public List<ProfitMarginSnapshot> getMarginSnapshots() {
        return marginSnapshots;
    }

    @Nullable
    public List<Articulos> getTopMarginArticles() {
        return topMarginArticles;
    }

    @Nullable
    public List<Articulos> getWorstMarginArticles() {
        return worstMarginArticles;
    }

    @Nullable
    public Map<String, BigDecimal> getDepartmentComparisons() {
        return departmentComparisons;
    }

    @Nullable
    public BigDecimal getAverageMargin() {
        return averageMargin;
    }

    @Nullable
    public BigDecimal getTotalProfit() {
        return totalProfit;
    }

    @Nullable
    public BigDecimal getTotalRevenue() {
        return totalRevenue;
    }
}