package Controllers;

import Controllers.SessionController;
import Controllers.Tiquetes.CrearTiqueteController;
import Models.ComprobantesV44.ComprobantesEmitidos;
import Models.Correos.ReporteProgramado;
import Services.AlertasService;
import Services.ComprobantesEmitidosService;
import Services.Correos.ReportesProgramadosService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Data;
import org.primefaces.PrimeFaces;

import java.io.Serializable;
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
        if (facturasPendientes != null && !facturasPendientes.isEmpty()) {
            // Aquí implementar la lógica para enviar facturas pendientes a Hacienda
            CompletableFuture.runAsync(() -> {
                try {
                    // Lógica de envío asíncrono
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Envío iniciado", 
                            "Se está iniciando el envío de " + facturasPendientes.size() + " facturas pendientes"));
                    
                    alertasService.registrarAlerta("Envío de facturas iniciado", 
                        "Se ha iniciado el proceso de envío de " + facturasPendientes.size() + " facturas pendientes", 
                        sessionController.getCurrentUser(), 0, "ConsultasController.enviarFacturasPendientes", null, null);
                        
                } catch (Exception e) {
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error en envío", e.getMessage()));
                }
            });
        }
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
            
            // Clonar la información de la factura rechazada al carrito
            clonarFacturaACarrito(facturaRechazada);
            
            // Abrir ventana de nueva factura
            PrimeFaces.current().executeScript("openFactura('CORREGIR_" + facturaRechazada.getId() + "');");
            
            // Recargar datos
            cargarDatos();
            
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Factura cargada para corrección", 
                    "Se ha cargado la información en el carrito para generar una nueva factura. Número original: " + facturaRechazada.getEncabezado().getNumeroConsecutivo()));
            
            return null; // Permanecer en la misma página
            
        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error al corregir factura", e.getMessage()));
            return null;
        }
    }
    
    private void clonarFacturaACarrito(ComprobantesEmitidos facturaRechazada) {
        try {
            // Crear nueva factura en el tiquete controller
            crearTiqueteController.openNewFactura();
            
            // Aquí se implementaría la lógica para copiar los detalles de la factura rechazada
            // al carrito del tiquete controller
            // Por ahora solo registramos que esta función necesita implementación completa
            
            // TODO: Implementar lógica completa para clonar:
            // - Cliente (receptor)
            // - Artículos del carrito (líneas de detalle)
            // - Método de pago
            // - Información de condicion de venta
            
        } catch (Exception e) {
            throw new RuntimeException("Error al clonar factura al carrito: " + e.getMessage(), e);
        }
    }
}