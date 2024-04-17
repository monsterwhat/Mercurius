package Controllers;

import Models.Departamento;
import Models.Familia;
import Services.FamiliaService;
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

@Data
@Named(value = "FamiliasController")
@SessionScoped
public class FamiliaController implements Serializable {
    @Inject private FamiliaService familiaService;
    @Inject private ViewController viewManager;

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

    public void openNewFamilia() {
        newFamilia = new Familia();
    }

    public void updateFamilia() {
        familiaService.update(selectedFamilia);
        clearSelectedFamilia();
    }

    public void createFamilia() {
        familiaService.create(newFamilia);
        clearSelectedFamilia();
    }
    
    public void createFamiliaSimple() {
        familiaService.create(newFamilia);
        clearSelectedFamiliaSimple();
    }

    public void deleteFamilia() {
        if (selectedFamilia != null) {
            familiaService.delete(selectedFamilia);
            clearSelectedFamilia();
        }
    }

    public void clearSelectedFamilia() {
        familias = null;
        newFamilia = null;
        selectedFamilia = null;
        viewManager.selectViewFamilias();
    }
    
    public void clearSelectedFamiliaSimple() {
        familias = null;
        newFamilia = null;
        selectedFamilia = null;
    }

    public List<Familia> getFilteredFamilias() {
        if (familiaFilter != null && !familiaFilter.isEmpty()) {
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
                || String.valueOf(familia.getId()).contains(filterText);
    }

    public Familia findFamiliaById(Integer number) {
        
        return familiaService.findById(number);
        
    }

}
