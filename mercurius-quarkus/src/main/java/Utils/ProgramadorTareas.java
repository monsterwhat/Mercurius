package Utils;

import Controllers.Settings.SettingsDirController;
import Services.EmailService;
import Services.TipoCambioService;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import java.time.temporal.ChronoUnit;

@Singleton
public class ProgramadorTareas {
    
    @Inject private TipoCambioService tipoCambioService;
    @Inject private EmailService emailer;
    @Inject private SettingsDirController settings;

    //Media noche
    @Scheduled(cron = "0 0 0 * * ?")
    public void actualizarTipoCambioUSD() {
        tipoCambioService.getTipoCambioFromApi();
    }
    
    //Cada 15min
    @Scheduled(cron = "0 */15 * * * ?")
    @CircuitBreaker(requestVolumeThreshold = 3, failureRatio = 0.5, delay = 15, delayUnit = ChronoUnit.MINUTES)
    @Fallback(fallbackMethod = "revisarRecibosEnCorreosFallback")
    public void revisarRecibosEnCorreos() {
        
        String correoElectronico = settings.getCurrentSettings().getCorreoElectronico();
        String contrasenaCorreo = settings.getCurrentSettings().getContrasenaCorreo();
        
        emailer.processUnreadXmlAttachments(correoElectronico, contrasenaCorreo, this::handleEmailProcess);
    }
    
    private void revisarRecibosEnCorreosFallback() {
        System.err.println("FALLBACK: revisarRecibosEnCorreos skipped - circuit breaker open or repeated failures");
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
