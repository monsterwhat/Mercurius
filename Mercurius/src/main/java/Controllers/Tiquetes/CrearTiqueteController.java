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
    private List<ArticuloCarrito> carrito;
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
    
}
