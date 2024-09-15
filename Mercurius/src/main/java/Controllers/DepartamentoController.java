package Controllers;

import Models.Departamento;
import Services.DepartamentoService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "DepartamentosController")
@ViewScoped
public class DepartamentoController implements Serializable {
    @Inject private DepartamentoService departamentoService;
    @Inject private SessionController currentSession;

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
    
    public List<Departamento> departamentosListAll() {
        return departamentoService.listAll();
    }

    public long departamentoCount() {
        return departamentoService.count();
    }
    
    public long departamentosActivosCount(){
        return departamentoService.countActivos();
    }
    
    public long departamentosInactivosCount(){
        return departamentoService.countInactivos();
    }

    public void openNewDepartamento() {
        newDepartamento = new Departamento();
    }

    public void updateDepartamento() {
        if(currentSession.isValid()){
            if(selectedDepartamento !=null ){
                selectedDepartamento.setUsuario(currentSession.getCurrentUser());
                departamentoService.update(selectedDepartamento);
                clearSelectedDepartamento();
                
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se actualizo el departamento!", null));
                PrimeFaces.current().executeScript("PF('EditarDepartamentoDialog').hide();");

            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }

    public void createDepartamento() {
        if(currentSession.isValid()){
            if(newDepartamento != null){
                newDepartamento.setStatus(true);
                newDepartamento.setUsuario(currentSession.getCurrentUser());
                departamentoService.create(newDepartamento);
                clearSelectedDepartamento(); 
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creo el departamento", null));
                PrimeFaces.current().executeScript("PF('CrearDepartamentoDialog').hide();");

            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }
    
    public Departamento createSimpleDepartamento(Departamento departamento){
        if(currentSession.isValid()){
            Departamento persistedDepartamento = departamentoService.createIfNotExist(departamento);
            clearSelectedDepartamento();
            return persistedDepartamento;
        }
        return null;
    }

    public void deleteDepartamento() {
        if (selectedDepartamento != null) {
            departamentoService.softDelete(selectedDepartamento);
            clearSelectedDepartamento();
        }
    }

    public void clearSelectedDepartamento() {
        departamentos = null;
        newDepartamento = null;
        selectedDepartamento = null;
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
    
    public List<Departamento> getFilteredDepartamentosDetallados() {
        if (departamentoFilter != null && !departamentoFilter.isEmpty()) {
            return departamentosListAll().stream()
                    .filter(departamento -> globalFilterFunction(departamento, departamentoFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return departamentosListAll();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Departamento departamento = (Departamento) value;
        return departamento.getNombre().toLowerCase().contains(filterText)
                || String.valueOf(departamento.getId()).contains(filterText)
                || departamento.getUsuario().getUsername().toLowerCase().contains(filterText);
    }

    public Departamento findDepartamentoById(Integer number) {
        
        return departamentoService.findById(number);
        
    }

}
