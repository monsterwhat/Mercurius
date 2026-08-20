package Controllers.Tiquetes;

import Services.ComprobanteService;
import Services.ComprobanteService.CrearComprobanteResult;
import Services.CarritoService;
import Services.LoyaltyService;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import Controllers.SessionController;
import Controllers.SettingsController;
import Controllers.TipoCambioController;
import Models.AppSettings;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Articulos;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Users;
import Models.ComprobantesRecibidos;
import Models.PagoEntry;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.ClientService;
import Services.ComprobantesEmitidosService;
import Services.PrinterService;
import Utils.PDFGenerator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.StreamedContent;
import org.primefaces.util.LangUtils;

@Named("crearTiqueteController")
@Getter @Setter @ToString @EqualsAndHashCode
@ViewScoped
public class CrearTiqueteController implements Serializable {

    // --- Injected Services ---
    @Inject
    @Nonnull
    private ClientService clientService;
    @Inject
    @Nonnull
    private CarritoService carritoService;
    @Inject
    @Nonnull
    private SessionController currentSession;
    @Inject
    @Nonnull
    private TipoCambioController tipoCambio;
    @Inject
    @Nonnull
    private SettingsController settings;
    @Inject
    @Nonnull
    private AppSettingsService appSettings;
    @Inject
    @Nonnull
    private AlertasService alertaService;
    @Inject
    @Nonnull
    private ComprobantesEmitidosService comprobantesEmitidosService;
    @Inject
    @Nonnull
    private ComprobanteService comprobanteService;
    @Inject
    @Nonnull
    private DocumentoStrategyFactory strategyFactory;
    @Inject
    @Nonnull
    private ArticulosService articulosService;
    @Inject
    @Nonnull
    private PrinterService printer;
    @Inject
    @Nonnull
    private PDFGenerator pdfGenerator;
    @Inject
    @Nonnull
    private LoyaltyService loyaltyService;

    // --- Private Fields ---
    @Nullable
    private ComprobantesRecibidos newFactura;
    @Nullable
    private Clients selectedClient;
    @Nullable
    private Clients cliente;
    @Nullable
    private String clientsFilter;
    @Nullable
    private List<Clients> clients;
    @Nonnull
    private List<FilterMeta> filterBy;
    @Nullable
    private String pdfUrl;
    @Nullable
    private StreamedContent pdfStream;
    @Nullable
    private String facturaId;
    @Nonnull
    private String tipoDocumento = "04";

    @Nonnull
    private List<PagoEntry> pagos = new ArrayList<>();

    @Nullable
    private Articulos selectedArticulo;

    @Nullable
    private String authUsername;
    @Nullable
    private String authPassword;
    @Nullable
    private String authorizedBy;
    @Nullable
    private String authTargetAction;
    @Nullable
    private ArticuloCarrito pendingRemoveArticulo;

    // --- Point Redemption Fields ---
    @Nonnull
    private BigDecimal puntosARedimir = BigDecimal.ZERO;
    @Nonnull
    private BigDecimal descuentoPuntos = BigDecimal.ZERO;

    @PostConstruct
    public void init() {
        newFactura = new ComprobantesRecibidos();
        cliente = new Clients();
        selectedClient = new Clients();
        clientsList();
        filterBy = new ArrayList<>();
        facturaId = FacesContext.getCurrentInstance()
                .getExternalContext()
                .getRequestParameterMap()
                .get("facturaId");
        if (facturaId == null) {
            facturaId = UUID.randomUUID().toString(); // unique session per popup
        }
    }

    public void initValores() {
        carritoService.setTotalCarrito(new BigDecimal(0));
        carritoService.setColones(new BigDecimal(0));
        carritoService.setDolares(new BigDecimal(0));
        carritoService.setVuelto(new BigDecimal(0));
        carritoService.setTotalPagado(BigDecimal.ZERO);
        pagos = new ArrayList<>();
        PagoEntry entry = new PagoEntry();
        entry.setMetodoPago("01");
        entry.setMonto(BigDecimal.ZERO);
        pagos.add(entry);
    }

