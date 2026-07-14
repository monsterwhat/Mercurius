package Controllers.Clientes;

import Controllers.SessionController;
import Models.Clients;
import Services.ClientService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "reportesClientesController")
@ViewScoped
public class ReportesClientesController implements Serializable {

    @Inject @Nonnull
    private ClientService clientService;

    @Inject @Nonnull
    private SessionController currentSession;

    @Nullable
    private List<Clients> clientes;
    @Nullable
    private Date fechaInicio;
    @Nullable
    private Date fechaFin;
    @Nullable
    private String filtroBusqueda;

    public ReportesClientesController() {
    }

    @PostConstruct
    public void init() {
        cargarClientes();
    }

    public void cargarClientes() {
        try {
            clientes = clientService.listAll();
        } catch (RuntimeException e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new jakarta.faces.application.FacesMessage(jakarta.faces.application.FacesMessage.SEVERITY_ERROR, 
                    "Error", "No se pudieron cargar los clientes: " + e.getMessage()));
        }
    }

    public void generarReporte() {
        cargarClientes();
        PrimeFaces.current().ajax().update("clientesReportes");
    }

    public void limpiarFiltros() {
        fechaInicio = null;
        fechaFin = null;
        filtroBusqueda = null;
        cargarClientes();
        PrimeFaces.current().ajax().update("filtros");
    }

    @Nonnull
    public List<Clients> getClientesFiltrados() {
        if (clientes == null) {
            return new ArrayList<>();
        }

        List<Clients> resultado = clientes;

        if (filtroBusqueda != null && !filtroBusqueda.isEmpty()) {
            String filtro = filtroBusqueda.toLowerCase();
            resultado = resultado.stream()
                .filter(c -> (c.getName() != null && c.getName().toLowerCase().contains(filtro))
                    || (c.getEmail() != null && c.getEmail().toLowerCase().contains(filtro))
                    || (c.getIdNumber() != null && c.getIdNumber().toLowerCase().contains(filtro)))
                .toList();
        }

        return resultado;
    }
}
