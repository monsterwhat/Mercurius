package Controllers;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Articulos;
import Models.Articulos.Promocion;
import Models.Enums.Tipo_Codigo_Descuento;
import Services.AlertasService;
import Services.ArticuloCarritoService;
import Services.ArticulosService;
import Services.PromocionesService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import Utils.DiffUtils;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

/**
 *
 * @author Al
 */
@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "PromocionesController")
@ViewScoped
public class PromocionesController implements Serializable {

    @Inject @Nonnull
    private SessionController currentSession;
    @Inject @Nonnull
    private PromocionesService promoService;
    @Inject @Nonnull
    private AlertasService alertas;
    @Inject @Nonnull
    private ArticulosService articuloService;
    @Inject @Nonnull
    private ArticuloCarritoService articuloCarritoService;

    @Nullable private List<Promocion> promociones;
    @Nullable private List<ArticuloCarrito> lista;
    @Nullable private Promocion selectedPromocion;
    @Nullable private Promocion newPromocion;
    @Nullable private String promocionFilter;
    @Nonnull private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    @Nullable private String selectedPromocionString;
    @Nonnull private String totalDescuentoConIVA = "0";
    @Nullable private List<Date> fechasPromocion;
    @Nullable private Articulos selectedArticulo;
    @Nullable private ArticuloCarrito selectedArticuloCarrito;
    @Nullable private BigDecimal cantidad;

    @PostConstruct
    public void init() {
        newPromocion = new Promocion();
        selectedPromocion = new Promocion();
        filterBy = new ArrayList<>();
    }

    @Nonnull
    public List<Promocion> promocionesList() {
        if (promociones == null) {
            promociones = promoService.listAll();
        }
        return promociones;
    }

    @Nonnull
    public List<Promocion> promocionesListAll() {
        return promoService.listAll();
    }

    public long promocionCount() {
        return promoService.count();
    }

    public long promocionesActivosCount() {
        return promoService.countActivos();
    }

    public long promocionesInactivosCount() {
        return promoService.countInactivos();
    }

    public void openNewPromocion() {
        newPromocion = new Promocion();
        PrimeFaces.current().executeScript("PF('CrearPromocionDialog').show();");
    }

