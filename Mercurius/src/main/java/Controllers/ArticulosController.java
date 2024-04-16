package Controllers;

import Models.Articulos;
import Models.Departamento;
import Models.Familia;
import Services.ArticulosService;
import Services.DepartamentoService;
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
@Named(value = "ArticulosController")
@SessionScoped
public class ArticulosController implements Serializable {
    
    @Inject private ArticulosService articulosService;
    @Inject private ViewController viewManager;
    @Inject private DepartamentoService departamentoService;
    @Inject private FamiliaService familiaService;
    
    private List<Articulos> articulosList;
    private Articulos selectedArticulo;
    private Articulos newArticulo;
    private String articulosFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private List<Departamento> departamentoOptions;
    private List<Familia> familiaOptions;


    @PostConstruct
    public void init() {
        newArticulo = new Articulos();
        selectedArticulo = new Articulos();
        articulosList();
        filterBy = new ArrayList<>();
        departamentoOptions = departamentoService.listAll();
        familiaOptions = familiaService.listAll();
    }

    public List<Articulos> articulosList() {
        if (articulosList == null) {
            articulosList = articulosService.listAll();
        }
        return articulosList;
    }

    public long articulosCount() {
        return articulosService.count();
    }

    public void openNewArticulo() {
        newArticulo = new Articulos();
    }

    public void updateArticulo() {
        articulosService.update(selectedArticulo);
        clearSelectedArticulo();
    }

    public void createArticulo() {
        articulosService.create(newArticulo);
        clearSelectedArticulo();
    }

    public void deleteArticulo() {
        if (selectedArticulo != null) {
            articulosService.delete(selectedArticulo);
            clearSelectedArticulo();
        }
    }

    public void clearSelectedArticulo() {
        articulosList = null;
        newArticulo = null;
        selectedArticulo = null;
        viewManager.selectViewArticulos();
    }

    public List<Articulos> getFilteredArticulos() {
        if (articulosFilter != null && !articulosFilter.isEmpty()) {
            return articulosList().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosList();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Articulos articulo = (Articulos) value;
        return String.valueOf(articulo.getCodigo()).contains(filterText)
                || articulo.getNombre().toLowerCase().contains(filterText);
    }

}
