package controller;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import model.userTO;
import service.User_Service;


@Named("loginController")
@ApplicationScoped
public class LoginController implements Serializable {

    private String user_name;
    private String user_password;
    private int user_id;
    private int position_id;
    private userTO UserTO;
    private List<userTO> userList = new ArrayList<>();
    private User_Service userService;

    @PostConstruct
    public void loadData() {
        userList = userService.loadUserData();
    }

    public void confirmLogin(String user, String pass) {
        if (this.getUser_name() == null || "".equals(this.getUser_name())) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Campos invalidos", "El correo electronico es incorrecto");
        } else {
            if (userService.VerifyUsername(user_name)) {
                if (userService.verifyLoginBoolean(user, pass)) {
                    this.UserTO = userService.verifyLogin(user_name, user_password);
                    this.user_id = UserTO.getUser_id();
                    this.position_id = UserTO.getPosition_id();
                    switch (this.UserTO.getPosition_id()) {
                        case 1:
                            this.userList = userService.loadUserData();
                            //this.redireccionar("/faces/adminMenu.xhtml");
                            break;
                        case 2:
                            //this.redireccionar("/faces/clienteMenu.xhtml");
                            break;
                        default:
                            addMessage(FacesMessage.SEVERITY_ERROR, "Autenticacion", "El tipo de usuario es invalido");
                            break;
                    }
                } else {
                    addMessage(FacesMessage.SEVERITY_ERROR, "Error de informacion de usuario!", "Uno de los valores digitados es incorrecto!");

                }
            } else {
                addMessage(FacesMessage.SEVERITY_WARN, "Correo Incorrecto!", "El correo digitado no se encuentra registrado!");

            }
        }
    }

    public void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().
                addMessage(null, new FacesMessage(severity, summary, detail));
    }


    public void exit() {
        FacesContext.getCurrentInstance().getExternalContext().invalidateSession();
    }

    public String getUser_name() {
        return user_name;
    }

    public void setUser_name(String user_name) {
        this.user_name = user_name;
    }

    public String getUser_password() {
        return user_password;
    }

    public void setUser_password(String user_password) {
        this.user_password = user_password;
    }

    public userTO getUserTO() {
        return UserTO;
    }

    public void setUserTO(userTO UserTO) {
        this.UserTO = UserTO;
    }

    public int getUser_id() {
        return user_id;
    }

    public void setUser_id(int user_id) {
        this.user_id = user_id;
    }

    public int getPosition_id() {
        return position_id;
    }

    public void setPosition_id(int position_id) {
        this.position_id = position_id;
    }

    public List<userTO> getUserList() {
        return userList;
    }

    public void setUserList(List<userTO> userList) {
        this.userList = userList;
    }

    public User_Service getUserService() {
        return userService;
    }

    public void setUserService(User_Service userService) {
        this.userService = userService;
    }
    
    

}
