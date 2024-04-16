package Controllers;

import Models.Departamento;
import Services.DepartamentoService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.context.FacesContext;
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

@Data
@Named(value = "DepartamentosController")
@SessionScoped
public class DepartamentoController implements Serializable {
    @Inject private DepartamentoService departamentoService;
    @Inject private ViewController viewManager;

    private List<Departamento> departamentos;
    private Departamento selectedDepartamento;
    private Departamento newDepartamento;
    private String departamentoFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    @PostConstruct
    public void init() {
        newDepartamento = new Departamento();
        selectedDepartamento = new Departamento();
        departamentosList();
        filterBy = new ArrayList<>();
    }

    public List<Departamento> departamentosList() {
        if (departamentos == null) {
            departamentos = departamentoService.listAll();
        }
        return departamentos;
    }

    public long departamentoCount() {
        return departamentoService.count();
    }

    public void openNewDepartamento() {
        newDepartamento = new Departamento();
    }

    public void updateDepartamento() {
        departamentoService.update(selectedDepartamento);
        clearSelectedDepartamento();
    }

    public void createDepartamento() {
        departamentoService.create(newDepartamento);
        clearSelectedDepartamento();
    }

    public void deleteDepartamento() {
        if (selectedDepartamento != null) {
            departamentoService.delete(selectedDepartamento);
            clearSelectedDepartamento();
        }
    }

    public void clearSelectedDepartamento() {
        departamentos = null;
        newDepartamento = null;
        selectedDepartamento = null;
        viewManager.selectViewDepartamentos();
    }

    public List<Departamento> getFilteredDepartamentos() {
        if (departamentoFilter != null && !departamentoFilter.isEmpty()) {
            return departamentosList().stream()
                    .filter(departamento -> globalFilterFunction(departamento, departamentoFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return departamentosList();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Departamento departamento = (Departamento) value;
        return departamento.getNombre().toLowerCase().contains(filterText)
                || String.valueOf(departamento.getId()).contains(filterText);
    }

}
