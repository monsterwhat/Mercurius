package Controllers.Facturas;

import Models.Inventario;
import Models.Users;
import Services.InventarioService;
import Services.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
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
    @Inject private InventarioService inventarioService;
    
    private Long usuarioSelecionadoId;
    private List<Users> usuarios;
    private List<Date> range;
    private Date date;
    private boolean status = false;
    
    private List<Inventario> movimientos;
    
    @PostConstruct
    public void init(){
        usuarios = uService.listAll();
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
    
    public void listReportes(List<Date> range, Long userId) {
        Date startDate = range.get(0);
        Date endDate = range.get(1);
        if (startDate != null && endDate != null) {
            movimientos = inventarioService.findByDateRangeAndUserId(startDate, endDate, userId);
        }
    }
    
}
