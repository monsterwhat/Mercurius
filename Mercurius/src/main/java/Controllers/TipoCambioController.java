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
        tipoCambioService.getTipoCambioFromApi();
    }
    
    public TipoCambio getTipoCambioActual(){
        return tipoCambioService.getNewestTipoCambio();
    }
    
}
