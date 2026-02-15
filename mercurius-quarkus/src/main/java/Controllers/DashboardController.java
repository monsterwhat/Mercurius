package Controllers;

import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import Services.DashboardService; 
import Models.ComprobantesEmitidos;
import Models.Users; 
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Named(value = "DashboardController")
@ViewScoped
public class DashboardController implements Serializable {
    
    @Inject private SessionController sessionController;
    @Inject private DashboardService dashboardService;
    
    // Today's stats
    private BigDecimal todaySales;
    private int transactionCount;
    private int itemsSold;
    
    // Last transaction
    private ComprobantesEmitidos lastTransaction;
    private String lastTransactionDisplay;
    
    // Recent activity
    private List<ComprobantesEmitidos> recentSales;
    
    // Current date for display
    private String currentDate;
    
    // Flag to ensure data is loaded only once
    private boolean dataLoaded = false;
    
    public DashboardController() {
    }
    
    @PostConstruct
    public void init() {
    }
    
public void loadDashboardData() {
        try {
            if (sessionController != null && sessionController.isValid()) {
                Users currentUser = sessionController.getCurrentUser();
                if (currentUser != null) {
                    todaySales = dashboardService.getTodaySales(currentUser);
                    transactionCount = dashboardService.getTransactionCount(currentUser);
                    itemsSold = dashboardService.getItemsSold(currentUser);
                    lastTransaction = dashboardService.getLastTransaction(currentUser);
                    recentSales = dashboardService.getRecentSales(currentUser, 10);
                    
                    // Format current date
                    currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                    
                    updateLastTransactionDisplay();
                    dataLoaded = true;
                }
            }
        } catch (Exception e) {
            System.out.println("DashboardController: Error loading dashboard data: " + e.toString());
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar los datos del dashboard"));
        }
    }
    
    public String startNewSale() {
        return "/secured/pages/Facturas/Facturas/factura.xhtml?faces-redirect=true";
    }
    
    private void updateLastTransactionDisplay() {
        if (lastTransaction != null) {
            try {
                String billNumber = lastTransaction.getEncabezado().getNumeroConsecutivo();
                BigDecimal total = lastTransaction.getResumen().getTotalComprobante();
                LocalDateTime time = lastTransaction.getEncabezado().getFechaEmision();
                String timeStr = time.format(DateTimeFormatter.ofPattern("HH:mm"));
                
                lastTransactionDisplay = String.format("Factura %s - %s colones a las %s", 
                    billNumber, total, timeStr);
            } catch (Exception e) {
                System.out.println("Error formatting last transaction: " + e.toString());
                lastTransactionDisplay = "Error al cargar última transacción";
            }
        } else {
            lastTransactionDisplay = "No hay transacciones hoy";
        }
    }
    
    // Getters and Setters
    public String getCurrentDate() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return currentDate;
    }
    
    public BigDecimal getTodaySales() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return todaySales;
    }
    
    public int getTransactionCount() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return transactionCount;
    }
    
    public int getItemsSold() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return itemsSold;
    }
    
    public ComprobantesEmitidos getLastTransaction() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return lastTransaction;
    }
    
    public String getLastTransactionDisplay() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return lastTransactionDisplay;
    }
    
    public List<ComprobantesEmitidos> getRecentSales() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return recentSales;
    }
}