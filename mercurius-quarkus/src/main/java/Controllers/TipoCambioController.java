package Controllers;

import Models.TipoCambio;
import Services.TipoCambioService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString(exclude = {"settings", "currentSession"}) @EqualsAndHashCode(exclude = {"settings", "currentSession"})
@Named(value = "TipoCambioController")
@ApplicationScoped
public class TipoCambioController implements Serializable {
    
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(TipoCambioController.class.getName());
    
    @Inject @Nonnull private TipoCambioService tipoCambioService;
    @Inject @Nonnull private SettingsController settings;
    @Inject @Nonnull private SessionController currentSession;

    @Nullable
    private List<TipoCambio> tipoCambios;
    @Nullable
    private TipoCambio cambioActual;
    
    @PostConstruct
    public void init() {
        loadTipoCambios();
        cambioActual = getTipoCambioActual(); 
    }

    @Nullable
    public List<TipoCambio> loadTipoCambios() {
        if (tipoCambios == null) {
            tipoCambios = tipoCambioService.listAll();
            if (tipoCambios == null || tipoCambios.isEmpty()) {
                fetchTipoCambioFromApi();
                LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                        "Tipo de Cambio Cargado", "Se cargó el tipo de cambio desde la API",
                        currentSession.getCurrentUser() != null ? currentSession.getCurrentUser().getUsername() : "Sistema",
                        0, "loadTipoCambios()", null, null));
            }
        }
        return tipoCambios;
    }
    
    public void recargar(){
        cambioActual = getTipoCambioActual();
        String cambioString = cambioActual != null ? cambioActual.toString() : "null";
        LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                "Tipo de Cambio Recargado", "Se recargo el tipo de cambio",
                currentSession.getCurrentUser() != null ? currentSession.getCurrentUser().getUsername() : "Sistema",
                0, "recargar()", null, cambioString));
    }
    
    private void fetchTipoCambioFromApi() {
        if (settings.getCurrentSettings() != null) {
            tipoCambioService.getTipoCambioFromApi();
        }
    }
    
    @Nullable
    public TipoCambio getTipoCambioActual() {
        return settings.getCurrentSettings() != null ? 
               tipoCambioService.getNewestTipoCambio() : 
               null;
    }
    
}
