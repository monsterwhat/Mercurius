package Controllers.Facturas;

import Controllers.SessionController; 
import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Inventario;
import Models.ReportesFamiliasYDepartamentos;
import Models.Users;
import Services.ArticulosService;
import Services.ComprobantesEmitidosService;
import Services.InventarioService;
import Services.PrinterService;
import Services.UserService;
import Services.AlertasService;
import Utils.PDFGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List; 
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Named("reportesDiariosController")
@ViewScoped
public class ReportesDiariosController implements Serializable{
    
    @Inject private UserService uService;
    @Inject private SessionController currentSession;
    @Inject private InventarioService inventarioService;
    @Inject private ArticulosService articuloService;
    @Inject PDFGenerator pdfGenerator;
    @Inject PrinterService printer;
    @Inject ComprobantesEmitidosService comprobanteService;
    @Inject AlertasService alertasService;
    
    private Long usuarioSelecionadoId;
    private List<Users> usuarios;
    private List<Date> range;
    private Date date;
    private boolean status = false;
    List<ReportesFamiliasYDepartamentos> reportes;
    List<ComprobantesEmitidos> facturasEmitidas;
    
    private List<Inventario> movimientos;
    
    @PostConstruct
    public void init(){
        usuarios = uService.listAll();
        facturasEmitidas = new ArrayList<>();
    }
    
