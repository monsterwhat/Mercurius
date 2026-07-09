package Controllers;

import Models.Cabys;
import Services.AlertasService;
import Services.CabysService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "CabysController")
@ViewScoped
public class CabysController implements Serializable {
    
    @Inject @Nonnull private CabysService cabysService;
    @Inject @Nonnull private SessionController currentSession;
    @Inject @Nonnull private AlertasService alertas;

    @Nullable
    private List<Cabys> catalogo;
    @Nullable
    private Cabys selectedCabys;
    @Nonnull
    private Cabys newCabys;
    @Nullable
    private String cabysFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    @Nonnull
    private String selectedOption = "none";
    @Nonnull
    private String[] selectedOptions;
    private boolean globalFilterOnly;
    private boolean cabysStatus;
    
    // Column visibility
    private boolean showCodigo = true;
    private boolean showCategoria = true;
    private boolean showDescripcion = true;
    private boolean showImpuesto = true;

        
    @PostConstruct
    public void init() {
        newCabys = new Cabys();
        selectedCabys = new Cabys();
        cabysList();
        filterBy = new ArrayList<>();
        selectedOptions = new String[]{"codigo", "categoria", "descripcion", "impuesto"};
    }
    
    @Nonnull
    public List<Cabys> cabysList() {
        if (catalogo == null) {
            catalogo = cabysService.listAll();
            if(catalogo == null || catalogo.isEmpty()){
                cabysStatus=false;
            }
        }
        return catalogo;
    }
    
    @Nonnull
    public List<Cabys> cabysListApi(){
        catalogo = cabysService.listAllAPI();
        saveAPItoDB();
        cabysStatus = true;
        return catalogo;
    }
    
    public long cabysCount() {
        return cabysService.count();
    }

    public void openNewCabys() {
        newCabys = new Cabys();
    }
    
    public void saveAPItoDB(){
        cabysService.saveAllDB(catalogo);
    }

    public void updateCabys() {
        var oldCabys = selectedCabys;
        cabysService.update(selectedCabys);
        alertas.registrarAlerta("CABYS actualizado", "Se ha actualizado el CABYS: " + selectedCabys.getCodigo(), currentSession.getCurrentUser(), 0, "CabysController.updateCabys", oldCabys.toString(), selectedCabys.toString());
        clearSelectedCabys();
    }

    public void createCabys() {
        cabysService.create(newCabys);
        //Over 300000 codes exist...
        //alertas.registrarAlerta("CABYS creado", "Se ha creado el CABYS: " + newCabys.getCodigo(), currentSession.getCurrentUser(), 0, "CabysController.createCabys", null, newCabys.toString());
        clearSelectedCabys();
    }

    public void deleteCabys() {
        if (selectedCabys != null) {
            if (!selectedCabys.getCodigo().isBlank()) {
                cabysService.delete(selectedCabys);
                alertas.registrarAlerta("CABYS eliminado", "Se ha eliminado el CABYS: " + selectedCabys.getCodigo(), currentSession.getCurrentUser(), 0, "CabysController.deleteCabys", selectedCabys.toString(), null);
                clearSelectedCabys();
            }
        }
    }

    public void clearSelectedCabys() {
        catalogo = null;
        newCabys = null;
        selectedCabys = null;
    }    
        
    @Nonnull
    public List<Cabys> getFilteredCabys() {
        if(catalogo == null){
            catalogo = cabysService.listAll();
        }
        if (cabysFilter != null && !cabysFilter.isEmpty()) {
            return cabysList().stream()
                    .filter(profile -> globalFilterFunction(profile, cabysFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return cabysList();
        }
    }
       
    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Cabys catalogo = (Cabys) value;
        return catalogo.getCodigo().toLowerCase().contains(filterText) ||
               catalogo.getDescripcion().toLowerCase().contains(filterText) ||
               catalogo.getCategorias().contains(filterText) ||
               catalogo.getEstado().toLowerCase().contains(filterText) ||
               String.valueOf(catalogo.getImpuesto()).contains(filterText);   
    }
    
    public void addMessage(@Nonnull FacesMessage.Severity severity, @Nonnull String summary, @Nullable String detail) {
        FacesContext.getCurrentInstance().
                addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public void showInfo(@Nonnull String message, @Nullable String content) {
        addMessage(FacesMessage.SEVERITY_INFO, message, content);
    }

    public void showWarn(@Nonnull String message, @Nullable String content) {
        addMessage(FacesMessage.SEVERITY_WARN, message, content);
    }

    public void showError(@Nonnull String message, @Nullable String content) {
        addMessage(FacesMessage.SEVERITY_ERROR, message, content);
    }

    public void showSticky(@Nonnull String message, @Nullable String content) {
        FacesContext.getCurrentInstance().addMessage("sticky-key", new FacesMessage(FacesMessage.SEVERITY_INFO, message, content));
    }
    
    public void selectCabys() {
        if (selectedCabys != null) {
            // This method should be called from the ArticulosController context
            // where the newArticulo is available
            selectedCabys = null;
        } else {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún CABYS.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
    
    
    public void selectCabysEditAndAssign() {
        if (selectedCabys != null) {
            // Find and call the ArticulosController bean directly
            FacesContext context = FacesContext.getCurrentInstance();
            ArticulosController articulosController = context.getApplication().evaluateExpressionGet(context, "#{ArticulosController}", ArticulosController.class);
            if (articulosController != null) {
                articulosController.assignCabysToSelectedArticulo();
            }
        } else {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún CABYS.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
    
    public void selectCabysCreateAndAssign(){
        if (selectedCabys != null) {
            // Find and call the ArticulosController bean directly
            FacesContext context = FacesContext.getCurrentInstance();
            ArticulosController articulosController = context.getApplication().evaluateExpressionGet(context, "#{ArticulosController}", ArticulosController.class);
            if (articulosController != null) {
                articulosController.assignCabysToNewArticulo();
            }
        } else {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún CABYS.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
    
    public void selectedOptionsChanged() {
        if (selectedOptions != null) {
            showCodigo = Arrays.toString(selectedOptions).contains("codigo");
            showCategoria = Arrays.toString(selectedOptions).contains("categoria");
            showDescripcion = Arrays.toString(selectedOptions).contains("descripcion");
            showImpuesto = Arrays.toString(selectedOptions).contains("impuesto");
        }
    }
    
    // Getters for column visibility
    public boolean isShowCodigo() { return showCodigo; }
    public boolean isShowCategoria() { return showCategoria; }
    public boolean isShowDescripcion() { return showDescripcion; }
    public boolean isShowImpuesto() { return showImpuesto; }
    
    public boolean isSelected(String selection){
        var state = Arrays.toString(selectedOptions).contains(selection);
        return state;
    }

    @Nullable
    public Cabys getSelectedCabysForAssignment() {
        Cabys cabys = selectedCabys;
        selectedCabys = null; // Clear after getting
        return cabys;
    }

    
}
