package Controllers;

import Models.Departamento;
import Services.AlertasService;
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
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;
import Utils.DiffUtils;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "DepartamentosController")
@ViewScoped
public class DepartamentoController implements Serializable {
    
    @Inject @Nonnull private DepartamentoService departamentoService;
    @Inject @Nonnull private SessionController currentSession;
    @Inject @Nonnull private AlertasService alertas;
    
    @Nullable
    private List<Departamento> departamentos;
    @Nullable
    private Departamento selectedDepartamento;
    @Nullable
    private Departamento newDepartamento;
    @Nullable
    private String departamentoFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    @PostConstruct
    public void init() {
        newDepartamento = new Departamento();
        selectedDepartamento = new Departamento();
        departamentosList();
        filterBy = new ArrayList<>();
    }

    public @Nonnull List<Departamento> departamentosList() {
        if (departamentos == null) {
            departamentos = departamentoService.listAll();
        }
        return departamentos;
    }
    
    public @Nonnull List<Departamento> departamentosListAll() {
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
                String antes = DiffUtils.snapshotEntity(selectedDepartamento);
                selectedDepartamento.setUsuario(currentSession.getCurrentUser());
                departamentoService.update(selectedDepartamento);
                alertas.registrarAlerta("Departamento Actualizado", "Se actualizó el departamento: " + selectedDepartamento.getNombre(), currentSession.getCurrentUser(), 0, "updateDepartamento()", antes, DiffUtils.snapshotEntity(selectedDepartamento));
                clearSelectedDepartamento();
                
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se actualizó el departamento!", null));
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
                alertas.registrarAlerta("Departamento Creado", "Se creó el departamento: " + newDepartamento.getNombre(), currentSession.getCurrentUser(), 0, "createDepartamento()", "", newDepartamento.toString());
                
                // Clear cache but reset newDepartamento for next entry
                departamentos = null;
                newDepartamento = new Departamento();
                
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creó el departamento", null));
                PrimeFaces.current().executeScript("PF('CrearDepartamentoDialog').hide();");

            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }
    
    public @Nullable Departamento createSimpleDepartamento(@Nonnull Departamento departamento){
        if(currentSession.isValid()){
            Departamento persistedDepartamento = departamentoService.createIfNotExist(departamento);
            clearSelectedDepartamento();
            return persistedDepartamento;
        }
        return null;
    }

    public void deleteDepartamento() {
        if (selectedDepartamento != null) {
            String antes = DiffUtils.snapshotEntity(selectedDepartamento);
            departamentoService.softDelete(selectedDepartamento);
            alertas.registrarAlerta("Departamento Eliminado", "Se eliminó el departamento: " + selectedDepartamento.getNombre(), currentSession.getCurrentUser(), 0, "deleteDepartamento()", antes, DiffUtils.snapshotEntity(selectedDepartamento));
            clearSelectedDepartamento();
        }
    }

    public void clearSelectedDepartamento() {
        departamentos = null;
        newDepartamento = null;
        selectedDepartamento = null;
    }

    public @Nonnull List<Departamento> getFilteredDepartamentos() {
        if (departamentoFilter != null && !departamentoFilter.trim().isEmpty()) {
            return departamentosList().stream()
                    .filter(departamento -> globalFilterFunction(departamento, departamentoFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return departamentosList();
        }
    }
    
    public @Nonnull List<Departamento> getFilteredDepartamentosDetallados() {
        if (departamentoFilter != null && !departamentoFilter.trim().isEmpty()) {
            return departamentosListAll().stream()
                    .filter(departamento -> globalFilterFunction(departamento, departamentoFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return departamentosListAll();
        }
    }

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Departamento departamento = (Departamento) value;
        return departamento.getNombre().toLowerCase().contains(filterText)
                || String.valueOf(departamento.getId()).contains(filterText)
                || departamento.getUsuario().getUsername().toLowerCase().contains(filterText)
                || (departamento.getContactoNombre() != null && departamento.getContactoNombre().toLowerCase().contains(filterText))
                || (departamento.getContactoTelefono() != null && departamento.getContactoTelefono().toLowerCase().contains(filterText))
                || (departamento.getContactoEmail() != null && departamento.getContactoEmail().toLowerCase().contains(filterText));
    }

    public @Nullable Departamento findDepartamentoById(@Nonnull Integer number) {
        
        return departamentoService.findById(number);
        
    }

}
