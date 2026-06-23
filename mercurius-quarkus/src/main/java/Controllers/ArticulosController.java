package Controllers;

import Controllers.Settings.SettingsDirController;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.Departamento;
import Models.Familia; 
import Services.ArticuloPrecioService;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.InventarioService;
import Services.PrinterService;
import Services.AlertasService;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Meta;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.component.datatable.DataTable;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "ArticulosController")
@ViewScoped
public class ArticulosController implements Serializable {
    
    @Inject private ArticulosService articulosService;
    @Inject private ArticuloPrecioService precioService;
    @Inject private DepartamentoService departamentoService;
    @Inject private FamiliaService familiaService;
    @Inject private InventarioService inventarioService;
    @Inject private SessionController currentSession;
    @Inject private CabysController cabysController;
    @Inject private SettingsDirController directoryConfig;
    @Inject private PrinterService printer;
    @Inject private AlertasService alertasService;
    
    private List<Articulos> articulosActivos;
    private List<Articulos> articulos;
    private List<Articulos> sinProcesar;
    private List<Articulos> activosYProcesados;
    private List<Articulos> inactivos;
    private Articulos selectedArticulo;
    private Articulos newArticulo;
    private String articulosFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private List<Departamento> departamentoOptions;
    private List<Familia> familiaOptions;
    private int DepartamentoID,FamiliaID = 0;
    private String SelectedUnidadMedida, SelectedUnidadMedidaComercial;
    private ArticuloPrecio precioArticulo;


    @PostConstruct
    public void init() {
        precioArticulo = new ArticuloPrecio();
        newArticulo = new Articulos();
        selectedArticulo = new Articulos();
        filterBy = new ArrayList<>();
        updateDepartamentoAndFamiliaOptions(); 
    }

    public List<Articulos> articulosActivos() {
        if(articulosActivos == null){
            articulosActivos = articulosService.ListAllEnabled();
        }
        return articulosActivos;
    }
    
    public List<Articulos> articulosFull() {
        if(articulos == null){
            articulos = articulosService.listAll();
        }
        return articulos;
    }
    
    public List<Articulos> articulosSinProcesar(){
        if(sinProcesar == null){
            sinProcesar = articulosService.listAllSinProcesar();
        }
        return sinProcesar;
    }
    
    public List<Articulos> articulosActivosYProcesados(){
        if(activosYProcesados == null){
            activosYProcesados = articulosService.listAllActivosYProcesados();
        }
        return activosYProcesados;
    }
    


    public long articulosCount() {
        return articulosService.count();
    }
    
    public long articulosActivosCount(){
        return articulosService.countActivos();
    }

    public long articulosInactivosCount(){
        return articulosService.countInactivos();
    }
    
    public long articulosPendientesCount(){
        return articulosService.countPendientes();
    }
    
    public void openNewArticulo() {
        newArticulo = new Articulos();
        updateDepartamentoAndFamiliaOptions(); 
        PrimeFaces.current().executeScript("PF('CrearArticuloDialog').show();");
    }
    
