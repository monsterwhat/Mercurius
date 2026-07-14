package Controllers;

import Models.Registros.Alertas;
import Models.Users;
import Services.AlertasService;
import Services.UserService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "logActividadController")
@ViewScoped
public class LogActividadController implements Serializable {

    @Inject @Nonnull private AlertasService alertasService;
    @Inject @Nonnull private UserService userService;

    @Nullable
    private List<Alertas> registros;

    // Filters
    @Nullable
    private Date fechaDesde;
    @Nullable
    private Date fechaHasta;
    @Nullable
    private Users selectedUser;
    @Nullable
    private String selectedTipo;
    @Nullable
    private String sourceFilter;

    @Nullable
    private List<Users> users;
    @Nonnull
    private List<String> tiposDisponibles;
    @Nonnull
    private List<String> sourcesDisponibles;

    @Nullable
    private Alertas selectedRegistro;

    @PostConstruct
    public void init() {
        users = userService.listAll();
        tiposDisponibles = alertasService.findDistinctTipos();
        sourcesDisponibles = alertasService.findDistinctSources();
        buscar();
    }

    public void buscar() {
        registros = alertasService.findFiltered(fechaDesde, fechaHasta, selectedUser, selectedTipo, sourceFilter);
    }

    public void limpiarFiltros() {
        fechaDesde = null;
        fechaHasta = null;
        selectedUser = null;
        selectedTipo = null;
        sourceFilter = null;
        buscar();
    }

    public void toggleVista(@Nonnull Alertas registro) {
        alertasService.toggleVista(registro);
    }

    public long getTotalRegistros() {
        return registros != null ? registros.size() : 0;
    }

    public void showDetails(@Nonnull Alertas registro) {
        selectedRegistro = registro;
    }
}
