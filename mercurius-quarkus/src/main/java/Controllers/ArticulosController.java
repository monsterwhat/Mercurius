package Controllers;

import Controllers.Settings.SettingsDirController;
import Models.Articulos.ArticuloImagen;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.Departamento;
import Models.Familia; 
import Services.ArticuloImagenService;
import Services.ArticuloPrecioService;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.InventarioService;
import Services.PrinterService;
import Services.AlertasService;
import Services.ProductoExoneracionService;
import Models.ProductoExoneracion;
import com.lowagie.text.DocumentException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.component.datatable.DataTable;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;
import org.primefaces.util.LangUtils;
import Utils.DiffUtils;
import Utils.ReportExporter;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "ArticulosController")
@ViewScoped
public class ArticulosController implements Serializable {
    
    @Inject @Nonnull private ArticulosService articulosService;
    @Inject @Nonnull private ArticuloPrecioService precioService;
    @Inject @Nonnull private DepartamentoService departamentoService;
    @Inject @Nonnull private FamiliaService familiaService;
    @Inject @Nonnull private InventarioService inventarioService;
    @Inject @Nonnull private SessionController currentSession;
    @Inject @Nonnull private CabysController cabysController;
    @Inject @Nonnull private SettingsDirController directoryConfig;
    @Inject @Nonnull private PrinterService printer;
    @Inject @Nonnull private AlertasService alertasService;
    @Inject @Nonnull private ArticuloImagenService imagenService;
    @Inject @Nonnull private ProductoExoneracionService productoExoneracionService;
    
    @Nullable
    private List<Articulos> articulosActivos;
    @Nullable
    private List<Articulos> articulos;
    @Nullable
    private List<Articulos> sinProcesar;
    @Nullable
    private List<Articulos> activosYProcesados;
    @Nullable
    private List<Articulos> inactivos;
    @Nullable
    private Articulos selectedArticulo;
    @Nullable
    private Articulos newArticulo;
    @Nullable
    private String articulosFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    @Nullable
    private List<Departamento> departamentoOptions;
    @Nullable
    private List<Familia> familiaOptions;
    private int DepartamentoID, FamiliaID = 0;
    @Nullable
    private String SelectedUnidadMedida;
    @Nullable
    private String SelectedUnidadMedidaComercial;
    @Nonnull
    private ArticuloPrecio precioArticulo;
    @Nullable
    private ProductoExoneracion exoneracion;
    @Nullable
    private ProductoExoneracion selectedExoneracion;
    private boolean showStockAlertConfig = false;
    @Nullable
    private String newFamiliaNombre;


    @PostConstruct
    public void init() {
        precioArticulo = new ArticuloPrecio();
        newArticulo = new Articulos();
        selectedArticulo = new Articulos();
        filterBy = new ArrayList<>();
        updateDepartamentoAndFamiliaOptions(); 
    }

    @Nonnull
    public List<Articulos> articulosActivos() {
        if(articulosActivos == null){
            articulosActivos = articulosService.ListAllEnabled();
        }
        return articulosActivos;
    }
    
    @Nonnull
    public List<Articulos> articulosFull() {
        if(articulos == null){
            articulos = articulosService.listAll();
        }
        return articulos;
    }
    
    @Nonnull
    public List<Articulos> articulosSinProcesar(){
        if(sinProcesar == null){
            sinProcesar = articulosService.listAllSinProcesar();
        }
        return sinProcesar;
    }
    