    public void authorize() {
        Users authUser = currentSession.authorizeAction(authUsername, authPassword);
        if (authUser != null) {
            authorizedBy = authUser.getUsername();
            alertaService.registrarAlerta("Autorización Exitosa",
                "Acción: " + authTargetAction + " autorizada por: " + authorizedBy,
                currentSession.getCurrentUser(), 0, "CrearTiqueteController.authorize()",
                null, null);

            if ("remove".equals(authTargetAction) && pendingRemoveArticulo != null) {
                carritoService.removeArticulo(pendingRemoveArticulo, currentSession.getCurrentUser());
                pendingRemoveArticulo = null;
                authTargetAction = null;
            } else if ("override".equals(authTargetAction)) {
                authTargetAction = null;
                facturar();
            }
            authUsername = null;
            authPassword = null;
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Autorización Fallida",
                    "Usuario o contraseña incorrectos"));
        }
    }

    private void resetAuth() {
        authUsername = null;
        authPassword = null;
        authTargetAction = null;
    }

    public boolean hasOverridesInCarrito() {
        for (ArticuloCarrito item : carritoService.getCarrito()) {
            if (item.getPrecioPersonalizado() != null) {
                return true;
            }
        }
        return false;
    }

    public void removeArticuloConAuth(@Nonnull ArticuloCarrito articulo) {
        authTargetAction = "remove";
        pendingRemoveArticulo = articulo;
        authorizedBy = null;
        org.primefaces.PrimeFaces.current().executeScript("PF('AuthDialog').show();");
    }

    public void resetClient() {
        selectedClient = new Clients();
    }

    public void revisarCarrito() {
        carritoService.revisarCarrito();
    }

    public void processCodigoBarra() {
        carritoService.processCodigoBarra();
    }

    public void calcularVuelto() {
        BigDecimal total = BigDecimal.ZERO;
        for (PagoEntry entry : pagos) {
            if (entry.getMonto() != null) {
                total = total.add(entry.getMonto());
            }
        }
        // Keep backward-compat colones/dolares for any remaining bindings
        carritoService.setColones(total);
        carritoService.setDolares(BigDecimal.ZERO);
        carritoService.setTotalPagado(total);
        carritoService.calcularVuelto(tipoCambio.getTipoCambioActual().getValorCompra());
    }

    @Nonnull
    public List<Articulos> completeArticulo(@Nonnull String query) {
        List<Articulos> results = articulosService.findByNameContaining(query);
        return results != null ? results : List.of();
    }

    public void onArticuloSelect() {
        if (selectedArticulo != null) {
            selectArticulo(selectedArticulo);
            selectedArticulo = null;
        }
    }

    public void addPagoEntry() {
        PagoEntry entry = new PagoEntry();
        entry.setMetodoPago("01");
        entry.setMonto(BigDecimal.ZERO);
        pagos.add(entry);
    }

    public void removePagoEntry(@Nonnull PagoEntry entry) {
        pagos.remove(entry);
        calcularVuelto();
    }

    public void selectArticulo(@Nullable Articulos articulo) {
        if (articulo == null) {
            return;
        }
        carritoService.setCodigoBarra(articulo.getCodigoBarra());
        carritoService.processCodigoBarra();
    }

    public void selectCliente(@Nonnull Clients cliente) {
        this.cliente = cliente;
        // Reset point discounts when client changes
        puntosARedimir = BigDecimal.ZERO;
        descuentoPuntos = BigDecimal.ZERO;
        carritoService.setDescuentoPuntos(BigDecimal.ZERO);
    }

    @Nonnull
    public BigDecimal getPuntosBalance() {
        if (selectedClient == null || selectedClient.getPuntosAcumulados() == null) {
            return BigDecimal.ZERO;
        }
        return selectedClient.getPuntosAcumulados();
    }

    public void calcularDescuentoPuntos() {
        if (selectedClient == null || selectedClient.getCode() == 0) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Puntos",
                    "Debe seleccionar un cliente para usar puntos."));
            return;
        }
        BigDecimal available = getPuntosBalance();
        if (puntosARedimir.compareTo(BigDecimal.ZERO) <= 0) {
            descuentoPuntos = BigDecimal.ZERO;
            carritoService.setDescuentoPuntos(BigDecimal.ZERO);
            return;
        }
        if (puntosARedimir.compareTo(available) > 0) {
            puntosARedimir = available;
        }
        // Rate: 1 point = ₡1 discount
        descuentoPuntos = puntosARedimir;
        carritoService.setDescuentoPuntos(descuentoPuntos);
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Puntos",
                "Descuento aplicado: " + descuentoPuntos + " colones"));
    }

    public void removeDescuentoPuntos() {
        puntosARedimir = BigDecimal.ZERO;
        descuentoPuntos = BigDecimal.ZERO;
        carritoService.setDescuentoPuntos(BigDecimal.ZERO);
    }

    @Nonnull
    public BigDecimal getNetoAPagar() {
        BigDecimal total = carritoService.calculateTotalCarrito();
        if (descuentoPuntos.compareTo(BigDecimal.ZERO) > 0) {
            return total.subtract(descuentoPuntos);
        }
        return total;
    }

    public void removeArticulo(@Nonnull ArticuloCarrito articulo) {
        carritoService.removeArticulo(articulo, currentSession.getCurrentUser());
    }

    public void cancel() {
        carritoService.cancel(currentSession.getCurrentUser());
    }

    @Nullable
    public List<Clients> getFilteredClients() {
        if (clientsFilter != null && !clientsFilter.isEmpty()) {
            return clientsList().stream()
                    .filter(profile -> globalFilterFunction(profile, clientsFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return clientsList();
        }
    }

    @Nullable
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
                || (client.getIdNumber() != null && client.getIdNumber().toLowerCase().contains(filterText))
                || String.valueOf(client.getDiscount()).contains(filterText)
                || (client.getPhoneNumber() != null && client.getPhoneNumber().toLowerCase().contains(filterText))
                || String.valueOf(client.isTaxpayer()).contains(filterText)
                || String.valueOf(client.getZoneCode()).contains(filterText);
    }

    public void openNewFactura() {
        newFactura = new ComprobantesRecibidos();
    }

    public void clearPago() {
        carritoService.setVuelto(BigDecimal.ZERO);
        carritoService.setDolares(BigDecimal.ZERO);
        carritoService.setColones(BigDecimal.ZERO);
        carritoService.setTotalPagado(BigDecimal.ZERO);
        pagos.clear();
        PagoEntry entry = new PagoEntry();
        entry.setMetodoPago("01");
        entry.setMonto(BigDecimal.ZERO);
        pagos.add(entry);
        removeDescuentoPuntos();
    }

    public void verificarPago() {
        calcularVuelto();
        if (carritoService.getVuelto() != null && carritoService.getVuelto().doubleValue() >= 0) {
            facturar(); 
        } else {
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "FALTANTE DE PAGO", "NO SE HA CANCELADO EL TOTAL DE LA FACTURA"));
        } 
    }

    public void facturar() {
        if (hasOverridesInCarrito() && authorizedBy == null) {
            authTargetAction = "override";
            org.primefaces.PrimeFaces.current().executeScript("PF('AuthDialog').show();");
            return;
        }
        AppSettings settings = appSettings.returnCurrent();
        if (Objects.equals(settings.getEstatus(), Boolean.FALSE)) {
            return;
        }
        DocumentoStrategy strategy = strategyFactory.forCode(tipoDocumento);
        if (strategy.requiresReceptor()
                && (cliente == null || cliente.getCode() == 0)) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "CLIENTE REQUERIDO",
                    "Para emitir una Factura Electrónica debe seleccionar un cliente."));
            return;
        }
        // 1. Hacer ajustes en inventario
        carritoService.ajustarInventario(currentSession.getCurrentUser());
        // 2. Crear Comprobante y enviar a Hacienda
        List<PagoEntry> pagosParaFactura = pagos;
        if (pagosParaFactura == null || pagosParaFactura.isEmpty()) {
            PagoEntry entry = new PagoEntry();
            entry.setMetodoPago("01");
            entry.setMonto(carritoService.getTotalCarrito());
            pagosParaFactura = List.of(entry);
        }
        CrearComprobanteResult result = comprobanteService.crearComprobante(
                settings,
                carritoService.getCarrito(),
                selectedClient,
                cliente,
                currentSession.getCurrentUser(),
                strategy,
                pagosParaFactura
        );

        if (result != null && result.comprobante != null) {
            ComprobantesEmitidos tiqueteElectronico = result.comprobante;

            // Apply point redemption if points were used
            if (selectedClient != null && selectedClient.getCode() > 0
                    && descuentoPuntos.compareTo(BigDecimal.ZERO) > 0
                    && puntosARedimir.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    loyaltyService.redeemPoints(selectedClient, puntosARedimir);
                } catch (RuntimeException e) {
                    alertaService.registrarAlerta("Error Puntos",
                        "Error al canjear puntos: " + e.getMessage(),
                        currentSession.getCurrentUser(), 0,
                        "CrearTiqueteController.facturar()", null, e.getMessage());
                }
            }
            
            if (result.haciendaEnviado) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Hacienda", result.haciendaMensaje));
            } else if (result.haciendaMensaje != null) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Hacienda", 
                        "Factura creada pero pendiente de envio a Hacienda. Puede enviarla desde la seccion de Consultas."));
            }

            try {
                pdfGenerator.generarPDFTiqueteElectronico(
                        tiqueteElectronico,
                        settings,
                        carritoService.getCarrito(),
                        cliente,
                        currentSession.getCurrentUser(),
                        carritoService.getPago(),
                        carritoService.getVuelto(),
                        pagosParaFactura
                );

                pdfUrl = pdfGenerator.getPdfUrl();

                if (result.haciendaEnviado) {
                    comprobanteService.enviarFacturaACliente(
                        tiqueteElectronico,
                        selectedClient,
                        currentSession.getCurrentUser(),
                        carritoService.getPago(),
                        carritoService.getVuelto(),
                        pagosParaFactura
                    );
                }

                try {
                    String localPath = pdfGenerator.getPdfLocalPath();
                    if (localPath != null) {
                        printer.printPDFFile(new File(localPath));
                    }
                } catch (Exception e) {
                    String msg = "Error printing PDF: " + e.getMessage();
                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", msg));
                    alertaService.registrarAlerta("Error Facturación", msg, currentSession.getCurrentUser(), 0, "CrearTiqueteController.facturar", null, null);
                    alertaService.registrarAlerta("Error", msg, currentSession.getCurrentUser(), 0, "CrearTiqueteController.facturar()", null, msg);
                }

                authorizedBy = null;
                clearPago();
                carritoService.clear();

            } catch (RuntimeException e) {
                String msg = "Error during PDF generation: " + e.getMessage();
                FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", msg));
                alertaService.registrarAlerta("Error Facturación", msg, currentSession.getCurrentUser(), 0, "CrearTiqueteController.facturar", null, null);
                alertaService.registrarAlerta("Error", msg, currentSession.getCurrentUser(), 0, "CrearTiqueteController.facturar()", null, msg);
            }
        }

        PrimeFaces.current().executeScript("window.close();");
    }

    @Nonnull
    public String getArticuloPrecioFinal(@Nonnull ArticuloCarrito articulo) {
        return articulo.getArticuloConDescuento().toString();
    }

    @Nullable
    public List<ArticuloCarrito> getCarrito() {
        return carritoService.getCarrito();
    }

    @Nullable
    public BigDecimal getCantidadArticulo() {
        return carritoService.getCantidadArticulo();
    }

    public void setCantidadArticulo(@Nonnull BigDecimal cantidad) {
        carritoService.setCantidadArticulo(cantidad);
    }

    @Nullable
    public String getCodigoBarra() {
        return carritoService.getCodigoBarra();
    }

    public void setCodigoBarra(@Nonnull String codigoBarra) {
        carritoService.setCodigoBarra(codigoBarra);
    }

    @Nullable
    public BigDecimal getColones() {
        return carritoService.getColones();
    }

    public void setColones(@Nonnull BigDecimal colones) {
        carritoService.setColones(colones);
    }

    @Nullable
    public BigDecimal getDolares() {
        return carritoService.getDolares();
    }

    public void setDolares(@Nonnull BigDecimal dolares) {
        carritoService.setDolares(dolares);
    }

    @Nullable
    public BigDecimal getTotalCarrito() {
        return carritoService.getTotalCarrito();
    }

    public void setTotalCarrito(@Nonnull BigDecimal total) {
        carritoService.setTotalCarrito(total);
    }

    public boolean isResetFlag() {
        return carritoService.isResetFlag();
    }
    
    @Nullable
    public String getvueltoString(){
        return carritoService.getVueltoString();
    } 

    public void setResetFlag(boolean resetFlag) {
        carritoService.setResetFlag(resetFlag);
    }

    @Nonnull
    public BigDecimal calculateTotalCarrito() {
        return carritoService.calculateTotalCarrito();
    }

    @Nonnull
    public BigDecimal calculateTotalCarritoDescuento() {
        return carritoService.calculateTotalCarritoDescuento();
    }

    @Nonnull
    public BigDecimal calculateTotalCarritoImpuesto() {
        return carritoService.calculateTotalCarritoImpuesto();
    }
}