    public void updateArticuloByDialog() {
        if(currentSession.isValid()){
            if(DepartamentoID != 0 || FamiliaID != 0){
            var oldArticulo = selectedArticulo;
            selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
            selectedArticulo.setUsuario(currentSession.getCurrentUser());
            selectedArticulo.setProcessed(true);
            if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null){
                if(selectedArticulo.getCodigoCabys() != null){
                    selectedArticulo.setProcessed(true);
                    articulosService.update(selectedArticulo);
                    alertasService.registrarAlerta("Artículo actualizado", "Se ha actualizado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "ArticulosController.updateArticulo", oldArticulo.toString(), selectedArticulo.toString());
                    clearCache();
                    clearArticulo();

                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se actualizó el artículo", null));
                    PrimeFaces.current().executeScript("PF('EditArticuloDialog').hide();");
                }else{
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "No se seleccionó un código del CABYS", null));
                }
            }
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No se seleccionó departamento o familia", null));
            }    
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "La sesión es inválida", null));
            
        }
    }
    
    public void updateArticuloRevision() {
        if(currentSession.isValid()){
            if(DepartamentoID != 0 || FamiliaID != 0){
                var oldArticulo = selectedArticulo;
                selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
                selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
                selectedArticulo.setUsuario(currentSession.getCurrentUser());
                selectedArticulo.setProcessed(true);
                if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null){
                    if(selectedArticulo.getCodigoCabys() != null){
                        if(selectedArticulo.getLastPrecio().getPrecioFinal() != null){
                            selectedArticulo.setProcessed(true);
                            articulosService.update(selectedArticulo);
                            alertasService.registrarAlerta("Artículo actualizado", "Se ha actualizado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "ArticulosController.updateArticulo", oldArticulo.toString(), selectedArticulo.toString());
                            clearCache();
                            clearArticulo();

                            FacesContext.getCurrentInstance().addMessage(null,
                            new FacesMessage(FacesMessage.SEVERITY_INFO, "Se proceso el articulo", null));

                            PrimeFaces.current().executeScript("PF('RevisionArticuloDialog').hide();");
                        }else{
                            FacesContext.getCurrentInstance().addMessage(null,
                            new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay precio final", "se debe re-ajustar la utilidad y verificar que el codigo cabys sea correcto"));
                        }
                    }else{
                        FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "No se selecciono un codigo del CABYS", null));
                    }
                }
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No se selecciono departamento o familia", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_WARN, "La sesion es invalida", null));
            
        }
        
    }
    
    public void procesadoRapido(){
        selectedArticulo = sinProcesar.get(0);
    }
    
    public void updateArticulosRevision() {
        if(currentSession.isValid()) {
            if(DepartamentoID != 0 || FamiliaID != 0) {
                var oldArticulo = selectedArticulo;
                selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
                selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
                selectedArticulo.setUsuario(currentSession.getCurrentUser());
                selectedArticulo.setProcessed(true);
                
                if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null) {
                    if(selectedArticulo.getCodigoCabys() != null){
                        if(selectedArticulo.getLastPrecio().getPrecioFinal() != null){
                            selectedArticulo.setProcessed(true);
                            articulosService.update(selectedArticulo);
                              
                            alertasService.registrarAlerta("Artículo actualizado", "Se ha actualizado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "updateArticulosRevision()", oldArticulo.toString(), selectedArticulo.toString());
                            clearCache();

                            // Load the next article or reset if none available
                            boolean hasNext = loadNextArticulo();

                            FacesContext.getCurrentInstance().addMessage(null,
                            new FacesMessage(FacesMessage.SEVERITY_INFO, "Se proceso el articulo", null));

                            // Add callback parameters for JavaScript
                            PrimeFaces.current().ajax().addCallbackParam("success", true);
                            PrimeFaces.current().ajax().addCallbackParam("hasNextArticle", hasNext);
                            PrimeFaces.current().ajax().update("RevisionArticulosDialog");
                        }else{
                            FacesContext.getCurrentInstance().addMessage(null,
                            new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay precio final", "se debe re-ajustar la utilidad y verificar que el codigo cabys sea correcto"));
                            PrimeFaces.current().ajax().addCallbackParam("success", false);
                        }
                    }else{
                        FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "No se selecciono un codigo del CABYS", null));
                        PrimeFaces.current().ajax().addCallbackParam("success", false);
                    }                    
                }
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "No se selecciono departamento o familia", null));
                PrimeFaces.current().ajax().addCallbackParam("success", false);
            }
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "La sesion es invalida", null));
            PrimeFaces.current().ajax().addCallbackParam("success", false);
        }
    }

    private boolean loadNextArticulo() {
        sinProcesar = articulosService.listAllSinProcesar();
        
        if (sinProcesar == null || sinProcesar.isEmpty()) {
            return false;
        }

        // Retrieve the first unprocessed article
        selectedArticulo = sinProcesar.get(0);
        
        if(selectedArticulo.getDepartamento() != null){
            var selectedDepartamento = selectedArticulo.getDepartamento().getId();
            this.DepartamentoID = selectedDepartamento;
        }else{
            this.DepartamentoID = 0;
        }
        
        if(selectedArticulo.getFamilia() != null){
            var selectedFamilia = selectedArticulo.getFamilia().getId();
            this.FamiliaID = selectedFamilia;
        }else{
            this.FamiliaID = 0;
        }

        return selectedArticulo != null;
    }
    
    public void loadPreviousArticulo() {
        // Implementation for loading previous article
        // This would require maintaining a current position in the list
        sinProcesar = articulosService.listAllSinProcesar();
        
        if (sinProcesar != null && !sinProcesar.isEmpty()) {
            // For now, just load the first one. In a real implementation,
            // you'd need to track current position and load the previous one
            selectedArticulo = sinProcesar.get(0);
            
            if(selectedArticulo.getDepartamento() != null){
                var selectedDepartamento = selectedArticulo.getDepartamento().getId();
                this.DepartamentoID = selectedDepartamento;
            }else{
                this.DepartamentoID = 0;
            }
            
            if(selectedArticulo.getFamilia() != null){
                var selectedFamilia = selectedArticulo.getFamilia().getId();
                this.FamiliaID = selectedFamilia;
            }else{
                this.FamiliaID = 0;
            }
            
            PrimeFaces.current().ajax().update("RevisionArticulosDialog");
        }
    }
    
    public void skipCurrentArticle() {
        // Skip current article without processing - just move to next
        if(selectedArticulo != null){
            var oldArticulo = selectedArticulo;
            
            alertasService.registrarAlerta("Artículo omitido", "Se ha omitido el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "skipCurrentArticle()", oldArticulo.toString(), selectedArticulo.toString());
            
            clearCache();
            loadNextArticulo();
            
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Se omitió el artículo", null));
            
            PrimeFaces.current().ajax().update("RevisionArticulosDialog");
        }
    }
    
    public void updateSimpleArticulo(Articulos articulo){
        articulosService.update(articulo);
    }
        
    public boolean isValidArticulo(){
        if(currentSession.isValid()){
            if(DepartamentoID != 0 || FamiliaID != 0){
                var existingArticulo = articulosService.findByBarCode(newArticulo.getCodigoBarra());
                if(existingArticulo == null){
                        return true;
                }else{
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "El codigo de barra ingresado ya existe.", null));
                }
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No se encontro seleccion para Departamentos o Familias", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_ERROR, "La sesion es invalida.", null));
        }
        return false;
    }

    public void createArticuloByDialog() {
        if(isValidArticulo()){
            newArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            newArticulo.setFamilia(familiaService.findById(FamiliaID));
            newArticulo.setUsuario(currentSession.getCurrentUser());
            newArticulo.setProcessed(true);
            if(newArticulo.getDepartamento() != null && newArticulo.getFamilia() != null && newArticulo.getUsuario() != null){
                newArticulo.setStatus(true);
                if(newArticulo.getPrecios()!=null){
                    precioArticulo.setArticulo(newArticulo);
                    newArticulo.getPrecios().add(precioArticulo);
                }else{
                    List<ArticuloPrecio> precios = new ArrayList<>();
                    precioArticulo.setArticulo(newArticulo);
                    precios.add(precioArticulo);
                    newArticulo.setPrecios(precios);
                }
                articulosService.create(newArticulo);
                alertasService.registrarAlerta("Artículo creado", "Se ha creado el artículo: " + newArticulo.getNombre(), currentSession.getCurrentUser(), 0, "ArticulosController.createArticulo", null, newArticulo.toString());
                clearCache();
                clearArticulo();
                
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se creo el articulo", null));
                
                PrimeFaces.current().executeScript("PF('CrearArticuloDialog').hide();");
            }
        }
    }

    public void deleteArticulo() {
        if (selectedArticulo != null) {
            var oldArticulo = selectedArticulo;
            articulosService.softDelete(selectedArticulo);
            alertasService.registrarAlerta("Artículo eliminado", "Se ha eliminado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "ArticulosController.deleteArticulo", oldArticulo.toString() , selectedArticulo.toString());
            clearSelectedArticulo();
        }
    }
    
    public void createSimpleArticulo(Articulos articulo){
        articulosService.create(articulo);
    }

    public void clearSelectedArticulo() {
        articulos = null;
        articulosActivos = null;
        articulos = null;
        sinProcesar = null;
        activosYProcesados = null;
        inactivos = null;
        newArticulo = null;
        selectedArticulo = null;
    }
    
    public void clearArticulo(){
        newArticulo = null;
        articulosFilter = null;
    }
    
    public void clearFilter(){
        articulosFilter = null;
        // Forzar la recarga de las listas filtradas
        inactivos = null;
        activosYProcesados = null;
        sinProcesar = null;
    }
    
    public void clearCache(){
        articulos = null;
        articulosActivos = null;
        sinProcesar = null;
        activosYProcesados = null;
        inactivos = null;
        clearArticulo();
    }
      
    public List<Articulos> getFilteredArticulosActivos() {
        if(articulosActivos == null){
            articulosActivos = articulosService.ListAllEnabled();
        }
        if (articulosFilter != null && !articulosFilter.trim().isEmpty()) {
            return articulosActivos().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosActivos;
        }
    }
    
    public List<Articulos> getFilteredArticulosInactivos() {
        if(inactivos == null){
            inactivos = articulosService.listAllInactivos();
        }
        if (articulosFilter != null && !articulosFilter.trim().isEmpty()) {
            return inactivos.stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inactivos;
        }
    }
    
    public List<Articulos> getFilteredArticulosActivosYProcesados() {
        if(activosYProcesados == null){
            activosYProcesados = articulosService.listAllActivosYProcesados();
        }
        if (articulosFilter != null && !articulosFilter.trim().isEmpty()) {
            return activosYProcesados.stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return activosYProcesados;
        }
    }
    
    public List<Articulos> getFilteredArticulosSinProcesar() {
        if(sinProcesar == null){
            sinProcesar = articulosService.listAllSinProcesar();
        }
        if (articulosFilter != null && !articulosFilter.trim().isEmpty()) {
            return sinProcesar.stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return sinProcesar;
        }
    }
    
    public List<Articulos> getFilteredArticulosFull() {
        if(articulos == null){
            articulos = articulosService.listAll();
        }
        if (articulosFilter != null && !articulosFilter.trim().isEmpty()) {
            return articulos.stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulos;
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
                || articulo.getCodigoBarra().toLowerCase().contains(filterText)
                || (articulo.getDepartamento() != null && articulo.getDepartamento().getNombre().toLowerCase().contains(filterText))
                || (articulo.getFamilia() != null && articulo.getFamilia().getNombre().toLowerCase().contains(filterText))
                || (articulo.getUsuario() != null && articulo.getUsuario().getUsername().toLowerCase().contains(filterText));
    }

    public void calcularPrecioConIVA(ArticuloPrecio articuloPrecio) {
        if (articuloPrecio != null && articuloPrecio.getPrecioConUtilidad() != null && newArticulo.getCodigoCabys() != null) {
            BigDecimal precioConUtilidad = articuloPrecio.getPrecioConUtilidad();
            BigDecimal precio0 = BigDecimal.ZERO;

            // Verificar si el precioConUtilidad es distinto de cero
            if (precioConUtilidad.compareTo(precio0) != 0) {
                BigDecimal impuesto = new BigDecimal(newArticulo.getCodigoCabys().getImpuesto());
                BigDecimal precioSinIVA = articuloPrecio.getPrecioConUtilidad();

                // Calcular el IVA como porcentaje del precio sin IVA
                BigDecimal factorIVA = impuesto.divide(new BigDecimal(100));
                BigDecimal IVA = precioSinIVA.multiply(factorIVA);

                // Calcular el precio con IVA sumando el IVA al precio sin IVA
                BigDecimal precioConIVA = precioSinIVA.add(IVA);

                // Redondear hacia arriba el precio con IVA utilizando RoundingMode.CEILING
                precioConIVA = precioConIVA.setScale(0, RoundingMode.CEILING);

                //Registrar quien ajusto el precio...
                articuloPrecio.setUsuario(currentSession.getCurrentUser());
                
                // Asignar el precio con IVA al artículo
                articuloPrecio.setPrecioFinal(precioConIVA);
                
                
            }
        }
    }

    public void calcularPrecioConUtilidad(ArticuloPrecio articuloPrecio) {
        try {
            if (articuloPrecio != null) {
                BigDecimal porcentajeUtilidad = articuloPrecio.getPorcentajeUtilidad();
                BigDecimal precioCosto = articuloPrecio.getPrecioCostoSinIVA();

                if(precioCosto != null && porcentajeUtilidad != null){
                   // Verificar que precioCosto y porcentajeUtilidad sean mayores o iguales a cero
                    if (precioCosto.compareTo(BigDecimal.ZERO) >= 0 && porcentajeUtilidad.compareTo(BigDecimal.ZERO) >= 0) {
                        // Calcular la utilidad como porcentaje del precioCosto
                        BigDecimal factorUtilidad = porcentajeUtilidad.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP); // Ajustar la escala según sea necesario
                        BigDecimal utilidad = precioCosto.multiply(factorUtilidad);

                        // Calcular el precio con utilidad sumando la utilidad al precioCosto
                        BigDecimal precioConUtilidad = precioCosto.add(utilidad);

                        // Redondear hacia arriba el precio con utilidad utilizando RoundingMode.CEILING
                        precioConUtilidad = precioConUtilidad.setScale(0, RoundingMode.CEILING);

                        // Asignar el precio con utilidad al atributo precioConUtilidad del artículo
                        articuloPrecio.setPrecioConUtilidad(precioConUtilidad);

                        // Calcular el precio con IVA después de actualizar el precio final
                        calcularPrecioConIVA(articuloPrecio);
                    } else {
                        // Mostrar mensaje de error si precioCosto o porcentajeUtilidad son negativos
                        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error en Validacion", "El precio costo o porcentaje de utilidad no pueden ser negativos"));
                    } 
                }
            }
        } catch (Exception e) {  
            alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), currentSession.getCurrentUser(), 0, "ArticulosController.calcularPrecioConUtilidad()", null, e.getMessage());
        }
    }
    
    public void calcularPrecioConUtilidadEdit(ArticuloPrecio articuloPrecio) {
        try {
            if (articuloPrecio != null) {
                BigDecimal porcentajeUtilidad = articuloPrecio.getPorcentajeUtilidad();
                BigDecimal precioCosto = articuloPrecio.getPrecioCostoSinIVA();
                if(porcentajeUtilidad != null && precioCosto != null){
                    // Verificar que precioCosto y porcentajeUtilidad sean mayores o iguales a cero
                    if (precioCosto.compareTo(BigDecimal.ZERO) >= 0 && porcentajeUtilidad.compareTo(BigDecimal.ZERO) >= 0) {
                        // Calcular la utilidad como porcentaje del precioCosto
                        BigDecimal factorUtilidad = porcentajeUtilidad.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP); // Ajustar la escala según sea necesario
                        BigDecimal utilidad = precioCosto.multiply(factorUtilidad);

                        // Calcular el precio con utilidad sumando la utilidad al precioCosto
                        BigDecimal precioConUtilidad = precioCosto.add(utilidad);

                        // Redondear hacia arriba el precio con utilidad utilizando RoundingMode.CEILING
                        precioConUtilidad = precioConUtilidad.setScale(0, RoundingMode.CEILING);

                        // Asignar el precio con utilidad al atributo precioConUtilidad del artículo
                        articuloPrecio.setPrecioConUtilidad(precioConUtilidad);

                        // Calcular el precio con IVA después de actualizar el precio final
                        calcularPrecioConIVAEdit(articuloPrecio);
                    } else {
                        // Mostrar mensaje de error si precioCosto o porcentajeUtilidad son negativos
                        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error en Validacion", "El precio costo o porcentaje de utilidad no pueden ser negativos"));
                    }
                }else{
                    return;
                }
            } else {
                return;
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), currentSession.getCurrentUser(), 0, "ArticulosController.calcularPrecioConUtilidadEdit()", null, e.getMessage());
        }
    }
    
    public void calcularPrecioConIVAEdit(ArticuloPrecio articuloPrecio) {
        if (articuloPrecio != null) {
            BigDecimal precioConUtilidad = articuloPrecio.getPrecioConUtilidad();
            if (precioConUtilidad != null && precioConUtilidad.compareTo(BigDecimal.ZERO) != 0 && selectedArticulo.getCodigoCabys() != null) {
                BigDecimal impuesto = new BigDecimal(selectedArticulo.getCodigoCabys().getImpuesto());
                BigDecimal precioSinIVA = articuloPrecio.getPrecioConUtilidad();

                // Calcular el IVA como porcentaje del precio sin IVA
                BigDecimal factorIVA = impuesto.divide(new BigDecimal(100));
                BigDecimal IVA = precioSinIVA.multiply(factorIVA);

                // Calcular el precio con IVA sumando el IVA al precio sin IVA
                BigDecimal precioConIVA = precioSinIVA.add(IVA);

                // Redondear hacia arriba el precio con IVA utilizando RoundingMode.CEILING
                precioConIVA = precioConIVA.setScale(0, RoundingMode.CEILING);

                //Registrar quien ajusto el precio...
                articuloPrecio.setUsuario(currentSession.getCurrentUser());
                
                // Asignar el precio con IVA al atributo correspondiente del artículo
                articuloPrecio.setPrecioFinal(precioConIVA);
            } else {
                return;
            }
        } else {
            alertasService.registrarAlerta("Info", "No hay articulo.", null, 0, "ArticulosController.calcularPrecioConIVAEdit()", null, null);
        }
    }
        
    private void updateDepartamentoAndFamiliaOptions() {
        departamentoOptions = departamentoService.listAll();
        familiaOptions = familiaService.listAll();
    }
    
    public double getStock(Articulos articulo){
        String codigoBarra = articulo.getCodigoBarra();
        double totalStock = inventarioService.getStock(codigoBarra);
        return totalStock;
    }
    
    //Returns the latest precio for an articulo.
    public ArticuloPrecio getLastPrecio() {
        List<ArticuloPrecio> precios = selectedArticulo.getPrecios();
        if (precios != null && !precios.isEmpty()) {
            return precios.get(precios.size() - 1);
        }
        return null;
    }
    
    public ArticuloPrecio getLastPrecioNew() {
        List<ArticuloPrecio> precios = newArticulo.getPrecios();
        if (precios != null && !precios.isEmpty()) {
            return precios.get(precios.size() - 1);
        }else{
            return precioArticulo;
        }
    }
    
    //Returns the latest precio for an articulo.
    public ArticuloPrecio getLastPrecioFor(Articulos articulo) {
        ArticuloPrecio precio = precioService.findByArticulo(articulo);
        return precio;
    }
    
    public List<ArticuloPrecio> getAllPreciosFor(Articulos articulo){
        List<ArticuloPrecio> precios = precioService.findAllByArticulo(articulo);
        return precios;
    }
    
    public Articulos findArticuloByName(String name){
        return articulosService.findByName(name);
    }
    
    public Articulos findArticuloByBarCode(String barcode){
        return articulosService.findByBarCode(barcode);
    }
    
    public void selectedUnidadMedidaChanged() {
        String message = "Se selecciono: ";
        if (SelectedUnidadMedida != null) {
                message += SelectedUnidadMedida;
        }

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
        
        selectedArticulo.setUnidadMedida(SelectedUnidadMedida);
    }
    
    public void assignCabysToNewArticulo() {
        Cabys selectedCabys = cabysController.getSelectedCabysForAssignment();
        if (selectedCabys != null) {
            newArticulo.setCodigoCabys(selectedCabys);
        }
    }
    
    public void assignCabysToSelectedArticulo() {
        Cabys selectedCabys = cabysController.getSelectedCabysForAssignment();
        if (selectedCabys != null) {
            selectedArticulo.setCodigoCabys(selectedCabys);
        }
    }
    
    public void selectedUnidadMedidaComercialChanged() {
        String message = "Se selecciono: ";
        if (SelectedUnidadMedidaComercial != null) {
                message += SelectedUnidadMedidaComercial;
        }

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
        
        selectedArticulo.setUnidadMedidaComercial(SelectedUnidadMedidaComercial);
    }
    
    public void createUnidadMedidaComercialChanged() {
        String message = "Se selecciono: ";
        if (SelectedUnidadMedidaComercial != null) {
                message += SelectedUnidadMedidaComercial;
        }

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
        
        newArticulo.setUnidadMedidaComercial(SelectedUnidadMedidaComercial);
    }
    
    public void createUnidadMedidaChanged() {
        String message = "Se selecciono: ";
        if (SelectedUnidadMedida != null) {
                message += SelectedUnidadMedida;
        }

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
        
        newArticulo.setUnidadMedida(SelectedUnidadMedida);
    }
    
    public void exportPDF(String table) throws IOException, DocumentException {
        FacesContext facesContext = FacesContext.getCurrentInstance();

        // Generate a unique file name using timestamp
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String timestamp = currentTime.format(formatter);
        String fileName = "reporteAjustesActivos_" + timestamp + ".pdf";
        File tempFile = File.createTempFile("reporteAjustesActivos_" + timestamp, ".pdf");

        try (OutputStream os = new FileOutputStream(tempFile)) {
            Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5);
            PdfWriter.getInstance(document, os);
            document.add(new Meta("charset", "UTF-8"));
            document.open();

            // Set font size
            com.lowagie.text.Font font = new com.lowagie.text.Font();
            font.setSize(8); // Set font size to 8 points

            // Add fancy title
            document.add(new Paragraph("Reporte de Articulos Activos", font));
            document.add(new Paragraph("-------------------------------------", font));

            // Add content to the PDF
            DataTable dataTable = (DataTable) facesContext.getViewRoot().findComponent(table);
            if (dataTable == null) {
                throw new RuntimeException("DataTable component not found: " + table);
            }
            List<Articulos> articulos = (List<Articulos>) dataTable.getValue();
            int totalItems = articulos.size();
            int currentItem = 1;
            for (Articulos articulo : articulos) {
                String itemInfo = currentItem + "/" + totalItems;
                document.add(new Paragraph(itemInfo, font));

                document.add(new Paragraph("Art: " + articulo.getNombre(), font));
                if (articulo.getFamilia() != null) {
                    document.add(new Paragraph("Dept: " + articulo.getDepartamento().getNombre() + " Fam: " + articulo.getFamilia().getNombre(), font));
                } else {
                    document.add(new Paragraph("Dept: " + articulo.getDepartamento().getNombre() + " Familia sin definir", font));
                }
                if (articulo.getCodigoCabys() != null) {
                    document.add(new Paragraph("%Imp: " + articulo.getCodigoCabys().getCodigo(), font));
                } else {
                    document.add(new Paragraph("Cabys sin definir", font));
                }
                document.add(new Paragraph("Und: " + articulo.getUnidadMedida() + " UndComercial: " + articulo.getUnidadMedidaComercial(), font));

                // Show only the latest price, assuming it's the last in the list
                if (articulo.getPrecios() != null && !articulo.getPrecios().isEmpty()) {
                    ArticuloPrecio latestPrecio = articulo.getPrecios().get(articulo.getPrecios().size() - 1);

                    document.add(new Paragraph("Costo: " + latestPrecio.getPrecioCostoSinIVA(), font));
                    document.add(new Paragraph("%Util: " + latestPrecio.getPorcentajeUtilidad(), font));
                    document.add(new Paragraph("C/Util: " + latestPrecio.getPrecioConUtilidad(), font));
                    document.add(new Paragraph("Venta: " + latestPrecio.getPrecioFinal(), font));
                } else {
                    document.add(new Paragraph("No hay precios definidos", font));
                }

                document.add(new Paragraph("Creador: " + articulo.getUsuario().getUsername(), font));
                document.add(new Paragraph("\n", font));

                currentItem++;
            }
            document.close();
        }

        // Create the directory if it doesn't exist
        directoryConfig.createReportesDir();

        // Move the temporary file to the permanent location with the unique name
        File permanentFile = new File(directoryConfig.getReportesDirPath(), fileName);
        
        // Ensure the parent directory exists
        File parentDir = permanentFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new RuntimeException("Failed to create directory: " + parentDir.getAbsolutePath());
            }
        }
        
        Files.move(tempFile.toPath(), permanentFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // PDF has been saved, print it!!!
        printer.printPDFFile(permanentFile);

        // Serve the PDF to the client with the unique name
        ExternalContext externalContext = facesContext.getExternalContext();
        HttpServletResponse response = (HttpServletResponse) externalContext.getResponse();
        response.setContentType("application/pdf");
        response.setHeader("Content-disposition", "attachment; filename=" + fileName);

        try (OutputStream outStream = response.getOutputStream()) {
            // Write the content of the permanent file to the output stream
            Files.copy(permanentFile.toPath(), outStream);
            outStream.flush();
        }

        facesContext.responseComplete();
    }
    
    public String getContextPath() {
        ExternalContext externalContext = FacesContext.getCurrentInstance().getExternalContext();
        HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
        return request.getContextPath();
    }
    
}
