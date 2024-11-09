package Controllers.Facturas;

import Controllers.SessionController;
import Models.ArticuloPrecio;
import Models.Articulos;
import Models.Comprobantes.ComprobantesEmitidos;
import Models.Comprobantes.Detalles.LineaDetalle;
import Models.Inventario;
import Models.ReportesFamiliasYDepartamentos;
import Models.Users;
import Services.ArticulosService;
import Services.ComprobantesEmitidosService;
import Services.InventarioService;
import Services.PrinterService;
import Services.UserService;
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
        if (startDate != null && endDate != null) {
            facturasEmitidas = comprobanteService.listAllEmitidosBy(currentSession.getCurrentUser());
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

        for (ComprobantesEmitidos facturaEmitida : facturasEmitidas) {
            if (facturaEmitida.getDetalles() != null && facturaEmitida.getDetalles().getLineasDetalle() != null) {
                for (LineaDetalle linea : facturaEmitida.getDetalles().getLineasDetalle()) {
                    if (linea.getMontoTotalLinea()!= null) { 
                        totalAmount = totalAmount.add(linea.getMontoTotalLinea());
                    }
                }
            }
        }
        return totalAmount;
    }
    
    public List<LineaDetalle> getLineasDetalle(){
        List<LineaDetalle> lineasDetalle = new ArrayList<>();
        for (ComprobantesEmitidos facturasEmitida : facturasEmitidas) {
            for(LineaDetalle lineaDetalle : facturasEmitida.getDetalles().getLineasDetalle()){
                lineasDetalle.add(lineaDetalle);
            }
        }
        return lineasDetalle;
    }

    public void imprimirReporteFamilias(){
        File pdfFile = null;
        pdfFile = pdfGenerator.generarPDFReportesFamilias(reportes,range);
        printer.printPDFFile(pdfFile);
    }
    
    public void imprimirReporteDepartamentos(){
        File pdfFile = null;
        pdfFile = pdfGenerator.generarPDFReportesDepartamentos(reportes,range);
        printer.printPDFFile(pdfFile);
    }
    
    public void imprimirReporteVentasXCajero(){
        File pdfFile = null;
        pdfFile = pdfGenerator.generarPDFReportesVentasXCajero(facturasEmitidas, currentSession.getUsername(), range);
        printer.printPDFFile(pdfFile);
    }
    
}
