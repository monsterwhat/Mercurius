package Controllers.Tiquetes;

import Controllers.ArticulosController;
import Controllers.SessionController;
import Controllers.TipoCambioController;
import Models.ArticuloCarrito;
import Models.Articulos;
import Models.Clients;
import Models.Comprobantes.ComprobanteFinal;
import Models.Inventario;
import Services.ClientService;
import Services.InventarioService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Named("crearTiqueteController")
@Data
@ViewScoped
public class CrearTiqueteController implements Serializable{
    
    @Inject ArticulosController articuloController;
    @Inject private ClientService clientService;
    @Inject private InventarioService inventario;
    @Inject private SessionController currentSession;
    @Inject private TipoCambioController tipoCambio;
    
    private ComprobanteFinal newFactura;
    private Clients selectedClient;
    private double cantidadArticulo = 1.0;
    private String codigoBarra;
    private List<ArticuloCarrito> carrito;
    private boolean resetFlag;
    private Clients cliente;
    private String clientsFilter;
    private List<Clients> clients;
    private List<FilterMeta> filterBy;
    private BigDecimal totalCarrito, colones, dolares, vuelto;
    
    @PostConstruct
    public void init(){
        newFactura = new ComprobanteFinal();
        selectedClient = new Clients();
        codigoBarra = new String();
        carrito = new ArrayList<>();
        cliente = new Clients();
        selectedClient = new Clients();
        clientsList();
        filterBy = new ArrayList<>();
        initValores();
    }
    
    public void initValores(){
        totalCarrito = new BigDecimal(0);
        colones = new BigDecimal(0);
        dolares = new BigDecimal(0);
        vuelto = new BigDecimal(0);
    }
    
    public void resetClient(){
        selectedClient = new Clients();
    }
        
