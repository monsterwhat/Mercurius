package Controllers;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import lombok.Data;

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
    
    String selectedOption = "none";

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
    
    public void selectViewCabys(){
        selectedOption = "viewCabys";
    }
    
    public void selectNone(){
        selectedOption = "none";
    }

    
}
