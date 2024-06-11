package Controllers;

import Models.TipoCambio;
import Services.TipoCambioService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.enterprise.context.SessionScoped;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

@Data
@Named(value = "TipoCambioController")
@SessionScoped
public class TipoCambioController implements Serializable {
    
    @Inject private TipoCambioService tipoCambioService;
    @Inject private SettingsController settings;

    private List<TipoCambio> tipoCambios;
    
    private TipoCambio cambioActual;
    
    @PostConstruct
    public void init() {
        tipoCambioList();
        cambioActual = getTipoCambioActual();
    }

    public List<TipoCambio> tipoCambioList() {
        if(tipoCambios == null){
            tipoCambios = tipoCambioService.listAll();
            if(tipoCambios == null){
                getTipoCambioFromApi();
            }
        }
        return tipoCambios;
    }
    
    public void recargar(){
        cambioActual = getTipoCambioActual();
    }
    
    public void getTipoCambioFromApi(){
        var configuracion = settings.getCurrentSettings();
        if(configuracion != null){
            var diferencia = configuracion.getDiferenciaCambio();
            tipoCambioService.getTipoCambioFromApi(diferencia);
        }
    }
    
    public TipoCambio getTipoCambioActual(){
        var configuracion = settings.getCurrentSettings();
        if(configuracion != null){
            var diferencia = configuracion.getDiferenciaCambio();
            return tipoCambioService.getNewestTipoCambio(diferencia);
        }else{
            return tipoCambioService.getNewestTipoCambio(0);
        }
    }
    
}
