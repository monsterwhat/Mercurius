package Controllers;

import Models.Inventario;
import Models.Articulos.Articulos;
import Services.InventarioService;
import Services.ArticulosService;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.component.datatable.DataTable;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "InventarioController")
@ViewScoped
public class InventarioController implements Serializable {
    
    @Inject private InventarioService inventarioService;
    @Inject private ArticulosService articuloService;
    @Inject private AlertasService alertasService;
    @Inject private SessionController currentSession;
    
    private List<Inventario> inventarioActivo;
    private List<Inventario> inventario;
    private List<Inventario> sinProcesar;
    private List<Inventario> activosYProcesados;
    private List<Inventario> inactivos;

    private Inventario selectedInventario;
    private Articulos selectedArticulo;
    private Inventario newInventario;
    private String inventarioFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    private List<Articulos> articuloOptions;
    private int ArticuloID = 0;

    @PostConstruct
    public void init() {
        newInventario = new Inventario();
        selectedInventario = new Inventario();
        filterBy = new ArrayList<>();
        selectedArticulo = new Articulos();
        inventarioActivoYProcesado();
    }

    public List<Inventario> inventarioList() {
        if(inventarioActivo == null){
            inventarioActivo = inventarioService.ListAllEnabled();
        }
        return inventarioActivo;
    }
    
    public List<Inventario> inventarioListAll() {
        if(inventario == null){
            inventario = inventarioService.listAll();
        }
        return inventario;
    }
    
    public List<Inventario> inventarioSinProcesar() {
        if(sinProcesar == null){
            sinProcesar = inventarioService.listAllSinProcesar();
        }
        return sinProcesar;
    }
    
    public List<Inventario> inventarioActivoYProcesado() {
        if(activosYProcesados == null){
            activosYProcesados = inventarioService.listAllActivosYProcesados();
        }
        return activosYProcesados;
    }
    
    public List<Inventario> inventarioInactivo() {
        if(inactivos == null){
            inactivos = inventarioService.listAllInactivos();
        }
        return inactivos;
    }

    public long inventarioCount() {
        return inventarioService.count();
    }
    
    public long inventarioActivoCount(){
        return inventarioService.countActivos();
    }

    public long inventarioPendienteCount(){
        return inventarioService.countPendientes();
    }
    
    public long inventarioInactivoCount(){
        return inventarioService.countInactivos();
    }

