package Utils;

import Controllers.Settings.SettingsDirController;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Services.AlertasService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import Services.EmailService;
import Services.HaciendaApiService;
import Services.TipoCambioService;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import java.time.temporal.ChronoUnit;
import java.time.DayOfWeek;

@Singleton
public class ProgramadorTareas {
    
    @Inject private TipoCambioService tipoCambioService;
    @Inject private EmailService emailer;
    @Inject private SettingsDirController settings;
    @Inject private AlertasService alertasService;
    @Inject private ComprobantesEmitidosService comprobantesEmitidosService;
    @Inject private ComprobantesRecibidosService comprobantesRecibidosService;
    @Inject private HaciendaApiService haciendaApiService;
    @Inject private ComprobanteService comprobanteService;

    //Media noche
    @Scheduled(cron = "0 0 0 * * ?")
    public void actualizarTipoCambioUSD() {
        tipoCambioService.getTipoCambioFromApi();
    }
    
    //Cada 15min
    @Scheduled(cron = "0 */15 * * * ?")
    @CircuitBreaker(requestVolumeThreshold = 3, failureRatio = 0.5, delay = 15, delayUnit = ChronoUnit.MINUTES)
    @Fallback(fallbackMethod = "revisarRecibosEnCorreosFallback")
    public void revisarRecibosEnCorreos() {
        
        String correoElectronico = settings.getCurrentSettings().getCorreoElectronico();
        String contrasenaCorreo = settings.getCurrentSettings().getContrasenaCorreo();
        
        emailer.processUnreadXmlAttachments(correoElectronico, contrasenaCorreo, this::handleEmailProcess);
    }
    
    private void revisarRecibosEnCorreosFallback() {
        alertasService.registrarAlerta("Error", "FALLBACK: revisarRecibosEnCorreos skipped - circuit breaker open or repeated failures", null, 0, "ProgramadorTareas.revisarRecibosEnCorreosFallback()", null, null);
    }
    
    public void handleEmailProcess(String emailResult) {
    // Log the result of the email processing
    alertasService.registrarAlerta("Info", "Email processing result: " + emailResult, null, 0, "ProgramadorTareas.handleEmailProcess()", null, null);

        if (emailResult.startsWith("Processing completed")) {
            alertasService.registrarAlerta("Info", "Success: " + emailResult, null, 0, "ProgramadorTareas.handleEmailProcess()", null, null);
        } else {
            // If there was an error, handle it appropriately
            alertasService.registrarAlerta("Error", "Error: " + emailResult, null, 0, "ProgramadorTareas.handleEmailProcess()", null, null);
        }
    
    }