    public void processCodigoBarra() {
    String codigo = this.codigoBarra;
    double cantidad = this.cantidadArticulo;
    System.out.println(cantidad);
        if (codigo != null && !codigo.isBlank()) {
            Articulos articulo = articuloController.findArticuloByBarCode(codigo);
            if (articulo != null) {
                if (cantidad > 0) {
                    ArticuloCarrito articuloCarrito = new ArticuloCarrito(articulo);
                    boolean found = false;

                    // Recorremos el carrito para ver si ya existe el artículo
                    for (ArticuloCarrito item : carrito) {
                        if (item.getArticulo().getCodigo() == articulo.getCodigo()) { 
                            item.setCantidad(item.getCantidad() + cantidad); // Sumamos la cantidad existente con la nueva
                            found = true;
                            break;
                        }
                    }

                    // Si no lo encontró en el carrito, lo agrega con la cantidad especificada
                    if (!found) {
                        articuloCarrito.setCantidad(cantidad);
                        carrito.add(articuloCarrito);
                    }

                    // Limpiamos los campos
                    codigoBarra = "";
                    cantidadArticulo = 1;
                    resetFlag = !resetFlag; // Toggle el reset flag

                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, "Artículo agregado", "El artículo fue agregado al carrito"));
                } else {
                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "No hay cantidad", "La cantidad es inválida"));
                }
            } else {
                FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Artículo no encontrado", "El código de barra no corresponde a un artículo válido"));
            }
        } else {
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Código de barra vacío o nulo", "El código de barra no corresponde a un artículo válido"));
        }
    }
    
    public void calcularVuelto(){
        var cambio = this.tipoCambio.getTipoCambioActual().getValorCompra();
        
        if(dolares != null && colones != null){
            var totalDolaresEnColones = dolares.multiply(new BigDecimal(cambio));
        
            vuelto = totalCarrito.subtract(colones).subtract(totalDolaresEnColones);
        }
    }
    
    public String getVueltoString(){
        if(vuelto.doubleValue() > 0){
            return "Faltante: " + vuelto;
        }else{
            return "Vuelto: " + vuelto.negate();
        }
    }
    
    public BigDecimal getTotalCarritoConIva() {
        if (carrito != null && !carrito.isEmpty()) {
            BigDecimal total = null;

            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioFinal();
                BigDecimal cantidadDecimal = new BigDecimal(cantidad);
                BigDecimal precioConUtilidad = precioUnidad;

                // Si hay un cliente con descuento, aplicar el descuento al precio del artículo
                if (selectedClient != null && selectedClient.getDiscount() > 0) {
                    BigDecimal descuento = BigDecimal.valueOf(selectedClient.getDiscount());
                    BigDecimal porcentajeDescuento = descuento.divide(BigDecimal.valueOf(100));  // Convertimos el descuento a porcentaje
                    BigDecimal descuentoAplicado = precioUnidad.multiply(BigDecimal.ONE.subtract(porcentajeDescuento));  // Aplicamos el descuento
                    precioConUtilidad = descuentoAplicado;  // Asignamos el precio con descuento
                }

                // Calcular el subtotal del artículo (precio final * cantidad)
                BigDecimal subtotal = precioConUtilidad.multiply(cantidadDecimal);

                // Sumar al total
                if (total != null) {
                    total = total.add(subtotal);
                } else {
                    total = subtotal;
                }
            }
            totalCarrito = total;
            return total;
        }
        return BigDecimal.ZERO;
    }
    
    public BigDecimal getTotalDescuento() {
        
        if (carrito != null && !carrito.isEmpty()) {
            BigDecimal totalDescuento = BigDecimal.ZERO;  // Inicializamos el total de descuento

            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioFinal();
                BigDecimal cantidadDecimal = new BigDecimal(cantidad);

                // Si hay un cliente con descuento, calcular el descuento por artículo
                if (selectedClient != null && selectedClient.getDiscount() > 0) {
                    BigDecimal descuento = BigDecimal.valueOf(selectedClient.getDiscount());
                    BigDecimal porcentajeDescuento = descuento.divide(BigDecimal.valueOf(100));  // Convertimos el descuento a porcentaje

                    // Calculamos cuánto se descuenta del precio por cada unidad del artículo
                    BigDecimal descuentoPorUnidad = precioUnidad.multiply(porcentajeDescuento);

                    // Descuento total por artículo (descuento por unidad * cantidad)
                    BigDecimal descuentoTotalArticulo = descuentoPorUnidad.multiply(cantidadDecimal);

                    // Sumar al total de descuento
                    totalDescuento = totalDescuento.add(descuentoTotalArticulo);
                }
            }

            return totalDescuento;
        }
        return BigDecimal.ZERO;
    }
    
    public BigDecimal getTotalCarrito(){
        if(carrito != null || !carrito.isEmpty()){
            BigDecimal total = null;
            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioFinal();
                BigDecimal cantidadDecimal = new BigDecimal(cantidad);

                if(total != null){
                    total = total.add(precioUnidad.multiply(cantidadDecimal));
                }else{
                    total = precioUnidad.multiply(cantidadDecimal);
                }
            }
            return total;
        }
        return new BigDecimal(0);
    }

    public BigDecimal getTotalCarritoSinIva(){
        if(carrito != null || !carrito.isEmpty()){
            BigDecimal total = null;
            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioCostoSinIVA();
                BigDecimal cantidadDecimal = new BigDecimal(cantidad);
                
                if(total != null){
                    total = total.add(precioUnidad.multiply(cantidadDecimal));
                }else{
                    total = precioUnidad.multiply(cantidadDecimal);
                }
            }
            return total;
        }
        return new BigDecimal(0);
    }
    
    public BigDecimal getTotalprecioConUtilidadCarrito(){
        if(carrito != null || !carrito.isEmpty()){
            BigDecimal total = null;
            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioConUtilidad();
                BigDecimal cantidadDecimal = new BigDecimal(cantidad);
                
                if(total != null){
                    total = total.add(precioUnidad.multiply(cantidadDecimal));
                }else{
                    total = precioUnidad.multiply(cantidadDecimal);
                }
            }
            return total;
        }
        return new BigDecimal(0);
    }
    
    public void selectArticulo(Articulos articulo){
        codigoBarra = articulo.getCodigoBarra();
        processCodigoBarra();
    }
    
    public void selectCliente(Clients cliente){
        this.cliente = cliente;
    }
    
    public void removeArticulo(Articulos articulo){
        if (carrito != null) {
            Iterator<ArticuloCarrito> iterator = carrito.iterator();
            while (iterator.hasNext()) {
                ArticuloCarrito item = iterator.next();
                if (item.getArticulo().equals(articulo)) {
                    iterator.remove(); // Safely remove the item
                }
            }
        }
    }
    
    public void cancel(){
        System.out.println("Cajero: " + currentSession.getCurrentUser().getUsername() + " - Cancelo Factura");
        resetFlag = !resetFlag; // Toggle el reset flag
        codigoBarra = "";
        cantidadArticulo = 1;
        selectedClient = new Clients();
        carrito = new ArrayList<>(); //Clear el carrito...
    }
    
    public List<Clients> getFilteredClients() {
        if (clientsFilter != null && !clientsFilter.isEmpty()) {
            return clientsList().stream()
                    .filter(profile -> globalFilterFunction(profile, clientsFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return clientsList();
        }
    }
    
    public List<Clients> clientsList() {
        if (clients == null) {
            clients = clientService.listAll();
        }
        return clients;
    }
    
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Clients client = (Clients) value;
        return client.getName().toLowerCase().contains(filterText)
                || client.getEmail().toLowerCase().contains(filterText)
                || client.getBirthDate().toString().toLowerCase().contains(filterText)
                || client.getIdType().toLowerCase().contains(filterText)
                || String.valueOf(client.getIdNumber()).contains(filterText)
                || String.valueOf(client.getDiscount()).contains(filterText)
                || String.valueOf(client.getPhoneNumber()).contains(filterText)
                || String.valueOf(client.isTaxpayer()).contains(filterText)
                || String.valueOf(client.getZoneCode()).contains(filterText);
    }
    
    public void openNewFactura(){
        newFactura = new ComprobanteFinal();
    }
    
    public void verificarPago(){
        calcularVuelto();
        
        if(vuelto.doubleValue() <= 0){
            facturar(); 
        }else{
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "FALTANTE DE PAGO", "NO SE HA CANCELADO EL TOTAL DE LA FACTURA"));
        }
    }
    
    public void clearPago(){
        vuelto = BigDecimal.ZERO;
        dolares = BigDecimal.ZERO;
        colones = BigDecimal.ZERO;
    }
    
    public void facturar(){
        
        //1. Hacer ajustes en inventario
        for (ArticuloCarrito articulo : carrito) {
            var Articulo = articulo.getArticulo();
            var Cantidad = articulo.getCantidad();
            
            Inventario movimiento = new Inventario();
            movimiento.setArticulo(Articulo);
            movimiento.setCantidad(BigDecimal.valueOf(Cantidad).negate());
            movimiento.setFechaMovimiento(new Date());
            movimiento.setNotas("Articulo Vendido");
            movimiento.setProcessed(Boolean.TRUE);
            movimiento.setStatus(Boolean.TRUE);
            movimiento.setTipoMovimiento("Venta");
            movimiento.setUnidadesRecomendadasFactura(Cantidad*-1);
            movimiento.setUsuario(currentSession.getCurrentUser());
            
            inventario.update(movimiento);
        }
        
        //2. Crear Comprobante y enviarlo a tributacion
        //2.1 Encabezado
        
        
        //2.2 Detalles
        
        
        //2.3 Resumen
        
        
        //3. Guardar e imprimir Tiquete
        
        
        //4. Guardar respuesta y limpiar valores de factura.
        clearPago();
        carrito.clear();
        PrimeFaces.current().executeScript("PF('PagoDialog').hide(); PF('CrearTiqueteDialog').hide();");

        
    }
    
}
