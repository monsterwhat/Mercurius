package Controllers;

import Models.Users;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import static java.lang.Integer.getInteger;
import java.util.Locale;
import lombok.Data;
import org.primefaces.util.LangUtils;

/**
 *
 * @author Al
 */

@Data
@Named(value = "ViewController")
@SessionScoped
public class ViewController implements Serializable {
    
    @Inject ProfilesController profiles;
    @Inject ClientsController clients;
    
    String selectedOption = "menu";

    public void selectViewUsers(){
        selectedOption = "viewUsers";
    }
    
    public void selectEditUsers(){
        selectedOption = "editUsers";
    }
    
    public void selectCreateUsers(){
        selectedOption = "createUsers";
        profiles.openNewProfile();
    }
    
    public void selectViewClients(){
        selectedOption = "viewClients";
    }
    
    public void selectEditClients(){
        selectedOption = "editClients";
    }
    
    public void selectCreateClients(){
        selectedOption = "createClients";
        clients.openNewClient();
    }
    
    public void selectMenu(){
        selectedOption = "menu";
    }

    
}
