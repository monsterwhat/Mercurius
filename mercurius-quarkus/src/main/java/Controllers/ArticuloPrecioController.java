package Controllers;

import Models.Articulos.ArticuloPrecio;
import Services.ArticuloPrecioService;
import Services.AlertasService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;
import Utils.DiffUtils;

/**
 *
 * @author Al
 */

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "ArticulosPrecioController")
@ViewScoped
public class ArticuloPrecioController implements Serializable  {
    
    @Inject @Nonnull ArticuloPrecioService precioService;
    @Inject @Nonnull private AlertasService alertasService;
    @Inject @Nonnull private SessionController currentSession;
    
    @Nullable
    List<ArticuloPrecio> precios;
    @Nullable
    private String preciosFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    @Nonnull
    private ArticuloPrecio selectedPrecio;
    @Nullable
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
        alertasService.registrarAlerta("Info", "Selected General " + selection, null, 0, "ArticuloPrecioController.selectGeneral()", null, null);
    }
    
    /**
     *
     */
    public void selectArticulos(){
        selection = "articulos";
    }
    
    @Nonnull
    public List<ArticuloPrecio> preciosActivos() {
        if(precios == null){
            precios = precioService.listAll();
        }
        return precios;
    }
     
     @Nonnull
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

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
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
            String antes = DiffUtils.snapshotEntity(selectedPrecio);
            precioService.update(selectedPrecio);
            alertasService.registrarAlerta("Precio actualizado", "Se ha actualizado el precio del artículo: " + selectedPrecio.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "ArticuloPrecioController.updateSelectedPrecio", antes, DiffUtils.snapshotEntity(selectedPrecio));
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error al actualizar el precio del artículo: " + selectedPrecio.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "ArticuloPrecioController.updateSelectedPrecio", selectedPrecio.toString(), e.getMessage());
        }
    }
    
}
