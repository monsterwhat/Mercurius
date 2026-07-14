package Controllers;

import Models.Articulos.Articulos;
import Models.Departamento;
import Models.OrdenCompra;
import Models.OrdenCompraDetalle;
import Services.AlertasService;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.OrdenCompraService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;
import Utils.DiffUtils;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "OrdenCompraController")
@ViewScoped
public class OrdenCompraController implements Serializable {

    @Inject @Nonnull private OrdenCompraService ordenCompraService;
    @Inject @Nonnull private DepartamentoService departamentoService;
    @Inject @Nonnull private ArticulosService articulosService;
    @Inject @Nonnull private AlertasService alertas;
    @Inject @Nonnull private SessionController currentSession;

    @Nullable
    private List<OrdenCompra> ordenes;
    @Nullable
    private List<OrdenCompra> filteredOrdenes;
    @Nullable
    private OrdenCompra selectedOrden;
    @Nullable
    private OrdenCompra newOrden;
    @Nullable
    private List<OrdenCompraDetalle> detallesOrden;
    @Nullable
    private OrdenCompraDetalle newDetalle;
    @Nullable
    private String ordenFilter;
    @Nullable
    private Departamento proveedorFilter;
    @Nullable
    private String numeroOrdenFilter;
    @Nullable
    private String estadoFilter;
    @Nullable
    private String cancelarMotivo;
    @Nullable
    private String nuevoEstado;
    @Nonnull
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    @PostConstruct
    public void init() {
        newOrden = new OrdenCompra();
        selectedOrden = new OrdenCompra();
        newDetalle = new OrdenCompraDetalle();
        detallesOrden = new ArrayList<>();
        filterBy = new ArrayList<>();
        ordenesList();
    }

    @Nonnull
    public List<OrdenCompra> ordenesList() {
        if (ordenes == null) {
            ordenes = ordenCompraService.listAll();
        }
        return ordenes;
    }

    public long ordenCount() {
        return ordenCompraService.count();
    }

    public long ordenCountByEstado(@Nullable String estado) {
        if (estado == null || estado.isEmpty()) {
            return ordenCompraService.count();
        }
        List<OrdenCompra> lista = ordenCompraService.findByEstado(estado);
        return lista != null ? lista.size() : 0;
    }

    public long ordenPendientesCount() {
        List<OrdenCompra> pendientes = ordenCompraService.findPendientes();
        return pendientes != null ? pendientes.size() : 0;
    }

    public void openNewOrden() {
        newOrden = new OrdenCompra();
        newOrden.setEstado("BORRADOR");
        newOrden.setFechaOrden(new java.util.Date());
        detallesOrden = new ArrayList<>();
        newDetalle = new OrdenCompraDetalle();
    }

