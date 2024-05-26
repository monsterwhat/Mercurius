package Controllers;

import Services.EmailService;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 *
 * @author Al
 */

@Named
@RequestScoped
@Data
public class EmailController {
    
    @Resource private ManagedExecutorService managedExecutorService;
    
    private String to, subject, body;
    private boolean emailSent;
    private String emailMessage;
    
    // Method to send email
    public void send() {
        
        if (to != null && subject != null && body != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    EmailService.sendEmail(to, subject, body);
                    emailSent = true;
                    emailMessage = "Correo Enviado";
                } catch (Exception e) {
                    emailSent = false;
                    emailMessage = "Failed to send email: " + e.getMessage();
                }
            });
        }else{
            emailMessage = "Faltaron requisitos!";
        }
        
        update();
        
    }
    
    public void update(){
        if(emailSent){
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, emailMessage, null));
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_WARN, emailMessage, null));
        }
        
    }
    
}
