package Controllers;

import Controllers.SessionController;
import Controllers.Tiquetes.CrearTiqueteController;
import Models.Articulos.Articulos;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Correos.ReporteProgramado;
import Models.Detalles.CodigoComercial;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Receptor;
import Services.AlertasService;
import Services.ArticulosService;
import Services.CarritoService;
import Services.ClientService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.Correos.ReportesProgramadosService;
import Services.HaciendaApiService;
import Services.HaciendaSigner;
import Services.NotaCreditoService;
import Models.NotaCredito;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import lombok.Data;
import org.primefaces.PrimeFaces;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Data
@Named("consultasController")
@ViewScoped
public class ConsultasController implements Serializable {

    @Inject
    private ComprobantesEmitidosService comprobantesService;
    
    @Inject
    private ReportesProgramadosService reportesService;
    
    @Inject
    private AlertasService alertasService;
    
    @Inject
    private SessionController sessionController;
    
    @Inject
    private CrearTiqueteController crearTiqueteController;
    
    @Inject
    private HaciendaApiService haciendaApiService;
    
    @Inject
    private HaciendaSigner haciendaSigner;
    
    @Inject
    private ComprobanteService comprobanteService;
    
    @Inject
    private NotaCreditoService notaCreditoService;

    @Inject
    private ClientService clientService;

    @Inject
    private ArticulosService articulosService;

    @Inject
    private CarritoService carritoService;

    private List<ComprobantesEmitidos> facturasPendientes;
    private List<ComprobantesEmitidos> facturasAceptadas;
    private List<ComprobantesEmitidos> facturasRechazadas;
    private ReporteProgramado proximoEnvio;
    private Long contadorPendientes;
    private Long contadorAceptadas;
    private Long contadorRechazadas;
    private String countdownDisplay;
    private Date nextScheduledTime;

    @PostConstruct
    public void init() {
        cargarDatos();
    }

    public void cargarDatos() {
        try {
            // Obtener facturas pendientes (status = true pero no enviadas a Hacienda)
            facturasPendientes = comprobantesService.findFacturasPendientes();
            contadorPendientes = facturasPendientes != null ? (long) facturasPendientes.size() : 0L;

            // Obtener facturas aceptadas por Hacienda
            facturasAceptadas = comprobantesService.findFacturasAceptadas();
            contadorAceptadas = facturasAceptadas != null ? (long) facturasAceptadas.size() : 0L;

            // Obtener facturas rechazadas por Hacienda
            facturasRechazadas = comprobantesService.findFacturasRechazadas();
            contadorRechazadas = facturasRechazadas != null ? (long) facturasRechazadas.size() : 0L;

            // Obtener próximo envío programado
            proximoEnvio = reportesService.findNextScheduledReport();
            if (proximoEnvio != null) {
                nextScheduledTime = proximoEnvio.getNextRunTime();
                actualizarCountdown();
            }

            // Registrar alerta
            alertasService.registrarAlerta("Página de consultas cargada", 
                "Se han cargado los datos de facturas pendientes, aceptadas y rechazadas", 
                sessionController.getCurrentUser(), 0, "ConsultasController.init", null, null);
                
        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error al cargar datos", e.getMessage()));
        }
    }

    public void actualizarCountdown() {
        if (proximoEnvio != null && nextScheduledTime != null) {
            Date now = new Date();
            long diff = nextScheduledTime.getTime() - now.getTime();
            
            if (diff > 0) {
                long hours = diff / (60 * 60 * 1000);
                long minutes = (diff % (60 * 60 * 1000)) / (60 * 1000);
                long seconds = (diff % (60 * 1000)) / 1000;
                
                countdownDisplay = String.format("%02d:%02d:%02d", hours, minutes, seconds);
            } else {
                countdownDisplay = "00:00:00";
                // Si el tiempo ha pasado, cargar datos nuevamente
                cargarDatos();
            }
        }
    }

    public void refreshData() {
        cargarDatos();
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Datos actualizados", "Se han recargado los datos"));
    }

