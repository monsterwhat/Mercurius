package Controllers;

import Models.Cabys;
import Services.CabysService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 *
 * @author Al
 */


@Data
@Named(value = "CabysController")
@SessionScoped
public class CabysController implements Serializable {
    
    @Inject private CabysService cabysService;
    @Inject private ViewController viewManager;
    @Inject private ArticulosController articulos;

    private List<Cabys> catalogo;
    private Cabys selectedCabys;
    private Cabys newCabys;
    private String cabysFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private boolean cabysStatus;
    
    @PostConstruct
    public void init() {
        newCabys = new Cabys();
        selectedCabys = new Cabys();
        cabysList();
        filterBy = new ArrayList<>();        
    }

    public List<Cabys> cabysList() {
        if (catalogo == null) {
            catalogo = cabysService.listAll();
            if(catalogo == null || catalogo.isEmpty()){
                cabysStatus=false;
            }
        }
        return catalogo;
    }
    
    public List<Cabys> cabysListApi(){
        catalogo = cabysService.listAllAPI();
        saveAPItoDB();
        cabysStatus = true;
        return catalogo;
    }
    
    public long cabysCount() {
        return cabysService.count();
    }

    public void openNewCabys() {
        newCabys = new Cabys();
    }
    
    public void saveAPItoDB(){
        cabysService.saveAllDB(catalogo);
    }

    public void updateCabys() {
        cabysService.update(selectedCabys);
        clearSelectedCabys();
    }

    public void createCabys() {
        cabysService.create(newCabys);
        clearSelectedCabys();
    }

    public void deleteCabys() {
        if (selectedCabys != null) {
            if (!selectedCabys.getCodigo().isBlank()) {
                cabysService.delete(selectedCabys);
                clearSelectedCabys();
            }
        }
    }

    public void clearSelectedCabys() {
        catalogo = null;
        newCabys = null;
        selectedCabys = null;
        viewManager.selectViewCabys();
    }    
        
    public List<Cabys> getFilteredCabys() {
        if (cabysFilter != null && !cabysFilter.isEmpty()) {
            return cabysList().stream()
                    .filter(profile -> globalFilterFunction(profile, cabysFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return cabysList();
        }
    }
       
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Cabys catalogo = (Cabys) value;
        return catalogo.getCodigo().toLowerCase().contains(filterText) ||
               catalogo.getDescripcion().toLowerCase().contains(filterText) ||
               catalogo.getCategorias().contains(filterText) ||
               catalogo.getEstado().toLowerCase().contains(filterText) ||
               String.valueOf(catalogo.getImpuesto()).contains(filterText);   
    }
    
    public void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().
                addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public void showInfo(String message, String content) {
        addMessage(FacesMessage.SEVERITY_INFO, message, content);
    }

    public void showWarn(String message, String content) {
        addMessage(FacesMessage.SEVERITY_WARN, message, content);
    }

    public void showError(String message, String content) {
        addMessage(FacesMessage.SEVERITY_ERROR, message, content);
    }

    public void showSticky(String message, String content) {
        FacesContext.getCurrentInstance().addMessage("sticky-key", new FacesMessage(FacesMessage.SEVERITY_INFO, message, content));
    }
    
    public void selectCabys() {
    if (selectedCabys != null) {
        articulos.getNewArticulo().setCodigoCabys(selectedCabys);
    } else {
        FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún CABYS.");
        FacesContext.getCurrentInstance().addMessage(null, message);
    }
}

    
}
