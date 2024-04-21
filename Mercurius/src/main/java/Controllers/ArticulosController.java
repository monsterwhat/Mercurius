package Controllers;

import Models.Articulos;
import Models.Departamento;
import Models.Familia;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.FamiliaService;
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
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "ArticulosController")
@ViewScoped
public class ArticulosController implements Serializable {
    
    @Inject private ArticulosService articulosService;
    @Inject private ViewController viewManager;
    @Inject private DepartamentoService departamentoService;
    @Inject private FamiliaService familiaService;
    
    private List<Articulos> articulosList;
    private Articulos selectedArticulo;
    private Articulos newArticulo;
    private String articulosFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private List<Departamento> departamentoOptions;
    private List<Familia> familiaOptions;
    private int DepartamentoID,FamiliaID = 0;

    @PostConstruct
    public void init() {
        newArticulo = new Articulos();
        selectedArticulo = new Articulos();
        articulosList();
        filterBy = new ArrayList<>();
        updateDepartamentoAndFamiliaOptions(); 
    }

    public List<Articulos> articulosList() {
        if (articulosList == null) {
            articulosList = articulosService.ListAllEnabled();
        }
        return articulosList;
    }

    public long articulosCount() {
        return articulosService.count();
    }

    public void openNewArticulo() {
        newArticulo = new Articulos();
        updateDepartamentoAndFamiliaOptions(); 
    }

    public void updateArticulo() {
        if(DepartamentoID != 0 || FamiliaID != 0){
            selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
            if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null){
                articulosService.updateAndDisable(selectedArticulo);
                clearSelectedArticulo();
            }
        }

    }

    public void createArticulo() {
        if(DepartamentoID != 0 || FamiliaID != 0){
            newArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            newArticulo.setFamilia(familiaService.findById(FamiliaID));
            if(newArticulo.getDepartamento() != null && newArticulo.getFamilia() != null){
                newArticulo.setStatus(true);
                articulosService.create(newArticulo);
                clearSelectedArticulo();
            }
        }
    }

    public void deleteArticulo() {
        if (selectedArticulo != null) {
            articulosService.softDelete(selectedArticulo);
            clearSelectedArticulo();
        }
    }

    public void clearSelectedArticulo() {
        articulosList = null;
        newArticulo = null;
        selectedArticulo = null;
        viewManager.selectViewArticulos();
    }

    public List<Articulos> getFilteredArticulos() {
        if (articulosFilter != null && !articulosFilter.isEmpty()) {
            return articulosList().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosList();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Articulos articulo = (Articulos) value;
        return String.valueOf(articulo.getCodigo()).contains(filterText)
                || articulo.getNombre().toLowerCase().contains(filterText)
                || String.valueOf(articulo.getCodigoBarra()).contains(filterText);
    }
    
    public void calcularPrecioConIVA() {
        if (newArticulo != null) {
            if (newArticulo.getPrecioFinal() != 0) {
                double impuesto = newArticulo.getCodigoCabys().getImpuesto();
                double precioSinIVA = newArticulo.getPrecioFinal();
                double IVA = precioSinIVA * (impuesto * 0.01);
                double precioConIVA = precioSinIVA+IVA;
                
                precioConIVA = Math.ceil(precioConIVA);
                
                newArticulo.setPrecioCostoConIVA(precioConIVA);
            }
        }
    }
    
    public void calcularPrecioConUtilidad(){
        try {
        if (newArticulo != null) {
            double porcentajeUtilidad = newArticulo.getPorcentajeUtilidad();
            double precioCosto = newArticulo.getPrecioCostoSinIVA();
            if(precioCosto >= 0 && porcentajeUtilidad >=0 ){
                double Utilidad = precioCosto*(porcentajeUtilidad*0.01);
                double precioConUtilidad = precioCosto+Utilidad;
                
                precioConUtilidad = Math.ceil(precioConUtilidad);
                
                newArticulo.setPrecioFinal(precioConUtilidad);
                calcularPrecioConIVA();
            }else{
                FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error en Validacion", "El precio costo o porcentaje de utilidad no pueden ser negativos"));
            }
        }
        } catch (Exception e) {
            System.out.println("Error:" + e.getLocalizedMessage());
        }
    }
    
    public void calcularPrecioConUtilidadEdit(){
        try {
        if (selectedArticulo != null) {
            double porcentajeUtilidad = selectedArticulo.getPorcentajeUtilidad();
            double precioCosto = selectedArticulo.getPrecioCostoSinIVA();
            if(precioCosto >= 0 && porcentajeUtilidad >=0 ){
                double Utilidad = precioCosto*(porcentajeUtilidad*0.01);
                double precioConUtilidad = precioCosto+Utilidad;
                
                precioConUtilidad = Math.ceil(precioConUtilidad);
                
                selectedArticulo.setPrecioFinal(precioConUtilidad);
                calcularPrecioConIVAEdit();
            }else{
                FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error en Validacion", "El precio costo o porcentaje de utilidad no pueden ser negativos"));
            }
        }
        } catch (Exception e) {
            System.out.println("Error:" + e.getLocalizedMessage());
        }
        
    }
    
    public void calcularPrecioConIVAEdit() {
        if (selectedArticulo != null) {
            if (selectedArticulo.getPrecioFinal() != 0) {
                double impuesto = selectedArticulo.getCodigoCabys().getImpuesto();
                double precioSinIVA = selectedArticulo.getPrecioFinal();
                double IVA = precioSinIVA * (impuesto * 0.01);
                double precioConIVA = precioSinIVA+IVA;
                
                precioConIVA = Math.ceil(precioConIVA);
                
                selectedArticulo.setPrecioCostoConIVA(precioConIVA);
            }
        }
    }
    
    private void updateDepartamentoAndFamiliaOptions() {
        departamentoOptions = departamentoService.listAll();
        familiaOptions = familiaService.listAll();
    }
    
}
