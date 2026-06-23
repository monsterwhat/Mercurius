package Controllers;

import Models.Familia;
import Services.AlertasService;
import Services.FamiliaService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "FamiliasController")  
@ViewScoped
public class FamiliaController implements Serializable {
    
    @Inject private AlertasService alertas;
    @Inject private FamiliaService familiaService;
    @Inject private SessionController currentSession;

    private List<Familia> familias;
    private Familia selectedFamilia;
    private Familia newFamilia;
    private String familiaFilter; 
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    @PostConstruct
    public void init() {
        newFamilia = new Familia();
        selectedFamilia = new Familia();
        familiasList();
        filterBy = new ArrayList<>();
    }

    public List<Familia> familiasList() {
        if (familias == null) {
            familias = familiaService.listAll();
        }
        return familias;
    }

    public long familiaCount() {
        return familiaService.count();
    }
    
    public long familiasActivasCount(){
        return familiaService.countActivas();
    }
    
    public long familiasInactivasCount(){
        return familiaService.countInactivas();
    }

    public void openNewFamilia() {
        newFamilia = new Familia();
    }
    
    public void updateFamiliaDialog() {
        if(currentSession.isValid()){
            if(selectedFamilia != null){
                var oldFamilia = selectedFamilia;
                selectedFamilia.setUsuario(currentSession.getCurrentUser());
                selectedFamilia.setFecha(new Date());
                familiaService.updateAndDisable(selectedFamilia);
                alertas.registrarAlerta("Familia actualizada", "Se ha actualizado la familia: " + selectedFamilia.getNombre(), currentSession.getCurrentUser(), 0, "FamiliaController.updateFamiliaDialog", oldFamilia.toString(), selectedFamilia.toString());
                clearSelectedFamilia();
                
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se edito la familia", null));

                PrimeFaces.current().executeScript("PF('EditarFamiliaDialog').hide();");
            }
        }else{
             FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }
    
    public void createFamiliaDialog() {
        if(currentSession.isValid()){
            if(newFamilia != null){
                newFamilia.setStatus(true);
                newFamilia.setUsuario(currentSession.getCurrentUser());
                newFamilia.setFecha(new Date());
                var valid = familiaService.createIfNotExists(newFamilia);
                if(valid){
                    alertas.registrarAlerta("Info", "DEBUG: Familia created: " + newFamilia.getNombre(), currentSession.getCurrentUser(), 0, "FamiliaController.createFamiliaDialog()", null, null);
                    alertas.registrarAlerta("Familia creada", "Se ha creado la familia: " + newFamilia.getNombre(), currentSession.getCurrentUser(), 0, "FamiliaController.createFamiliaDialog", null, newFamilia.toString());
                    FacesContext.getCurrentInstance().addMessage("messages",
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creo la familia", null));

                    // Clear cache to force refresh
                    familias = null;
                    alertas.registrarAlerta("Info", "Cache cleared, familias set to null", currentSession.getCurrentUser(), 0, "FamiliaController.createFamiliaDialog()", null, null);
                    
                    // Reset for next family creation
                    newFamilia = new Familia();
                    alertas.registrarAlerta("Info", "newFamilia reset for next entry", currentSession.getCurrentUser(), 0, "FamiliaController.createFamiliaDialog()", null, null);

                    PrimeFaces.current().executeScript("PF('CrearFamiliaDialog').hide();");
                }else{
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Ya existe una familia con ese nombre!", null));
                }
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }
    
    public void deleteFamilia() {
        if (selectedFamilia != null) {
            var oldFamilia = selectedFamilia;
            familiaService.softDelete(selectedFamilia);
            alertas.registrarAlerta("Familia eliminada", "Se ha eliminado la familia: " + selectedFamilia.getNombre(), currentSession.getCurrentUser(), 0, "FamiliaController.deleteFamilia", oldFamilia.toString() , selectedFamilia.toString());
            clearSelectedFamilia();
        }
    }

    public void clearSelectedFamilia() {
        familias = null;
        newFamilia = null;
        selectedFamilia = null;
    }

    public List<Familia> getFilteredFamilias() {
        if (familiaFilter != null && !familiaFilter.trim().isEmpty()) {
            return familiasList().stream()
                    .filter(familia -> globalFilterFunction(familia, familiaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return familiasList();
        }
    }
    
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Familia familia = (Familia) value;
        return familia.getNombre().toLowerCase().contains(filterText)
                || String.valueOf(familia.getId()).contains(filterText)
                || familia.getUsuario().getUsername().toLowerCase().contains(filterText);
    }

}
