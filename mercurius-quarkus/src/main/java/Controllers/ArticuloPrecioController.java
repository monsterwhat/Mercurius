package Controllers;

import Models.Articulos.ArticuloPrecio;
import Services.ArticuloPrecioService;
import Services.AlertasService;
import jakarta.annotation.PostConstruct;
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
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 *
 * @author Al
 */

@Data
@Named(value = "ArticulosPrecioController")
@ViewScoped
public class ArticuloPrecioController implements Serializable  {
    
    @Inject ArticuloPrecioService precioService;
    @Inject private AlertasService alertasService;
    @Inject private SessionController currentSession;
    
    List<ArticuloPrecio> precios;
    private String preciosFilter;
    private List<FilterMeta> filterBy;
    private ArticuloPrecio selectedPrecio;
    private String selection;
    
    /**
     *
     */
    @PostConstruct
    public void init() {
        precios = null;
        filterBy = new ArrayList<>();
        selectedPrecio = new ArticuloPrecio();
        selection = null;
    }
    
    /**
     *
     */
    public void selectGeneral(){
        selection = "general";
        System.out.println("Selected General " + selection);
    }
    
    /**
     *
     */
    public void selectArticulos(){
        selection = "articulos";
    }
    
    public List<ArticuloPrecio> preciosActivos() {
        if(precios == null){
            precios = precioService.listAll();
        }
        return precios;
    }
     
     public List<ArticuloPrecio> getFilteredPrecios() {
        if(precios == null){
            precios = precioService.listAll();
        }
        if (preciosFilter != null && !preciosFilter.isEmpty()) {
            return preciosActivos().stream()
                    .filter(precio -> globalFilterFunction(precio, preciosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return preciosActivos();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        ArticuloPrecio precio = (ArticuloPrecio) value;
        return String.valueOf(precio.getId()).contains(filterText)
                || precio.getArticulo().getNombre().toLowerCase().contains(filterText)
                || precio.getArticulo().getCodigoBarra().toLowerCase().contains(filterText)
                || (precio.getArticulo().getDepartamento() != null && precio.getArticulo().getDepartamento().getNombre().toLowerCase().contains(filterText))
                || (precio.getArticulo().getFamilia() != null && precio.getArticulo().getFamilia().getNombre().toLowerCase().contains(filterText))
                || (precio.getUsuario() != null && precio.getUsuario().getUsername().toLowerCase().contains(filterText));
    }
    
    public void updateSelectedPrecio() {
        try {
            var oldPrecio = selectedPrecio;
            precioService.update(selectedPrecio);
            alertasService.registrarAlerta("Precio actualizado", "Se ha actualizado el precio del artículo: " + selectedPrecio.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "ArticuloPrecioController.updateSelectedPrecio", oldPrecio.toString() , selectedPrecio.toString());
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error al actualizar el precio del artículo: " + selectedPrecio.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "ArticuloPrecioController.updateSelectedPrecio", selectedPrecio.toString(), e.getMessage());
        }
    }
    
}
