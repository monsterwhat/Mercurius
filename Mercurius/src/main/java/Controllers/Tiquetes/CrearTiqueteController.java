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
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Meta;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    @Inject private AppSettingsService appSettings;
    @Inject private AlertasService alertaService;
    @Inject private ComprobantesEmitidosService comprobanteService;
    @Inject private EncabezadoService encabezadoService;
    @Inject private DetalleServicioService detallesService;
    @Inject private ResumenFacturaService resumenService;
    @Inject private EmisorService emisorService;
    @Inject private ReceptorService receptorService;
    
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
                if (promocionAplicada) {
                    System.out.println("Promoción aplicada: " + promocion.toString());
                }
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
                    precioFinal = getArticuloConDescuento(item);  // Price after discount INCLUDES TAXES...
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
                
                var totalItem = getTotalDescuento(item);
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
                
                var totalItem = getTotalImpuesto(item);
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
    
    public void facturar(){
        
        AppSettings settings = appSettings.returnCurrent();;
        if(Objects.equals(settings.getEstatus(), Boolean.FALSE)){
            return ;
        }
        
        //1. Hacer ajustes en inventario
        ajustarInventario();
        
        //2. Crear Comprobante y enviarlo a tributacion
        ComprobantesEmitidos tiqueteElectronico = crearComprobante();
        
        if (tiqueteElectronico != null) {
            generarPDF(tiqueteElectronico, settings);
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
    
    private void generarPDF(ComprobantesEmitidos tiqueteElectronico, AppSettings settings) {
        // PDF generation logic here
        try {
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

            document.add(new Paragraph(settings.getNombreNegocio(), font));
            document.add(new Paragraph(settings.getNombre(), font));
            document.add(new Paragraph(settings.getIdentificacion(), font));
            document.add(new Paragraph(settings.getTelefono(), font));
            document.add(new Paragraph(settings.getCorreoElectronicoTributacion(), font));
            document.add(new Paragraph(settings.getDireccionCompleta(), font));

            document.add(new Paragraph("",font));//HERE ADD FECHA LIKE DD/MM/YYYY HOUR:24HRCLOCK
            document.add(new Paragraph("TIQUETE ELECTRONICO",font));//Tipo de Factura (TIQUETE ELECTRONICO)
            document.add(new Paragraph("CONSECUTIVO: " + tiqueteElectronico.getEncabezado().getNumeroConsecutivo(),font));//CONSECUTIVO
            document.add(new Paragraph("CLAVE NUMERICA: "+ tiqueteElectronico.getEncabezado().getClave(),font));//CLAVE NUMERICA
            document.add(new Paragraph("NUMERO: "+ tiqueteElectronico.getId(),font));//NUMERO TIQUETE ELECTRONICO
            document.add(new Paragraph(cliente.getName(),font));//NOMBRE CLIENTE
            document.add(new Paragraph(currentSession.getUsername(),font));//CAJERO

            PdfPTable table = new PdfPTable(4); // 4 columns
            table.addCell("NOMBRE ART");
            table.addCell("CANTIDAD");
            table.addCell("P.VENTA");
            table.addCell("TOTAL");

            for (ArticuloCarrito articulo : carrito) {
                table.addCell(articulo.getArticulo().getNombre());
                table.addCell(articulo.getCantidad().toString());
                table.addCell(articulo.getTotalArticulo().toString());
                table.addCell(articulo.getTotalArticulos().toString());
            }

            document.add(table);

            document.add(new Paragraph("****Ultima Linea****",font)); //Aviso ultima linea de articulos

            document.add(new Paragraph("TOTAL GRAVADO: " + tiqueteElectronico.getResumen().getTotalGravado(),font));//TOTAL GRAVADO
            document.add(new Paragraph("TOTAL EXENTO: " + tiqueteElectronico.getResumen().getTotalExento(),font));//TOTAL EXENTO
            document.add(new Paragraph("TOTAL VENTA: " + tiqueteElectronico.getResumen().getTotalVenta(),font));//TOTAL VENTA
            document.add(new Paragraph("TOTAL DESCUENTOS: " + tiqueteElectronico.getResumen().getTotalDescuentos(),font));//TOTAL DESCUENTOS
            document.add(new Paragraph("TOTAL VENTA NETA: " + tiqueteElectronico.getResumen().getTotalVentaNeta(),font));//TOTAL VENTA NETA
            document.add(new Paragraph("TOTAL IMPUESTOS: " + tiqueteElectronico.getResumen().getTotalImpuesto(),font));//TOTAL IMPUESTOS
            document.add(new Paragraph("TOTAL COMPROBANTE: " + tiqueteElectronico.getResumen().getTotalComprobante(),font));//TOTAL COMPROBANTE

            document.add(new Paragraph("PAGA CON: " + pago,font));//PAGA CON:
            document.add(new Paragraph("VUELTO: " + vuelto,font));//VUELTO:

            document.add(new Paragraph("* Articulo Exento",font));//* Articulo Exento
            document.add(new Paragraph("Autorizado mediante resolucion No. DGT-R033-2019 del dia 20 de junio de 2019. Version FE 4.3",font));//Autorizado mediante resolucion No. DGT-R033-2019 del dia 20 de junio de 2019. Version FE 4.3

            document.close();

            FacesContext facesContext = FacesContext.getCurrentInstance();
            HttpServletResponse response = (HttpServletResponse) facesContext.getExternalContext().getResponse();

            response.setContentType("application/pdf; charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentLength(baos.size());
            response.setHeader("Content-disposition", "attachment; filename=tiqueteElectronico.pdf");

            try (ServletOutputStream outputStream = response.getOutputStream()) {
                baos.writeTo(outputStream);
                outputStream.flush();
            }
            
            facesContext.responseComplete();
            clearPago();
            carrito.clear();
            PrimeFaces.current().executeScript("PF('PagoDialog').hide(); PF('CrearTiqueteDialog').hide();");
            
        } catch (DocumentException | IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }
    
    public String getContextPath() {
        ExternalContext externalContext = FacesContext.getCurrentInstance().getExternalContext();
        HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
        return request.getContextPath();
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
            medio.setComprobante(encabezado);
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
        return getArticuloConDescuento(articulo).toString();
    }
    
    //TODO ADD METHOD THAT RETURNS TOTAL OF PRECIO BASED ON THE AMOUNT OF ITEMS ONLY FOR NON PROMO
    //ALSO ADD A FIELD ON THE TABLE THAT SHOWS THIS VALUE AND KEEP THE ONE THAT SHOWS THE PRICE FOR UNIT
    
    public BigDecimal getArticuloConDescuento(ArticuloCarrito articulo) {
        // Get the Articulo and necessary values
        var Articulo = articulo.getArticulo();
        var descuento = articulo.getDescuento();
        var precioConUtilidad = Articulo.getLastPrecio().getPrecioConUtilidad();
        double tax = Articulo.getCodigoCabys().getImpuesto();

        // Calculate the tax percentage and discount percentage
        var taxPercentage = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));
       
        BigDecimal applicableTax, precioFinal;
        
        if(descuento != null){
            var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));

            // Calculate the total discount and new price after discount
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);

            var newPrecio = precioConUtilidad.subtract(descuentoTotal); // Subtract discount
            
            // Calculate the applicable tax on the new price after discount
            applicableTax = newPrecio.multiply(taxPercentage);
            
            // Calculate the final price
            precioFinal = newPrecio.add(applicableTax);
        }else{
            // Calculate the applicable tax on the new price after discount
            applicableTax = precioConUtilidad.multiply(taxPercentage);
            // Calculate the final price
            precioFinal = precioConUtilidad.add(applicableTax);
        }
        
        return precioFinal;
    }
    
    public BigDecimal getTotalDescuento(ArticuloCarrito articulo) {
        if(articulo.getDescuento() == null){
            return BigDecimal.ZERO;
        }
        // Obtener el Articulo y los valores necesarios
        var Articulo = articulo.getArticulo();
        var descuento = articulo.getDescuento();
        var precioConUtilidad = Articulo.getLastPrecio().getPrecioConUtilidad();

        // Calcular el porcentaje de descuento
        var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));

        // Calcular el descuento total
        var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);

        return descuentoTotal; // Retornar solo el total del descuento
    }

    public BigDecimal getTotalImpuesto(ArticuloCarrito articulo) {
        // Obtener el Articulo y los valores necesarios
        var Articulo = articulo.getArticulo();
        var descuento = articulo.getDescuento();
        
        var precioConUtilidad = Articulo.getLastPrecio().getPrecioConUtilidad();
        double tax = Articulo.getCodigoCabys().getImpuesto();
        
        var applicableTax = BigDecimal.ZERO;
        
        // Calcular el porcentaje de impuesto
        var taxPercentage = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));
        if(descuento != null){
            var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));

            // Calcular el descuento total
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);

            // Calcular el nuevo precio después del descuento
            var totalConDescuento = precioConUtilidad.subtract(descuentoTotal);
            
            applicableTax = totalConDescuento.multiply(taxPercentage);

        }else{
            // Calcular el impuesto aplicable sobre el nuevo precio después del descuento
            applicableTax = precioConUtilidad.multiply(taxPercentage);
        }

        return applicableTax; // Retornar solo el total del impuesto
    }

}
