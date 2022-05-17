
package Controller;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import Model.UserTO;
import Service.Server_User;
import java.sql.Blob;
import jakarta.faces.bean.ManagedBean;
import jakarta.faces.bean.SessionScoped;
import jakarta.faces.event.NamedEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.primefaces.PrimeFaces;

public class LoginController implements Serializable{
    private int user_id;
    private String user_name;
    private Blob user_password;
    private int position_id;
    
    private Service.Server_User userService = new Server_User();
    private UserTO userData;
    private List<UserTO> userList = new ArrayList<UserTO>();

    public LoginController() {
    }
   
    @PostConstruct
    public void Load(){
        this.userList = userService.returnUserList();
    }
    
    public void confirmLogin(){
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error!", "Login Failed!"));
    }
}
