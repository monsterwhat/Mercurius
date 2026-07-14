package Controllers;

import Models.AppSettings;
import Models.Clients;
import Models.PuntosTransaccion;
import Models.Users;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ClientService;
import Services.LoyaltyService;
import jakarta.annotation.PostConstruct;
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
import java.util.Locale;
import java.util.stream.Collectors;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 * Controller for managing customer loyalty points system
 */
@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "loyaltyController")
@ViewScoped
public class LoyaltyController implements Serializable {

    @Inject @Nonnull
    private LoyaltyService loyaltyService;

    @Inject @Nonnull
    private ClientService clientService;

    @Inject @Nonnull
    private AppSettingsService appSettingsService;

    @Inject @Nonnull
    private AlertasService alertasService;

    @Inject @Nonnull
    private SessionController currentSession;

    // Form fields
    @Nullable
    private AppSettings selectedSettings;
    @Nullable
    private String clientsFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    // Lists for display
    @Nullable
    private List<Clients> topLoyaltyCustomers;
    @Nullable
    private List<PuntosTransaccion> selectedCustomerPointsHistory;

    public LoyaltyController() {
    }

    @PostConstruct
    public void init() {
        filterBy = new ArrayList<>();
        loadCurrentSettings();
        loadTopCustomers();
    }

    /**
     * Load current loyalty settings from AppSettings
     */
    public void loadCurrentSettings() {
        selectedSettings = appSettingsService.returnCurrent();
    }

    /**
     * Save loyalty settings
     */
    public void saveLoyaltySettings() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        if (selectedSettings != null) {
            appSettingsService.update(selectedSettings);
            
            alertasService.registrarAlerta(
                "Configuración de Lealtad Actualizada", 
                "Se han actualizado las configuraciones del programa de lealtad", 
                currentSession.getCurrentUser(), 
                0, 
                "LoyaltyController.saveLoyaltySettings", 
                null, 
                selectedSettings.toString()
            );

            PrimeFaces.current().executeScript("PF('SettingsDialog').hide();");
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Configuración Guardada", "Las configuraciones de lealtad se han guardado exitosamente"));
        }
    }

    /**
     * Load top loyalty customers
     */
    public void loadTopCustomers() {
        topLoyaltyCustomers = loyaltyService.getTopLoyaltyCustomers(10);
    }

    /**
     * Load points history for selected customer
     */
    public void loadCustomerPointsHistory(@Nullable Clients customer) {
        if (customer != null) {
            selectedCustomerPointsHistory = loyaltyService.getCustomerPointsHistory(customer);
        }
    }

    /**
     * Process points expiration for inactive customers
     */
    public void processPointExpiration() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión Inválida", "No tiene permisos para realizar esta acción"));
            return;
        }

        loyaltyService.checkAndExpireInactivePoints();
        loadTopCustomers(); // Refresh the list
        
        alertasService.registrarAlerta(
            "Expiración de Puntos Procesada", 
            "Se ha procesado la expiración de puntos por inactividad", 
            currentSession.getCurrentUser(), 
            0, 
            "LoyaltyController.processPointExpiration", 
            null, 
            null
        );

        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Expiración Procesada", "Se ha procesado la expiración de puntos de clientes inactivos"));
    }

    /**
     * Get list of all customers with filtering
     */
    public @Nonnull List<Clients> getFilteredCustomers() {
        if (clientsFilter != null && !clientsFilter.isEmpty()) {
            return clientService.listAll().stream()
                    .filter(client -> globalFilterFunction(client, clientsFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return clientService.listAll();
        }
    }

    /**
     * Global filter function for customers
     */
    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Clients client = (Clients) value;
        return client.getName().toLowerCase().contains(filterText)
                || (client.getEmail() != null && client.getEmail().toLowerCase().contains(filterText))
                || (client.getIdNumber() != null && client.getIdNumber().toLowerCase().contains(filterText))
                || (client.getPuntosAcumulados() != null && client.getPuntosAcumulados().toString().contains(filterText));
    }

    /**
     * Get customer tier color for display
     */
    public @Nonnull String getCustomerTierColor(@Nonnull Clients customer) {
        if (customer.getPuntosAcumulados() == null) {
            return "#cccccc"; // Gray
        }
        
        BigDecimal points = customer.getPuntosAcumulados();
        if (points.compareTo(BigDecimal.valueOf(10000)) >= 0) {
            return "#ffd700"; // Gold
        } else if (points.compareTo(BigDecimal.valueOf(5000)) >= 0) {
            return "#c0c0c0"; // Silver
        } else if (points.compareTo(BigDecimal.ZERO) > 0) {
            return "#cd7f32"; // Bronze
        }
        return "#cccccc"; // Basic
    }

    /**
     * Get customer tier label
     */
    public @Nonnull String getCustomerTierLabel(@Nonnull Clients customer) {
        if (customer.getPuntosAcumulados() == null) {
            return "Sin Puntos";
        }
        
        BigDecimal points = customer.getPuntosAcumulados();
        if (points.compareTo(BigDecimal.valueOf(10000)) >= 0) {
            return "Oro";
        } else if (points.compareTo(BigDecimal.valueOf(5000)) >= 0) {
            return "Plata";
        } else if (points.compareTo(BigDecimal.ZERO) > 0) {
            return "Bronce";
        }
        return "Básico";
    }

    // Getters and Setters for view
    public @Nullable AppSettings getSelectedSettings() {
        if (selectedSettings == null) {
            loadCurrentSettings();
        }
        return selectedSettings;
    }

    public @Nullable List<Clients> getTopLoyaltyCustomers() {
        if (topLoyaltyCustomers == null) {
            loadTopCustomers();
        }
        return topLoyaltyCustomers;
    }

    public @Nullable List<PuntosTransaccion> getSelectedCustomerPointsHistory() {
        return selectedCustomerPointsHistory;
    }

    /**
     * Clear selections and refresh data
     */
    public void clearSelections() {
        selectedCustomerPointsHistory = null;
        loadTopCustomers();
    }
}