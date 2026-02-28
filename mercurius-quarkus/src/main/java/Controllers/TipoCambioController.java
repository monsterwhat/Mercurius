package Controllers;

import Models.TipoCambio;
import Services.AlertasService;
import Services.TipoCambioService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

@Data
@Named(value = "TipoCambioController")
@ApplicationScoped
public class TipoCambioController implements Serializable {
    
    @Inject private TipoCambioService tipoCambioService;
    @Inject private SettingsController settings;
    @Inject private AlertasService alertasService;
    @Inject private SessionController currentSession;

    private List<TipoCambio> tipoCambios;
    private TipoCambio cambioActual;
    
    @PostConstruct
    public void init() {
        loadTipoCambios();
        cambioActual = getTipoCambioActual(); 
    }

    public List<TipoCambio> loadTipoCambios() {
        if (tipoCambios == null) {
            tipoCambios = tipoCambioService.listAll();
            if (tipoCambios == null || tipoCambios.isEmpty()) {
                fetchTipoCambioFromApi();
                alertasService.registrarAlerta("Tipo de Cambio Cargado", "Se cargó el tipo de cambio desde la API", currentSession.getCurrentUser(), 0, "loadTipoCambios()", null, null);
            }
        }
        return tipoCambios;
    }
    
    public void recargar(){
        cambioActual = getTipoCambioActual();
        String cambioString = cambioActual != null ? cambioActual.toString() : "null";
        alertasService.registrarAlerta("Tipo de Cambio Recargado", "Se recargo el tipo de cambio", currentSession.getCurrentUser(), 0, "recargar()", null, cambioString);
    }
    
    private void fetchTipoCambioFromApi() {
        if (settings.getCurrentSettings() != null) {
            tipoCambioService.getTipoCambioFromApi();
        }
    }
    
    public TipoCambio getTipoCambioActual() {
        return settings.getCurrentSettings() != null ? 
               tipoCambioService.getNewestTipoCambio() : 
               null;
    }
    
}
