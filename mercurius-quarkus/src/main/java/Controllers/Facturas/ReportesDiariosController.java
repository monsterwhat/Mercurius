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
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 *
 * @author Al
 */

@Getter @Setter @ToString @EqualsAndHashCode
@Named("reportesDiariosController")
@ViewScoped
public class ReportesDiariosController implements Serializable{
    
    @Inject @Nonnull private UserService uService;
    @Inject @Nonnull private SessionController currentSession;
    @Inject @Nonnull private InventarioService inventarioService;
    @Inject @Nonnull private ArticulosService articuloService;
    @Inject @Nonnull PDFGenerator pdfGenerator;
    @Inject @Nonnull PrinterService printer;
    @Inject @Nonnull ComprobantesEmitidosService comprobanteService;
    @Inject @Nonnull AlertasService alertasService;
    
    @Nullable
    private Long usuarioSelecionadoId;
    @Nonnull
    private List<Users> usuarios;
    @Nullable
    private List<Date> range;
    @Nullable
    private Date date;
    private boolean status = false;
    @Nullable
    List<ReportesFamiliasYDepartamentos> reportes;
    @Nonnull
    List<ComprobantesEmitidos> facturasEmitidas;
    
    @Nullable
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
    
    public void listReportes(@Nonnull List<Date> range, @Nonnull Long userId) {
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
            movimientos = inventarioService.findByDateRangeAndUserId(startDate, endDate, userId);
        }
    }
    
    public void listReportesVentas(@Nonnull List<Date> range, @Nonnull Long userId) {
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
            movimientos = inventarioService.findVentasByDateRangeAndUserId(startDate, endDate, userId);
        }
    }
    
    public void listReportesVentasXFamilia(@Nonnull List<Date> range, @Nonnull Long userId){
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
           reportes = inventarioService.getTotalSalesByFamilia(startDate, endDate);
        }
    }
    
    public void listReportesVentasXDepartamento(@Nonnull List<Date> range, @Nonnull Long userId){
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
            reportes = inventarioService.getTotalSalesByDepartamento(startDate, endDate);
        }
    }
    
    public void listReportesVentasXCajero(@Nonnull List<Date> range, @Nullable Long userId){
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
                alertasService.registrarAlerta("Info", "Loaded " + facturasEmitidas.size() + " invoices for user " + userId + " between " + startDate + " and " + endDate, currentSession.getCurrentUser(), 0, "ReportesDiariosController.loadFacturas()", null, null);
            } else {
                alertasService.registrarAlerta("Error", "User not found with ID: " + userId, currentSession.getCurrentUser(), 0, "ReportesDiariosController.loadFacturas()", null, null);
            }
        } else {
            alertasService.registrarAlerta("Error", "Invalid parameters: startDate=" + startDate + ", endDate=" + endDate + ", userId=" + userId, currentSession.getCurrentUser(), 0, "ReportesDiariosController.loadFacturas()", null, null);
        }
    }
    
    public @Nonnull String getFecha(int posicion){
        return range.get(posicion).toString();
    }
    
    public @Nullable BigDecimal totalReportes(){
        return ReportesFamiliasYDepartamentos.totalReportes(reportes);
    }
    
    public @Nonnull BigDecimal totalReporteVentasXCajero() {
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
    
    public @Nonnull List<LineaDetalle> getLineasDetalle(){
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
        alertasService.registrarAlerta("Info", "imprimirReporteFamilias called", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteFamilias()", null, null);
        alertasService.registrarAlerta("Info", "reportes: " + (reportes != null ? reportes.size() : "null"), currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteFamilias()", null, null);
        alertasService.registrarAlerta("Info", "range: " + range, currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteFamilias()", null, null);
        
        if (reportes == null || reportes.isEmpty()) {
            alertasService.registrarAlerta("Info", "No data to generate family report", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteFamilias()", null, null);
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay datos para imprimir", "No se encontraron datos para generar el reporte de familias"));
            return;
        }
        
        File pdfFile = pdfGenerator.generarPDFReportesFamilias(reportes, range);
        alertasService.registrarAlerta("Info", "PDF file generated: " + (pdfFile != null ? pdfFile.getAbsolutePath() : "null"), currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteFamilias()", null, null);
        
        if(pdfFile != null){
            alertasService.registrarAlerta("Info", "Sending PDF to printer...", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteFamilias()", null, null);
            printer.printPDFFile(pdfFile);
        } else {
            alertasService.registrarAlerta("Error", "PDF generation failed", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteFamilias()", null, null);
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
        alertasService.registrarAlerta("Info", "imprimirReporteVentasXCajero called", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
        alertasService.registrarAlerta("Info", "facturasEmitidas: " + (facturasEmitidas != null ? facturasEmitidas.size() : "null"), currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
        alertasService.registrarAlerta("Info", "range: " + range, currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
        
        if (facturasEmitidas != null && !facturasEmitidas.isEmpty()) {
            File pdfFile = pdfGenerator.generarPDFReportesVentasXCajero(facturasEmitidas, currentSession.getUsername(), range);
            alertasService.registrarAlerta("Info", "PDF file generated: " + (pdfFile != null ? pdfFile.getAbsolutePath() : "null"), currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
            
            if(pdfFile != null){
                alertasService.registrarAlerta("Info", "Sending PDF to printer...", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
                printer.printPDFFile(pdfFile);
                alertasService.registrarAlerta("Info", "PDF sent to printer", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
            } else {
                alertasService.registrarAlerta("Error", "PDF generation failed", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
                FacesContext.getCurrentInstance().addMessage(null, 
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error generando PDF", "No se pudo generar el archivo PDF"));
            }
        } else {
            alertasService.registrarAlerta("Info", "No invoices to print", currentSession.getCurrentUser(), 0, "ReportesDiariosController.imprimirReporteVentasXCajero()", null, null);
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_WARN, "No hay datos para imprimir", "No se encontraron facturas para generar el reporte"));
        }
    }
    
}
