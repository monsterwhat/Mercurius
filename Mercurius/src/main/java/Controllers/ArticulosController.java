package Controllers;

import Models.Articulos;
import Models.Departamento;
import Models.Familia;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.InventarioService;
import Services.PrinterService;
import Utils.directoryConfig;
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
    @Inject private ViewController viewManager;
    @Inject private DepartamentoService departamentoService;
    @Inject private FamiliaService familiaService;
    @Inject private InventarioService inventarioService;
    @Inject private SessionController currentSession;
    @Inject private CabysController cabysController;
    @Inject private directoryConfig directoryConfig;
    @Inject private PrinterService printer;
    
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

    @PostConstruct
    public void init() {
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
    
    public void getCabysSelection(){
        if(cabysController.getSelectedCabys()!=null){
            var selection = cabysController.getSelectedCabys();
            selectedArticulo.setCodigoCabys(selection);
        }
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
    
    public List<Articulos> articulosInactivos(){
        if(inactivos == null){
            inactivos = articulosService.listAllInactivos();
        }
        return inactivos;
    }

    public long articulosCount() {
        return articulosService.count();
    }

    public void openNewArticulo() {
        newArticulo = new Articulos();
        updateDepartamentoAndFamiliaOptions(); 
    }

    public void updateArticulo() {
        if(DepartamentoID != 0 || FamiliaID != 0 && currentSession.isValid()){
            selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
            selectedArticulo.setUsuario(currentSession.getCurrentUser());
            selectedArticulo.setProcessed(true);
            if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null){
                articulosService.updateAndDisable(selectedArticulo);
                clearSelectedArticulo();
            }
        }
    }
    
    public void updateArticuloByDialog() {
        if(currentSession.isValid()){
            if(DepartamentoID != 0 || FamiliaID != 0){
            selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
            selectedArticulo.setUsuario(currentSession.getCurrentUser());
            selectedArticulo.setProcessed(true);
            if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null){
                articulosService.updateAndDisable(selectedArticulo);
                clearCache();
                clearArticulo();
                 
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se actualizo el articulo", null));
                PrimeFaces.current().executeScript("PF('EditArticuloDialog').hide();");
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
    
    public void updateArticuloRevision() {
        if(currentSession.isValid()){
            if(DepartamentoID != 0 || FamiliaID != 0){
                selectedArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
                selectedArticulo.setFamilia(familiaService.findById(FamiliaID));
                selectedArticulo.setUsuario(currentSession.getCurrentUser());
                selectedArticulo.setProcessed(true);
                if(selectedArticulo.getDepartamento() != null && selectedArticulo.getFamilia() != null  && selectedArticulo.getUsuario() != null){
                    articulosService.updateAndDisable(selectedArticulo);
                    clearCache();
                    clearArticulo();

                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Se proceso el articulo", null));
                    
                    PrimeFaces.current().executeScript("PF('RevisionArticuloDialog').hide();");

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
    
    public void updateSimpleArticulo(Articulos articulo){
        articulosService.updateAndDisable(articulo);
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

    public void createArticulo() {
        if(isValidArticulo()){
                newArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
                newArticulo.setFamilia(familiaService.findById(FamiliaID));
                newArticulo.setUsuario(currentSession.getCurrentUser());
                selectedArticulo.setProcessed(true);
                if(newArticulo.getDepartamento() != null && newArticulo.getFamilia() != null && newArticulo.getUsuario() != null){
                    newArticulo.setStatus(true);
                    articulosService.create(newArticulo);
                    clearSelectedArticulo();
            }
        }
    }

    public void createArticuloByDialog() {
        if(isValidArticulo()){
            newArticulo.setDepartamento(departamentoService.findById(DepartamentoID));
            newArticulo.setFamilia(familiaService.findById(FamiliaID));
            newArticulo.setUsuario(currentSession.getCurrentUser());
            newArticulo.setProcessed(true);
            if(newArticulo.getDepartamento() != null && newArticulo.getFamilia() != null && newArticulo.getUsuario() != null){
                newArticulo.setStatus(true);
                articulosService.create(newArticulo);
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
            articulosService.softDelete(selectedArticulo);
            clearSelectedArticulo();
        }
    }
    
    public void createSimpleArticulo(Articulos articulo){
        articulosService.create(articulo);
    }

    public void clearSelectedArticulo() {
        clearCache();
        clearArticulo();
    }
    
    public void clearArticulo(){
        newArticulo = null;
    }
    
    public void clearCache(){
        articulos = null;
        articulosActivos = null;
        sinProcesar = null;
        activosYProcesados = null; 
        updateDepartamentoAndFamiliaOptions();
    }

    public List<Articulos> getFilteredArticulosActivos() {
        if(articulosActivos == null){
            articulosActivos = articulosService.ListAllEnabled();
        }
        if (articulosFilter != null && !articulosFilter.isEmpty()) {
            return articulosActivos().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosActivos();
        }
    }
    
    public List<Articulos> getFilteredArticulosFull() {
        if(articulos == null){
            articulos = articulosService.listAll();
        }
        if (articulosFilter != null && !articulosFilter.isEmpty()) {
            return articulosFull().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosFull();
        }
    }
    
    public List<Articulos> getFilteredArticulosSinProcesar() {
        if(sinProcesar == null){
            sinProcesar = articulosService.listAllSinProcesar();
        }
        if (articulosFilter != null && !articulosFilter.isEmpty()) {
            return articulosSinProcesar().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosSinProcesar();
        }
    }
    
    public List<Articulos> getFilteredArticulosActivosYProcesados() {
        if(activosYProcesados == null){
            activosYProcesados = articulosService.listAllActivosYProcesados();
        }
        if (articulosFilter != null && !articulosFilter.isEmpty()) {
            return articulosActivosYProcesados().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosActivosYProcesados();
        }
    }
    
    public List<Articulos> getFilteredArticulosInactivos() {
        if(inactivos == null){
            inactivos = articulosService.listAllInactivos();
        }
        if (articulosFilter != null && !articulosFilter.isEmpty()) {
            return articulosInactivos().stream()
                    .filter(articulo -> globalFilterFunction(articulo, articulosFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return articulosInactivos();
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
    
    public void calcularPrecioConIVA() {
        if (newArticulo != null) {
            if (newArticulo.getPrecioFinal() != 0 && newArticulo.getCodigoCabys() != null) {
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
            }else{
                System.out.println("No hay articulo para editar...");
            }
        } catch (Exception e) {
            System.out.println("Error:" + e.getLocalizedMessage());
        }
        
    }
    
    public void calcularPrecioConIVAEdit() {
        if (selectedArticulo != null) {
            if (selectedArticulo.getPrecioFinal() != 0 && selectedArticulo.getCodigoCabys() != null) {
                double impuesto = selectedArticulo.getCodigoCabys().getImpuesto();
                double precioSinIVA = selectedArticulo.getPrecioFinal();
                double IVA = precioSinIVA * (impuesto * 0.01);
                double precioConIVA = precioSinIVA+IVA;

                precioConIVA = Math.ceil(precioConIVA);

                selectedArticulo.setPrecioCostoConIVA(precioConIVA); 
            }else{
                System.out.println("No hay Cabys o Precio final.");
            }
        }else{
            System.out.println("No hay articulo.");
        }
    }
    
    public double calcularPrecioConIVAReturn(double precio, double tax) {
        if (precio != 0) {
            
            double IVA = precio * (tax * 0.01);
            double precioConIVA = precio+IVA;

            precioConIVA = Math.ceil(precioConIVA);

            return precioConIVA;
        }
        return 0;
    }
    
    public double calcularPrecioConUtilidadReturn(double precio, double utilidad){
        try {
            if (precio != 0) {
                if(precio >= 0 && utilidad >=0 ){
                    double Utilidad = precio*(utilidad*0.01);
                    double precioConUtilidad = precio+Utilidad;

                    precioConUtilidad = Math.ceil(precioConUtilidad);
                    return precioConUtilidad;
                }
            }
        } catch (Exception e) {
            System.out.println("Error:" + e.getLocalizedMessage());
        }
        return 0;
    }
    
    private void updateDepartamentoAndFamiliaOptions() {
        departamentoOptions = departamentoService.listAll();
        familiaOptions = familiaService.listAll();
    }
    
    public double getStock(Articulos articulo){
        String codigoBarra = articulo.getCodigoBarra();
        double totalStock = inventarioService.calculateTotalStockForItemByBarcode(codigoBarra);
        return totalStock;
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
            font.setSize(8); // Set font size to 5 points

            //Add fancy title
            document.add(new Paragraph("Reporte de Articulos Activos", font));
            document.add(new Paragraph("-------------------------------------", font));

            // Add content to the PDF
            DataTable dataTable = (DataTable) facesContext.getViewRoot().findComponent(table);
            List<Articulos> articulos = (List<Articulos>) dataTable.getValue();
            int totalItems = articulos.size();
            int currentItem = 1;
            for (Articulos articulo : articulos) {
                String itemInfo = currentItem + "/" + totalItems;
                document.add(new Paragraph(itemInfo, font));

                document.add(new Paragraph("Art: " + articulo.getNombre(), font));
                if(articulo.getFamilia() !=null){
                    document.add(new Paragraph("Dept: " + articulo.getDepartamento().getNombre() + "Fam" + articulo.getFamilia().getNombre(), font));
                }else{
                    document.add(new Paragraph("Dept: " + articulo.getDepartamento().getNombre() + "Familia sin definir", font));
                }
                if(articulo.getCodigoCabys() != null){
                    document.add(new Paragraph("%Imp: " + articulo.getCodigoCabys().getCodigo(), font));
                }else{
                    document.add(new Paragraph("Cabys sin definir", font));
                }
                document.add(new Paragraph("Und: " + articulo.getUnidadMedida() + " UndComercial " + articulo.getUnidadMedidaComercial(), font));
                document.add(new Paragraph("Costo: " + articulo.getPrecioCostoSinIVA(), font));
                document.add(new Paragraph("%Util: " + articulo.getPorcentajeUtilidad(), font));
                document.add(new Paragraph("C/Iva: " + articulo.getPrecioFinal(), font));
                document.add(new Paragraph("Venta: " + articulo.getPrecioCostoConIVA(), font));
                document.add(new Paragraph("Creador: " + articulo.getUsuario().getUsername(), font));
                document.add(new Paragraph("\n", font));

                currentItem++;
            }
            document.close();
        }
        
        // Create the directory if it doesn't exist
        directoryConfig.createPdfSaveDirectoryIfNeeded();

        // Move the temporary file to the permanent location with the unique name
        File permanentFile = new File(directoryConfig.getPdfSaveDirectory(), fileName);
        Files.move(tempFile.toPath(), permanentFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        //PDF has been saved print it!!!
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
