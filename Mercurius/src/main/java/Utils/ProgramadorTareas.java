package Utils;

import Controllers.Settings.SettingsDirController;
import Services.EmailService;
import Services.TipoCambioService;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.inject.Inject;

@Singleton
public class ProgramadorTareas {
    
    @Inject private TipoCambioService tipoCambioService;
    @Inject private EmailService emailer;
    @Inject private SettingsDirController settings;

    //Media noche
    @Schedule(hour = "0", minute = "0", second = "0", persistent = true)
    public void actualizarTipoCambioUSD() {
        tipoCambioService.getTipoCambioFromApi();
    }
    
    //Cada 15min
    @Schedule(hour = "*", minute = "*/15", second = "0", persistent = true)
    public void revisarRecibosEnCorreos() {
        
        String correoElectronico = settings.getCurrentSettings().getCorreoElectronico();
        String contrasenaCorreo = settings.getCurrentSettings().getContrasenaCorreo();
        
        emailer.processUnreadXmlAttachments(correoElectronico, contrasenaCorreo, this::handleEmailProcess);
    }
    
    public void handleEmailProcess(String emailResult) {
    // Log the result of the email processing
    System.out.println("Email processing result: " + emailResult);

        if (emailResult.startsWith("Processing completed")) {
            System.out.println("Success: " + emailResult);
        } else {
            // If there was an error, handle it appropriately
            System.out.println("Error: " + emailResult);
        }
    
    }
    
}
