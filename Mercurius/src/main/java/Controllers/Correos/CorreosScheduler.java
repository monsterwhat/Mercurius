package Controllers.Correos;

import Controllers.SettingsController;
import Models.Articulos;
import Models.Comprobantes.ComprobanteFinal;
import Models.Correos.ReporteProgramado;
import Models.Correos.ReportesEnum;
import Models.Departamento;
import Models.Familia;
import Models.Inventario;
import Services.ArticulosService;
import Services.Correos.ReportesProgramadosService;
import Services.DepartamentoService;
import Services.EmailService;
import Services.FacturaService;
import Services.FamiliaService;
import Services.InventarioService;
import Utils.ExcelExporter;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 *
 * @author Al
 */

@Singleton
public class CorreosScheduler {
    @Inject ReportesProgramadosService rpService;
    
    @Inject InventarioService inventarioService;
    @Inject FacturaService facturaService;
    @Inject ArticulosService articuloService;
    @Inject DepartamentoService departamentosService;
    @Inject FamiliaService familiaService;
    @Inject SettingsController settings;
    @Inject private EmailService emailer;
    
    ExcelExporter exporter = new ExcelExporter();
    
    private List<ReporteProgramado> reportes;
    
    //Midnight everyday!
    @Schedule(hour = "0", minute = "0", second = "0", persistent = false)
    public void checkReportesActivos() {
        reportes = rpService.listAll();
        
        for (ReporteProgramado reporte : reportes) {
            if (reporte.isStatus()) {
                Date fechaUltimoReporte = reporte.getLastRun();
                
                if (fechaUltimoReporte != null) { // Null check added here
                    List<String> frecuencias = reporte.getFrecuencia();
                    
                    for (String frecuencia : frecuencias) {
                        Date fechaProximoReporte = calcularFechaProximoReporte(fechaUltimoReporte, frecuencia);
                        
                        if (fechaUltimoReporte.before(fechaProximoReporte) || fechaUltimoReporte.equals(fechaProximoReporte)) {
                            checkChanges(reporte);
                        } else {
                            // No hay que hacer nada nos vamos!
                            return;
                        }
                    }
                } else {
                    //Null fecha...
                }
            }else{
                //Disabled Reporte...
            }
        }
    }
    
    private Date calcularFechaProximoReporte(Date fechaUltimoReporte, String frecuencia) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(fechaUltimoReporte);

        switch (frecuencia) {
            case "Diario" -> calendar.add(Calendar.DAY_OF_MONTH, 1); // Next day
            case "Semanal" -> calendar.add(Calendar.WEEK_OF_YEAR, 1); // Next week
            case "Quincenal" -> calendar.add(Calendar.DAY_OF_MONTH, 15); // Next 15 days
            case "Mensual" -> calendar.add(Calendar.MONTH, 1); // Next month
            default -> {
                // Handle unknown frequency or throw an exception
                return null;
            }
        }

