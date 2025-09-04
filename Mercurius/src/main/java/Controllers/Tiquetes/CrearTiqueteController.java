package Controllers.Tiquetes;

import Controllers.ArticulosController;
import Controllers.SessionController;
import Controllers.Settings.SettingsDirController;
import Controllers.SettingsController;
import Controllers.TipoCambioController;
import Models.AppSettings;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Articulos;
import Models.Clients;
import Models.ComprobantesV44.ComprobantesEmitidos;
import Models.ComprobantesV44.ComprobantesRecibidos;
import Models.ComprobantesV44.Detalles.CodigoComercial;
import Models.ComprobantesV44.Detalles.Descuento;
import Models.ComprobantesV44.Detalles.DetalleServicio;
import Models.ComprobantesV44.Detalles.Impuesto;
import Models.ComprobantesV44.Detalles.LineaDetalle;
import Models.ComprobantesV44.Detalles.OtroCargo;
import Models.ComprobantesV44.Encabezado.Emisor;
import Models.ComprobantesV44.Encabezado.Encabezado;
import Models.ComprobantesV44.Encabezado.Fax;
import Models.ComprobantesV44.Encabezado.IdentificacionEmisor;
import Models.ComprobantesV44.Encabezado.IdentificacionReceptor;
import Models.ComprobantesV44.Encabezado.MedioPago;
import Models.ComprobantesV44.Encabezado.Receptor;
import Models.ComprobantesV44.Encabezado.Telefono;
import Models.ComprobantesV44.Encabezado.Ubicacion;
import Models.ComprobantesV44.Resumen.ResumenFactura;
import Models.Inventario;
import Models.Articulos.Promocion;
import Models.ComprobantesV44.Encabezado.CorreoElectronicoEmisor;
import Models.ComprobantesV44.Enums.Tipo_CondicionVenta;
import Models.ComprobantesV44.Enums.Tipo_TarifaIVA;
import Models.Registros.Alertas;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ClientService;
import Services.ComprobantesEmitidosService;
import Services.Facturas.DescuentoService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.EmisorService;
import Services.Facturas.EncabezadoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
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
import java.util.stream.Stream;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.StreamedContent;
import org.primefaces.util.LangUtils;

@Named("crearTiqueteController")
@Data
@ViewScoped
public class CrearTiqueteController implements Serializable {

    @Inject
    ArticulosController articuloController;
    @Inject
    private ClientService clientService;
    @Inject
    private InventarioService inventario;
    @Inject
    private SessionController currentSession;
    @Inject
    private TipoCambioController tipoCambio;
    @Inject
    private SettingsController settings;
    @Inject
    private AppSettingsService appSettings;
    @Inject
    private AlertasService alertaService;
    @Inject
    private ComprobantesEmitidosService comprobanteService;
    @Inject
    private EncabezadoService encabezadoService;
    @Inject
    private DetalleServicioService detallesService;
    @Inject
    private ResumenFacturaService resumenService;
    @Inject
    private EmisorService emisorService;
    @Inject
    private ReceptorService receptorService;
    @Inject
    private PDFGenerator pdfGenerator;
    @Inject
    SettingsDirController dirController;
    @Inject
    private PrinterService printer;
    @Inject
    private ImpuestoService impuestoService;
    @Inject
    private LineaDetalleService lineaService;
    @Inject
    private DescuentoService descuentoService;

