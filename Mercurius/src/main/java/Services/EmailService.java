package Services;

import Controllers.SettingsController;
import jakarta.ejb.AsyncResult;
import jakarta.ejb.Stateless;
import jakarta.ejb.Asynchronous;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.Serializable;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 *
 * @author Al
 */

@Named
@Stateless
public class EmailService implements Serializable{

    @Inject SettingsController settings;
    
    @Asynchronous
    public Future<String> sendEmail(String to, String subject, String body) {
    String status;
        
    String email = settings.getCurrentSettings().getCorreoElectronico();
    String pass = settings.getCurrentSettings().getContrasenaCorreo();
    
        if(email != null && pass != null){
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.gmail.com");
            props.put("mail.smtp.port", "587");

            Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(email, pass);
                }
            });

            try {
                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(email));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
                message.setSubject(subject);
                message.setContent(body, "text/plain; charset=UTF-8");

                Transport.send(message);
                status = "Sent";

            } catch (MessagingException e) {
                status = "Encountered an Error: " + e.getLocalizedMessage();
                System.out.println("Error: " + e.getLocalizedMessage());
            } 
        }else{
            System.out.println("No email set up");
            status="No Email Setup!";
        }
        return new AsyncResult<>(status);
    }
}
