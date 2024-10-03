package Controllers;

import Models.ArticuloCarrito;
import Models.Articulos;
import Models.Promocion;
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
    @Inject private ArticulosService articuloService;

    private List<Promocion> promociones;
    private List<ArticuloCarrito> lista;
    private Promocion selectedPromocion;
    private Promocion newPromocion;
    private String promocionFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private String selectedPromocionString;
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
                selectedPromocion.setUsuario(currentSession.getCurrentUser());
                promoService.update(selectedPromocion);
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
            promoService.delete(selectedPromocion);
            clearSelectedPromocion();
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
            //Should save it in each item entity too...
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

    
    public void createTipoPromocionChanged() {
        String message = "Se selecciono: ";
        selectedPromocionString = newPromocion.getTipoPromocion();
        if (selectedPromocionString != null) {
                message += selectedPromocionString;
        }
        
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
        
        newPromocion.setTipoPromocion(selectedPromocionString);
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
    
    public BigDecimal totalLista(){
        
        BigDecimal total = BigDecimal.ZERO;
        
        if(lista == null){
            return BigDecimal.ZERO;
        }
        
        for (ArticuloCarrito articulo : lista) {
            BigDecimal precioFinal = articulo.getArticulo().getLastPrecio().getPrecioFinal();
            total = total.add(precioFinal.multiply(new BigDecimal(articulo.getCantidad())));        }
        
        return total;
    }

    
    
}
