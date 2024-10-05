package Controllers.Tiquetes;

import Controllers.ArticulosController;
import Controllers.SessionController;
import Controllers.SettingsController;
import Controllers.TipoCambioController;
import Models.AppSettings;
import Models.ArticuloCarrito;
import Models.Articulos;
import Models.Clients;
import Models.Comprobantes.ComprobantesEmitidos;
import Models.Comprobantes.ComprobantesRecibidos;
import Models.Comprobantes.Detalles.CodigoComercial;
import Models.Comprobantes.Detalles.Descuento;
import Models.Comprobantes.Detalles.DetalleServicio;
import Models.Comprobantes.Detalles.Impuesto;
import Models.Comprobantes.Detalles.LineaDetalle;
import Models.Comprobantes.Detalles.OtroCargo;
import Models.Comprobantes.Encabezado.Emisor;
import Models.Comprobantes.Encabezado.Encabezado;
import Models.Comprobantes.Encabezado.Fax;
import Models.Comprobantes.Encabezado.IdentificacionEmisor;
import Models.Comprobantes.Encabezado.IdentificacionReceptor;
import Models.Comprobantes.Encabezado.MedioPago;
import Models.Comprobantes.Encabezado.Receptor;
import Models.Comprobantes.Encabezado.Telefono;
import Models.Comprobantes.Encabezado.Ubicacion;
import Models.Comprobantes.Enums.CondicionVenta;
import Models.Comprobantes.Resumen.CodigoTipoMoneda;
import Models.Comprobantes.Resumen.ResumenFactura;
import Models.Inventario;
import Models.Promocion;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
    @Inject private SettingsController settings;
    
    private ComprobantesRecibidos newFactura;
    private Clients selectedClient;
    private double cantidadArticulo = 1.0;
    private String codigoBarra;
    private List<ArticuloCarrito> carrito, carritoDescuento;
    private boolean resetFlag;
    private Clients cliente;
    private String clientsFilter;
    private List<Clients> clients;
    private List<FilterMeta> filterBy;
    private BigDecimal totalCarrito, colones, dolares, vuelto;
    
    @PostConstruct
    public void init(){
        newFactura = new ComprobantesRecibidos();
        selectedClient = new Clients();
        codigoBarra = new String();
        carrito = new ArrayList<>();
        carritoDescuento = new ArrayList<>();
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

        if (codigo != null && !codigo.isBlank()) {
            Articulos articulo = articuloController.findArticuloByBarCode(codigo);

            if (articulo != null) {
                if (cantidad > 0) {
                    ArticuloCarrito articuloCarrito = new ArticuloCarrito(articulo, cantidad);
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
                        carrito.add(articuloCarrito); // Add to carrito first
                    }

                    // Check for active promotions in the entire cart
                    validatePromotionsForCart();
                    
                    // Limpiamos los campos
                    codigoBarra = "";
                    cantidadArticulo = 1;
                    resetFlag = !resetFlag; // Toggle el reset flag

                    FacesContext.getCurrentInstance().addMessage(null, 
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Artículo agregado", 
                                         "El artículo fue agregado al carrito"));
                } else {
                    FacesContext.getCurrentInstance().addMessage(null, 
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "No hay cantidad", 
                                         "La cantidad es inválida"));
                }
            } else {
                FacesContext.getCurrentInstance().addMessage(null, 
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Artículo no encontrado", 
                                     "El código de barra no corresponde a un artículo válido"));
            }
        } else {
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Código de barra vacío o nulo", 
                                 "El código de barra no corresponde a un artículo válido"));
        }
    }

    private void validatePromotionsForCart() {

        // Loop through all items in the cart
        for (int i = 0; i < carrito.size(); i++) {
            var item = carrito.get(i);
            List<Promocion> promocionesActivas = item.getArticulo().getPromocionesActivas();

            for (Promocion promocionActiva : promocionesActivas) {
                // Check if the cart contains all the required items for the promotion
                boolean allItemsPresent = true;

                // Loop through the required items for the promotion
                for (ArticuloCarrito articuloPromocion : promocionActiva.getArticulosCarrito()) {
                    boolean itemFoundInCart = false;

                    // Loop through the cart to see if the promotion item is present
                    for (ArticuloCarrito itemInCart : carrito) {
                        if (itemInCart.getArticulo().equals(articuloPromocion.getArticulo()) &&
                            Objects.equals(itemInCart.getCantidad(), articuloPromocion.getCantidad())) {
                            itemFoundInCart = true;
                            break;
                        }
                    }

                    // If one of the required items is missing, promotion does not apply
                    if (!itemFoundInCart) {
                        allItemsPresent = false;
                        break;
                    }
                }

                // If all required items are in the cart, apply the promotion
                if (allItemsPresent) {
                    BigDecimal precioDescuento = promocionActiva.getDescuento(); // Percentage discount
                    BigDecimal precioConDescuento = item.getArticulo().getLastPrecio().getPrecioConUtilidad()
                        .multiply(BigDecimal.ONE.subtract(precioDescuento.divide(BigDecimal.valueOf(100))));

                    // Set the discounted price and promotion flag
                    item.setPrecioConDescuento(precioConDescuento);
                    item.setPromo(true);

                    // Add to carritoDescuento and remove from the main cart
                    if(carritoDescuento.contains(item)){ //If it exists we should grab the existing one and add one or the amount being carried
                        var index = carritoDescuento.indexOf(item);
                        var itemToModify = carritoDescuento.get(index);
                        itemToModify.setCantidad(itemToModify.getCantidad()+item.getCantidad());
                    }else{
                        carritoDescuento.add(item);
                    }
                    
                    carrito.remove(item);

                    // Notify the user about the promotion
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Promoción aplicada",
                                         "El artículo " + item.getArticulo().getNombre() + 
                                         " tiene un descuento de " + precioDescuento + "%"));
                }
            }
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
    
    // Helper method to calculate total from a given list
    public BigDecimal calculateTotalCarritoSinIVA() {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioConUtilidad();
                
                BigDecimal cantidadDecimal = BigDecimal.valueOf(cantidad);
                
                BigDecimal precioFinal = precioUnidad;

                // Calculate subtotal
                BigDecimal subtotal = precioFinal.multiply(cantidadDecimal);
                total = total.add(subtotal);
            }
        }
        return total;
    }
    
    // Helper method to calculate total from a given list
    public BigDecimal calculateTotalDescuento() {
        BigDecimal total = BigDecimal.ZERO;

        if (carritoDescuento != null && !carritoDescuento.isEmpty()) {
            for (ArticuloCarrito item : carritoDescuento) {
                
            }
        }
        return total;
    }
    
    // Helper method to calculate total from a given list
    public BigDecimal calculateTotalCarritoDescuento() {
        BigDecimal total = BigDecimal.ZERO;

        if (carritoDescuento != null && !carritoDescuento.isEmpty()) {
            for (ArticuloCarrito item : carritoDescuento) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                var impuesto = item.getArticulo().getCodigoCabys().getImpuesto();
                
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioConUtilidad();
                
                BigDecimal cantidadDecimal = BigDecimal.valueOf(cantidad);
                
                BigDecimal impuestoDecimal = new BigDecimal(impuesto);
                
                BigDecimal precioFinal = precioUnidad;

                // Apply discount if applicable
                if (selectedClient != null && selectedClient.getDiscount() > 0) {
                    BigDecimal descuento = BigDecimal.valueOf(selectedClient.getDiscount());
                    BigDecimal porcentajeDescuento = descuento.divide(BigDecimal.valueOf(100));
                    BigDecimal descuentoAplicado = precioUnidad.multiply(BigDecimal.ONE.subtract(porcentajeDescuento));
                    precioFinal = descuentoAplicado;
                }

                // Calculate subtotal
                BigDecimal subtotal = precioFinal.multiply(cantidadDecimal);
                total = total.add(subtotal);
            }
        }
        return total;
    }
    
    public BigDecimal calculateTotalCarritoFinal() {
        BigDecimal total = BigDecimal.ZERO;

        // Process normal cart items
        if (carrito != null && !carrito.isEmpty()) {
            total = calculateTotalForItems(carrito, total);
        }

        // Process discounted cart items
        if (carritoDescuento != null && !carritoDescuento.isEmpty()) {
            total = calculateTotalForItems(carritoDescuento, total);
        }

        return total;
    }

    public BigDecimal calculateTotalForItems(List<ArticuloCarrito> items, BigDecimal total) {
        for (ArticuloCarrito item : items) {
            var articulo = item.getArticulo();
            var cantidad = item.getCantidad();
            var impuesto = item.getArticulo().getCodigoCabys().getImpuesto();

            BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioConUtilidad();
            BigDecimal cantidadDecimal = BigDecimal.valueOf(cantidad);

            // Convert impuesto from hundredths to decimal
            BigDecimal impuestoDecimal = new BigDecimal(impuesto).divide(BigDecimal.valueOf(100));

            // Calculate the final price after discount
            BigDecimal precioFinal = precioUnidad;

            // Apply discount if applicable
            if (selectedClient != null && selectedClient.getDiscount() > 0) {
                BigDecimal descuento = BigDecimal.valueOf(selectedClient.getDiscount());
                BigDecimal porcentajeDescuento = descuento.divide(BigDecimal.valueOf(100));
                BigDecimal descuentoAplicado = precioUnidad.multiply(BigDecimal.ONE.subtract(porcentajeDescuento));
                precioFinal = descuentoAplicado;
            }

            // Calculate subtotal
            BigDecimal subtotal = precioFinal.multiply(cantidadDecimal);

            // Calculate total with tax
            BigDecimal totalConImpuesto = subtotal.add(subtotal.multiply(impuestoDecimal));

            // Add to total
            total = total.add(totalConImpuesto);
        }
        return total;
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
        newFactura = new ComprobantesRecibidos();
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
        
        AppSettings appSettings = this.settings.getCurrentSettings();
        if(Objects.equals(appSettings.getEstatus(), Boolean.FALSE)){
            return;
        }
        
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
        
        Encabezado encabezado = encabezadoTiqueteElectronico();
        
        //2.2 Detalles
            
        DetalleServicio detalles = detallesTiqueteElectronico();

        //Agregar a comprobantesEmitidos...
            
        //2.3 Resumen
        
        ResumenFactura resumen = resumenTiqueteElectronico();
        
        //2.4 Agregar a ComprobanteEmitidos.
        ComprobantesEmitidos tiqueteElectronico = new ComprobantesEmitidos();
        tiqueteElectronico.setEncabezado(encabezado);
        tiqueteElectronico.setDetalles(detalles);
        tiqueteElectronico.setResumen(resumen);
        tiqueteElectronico.setUser(currentSession.getCurrentUser());
        
        //3. Guardar e imprimir Tiquete
        
        
        
        //4. Guardar respuesta y limpiar valores de factura.
        
        encabezado = null;
        detalles = null;
        resumen = null;
        tiqueteElectronico = null;
        
        clearPago();
        carrito.clear();
        PrimeFaces.current().executeScript("PF('PagoDialog').hide(); PF('CrearTiqueteDialog').hide();");

    }
    
    public ResumenFactura resumenTiqueteElectronico(){
        
        ResumenFactura resumen = new ResumenFactura();

        CodigoTipoMoneda moneda = new CodigoTipoMoneda();
            BigDecimal totalServGravados;
            BigDecimal totalServExentos;
            BigDecimal totalServExonerado;
            BigDecimal totalMercanciasGravadas;
            BigDecimal totalMercanciasExentas;
            BigDecimal totalMercExonerada;
            BigDecimal totalGravado;
            BigDecimal totalExento;
            BigDecimal totalExonerado;
            BigDecimal totalVenta;
            BigDecimal totalDescuentos;
            BigDecimal totalVentaNeta;
            BigDecimal totalImpuesto;
            BigDecimal totalIVADevuelto;
            BigDecimal totalOtrosCargos;
            BigDecimal totalComprobante;
            
        return resumen;
    }
    
    public DetalleServicio detallesTiqueteElectronico(){
        
        DetalleServicio detalles = new DetalleServicio();
        
        List<LineaDetalle> lineasDetalle = new ArrayList<>();
            for (int i = 0; i < carrito.size(); i++) {
                ArticuloCarrito articulo = carrito.get(i);
                LineaDetalle linea = new LineaDetalle();
                linea.setNumeroLinea(i);
                linea.setCodigoCabys(articulo.getArticulo().getCodigoCabys().getCodigo());
                List<CodigoComercial> codigosComerciales = new ArrayList<>();
                CodigoComercial codigoComercial = new CodigoComercial();
                codigoComercial.setTipo("04");
                codigoComercial.setCodigo(articulo.getArticulo().getCodigoBarra());
                codigosComerciales.add(codigoComercial);
                var Cantidad = BigDecimal.valueOf(carrito.get(i).getCantidad());
                linea.setCantidad(Cantidad);
                linea.setUnidadMedida(articulo.getArticulo().getUnidadMedida());
                linea.setUnidadMedidaComercial(articulo.getArticulo().getUnidadMedidaComercial());
                linea.setDetalle(articulo.getArticulo().getNombre());
                var precioUnitario = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();
                linea.setPrecioUnitario(precioUnitario);
                var montoTotal = precioUnitario.multiply(Cantidad);
                linea.setMontoTotal(montoTotal);
                
                //Descuento/s
                List<Descuento> descuentos = new ArrayList<>();
                //MontoDescuento NaturalezaDescuento
                
                //Impuesto/s
                List<Impuesto> impuestos = new ArrayList<>();
                
                //Codigo CodigoTarifa Tarifa Monto
                
                            
                List<OtroCargo> otrosCargos = new ArrayList<>();
                OtroCargo otroCargo = new OtroCargo();
                
                lineasDetalle.add(linea);
            }
        
        return detalles;
    }
    
    public Encabezado encabezadoTiqueteElectronico(){
        
        AppSettings appSettings = this.settings.getCurrentSettings();
        if(!Objects.equals(appSettings.getEstatus(), Boolean.FALSE)){
            Encabezado encabezado = new Encabezado();
            //Codigo actividad
            String codigoActividad = "521202";
            encabezado.setCodigoActividad(codigoActividad);
            //Clave
            String clave = ""; //Traer de Tributacion
            encabezado.setClave(clave);
            //Consecutivo
            String numeroConsecutivo = ""; //Traer de Registros...
            encabezado.setNumeroConsecutivo(numeroConsecutivo);
            //Fecha y Hora
            LocalDateTime emision = LocalDateTime.now();
            encabezado.setFechaEmision(emision);
            //CondicionVenta
            String condicionVenta = CondicionVenta.OTROS.getCodigo();
            encabezado.setCondicionVenta(condicionVenta);
            //PlazoCredito
            String plazoCredito = "";
            encabezado.setPlazoCredito(plazoCredito);
            //Medios de Pago
            List<MedioPago> medioPago = new ArrayList<>();
            MedioPago medio = new MedioPago();
            medio.setMedioPago("01");
            medioPago.add(medio);
            encabezado.setMedioPago(medioPago);
            //Emisor
            Emisor emisor = new Emisor();
            emisor.setNombre(appSettings.getNombre());
            //Identificacion
            IdentificacionEmisor emisorId = new IdentificacionEmisor();
            emisorId.setNumero(appSettings.getIdentificacion());
            emisorId.setTipo(appSettings.getTipoIdentificion());
            emisor.setIdentificacion(emisorId);
            //NombreComercial
            emisor.setNombreComercial(appSettings.getNombreNegocio());
            //Ubicacion
            Ubicacion emisorUbicacion = new Ubicacion();
            emisorUbicacion.setProvincia(appSettings.getProvincia());
            emisorUbicacion.setCanton(appSettings.getCanton());
            emisorUbicacion.setDistrito(appSettings.getDistrito());
            emisorUbicacion.setBarrio(appSettings.getBarrio());
            emisorUbicacion.setOtrasSenas(appSettings.getDireccionCompleta());
            emisor.setUbicacion(emisorUbicacion);
            //Telefono
            Telefono emisorTelefono = new Telefono();
            emisorTelefono.setCodigoPais(appSettings.getCodigoPais());
            emisorTelefono.setNumeroTelefono(appSettings.getTelefono());
            //Fax
            Fax emisorFax = new Fax();
            emisorFax.setCodigoPais(appSettings.getCodigoPaisFax());
            emisorFax.setNumeroFax(appSettings.getTelefonoFax());
            //CorreoElectronico
            emisor.setCorreoElectronico(appSettings.getCorreoElectronicoTributacion());
            //Guardamos info Emisor en encabezado
            encabezado.setEmisor(emisor);
            
            Receptor receptor = new Receptor();
            if(selectedClient != null){
                receptor.setNombre(selectedClient.getName());
                receptor.setNombreComercial(selectedClient.getName());
                if(!"nacional".equals(selectedClient.getIdType().toLowerCase())){
                    String idNumber = String.valueOf(selectedClient.getIdNumber());
                    receptor.setIdentificacionExtranjero(idNumber);
                }else{
                    String idNumber = String.valueOf(selectedClient.getIdNumber());
                    IdentificacionReceptor id = new IdentificacionReceptor();
                    id.setNumero(idNumber);
                    id.setTipo(selectedClient.getTipoIdentificacion());
                    
                    receptor.setIdentificacion(id);
                }
            }
            //Guardamos info Receptor en encabezado
            encabezado.setReceptor(receptor);
            return encabezado;
        }
        return null;
    }
    
    public List<ArticuloCarrito> getCombinedCarrito() {
        List<ArticuloCarrito> combinedCarrito = new ArrayList<>(); // Initialize a new list

        // Add items from carrito if it's not null
        if (carrito != null) {
            combinedCarrito.addAll(carrito);
        }

        // Add items from carritoDescuento if it's not null
        if (carritoDescuento != null) {
            combinedCarrito.addAll(carritoDescuento);
        }

        return combinedCarrito; // Return the combined list
    }

    
}
