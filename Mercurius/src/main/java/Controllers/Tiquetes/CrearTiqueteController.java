package Controllers.Tiquetes;

import Controllers.ArticulosController;
import Controllers.SessionController;
import Controllers.Settings.SettingsDirController;
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
import Models.Comprobantes.Resumen.ResumenFactura;
import Models.Inventario;
import Models.Promocion;
import Models.Registros.Alertas;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ClientService;
import Services.ComprobantesEmitidosService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.EmisorService;
import Services.Facturas.EncabezadoService;
import Services.Facturas.ReceptorService;
import Services.Facturas.ResumenFacturaService;
import Services.InventarioService;
import Services.PrinterService;
import Utils.PDFGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.StreamedContent;
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
    @Inject private AppSettingsService appSettings;
    @Inject private AlertasService alertaService;
    @Inject private ComprobantesEmitidosService comprobanteService;
    @Inject private EncabezadoService encabezadoService;
    @Inject private DetalleServicioService detallesService;
    @Inject private ResumenFacturaService resumenService;
    @Inject private EmisorService emisorService;
    @Inject private ReceptorService receptorService;
    @Inject private PDFGenerator pdfGenerator;
    @Inject SettingsDirController dirController;
    @Inject private PrinterService printer;
    
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
    private BigDecimal totalCarrito, colones, dolares, vuelto, pago;
    private String pdfUrl;
    private StreamedContent pdfStream;
    
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

        if (codigo != null && !codigo.isBlank()) {
            Articulos articulo = articuloController.findArticuloByBarCode(codigo);

            if (articulo != null) {
                if (cantidad > 0) {
                    ArticuloCarrito articuloCarrito = new ArticuloCarrito(articulo, cantidad);
                    boolean found = false;

                    // Recorremos el carrito para ver si ya existe el artículo Y No es una promo...
                    for (ArticuloCarrito item : carrito) {
                        if (item.getArticulo().getCodigo() == articulo.getCodigo() && !item.isPromo()) {
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
                    procesarPromocionesCarrito();
                    
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

    private void procesarPromocionesCarrito() {
        List<ArticuloCarrito> listaArticulos = new ArrayList<>(carrito); // Crear una copia para evitar ConcurrentModificationException
        List<ArticuloCarrito> articulosPromocionales = new ArrayList<>(); // Lista para almacenar artículos promocionales

        // Paso 1: Verificar y aplicar promociones aplicables
        for (ArticuloCarrito articulo : listaArticulos) {
            List<Promocion> promocionesActivas = articulo.getArticulo().getPromocionesActivas();

            for (Promocion promocion : promocionesActivas) {
                boolean promocionAplicada = promocionAplica(promocion, listaArticulos, articulosPromocionales);
            }
        }

        carrito.addAll(articulosPromocionales);

        // Paso 2: Verificar si las promociones aplicadas siguen siendo válidas
        verificarPromocionesCarrito();

        // Paso 3: Eliminar artículos con cantidad 0
        carrito.removeIf(articulo -> articulo.getCantidad() <= 0);
    }

    private boolean promocionAplica(Promocion promocion, List<ArticuloCarrito> articulos, List<ArticuloCarrito> articulosPromocionales) {
        // Verificamos si todos los artículos requeridos por la promoción están en el carrito
        boolean aplicable = promocion.getArticulosCarrito().stream()
            .allMatch(itemPromocion -> articulos.stream()
                .anyMatch(itemCarrito -> itemCarrito.getArticulo().equals(itemPromocion.getArticulo()) 
                    && itemCarrito.getCantidad() >= itemPromocion.getCantidad() // Verifica si hay suficiente cantidad
                    && !itemCarrito.isPromo() // Verifica que no esté ya en otra promoción
                )
            );

        if (aplicable) {
            // Aplicamos las modificaciones a los artículos en el carrito
            for (ArticuloCarrito itemPromocion : promocion.getArticulosCarrito()) {
                double cantidadRequerida = itemPromocion.getCantidad();
                double cantidadTotalDisponible = articulos.stream()
                    .filter(itemCarrito -> itemCarrito.getArticulo().equals(itemPromocion.getArticulo()) && !itemCarrito.isPromo())
                    .mapToDouble(ArticuloCarrito::getCantidad)
                    .sum();

                // Solo aplica la promoción si hay suficiente cantidad
                while (cantidadTotalDisponible >= cantidadRequerida) {
                    // Crear nuevo artículo de promoción con la cantidad requerida
                    ArticuloCarrito itemPromocionAplicado = new ArticuloCarrito(itemPromocion.getArticulo(), cantidadRequerida);
                    itemPromocionAplicado.setPromo(true); // Marcamos el artículo como parte de una promoción
                    itemPromocionAplicado.setPromocion(promocion);
                    itemPromocionAplicado.setDescuento(promocion.getDescuento()); // Establecemos el descuento
                    articulosPromocionales.add(itemPromocionAplicado); // Añadimos el artículo promocionado a la lista temporal

                    // Restar cantidad utilizada de los artículos en el carrito
                    double cantidadRestante = cantidadRequerida; // Inicializamos la cantidad que se necesita restar
                    for (ArticuloCarrito itemCarrito : articulos) {
                        // Verificamos que el artículo del carrito sea el mismo que el de la promoción y que no esté ya en otra promoción
                        if (itemCarrito.getArticulo().equals(itemPromocion.getArticulo()) && !itemCarrito.isPromo()) {
                            double cantidadActual = itemCarrito.getCantidad(); // Obtenemos la cantidad actual del artículo en el carrito

                            if (cantidadActual >= cantidadRestante) {
                                // Si hay suficiente cantidad en el artículo del carrito
                                itemCarrito.setCantidad(cantidadActual - cantidadRestante); // Restamos la cantidad requerida
                                break; // Ya hemos consumido la cantidad necesaria, salimos del bucle
                            } else {
                                // Si no hay suficiente cantidad, consumimos todo lo que hay
                                cantidadRestante -= cantidadActual; // Reducimos la cantidad que aún necesitamos restar
                                itemCarrito.setCantidad(0.0); // Marcamos el artículo como completamente consumido
                            }
                        }
                    }
                    // Actualizamos la cantidad total disponible restando la cantidad requerida para esta iteración
                    cantidadTotalDisponible -= cantidadRequerida;
                }
            }
        }
        return aplicable;
    }

    private void verificarPromocionesCarrito() {
        // Paso 2: Verificar si las promociones aplicadas siguen siendo válidas
        List<Promocion> promocionesAplicadas = obtenerPromocionesAplicadas(); // Método que retorna todas las promociones aplicadas

        for (Promocion promocion : promocionesAplicadas) {
            boolean promocionSigueValida = promocionSigueSiendoValida(promocion);

            if (!promocionSigueValida) {
                // Si la promoción ya no es válida, revertimos los descuentos aplicados
                revertirPromocion(promocion, carrito);
                System.out.println("Promocion revertida: " + promocion.getNombre());
            }
        }
    }

    private boolean promocionSigueSiendoValida(Promocion promocion) {
        // Verificar si la promoción sigue siendo válida con la situación actual del carrito
        return promocion.getArticulosCarrito().stream()
            .allMatch(itemPromocion -> carrito.stream()
                .anyMatch(itemCarrito -> itemCarrito.getArticulo().equals(itemPromocion.getArticulo()) 
                    && itemCarrito.getCantidad() >= itemPromocion.getCantidad() 
                    && itemCarrito.isPromo() // Aquí solo se verifican los artículos ya marcados como promociones
                )
            );
    }

    private void revertirPromocion(Promocion promocion, List<ArticuloCarrito> carrito) {
        for (ArticuloCarrito articulo : carrito) {
            if (articulo.getPromocion() == promocion) {
                // Revertimos el descuento y el estado de promoción del artículo
                articulo.setPromo(false);
                articulo.setDescuento(BigDecimal.ZERO);
                articulo.setPromocion(null); // Limpiamos la referencia a la promoción
            }
        }
    }

    // Método auxiliar para obtener todas las promociones aplicadas actualmente en el carrito
    private List<Promocion> obtenerPromocionesAplicadas() {
        return carrito.stream()
            .filter(ArticuloCarrito::isPromo)
            .map(ArticuloCarrito::getPromocion)
            .distinct()
            .collect(Collectors.toList());
    }
    
    public void calcularVuelto(){
        var cambio = this.tipoCambio.getTipoCambioActual().getValorCompra();
        
        if(dolares != null && colones != null){
            var totalDolaresEnColones = dolares.multiply(new BigDecimal(cambio));
            
            pago = totalDolaresEnColones.add(colones);
        
            var total = calculateTotalCarrito();
            
            vuelto = total.subtract(pago);
        }
    }
    
    public String getVueltoString(){
        if(vuelto.doubleValue() > 0){
            return "Faltante: " + vuelto;
        }else{
            return "Vuelto: " + vuelto.negate();
        }
    }
    
    public BigDecimal calculateTotalCarrito() {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                var isPromo = item.isPromo();
                var tax = articulo.getCodigoCabys().getImpuesto();
                var taxDecimal = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));

                BigDecimal precioFinal;
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioConUtilidad();
                BigDecimal cantidadDecimal = BigDecimal.valueOf(cantidad);

                // Determine final price based on promotional status
                if (isPromo) {
                    precioFinal = item.getArticuloConDescuento();  // Price after discount INCLUDES TAXES...
                } else {
                    precioFinal = precioUnidad;  // Regular price
                    // Calculate total tax based on the final price after discount
                    var totalImpuestos = precioFinal.multiply(taxDecimal);

                    // Add tax to the final price to get the total price for the item
                    precioFinal = precioFinal.add(totalImpuestos);
                }                

                // Calculate subtotal for this item based on quantity
                BigDecimal subtotal = precioFinal.multiply(cantidadDecimal);

                // Add subtotal to the overall total
                total = total.add(subtotal);
            }
        }
        return total;
    }
    
    // Helper method to calculate total from a given list
    public BigDecimal calculateTotalCarritoDescuento() {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                
                var totalItem = item.getTotalDescuento();
                var cantidad = BigDecimal.valueOf(item.getCantidad());
                
                // Calculate subtotal
                BigDecimal subtotal = totalItem.multiply(cantidad);
                total = total.add(subtotal);
            }
        }
        return total;
    }
    
    // Helper method to calculate total from a given list
    public BigDecimal calculateTotalCarritoImpuesto() {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                
                var totalItem = item.getTotalImpuesto();
                var cantidad = BigDecimal.valueOf(item.getCantidad());
                
                // Calculate subtotal
                BigDecimal subtotal = totalItem.multiply(cantidad);
                total = total.add(subtotal);
            }
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
    
    public void removeArticulo(ArticuloCarrito articulo) {
        if (carrito != null) {
            Iterator<ArticuloCarrito> iterator = carrito.iterator();
            boolean removed = false; // Variable para controlar si se eliminó un artículo

            while (iterator.hasNext() && !removed) {
                ArticuloCarrito articuloCarrito = iterator.next();
                if (articuloCarrito.equals(articulo)) {
                    
                    Alertas alerta = new Alertas();
                    alerta.setMensaje("Cajero " + currentSession.getCurrentUser().getUsername() + " elimino articulo de carrito");
                    alerta.setTipo("Modificacion Carrito");
                    alerta.setAntes(articulo.toString());
                    alerta.setDespues("");
                    alerta.setVista(false);
                    
                    alertaService.create(alerta);
                    
                    iterator.remove(); // Elimina el artículo
                    removed = true; // Marca que se ha realizado la eliminación
                }
            }
            procesarPromocionesCarrito();
        }
    }
    
    public void cancel() {
        
        String cajero = currentSession.getCurrentUser().getUsername();

        Alertas alerta = new Alertas();

        // Using StringBuilder to construct the message details
        StringBuilder antesBuilder = new StringBuilder();

        // Constructing the "Antes" part
        antesBuilder.append("Items en Carrito: ");
        if (carrito.isEmpty()) {
            antesBuilder.append("Carrito vacío");
        } else {
            for (ArticuloCarrito articulo : carrito) {
                antesBuilder.append("[Artículo: ")
                             .append(articulo.getArticulo().getNombre())
                             .append(", Cantidad: ")
                             .append(articulo.getCantidad());

                // Check if the item is part of a promotion
                if (articulo.isPromo()) {
                    antesBuilder.append(", Promoción: ")
                                 .append(articulo.getPromocion().getDescuento());
                } else {
                    antesBuilder.append(", Sin promoción");
                }
                antesBuilder.append("], ");
            }
        }

        antesBuilder.append("\nCliente: ").append(selectedClient != null ? selectedClient.getName() : "Ninguno");
        antesBuilder.append("\nCantidad Articulo: ").append(cantidadArticulo);
        antesBuilder.append("\nCódigo Barra: ").append(codigoBarra);

        // Set the fields for alerta
        alerta.setMensaje("Eliminacion Articulo en Carrito - Cajero: " + cajero);
        alerta.setTipo("facturacion");
        alerta.setAntes(antesBuilder.toString());
        alerta.setDespues("Empty");
        alerta.setVista(false);

        // Save the alerta using the service
        alertaService.create(alerta);

        // Clear the carrito and reset fields
        resetFlag = !resetFlag; // Toggle the reset flag
        codigoBarra = "";
        cantidadArticulo = 1;
        selectedClient = new Clients(); // Reset the client
        carrito = new ArrayList<>(); // Clear the carrito
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
     
    public void clearPago(){
        vuelto = BigDecimal.ZERO;
        dolares = BigDecimal.ZERO;
        colones = BigDecimal.ZERO;
    }
    
    public void verificarPago(){
        calcularVuelto();
        
        if(vuelto.doubleValue() <= 0){
            facturar(); 
        }else{
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "FALTANTE DE PAGO", "NO SE HA CANCELADO EL TOTAL DE LA FACTURA"));
        }
    }
    
    public void facturar() {
        
        AppSettings settings = appSettings.returnCurrent();
        if (Objects.equals(settings.getEstatus(), Boolean.FALSE)) {
            return;
        }

        // 1. Hacer ajustes en inventario
        ajustarInventario();

        // 2. Crear Comprobante y enviarlo a tributacion
        ComprobantesEmitidos tiqueteElectronico = crearComprobante();

        if (tiqueteElectronico != null) {
            
            try {
                pdfGenerator.generarPDFTiqueteElectronico(
                    tiqueteElectronico, 
                    settings, 
                    carrito, 
                    cliente, 
                    currentSession.getCurrentUser(), 
                    pago, 
                    vuelto
                );
                
            pdfUrl = pdfGenerator.getPdfUrl();
            
            try {
                URL url = new URL(pdfUrl);
                File fileToPrint = new File("tiqueteElectronico_104.pdf");
                try (InputStream in = url.openStream()) {
                    Files.copy(in, fileToPrint.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                printer.printPDFFile(fileToPrint);
            } catch (MalformedURLException e) {
                System.out.println("Malformed URL: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("I/O Error while downloading or printing the PDF: " + e.getMessage());
            }

                
            clearPago();
            carrito.clear();
                
            } catch (Exception e) {
                System.out.println("Error during PDF generation: " + e.getMessage());
            }
            return;
        }
    }

    
    private void ajustarInventario() {
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
            movimiento.setUnidadesRecomendadasFactura(Cantidad * -1);
            movimiento.setUsuario(currentSession.getCurrentUser());

            inventario.update(movimiento);
        }
    }
    
    private ComprobantesEmitidos crearComprobante() {
        Encabezado encabezado = encabezadoTiqueteElectronico();
        encabezadoService.create(encabezado);

        DetalleServicio detalles = detallesTiqueteElectronico();
        detallesService.create(detalles);

        ResumenFactura resumen = resumenTiqueteElectronico();
        resumenService.create(resumen);

        ComprobantesEmitidos tiqueteElectronico = new ComprobantesEmitidos();
        tiqueteElectronico.setEncabezado(encabezado);
        tiqueteElectronico.setDetalles(detalles);
        tiqueteElectronico.setResumen(resumen);
        tiqueteElectronico.setUser(currentSession.getCurrentUser());

        comprobanteService.create(tiqueteElectronico);
        return tiqueteElectronico;
    }
    
    public ResumenFactura resumenTiqueteElectronico() {
        // Inicializar variables
        BigDecimal totalServGravados = BigDecimal.ZERO;
        BigDecimal totalServExentos = BigDecimal.ZERO;
        BigDecimal totalServExonerado = BigDecimal.ZERO;
        BigDecimal totalMercanciasGravadas = BigDecimal.ZERO;
        BigDecimal totalMercanciasExentas = BigDecimal.ZERO;
        BigDecimal totalMercExonerada = BigDecimal.ZERO;
        BigDecimal totalGravado = BigDecimal.ZERO;
        BigDecimal totalExento = BigDecimal.ZERO;
        BigDecimal totalExonerado = BigDecimal.ZERO;
        BigDecimal totalVenta = BigDecimal.ZERO;
        BigDecimal totalDescuentos = BigDecimal.ZERO;
        BigDecimal totalVentaNeta = BigDecimal.ZERO;
        BigDecimal totalImpuesto = BigDecimal.ZERO;
        BigDecimal totalIVADevuelto = BigDecimal.ZERO;
        BigDecimal totalOtrosCargos = BigDecimal.ZERO;
        BigDecimal totalComprobante = BigDecimal.ZERO;

        // Recorrer el carrito para calcular totales
        for (ArticuloCarrito articuloCarrito : carrito) {
            var articulo = articuloCarrito.getArticulo();
            var precioFinal = articuloCarrito.getTotalArticulos(); // Total considerando descuentos y impuestos y cantidad

            // Determinar el impuesto y agregar a los totales correspondientes
            var impuesto = BigDecimal.valueOf(articulo.getCodigoCabys().getImpuesto()).divide(BigDecimal.valueOf(100));
            var totalImpuestoArticulo = precioFinal.multiply(impuesto);

            // Determinar si el artículo es gravado o exento
            if (articulo.getCodigoCabys().getImpuesto() != 0) {
                totalServGravados = totalServGravados.add(precioFinal);
                totalImpuesto = totalImpuesto.add(totalImpuestoArticulo);
            } else if (articulo.getCodigoCabys().getImpuesto() == 0) {
                totalServExentos = totalServExentos.add(precioFinal);
            }

            // Calcular total de mercancías
            if (articulo.getCodigoCabys().getImpuesto() != 0) {
                totalMercanciasGravadas = totalMercanciasGravadas.add(precioFinal);
            } else if (articulo.getCodigoCabys().getImpuesto() == 0) {
                totalMercanciasExentas = totalMercanciasExentas.add(precioFinal);
            }

            // Calcular totales
            totalVenta = totalVenta.add(precioFinal);
            totalDescuentos = totalDescuentos.add(articuloCarrito.getTotalDescuento());
        }

        // Calcular total neto de venta
        totalVentaNeta = totalVenta.subtract(totalDescuentos);
        totalComprobante = totalVentaNeta.add(totalImpuesto);

        // Crear objeto ResumenFactura y asignar valores
        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalServGravados(totalServGravados);
        resumen.setTotalServExentos(totalServExentos);
        resumen.setTotalServExonerado(totalServExonerado);
        resumen.setTotalMercanciasGravadas(totalMercanciasGravadas);
        resumen.setTotalMercanciasExentas(totalMercanciasExentas);
        resumen.setTotalMercExonerada(totalMercExonerada);
        resumen.setTotalGravado(totalGravado);
        resumen.setTotalExento(totalExento);
        resumen.setTotalExonerado(totalExonerado);
        resumen.setTotalVenta(totalVenta);
        resumen.setTotalDescuentos(totalDescuentos);
        resumen.setTotalVentaNeta(totalVentaNeta);
        resumen.setTotalImpuesto(totalImpuesto);
        resumen.setTotalIVADevuelto(totalIVADevuelto);
        resumen.setTotalOtrosCargos(totalOtrosCargos);
        resumen.setTotalComprobante(totalComprobante);

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
                for (ArticuloCarrito articuloCarrito : carrito) {
                    if(articuloCarrito.isPromo()){
                        Descuento descuento = new Descuento();
                        descuento.setLineaDetalle(linea);
                        descuento.setMontoDescuento(articuloCarrito.getTotalDescuento());
                        descuento.setNaturalezaDescuento(articuloCarrito.getPromocion().getNombre());
                        descuentos.add(descuento);
                    }
                }
                
                linea.setDescuentos(descuentos);
                
                //Impuesto/s
                List<Impuesto> impuestos = new ArrayList<>();
                for (ArticuloCarrito articuloCarrito : carrito) {
                    if(!articuloCarrito.getTotalImpuesto().equals(BigDecimal.ZERO)){
                        Impuesto impuesto = new Impuesto();
                        impuesto.setCodigo("");
                        impuesto.setCodigoTarifa("");
                        impuesto.setExoneracion(null);
                        impuesto.setFactorIVA(BigDecimal.ZERO);
                        impuesto.setLineaDetalle(linea);
                        impuesto.setMonto(vuelto);
                        impuesto.setMontoExportacion(BigDecimal.ZERO);
                        impuesto.setTarifa(BigDecimal.ZERO);
                        impuestos.add(impuesto);
                    }
                }
                
                linea.setImpuestos(impuestos);
                            
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
            medio.setComprobante(encabezado);
            medioPago.add(medio);
            encabezado.setMedioPago(medioPago);
            //Emisor
            Emisor emisor = new Emisor();
            emisor.setNombre(appSettings.getNombre());
            //Identificacion
            IdentificacionEmisor emisorId = new IdentificacionEmisor();
            emisorId.setNumero(appSettings.getIdentificacion());
            emisorId.setTipo(appSettings.getTipoIdentificacion());
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
            emisorService.create(emisor);
            
            Receptor receptor = new Receptor();
            if(selectedClient.getName() != null){
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
            receptorService.create(receptor);
            
            return encabezado;
        }
        return null;
    }
    
    public String getArticuloPrecioFinal(ArticuloCarrito articulo){
        return articulo.getArticuloConDescuento().toString();
    }
    
    
    
}
