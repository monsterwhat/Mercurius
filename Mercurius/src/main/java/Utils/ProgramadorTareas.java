package Utils;

import Services.TipoCambioService;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.inject.Inject;

@Singleton
public class ProgramadorTareas {
    
    @Inject private TipoCambioService tipoCambioService;

    //Media noche
    @Schedule(hour = "0", minute = "0", second = "0", persistent = false)
    public void actualizarTipoCambioUSD() {
        tipoCambioService.getTipoCambioFromApi();
    }
    
}
