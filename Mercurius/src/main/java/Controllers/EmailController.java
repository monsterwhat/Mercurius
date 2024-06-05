package Controllers;

import Services.EmailService;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
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
    @Inject private EmailService emailService;
    
    private String to, subject, body;
    private boolean emailSent;
    private String emailMessage;
    
    // Method to send email
    public void send() {
        
        if (to != null && subject != null && body != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    emailService.sendEmail(to, subject, body);
                    emailSent = true;
                    emailMessage = "Correo Enviado";
                } catch (Exception e) {
                    emailSent = false;
                    emailMessage = "Error enviando correo: " + e.getMessage();
                }
                update();
            }, managedExecutorService);
        } else {
            emailMessage = "Faltaron requisitos!";
            update();
        }
        
    }
    
     private void update() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (facesContext != null) {
            FacesMessage facesMessage = new FacesMessage(
                emailSent ? FacesMessage.SEVERITY_INFO : FacesMessage.SEVERITY_WARN,
                emailMessage,
                null
            );
            facesContext.addMessage(null, facesMessage);
        }
    }
    
}
