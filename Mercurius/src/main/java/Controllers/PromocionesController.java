package Controllers;

import Models.ArticuloCarrito;
import Models.Articulos;
import Models.Promocion;
import Services.AlertasService;
import Services.ArticulosService;
import Services.PromocionesService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
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
@Named(value = "PromocionesController")
@ViewScoped
public class PromocionesController implements Serializable {
    
    @Inject private SessionController currentSession;
    @Inject private PromocionesService promoService;
    @Inject private AlertasService alertas;
    @Inject private ArticulosService articuloService;

    private List<Promocion> promociones;
    private List<ArticuloCarrito> lista;
    private Promocion selectedPromocion;
    private Promocion newPromocion;
    private String promocionFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private String selectedPromocionString;
    private String totalDescuentoConIVA = "0";
    private List<Date> fechasPromocion;
    private Articulos selectedArticulo;
    private BigDecimal cantidad;

    @PostConstruct
    public void init() {
        newPromocion = new Promocion();
        selectedPromocion = new Promocion();
        filterBy = new ArrayList<>();
    }
    
    public List<Promocion> promocionesList() {
        if (promociones == null) {
            promociones = promoService.listAll();
        }
        return promociones;
    }
    
    public List<Promocion> promocionesListAll() {
        return promoService.listAll();
    }

    public long promocionCount() {
        return promoService.count();
    }
    
    public long promocionesActivosCount(){
        return promoService.countActivos();
    }
    
    public long promocionesInactivosCount(){
        return promoService.countInactivos();
    }

    public void openNewPromocion() {
        newPromocion = new Promocion();
        PrimeFaces.current().executeScript("PF('CrearPromocionDialog').show();");
    }

