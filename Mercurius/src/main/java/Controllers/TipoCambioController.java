package Controllers;

import Models.TipoCambio;
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
            }
        }
        return tipoCambios;
    }
    
    public void recargar(){
        cambioActual = getTipoCambioActual();
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
