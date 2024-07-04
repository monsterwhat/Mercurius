package Services;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.ejb.Stateless;
import jakarta.ejb.Asynchronous;
import jakarta.inject.Named;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 *
 * @author Al
 */

@Named
@Stateless
public class EmailService implements Serializable{
    
    @Asynchronous
    public void sendEmail(String to, String subject, String body, String email, String pass, Consumer<String> callback) {
        final String[] status = {null}; // Declare status as an array
        
        if (email != null && pass != null) {
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
                message.setHeader("Content-Type","text/plain; chartset=utf-8");
                message.setFrom(new InternetAddress(email));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
                message.setSubject(subject);
                message.setContent(body, "text/plain; charset=utf-8");

                Transport.send(message);
                status[0] = "Sent";
                
            } catch (MessagingException e) {
                status[0] = "Encountered an Error: " + e.getLocalizedMessage();
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        } else {
            System.out.println("No email set up");
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    @Asynchronous
    public void sendEmails(List<String> to, String subject, String body, String email, String pass, Consumer<String> callback) {
        final String[] status = {null}; // Declare status as an array
        
        if (email != null && pass != null) {
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
                message.setHeader("Content-Type","text/plain; charset=utf-8");
                message.setFrom(new InternetAddress(email));

                // Set multiple recipients
                InternetAddress[] toAddresses = new InternetAddress[to.size()];
                for (int i = 0; i < to.size(); i++) {
                    toAddresses[i] = new InternetAddress(to.get(i));
                }
                message.setRecipients(Message.RecipientType.TO, toAddresses);

                message.setSubject(subject);
                message.setContent(body, "text/plain; charset=utf-8");

                Transport.send(message);
                status[0] = "Sent";
                
            } catch (MessagingException e) {
                status[0] = "Encountered an Error: " + e.getLocalizedMessage();
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        } else {
            System.out.println("No email set up");
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    @Asynchronous
    public void sendEmailsWithAttachment(List<String> to, String subject, String body, String email, String pass, File attachment, Consumer<String> callback) {
        final String[] status = {null}; // Declare status as an array
        
        if (email != null && pass != null) {
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
                message.setHeader("Content-Type","text/plain; charset=utf-8");
                message.setFrom(new InternetAddress(email));

                // Set multiple recipients
                InternetAddress[] toAddresses = new InternetAddress[to.size()];
                for (int i = 0; i < to.size(); i++) {
                    toAddresses[i] = new InternetAddress(to.get(i));
                }
                message.setRecipients(Message.RecipientType.TO, toAddresses);

                message.setSubject(subject);
                message.setContent(body, "text/plain");

                // Attach file if provided
                if (attachment != null) {
                    DataSource source = new FileDataSource(attachment);
                    message.setDataHandler(new DataHandler(source));
                    message.setFileName(attachment.getName());
                }

                Transport.send(message);
                status[0] = "Sent";
                
            } catch (MessagingException e) {
                status[0] = "Encountered an Error: " + e.getLocalizedMessage();
                System.out.println("Error: " + e.getLocalizedMessage());
            }
        } else {
            System.out.println("No email set up");
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
}
