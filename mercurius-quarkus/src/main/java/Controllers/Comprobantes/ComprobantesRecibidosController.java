package Controllers.Comprobantes;

import Models.ComprobantesV44.Detalles.LineaDetalle;
import Models.ComprobantesV44.Detalles.CodigoComercial;
import Controllers.*;
import Controllers.Settings.SettingsDirController;
import Models.Articulos.ArticuloPrecio;
import Services.ComprobantesRecibidosService;
import Models.Articulos.Articulos;
import Models.ComprobantesV44.ComprobantesRecibidos;
import Models.Departamento;
import Models.Inventario;
import Services.ArticuloPrecioService;
import Services.Facturas.*;
import Utils.Parsers.Parser;   
import Services.AlertasService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.file.UploadedFile;
import org.primefaces.util.LangUtils;

@Named
@Data
@ViewScoped
public class ComprobantesRecibidosController implements Serializable {
    
    @Inject SettingsDirController directoryService;
    @Inject ComprobantesRecibidosService facturaService;
    @Inject LineaDetalleService lineaDetalleService;
    @Inject SessionController currentSession;
    @Inject ArticulosController articuloController;
    @Inject InventarioController inventarioController;
    @Inject DepartamentoController departamentosController;
    @Inject MedioPagoService medioPagoService;
    @Inject ArticuloPrecioService precioService;
    @Inject Parser parser;
    @Inject AlertasService alertaService;
    
    private List<UploadedFile> files;
    private List<ComprobantesRecibidos> facturas;
    private List<ComprobantesRecibidos> facturasDetalladas;
    private List<ComprobantesRecibidos> facturasVencidas;
    private List<ComprobantesRecibidos> facturasPendientes;
    
    private LineaDetalle lineaDetalle;
    
    private ComprobantesRecibidos selectedFactura;
    private String facturaFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    @PostConstruct
    public void init(){
        files = new ArrayList<>();
        filterBy = new ArrayList<>();
        selectedFactura = new ComprobantesRecibidos();
    }
    
    public List<ComprobantesRecibidos> facturasList() {
        if (facturas == null) {
            facturas = facturaService.ListAllEnabled();
        }
        return facturas;
    }
    
    public List<ComprobantesRecibidos> facturasListDetalladas() {
        if(facturasDetalladas == null){
            facturasDetalladas = facturaService.listAll();
        }
        return facturasDetalladas;
    }
    
    public List<ComprobantesRecibidos> facturasPenditenes(){
        if(facturasPendientes == null){
            facturasPendientes = facturaService.listPendientes();
        }
        return facturasPendientes;
    }
    
    public List<ComprobantesRecibidos> facturasVencidas(){
        if(facturasVencidas == null){
            facturasVencidas = facturaService.listVencidas();
        }
        return facturasVencidas;
    }
    
    public long facturaCount() {
        return facturaService.count();
    }

    public void deleteFactura() {
        if (selectedFactura != null) {
            var oldFactura = selectedFactura;
            facturaService.softDelete(selectedFactura);
             alertaService.registrarAlerta("Exito", "La factura recibida ha sido eliminada correctamente.", currentSession.getCurrentUser(), 0, "ComprobantesRecibidosController.deleteFactura()", oldFactura.toString(), selectedFactura.toString());

            clearFactura();
        }
    }
    
    public void toggleFactura(){
        if(selectedFactura != null){
            var oldFactura = selectedFactura;
            facturaService.toggle(selectedFactura);
            alertaService.registrarAlerta("Exito", "El estado de la factura recibida ha sido cambiado correctamente.", currentSession.getCurrentUser(), 0, "ComprobantesRecibidosController.toggleFactura()", oldFactura.toString(), selectedFactura.toString());
        }
    }

    public void clearFactura() {
        selectedFactura = null;
    }
    
    public void clearCache(){
        facturas = null;
        facturasDetalladas = null;
    }

    public List<ComprobantesRecibidos> getFilteredFacturas() {
        if(facturas == null){
            facturas = facturaService.ListAllEnabled();
        }
        if (facturaFilter != null && !facturaFilter.isEmpty()) {
            return facturasList().stream()
                    .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return facturasList();
        }
    }
    
