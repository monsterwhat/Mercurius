package Controllers.Correos;

import Controllers.SessionController;
import Models.Correos.ReporteProgramado;
import Services.Correos.ReportesProgramadosService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 *
 * @author Al
 */

@Data
@Named
@ViewScoped
public class ReportesProgramadosController implements Serializable {
    
    @Inject ReportesProgramadosService reportesProgramadosService;
    
    @Inject private SessionController currentSession;

    private List<ReporteProgramado> reportes;
    private ReporteProgramado selectedReporte;
    private ReporteProgramado newReporte;
    private String reportesFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    public ReportesProgramadosController() {
    }

    @PostConstruct
    public void init() {
        newReporte = new ReporteProgramado();
        selectedReporte = new ReporteProgramado();
        reportesList();
        filterBy = new ArrayList<>();        
    }

    public List<ReporteProgramado> reportesList() {
        if (reportes == null) {
            reportes = reportesProgramadosService.listAll();
        }
        return reportes;
    }

    public long reportesCount() {
        return reportesProgramadosService.count();
    }

    public void openNewReporte() {
        newReporte = new ReporteProgramado();
    }

    public void updateReporte() {
        if(currentSession.isValid()){
            reportesProgramadosService.update(selectedReporte);
            clearSelectedReporte();
            PrimeFaces.current().executeScript("PF('EditarReporteDialog').hide();");
        }
    }

    public void createReporte() {
        if(currentSession.isValid()){
            var exists = reportesProgramadosService.findByName(newReporte.getPerfil());
            if(!exists){
                newReporte.setStatus(true);
                reportesProgramadosService.create(newReporte);
                clearSelectedReporte();    
                PrimeFaces.current().executeScript("PF('CrearReporteDialog').hide();");
            }
        }
    }

    public void toggleReporte() {
        if (selectedReporte != null) {
            if(selectedReporte.isStatus()){
                disableReporte();
            }else{
                enableReporte();
            }
            reportesProgramadosService.update(selectedReporte);
            clearSelectedReporte();
        }
    }
    
    public void disableReporte(){
        selectedReporte.setStatus(false);
    }
    
    public void enableReporte(){
        selectedReporte.setStatus(true);
    }

    public void clearSelectedReporte() {
        reportes = null;
        newReporte = null;
        selectedReporte = null;
    }    
        
    public List<ReporteProgramado> getFilteredReportesProgramados() {
        if (reportesFilter != null && !reportesFilter.isEmpty()) {
            return reportesList().stream()
                    .filter(profile -> globalFilterFunction(profile, reportesFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return reportesList();
        }
    }
       
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        ReporteProgramado reporte = (ReporteProgramado) value;
        return reporte.getPerfil().toLowerCase().contains(filterText)
                || reporte.getCorreos().toString().toLowerCase().contains(filterText)
                || reporte.getReportes().toString().toLowerCase().contains(filterText)
                || reporte.getTipo().toString().toLowerCase().contains(filterText);
    }
    
    public void updateReporteDialog() {
        if(currentSession.isValid()){
            if(selectedReporte != null){
                reportesProgramadosService.updateAndDisable(selectedReporte);
                clearSelectedReporte();
                
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se edito el reporte programado", null));

                PrimeFaces.current().executeScript("PF('EditarReporteDialog').hide();");
            }
        }else{
             FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }
    
    public void createReporteDialog() {
        if(currentSession.isValid()){
            if(newReporte != null){
                newReporte.setStatus(true);
                var valid = reportesProgramadosService.createIfNotExists(newReporte);
                if(valid){
                    clearSelectedReporte();
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creo el reporte programado", null));

                    PrimeFaces.current().executeScript("PF('CrearReporteDialog').hide();");
                }else{
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Ya existe un reporte programado con ese nombre!", null));
                }
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }
    
}
