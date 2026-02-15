package Controllers.Tiquetes;

import Services.ComprobanteService;
import Services.CarritoService;
import Controllers.SessionController;
import Controllers.SettingsController;
import Controllers.TipoCambioController;
import Models.AppSettings;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Articulos;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ClientService;
import Services.ComprobantesEmitidosService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.StreamedContent;
import org.primefaces.util.LangUtils;

@Named("crearTiqueteController")
@Data
@ViewScoped
public class CrearTiqueteController implements Serializable {

    // --- Injected Services ---
    @Inject
    private ClientService clientService;
    @Inject
    private CarritoService carritoService;
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
    private ComprobantesEmitidosService comprobantesEmitidosService;
    @Inject
    private ComprobanteService comprobanteService;
    @Inject
    private PrinterService printer;
    @Inject
    private PDFGenerator pdfGenerator;

    // --- Private Fields ---
    private ComprobantesRecibidos newFactura;
    private Clients selectedClient;
    private Clients cliente;
    private String clientsFilter;
    private List<Clients> clients;
    private List<FilterMeta> filterBy;
    private String pdfUrl;
    private StreamedContent pdfStream;
    private String facturaId;

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
        carritoService.calcularVuelto(BigDecimal.valueOf(tipoCambio.getTipoCambioActual().getValorCompra()));
    }

    public void selectArticulo(Articulos articulo) {
        if (articulo == null) {
            return;
        }
        carritoService.setCodigoBarra(articulo.getCodigoBarra());
        carritoService.processCodigoBarra();
    }

    public void selectCliente(Clients cliente) {
        this.cliente = cliente;
    }

    public void removeArticulo(ArticuloCarrito articulo) {
        carritoService.removeArticulo(articulo, currentSession.getCurrentUser());
    }

    public void cancel() {
        carritoService.cancel(currentSession.getCurrentUser());
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
        carritoService.setVuelto(BigDecimal.ZERO);
        carritoService.setDolares(BigDecimal.ZERO);
        carritoService.setColones(BigDecimal.ZERO);
    }

    public void verificarPago() {
        carritoService.calcularVuelto(BigDecimal.valueOf(tipoCambio.getTipoCambioActual().getValorCompra()));
        if (carritoService.getVuelto() != null && carritoService.getVuelto().doubleValue() >= 0) {
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
        carritoService.ajustarInventario(currentSession.getCurrentUser());
        // 2. Crear Comprobante y TODO enviarlo a tributacion
        ComprobantesEmitidos tiqueteElectronico = comprobanteService.crearComprobante(
                settings,
                carritoService.getCarrito(),
                selectedClient,
                cliente,
                currentSession.getCurrentUser()
        );

        if (tiqueteElectronico != null) {

            try {
                pdfGenerator.generarPDFTiqueteElectronico(
                        tiqueteElectronico,
                        settings,
                        carritoService.getCarrito(),
                        cliente,
                        currentSession.getCurrentUser(),
                        carritoService.getPago(),
                        carritoService.getVuelto()
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
                    String msg = "Malformed URL: " + e.getMessage();
                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", msg));
                    alertaService.registrarAlerta("Error Facturación", msg, currentSession.getCurrentUser(), 0, "CrearTiqueteController.facturar", null, null);
                    System.out.println(msg);
                } catch (IOException e) {
                    String msg = "I/O Error while downloading or printing the PDF: " + e.getMessage();
                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", msg));
                    alertaService.registrarAlerta("Error Facturación", msg, currentSession.getCurrentUser(), 0, "CrearTiqueteController.facturar", null, null);
                    System.out.println(msg);
                }

                clearPago();
                carritoService.clear();

            } catch (Exception e) {
                String msg = "Error during PDF generation: " + e.getMessage();
                FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", msg));
                alertaService.registrarAlerta("Error Facturación", msg, currentSession.getCurrentUser(), 0, "CrearTiqueteController.facturar", null, null);
                System.out.println(msg);
            }
        }

        PrimeFaces.current().executeScript("window.close();");
    }

    public String getArticuloPrecioFinal(ArticuloCarrito articulo) {
        return articulo.getArticuloConDescuento().toString();
    }

    public List<ArticuloCarrito> getCarrito() {
        return carritoService.getCarrito();
    }

    public BigDecimal getCantidadArticulo() {
        return carritoService.getCantidadArticulo();
    }

    public void setCantidadArticulo(BigDecimal cantidad) {
        carritoService.setCantidadArticulo(cantidad);
    }

    public String getCodigoBarra() {
        return carritoService.getCodigoBarra();
    }

    public void setCodigoBarra(String codigoBarra) {
        carritoService.setCodigoBarra(codigoBarra);
    }

    public BigDecimal getColones() {
        return carritoService.getColones();
    }

    public void setColones(BigDecimal colones) {
        carritoService.setColones(colones);
    }

    public BigDecimal getDolares() {
        return carritoService.getDolares();
    }

    public void setDolares(BigDecimal dolares) {
        carritoService.setDolares(dolares);
    }

    public BigDecimal getTotalCarrito() {
        return carritoService.getTotalCarrito();
    }

    public void setTotalCarrito(BigDecimal total) {
        carritoService.setTotalCarrito(total);
    }

    public boolean isResetFlag() {
        return carritoService.isResetFlag();
    }
    
    public String getvueltoString(){
        return carritoService.getVueltoString();
    } 

    public void setResetFlag(boolean resetFlag) {
        carritoService.setResetFlag(resetFlag);
    }

    public BigDecimal calculateTotalCarrito() {
        return carritoService.calculateTotalCarrito();
    }

    public BigDecimal calculateTotalCarritoDescuento() {
        return carritoService.calculateTotalCarritoDescuento();
    }

    public BigDecimal calculateTotalCarritoImpuesto() {
        return carritoService.calculateTotalCarritoImpuesto();
    }
}