    private ComprobantesRecibidos newFactura;
    private Clients selectedClient;
    private BigDecimal cantidadArticulo = BigDecimal.ONE;
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
    public void init() {
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

    public void initValores() {
        totalCarrito = new BigDecimal(0);
        colones = new BigDecimal(0);
        dolares = new BigDecimal(0);
        vuelto = new BigDecimal(0);
    }

    public void resetClient() {
        selectedClient = new Clients();
    }
    
    public void revisarCarrito(){ 
        if(!carrito.isEmpty()){
             PrimeFaces.current().executeScript("PF('PagoDialog').show();");
        }else{
            //DISPLAY EMPTY CART WARNING
        }
    }

    public void processCodigoBarra() {
        String codigo = this.codigoBarra;
        BigDecimal cantidad = this.cantidadArticulo;

        if (codigo != null && !codigo.isBlank()) {
            Articulos articulo = articuloController.findArticuloByBarCode(codigo);

            if (articulo != null) {
                if (cantidad.compareTo(BigDecimal.ZERO) == 1) {
                    ArticuloCarrito articuloCarrito = new ArticuloCarrito();
                    articuloCarrito.setArticulo(articulo);
                    articuloCarrito.setCantidad(cantidad);
                    boolean found = false;

                    // Recorremos el carrito para ver si ya existe el artículo Y No es una promo...
                    for (ArticuloCarrito item : carrito) {
                        if (item.getArticulo().getCodigo() == articulo.getCodigo() && !item.isPromo()) {
                            item.setCantidad(item.getCantidad().add(cantidad)); // Sumamos la cantidad existente con la nueva
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
                    cantidadArticulo = BigDecimal.ONE;
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
            List<Promocion> promocionesActivas = articulo.getPromocionesActivas();

            for (Promocion promocion : promocionesActivas) {
                boolean promocionAplicada = promocionAplica(promocion, listaArticulos, articulosPromocionales);
            }
        }

        carrito.addAll(articulosPromocionales);

        // Paso 2: Verificar si las promociones aplicadas siguen siendo válidas
        verificarPromocionesCarrito();

        // Paso 3: Eliminar artículos con cantidad 0
        carrito.removeIf(articulo -> articulo.getCantidad().compareTo(BigDecimal.ZERO) <= 0);
    }

    private boolean promocionAplica(Promocion promocion, List<ArticuloCarrito> articulos, List<ArticuloCarrito> articulosPromocionales) {
        // Verificamos si todos los artículos requeridos por la promoción están en el carrito
        boolean aplicable = promocion.getArticulosCarrito().stream()
                .allMatch(itemPromocion -> articulos.stream()
                .anyMatch(itemCarrito -> itemCarrito.equals(itemPromocion)
                && itemCarrito.getCantidad().compareTo(itemPromocion.getCantidad()) >= 0 // Verifica si hay suficiente cantidad
                && !itemCarrito.isPromo() // Verifica que no esté ya en otra promoción
                )
                );

        if (aplicable) {
            // Aplicamos las modificaciones a los artículos en el carrito
            for (ArticuloCarrito itemPromocion : promocion.getArticulosCarrito()) {
                BigDecimal cantidadRequerida = itemPromocion.getCantidad();
                BigDecimal cantidadTotalDisponible = articulos.stream()
                        .filter(itemCarrito -> itemCarrito.equals(itemPromocion) && !itemCarrito.isPromo())
                        .map(ArticuloCarrito::getCantidad)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Solo aplica la promoción si hay suficiente cantidad
                while (cantidadTotalDisponible.compareTo(cantidadRequerida) >= 0) {
                    // Crear nuevo artículo de promoción con la cantidad requerida
                    ArticuloCarrito itemPromocionAplicado = new ArticuloCarrito();
                    itemPromocionAplicado = itemPromocion;
                    itemPromocionAplicado.setCantidad(cantidadRequerida);
                    itemPromocionAplicado.setPromo(true); // Marcamos el artículo como parte de una promoción
                    List<Promocion> promociones = new ArrayList<>();
                    promociones.add(promocion);
                    itemPromocionAplicado.setPromociones(promociones);
                    itemPromocionAplicado.setDescuento(promocion.getDescuento()); // Establecemos el descuento
                    articulosPromocionales.add(itemPromocionAplicado); // Añadimos el artículo promocionado a la lista temporal

                    // Restar cantidad utilizada de los artículos en el carrito
                    BigDecimal cantidadRestante = cantidadRequerida; // Inicializamos la cantidad que se necesita restar
                    for (ArticuloCarrito itemCarrito : articulos) {
                        // Verificamos que el artículo del carrito sea el mismo que el de la promoción y que no esté ya en otra promoción
                        if (itemCarrito.equals(itemPromocion) && !itemCarrito.isPromo()) {
                            BigDecimal cantidadActual = itemCarrito.getCantidad(); // Obtenemos la cantidad actual del artículo en el carrito

                            if (cantidadActual.compareTo(cantidadRestante) >= 0) {
                                // Si hay suficiente cantidad en el artículo del carrito
                                itemCarrito.setCantidad(cantidadActual.subtract(cantidadRestante)); // Restamos la cantidad requerida
                                break; // Ya hemos consumido la cantidad necesaria, salimos del bucle
                            } else {
                                // Si no hay suficiente cantidad, consumimos todo lo que hay
                                cantidadRestante.subtract(cantidadActual); // Reducimos la cantidad que aún necesitamos restar
                                itemCarrito.setCantidad(BigDecimal.ZERO); // Marcamos el artículo como completamente consumido
                            }
                        }
                    }
                    // Actualizamos la cantidad total disponible restando la cantidad requerida para esta iteración
                    cantidadTotalDisponible.subtract(cantidadRequerida);
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
                .anyMatch(itemCarrito -> itemCarrito.equals(itemPromocion)
                && itemCarrito.getCantidad().compareTo(itemPromocion.getCantidad()) >= 0
                && itemCarrito.isPromo() // Aquí solo se verifican los artículos ya marcados como promociones
                )
                );
    }

    private void revertirPromocion(Promocion promocion, List<ArticuloCarrito> carrito) {
        for (ArticuloCarrito articulo : carrito) {
            List<Promocion> promociones = articulo.getPromociones();
            if (promociones != null && promociones.contains(promocion)) {
                promociones.remove(promocion);
                articulo.setDescuento(BigDecimal.ZERO);
                articulo.setPromo(false);
            }
        }

        // También es buena idea remover los artículos de la promoción para mantener la bidireccionalidad
        List<ArticuloCarrito> articulosPromo = promocion.getArticulosCarrito();
        if (articulosPromo != null) {
            articulosPromo.removeAll(carrito);
        }
    }

    // Método auxiliar para obtener todas las promociones aplicadas actualmente en el carrito
    private List<Promocion> obtenerPromocionesAplicadas() {
        return carrito.stream()
                .filter(ArticuloCarrito::isPromo)
                .flatMap(articulo -> {
                    List<Promocion> promociones = articulo.getPromociones();
                    return promociones != null ? promociones.stream() : Stream.empty();
                })
                .distinct()
                .collect(Collectors.toList());
    }

    public void calcularVuelto() {
        var cambio = this.tipoCambio.getTipoCambioActual().getValorCompra();

        if (dolares != null && colones != null) {
            var totalDolaresEnColones = dolares.multiply(new BigDecimal(cambio));

            pago = totalDolaresEnColones.add(colones);

            var total = calculateTotalCarrito();

            vuelto = total.subtract(pago);
        }
    }

    public String getVueltoString() {
        if (vuelto.doubleValue() > 0) {
            return "Faltante: " + vuelto;
        } else {
            return "Vuelto: " + vuelto.negate();
        }
    }

    public BigDecimal calculateTotalCarrito() {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var articulo = item;
                var cantidad = item.getCantidad();
                var isPromo = item.isPromo();
                var tax = articulo.getArticulo().getCodigoCabys().getImpuesto();
                var taxDecimal = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));

                BigDecimal precioFinal;
                BigDecimal precioUnidad = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();
                BigDecimal cantidadDecimal = cantidad;

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
                var cantidad = item.getCantidad();

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
                var cantidad = item.getCantidad();

                // Calculate subtotal
                BigDecimal subtotal = totalItem.multiply(cantidad);
                total = total.add(subtotal);
            }
        }
        return total;
    }

    public void selectArticulo(Articulos articulo) {
        codigoBarra = articulo.getCodigoBarra();
        processCodigoBarra();
    }

    public void selectCliente(Clients cliente) {
        this.cliente = cliente;
    }

    public void removeArticulo(ArticuloCarrito articulo) {
        if (carrito != null) {
            Iterator<ArticuloCarrito> iterator = carrito.iterator();
            boolean removed = false; // Variable para controlar si se eliminó un artículo

            while (iterator.hasNext() && !removed) {
                ArticuloCarrito articuloCarrito = iterator.next();
                if (articuloCarrito.equals(articulo)) {

                    alertaService.registrarAlerta("Modificacion Carrito", "Cajero " + currentSession.getCurrentUser().getUsername() + " elimino articulo de carrito", currentSession.getCurrentUser(), 0, "CrearTiqueteController.removeArticulo", articulo.toString(), null);

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
        StringBuilder antesBuilder = new StringBuilder();

        antesBuilder.append("Items en Carrito: ");
        if (carrito.isEmpty()) {
            antesBuilder.append("Carrito vacío");
        } else {
            for (ArticuloCarrito articulo : carrito) {
                antesBuilder.append("[Artículo: ")
                        .append(articulo.getArticulo().getNombre())
                        .append(", Cantidad: ")
                        .append(articulo.getCantidad());

                if (articulo.isPromo()) {
                    List<Promocion> promociones = articulo.getPromociones();
                    if (promociones != null && !promociones.isEmpty()) {
                        antesBuilder.append(", Promociones: ");
                        for (Promocion promo : promociones) {
                            antesBuilder.append(promo.getNombre());
                            if (promo.getDescuento() != null) {
                                antesBuilder.append(" (Descuento: ").append(promo.getDescuento()).append(")");
                            }
                            antesBuilder.append("; ");
                        }
                    } else {
                        antesBuilder.append(", Sin promoción");
                    }
                } else {
                    antesBuilder.append(", Sin promoción");
                }

                antesBuilder.append("], ");
            }
        }

        antesBuilder.append("\nCliente: ").append(selectedClient != null ? selectedClient.getName() : "Ninguno");
        antesBuilder.append("\nCantidad Articulo: ").append(cantidadArticulo);
        antesBuilder.append("\nCódigo Barra: ").append(codigoBarra);

        alerta.setMensaje("Eliminacion Articulo en Carrito - Cajero: " + cajero);
        alerta.setTipo("facturacion");
        alerta.setAntes(antesBuilder.toString());
        alerta.setDespues("Empty");
        alerta.setVista(false);

        alertaService.create(alerta);

        // Reset state
        resetFlag = !resetFlag;
        codigoBarra = "";
        cantidadArticulo = BigDecimal.ONE;
        selectedClient = new Clients();
        carrito = new ArrayList<>();
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

    public void openNewFactura() {
        newFactura = new ComprobantesRecibidos();
    }

    public void clearPago() {
        vuelto = BigDecimal.ZERO;
        dolares = BigDecimal.ZERO;
        colones = BigDecimal.ZERO;
    }

    public void verificarPago() {
        calcularVuelto();

        if (vuelto.doubleValue() <= 0) {
            facturar();
        } else {
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
        }
    }

    private void ajustarInventario() {
        for (ArticuloCarrito articulo : carrito) {
            var Articulo = articulo;
            var Cantidad = articulo.getCantidad();

            Inventario movimiento = new Inventario();
            movimiento.setArticulo(Articulo.getArticulo());
            movimiento.setCantidad(Cantidad.negate());
            movimiento.setFechaMovimiento(new Date());
            movimiento.setNotas("Articulo Vendido");
            movimiento.setProcessed(Boolean.TRUE);
            movimiento.setStatus(Boolean.TRUE);
            movimiento.setTipoMovimiento("Venta");
            movimiento.setUnidadesRecomendadasFactura(Cantidad.negate());
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
            var articulo = articuloCarrito;
            var precioFinal = articuloCarrito.getTotalArticulos(); // Total considerando descuentos y impuestos y cantidad

            // Determinar el impuesto y agregar a los totales correspondientes
            var impuesto = BigDecimal.valueOf(articulo.getArticulo().getCodigoCabys().getImpuesto()).divide(BigDecimal.valueOf(100));
            var totalImpuestoArticulo = precioFinal.multiply(impuesto);

            // Determinar si el artículo es gravado o exento
            if (articulo.getArticulo().getCodigoCabys().getImpuesto() != 0) {
                totalServGravados = totalServGravados.add(precioFinal);
                totalImpuesto = totalImpuesto.add(totalImpuestoArticulo);
            } else if (articulo.getArticulo().getCodigoCabys().getImpuesto() == 0) {
                totalServExentos = totalServExentos.add(precioFinal);
            }

            // Calcular total de mercancías
            if (articulo.getArticulo().getCodigoCabys().getImpuesto() != 0) {
                totalMercanciasGravadas = totalMercanciasGravadas.add(precioFinal);
            } else if (articulo.getArticulo().getCodigoCabys().getImpuesto() == 0) {
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
        resumen.setTotalGravado(totalMercanciasGravadas);
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

    public DetalleServicio detallesTiqueteElectronico() {

        DetalleServicio detalles = new DetalleServicio();

        List<OtroCargo> otrosCargos = new ArrayList<>();

        List<LineaDetalle> lineasDetalle = new ArrayList<>();
        for (int i = 0; i < carrito.size(); i++) {
            ArticuloCarrito articulo = new ArticuloCarrito();
            articulo = carrito.get(i);

            LineaDetalle linea = new LineaDetalle();
            linea.setNumeroLinea(i);
            linea.setCodigoCabys(articulo.getArticulo().getCodigoCabys().getCodigo());

            List<CodigoComercial> codigosComerciales = new ArrayList<>();
            CodigoComercial codigoComercial = new CodigoComercial();
            codigoComercial.setTipo("04");
            codigoComercial.setCodigo(articulo.getArticulo().getCodigoBarra());

            codigosComerciales.add(codigoComercial);

            linea.setCodigosComerciales(codigosComerciales);

            var Cantidad = carrito.get(i).getCantidad();
            linea.setCantidad(Cantidad);

            linea.setUnidadMedida(articulo.getArticulo().getUnidadMedida());
            linea.setUnidadMedidaComercial(articulo.getArticulo().getUnidadMedidaComercial());
            linea.setDetalle(articulo.getArticulo().getNombre());

            var precioUnitario = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();
            linea.setPrecioUnitario(precioUnitario);

            var montoTotal = precioUnitario.multiply(Cantidad);
            linea.setMontoTotal(montoTotal);

            linea.setSubTotal(montoTotal);

            //Descuento/s TODO DESCUENTOS GET REPEATED...
            List<Descuento> descuentos = new ArrayList<>();
            if (articulo.isPromo()) {
                List<Promocion> promociones = articulo.getPromociones();
                if (promociones != null && !promociones.isEmpty()) {
                    for (Promocion promocion : promociones) {
                        Descuento descuento = new Descuento();
                        descuento.setMontoDescuento(articulo.getTotalDescuento()); // You might want to split this proportionally later
                        descuento.setNaturalezaDescuento(promocion.getNombre());
                        descuentoService.create(descuento);
                        descuentos.add(descuento);
                    }
                }
            }
            linea.setDescuentos(descuentos);

            //Impuesto/s TODO IMPUESTOS GET REPEATED...
            List<Impuesto> impuestos = new ArrayList<>();
            if (!articulo.getTotalImpuesto().equals(BigDecimal.ZERO)) {
                Impuesto impuesto = new Impuesto();
                String codigoImpuesto = String.valueOf(articulo.getArticulo().getCodigoCabys().getImpuesto());
                impuesto.setCodigo("01");

                Tipo_TarifaIVA tarifa = Tipo_TarifaIVA.getTarifa(codigoImpuesto);
                impuesto.setCodigoTarifaIVA(tarifa.getCodigo());
                impuesto.setTarifa(new BigDecimal(codigoImpuesto));
                impuesto.setMonto(articulo.getTotalImpuesto());

                impuestoService.create(impuesto);
                impuestos.add(impuesto);
            }

            OtroCargo otroCargo = new OtroCargo();
            otrosCargos.add(otroCargo);

            linea.setMontoTotalLinea(montoTotal);

            linea.setImpuestos(impuestos);

            lineaService.create(linea);

            linea.setDetalleServicio(detalles);

            lineasDetalle.add(linea);

        }

        detalles.setLineasDetalle(lineasDetalle);
        detalles.setOtrosCargos(otrosCargos);
        detalles.setStatus(true);

        return detalles;
    }

    public Encabezado encabezadoTiqueteElectronico() {

        AppSettings appSettings = this.settings.getCurrentSettings();
        if (!Objects.equals(appSettings.getEstatus(), Boolean.FALSE)) {
            Encabezado encabezado = new Encabezado();
            //Codigo actividad
            String codigoActividad = "521202";
            encabezado.setCodigoActividadEmisor(codigoActividad);
            //Clave
            String clave = ""; //Traer de Tributacion
            encabezado.setClave(clave);
            //Consecutivo
            String numeroConsecutivo = ""; //Traer de Registros...
            encabezado.setNumeroConsecutivo(numeroConsecutivo);
            //Fecha y Hora
            LocalDateTime emision = LocalDateTime.now().withNano(0);
            encabezado.setFechaEmision(emision);
            //CondicionVenta
            String condicionVenta = Tipo_CondicionVenta.OTROS.getCodigo();
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
            List<CorreoElectronicoEmisor> correosElectronicos = new ArrayList<>();
            CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
            correo.setCorreo(appSettings.getCorreoElectronicoTributacion());
            correo.setEmisor(emisor);

            correosElectronicos.add(correo);

            emisor.setCorreosElectronicos(correosElectronicos);
            //Guardamos info Emisor en encabezado
            encabezado.setEmisor(emisor);
            emisorService.create(emisor);

            Receptor receptor = new Receptor();
            if (selectedClient != null) {
                if (selectedClient.getName() != null) {
                    receptor.setNombre(selectedClient.getName());
                    receptor.setNombreComercial(selectedClient.getName());
                    if (!"nacional".equals(selectedClient.getIdType().toLowerCase())) {
                        String idNumber = String.valueOf(selectedClient.getIdNumber());
                        receptor.setIdentificacionExtranjero(idNumber);
                    } else {
                        String idNumber = String.valueOf(selectedClient.getIdNumber());
                        IdentificacionReceptor id = new IdentificacionReceptor();
                        id.setNumero(idNumber);
                        id.setTipo(selectedClient.getTipoIdentificacion());

                        receptor.setIdentificacion(id);
                    }

                    //Guardamos info Receptor en encabezado
                    encabezado.setReceptor(receptor);
                    receptorService.createIfNotExist(receptor);
                }
            }

            return encabezado;
        }
        return null;
    }

    public String getArticuloPrecioFinal(ArticuloCarrito articulo) {
        return articulo.getArticuloConDescuento().toString();
    }

}
