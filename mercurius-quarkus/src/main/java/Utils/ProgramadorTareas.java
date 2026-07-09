package Utils;

import Models.AppSettings;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Models.Departamento;
import Models.ReportesFamiliasYDepartamentos;
import Services.AlertasService;
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
import Services.TipoCambioService;
import io.quarkus.scheduler.Scheduled;
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
    
    @Inject @Nonnull private TipoCambioService tipoCambioService;
    @Inject @Nonnull private EmailService emailer;
    @Inject @Nonnull private AppSettingsService appSettingsService;
    @Inject @Nonnull private AlertasService alertasService;
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
                    alertasService.registrarAlerta("Error", "Error enviando factura pendiente: " + e.getMessage(),
                        null, 0, "ProgramadorTareas.enviarFacturasPendientes()", null, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error en enviarFacturasPendientes: " + e.getMessage(),
                null, 0, "ProgramadorTareas.enviarFacturasPendientes()", null, e.getMessage());
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
        alertasService.registrarAlerta("Error", "FALLBACK: revisarRecibosEnCorreos skipped - circuit breaker open or repeated failures", null, 0, "ProgramadorTareas.revisarRecibosEnCorreosFallback()", null, null);
    }
    
    public void handleEmailProcess(@Nonnull String emailResult) {
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
                                    } catch (RuntimeException e) {
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
                        // Trigger auto-correction for newly rejected invoices
                        try {
                            if (correctionService.puedeCorregir(factura)) {
                                correctionService.corregirFactura(factura);
                            }
                        } catch (RuntimeException ce) {
                            alertasService.registrarAlerta("Error", "Error en auto-corrección: " + ce.getMessage(), null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, ce.getMessage());
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
                                    result -> alertasService.registrarAlerta("Info",
                                        "Notificación de rechazo enviada: " + result, null, 0,
                                        "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, null)
                                );
                            }
                        } catch (RuntimeException ne) {
                            alertasService.registrarAlerta("Error", "Error enviando notificación de rechazo: " + ne.getMessage(),
                                null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, ne.getMessage());
                        }
                    }
                } catch (RuntimeException e) {
                    alertasService.registrarAlerta("Error", "Error verificando estado factura: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error en verificarEstadoFacturasEnviadas: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarEstadoFacturasEnviadas()", null, e.getMessage());
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
                } catch (RuntimeException e) {
                    alertasService.registrarAlerta("Error", "Error verificando factura 3h: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarFacturasSinRespuesta3Horas()", null, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
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
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error en verificarVencimientoMensajeReceptor: " + e.getMessage(), null, 0, "ProgramadorTareas.verificarVencimientoMensajeReceptor()", null, e.getMessage());
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

            alertasService.registrarAlerta("Info", "Notificación de lotes próximos a vencer enviada a " + correoNotif,
                null, 0, "ProgramadorTareas.notificarLotesProximosVencer()", null, null);

        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error en notificarLotesProximosVencer: " + e.getMessage(),
                null, 0, "ProgramadorTareas.notificarLotesProximosVencer()", null, e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 6 * * ?")
    public void notificarLlegadaProveedores() {
        try {
            AppSettings currentSettings = appSettingsService.returnCurrent();
            String correoElectronico = currentSettings.getCorreoElectronico();
            String contrasenaCorreo = currentSettings.getContrasenaCorreo();

            if (correoElectronico == null || contrasenaCorreo == null) {
                alertasService.registrarAlerta("Info", "Email not configured for supplier notifications", null, 0,
                    "ProgramadorTareas.notificarLlegadaProveedores()", null, null);
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
                        result -> alertasService.registrarAlerta("Info",
                            "Notificación enviada a " + dept.getContactoEmail() + ": " + result, null, 0,
                            "ProgramadorTareas.notificarLlegadaProveedores()", null, null)
                    );
                } catch (RuntimeException e) {
                    alertasService.registrarAlerta("Error",
                        "Error notificando proveedor " + dept.getNombre() + ": " + e.getMessage(),
                        null, 0, "ProgramadorTareas.notificarLlegadaProveedores()", null, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error en notificarLlegadaProveedores: " + e.getMessage(),
                null, 0, "ProgramadorTareas.notificarLlegadaProveedores()", null, e.getMessage());
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
            alertasService.registrarAlerta("Info", "Expiración automática de puntos completada",
                null, 0, "ProgramadorTareas.expirePuntosInactivos()", null, null);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error expirando puntos inactivos: " + e.getMessage(),
                null, 0, "ProgramadorTareas.expirePuntosInactivos()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Notificación diaria: No hay facturas rechazadas pendientes",
                    null, 0, "ProgramadorTareas.notificarRechazosPendientes()", null, null);
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
                result -> alertasService.registrarAlerta("Info",
                    "Resumen diario de rechazos enviado: " + result, null, 0,
                    "ProgramadorTareas.notificarRechazosPendientes()", null, null)
            );

            alertasService.registrarAlerta("Info", "Resumen diario de rechazos enviado a " + currentSettings.getCorreoNotificaciones(),
                null, 0, "ProgramadorTareas.notificarRechazosPendientes()", null, null);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error en notificarRechazosPendientes: " + e.getMessage(),
                null, 0, "ProgramadorTareas.notificarRechazosPendientes()", null, e.getMessage());
        }
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
                alertasService.registrarAlerta("Info", "Iniciando backup programado...", null, 0,
                    "ProgramadorTareas.ejecutarBackupProgramado()", null, null);
                backupService.ejecutarBackup();
            }

        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error en backup programado: " + e.getMessage(), null, 0,
                "ProgramadorTareas.ejecutarBackupProgramado()", null, e.getMessage());
        }
    }
    
}
