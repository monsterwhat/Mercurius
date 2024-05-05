package Controllers;

import Models.Inventario;
import Models.Articulos;
import Services.InventarioService;
import Services.ArticulosService;
import jakarta.annotation.PostConstruct;
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
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "InventarioController")
@ViewScoped
public class InventarioController implements Serializable {
    
    @Inject private InventarioService inventarioService;
    @Inject private ViewController viewManager;
    @Inject private ArticulosService articuloService;
    @Inject private SessionController currentSession;
    
    private List<Inventario> inventarioList;
    private List<Inventario> inventarioListAll;
    private Inventario selectedInventario;
    private Inventario newInventario;
    private String inventarioFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private List<Articulos> articuloOptions;
    private int ArticuloID = 0;

    @PostConstruct
    public void init() {
        newInventario = new Inventario();
        selectedInventario = new Inventario();
        inventarioList();
        filterBy = new ArrayList<>();
        updateArticulosOptions();
    }

    public List<Inventario> inventarioList() {
        if(inventarioList == null){
            inventarioList = inventarioService.ListAllEnabled();
        }
        return inventarioList;
    }
    
    public List<Inventario> inventarioListAll() {
        if(inventarioListAll == null){
            inventarioListAll = inventarioService.listAll();
        }
        return inventarioListAll;
    }

    public long inventarioCount() {
        return inventarioService.count();
    }

    public void openNewInventario() {
        newInventario = new Inventario();
    }

    public void updateInventario() {
        if(selectedInventario != null && ArticuloID != 0 && currentSession.isValid()){
            selectedInventario.setArticulo(articuloService.findById(ArticuloID));
            selectedInventario.setUsuario(currentSession.getCurrentUser());
            selectedInventario.setProcessed(true);
            if(selectedInventario.getArticulo() != null && selectedInventario.getUsuario() != null){
                Date today = new Date();
                selectedInventario.setFechaMovimiento(today);
                inventarioService.updateAndDisable(selectedInventario);
                clearSelectedInventario();      
            }
        }
    }

    public void createInventario() {
        if(newInventario != null && ArticuloID != 0 && currentSession.isValid()) {
            newInventario.setArticulo(articuloService.findById(ArticuloID));
            newInventario.setUsuario(currentSession.getCurrentUser());
            newInventario.setProcessed(true);
            if(newInventario.getArticulo() != null && newInventario.getUsuario() != null){
                newInventario.setStatus(true);
                Date today = new Date();
                newInventario.setFechaMovimiento(today);
                inventarioService.create(newInventario);
                clearSelectedInventario();
            }                
        }
    }
    
    public void createSimpleInventario(Inventario inventario){
        inventarioService.create(inventario);
    }

    public void deleteInventario() {
        if (selectedInventario != null) {
            inventarioService.softDelete(selectedInventario);
            clearSelectedInventario();
        }
    }

    public void clearSelectedInventario() {
        inventarioList = null;
        inventarioListAll = null;
        newInventario = null;
        selectedInventario = null;
        viewManager.selectViewInventario();
        updateArticulosOptions();
    }

    public List<Inventario> getFilteredInventario() {
        if (inventarioFilter != null && !inventarioFilter.isEmpty()) {
            return inventarioList().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioList();
        }
    }
    
    public List<Inventario> getFilteredInventarioDetallado() {
        if (inventarioFilter != null && !inventarioFilter.isEmpty()) {
            return inventarioListAll().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioListAll();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Inventario inventario = (Inventario) value;
        return String.valueOf(inventario.getCodigo()).contains(filterText)
                || inventario.getArticulo().getNombre().toLowerCase().contains(filterText)
                || inventario.getArticulo().getCodigoBarra().toLowerCase().contains(filterText)
                || inventario.getFechaMovimiento().toString().toLowerCase().contains(filterText)
                || inventario.getNotas().toLowerCase().contains(filterText)
                || inventario.getTipoMovimiento().toLowerCase().contains(filterText)
                || String.valueOf(inventario.getCantidad()).contains(filterText)
                || inventario.getUsuario().getUsername().toLowerCase().contains(filterText);
    }
    
    public void updateArticulosOptions(){
        articuloOptions = articuloService.ListAllEnabled();
    }
    
    public int getStock(Articulos articulo){
        String codigoBarra = articulo.getCodigoBarra();
        int totalStock = inventarioService.calculateTotalStockForItemByBarcode(codigoBarra);
        return totalStock;
    }
    
}