    public void cargar(){
        if(range != null && !range.isEmpty()){
            if(usuarioSelecionadoId != null){
                status = true;
                listReportes(range, usuarioSelecionadoId);
                
                // Save an alert (log) for loading the report
                alertasService.registrarAlerta("Reporte cargado", "Se ha cargado el reporte para el usuario: " + usuarioSelecionadoId, currentSession.getCurrentUser(), 0, "ReportesDiariosController.cargar", null, null);
                
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono un usuario", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono el rango de fechas", null));
        }
    }
    
    public void cargarVentasPorCajero(){
        if(range != null && !range.isEmpty()){
            if(usuarioSelecionadoId != null){
                status = true;
                listReportesVentasXCajero(range, usuarioSelecionadoId);
                
                // Save an alert (log) for loading sales report by cashier
                alertasService.registrarAlerta("Reporte de ventas por cajero cargado", "Se ha cargado el reporte de ventas por cajero para el usuario: " + usuarioSelecionadoId, currentSession.getCurrentUser(), 0, "ReportesDiariosController.cargarVentasPorCajero", null, null);
                
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono un usuario", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono el rango de fechas", null));
        }
    }
    
     public void cargarVentasFamilias(){
        if(range != null && !range.isEmpty()){
            if(usuarioSelecionadoId != null){
                status = true;
                listReportesVentasXFamilia(range, usuarioSelecionadoId);
                
                // Save an alert (log) for loading sales report by family
                alertasService.registrarAlerta("Reporte de ventas por familia cargado", "Se ha cargado el reporte de ventas por familia para el usuario: " + usuarioSelecionadoId, currentSession.getCurrentUser(), 0, "ReportesDiariosController.cargarVentasFamilias", null, null);
                
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono un usuario", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono el rango de fechas", null));
        }
    }
    
    public void cargarVentasDepartamento(){
        if(range != null && !range.isEmpty()){
            if(usuarioSelecionadoId != null){
                status = true;
                listReportesVentasXDepartamento(range, usuarioSelecionadoId);
                
                // Save an alert (log) for loading sales report by department
                alertasService.registrarAlerta("Reporte de ventas por departamento cargado", "Se ha cargado el reporte de ventas por departamento para el usuario: " + usuarioSelecionadoId, currentSession.getCurrentUser(), 0, "ReportesDiariosController.cargarVentasDepartamento", null, null);
                
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono un usuario", null));
            }
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_ERROR, "No se selecciono el rango de fechas", null));
        }
    }
    
    public void listReportes(List<Date> range, Long userId) {
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
            movimientos = inventarioService.findByDateRangeAndUserId(startDate, endDate, userId);
        }
    }
    
    public void listReportesVentas(List<Date> range, Long userId) {
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
            movimientos = inventarioService.findVentasByDateRangeAndUserId(startDate, endDate, userId);
        }
    }
    
    public void listReportesVentasXFamilia(List<Date> range, Long userId){
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
           reportes = inventarioService.getTotalSalesByFamilia(startDate, endDate);
        }
    }
    
    public void listReportesVentasXDepartamento(List<Date> range, Long userId){
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
            reportes = inventarioService.getTotalSalesByDepartamento(startDate, endDate);
        }
    }
    
    public void listReportesVentasXCajero(List<Date> range, Long userId){
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        
        // Always initialize the list
        facturasEmitidas = new ArrayList<>();
        
        if (startDate != null && endDate != null && userId != null) {
            // Find the user by ID first
            Users selectedUser = uService.find(userId);
            if (selectedUser != null) {
                // Get invoices for the selected user within the date range
                List<ComprobantesEmitidos> invoices = comprobanteService.listAllEmitidosBy(selectedUser, startDate, endDate);
                if (invoices != null) {
                    facturasEmitidas = invoices;
                }
                System.out.println("Loaded " + facturasEmitidas.size() + " invoices for user " + userId + " between " + startDate + " and " + endDate);
            } else {
                System.out.println("User not found with ID: " + userId);
            }
        } else {
            System.out.println("Invalid parameters: startDate=" + startDate + ", endDate=" + endDate + ", userId=" + userId);
        }
    }
    
    public String getFecha(int posicion){
        return range.get(posicion).toString();
    }
    
    public BigDecimal totalReportes(){
        return ReportesFamiliasYDepartamentos.totalReportes(reportes);
    }
    
    public BigDecimal totalReporteVentasXCajero() {
        BigDecimal totalAmount = BigDecimal.ZERO;

        if (facturasEmitidas != null) {
            for (ComprobantesEmitidos facturaEmitida : facturasEmitidas) {
                if (facturaEmitida.getDetalles() != null && facturaEmitida.getDetalles().getLineasDetalle() != null) {
                    for (LineaDetalle linea : facturaEmitida.getDetalles().getLineasDetalle()) {
                        if (linea.getMontoTotalLinea()!= null) { 
                            totalAmount = totalAmount.add(linea.getMontoTotalLinea());
                        }
                    }
                }
            }
        }
        return totalAmount;
    }
    
    public List<LineaDetalle> getLineasDetalle(){
        List<LineaDetalle> lineasDetalle = new ArrayList<>();
        if (facturasEmitidas != null) {
            for (ComprobantesEmitidos facturasEmitida : facturasEmitidas) {
                if (facturasEmitida.getDetalles() != null && facturasEmitida.getDetalles().getLineasDetalle() != null) {
                    for(LineaDetalle lineaDetalle : facturasEmitida.getDetalles().getLineasDetalle()){
                        lineasDetalle.add(lineaDetalle);
                    }
                }
            }
        }
        return lineasDetalle;
    }

    public void imprimirReporteFamilias(){
        System.out.println("imprimirReporteFamilias called");
        System.out.println("reportes: " + (reportes != null ? reportes.size() : "null"));
        System.out.println("range: " + range);
        
        if (reportes == null || reportes.isEmpty()) {
            System.out.println("No data to generate family report");
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay datos para imprimir", "No se encontraron datos para generar el reporte de familias"));
            return;
        }
        
        File pdfFile = pdfGenerator.generarPDFReportesFamilias(reportes, range);
        System.out.println("PDF file generated: " + (pdfFile != null ? pdfFile.getAbsolutePath() : "null"));
        
        if(pdfFile != null){
            System.out.println("Sending PDF to printer...");
            printer.printPDFFile(pdfFile);
        } else {
            System.out.println("PDF generation failed");
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error generando PDF", "No se pudo generar el archivo PDF"));
        }
    }
    
    public void imprimirReporteDepartamentos(){
        File pdfFile = pdfGenerator.generarPDFReportesDepartamentos(reportes,range);
        if(pdfFile != null){
            printer.printPDFFile(pdfFile);
        }
    }
    
    public void imprimirReporteVentasXCajero(){
        System.out.println("imprimirReporteVentasXCajero called");
        System.out.println("facturasEmitidas: " + (facturasEmitidas != null ? facturasEmitidas.size() : "null"));
        System.out.println("range: " + range);
        
        if (facturasEmitidas != null && !facturasEmitidas.isEmpty()) {
            File pdfFile = pdfGenerator.generarPDFReportesVentasXCajero(facturasEmitidas, currentSession.getUsername(), range);
            System.out.println("PDF file generated: " + (pdfFile != null ? pdfFile.getAbsolutePath() : "null"));
            
            if(pdfFile != null){
                System.out.println("Sending PDF to printer...");
                printer.printPDFFile(pdfFile);
                System.out.println("PDF sent to printer");
            } else {
                System.out.println("PDF generation failed");
                FacesContext.getCurrentInstance().addMessage(null, 
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error generando PDF", "No se pudo generar el archivo PDF"));
            }
        } else {
            System.out.println("No invoices to print");
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay datos para imprimir", "No se encontraron facturas para generar el reporte"));
        }
    }
    
}