    public List<ComprobantesRecibidos> getFilteredFacturasDetallados() {
        try {
            if(facturasDetalladas == null){
            facturasDetalladas = facturaService.listAll();
            }
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return facturasListDetalladas().stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return facturasListDetalladas();
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
    }
    
    public List<ComprobantesRecibidos> getFilteredFacturasPendientes() {
        try {
            if(facturasPendientes == null){
            facturasPendientes = facturaService.listPendientes();
            }
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return facturasPenditenes().stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return facturasPenditenes();
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
    }
    
    public List<ComprobantesRecibidos> getFilteredFacturasVencidas() {
        try {
            if(facturasVencidas == null){
            facturasVencidas = facturaService.listVencidas();
            }
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return facturasVencidas().stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return facturasVencidas();
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        ComprobantesRecibidos factura = (ComprobantesRecibidos) value;
        return factura.getEncabezado().getCodigoActividadEmisor().toLowerCase().contains(filterText)
                || factura.getEncabezado().getCondicionVenta().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getNombre().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getCorreosElectronicos().contains(filterText.toLowerCase())
                || factura.getEncabezado().getEmisor().getIdentificacion().getNumero().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getNombreComercial().toLowerCase().contains(filterText)
                || factura.getEncabezado().getFechaEmision().toString().toLowerCase().contains(filterText)
                || factura.getEncabezado().getNumeroConsecutivo().toLowerCase().contains(filterText);
    }
    
    public void addFile(UploadedFile file){
        if(files == null){
            files = new ArrayList<>();
        }
        files.add(file);
    }
    
    public void parseXMLFromUploadedFile(UploadedFile uploadedFile) {
        try {
            InputStream inputStream = uploadedFile.getInputStream();    
            parser.parseXML(inputStream);
        } catch (IOException e) {
            alertaService.registrarAlerta("Error parsing xml from uploaded file", "", currentSession.getCurrentUser(), 0, "parseXMLFromUploadedFile()", null, e.getLocalizedMessage());
            System.out.println("Error" + e.getLocalizedMessage());
        }
    }
    
    public void processFacturas(){
        if(!files.isEmpty()){
            for (int i = 0; i < files.size(); i++) {
                parseXMLFromUploadedFile(files.get(i));
                 
                //TODO Should also save them on the documents/recibos/xmls
                //Done i think
                directoryService.saveUploadedFile(files.get(i), directoryService.getXMLDirPath());
                
            }
            files.clear();
            clearCache();

            PrimeFaces.current().executeScript("PF('facturasUpload').hide();");            
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Se procesaron las facturas", null));
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay facturas por procesar!", null));
        }
    }
    
    public void processSelectedFactura(){
        if(selectedFactura != null){
            if(!selectedFactura.getProcessed() && selectedFactura.getStatus()){
                processFactura(selectedFactura);
                FacesMessage message = new FacesMessage("Exito","Se procesaron los articulos de la factura!");
                FacesContext.getCurrentInstance().addMessage(null, message);
            }else{
                FacesMessage message = new FacesMessage("Oops!","La factura ya fue procesada.");
                FacesContext.getCurrentInstance().addMessage(null, message);
            }
        }else{
            FacesMessage message = new FacesMessage("Error","No hay una factura seleccionada");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
        
    private void processFactura(ComprobantesRecibidos factura){
        try {
            
            List<LineaDetalle> lineasDetalle = factura.getDetalles().getLineasDetalle();
            if(lineasDetalle.isEmpty()){
                System.out.println("Empty factura?");
                lineasDetalle = lineaDetalleService.listAllWhereID(factura.getDetalles().getId());
                if(lineasDetalle.isEmpty() || lineasDetalle == null){
                    return;
                }
            }
            
            for(LineaDetalle lineaDetalle : lineasDetalle){
                String codigoBarra = "";
                String nombre = lineaDetalle.getDetalle();
                List<CodigoComercial> codigosComercialesLineaDetalle = lineaDetalle.getCodigosComerciales();

                for(CodigoComercial codigoComercial : codigosComercialesLineaDetalle){
                    if(codigoComercial.getTipo().contains("03")){
                        codigoBarra = codigoComercial.getCodigo();
                    }
                }
                
                Articulos articuloExistente = (codigoBarra.isEmpty()) ?
                        articuloController.findArticuloByName(nombre) :
                        articuloController.findArticuloByBarCode(codigoBarra);
                
                var cantidad = lineaDetalle.getCantidad();
                String codigoCabys = lineaDetalle.getCodigoCabys();
                String unidadMedida = lineaDetalle.getUnidadMedida();
                String unidadMedidaComercial = lineaDetalle.getUnidadMedidaComercial();
                var montoTotalLinea = lineaDetalle.getMontoTotalLinea();
                var totalUnitario = montoTotalLinea.divide(cantidad,20,RoundingMode.HALF_UP);
                var precioUnitario = totalUnitario;
                BigDecimal UnidadesParseadas = parser.parseUnidadMedida(unidadMedida, unidadMedidaComercial).multiply(cantidad);

                Articulos articulo = new Articulos();
                
                Departamento departamento = new Departamento();
                    departamento.setNombre(factura.getEncabezado().getEmisor().getNombre());
                    departamento.setStatus(true);
                    departamento.setUsuario(currentSession.getCurrentUser());
                    Departamento persistedDepartamento = departamentosController.createSimpleDepartamento(departamento);
                
                if(articuloExistente == null){
                    articulo.setNombre(nombre);
                    articulo.setCodigoBarra(codigoBarra);
                    articulo.setRecomendacionCabys(codigoCabys);
                    articulo.setDepartamento(persistedDepartamento);
                    articulo.setUnidadMedida(unidadMedida);
                    articulo.setUnidadMedidaComercial(unidadMedidaComercial);
                    
                    ArticuloPrecio precioArticulo = new ArticuloPrecio();
                    precioArticulo.setArticulo(articulo);
                    precioArticulo.setPrecioCostoSinIVA(precioUnitario);

                    List<ArticuloPrecio> preciosArticulos = new ArrayList<>();
                    preciosArticulos.add(precioArticulo);

                    articulo.setPrecios(preciosArticulos);
                    
                    articulo.setUsuario(currentSession.getCurrentUser());
                    articulo.setStatus(true);
                    articulo.setProcessed(false);
                    articuloController.createSimpleArticulo(articulo);
                }else{
                    articuloExistente.setRecomendacionCabys(codigoCabys);
                    articuloExistente.setUsuario(currentSession.getCurrentUser());
                    articuloExistente.setUnidadMedida(unidadMedida);
                    articuloExistente.setUnidadMedidaComercial(unidadMedidaComercial);
                    articuloExistente.setDepartamento(persistedDepartamento);
                    
                    ArticuloPrecio precioArticulo = new ArticuloPrecio();
                    
                    precioArticulo.setArticulo(articuloExistente);
                    precioArticulo.setPrecioCostoSinIVA(precioUnitario);
                    precioArticulo.setPrecioConUtilidad(BigDecimal.ZERO);
                    precioArticulo.setPrecioFinal(BigDecimal.ZERO);

                    List<ArticuloPrecio> preciosArticulos = articuloExistente.getPrecios();
                    if (preciosArticulos == null) {
                        preciosArticulos = new ArrayList<>();
                    }
                    preciosArticulos.add(precioArticulo);
                    
                    articuloExistente.setPrecios(preciosArticulos);
                    articuloExistente.setStatus(true);
                    articuloExistente.setProcessed(false);
                    articuloController.updateSimpleArticulo(articuloExistente);
                }
                
                Inventario ajusteArticulo = new Inventario();
                
                if(articuloExistente != null){
                    ajusteArticulo.setArticulo(articuloExistente);
                }else{
                    ajusteArticulo.setArticulo(articulo);
                }
                ajusteArticulo.setUnidadesRecomendadasFactura(UnidadesParseadas);
                ajusteArticulo.setUsuario(currentSession.getCurrentUser());
                ajusteArticulo.setFechaMovimiento(new Date());
                ajusteArticulo.setTipoMovimiento("Ingreso Automatico por factura");
                ajusteArticulo.setStatus(true);
                ajusteArticulo.setProcessed(false);
                ajusteArticulo.setCantidad(cantidad);
                ajusteArticulo.setNotas((cantidad.doubleValue() != 0) ? "Pendiente a revision" : "Pendiente a revision, No se pudo auto adquirir la cantidad");                
                
                inventarioController.createSimpleInventario(ajusteArticulo);
            }
            
            factura.setProcessed(true);
            facturaService.update(factura);
            clearCache();
            
        } catch (Exception e) {
            System.out.println("Error procesing factura: " + e.getLocalizedMessage());
            alertaService.registrarAlerta("Error procesando factura", "", currentSession.getCurrentUser(), 0, "processFactura()", null, e.getLocalizedMessage());
        }
    }
    
    public void cancel(){
        System.out.println("Cajero: " + currentSession.getCurrentUser().getUsername() + "Cancelo Factura");
    }
    
    
}
