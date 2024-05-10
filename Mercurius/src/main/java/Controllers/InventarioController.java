package Controllers;

import Models.Inventario;
import Models.Articulos;
import Services.InventarioService;
import Services.ArticulosService;
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
    
    private List<Inventario> inventarioActivo;
    private List<Inventario> inventario;
    private List<Inventario> sinProcesar;
    private List<Inventario> activosYProcesados;
    private List<Inventario> inactivos;

    private Inventario selectedInventario;
    private Articulos selectedArticulo;
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
        filterBy = new ArrayList<>();
        selectedArticulo = new Articulos();
    }

    public List<Inventario> inventarioList() {
        if(inventarioActivo == null){
            inventarioActivo = inventarioService.ListAllEnabled();
        }
        return inventarioActivo;
    }
    
    public List<Inventario> inventarioListAll() {
        if(inventario == null){
            inventario = inventarioService.listAll();
        }
        return inventario;
    }
    
    public List<Inventario> inventarioSinProcesar() {
        if(sinProcesar == null){
            sinProcesar = inventarioService.listAllSinProcesar();
        }
        return sinProcesar;
    }
    
    public List<Inventario> inventarioActivoYProcesado() {
        if(activosYProcesados == null){
            activosYProcesados = inventarioService.listAllActivosYProcesados();
        }
        return activosYProcesados;
    }
    
    public List<Inventario> inventarioInactivo() {
        if(inactivos == null){
            inactivos = inventarioService.listAllInactivos();
        }
        return inactivos;
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
    
    public void updateInventarioDetallado() {
        if(selectedInventario != null && ArticuloID != 0 && currentSession.isValid()){
            selectedInventario.setArticulo(articuloService.findById(ArticuloID));
            selectedInventario.setUsuario(currentSession.getCurrentUser());
            selectedInventario.setProcessed(true);
            if(selectedInventario.getArticulo() != null && selectedInventario.getUsuario() != null){
                Date today = new Date();
                selectedInventario.setFechaMovimiento(today);
                inventarioService.updateAndDisable(selectedInventario);
                clearSelectedInventario();
                resetViewInventarioDetallado();
            }
        }
    }
        
    public void updateInventarioRevision() {
        if(selectedInventario != null && currentSession.isValid()){
            //Should find the active version of this item...
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
                resetViewInventario();
            }                
        }
    }
    
    public void createInventarioDetallado() {
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
                resetViewInventarioDetallado();
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
            resetViewInventario();
        }
    }
    
    public void deleteInventarioDetallado() {
        if (selectedInventario != null) {
            inventarioService.softDelete(selectedInventario);
            clearSelectedInventario();
            resetViewInventarioDetallado();
        }
    }

    public void clearSelectedInventario() {
        clearCache();
        clearInventario();
    }
    
    public void resetViewInventario(){
        viewManager.selectViewInventario();
    }
    
    public void resetViewInventarioDetallado(){
        viewManager.selectViewInventarioDetallado();
    }

    public List<Inventario> getFilteredInventarioSinProcesar() {
        if(sinProcesar == null){
            sinProcesar = inventarioService.listAllSinProcesar();
        }
        if (inventarioFilter != null && !inventarioFilter.isEmpty()) {
            return inventarioSinProcesar().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioSinProcesar();
        }
    }
    
    public List<Inventario> getFilteredInventarioActivoYProcesado() {
        if(activosYProcesados == null){
            activosYProcesados = inventarioService.listAllActivosYProcesados();
        }
        if (inventarioFilter != null && !inventarioFilter.isEmpty()) {
            return inventarioActivoYProcesado().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioActivoYProcesado();
        }
    }
    
    public List<Inventario> getFilteredInventarioInactivo() {
        if(inactivos == null){
            inactivos = inventarioService.listAllInactivos();
        }
        if (inventarioFilter != null && !inventarioFilter.isEmpty()) {
            return inventarioInactivo().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioInactivo();
        }
    }
    
    public List<Inventario> getFilteredInventario() {
        if(inventarioActivo == null){
            inventarioActivo = inventarioService.ListAllEnabled();
        }
        if (inventarioFilter != null && !inventarioFilter.isEmpty()) {
            return inventarioList().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioList();
        }
    }
    
    public List<Inventario> getFilteredInventarioDetallado() {
        if(inventario == null){
            inventario = inventarioService.listAll();
        }
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
    
    public void articuloSelectedEdit() {
        if (selectedArticulo != null) {
            selectedInventario.setArticulo(selectedArticulo);
        } else {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún CABYS.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    
    }
    
    public void clearCache(){
        inventario = null;
        inventarioActivo = null;
        inactivos = null;
        sinProcesar = null;
    }
    
    public void clearInventario(){
        newInventario = null;
        selectedInventario = null;
    }
    
    public double getStock(Articulos articulo){
        String codigoBarra = articulo.getCodigoBarra();
        double totalStock = inventarioService.calculateTotalStockForItemByBarcode(codigoBarra);
        return totalStock;
    }
    
}