    public void updatePromocion() {
        if(currentSession.isValid()){
            if(selectedPromocion !=null ){
                var oldPromocion = selectedPromocion;
                selectedPromocion.setUsuario(currentSession.getCurrentUser());
                promoService.update(selectedPromocion);
                alertas.registrarAlerta("Promocion Actualizada", "Se actualizo la promocion: " + selectedPromocion.getNombre(), currentSession.getCurrentUser(), 0, "updatePromocion()", oldPromocion.toString(), selectedPromocion.toString());
                clearSelectedPromocion();
                
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se actualizo la promocion!", null));
                PrimeFaces.current().executeScript("PF('EditarPromocionDialog').hide();");

            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }

    public void createPromocion() {
        if(currentSession.isValid()){
            if(newPromocion != null){
                newPromocion.setActiva(true);
                newPromocion.setUsuario(currentSession.getCurrentUser());
                promoService.create(newPromocion);
                alertas.registrarAlerta("Promocion Creada", "Se creo la promocion: " + newPromocion.getNombre(), currentSession.getCurrentUser(), 0, "createPromocion()", null, newPromocion.toString());
                clearSelectedPromocion(); 
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creo la promocion", null));
                PrimeFaces.current().executeScript("PF('CrearPromocionDialog').hide();");

            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }

    public void deletePromocion() {
        if (selectedPromocion != null) {
            var oldPromo = selectedPromocion;
            promoService.delete(selectedPromocion);
            alertas.registrarAlerta("Promocion Eliminada", "Se elimino la promocion: " + selectedPromocion.getNombre(), currentSession.getCurrentUser(), 0, "deletePromocion()", oldPromo.toString(), selectedPromocion.toString());
            clearSelectedPromocion();
        }
    }
    
    public void removeSelectedItemFromPromociones(Articulos articulo) {
    ArticuloCarrito itemToRemove = null;

        // Iterate through the list to find the matching articulo
        for (ArticuloCarrito articuloCarrito : lista) {
            if (articuloCarrito.getArticulo().equals(articulo)) {
                itemToRemove = articuloCarrito;
                break; // Exit loop once the item is found
            }
        }

        // Remove the item from the list if found
        if (itemToRemove != null) {
            System.out.println(itemToRemove.getArticulo().getNombre());
            lista.remove(itemToRemove);
        }
    }


    public void clearSelectedPromocion() {
        promociones = null;
        newPromocion = null;
        selectedPromocion = null;
        lista = null;
    }

    public List<Promocion> getFilteredPromocions() {
        if (promocionFilter != null && !promocionFilter.isEmpty()) {
            return promocionesList().stream()
                    .filter(promocion -> globalFilterFunction(promocion, promocionFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return promocionesList();
        }
    }
    
    public List<Promocion> getFilteredPromocionsDetallados() {
        if (promocionFilter != null && !promocionFilter.isEmpty()) {
            return promocionesListAll().stream()
                    .filter(promocion -> globalFilterFunction(promocion, promocionFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return promocionesListAll();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Promocion promocion = (Promocion) value;
        return promocion.getNombre().toLowerCase().contains(filterText)
                || String.valueOf(promocion.getId()).contains(filterText)
                || promocion.getUsuario().getUsername().toLowerCase().contains(filterText);
    }

    public Promocion findPromocionById(Integer number) {
        
        return promoService.findById(number);
        
    }
    
    public void createPromocionByDialog() {
        if (newPromocion == null) {
            return;
        }

        if (lista == null || lista.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay artículos en la promoción", null));
            return;
        }

        newPromocion.setUsuario(currentSession.getCurrentUser());
        newPromocion.setArticulosCarrito(lista);

        if (fechasPromocion == null || fechasPromocion.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No se seleccionaron fechas para la promoción", null));
            return;
        }

        // Extract the start and end dates
        Date fechaInicio = fechasPromocion.get(0);
        Date fechaFin = fechasPromocion.get(fechasPromocion.size() - 1);

        if (fechaInicio == null) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay fecha de inicio en la promoción", null));
            return;
        }

        if (fechaFin == null) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay fecha de fin en la promoción", null));
            return;
        }

        newPromocion.setFechaInicio(fechaInicio);
        newPromocion.setFechaFin(fechaFin);
        newPromocion.setActiva(true);
        
        if (newPromocion.getUsuario() != null) {
            promoService.create(newPromocion);
            alertas.registrarAlerta("Promocion Creada", "Se creó la promoción: " + newPromocion.getNombre(), currentSession.getCurrentUser(), 0, "createPromocionByDialog()", null, newPromocion.toString());
            for (ArticuloCarrito articulo : lista) {
                var articuloToUpdate = articulo.getArticulo();
                List<Promocion> promociones = new ArrayList<>();
                promociones.add(newPromocion);
                
                articuloToUpdate.setPromociones(promociones);
                articuloService.update(articuloToUpdate);
            }
            
            clearSelectedPromocion();

            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creó la promoción", null));

            PrimeFaces.current().executeScript("PF('CrearPromocionDialog').hide();");
            
        }
    }

    public void editPromocion(){
        PrimeFaces.current().executeScript("PF('EditPromocionDialog').show();");
        lista = selectedPromocion.getArticulosCarrito();
        fechasPromocion = selectedPromocion.getFechas();
    }
    
    public void articuloSelectedDialog() {
        if (selectedArticulo != null) {
            if (cantidad != null) {
                boolean exists = false;

                // Initialize the list if it's null
                if (lista == null) {
                    lista = new ArrayList<>();
                }

                // Iterate through the list to check if the article is already there
                for (ArticuloCarrito articulo : lista) {
                    if (articulo.getArticulo().getCodigo() == selectedArticulo.getCodigo()) {
                        // Update the quantity of the existing article
                        articulo.setCantidad(articulo.getCantidad() + cantidad.doubleValue());
                        exists = true;
                        break;
                    }
                }

                // If the article wasn't found in the list, add it as a new item
                if (!exists) {
                    ArticuloCarrito articulo = new ArticuloCarrito(selectedArticulo, cantidad.doubleValue());
                    lista.add(articulo);
                }

                // Reset selectedArticulo and cantidad
                selectedArticulo = null;
                cantidad = BigDecimal.ZERO;

                // Hide the dialog
                PrimeFaces.current().executeScript("PF('ArticuloRevisionDialog').hide();");

            } else {
                FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha ingresado ningúna cantidad.");
                FacesContext.getCurrentInstance().addMessage(null, message);
            }
        } else {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún Articulo.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
    
    public BigDecimal totalListaConIVA(){
        
        BigDecimal total = BigDecimal.ZERO;
        
        if(lista == null){
            return BigDecimal.ZERO;
        }
        
        for (ArticuloCarrito articulo : lista) {
            BigDecimal precioFinal = articulo.getArticulo().getLastPrecio().getPrecioFinal();
            total = total.add(precioFinal.multiply(new BigDecimal(articulo.getCantidad())));        }
        
        return total;
    }
    
    public BigDecimal totalListaConUtilidad(){
        
        BigDecimal total = BigDecimal.ZERO;
        
        if(lista == null){
            return BigDecimal.ZERO;
        }
        
        for (ArticuloCarrito articulo : lista) {
            BigDecimal precioFinal = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();
            total = total.add(precioFinal.multiply(new BigDecimal(articulo.getCantidad())));        }
        
        return total;
    }   
    
    public void updatePromocionByDialog() {
        if (selectedPromocion == null) {
            return;
        }

        if (lista == null || lista.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay artículos en la promoción", null));
            return;
        }

        selectedPromocion.setUsuario(currentSession.getCurrentUser());
        selectedPromocion.setArticulosCarrito(lista);

        if (fechasPromocion == null || fechasPromocion.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No se seleccionaron fechas para la promoción", null));
            return;
        }

        // Extract the start and end dates
        Date fechaInicio = fechasPromocion.get(0);
        Date fechaFin = fechasPromocion.get(fechasPromocion.size() - 1);

        if (fechaInicio == null) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay fecha de inicio en la promoción", null));
            return;
        }

        if (fechaFin == null) {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay fecha de fin en la promoción", null));
            return;
        }

        selectedPromocion.setFechaInicio(fechaInicio);
        selectedPromocion.setFechaFin(fechaFin);
        selectedPromocion.setActiva(true);
        
        if (selectedPromocion.getUsuario() != null) {
            promoService.update(selectedPromocion);
            alertas.registrarAlerta("Promocion Actualizada", "Se actualizó la promoción: " + selectedPromocion.getNombre(), currentSession.getCurrentUser(), 0, "updatePromocionByDialog()", null, selectedPromocion.toString());
            for (ArticuloCarrito articulo : lista) {
                var articuloToUpdate = articulo.getArticulo();
                List<Promocion> promociones = new ArrayList<>();
                promociones.add(selectedPromocion);
                
                articuloToUpdate.setPromociones(promociones);
                articuloService.update(articuloToUpdate);
            }
            
            clearSelectedPromocion();

            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se Actualizó la promoción", null));

            PrimeFaces.current().executeScript("PF('EditPromocionDialog').hide();");
        }
    }
    
    public String createTotalDescuentoEIVAText(){
        if(selectedPromocion != null){
            return selectedPromocion.getTotalPromo(lista,newPromocion.getDescuento()).toString();
        }
        else{
            return "";
        }
    }
    
    public String updateTotalDescuentoEIVAText(){
        return newPromocion.getTotalPromo(lista, selectedPromocion.getDescuento()).toString();
    }
    
    public void descuentoChanged(){
        System.out.println("Descuento: " + newPromocion.getDescuento());
    }
    
    
}