    public void enviarFacturasPendientes() {
        if (facturasPendientes == null || facturasPendientes.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Sin pendientes", "No hay facturas pendientes de envio"));
            return;
        }

        int total = facturasPendientes.size();
        int[] enviadas = {0};
        int[] fallidas = {0};

        CompletableFuture.runAsync(() -> {
            for (ComprobantesEmitidos factura : facturasPendientes) {
                try {
                    String clave = factura.getHaciendaClave();
                    if (clave == null || clave.isEmpty()) {
                        fallidas[0]++;
                        continue;
                    }

                    JAXBContext context = JAXBContext.newInstance(ComprobantesEmitidos.class);
                    Marshaller marshaller = context.createMarshaller();
                    marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
                    StringWriter sw = new StringWriter();
                    marshaller.marshal(factura, sw);
                    String xmlContent = sw.toString();

                    HaciendaSigner.SignResult signResult = haciendaSigner.signXml(xmlContent);
                    if (signResult.success) {
                        HaciendaApiService.ApiResponse apiResponse = haciendaApiService.sendInvoice(clave, signResult.signedXml);
                        if (apiResponse.isSuccess()) {
                            factura.setHaciendaEstado("ENVIADO");
                            factura.setHaciendaFechaEnvio(LocalDateTime.now());
                            if (factura.getEncabezado() != null) factura.getEncabezado().setEstado("ENVIADO");
                            comprobantesService.update(factura);
                            enviadas[0]++;
                        } else {
                            factura.getEncabezado().setEstado("RECHAZADO");
                            factura.getEncabezado().setMotivoRechazo(apiResponse.errorMessage);
                            comprobantesService.update(factura);
                            fallidas[0]++;
                        }
                    } else {
                        fallidas[0]++;
                    }
                } catch (Exception e) {
                    fallidas[0]++;
                }
            }

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Envio completado",
                    "Enviadas: " + enviadas[0] + ", Fallidas: " + fallidas[0] + " de " + total));

            alertasService.registrarAlerta("Envio de facturas completado",
                "Envio masivo de facturas: " + enviadas[0] + " enviadas, " + fallidas[0] + " fallidas de " + total,
                sessionController.getCurrentUser(), 0, "ConsultasController.enviarFacturasPendientes", null, null);

            cargarDatos();
        });
    }

    public String getProximoEnvioDisplay() {
        if (proximoEnvio != null && nextScheduledTime != null) {
            return "Próximo envío: " + nextScheduledTime.toString();
        }
        return "No hay envíos programados";
    }

    public boolean hasFacturasPendientes() {
        return contadorPendientes != null && contadorPendientes > 0;
    }

    public boolean hasProximoEnvio() {
        return proximoEnvio != null;
    }

    public String corregirFacturaRechazada(ComprobantesEmitidos facturaRechazada) {
        try {
            // Registrar alerta
            alertasService.registrarAlerta("Corrección de factura iniciada", 
                "Se inició la corrección de la factura rechazada: " + facturaRechazada.getEncabezado().getNumeroConsecutivo(), 
                sessionController.getCurrentUser(), 0, "ConsultasController.corregirFacturaRechazada", null, null);
            
            // Crear nota de crédito automáticamente para la factura rechazada
            crearNotaCreditoAutomatica(facturaRechazada);
            
            // Clonar la información de la factura rechazada al carrito
            clonarFacturaACarrito(facturaRechazada);
            
            // Abrir ventana de nueva factura
            PrimeFaces.current().executeScript("openFactura('CORREGIR_" + facturaRechazada.getId() + "');");
            
            // Recargar datos
            cargarDatos();
            
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Factura cargada para corrección", 
                    "Se ha creado la nota de crédito y cargado la información en el carrito para generar una nueva factura. Número original: " + facturaRechazada.getEncabezado().getNumeroConsecutivo()));
            
            return null; // Permanecer en la misma página
            
        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error al corregir factura", e.getMessage()));
            return null;
        }
    }
    
    private void crearNotaCreditoAutomatica(ComprobantesEmitidos facturaRechazada) {
        try {
            if (facturaRechazada.getResumen() == null || facturaRechazada.getEncabezado() == null) {
                return;
            }
            
            // Check if credit note already exists
            List<NotaCredito> existentes = notaCreditoService.listPorComprobante(facturaRechazada.getId());
            if (existentes != null && !existentes.isEmpty()) {
                alertasService.registrarAlerta("Info", "Nota de crédito ya existe para factura: " + facturaRechazada.getId(), null, 0, "ConsultasController.crearNotaCreditoAutomatica()", null, null);
                return;
            }
            
            NotaCredito notaCredito = new NotaCredito();
            notaCredito.setComprobanteOriginal(facturaRechazada);
            notaCredito.setFecha(new Date());
            notaCredito.setMotivo("Corrección automática por rechazo de Hacienda: " + 
                (facturaRechazada.getEncabezado().getMotivoRechazo() != null ? facturaRechazada.getEncabezado().getMotivoRechazo() : "Sin motivo especificado"));
            notaCredito.setMontoTotal(facturaRechazada.getResumen().getTotalVentaNeta());
            
            // Get client from receptor
            if (facturaRechazada.getEncabezado().getReceptor() != null) {
                String receptorNombre = facturaRechazada.getEncabezado().getReceptor().getNombre();
                if (receptorNombre != null) {
                    List<Clients> clients = clientService.searchByName(receptorNombre);
                    if (clients != null && !clients.isEmpty()) {
                        notaCredito.setCliente(clients.get(0));
                    }
                }
            }
            
            notaCredito.setUsuario(sessionController.getCurrentUser() != null ? sessionController.getCurrentUser().getUsername() : "system");
            notaCredito.setStatus(true);
            notaCredito.setHaciendaEstado("PENDIENTE");
            
            notaCreditoService.create(notaCredito);
            
            alertasService.registrarAlerta("Hacienda", "Nota de crédito creada automáticamente para factura rechazada: " + facturaRechazada.getId(), sessionController.getCurrentUser(), 0, "ConsultasController.crearNotaCreditoAutomatica()", null, null);
            
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creando nota de crédito automática: " + e.getMessage(), sessionController.getCurrentUser(), 0, "ConsultasController.crearNotaCreditoAutomatica()", null, e.getMessage());
        }
    }
    
    private void clonarFacturaACarrito(ComprobantesEmitidos facturaRechazada) {
        try {
            crearTiqueteController.openNewFactura();
            carritoService.clear();

            Receptor receptor = facturaRechazada.getEncabezado().getReceptor();
            if (receptor != null && receptor.getNombre() != null) {
                List<Clients> clients = clientService.searchByName(receptor.getNombre());
                if (clients != null && !clients.isEmpty()) {
                    crearTiqueteController.setSelectedClient(clients.get(0));
                }
            }

            DetalleServicio detalles = facturaRechazada.getDetalles();
            if (detalles != null && detalles.getLineasDetalle() != null) {
                for (LineaDetalle linea : detalles.getLineasDetalle()) {
                    if (linea.getCantidad() == null) continue;

                    Articulos articulo = null;

                    if (linea.getCodigosComerciales() != null) {
                        for (CodigoComercial codigo : linea.getCodigosComerciales()) {
                            if (codigo.getCodigo() != null && !codigo.getCodigo().isEmpty()) {
                                articulo = articulosService.findByBarCode(codigo.getCodigo());
                                if (articulo != null) break;
                            }
                        }
                    }

                    if (articulo == null && linea.getDetalle() != null && !linea.getDetalle().isEmpty()) {
                        articulo = articulosService.findByName(linea.getDetalle());
                    }

                    if (articulo != null) {
                        carritoService.addArticulo(articulo, linea.getCantidad());
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error al clonar factura al carrito: " + e.getMessage(), e);
        }
    }
}