    public void updatePromocion() {
        if (currentSession.isValid()) {
            if (selectedPromocion != null) {
                String antes = DiffUtils.snapshotEntity(selectedPromocion);
                selectedPromocion.setUsuario(currentSession.getCurrentUser());
                promoService.update(selectedPromocion);
                alertas.registrarAlerta("Promocion Actualizada", "Se actualizo la promocion: " + selectedPromocion.getNombre(), currentSession.getCurrentUser(), 0, "updatePromocion()", antes, DiffUtils.snapshotEntity(selectedPromocion));
                clearSelectedPromocion();

                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Se actualizo la promocion!", null));
                PrimeFaces.current().executeScript("PF('EditarPromocionDialog').hide();");

            }
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }

    public void createPromocion() {
        if (currentSession.isValid()) {
            if (newPromocion != null) {
                newPromocion.setActiva(true);
                newPromocion.setUsuario(currentSession.getCurrentUser());
                promoService.create(newPromocion);
                alertas.registrarAlerta("Promocion Creada", "Se creo la promocion: " + newPromocion.getNombre(), currentSession.getCurrentUser(), 0, "createPromocion()", null, newPromocion.toString());
                clearSelectedPromocion();
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creo la promocion", null));
                PrimeFaces.current().executeScript("PF('CrearPromocionDialog').hide();");

            }
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }

    public void deletePromocion() {
        if (selectedPromocion != null) {
            String antes = DiffUtils.snapshotEntity(selectedPromocion);
            promoService.delete(selectedPromocion);
            alertas.registrarAlerta("Promocion Eliminada", "Se elimino la promocion: " + selectedPromocion.getNombre(), currentSession.getCurrentUser(), 0, "deletePromocion()", antes, DiffUtils.snapshotEntity(selectedPromocion));
            clearSelectedPromocion();
        }
    }

    public void removeSelectedItemFromPromociones() {
        if (selectedArticulo != null) {
            var articulo = selectedArticuloCarrito;
            ArticuloCarrito itemToRemove = null;

            // Iterate through the list to find the matching articulo
            for (ArticuloCarrito articuloCarrito : lista) {
                if (articuloCarrito.getArticulo().equals(articulo.getArticulo())) {
                    itemToRemove = articuloCarrito;
                    break; // Exit loop once the item is found
                }
            }

            // Remove the item from the list if found
            if (itemToRemove != null) {
                alertas.registrarAlerta("Info", "Item removed: " + itemToRemove.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "PromocionesController.removeItem()", null, null);
                lista.remove(itemToRemove);
            } else {
                alertas.registrarAlerta("Info", "No item removed?", currentSession.getCurrentUser(), 0, "PromocionesController.removeItem()", null, null);
            }
        }else{
            alertas.registrarAlerta("Info", "Null selection", currentSession.getCurrentUser(), 0, "PromocionesController.removeItem()", null, null);
        }
    }

    public void clearSelectedPromocion() {
        promociones = null;
        newPromocion = null;
        selectedPromocion = null;
        lista = null;
    }

    @Nonnull
    public List<Promocion> getFilteredPromocions() {
        if (promocionFilter != null && !promocionFilter.isEmpty()) {
            return promocionesList().stream()
                    .filter(promocion -> globalFilterFunction(promocion, promocionFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return promocionesList();
        }
    }

    @Nonnull
    public List<Promocion> getFilteredPromocionsDetallados() {
        if (promocionFilter != null && !promocionFilter.isEmpty()) {
            return promocionesListAll().stream()
                    .filter(promocion -> globalFilterFunction(promocion, promocionFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return promocionesListAll();
        }
    }

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Promocion promocion = (Promocion) value;
        return promocion.getNombre().toLowerCase().contains(filterText)
                || String.valueOf(promocion.getId()).contains(filterText)
                || promocion.getUsuario().getUsername().toLowerCase().contains(filterText);
    }

    @Nullable
    public Promocion findPromocionById(@Nonnull Integer number) {

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

        if (fechasPromocion == null || fechasPromocion.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "No se seleccionaron fechas para la promoción", null));
            return;
        }

        Date fechaInicio = fechasPromocion.get(0);
        Date fechaFin = fechasPromocion.get(fechasPromocion.size() - 1);

        if (fechaInicio == null || fechaFin == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Fechas de promoción incompletas", null));
            return;
        }

        // Configurar campos principales
        newPromocion.setUsuario(currentSession.getCurrentUser());
        newPromocion.setFechaInicio(fechaInicio);
        newPromocion.setFechaFin(fechaFin);
        newPromocion.setActiva(true);

        // Persistir artículos nuevos si no tienen código asignado
        for (ArticuloCarrito articulo : lista) {
            if (articulo.getCodigo() == null) {
                articuloCarritoService.create(articulo);
            }
            if (articulo.getPromociones() == null) {
                articulo.setPromociones(new ArrayList<>());
            }
            articulo.getPromociones().add(newPromocion);
        }

        // Establecer relación inversa
        newPromocion.setArticulosCarrito(lista);

        if (newPromocion.getUsuario() != null) {
            promoService.create(newPromocion);

            alertas.registrarAlerta(
                    "Promoción Creada",
                    "Se creó la promoción: " + newPromocion.getNombre(),
                    currentSession.getCurrentUser(),
                    0,
                    "createPromocionByDialog()",
                    null,
                    newPromocion.toString()
            );

            clearSelectedPromocion();

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creó la promoción", null));

            PrimeFaces.current().executeScript("PF('CrearPromocionDialog').hide();");
        }
    }

    public void editPromocion() {
        PrimeFaces.current().executeScript("PF('EditPromocionDialog').show();");
        lista = selectedPromocion.getArticulosCarrito();
        fechasPromocion = selectedPromocion.getFechas();
    }

    public void articuloSelectedDialog() {
        if (selectedArticulo == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún Articulo."));
            return;
        }

        if (cantidad == null || cantidad.compareTo(BigDecimal.ZERO) <= 0) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Debe ingresar una cantidad mayor a cero."));
            return;
        }

        if (lista == null) {
            lista = new ArrayList<>();
        }

        Long selectedCodigo = selectedArticulo.getCodigo();
        boolean exists = false;

        for (ArticuloCarrito articulo : lista) {
            if (Objects.equals(articulo.getArticulo().getCodigo(), selectedCodigo)) {
                articulo.setCantidad(articulo.getCantidad().add(cantidad));
                exists = true;
                break;
            }
        }

        if (!exists) {
            ArticuloCarrito nuevo = new ArticuloCarrito();
            nuevo.setArticulo(selectedArticulo);
            nuevo.setCantidad(cantidad);
            lista.add(nuevo);
        }

        selectedArticulo = null;
        cantidad = BigDecimal.ZERO;

        PrimeFaces.current().executeScript("PF('ArticuloRevisionDialog').hide();");
    }

    @Nonnull
    public BigDecimal totalListaConIVA() {

        BigDecimal total = BigDecimal.ZERO;

        if (lista == null) {
            return BigDecimal.ZERO;
        }

        for (ArticuloCarrito articulo : lista) {
            BigDecimal precioFinal = articulo.getArticulo().getLastPrecio().getPrecioFinal();
            total = total.add(precioFinal.multiply(articulo.getCantidad()));
        }

        return total;
    }

    @Nonnull
    public BigDecimal totalListaConUtilidad() {

        BigDecimal total = BigDecimal.ZERO;

        if (lista == null) {
            return BigDecimal.ZERO;
        }

        for (ArticuloCarrito articulo : lista) {
            BigDecimal precioFinal = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();
            total = total.add(precioFinal.multiply(articulo.getCantidad()));
        }

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

        // Fechas
        Date fechaInicio = fechasPromocion.get(0);
        Date fechaFin = fechasPromocion.get(fechasPromocion.size() - 1);

        if (fechaInicio == null || fechaFin == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Fechas de promoción incompletas", null));
            return;
        }

        selectedPromocion.setFechaInicio(fechaInicio);
        selectedPromocion.setFechaFin(fechaFin);
        selectedPromocion.setActiva(true);

        if (selectedPromocion.getUsuario() != null) {
            promoService.update(selectedPromocion);

            alertas.registrarAlerta("Promoción Actualizada",
                    "Se actualizó la promoción: " + selectedPromocion.getNombre(),
                    currentSession.getCurrentUser(),
                    0,
                    "updatePromocionByDialog()",
                    null,
                    selectedPromocion.toString());

            for (ArticuloCarrito articulo : lista) {
                if (articulo.getPromociones() == null) {
                    articulo.setPromociones(new ArrayList<>());
                }
                if (!articulo.getPromociones().contains(selectedPromocion)) {
                    articulo.getPromociones().add(selectedPromocion);
                }
                articuloService.update(articulo.getArticulo());
            }

            promociones.add(selectedPromocion);
            clearSelectedPromocion();

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se actualizó la promoción", null));

            PrimeFaces.current().executeScript("PF('EditPromocionDialog').hide()");
        }
    }

    @Nonnull
    public String createTotalDescuentoEIVAText() {
        if (selectedPromocion != null && newPromocion != null) {
            return selectedPromocion.getTotalPromo(lista, newPromocion.getDescuento()).toString();
        } else {
            return "";
        }
    }

    @Nonnull
    public String updateTotalDescuentoEIVAText() {
        return newPromocion.getTotalPromo(lista, selectedPromocion.getDescuento()).toString();
    }

    public void descuentoChanged() {
        alertas.registrarAlerta("Info", "Descuento: " + newPromocion.getDescuento(), currentSession.getCurrentUser(), 0, "PromocionesController.descuentoChanged()", null, null);
    }

    @Nonnull
    public Tipo_Codigo_Descuento[] getTiposDescuento() {
        return Tipo_Codigo_Descuento.values();
    }

}
