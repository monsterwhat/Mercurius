package Utils;

import Models.AppSettings;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Models.Departamento;
import Models.ReportesFamiliasYDepartamentos;
import Services.AppSettingsService;
import Services.BackupService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosCorrectionService;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import Services.DepartamentoService;
import Services.EmailService;
import Services.HaciendaApiService;
import Services.InventarioService;
import Services.LoteService;
import Services.LoyaltyService;
import Services.MensajeReceptorService;
import Services.StockAlertService;
import Services.TipoCambioService;
import Models.StockAlert;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.faulttolerance.Retry;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import java.time.temporal.ChronoUnit;
import java.time.DayOfWeek;
import java.util.concurrent.TimeUnit;

@Singleton
public class ProgramadorTareas {
    
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ProgramadorTareas.class.getName());

    @Inject @Nonnull private TipoCambioService tipoCambioService;
    @Inject @Nonnull private EmailService emailer;
    @Inject @Nonnull private AppSettingsService appSettingsService;
    @Inject @Nonnull private ComprobantesEmitidosService comprobantesEmitidosService;
    @Inject @Nonnull private ComprobantesRecibidosService comprobantesRecibidosService;
    @Inject @Nonnull private HaciendaApiService haciendaApiService;
    @Inject @Nonnull private ComprobanteService comprobanteService;
    @Inject @Nonnull private ComprobantesEmitidosCorrectionService correctionService;
    @Inject @Nonnull private DepartamentoService departamentoService;
    @Inject @Nonnull private InventarioService inventarioService;
    @Inject @Nonnull private LoteService loteService;
    @Inject @Nonnull private BackupService backupService;
    @Inject @Nonnull private LoyaltyService loyaltyService;
    @Inject @Nonnull private StockAlertService stockAlertService;
    @Inject @Nonnull private MensajeReceptorService mensajeReceptorService;

    //Media noche
    @Scheduled(cron = "0 0 0 * * ?")
    public void actualizarTipoCambioUSD() {
        tipoCambioService.getTipoCambioFromApi();
    }
    
    // Every 48h — batch-send pending invoices to Hacienda
    @Scheduled(every = "48h", delay = 2, delayUnit = TimeUnit.HOURS)
    public void enviarFacturasPendientes() {
        try {
            List<ComprobantesEmitidos> facturas = comprobantesEmitidosService.findFacturasPendientesEnvio();
            if (facturas == null || facturas.isEmpty()) return;

            for (ComprobantesEmitidos factura : facturas) {
                try {
                    comprobanteService.enviarComprobanteAHacienda(factura);
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error enviando factura pendiente: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.enviarFacturasPendientes()", null, e.getMessage()));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en enviarFacturasPendientes: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.enviarFacturasPendientes()", null, e.getMessage()));
        }
    }

    //Cada 15min
    @Scheduled(cron = "0 */15 * * * ?")
    @CircuitBreaker(requestVolumeThreshold = 3, failureRatio = 0.5, delay = 15, delayUnit = ChronoUnit.MINUTES)
    @Fallback(fallbackMethod = "revisarRecibosEnCorreosFallback")
    public void revisarRecibosEnCorreos() {
        
AppSettings currentSettings = appSettingsService.returnCurrent();
String correoElectronico = currentSettings.getCorreoElectronico();
String contrasenaCorreo = currentSettings.getContrasenaCorreo();
        
        emailer.processUnreadXmlAttachments(correoElectronico, contrasenaCorreo, this::handleEmailProcess);
    }
    
    private void revisarRecibosEnCorreosFallback() {
        LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "FALLBACK: revisarRecibosEnCorreos skipped - circuit breaker open or repeated failures", "Sistema", 0, "ProgramadorTareas.revisarRecibosEnCorreosFallback()", null, null));
    }
    
    public void handleEmailProcess(@Nonnull String emailResult) {
    // Log the result of the email processing
    LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Email processing result: " + emailResult, "Sistema", 0, "ProgramadorTareas.handleEmailProcess()", null, null));

        if (emailResult.startsWith("Processing completed")) {
            LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Success: " + emailResult, "Sistema", 0, "ProgramadorTareas.handleEmailProcess()", null, null));
        } else {
            // If there was an error, handle it appropriately
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error: " + emailResult, "Sistema", 0, "ProgramadorTareas.handleEmailProcess()", null, null));
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

                                LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Hacienda", "Estado actualizado: " + clave + " -> " + nuevoEstado, "Sistema", 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, null));

                                // If newly accepted, send to client
                                if ("ACEPTADO".equals(nuevoEstado) && !"ACEPTADO".equals(estadoAnterior)) {
                                    try {
                                        comprobanteService.enviarFacturaACliente(factura, null, null, null, null, null);
                                    } catch (RuntimeException e) {
                                        LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error enviando factura a cliente: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage()));
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
                        // Trigger auto-correction for newly rejected invoices
                        try {
                            if (correctionService.puedeCorregir(factura)) {
                                correctionService.corregirFactura(factura);
                            }
                        } catch (RuntimeException ce) {
                            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en auto-corrección: " + ce.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, ce.getMessage()));
                        }

                        // Send email notification if configured
                        try {
                            var currentSettings = appSettingsService.returnCurrent();
                            if (Boolean.TRUE.equals(currentSettings.getNotificarRechazos())
                                && currentSettings.getCorreoNotificaciones() != null
                                && !currentSettings.getCorreoNotificaciones().isEmpty()) {

                                String consecutivo = factura.getEncabezado() != null ? factura.getEncabezado().getNumeroConsecutivo() : "N/A";
                                String motivo = factura.getEncabezado() != null && factura.getEncabezado().getMotivoRechazo() != null
                                    ? factura.getEncabezado().getMotivoRechazo() : "No especificado";
                                String fecha = factura.getHaciendaFechaRespuesta() != null
                                    ? factura.getHaciendaFechaRespuesta().toString() : LocalDateTime.now().toString();
                                String intentos = factura.getCorrectionAttempts() != null
                                    ? factura.getCorrectionAttempts().toString() : "0";

                                String subject = "Factura Rechazada - " + consecutivo;
                                String body = "La factura con consecutivo " + consecutivo + " ha sido RECHAZADA por Hacienda.\n\n"
                                    + "Motivo de rechazo: " + motivo + "\n"
                                    + "Fecha de respuesta: " + fecha + "\n"
                                    + "Intentos de corrección: " + intentos + "\n\n"
                                    + "Por favor revise los detalles y corrija la factura.";

                                emailer.sendEmails(
                                    List.of(currentSettings.getCorreoNotificaciones()),
                                    subject, body,
                                    currentSettings.getCorreoElectronico(),
                                    currentSettings.getContrasenaCorreo(),
                                    result -> LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación de rechazo enviada: " + result, "Sistema", 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, null))
                                );
                            }
                        } catch (RuntimeException ne) {
                            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error enviando notificación de rechazo: " + ne.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, ne.getMessage()));
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error verificando estado factura: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage()));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en verificarEstadoFacturasEnviadas: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage()));
        }
    }

    @Nullable
    private String parseEstadoFromHaciendaResponse(@Nullable String responseBody) {
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

                    LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Advertencia Hacienda", "Factura sin respuesta de Hacienda tras 3 horas: " + clave + 
                        " (Enviada: " + factura.getHaciendaFechaEnvio() + ")", "Sistema", 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, null));

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
                            
                            LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Hacienda", "Estado recuperado tras 3h: " + clave + " -> " + nuevoEstado, "Sistema", 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, null));

                            if ("ACEPTADO".equals(nuevoEstado)) {
                                comprobanteService.enviarFacturaACliente(factura, null, null, null, null, null);
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error verificando factura 3h: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, e.getMessage()));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en verificarFacturasSinRespuesta3Horas: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, e.getMessage()));
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
                    LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Crítico Hacienda", "VENCIDO: Mensaje Receptor para factura " + consecutivo + " - Perdida de crédito fiscal IVA", "Sistema", 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, null));
                } else if (diasRestantes <= 1) {
                    LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Urgente Hacienda", "MAÑANA VENCE: Mensaje Receptor para factura " + consecutivo + " (" + diasRestantes + " día)", "Sistema", 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, null));
                } else {
                    LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Advertencia Hacienda", "Próximo a vencer: Mensaje Receptor para factura " + consecutivo + " (" + diasRestantes + " días)", "Sistema", 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, null));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en verificarVencimientoMensajeReceptor: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, e.getMessage()));
        }
    }

    @Scheduled(cron = "0 0 6 * * ?")
    @Retry(maxRetries = 3, delay = 300000, maxDuration = 900000)
    public void enviarMensajesReceptorPendientes() {
        try {
            List<ComprobantesRecibidos> proximosVencer = comprobantesRecibidosService.findProximosVencerMensajeReceptor(2);
            if (proximosVencer == null || proximosVencer.isEmpty()) return;

            for (ComprobantesRecibidos factura : proximosVencer) {
                try {
                    if (factura.getHaciendaMensajeReceptorEstado() != null) continue;

                    java.math.BigDecimal montoTotalImpuesto = null;
                    java.math.BigDecimal montoTotalFactura = null;
                    if (factura.getResumen() != null) {
                        montoTotalImpuesto = factura.getResumen().getTotalImpuesto();
                        montoTotalFactura = factura.getResumen().getTotalComprobante();
                    }

                    String consecutivo = factura.getEncabezado() != null ? factura.getEncabezado().getNumeroConsecutivo() : "N/A";
                    long diasRestantes = factura.getDiasRestantesMensajeReceptor();

                    MensajeReceptorService.MRResult result = mensajeReceptorService.enviarMensajeReceptor(
                        factura, 1, "ACEPTADO", montoTotalImpuesto, montoTotalFactura);

                    if (result.success) {
                        LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Hacienda", "Auto-envío MR aceptado para factura " + consecutivo + " (" + diasRestantes + " días restantes)", "Sistema", 0, "ProgramadorTareas.enviarMensajesReceptorPendientes()", null, null));
                    } else {
                        LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Advertencia Hacienda", "No se pudo auto-enviar MR para factura " + consecutivo + ": " + result.message, "Sistema", 0, "ProgramadorTareas.enviarMensajesReceptorPendientes()", null, result.message));
                    }
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error auto-enviando MR: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.enviarMensajesReceptorPendientes()", null, e.getMessage()));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en enviarMensajesReceptorPendientes: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.enviarMensajesReceptorPendientes()", null, e.getMessage()));
        }
    }

    @Scheduled(cron = "0 0 7 * * ?")
    public void notificarLotesProximosVencer() {
        try {
            List<Models.Lote> vencidos = loteService.listVencidos();
            List<Models.Lote> proximos = loteService.listProximosVencer(7);

            boolean hasVencidos = vencidos != null && !vencidos.isEmpty();
            boolean hasProximos = proximos != null && !proximos.isEmpty();

            if (!hasVencidos && !hasProximos) return;

            var currentSettings = appSettingsService.returnCurrent();
            Boolean notificar = currentSettings.getNotificarRechazos();
            String correoNotif = currentSettings.getCorreoNotificaciones();

            if (notificar == null || !notificar || correoNotif == null || correoNotif.isEmpty()) return;

            StringBuilder body = new StringBuilder();
            body.append("Reporte de Productos Próximos a Vencer\n\n");

            if (hasVencidos) {
                body.append("=== Productos Vencidos ===\n");
                for (Models.Lote lote : vencidos) {
                    body.append("Artículo: ").append(lote.getArticulo().getNombre())
                        .append(" | Lote: ").append(lote.getNumeroLote())
                        .append(" | Cantidad: ").append(lote.getCantidadActual())
                        .append(" | Vence: ").append(String.format("%1$td/%1$tm/%1$tY", lote.getFechaVencimiento()))
                        .append("\n");
                }
                body.append("\n");
            }

            if (hasProximos) {
                body.append("=== Próximos a Vencer (7 días) ===\n");
                for (Models.Lote lote : proximos) {
                    body.append("Artículo: ").append(lote.getArticulo().getNombre())
                        .append(" | Lote: ").append(lote.getNumeroLote())
                        .append(" | Cantidad: ").append(lote.getCantidadActual())
                        .append(" | Vence: ").append(String.format("%1$td/%1$tm/%1$tY", lote.getFechaVencimiento()))
                        .append("\n");
                }
                body.append("\n");
            }

            emailer.sendEmails(List.of(correoNotif),
                "Mercurius - Productos Próximos a Vencer", body.toString(),
                currentSettings.getCorreoElectronico(), currentSettings.getContrasenaCorreo(), null);

            LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación de lotes próximos a vencer enviada a " + correoNotif, "Sistema", 0, "ProgramadorTareas.notificarLotesProximosVencer()", null, null));

        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en notificarLotesProximosVencer: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.notificarLotesProximosVencer()", null, e.getMessage()));
        }
    }

    @Scheduled(cron = "0 0 6 * * ?")
    public void notificarLlegadaProveedores() {
        try {
            AppSettings currentSettings = appSettingsService.returnCurrent();
            String correoElectronico = currentSettings.getCorreoElectronico();
            String contrasenaCorreo = currentSettings.getContrasenaCorreo();

            if (correoElectronico == null || contrasenaCorreo == null) {
                LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Email not configured for supplier notifications", "Sistema", 0, "ProgramadorTareas.notificarLlegadaProveedores()", null, null));
                return;
            }

            List<Departamento> departamentos = departamentoService.listAllActive();
            if (departamentos == null || departamentos.isEmpty()) return;

            Date today = new Date();
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");

            for (Departamento dept : departamentos) {
                try {
                    if (dept.getContactoEmail() == null || dept.getContactoEmail().isEmpty()) continue;
                    if (dept.getTiempoEntregaDias() == null || dept.getTiempoEntregaDias() <= 0) continue;

                    Date lastPurchaseDate = inventarioService.getLastPurchaseDateByDepartamento(dept.getId());
                    if (lastPurchaseDate == null) {
                        Calendar cal = Calendar.getInstance();
                        cal.add(Calendar.DAY_OF_MONTH, -30);
                        lastPurchaseDate = cal.getTime();
                    }

                    Calendar arrivalCal = Calendar.getInstance();
                    arrivalCal.setTime(lastPurchaseDate);
                    arrivalCal.add(Calendar.DAY_OF_MONTH, dept.getTiempoEntregaDias());
                    Date expectedArrival = arrivalCal.getTime();

                    List<Object[]> salesDetails = inventarioService.getSalesDetailsByDepartamento(
                        inventarioService.getStartOfDay(lastPurchaseDate),
                        inventarioService.getEndOfDay(today),
                        dept.getId()
                    );

                    StringBuilder body = new StringBuilder();
                    body.append("Notificación de Llegada de Proveedor\n");
                    body.append("====================================\n\n");
                    body.append("Proveedor: ").append(dept.getNombre()).append("\n");
                    if (dept.getContactoNombre() != null && !dept.getContactoNombre().isEmpty()) {
                        body.append("Contacto: ").append(dept.getContactoNombre()).append("\n");
                    }
                    if (dept.getContactoTelefono() != null && !dept.getContactoTelefono().isEmpty()) {
                        body.append("Teléfono: ").append(dept.getContactoTelefono()).append("\n");
                    }
                    body.append("Email: ").append(dept.getContactoEmail()).append("\n\n");

                    body.append("Última compra: ").append(dateFormat.format(lastPurchaseDate)).append("\n");
                    body.append("Tiempo de entrega estimado: ").append(dept.getTiempoEntregaDias()).append(" días\n");

                    String arrivalStatus;
                    if (today.after(expectedArrival)) {
                        long daysOverdue = (today.getTime() - expectedArrival.getTime()) / (1000 * 60 * 60 * 24);
                        arrivalStatus = "VENCIDA hace " + daysOverdue + " día(s) (fecha estimada: " + dateFormat.format(expectedArrival) + ")";
                    } else {
                        long daysUntilArrival = (expectedArrival.getTime() - today.getTime()) / (1000 * 60 * 60 * 24);
                        arrivalStatus = "Esperada en " + daysUntilArrival + " día(s) (fecha estimada: " + dateFormat.format(expectedArrival) + ")";
                    }
                    body.append("Estado de entrega: ").append(arrivalStatus).append("\n\n");

                    body.append("--- Artículos vendidos desde la última compra ---\n");
                    BigDecimal totalVendido = BigDecimal.ZERO;
                    if (salesDetails != null && !salesDetails.isEmpty()) {
                        for (Object[] sale : salesDetails) {
                            String articuloNombre = (String) sale[0];
                            BigDecimal cantidad = (BigDecimal) sale[1];
                            BigDecimal total = (BigDecimal) sale[2];
                            // Sales quantities are negative values; negate to positive amount
                            if (total != null) {
                                total = total.multiply(BigDecimal.valueOf(-1));
                            } else {
                                total = BigDecimal.ZERO;
                            }
                            if (cantidad == null) cantidad = BigDecimal.ZERO;
                            totalVendido = totalVendido.add(total);
                            body.append("- ").append(articuloNombre)
                                .append(": Vendido = ").append(cantidad.abs().stripTrailingZeros().toPlainString())
                                .append(" unidades, Total = ₡").append(String.format("%.2f", total))
                                .append("\n");
                        }
                        body.append("\nTotal vendido del departamento: ₡").append(String.format("%.2f", totalVendido)).append("\n");
                    } else {
                        body.append("No se encontraron ventas en este período.\n");
                    }

                    body.append("\n--- Sugerencia de Reorden ---\n");
                    body.append("Basado en las ventas desde la última compra (").append(dateFormat.format(lastPurchaseDate));
                    body.append("), considere reordenar los artículos vendidos para mantener el inventario óptimo.\n");
                    body.append("Los artículos ordenados hoy llegarían aproximadamente en ").append(dept.getTiempoEntregaDias()).append(" días.\n");

                    String subject = "Notificación de Llegada - " + dept.getNombre();

                    emailer.sendEmails(
                        java.util.List.of(dept.getContactoEmail()),
                        subject,
                        body.toString(),
                        correoElectronico,
                        contrasenaCorreo,
                        result -> LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación enviada a " + dept.getContactoEmail() + ": " + result, "Sistema", 0, "ProgramadorTareas.notificarLlegadaProveedores()", null, null))
                    );
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error notificando proveedor " + dept.getNombre() + ": " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.notificarLlegadaProveedores()", null, e.getMessage()));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en notificarLlegadaProveedores: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.notificarLlegadaProveedores()", null, e.getMessage()));
        }
    }

    @Nonnull
    private LocalDate calcularLimite8DiasHabiles(@Nonnull LocalDate inicio) {
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

    // First day of month, 3 AM — expire inactive loyalty points
    @Scheduled(cron = "0 0 3 1 * ?")
    public void expirePuntosInactivos() {
        try {
            loyaltyService.checkAndExpireInactivePoints();
            LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Expiración automática de puntos completada", "Sistema", 0, "ProgramadorTareas.expirePuntosInactivos()", null, null));
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error expirando puntos inactivos: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.expirePuntosInactivos()", null, e.getMessage()));
        }
    }

    //Daily at 9am — send summary of all currently rejected invoices
    @Scheduled(cron = "0 0 9 * * ?")
    public void notificarRechazosPendientes() {
        try {
            var currentSettings = appSettingsService.returnCurrent();
            if (!Boolean.TRUE.equals(currentSettings.getNotificarRechazosResumen())
                || currentSettings.getCorreoNotificaciones() == null
                || currentSettings.getCorreoNotificaciones().isEmpty()) {
                return;
            }

            List<ComprobantesEmitidos> rechazadas = comprobantesEmitidosService.findFacturasRechazadas();
            if (rechazadas == null || rechazadas.isEmpty()) {
                LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación diaria: No hay facturas rechazadas pendientes", "Sistema", 0, "ProgramadorTareas.notificarRechazosPendientes()", null, null));
                return;
            }

            StringBuilder body = new StringBuilder();
            body.append("Resumen diario de facturas rechazadas por Hacienda\n");
            body.append("================================================\n\n");
            body.append("Total de facturas rechazadas: ").append(rechazadas.size()).append("\n\n");

            for (ComprobantesEmitidos factura : rechazadas) {
                String consecutivo = factura.getEncabezado() != null ? factura.getEncabezado().getNumeroConsecutivo() : "N/A";
                String motivo = factura.getEncabezado() != null && factura.getEncabezado().getMotivoRechazo() != null
                    ? factura.getEncabezado().getMotivoRechazo() : "No especificado";
                String fecha = factura.getHaciendaFechaRespuesta() != null
                    ? factura.getHaciendaFechaRespuesta().toString() : "N/A";
                String intentos = factura.getCorrectionAttempts() != null
                    ? factura.getCorrectionAttempts().toString() : "0";

                body.append("- Consecutivo: ").append(consecutivo).append("\n");
                body.append("  Motivo: ").append(motivo).append("\n");
                body.append("  Fecha: ").append(fecha).append("\n");
                body.append("  Intentos: ").append(intentos).append("\n\n");
            }

            body.append("Este es un reporte automático generado por Mercurius.");

            String subject = "Resumen Diario - Facturas Rechazadas (" + rechazadas.size() + ")";

            emailer.sendEmails(
                List.of(currentSettings.getCorreoNotificaciones()),
                subject, body.toString(),
                currentSettings.getCorreoElectronico(),
                currentSettings.getContrasenaCorreo(),
                result -> LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Resumen diario de rechazos enviado: " + result, "Sistema", 0, "ProgramadorTareas.notificarRechazosPendientes()", null, null))
            );

            LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Resumen diario de rechazos enviado a " + currentSettings.getCorreoNotificaciones(), "Sistema", 0, "ProgramadorTareas.notificarRechazosPendientes()", null, null));
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en notificarRechazosPendientes: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.notificarRechazosPendientes()", null, e.getMessage()));
        }
    }

    @Scheduled(cron = "0 30 7 * * ?")
    public void notificarAlertasStock() {
        try {
            stockAlertService.checkAndCreateStockAlerts();

            List<StockAlert> alertas = stockAlertService.getActiveStockAlerts();
            if (alertas == null || alertas.isEmpty()) {
                LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación diaria de stock: No hay alertas activas", "Sistema", 0, "ProgramadorTareas.notificarAlertasStock()", null, null));
                return;
            }

            var currentSettings = appSettingsService.returnCurrent();
            String correoNotif = currentSettings.getCorreoNotificaciones();
            if (correoNotif == null || correoNotif.isEmpty()) {
                LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación de stock omitida: correo de notificaciones no configurado", "Sistema", 0, "ProgramadorTareas.notificarAlertasStock()", null, null));
                return;
            }

            String subject = "Alertas de Stock - " + alertas.size() + " artículos necesitan reabastecimiento";
            String body = construirCuerpoAlertasStock(alertas);

            emailer.sendEmails(
                List.of(correoNotif),
                subject, body,
                currentSettings.getCorreoElectronico(),
                currentSettings.getContrasenaCorreo(),
                result -> LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación de stock enviada: " + result, "Sistema", 0, "ProgramadorTareas.notificarAlertasStock()", null, null))
            );

            LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Notificación de alertas de stock enviada a " + correoNotif + " (" + alertas.size() + " alertas)", "Sistema", 0, "ProgramadorTareas.notificarAlertasStock()", null, null));

        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en notificarAlertasStock: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.notificarAlertasStock()", null, e.getMessage()));
        }
    }

    private String construirCuerpoAlertasStock(List<StockAlert> alertas) {
        StringBuilder body = new StringBuilder();
        body.append("Reporte Diario de Alertas de Stock\n");
        body.append("===================================\n\n");
        body.append("Total de artículos con stock bajo: ").append(alertas.size()).append("\n\n");

        String fmt = "%-30s | %-12s | %-12s | %-15s | %-20s%n";
        body.append(String.format(fmt, "Artículo", "Stock Actual", "Stock Mínimo", "Tipo", "Departamento"));
        body.append(String.format(fmt, "------------------------------", "------------", "------------", "---------------", "--------------------"));

        for (StockAlert alerta : alertas) {
            String nombre = alerta.getArticulo() != null ? alerta.getArticulo().getNombre() : "N/A";
            String depto = alerta.getDepartamento() != null ? alerta.getDepartamento().getNombre() : "N/A";
            String tipo;
            if ("out_of_stock".equals(alerta.getTipoAlerta())) {
                tipo = "Sin Stock";
            } else if ("low_stock".equals(alerta.getTipoAlerta())) {
                tipo = "Stock Bajo";
            } else {
                tipo = alerta.getTipoAlerta();
            }

            body.append(String.format(fmt,
                nombre,
                alerta.getCantidadActual() != null ? alerta.getCantidadActual().toString() : "0",
                alerta.getCantidadMinima() != null ? alerta.getCantidadMinima().toString() : "0",
                tipo,
                depto));
        }

        body.append("\nEste es un reporte automático generado por Mercurius.");
        return body.toString();
    }

    @Scheduled(cron = "0 0 * * * ?")
    public void ejecutarBackupProgramado() {
        try {
            AppSettings currentSettings = backupService.getSettings();
            if (currentSettings == null) return;

            Boolean habilitado = currentSettings.getBackupHabilitado();
            if (habilitado == null || !habilitado) return;

            String backupHora = currentSettings.getBackupHora();
            if (backupHora == null || backupHora.isBlank()) return;

            LocalTime now = LocalTime.now();
            LocalTime scheduledTime;
            try {
                scheduledTime = LocalTime.parse(backupHora);
            } catch (java.time.format.DateTimeParseException e) {
                return;
            }

            if (now.getHour() == scheduledTime.getHour() && now.getMinute() == scheduledTime.getMinute()) {
                LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Info", "Iniciando backup programado...", "Sistema", 0, "ProgramadorTareas.ejecutarBackupProgramado()", null, null));
                backupService.ejecutarBackup();
            }

        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s", "Error", "Error en backup programado: " + e.getMessage(), "Sistema", 0, "ProgramadorTareas.ejecutarBackupProgramado()", null, e.getMessage()));
        }
    }
    
}
