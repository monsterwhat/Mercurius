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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.Data;
import org.primefaces.event.SelectEvent;
import org.primefaces.event.ToggleSelectEvent;
import org.primefaces.event.UnselectEvent;

/**
 *
 * @author Al
 */

@Data
@Named
@ViewScoped
public class ReportesDiariosController implements Serializable{
    
    @Inject private UserService uService;
    @Inject private InventarioService inventarioService;
    
    private String[] usuariosSeleccionados;
    private List<Users> usuarios;
    private List<Date> range;
    private Date date;
    private boolean status = false;
    
    private List<Inventario> movimientos;
    
    @PostConstruct
    public void init(){
        usuarios = uService.listAll();
    }
    
    public void onToggleSelect(ToggleSelectEvent event) {
        FacesMessage msg = new FacesMessage();
        msg.setSummary("Seleccionado: " + event.isSelected());
        msg.setSeverity(FacesMessage.SEVERITY_INFO);

        FacesContext.getCurrentInstance().addMessage(null, msg);
    }

    public void onItemSelect(SelectEvent event) {
        FacesMessage msg = new FacesMessage();
        msg.setSummary("Usuario seleccionado: " + event.getObject().toString());
        msg.setSeverity(FacesMessage.SEVERITY_INFO);

        FacesContext.getCurrentInstance().addMessage(null, msg);
    }

    public void onItemUnselect(UnselectEvent event) {
        FacesMessage msg = new FacesMessage();
        msg.setSummary("Usuario deseleccionado: " + event.getObject().toString());
        msg.setSeverity(FacesMessage.SEVERITY_INFO);

        FacesContext.getCurrentInstance().addMessage(null, msg);
    }
    
    public void cargar(){
        if(range != null && usuariosSeleccionados.length != 0){
            status = true;
            listReportes();
        }
    }
    
    public void listReportes(){
        var startDate = range.get(0);
        var endDate = range.get(1);
        List<Integer> users = new ArrayList<>();
        for (String usuariosSeleccionado : usuariosSeleccionados) {
            users.add(Integer.parseInt(usuariosSeleccionado));
        }
        
        movimientos = inventarioService.findByDateRangeAndUserIds(startDate, endDate, users);
    }
    
}