    public void updateInventarioRevisionDialog() {
        if(currentSession.isValid()){
            if(selectedInventario != null){
                var oldInventario = selectedInventario;
                selectedInventario.setUsuario(currentSession.getCurrentUser());
                selectedInventario.setProcessed(true);
                if(selectedInventario.getArticulo() != null){
                    if(selectedInventario.getUsuario() != null){
                        Date today = new Date();
                        selectedInventario.setFechaMovimiento(today);
                        selectedInventario.setTipoMovimiento("Stock por Factura");
                        selectedInventario.setNotas("Procesado mediante el sistema por: " + currentSession.getUsername());
                        inventarioService.markAsProcessed(selectedInventario);
                        alertasService.registrarAlerta("Inventario actualizado", "Se ha actualizado el inventario: " + selectedInventario.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "InventarioController.updateInventarioRevisionDialog", oldInventario.toString(), selectedInventario.toString());
                        clearSelectedInventario();
                        PrimeFaces.current().executeScript("PF('RevisionMovimientoDialog').hide();");
                    }
                }else{
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Articulo Invalido", null));
                }
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono articulo por procesar?", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion invalida", null));
        }
    }    
    
    public void updateInventariosRevisionDialog() {
        if(currentSession.isValid()){
            if(selectedInventario != null){
                var oldInventario = selectedInventario;
                selectedInventario.setUsuario(currentSession.getCurrentUser());
                selectedInventario.setProcessed(true);
                if(selectedInventario.getArticulo() != null){
                    if(selectedInventario.getUsuario() != null){
                        Date today = new Date();
                        selectedInventario.setFechaMovimiento(today);
                        selectedInventario.setTipoMovimiento("Stock por Factura");
                        selectedInventario.setNotas("Procesado mediante el sistema por: " + currentSession.getUsername());
                        inventarioService.markAsProcessed(selectedInventario);
                        // Save an alert (log) for updating an inventory item
                        alertasService.registrarAlerta("Inventario actualizado", "Se ha actualizado el inventario: " + selectedInventario.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "InventarioController.updateInventariosRevisionDialog", oldInventario.toString(), selectedInventario.toString());
                        
                        // Refresh cache and load next
                        clearCache();
                        loadNextAjuste();
 
                        FacesContext.getCurrentInstance().addMessage(null,
                            new FacesMessage(FacesMessage.SEVERITY_INFO, "Se proceso el ajuste", null));
                        
                        // Add callback parameters for JavaScript
                        PrimeFaces.current().ajax().addCallbackParam("success", true);
                        PrimeFaces.current().ajax().addCallbackParam("hasNextMovement", (sinProcesar != null && !sinProcesar.isEmpty()));
 
                        // Update the rapid processing dialog
                        PrimeFaces.current().ajax().update("ProcesadoRapidoInventarioDialog");
                    }
                }else{
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Articulo Invalido", null));
                }
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono articulo por procesar?", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion invalida", null));
        }
    }  
    
    private void loadNextAjuste() {
        
        sinProcesar = inventarioService.listAllSinProcesar();
        
        if (sinProcesar == null || sinProcesar.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay más artículos para revisar", null));
            return;
        }

        // Retrieve first unprocessed article
        selectedInventario = sinProcesar.get(0);
        selectedInventario.setNotas("");
        
        if (selectedInventario == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay más artículos para revisar", null));
        }
    }
    
    public void skipCurrentMovement() {
        // Skip current movement without processing - just move to next
        if(selectedInventario != null){
            var oldInventario = selectedInventario;
            
            alertasService.registrarAlerta("Movimiento omitido", "Se ha omitido el movimiento: " + selectedInventario.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "skipCurrentMovement()", oldInventario.toString(), selectedInventario.toString());
            
            clearSelectedInventario();
            loadNextAjuste();
            
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Se omitió el movimiento", null));
            
                    PrimeFaces.current().ajax().addCallbackParam("success", true);
                    PrimeFaces.current().ajax().addCallbackParam("skipped", true);
                    PrimeFaces.current().ajax().addCallbackParam("hasNextMovement", (sinProcesar != null && !sinProcesar.isEmpty()));
                    
                    // Mostrar el diálogo de nuevo para el siguiente movimiento
                    PrimeFaces.current().executeScript("setTimeout(function() { if (typeof PF === 'function' && PF('ProcesadoRapidoInventarioDialog')) { PF('ProcesadoRapidoInventarioDialog').show(); } }, 500);");
        }
    }
    
    public void procesarMovimientoYSiguiente() {
        // Procesar movimiento actual y cargar siguiente
        if(currentSession.isValid()){
            if(selectedInventario != null){
                var oldInventario = selectedInventario;
                selectedInventario.setUsuario(currentSession.getCurrentUser());
                selectedInventario.setProcessed(true);
                if(selectedInventario.getArticulo() != null){
                    Date today = new Date();
                    selectedInventario.setFechaMovimiento(today);
                    selectedInventario.setTipoMovimiento("Stock por Factura");
                    
                    // Usar las notas del formulario si existen
                    String notas = FacesContext.getCurrentInstance().getExternalContext()
                        .getRequestParameterMap().get("procesadoRapidoInventarioForm:notasR");
                    if(notas != null && !notas.trim().isEmpty()) {
                        selectedInventario.setNotas("Procesado mediante el sistema por: " + currentSession.getCurrentUser().getUsername() + ". Notas: " + notas);
                    } else {
                        selectedInventario.setNotas("Procesado mediante el sistema por: " + currentSession.getCurrentUser().getUsername());
                    }
                    
                    inventarioService.markAsProcessed(selectedInventario);
                    alertasService.registrarAlerta("Inventario actualizado", "Se ha actualizado el inventario: " + selectedInventario.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "procesarMovimientoYSiguiente()", oldInventario.toString(), selectedInventario.toString());
                    
                    clearCache();
                    loadNextAjuste();
                    
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Se proceso el ajuste", null));
                    
                    PrimeFaces.current().ajax().addCallbackParam("success", true);
                    PrimeFaces.current().ajax().addCallbackParam("hasNextMovement", (sinProcesar != null && !sinProcesar.isEmpty()));
                } else {
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "Articulo Invalido", null));
                    PrimeFaces.current().ajax().addCallbackParam("success", false);
                }
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono articulo por procesar?", null));
                PrimeFaces.current().ajax().addCallbackParam("success", false);
            }
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion invalida", null));
            PrimeFaces.current().ajax().addCallbackParam("success", false);
        }
    }
     
    public void procesadoRapido(){
        // Cargar el primer inventario pendiente
        sinProcesar = inventarioService.listAllSinProcesar();
        
        if (sinProcesar != null && !sinProcesar.isEmpty()) {
            selectedInventario = sinProcesar.get(0);
            selectedInventario.setNotas("");
            
            // Mostrar mensaje informativo
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Movimiento cargado. Seleccione el movimiento en la tabla para procesar.", null));
        } else {
            // Mostrar mensaje si no hay pendientes
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay movimientos pendientes para procesar", null));
        }
    }
    
    public void createInventarioDialog() {
        if(!currentSession.isValid()){
            //Invalid Session
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }else{
            if(newInventario == null){
                //No se abrio articulo nuevo ???
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se pudo abrir un articulo nuevo!", null));
            }else{
                if(ArticuloID == 0) {
                    FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Articulo Invalido", null));
                }else{
                    newInventario.setArticulo(articuloService.findById(ArticuloID));
                    newInventario.setUsuario(currentSession.getCurrentUser());
                    newInventario.setProcessed(true);
                    if(newInventario.getArticulo() != null && newInventario.getUsuario() != null){
                        newInventario.setStatus(true);
                        Date today = new Date();
                        newInventario.setFechaMovimiento(today);
                        inventarioService.createWithStock(newInventario);
                        // Save an alert (log) for creating an inventory item
                        alertasService.registrarAlerta("Inventario creado", "Se ha creado el inventario: " + newInventario.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "InventarioController.createInventarioDialog", null, newInventario.toString());
                        clearSelectedInventario();
                        PrimeFaces.current().executeScript("PF('CrearAjusteDialog').hide();");

                    }else{
                        //No se guardo el Ajuste
                    }
                }
            }
        }
    }
    
    public void createSimpleInventario(Inventario inventario){
        inventarioService.create(inventario);
    }

    public void deleteInventario() {
        if (selectedInventario != null) {
            var oldInventario = selectedInventario;
            inventarioService.softDelete(selectedInventario);
            // Save an alert (log) for deleting an inventory item
            alertasService.registrarAlerta("Inventario eliminado", "Se ha eliminado el inventario: " + selectedInventario.getArticulo().getNombre(), currentSession.getCurrentUser(), 0, "InventarioController.deleteInventario", oldInventario.toString() , selectedInventario.toString());
            clearSelectedInventario();
        }
    }

    public void clearSelectedInventario() {
        clearCache();
        clearInventario();
    }

    public List<Inventario> getFilteredInventarioSinProcesar() {
        if(sinProcesar == null){
            sinProcesar = inventarioService.listAllSinProcesar();
        }
        if (inventarioFilter != null && !inventarioFilter.trim().isEmpty()) {
            return inventarioSinProcesar().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioSinProcesar();
        }
    }
    
    public List<Inventario> getFilteredInventarioActivoYProcesado() {
        if(activosYProcesados == null){
            activosYProcesados = inventarioService.listAllActivosYProcesados();
        }
        if (inventarioFilter != null && !inventarioFilter.trim().isEmpty()) {
            return inventarioActivoYProcesado().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioActivoYProcesado();
        }
    }
    
    public List<Inventario> getFilteredInventarioInactivo() {
        if(inactivos == null){
            inactivos = inventarioService.listAllInactivos();
        }
        if (inventarioFilter != null && !inventarioFilter.trim().isEmpty()) {
            return inventarioInactivo().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioInactivo();
        }
    }
    
    public List<Inventario> getFilteredInventario() {
        if(inventarioActivo == null){
            inventarioActivo = inventarioService.ListAllEnabled();
        }
        if (inventarioFilter != null && !inventarioFilter.trim().isEmpty()) {
            return inventarioList().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioList();
        }
    }
    
    public List<Inventario> getFilteredInventarioDetallado() {
        if(inventario == null){
            inventario = inventarioService.listAll();
        }
        if (inventarioFilter != null && !inventarioFilter.trim().isEmpty()) {
            return inventarioListAll().stream()
                    .filter(inventario -> globalFilterFunction(inventario, inventarioFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return inventarioListAll();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Inventario inventario = (Inventario) value;
        return String.valueOf(inventario.getCodigo()).contains(filterText)
                || inventario.getArticulo().getNombre().toLowerCase().contains(filterText)
                || inventario.getArticulo().getCodigoBarra().toLowerCase().contains(filterText)
                || inventario.getFechaMovimiento().toString().toLowerCase().contains(filterText)
                || inventario.getNotas().toLowerCase().contains(filterText)
                || inventario.getTipoMovimiento().toLowerCase().contains(filterText)
                || String.valueOf(inventario.getCantidad()).contains(filterText)
                || inventario.getUsuario().getUsername().toLowerCase().contains(filterText);
    }
    
    public void articuloSelectedEditDialog() {
        if (selectedArticulo != null) {
            selectedInventario.setArticulo(selectedArticulo);
            PrimeFaces.current().executeScript("PF('ArticuloRevisionDialog').hide();");
        } else {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se ha seleccionado ningún CABYS.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
    
    public void clearCache(){
        inventario = null;
        inventarioActivo = null;
        inactivos = null;
        sinProcesar = null;
    }
    
    public void clearInventario(){
        newInventario = null;
        selectedInventario = null;
    }
    
    public void unprocess(){
        if(selectedInventario != null){
            selectedInventario.setProcessed(false);
            inventarioService.update(selectedInventario);
        }
    }
    
    public void exportPDF() throws IOException, DocumentException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        DataTable dataTable = (DataTable) facesContext.getViewRoot().findComponent("Inventarios:AjustesActivos:InventarioTableData");

        // Create a document with custom page size (width: 80mm, height: 200mm) and margins (5px)
        //Document document = new Document(new Rectangle(80f, 200f), 5, 5, 5, 5);
        Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, baos);
        document.add(new Meta("charset", "UTF-8"));
        document.open();
        
        // Set font size
        com.lowagie.text.Font font = new com.lowagie.text.Font();
        font.setSize(8); // Set font size to 5 points

        //Add fancy title
        document.add(new Paragraph("Reporte de Ajustes Activos", font));
        document.add(new Paragraph("-------------------------------------", font));

        
        // Add content to the PDF
        List<Inventario> inventarios = (List<Inventario>) dataTable.getValue();
        int totalItems = inventarios.size();
        int currentItem = 1;
        for (Inventario inventario : inventarios) {
            String itemInfo = currentItem + "/" + totalItems;
            document.add(new Paragraph(itemInfo, font));

            document.add(new Paragraph("Art: " + inventario.getArticulo().getNombre(), font));
            document.add(new Paragraph("Can: " + inventario.getCantidad()+ "  Tipo: " + inventario.getTipoMovimiento(), font));
            document.add(new Paragraph("Fecha: " + inventario.getFechaMovimiento(), font));
            document.add(new Paragraph("Creador: " + inventario.getUsuario().getUsername(), font));
            document.add(new Paragraph("\n", font));
            
            currentItem++;
        }

        document.close();

        // Serve the PDF to the client
        HttpServletResponse response = (HttpServletResponse) facesContext.getExternalContext().getResponse();
        response.setContentType("application/pdf; charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(baos.size());
        response.setHeader("Content-disposition", "attachment; filename=reporteAjustesActivos.pdf");

        response.getOutputStream().write(baos.toByteArray());
        response.getOutputStream().flush();
        response.getOutputStream().close();

        facesContext.responseComplete();
    }
    
    public String getContextPath() {
        ExternalContext externalContext = FacesContext.getCurrentInstance().getExternalContext();
        HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
        return request.getContextPath();
    }
    
}
