package Controllers.Correos;

import Models.Correos.ReporteProgramado; 
import Services.AlertasService;
import Services.Correos.ReportesProgramadosService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Date;
import java.util.List;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class CorreosScheduler {
    
    @Inject ReportesProgramadosService rpService;    
    @Inject CorreosHelper helper;
    @Inject AlertasService alertasService;
    
    private List<ReporteProgramado> reportes;
    
    //Midnight everyday!
    @Scheduled(cron = "0 0 0 * * ?")
    public void checkReportesActivos() {
        reportes = rpService.listAll();
        
        for (ReporteProgramado reporte : reportes) {
            if (reporte.isStatus()) {
                Date fechaUltimoReporte = reporte.getLastRun();
                
                if (fechaUltimoReporte != null) { // Null check added here
                    List<String> frecuencias = reporte.getFrecuencia();
                    
                    for (String frecuencia : frecuencias) {
                        Date fechaProximoReporte = helper.calcularFechaProximoReporte(fechaUltimoReporte, frecuencia);
                        
                        if (new Date().after(fechaProximoReporte) || new Date().equals(fechaProximoReporte)) {
                            helper.checkChanges(reporte);
                             
                        } 
                    }
                } else {
                    //Null fecha...
                }
            }else{
                //Disabled Reporte...
            }
        }
    }
}
