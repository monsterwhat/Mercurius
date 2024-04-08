package Controllers;

import Models.CaByS;
import Services.CabysService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
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

    private List<CaByS> cabys;
    private CaByS selectedCabys;
    private CaByS newCabys;
    private String cabysFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    @PostConstruct
    public void init() {
        newCabys = new CaByS();
        selectedCabys = new CaByS();
        cabysList();
        filterBy = new ArrayList<>();        
    }

    public List<CaByS> cabysList() {
        if (cabys == null) {
            cabys = cabysService.listAll();
        }
        return cabys;
    }
    
    public long cabysCount() {
        return cabysService.count();
    }

    public void openNewCabys() {
        newCabys = new CaByS();
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
            if (selectedCabys.getId()!= 0) {
                cabysService.delete(selectedCabys);
                clearSelectedCabys();
            }
        }
    }

    public void clearSelectedCabys() {
        cabys = null;
        newCabys = null;
        selectedCabys = null;
        viewManager.selectViewCabys();
    }    
        
    public List<CaByS> getFilteredCabys() {
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

        CaByS cabys = (CaByS) value;
        return cabys.getDescripcionCategoria1().toLowerCase().contains(filterText) ||
               cabys.getDescripcionCategoria2().toLowerCase().contains(filterText) ||
               cabys.getDescripcionCategoria3().toLowerCase().contains(filterText) ||
               cabys.getDescripcionCategoria4().toLowerCase().contains(filterText) ||
               cabys.getDescripcionCategoria5().toLowerCase().contains(filterText) ||
               cabys.getDescripcionCategoria6().toLowerCase().contains(filterText) ||
               cabys.getDescripcionCategoria7().toLowerCase().contains(filterText) ||
               cabys.getDescripcionCategoria8().toLowerCase().contains(filterText) ||
               cabys.getNotaInclusiva1().toLowerCase().contains(filterText) ||
               cabys.getNotaExclusiva1().toLowerCase().contains(filterText) ||
               String.valueOf(cabys.getCategoria1()).contains(filterText) ||
               String.valueOf(cabys.getCategoria2()).contains(filterText) ||
               String.valueOf(cabys.getCategoria3()).contains(filterText) ||
               String.valueOf(cabys.getCategoria4()).contains(filterText) ||
               String.valueOf(cabys.getCategoria5()).contains(filterText) ||
               String.valueOf(cabys.getCategoria6()).contains(filterText) ||
               String.valueOf(cabys.getCategoria7()).contains(filterText) ||
               String.valueOf(cabys.getCategoria8()).contains(filterText) ||
               String.valueOf(cabys.getId()).contains(filterText) ||
               String.valueOf(cabys.getImpuesto()).contains(filterText);
                
    }
    
    
}