    public void saveOrden() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión inválida!", null));
            return;
        }

        if (newOrden == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "No hay orden para guardar!", null));
            return;
        }

        if (newOrden.getProveedor() == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Debe seleccionar un proveedor!", null));
            return;
        }

        if (detallesOrden == null || detallesOrden.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Debe agregar al menos un artículo!", null));
            return;
        }

        // Validate each detalle
        boolean hasInvalidDetalle = false;
        for (OrdenCompraDetalle d : detallesOrden) {
            if (d.getArticulo() == null || d.getCantidad() == null || d.getCantidad().compareTo(BigDecimal.ZERO) <= 0) {
                hasInvalidDetalle = true;
                break;
            }
        }
        if (hasInvalidDetalle) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Todos los artículos deben tener cantidad válida!", null));
            return;
        }

        try {
            newOrden.setUsuario(currentSession.getCurrentUser());
            String numeroOrden = ordenCompraService.generarNumeroOrden();
            newOrden.setNumeroOrden(numeroOrden);

            ordenCompraService.crearOrden(newOrden, detallesOrden);

            String antes = DiffUtils.snapshotEntity(newOrden);
            alertas.registrarAlerta("Orden de Compra Creada",
                "Se creó la orden de compra: " + numeroOrden,
                currentSession.getCurrentUser(), 0, "OrdenCompraController.saveOrden()",
                "", antes);

            clearCache();
            newOrden = new OrdenCompra();
            detallesOrden = new ArrayList<>();
            newDetalle = new OrdenCompraDetalle();

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Orden de compra creada exitosamente!", null));
            PrimeFaces.current().executeScript("PF('CrearOrdenDialog').hide();");

        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error al guardar: " + e.getMessage(), null));
        }
    }

    public void updateOrden() {
        if (!currentSession.isValid()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesión inválida!", null));
            return;
        }

        if (selectedOrden == null) {
            return;
        }

        if (selectedOrden.getProveedor() == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Debe seleccionar un proveedor!", null));
            return;
        }

        if (detallesOrden == null || detallesOrden.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Debe agregar al menos un artículo!", null));
            return;
        }

        try {
            String antes = DiffUtils.snapshotEntity(selectedOrden);

            // Update detalles
            selectedOrden.setDetalles(detallesOrden);
            for (OrdenCompraDetalle detalle : detallesOrden) {
                detalle.setOrdenCompra(selectedOrden);
                detalle.calcularSubtotal();
            }
            selectedOrden.setTotalEstimado(ordenCompraService.calcularTotal(detallesOrden));
            selectedOrden.setUsuario(currentSession.getCurrentUser());

            ordenCompraService.update(selectedOrden);

            alertas.registrarAlerta("Orden de Compra Actualizada",
                "Se actualizó la orden de compra: " + selectedOrden.getNumeroOrden(),
                currentSession.getCurrentUser(), 0, "OrdenCompraController.updateOrden()",
                antes, DiffUtils.snapshotEntity(selectedOrden));

            clearCache();
            selectedOrden = new OrdenCompra();
            detallesOrden = new ArrayList<>();

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Orden de compra actualizada!", null));
            PrimeFaces.current().executeScript("PF('EditarOrdenDialog').hide();");

        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error al actualizar: " + e.getMessage(), null));
        }
    }

    public void deleteOrden() {
        if (selectedOrden == null) {
            return;
        }

        String antes = DiffUtils.snapshotEntity(selectedOrden);
        ordenCompraService.softDelete(selectedOrden);

        alertas.registrarAlerta("Orden de Compra Eliminada",
            "Se eliminó la orden de compra: " + selectedOrden.getNumeroOrden(),
            currentSession.getCurrentUser(), 0, "OrdenCompraController.deleteOrden()",
            antes, DiffUtils.snapshotEntity(selectedOrden));

        clearCache();
        selectedOrden = new OrdenCompra();

        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Orden de compra eliminada!", null));
    }

    public void cambiarEstado() {
        if (selectedOrden == null || nuevoEstado == null) {
            return;
        }

        if (!ordenCompraService.esTransicionValida(selectedOrden.getEstado(), nuevoEstado)) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Transición de estado no válida: " + selectedOrden.getEstado() + " → " + nuevoEstado, null));
            return;
        }

        String antes = DiffUtils.snapshotEntity(selectedOrden);
        ordenCompraService.cambiarEstado(selectedOrden, nuevoEstado);

        alertas.registrarAlerta("Estado de Orden Cambiado",
            "Orden " + selectedOrden.getNumeroOrden() + ": " + selectedOrden.getEstado() + " → " + nuevoEstado,
            currentSession.getCurrentUser(), 0, "OrdenCompraController.cambiarEstado()",
            antes, DiffUtils.snapshotEntity(selectedOrden));

        clearCache();
        selectedOrden = null;
        nuevoEstado = null;

        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Estado actualizado correctamente!", null));
        PrimeFaces.current().executeScript("PF('CambiarEstadoDialog').hide();");
    }

    public void recibirOrden() {
        if (selectedOrden == null) {
            return;
        }

        String antes = DiffUtils.snapshotEntity(selectedOrden);
        ordenCompraService.recibirOrden(selectedOrden);

        alertas.registrarAlerta("Orden Recibida",
            "Se marcó como recibida la orden: " + selectedOrden.getNumeroOrden(),
            currentSession.getCurrentUser(), 0, "OrdenCompraController.recibirOrden()",
            antes, DiffUtils.snapshotEntity(selectedOrden));

        clearCache();
        selectedOrden = null;

        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Orden marcada como recibida!", null));
        PrimeFaces.current().executeScript("PF('DetallesOrdenDialog').hide();");
    }

    public void cancelarOrden() {
        if (selectedOrden == null) {
            return;
        }

        String antes = DiffUtils.snapshotEntity(selectedOrden);
        ordenCompraService.cancelarOrden(selectedOrden, cancelarMotivo);

        alertas.registrarAlerta("Orden Cancelada",
            "Se canceló la orden: " + selectedOrden.getNumeroOrden() +
                (cancelarMotivo != null ? " - Motivo: " + cancelarMotivo : ""),
            currentSession.getCurrentUser(), 0, "OrdenCompraController.cancelarOrden()",
            antes, DiffUtils.snapshotEntity(selectedOrden));

        clearCache();
        selectedOrden = null;
        cancelarMotivo = null;

        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_WARN, "Orden cancelada!", null));
        PrimeFaces.current().executeScript("PF('CancelarOrdenDialog').hide();");
    }

    public void addDetalle() {
        if (detallesOrden == null) {
            detallesOrden = new ArrayList<>();
        }
        OrdenCompraDetalle detalle = new OrdenCompraDetalle();
        detallesOrden.add(detalle);
        newDetalle = detalle;
    }

    public void removeDetalle(@Nonnull OrdenCompraDetalle detalle) {
        if (detallesOrden != null) {
            detallesOrden.remove(detalle);
            recalcularTotal();
        }
    }

    public void recalcularTotal() {
        if (detallesOrden != null && newOrden != null) {
            for (OrdenCompraDetalle d : detallesOrden) {
                d.calcularSubtotal();
            }
            newOrden.setTotalEstimado(ordenCompraService.calcularTotal(detallesOrden));
        }
    }

    public @Nonnull String getEstadoStyleClass(@Nullable String estado) {
        if (estado == null) return "is-light";
        return switch (estado) {
            case "BORRADOR" -> "is-light";
            case "ENVIADA" -> "is-info";
            case "CONFIRMADA" -> "is-warning";
            case "RECIBIDA" -> "is-success";
            case "FACTURADA" -> "is-primary";
            case "CANCELADA" -> "is-danger";
            default -> "is-light";
        };
    }

    public boolean puedeCambiarEstado(@Nullable String estado) {
        if (estado == null) return false;
        return switch (estado) {
            case "BORRADOR" -> true;
            case "ENVIADA" -> true;
            case "CONFIRMADA" -> true;
            case "RECIBIDA" -> true;
            default -> false;
        };
    }

    public boolean puedeEditar(@Nullable String estado) {
        return "BORRADOR".equals(estado);
    }

    public @Nullable List<Articulos> completeArticulo(@Nonnull String query) {
        try {
            return articulosService.findByNameContaining(query);
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error buscando artículos: " + e.getMessage(), null, 0, "OrdenCompraController.completeArticulo()", null, e.getMessage());
            return new ArrayList<>();
        }
    }

    public @Nonnull List<Departamento> getProveedoresList() {
        List<Departamento> departamentos = departamentoService.listAll();
        return departamentos != null ? departamentos : new ArrayList<>();
    }

    @Nonnull
    public List<OrdenCompra> getFilteredOrdenes() {
        List<OrdenCompra> lista = ordenesList();
        if (lista == null) return new ArrayList<>();

        String filtroTexto = (ordenFilter != null && !ordenFilter.trim().isEmpty()) ? ordenFilter.trim().toLowerCase() : null;

        return lista.stream()
            .filter(o -> filtroTexto == null || globalFilterFunction(o, filtroTexto, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
            .filter(o -> estadoFilter == null || estadoFilter.isEmpty() || o.getEstado().equals(estadoFilter))
            .filter(o -> proveedorFilter == null || o.getProveedor().equals(proveedorFilter))
            .filter(o -> numeroOrdenFilter == null || numeroOrdenFilter.trim().isEmpty() ||
                o.getNumeroOrden().toLowerCase().contains(numeroOrdenFilter.trim().toLowerCase()))
            .collect(Collectors.toList());
    }

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        OrdenCompra orden = (OrdenCompra) value;
        return (orden.getNumeroOrden() != null && orden.getNumeroOrden().toLowerCase().contains(filterText))
            || (orden.getProveedor() != null && orden.getProveedor().getNombre() != null
                && orden.getProveedor().getNombre().toLowerCase().contains(filterText))
            || (orden.getEstado() != null && orden.getEstado().toLowerCase().contains(filterText))
            || (orden.getNotas() != null && orden.getNotas().toLowerCase().contains(filterText))
            || (orden.getUsuario() != null && orden.getUsuario().getUsername() != null
                && orden.getUsuario().getUsername().toLowerCase().contains(filterText));
    }

    public void clearCache() {
        ordenes = null;
        filteredOrdenes = null;
    }

    public void clearSelectedOrden() {
        selectedOrden = null;
        detallesOrden = null;
    }

    public void prepararEdicion(@Nonnull OrdenCompra orden) {
        selectedOrden = orden;
        if (orden.getDetalles() != null) {
            detallesOrden = new ArrayList<>(orden.getDetalles());
        } else {
            detallesOrden = new ArrayList<>();
        }
    }

    public void prepararDetalles(@Nonnull OrdenCompra orden) {
        selectedOrden = orden;
        if (orden.getDetalles() != null) {
            detallesOrden = new ArrayList<>(orden.getDetalles());
        } else {
            detallesOrden = new ArrayList<>();
        }
    }

    public void setOrdenFilter(String estado) {
        this.estadoFilter = estado;
    }

    public String getOrdenFilter() {
        return this.estadoFilter;
    }

    public void abrirDialogoCancelar() {
        cancelarMotivo = null;
    }

    public void abrirDialogoCambiarEstado(@Nonnull OrdenCompra orden) {
        selectedOrden = orden;
        nuevoEstado = null;
    }

    public void seleccionarSiguienteEstado() {
        if (selectedOrden == null) return;
        String estadoActual = selectedOrden.getEstado();
        nuevoEstado = switch (estadoActual) {
            case "BORRADOR" -> "ENVIADA";
            case "ENVIADA" -> "CONFIRMADA";
            case "CONFIRMADA" -> "RECIBIDA";
            case "RECIBIDA" -> "FACTURADA";
            default -> null;
        };
    }

    public @Nullable OrdenCompra getSelectedOrden() {
        return selectedOrden;
    }
}
