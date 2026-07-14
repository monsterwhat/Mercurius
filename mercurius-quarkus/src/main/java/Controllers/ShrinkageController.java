package Controllers;

import Models.Inventario;
import Services.InventarioService;
import Services.ShrinkageAnalysisService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString @EqualsAndHashCode
@Named("shrinkageController")
@ViewScoped
public class ShrinkageController implements Serializable {

    @Inject
    @Nonnull
    private ShrinkageAnalysisService shrinkageAnalysisService;

    @Inject
    @Nonnull
    private InventarioService inventarioService;

    @Inject
    @Nonnull
    private SessionController currentSession;

    // Date range filters
    @Nonnull
    private Date startDate;
    @Nonnull
    private Date endDate;

    // Summary data
    @Nullable
    private BigDecimal totalShrinkage;
    @Nullable
    private BigDecimal shrinkagePercentage;
    @Nullable
    private BigDecimal totalInventoryMovement;
    @Nullable
    private Map<String, BigDecimal> shrinkageByCause;
    @Nullable
    private Map<String, BigDecimal> shrinkageByDepartment;

    // Detailed movements list
    @Nullable
    private List<Inventario> shrinkageMovements;

    public ShrinkageController() {
        Calendar cal = Calendar.getInstance();
        endDate = new Date();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        startDate = cal.getTime();
    }

    @PostConstruct
    public void init() {
        refreshData();
    }

    /**
     * Load all shrinkage data for the current date range.
     */
    public void refreshData() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesi\u00f3n Inv\u00e1lida", "No tiene permisos para realizar esta acci\u00f3n"));
            return;
        }

        try {
            Date start = inventarioService.getStartOfDay(startDate);
            Date end = inventarioService.getEndOfDay(endDate);

            totalShrinkage = shrinkageAnalysisService.getTotalShrinkage(start, end);
            totalInventoryMovement = shrinkageAnalysisService.getTotalInventoryMovement(start, end);
            shrinkagePercentage = shrinkageAnalysisService.getShrinkagePercentage(start, end);

            shrinkageByCause = shrinkageAnalysisService.getShrinkageByCause(start, end);
            shrinkageByDepartment = shrinkageAnalysisService.getShrinkageByDepartment(start, end);

            shrinkageMovements = shrinkageAnalysisService.getShrinkageMovements(start, end);

        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar los datos de mermas: " + e.getMessage()));
        }
    }

    /**
     * Get human-readable label for shrinkage cause type.
     */
    @Nonnull
    public String getCauseLabel(@Nullable String tipo) {
        if (tipo == null) return "";
        return switch (tipo) {
            case "Merma" -> "Merma General";
            case "Perdida/Robo" -> "P\u00e9rdida/Robo";
            case "Vencimiento" -> "Vencimiento";
            case "Da\u00f1o" -> "Da\u00f1o";
            default -> tipo;
        };
    }

    /**
     * Get color for shrinkage cause display.
     */
    @Nonnull
    public String getCauseColor(@Nullable String tipo) {
        if (tipo == null) return "#cccccc";
        return switch (tipo) {
            case "Merma" -> "#ffc107";
            case "Perdida/Robo" -> "#dc3545";
            case "Vencimiento" -> "#17a2b8";
            case "Da\u00f1o" -> "#fd7e14";
            default -> "#6c757d";
        };
    }

    /**
     * Calculate percentage contribution of a specific cause to total shrinkage.
     */
    @Nonnull
    public BigDecimal getCausePercentage(@Nullable String tipo) {
        if (shrinkageByCause == null || totalShrinkage == null
            || totalShrinkage.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal causeTotal = shrinkageByCause.getOrDefault(tipo, BigDecimal.ZERO);
        return causeTotal.abs()
                .divide(totalShrinkage.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * Check if there's data to display.
     */
    public boolean hasData() {
        return shrinkageMovements != null && !shrinkageMovements.isEmpty();
    }

    /**
     * Get shrinkage movements count.
     */
    public int getMovementCount() {
        return shrinkageMovements != null ? shrinkageMovements.size() : 0;
    }
}
