package Controllers;

import Models.ArticuloPrecio;
import Services.FacturaService;
import Models.Articulos;
import Models.Comprobantes.ComprobanteFinal;
import Models.Comprobantes.Detalles.*;
import Models.Comprobantes.Encabezado.Encabezado;
import Models.Comprobantes.Resumen.ResumenFactura;
import Models.Departamento;
import Models.Inventario;
import Models.Users;
import Services.ArticuloPrecioService;
import Services.Facturas.*;
import Utils.Parsers.Parser;
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

/**
 *
 * @author Al
 */

@Named
@Data
@ViewScoped
public class FacturasController implements Serializable {
    
    @Inject FacturaService facturaService;
    @Inject LineaDetalleService lineaDetalleService;
    @Inject SessionController currentSession;
    @Inject ArticulosController articuloController;
    @Inject InventarioController inventarioController;
    @Inject DepartamentoController departamentosController;
    @Inject MedioPagoService medioPagoService;
    @Inject ArticuloPrecioService precioService;
    @Inject Parser parser;
    
    private List<UploadedFile> files;
    private List<ComprobanteFinal> facturas;
    private List<ComprobanteFinal> facturasDetalladas;
    private List<ComprobanteFinal> carritoCompras;
    private List<Articulos> articulosCarrito;
    private List<ComprobanteFinal> facturasVencidas;
    private List<ComprobanteFinal> facturasPendientes;
    private ComprobanteFinal newFactura;
    private String codigoBarra;
    private int cantidadArticulos;
    private boolean resetFlag;
    
    private DetalleServicio detalleCarrito;
    private LineaDetalle lineaDetalle;
    
    private ComprobanteFinal selectedFactura;
    private String facturaFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    public void processCodigoBarra() {
        String codigo = this.codigoBarra;
        if(codigo != null || !codigo.isBlank()){
            Articulos articulo = articuloController.findArticuloByBarCode(codigo);
            if (articulo != null) {
                if(cantidadArticulos > 0){
                    for (int i = 0; i < cantidadArticulos; i++) {
                        articulosCarrito.add(articulo);
                    }
                    codigoBarra = "";
                    cantidadArticulos = 1;
                    resetFlag = !resetFlag; // Toggle the reset flag
                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, cantidadArticulos + "# Artículo agregado", "El artículo fue agregado al carrito"));
                } else {
                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "No hay cantidad", "La cantidad es invalida"));
                }
            }else{
                FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Artículo no encontrado", "El código de barra no corresponde a un artículo válido"));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Codigo de Barra vacio o nulo", "El código de barra no corresponde a un artículo válido"));
        }

    }
    
    public void selectArticulo(Articulos articulo){
        codigoBarra = articulo.getCodigoBarra();
        processCodigoBarra();
    }
    
    @PostConstruct
    public void init(){
        files = new ArrayList<>();
        filterBy = new ArrayList<>();
        selectedFactura = new ComprobanteFinal();
        carritoCompras = new ArrayList<>();
        articulosCarrito = new ArrayList<>();
        cantidadArticulos = 1;
        codigoBarra = new String();
    }
    
    public List<ComprobanteFinal> facturasList() {
        if (facturas == null) {
            facturas = facturaService.ListAllEnabled();
        }
        return facturas;
    }
    
    public List<ComprobanteFinal> facturasListDetalladas() {
        if(facturasDetalladas == null){
            facturasDetalladas = facturaService.listAll();
        }
        return facturasDetalladas;
    }
    
    public List<ComprobanteFinal> facturasPenditenes(){
        if(facturasPendientes == null){
            facturasPendientes = facturaService.listPendientes();
        }
        return facturasPendientes;
    }
    
    public List<ComprobanteFinal> facturasVencidas(){
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
            facturaService.softDelete(selectedFactura);
            clearFactura();
        }
    }
    
    public void toggleFactura(){
        if(selectedFactura != null){
            facturaService.toggle(selectedFactura);
        }
    }

    public void clearFactura() {
        openNewFactura();
        selectedFactura = null;
    }
    
    public void clearCache(){
        facturas = null;
        facturasDetalladas = null;
    }

    public List<ComprobanteFinal> getFilteredFacturas() {
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
    
    public List<ComprobanteFinal> getFilteredFacturasDetallados() {
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
    
    public List<ComprobanteFinal> getFilteredFacturasPendientes() {
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
    
    public List<ComprobanteFinal> getFilteredFacturasVencidas() {
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

        ComprobanteFinal factura = (ComprobanteFinal) value;
        return factura.getEncabezado().getCodigoActividad().toLowerCase().contains(filterText)
                || factura.getEncabezado().getCondicionVenta().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getNombre().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getCorreoElectronico().toLowerCase().contains(filterText)
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
            System.out.println("Error" + e.getLocalizedMessage());
        }
    }
    
    public void processFacturas(){
        if(!files.isEmpty()){
            for (int i = 0; i < files.size(); i++) {
                parseXMLFromUploadedFile(files.get(i));
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
        
    private void processFactura(ComprobanteFinal factura){
        try {
            
            List<LineaDetalle> lineasDetalle = factura.getDetalles().getLineasDetalle();
            if(lineasDetalle.isEmpty()){
                System.out.println("Empty factura?");
                lineasDetalle = lineaDetalleService.listAllWhereID(factura.getDetalles().getId());
                if(lineasDetalle.isEmpty() || lineasDetalle.equals(null)){
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
                        break;
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
                var UnidadesParseadas = parser.parseUnidadComercial(unidadMedida, unidadMedidaComercial) * cantidad.doubleValue();

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
                    precioArticulo.setPrecioFinal(BigDecimal.ZERO);
                    precioArticulo.setPrecioCostoConIVA(BigDecimal.ZERO);

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
        }
    }
    
    public void cancel(){
        System.out.println("Cajero: " + currentSession.getCurrentUser().getUsername() + "Cancelo Factura");
    }
    
    public void openNewFactura(){
        newFactura = new ComprobanteFinal();
    }
    
    public void crearFactura(){
        ComprobanteFinal comprobante = new ComprobanteFinal();
        Encabezado encabezado = new Encabezado();
        DetalleServicio detalles = new DetalleServicio();
        ResumenFactura resumen = new ResumenFactura();
        
        boolean status, processed;
        Users user;
        
    }
    
}