    @Nonnull
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
        exoneracion = new ProductoExoneracion();
        updateDepartamentoAndFamiliaOptions(); 
        PrimeFaces.current().executeScript("PF('CrearArticuloDialog').show();");
    }
    
    @Nullable
    public ProductoExoneracion getSelectedExoneracion() {
        if (selectedExoneracion == null && selectedArticulo != null && selectedArticulo.getCodigo() != null) {
            selectedExoneracion = productoExoneracionService.findByArticuloCodigo(String.valueOf(selectedArticulo.getCodigo()));
            if (selectedExoneracion == null) {
                selectedExoneracion = new ProductoExoneracion();
            }
        }
        return selectedExoneracion;
    }
    
    public void updateArticuloByDialog() {
        if(currentSession.isValid()){
            if(DepartamentoID != 0 || FamiliaID != 0){
            String antes = DiffUtils.snapshotEntity(selectedArticulo);
            selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
            selectedArticulo.setUsuario(currentSession.getCurrentUser());
            selectedArticulo.setProcessed(true);
                if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null){
                    if(selectedArticulo.getCodigoCabys() != null){
                        selectedArticulo.setProcessed(true);
                        articulosService.update(selectedArticulo);
                        
                        saveOrUpdateExoneracion(selectedArticulo, selectedExoneracion);
                        
                        alertasService.registrarAlerta("Artículo actualizado", "Se ha actualizado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "ArticulosController.updateArticulo", antes, DiffUtils.snapshotEntity(selectedArticulo));
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
                String antes = DiffUtils.snapshotEntity(selectedArticulo);
                selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
                selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
                selectedArticulo.setUsuario(currentSession.getCurrentUser());
                selectedArticulo.setProcessed(true);
                if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null){
                    if(selectedArticulo.getCodigoCabys() != null){
                        if(selectedArticulo.getLastPrecio().getPrecioFinal() != null){
                            selectedArticulo.setProcessed(true);
                            articulosService.update(selectedArticulo);
                            alertasService.registrarAlerta("Artículo actualizado", "Se ha actualizado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "ArticulosController.updateArticulo", antes, DiffUtils.snapshotEntity(selectedArticulo));
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
                String antes = DiffUtils.snapshotEntity(selectedArticulo);
                selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
                selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
                selectedArticulo.setUsuario(currentSession.getCurrentUser());
                selectedArticulo.setProcessed(true);
                
                if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null) {
                    if(selectedArticulo.getCodigoCabys() != null){
                        if(selectedArticulo.getLastPrecio().getPrecioFinal() != null){
                            selectedArticulo.setProcessed(true);
                            articulosService.update(selectedArticulo);
                              
                            alertasService.registrarAlerta("Artículo actualizado", "Se ha actualizado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "updateArticulosRevision()", antes, DiffUtils.snapshotEntity(selectedArticulo));
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
        if(selectedArticulo != null){
            String antes = DiffUtils.snapshotEntity(selectedArticulo);
            
            alertasService.registrarAlerta("Artículo omitido", "Se ha omitido el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "skipCurrentArticle()", antes, DiffUtils.snapshotEntity(selectedArticulo));
            
            clearCache();
            loadNextArticulo();
            
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Se omitió el artículo", null));
            
            PrimeFaces.current().ajax().update("RevisionArticulosDialog");
        }
    }
    
    public void updateSimpleArticulo(@Nonnull Articulos articulo){
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
                
                if (newArticulo.isExento() && exoneracion != null) {
                    exoneracion.setArticuloCodigo(String.valueOf(newArticulo.getCodigo()));
                    productoExoneracionService.save(exoneracion);
                }
                
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
            String antes = DiffUtils.snapshotEntity(selectedArticulo);
            articulosService.softDelete(selectedArticulo);
            alertasService.registrarAlerta("Artículo eliminado", "Se ha eliminado el artículo: " + selectedArticulo.getNombre(), currentSession.getCurrentUser(), 0, "ArticulosController.deleteArticulo", antes, DiffUtils.snapshotEntity(selectedArticulo));
            clearSelectedArticulo();
        }
    }
    
    public void createSimpleArticulo(@Nonnull Articulos articulo){
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
        exoneracion = null;
        selectedExoneracion = null;
        articulosFilter = null;
    }
    
    private void saveOrUpdateExoneracion(@Nonnull Articulos articulo, @Nullable ProductoExoneracion source) {
        if (articulo.isExento() && source != null) {
            ProductoExoneracion existing = productoExoneracionService.findByArticuloCodigo(String.valueOf(articulo.getCodigo()));
            if (existing != null) {
                existing.setTipoDocumentoEX1(source.getTipoDocumentoEX1());
                existing.setNumeroDocumento(source.getNumeroDocumento());
                existing.setNombreInstitucion(source.getNombreInstitucion());
                existing.setFechaEmisionEX(source.getFechaEmisionEX());
                existing.setMontoExoneracion(source.getMontoExoneracion());
                existing.setTarifaExonerada(source.getTarifaExonerada());
                existing.setTipoDocumentoOTRO(source.getTipoDocumentoOTRO());
                existing.setArticulo(source.getArticulo());
                existing.setInciso(source.getInciso());
                existing.setNombreInstitucionOtros(source.getNombreInstitucionOtros());
                productoExoneracionService.save(existing);
            } else {
                source.setArticuloCodigo(String.valueOf(articulo.getCodigo()));
                productoExoneracionService.save(source);
            }
        }
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
      
    @Nonnull
    public List<Articulos> getFilteredArticulosActivos() {
        if(articulosActivos == null){
            articulosActivos = articulosService.ListAllEnabled();
        }
        return articulosActivos;
    }
    
    @Nonnull
    public List<Articulos> getFilteredArticulosInactivos() {
        if(inactivos == null){
            inactivos = articulosService.listAllInactivos();
        }
        return inactivos;
    }
    
    @Nonnull
    public List<Articulos> getFilteredArticulosActivosYProcesados() {
        if(activosYProcesados == null){
            activosYProcesados = articulosService.listAllActivosYProcesados();
        }
        return activosYProcesados;
    }
    
    @Nonnull
    public List<Articulos> getFilteredArticulosSinProcesar() {
        if(sinProcesar == null){
            sinProcesar = articulosService.listAllSinProcesar();
        }
        return sinProcesar;
    }
    
    @Nonnull
    public List<Articulos> getFilteredArticulosFull() {
        if(articulos == null){
            articulos = articulosService.listAll();
        }
        return articulos;
    }
      
    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
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

    public void calcularPrecioConIVA(@Nullable ArticuloPrecio articuloPrecio) {
        if (articuloPrecio != null && articuloPrecio.getPrecioConUtilidad() != null && newArticulo.getCodigoCabys() != null) {
            BigDecimal precioConUtilidad = articuloPrecio.getPrecioConUtilidad();
            BigDecimal precio0 = BigDecimal.ZERO;

            // Verificar si el precioConUtilidad es distinto de cero
                if (precioConUtilidad.compareTo(precio0) != 0 && newArticulo.getCodigoCabys().getImpuesto() != null
                    && !newArticulo.getCodigoCabys().getImpuesto().isEmpty()) {
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

    public void calcularPrecioConUtilidad(@Nullable ArticuloPrecio articuloPrecio) {
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
        } catch (RuntimeException e) {  
            alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), currentSession.getCurrentUser(), 0, "ArticulosController.calcularPrecioConUtilidad()", null, e.getMessage());
        }
    }
    
    public void calcularPrecioConUtilidadEdit(@Nullable ArticuloPrecio articuloPrecio) {
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
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), currentSession.getCurrentUser(), 0, "ArticulosController.calcularPrecioConUtilidadEdit()", null, e.getMessage());
        }
    }
    
    public void calcularPrecioConIVAEdit(@Nullable ArticuloPrecio articuloPrecio) {
        if (articuloPrecio != null) {
            BigDecimal precioConUtilidad = articuloPrecio.getPrecioConUtilidad();
            if (precioConUtilidad != null && precioConUtilidad.compareTo(BigDecimal.ZERO) != 0 && selectedArticulo.getCodigoCabys() != null
                && selectedArticulo.getCodigoCabys().getImpuesto() != null
                && !selectedArticulo.getCodigoCabys().getImpuesto().isEmpty()) {
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
    
    public void createFamiliaFromDialog() {
        if (newFamiliaNombre != null && !newFamiliaNombre.trim().isEmpty()) {
            Familia nuevaFamilia = new Familia();
            nuevaFamilia.setNombre(newFamiliaNombre.trim());
            nuevaFamilia.setStatus(true);
            nuevaFamilia.setUsuario(currentSession.getCurrentUser());
            nuevaFamilia.setFecha(new Date());
            
            boolean created = familiaService.createIfNotExists(nuevaFamilia);
            if (created) {
                updateDepartamentoAndFamiliaOptions();
                Familia justCreated = familiaService.findByNombre(newFamiliaNombre.trim());
                if (justCreated != null) {
                    FamiliaID = justCreated.getId();
                }
                newFamiliaNombre = null;
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Familia creada exitosamente", null));
                PrimeFaces.current().ajax().update("editFamiliaSelect");
            } else {
                Familia existing = familiaService.findByNombre(newFamiliaNombre.trim());
                if (existing != null) {
                    FamiliaID = existing.getId();
                }
                newFamiliaNombre = null;
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "La familia ya existe, se ha seleccionado", null));
                PrimeFaces.current().ajax().update("editFamiliaSelect");
            }
        }
    }
    
    public double getStock(@Nonnull Articulos articulo){
        String codigoBarra = articulo.getCodigoBarra();
        double totalStock = inventarioService.getStock(codigoBarra);
        return totalStock;
    }
    
    //Returns the latest precio for an articulo.
    @Nullable
    public ArticuloPrecio getLastPrecio() {
        List<ArticuloPrecio> precios = selectedArticulo.getPrecios();
        if (precios != null && !precios.isEmpty()) {
            return precios.get(precios.size() - 1);
        }
        return null;
    }
    
    @Nonnull
    public ArticuloPrecio getLastPrecioNew() {
        List<ArticuloPrecio> precios = newArticulo.getPrecios();
        if (precios != null && !precios.isEmpty()) {
            return precios.get(precios.size() - 1);
        }else{
            return precioArticulo;
        }
    }
    
    //Returns the latest precio for an articulo.
    @Nullable
    public ArticuloPrecio getLastPrecioFor(@Nonnull Articulos articulo) {
        ArticuloPrecio precio = precioService.findByArticulo(articulo);
        return precio;
    }
    
    @Nullable
    public List<ArticuloPrecio> getAllPreciosFor(@Nonnull Articulos articulo){
        List<ArticuloPrecio> precios = precioService.findAllByArticulo(articulo);
        return precios;
    }
    
    @Nullable
    public Articulos findArticuloByName(@Nonnull String name){
        return articulosService.findByName(name);
    }
    
    @Nullable
    public Articulos findArticuloByBarCode(@Nonnull String barcode){
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
    
    public void exportPDF(@Nonnull String table) throws IOException, DocumentException {
        FacesContext facesContext = FacesContext.getCurrentInstance();

        // Generate a unique file name using timestamp
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String timestamp = currentTime.format(formatter);
        String fileName = "reporteAjustesActivos_" + timestamp + ".pdf";
        File tempFile = File.createTempFile("reporteAjustesActivos_" + timestamp, ".pdf");

        // Add content to the PDF
        DataTable dataTable = (DataTable) facesContext.getViewRoot().findComponent(table);
        if (dataTable == null) {
            throw new RuntimeException("DataTable component not found: " + table);
        }
        List<Articulos> articulos = (List<Articulos>) dataTable.getValue();

        // T17: bytes from ReportExporter must stay byte-identical to the old inline OpenPDF block.
        byte[] pdfBytes = ReportExporter.exportArticulosPdf(articulos);

        try (OutputStream os = new FileOutputStream(tempFile)) {
            os.write(pdfBytes);
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
    
    @Nonnull
    public String getContextPath() {
        ExternalContext externalContext = FacesContext.getCurrentInstance().getExternalContext();
        HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
        return request.getContextPath();
    }

    public void uploadImagen(@Nonnull FileUploadEvent event) {
        if (selectedArticulo == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Seleccione un artículo primero"));
            return;
        }
        try {
            UploadedFile file = event.getFile();
            if (file == null || file.getFileName() == null) return;

            directoryConfig.createProductosImgDir();
            String articuloDir = directoryConfig.getProductosImgDirPath()
                + File.separator + selectedArticulo.getCodigo();

            Path dirPath = Path.of(articuloDir);
            if (!Files.exists(dirPath)) Files.createDirectories(dirPath);

            String fileName = System.currentTimeMillis() + "_" + file.getFileName();
            Path target = dirPath.resolve(fileName);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }

            ArticuloImagen img = new ArticuloImagen();
            img.setArticulo(selectedArticulo);
            img.setRuta(target.toString());
            img.setNombreOriginal(file.getFileName());
            img.setMimeType(file.getContentType());
            img.setOrden(imagenService.getNextOrden(selectedArticulo.getCodigo()));
            imagenService.create(img);

            clearCache();

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Imagen subida", file.getFileName()));
        } catch (IOException e) {
            alertasService.registrarAlerta("Error", "Error uploading image: " + e.getMessage(),
                currentSession.getCurrentUser(), 0, "ArticulosController.uploadImagen()", null, e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo subir la imagen"));
        }
    }

    public void eliminarImagen(@Nullable ArticuloImagen imagen) {
        if (imagen == null || imagen.getId() == null) return;
        try {
            Path filePath = Path.of(imagen.getRuta());
            Files.deleteIfExists(filePath);
        } catch (IOException ignored) {}
        imagenService.deleteById(imagen.getId());
        clearCache();
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Imagen eliminada", imagen.getNombreOriginal()));
    }

    public void subirOrdenImagen(@Nullable ArticuloImagen imagen) {
        if (imagen == null || selectedArticulo == null || selectedArticulo.getImagenes() == null) return;
        int idx = selectedArticulo.getImagenes().indexOf(imagen);
        if (idx <= 0) return;
        ArticuloImagen above = selectedArticulo.getImagenes().get(idx - 1);
        int temp = imagen.getOrden();
        imagen.setOrden(above.getOrden());
        above.setOrden(temp);
        imagenService.update(imagen);
        imagenService.update(above);
        clearCache();
    }

    public void bajarOrdenImagen(@Nullable ArticuloImagen imagen) {
        if (imagen == null || selectedArticulo == null || selectedArticulo.getImagenes() == null) return;
        int idx = selectedArticulo.getImagenes().indexOf(imagen);
        if (idx < 0 || idx >= selectedArticulo.getImagenes().size() - 1) return;
        ArticuloImagen below = selectedArticulo.getImagenes().get(idx + 1);
        int temp = imagen.getOrden();
        imagen.setOrden(below.getOrden());
        below.setOrden(temp);
        imagenService.update(imagen);
        imagenService.update(below);
        clearCache();
    }

    @Nullable
    public StreamedContent getImagenStream(@Nullable Long imagenId) {
        if (imagenId == null) return null;
        ArticuloImagen img = imagenService.findById(imagenId);
        if (img == null) return null;
        try {
            Path path = Path.of(img.getRuta());
            byte[] data = Files.readAllBytes(path);
            return DefaultStreamedContent.builder()
                .contentType(img.getMimeType() != null ? img.getMimeType() : "image/jpeg")
                .stream(() -> new ByteArrayInputStream(data))
                .build();
        } catch (IOException e) {
            return null;
        }
    }
    
}
