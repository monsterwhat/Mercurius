package Controllers.Correos;

import Controllers.Settings.SettingsDirController;
import Models.Articulos;
import Models.Comprobantes.ComprobantesRecibidos;
import Models.Correos.ReporteProgramado;
import Models.Correos.ReportesEnum;
import static Models.Correos.ReportesEnum.ARTICULOS;
import static Models.Correos.ReportesEnum.DEPARTAMENTOS;
import static Models.Correos.ReportesEnum.FACTURACION;
import static Models.Correos.ReportesEnum.FAMILIAS;
import static Models.Correos.ReportesEnum.INVENTARIOS;
import static Models.Correos.ReportesEnum.MOVIMIENTOS;
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
import jakarta.ejb.Singleton;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Singleton
@Named
public class CorreosHelper {
    
    @Inject InventarioService inventarioService;
    @Inject FacturaService facturaService;
    @Inject ArticulosService articuloService;
    @Inject DepartamentoService departamentosService;
    @Inject FamiliaService familiaService;
    @Inject SettingsDirController settings;
    @Inject private EmailService emailer;
    @Inject ReportesProgramadosService rpService;    
    
    ExcelExporter exporter = new ExcelExporter();
    
    public Date calcularFechaProximoReporte(Date fechaUltimoReporte, String frecuencia) {
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
        
        // Set the time to midnight
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        
        return calendar.getTime();
    }
    
    public void checkChanges(ReporteProgramado reporte) {
    List<String> tipos = reporte.getReportes();
        if(tipos == null){
            return;
        }
        for (String tipo : tipos) {
            try {
                String normalizedTipo = tipo.toUpperCase().trim();

                ReportesEnum reporteEnum = ReportesEnum.valueOf(normalizedTipo);

                // Handle each enum value
                switch (reporteEnum) {
                    case MOVIMIENTOS -> processMovimientos(reporte);
                    case FACTURACION -> processFacturas(reporte);
                    case ARTICULOS -> processArticulos(reporte);
                    case DEPARTAMENTOS -> processDepartamentos(reporte);
                    case FAMILIAS -> processFamilias(reporte);
                    case INVENTARIOS -> processInventarios(reporte);
                    default -> {
                        return;
                    }
                }
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid report type: " + tipo);
                e.printStackTrace(); // Print the full stack trace for debugging
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
            File file = exporter.exportMovimientosToExcel(changes, filePath);
            
            //Mail the file!
            mailChanges(file, reporte);
            
        } catch (IOException e) {
            System.out.println("Error: " + e.getLocalizedMessage());
        }
    } else {
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay cambios desde el ultimo reporte.", null));

    }
}
    
    public void processFacturas(ReporteProgramado reporte){
        List<ComprobantesRecibidos> changes = facturaService.findComprobantesAfterDate(reporte.getLastRun());
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
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay cambios desde el ultimo reporte.", null));
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
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay cambios desde el ultimo reporte.", null));

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
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay cambios desde el ultimo reporte.", null));

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
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay cambios desde el ultimo reporte.", null));

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
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay cambios desde el ultimo reporte.", null));

        }
    }
    
    public void mailChanges(File changes, ReporteProgramado reporte){
        String correoElectronico = settings.getCurrentSettings().getCorreoElectronico();
        String contrasenaCorreo = settings.getCurrentSettings().getContrasenaCorreo();
        List<String> to = reporte.getCorreos();
        String nombreReporte = reporte.getPerfil();

        // Use current date and time for the subject
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedDate = now.format(formatter);
        String subject = "Reporte Automatico - " + formattedDate;
        String body = "Adjunto encontrara el reporte " + nombreReporte;
        
        emailer.sendEmailsWithAttachment(to, subject, body, correoElectronico, contrasenaCorreo, changes, this::handleEmailResult);
        
        reporte.setLastRun(new Date());
        rpService.update(reporte);
        
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