    @Scheduled(cron = "0 */30 * * * ?")
    public void verificarEstadoFacturasEnviadas() {
        try {
            List<ComprobantesEmitidos> facturas = comprobantesEmitidosService.findFacturasParaVerificarEstado();
            if (facturas == null || facturas.isEmpty()) return;

            for (ComprobantesEmitidos factura : facturas) {
                try {
                    String clave = factura.getHaciendaClave();
                    if (clave == null || clave.isEmpty()) continue;

                    HaciendaApiService.ApiResponse response = haciendaApiService.checkInvoiceStatus(clave);
                    if (response.isSuccess()) {
                        String nuevoEstado = parseEstadoFromHaciendaResponse(response.responseBody);

                            if (nuevoEstado != null) {
                                String estadoAnterior = factura.getHaciendaEstado();
                                factura.setHaciendaEstado(nuevoEstado);
                                factura.setHaciendaFechaRespuesta(LocalDateTime.now());
                                if (factura.getEncabezado() != null) {
                                    factura.getEncabezado().setEstado(nuevoEstado);
                                }
                                comprobantesEmitidosService.update(factura);

                                alertasService.registrarAlerta("Hacienda", "Estado actualizado: " + clave + " -> " + nuevoEstado, null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, null);

                                // If newly accepted, send to client
                                if ("ACEPTADO".equals(nuevoEstado) && !"ACEPTADO".equals(estadoAnterior)) {
                                    try {
                                        comprobanteService.enviarFacturaACliente(factura, null, null, null, null);
                                    } catch (Exception e) {
                                        alertasService.registrarAlerta("Error", "Error enviando factura a cliente: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage());
                                    }
                                }
                            }
                    } else if (response.statusCode >= 400 && response.statusCode < 500) {
                        factura.setHaciendaEstado("RECHAZADO");
                        factura.setHaciendaFechaRespuesta(LocalDateTime.now());
                        if (factura.getEncabezado() != null) {
                            factura.getEncabezado().setEstado("RECHAZADO");
                            factura.getEncabezado().setMotivoRechazo(response.errorMessage);
                        }
                        comprobantesEmitidosService.update(factura);
                    }
                } catch (Exception e) {
                    alertasService.registrarAlerta("Error", "Error verificando estado factura: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage());
                }
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error en verificarEstadoFacturasEnviadas: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage());
        }
    }

    private String parseEstadoFromHaciendaResponse(String responseBody) {
        if (responseBody == null) return null;
        String lower = responseBody.toLowerCase();
        if (lower.contains("\"estado\":\"aceptado\"") || lower.contains("aceptado")) return "ACEPTADO";
        if (lower.contains("\"estado\":\"rechazado\"") || lower.contains("rechazado")) return "RECHAZADO";
        if (lower.contains("\"estado\":\"procesando\"") || lower.contains("procesando")) return null;
        return null;
    }

    @Scheduled(cron = "0 */15 * * * ?")
    public void verificarFacturasSinRespuesta3Horas() {
        try {
            List<ComprobantesEmitidos> facturas = comprobantesEmitidosService.findFacturasSinRespuesta3Horas();
            if (facturas == null || facturas.isEmpty()) return;

            for (ComprobantesEmitidos factura : facturas) {
                try {
                    String clave = factura.getHaciendaClave();
                    if (clave == null || clave.isEmpty()) continue;

                    alertasService.registrarAlerta("Advertencia Hacienda", 
                        "Factura sin respuesta de Hacienda tras 3 horas: " + clave + 
                        " (Enviada: " + factura.getHaciendaFechaEnvio() + ")",
                        null, 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, null);

                    // Try to check status one more time
                    HaciendaApiService.ApiResponse response = haciendaApiService.checkInvoiceStatus(clave);
                    if (response.isSuccess()) {
                        String nuevoEstado = parseEstadoFromHaciendaResponse(response.responseBody);
                        if (nuevoEstado != null) {
                            factura.setHaciendaEstado(nuevoEstado);
                            factura.setHaciendaFechaRespuesta(LocalDateTime.now());
                            if (factura.getEncabezado() != null) {
                                factura.getEncabezado().setEstado(nuevoEstado);
                            }
                            comprobantesEmitidosService.update(factura);
                            
                            alertasService.registrarAlerta("Hacienda", "Estado recuperado tras 3h: " + clave + " -> " + nuevoEstado, null, 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, null);

                            if ("ACEPTADO".equals(nuevoEstado)) {
                                comprobanteService.enviarFacturaACliente(factura, null, null, null, null);
                            }
                        }
                    }
                } catch (Exception e) {
                    alertasService.registrarAlerta("Error", "Error verificando factura 3h: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, e.getMessage());
                }
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error en verificarFacturasSinRespuesta3Horas: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void verificarVencimientoMensajeReceptor() {
        try {
            List<ComprobantesRecibidos> proximosVencer = comprobantesRecibidosService.findProximosVencerMensajeReceptor(3);
            if (proximosVencer == null || proximosVencer.isEmpty()) return;

            for (ComprobantesRecibidos factura : proximosVencer) {
                long diasRestantes = factura.getDiasRestantesMensajeReceptor();
                String consecutivo = factura.getEncabezado() != null ? factura.getEncabezado().getNumeroConsecutivo() : "N/A";
                
                if (diasRestantes <= 0) {
                    alertasService.registrarAlerta("Crítico Hacienda", 
                        "VENCIDO: Mensaje Receptor para factura " + consecutivo + " - Perdida de crédito fiscal IVA", 
                        null, 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, null);
                } else if (diasRestantes <= 1) {
                    alertasService.registrarAlerta("Urgente Hacienda", 
                        "MAÑANA VENCE: Mensaje Receptor para factura " + consecutivo + " (" + diasRestantes + " día)", 
                        null, 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, null);
                } else {
                    alertasService.registrarAlerta("Advertencia Hacienda", 
                        "Próximo a vencer: Mensaje Receptor para factura " + consecutivo + " (" + diasRestantes + " días)", 
                        null, 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, null);
                }
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error en verificarVencimientoMensajeReceptor: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, e.getMessage());
        }
    }

    private LocalDate calcularLimite8DiasHabiles(LocalDate inicio) {
        int diasHabiles = 0;
        LocalDate fecha = inicio;
        while (diasHabiles < 8) {
            if (fecha.getDayOfWeek() != DayOfWeek.SATURDAY && fecha.getDayOfWeek() != DayOfWeek.SUNDAY) {
                diasHabiles++;
                if (diasHabiles == 8) break;
            }
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }
    
}