        return calendar.getTime();
    }
    
    public void checkChanges(ReporteProgramado reporte) {
    List<String> tipos = reporte.getReportes();

        for (String tipo : tipos) {
            try {
                ReportesEnum reporteEnum = ReportesEnum.valueOf(tipo.toUpperCase());
                    // Handle each enum value
                    switch (reporteEnum) {
                        case MOVIMIENTOS -> {
                            processMovimientos(reporte);
                        }
                        case FACTURACION -> {
                            processFacturas(reporte);
                        }
                        case ARTICULOS -> {
                            processArticulos(reporte);
                        }
                        case DEPARTAMENTOS -> {
                            processDepartamentos(reporte);
                        }
                        case FAMILIAS -> {
                            processFamilias(reporte);
                        }
                        case INVENTARIOS -> {
                            processInventarios(reporte);
                        }
                        default -> {
                            return;
                        }
                    }
                } catch (IllegalArgumentException e) {
                System.err.println("Invalid report type: " + tipo);
            }
        }
    }
    
    public void processMovimientos(ReporteProgramado reporte){
    List<Inventario> changes = inventarioService.findInventariosAfterDate(reporte.getLastRun());
    if(changes != null && !changes.isEmpty()){
        try {
            // Generate a unique file name based on timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fileName = "movimientos_reporte_" + timestamp + ".xlsx";
            
            // Construct full file path
            String filePath = settings.getReportesDirPath() + "/" + fileName;
            
            // Export to Excel
            File file = exporter.exportInventoryToExcel(changes, filePath);
            
            //Mail the file!
            mailChanges(file, reporte);
            
        } catch (IOException e) {
            System.out.println("Error: " + e.getLocalizedMessage());
        }
    } else {
        System.out.println("No changes found after last run.");
    }
}
    
    public void processFacturas(ReporteProgramado reporte){
        List<ComprobanteFinal> changes = facturaService.findComprobantesAfterDate(reporte.getLastRun());
        if(changes != null && !changes.isEmpty()){
            try {
                // Generate a unique file name based on timestamp
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String fileName = "facturas_reporte_" + timestamp + ".xlsx";

                // Construct full file path
                String filePath = settings.getReportesDirPath() + "/" + fileName;

                // Export to Excel
                File file = exporter.exportComprobantesToExcel(changes, filePath);
                
                //Mail the file!
                mailChanges(file, reporte);
                
                System.out.println("Report generated: " + filePath);
            } catch (IOException e) {
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        }
    }
    
    public void processArticulos(ReporteProgramado reporte){
        List<Articulos> changes = articuloService.findArticulosAfterDate(reporte.getLastRun());
        if(changes != null && !changes.isEmpty()){
            try {
                // Generate a unique file name based on timestamp
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String fileName = "articulos_reporte_" + timestamp + ".xlsx";

                // Construct full file path
                String filePath = settings.getReportesDirPath() + "/" + fileName;

                // Export to Excel
                File file = exporter.exportArticulosToExcel(changes, filePath);

                //Mail the file!
                mailChanges(file, reporte);
                
                System.out.println("Report generated: " + filePath);
            } catch (IOException e) {
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        }
    }
    
    public void processDepartamentos(ReporteProgramado reporte){
        List<Departamento> changes = departamentosService.findDepartamentosAfterDate(reporte.getLastRun());
        if(changes != null && !changes.isEmpty()){
            try {
                // Generate a unique file name based on timestamp
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String fileName = "departamentos_reporte_" + timestamp + ".xlsx";

                // Construct full file path
                String filePath = settings.getReportesDirPath() + "/" + fileName;

                // Export to Excel
                File file = exporter.exportDepartamentosToExcel(changes, filePath);

                //Mail the file!
                mailChanges(file, reporte);
                
                System.out.println("Report generated: " + filePath);
            } catch (IOException e) {
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        }
    }
    
    public void processFamilias(ReporteProgramado reporte){
        List<Familia> changes = familiaService.findFamiliasAfterDate(reporte.getLastRun());
        if(changes != null && !changes.isEmpty()) {
            try {
                // Generate a unique file name based on timestamp
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String fileName = "familias_reporte_" + timestamp + ".xlsx";

                // Construct full file path
                String filePath = settings.getReportesDirPath() + "/" + fileName;

                // Export to Excel
                File file = exporter.exportFamiliasToExcel(changes, filePath);

                //Mail the file!
                mailChanges(file, reporte);
                
                System.out.println("Report generated: " + filePath);
            } catch (IOException e) {
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        }
    }
    
    public void processInventarios(ReporteProgramado reporte){
        List<Inventario> changes = inventarioService.findInventariosAfterDate(reporte.getLastRun());
        if(changes != null && !changes.isEmpty()){
            try {
                // Generate a unique file name based on timestamp
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String fileName = "inventarios_reporte_" + timestamp + ".xlsx";

                // Construct full file path
                String filePath = settings.getReportesDirPath() + "/" + fileName;

                // Export to Excel
                File file = exporter.exportInventoryToExcel(changes, filePath);
                
                //Mail the file!
                mailChanges(file, reporte);

                System.out.println("Report generated: " + filePath);
            } catch (IOException e) {
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        }
    }
    
    public void mailChanges(File changes, ReporteProgramado reporte){
        String correoElectronico = settings.getCurrentSettings().getCorreoElectronico();
        String contrasenaCorreo = settings.getCurrentSettings().getContrasenaCorreo();
        List<String> to = reporte.getCorreos();
        String nombreReporte = reporte.getPerfil();
        String subject = "Reporte Automatico" + Date.from(Instant.MIN);
        String body = "Adjunto encontrara el reporte " + nombreReporte;
        emailer.sendEmailsWithAttachment(to, subject, body, correoElectronico, contrasenaCorreo, changes, this::handleEmailResult);
    }
    
    public void handleEmailResult(String emailResult) {
    // Handle the result of the email sending operation
    if (emailResult.equals("Sent")) {
        // Email sent successfully
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Reporte enviado exitosamente!", null));
        } else {
            // Failed to send email
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error enviando reporte!: " + emailResult, null));
        }
    }
    
}
