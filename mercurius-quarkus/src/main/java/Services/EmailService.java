package Services;

import Controllers.Settings.SettingsDirController;
import Services.AlertasService;
import Utils.Parsers.Parser;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import java.time.temporal.ChronoUnit;

/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class EmailService implements Serializable {
    
    @Inject @Nonnull Parser parser;
    @Inject @Nonnull SettingsDirController dirController;
    @Inject @Nonnull AlertasService alertasService;
    
    @Timeout(value = 30, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailFallback")
    public void sendEmail(@Nonnull String to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nonnull Consumer<String> callback) {
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
                alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), null, 0, "EmailService.sendEmail()", null, e.getMessage());
            }
        } else {
            alertasService.registrarAlerta("Info", "No email set up", null, 0, "EmailService.sendEmail()", null, null);
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendEmailFallback(String to, String subject, String body, String email, String pass, Consumer<String> callback) {
        alertasService.registrarAlerta("Error", "FALLBACK: sendEmail failed, notifying via callback", null, 0, "EmailService.sendEmailFallback()", null, null);
        CompletableFuture.runAsync(() -> callback.accept("Email send failed: Timeout or error - please try again later"));
    }
    
    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailsFallback")
    public void sendEmails(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nonnull Consumer<String> callback) {
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
                alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), null, 0, "EmailService.sendEmail()", null, e.getMessage());
            }
        } else {
            alertasService.registrarAlerta("Info", "No email set up", null, 0, "EmailService.sendEmail()", null, null);
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendEmailsFallback(List<String> to, String subject, String body, String email, String pass, Consumer<String> callback) {
        alertasService.registrarAlerta("Error", "FALLBACK: sendEmails failed, notifying via callback", null, 0, "EmailService.sendEmailsFallback()", null, null);
        CompletableFuture.runAsync(() -> callback.accept("Email send failed: Timeout or error - please try again later"));
    }
    
    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailsWithAttachmentFallback")
    public void sendEmailsWithAttachment(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nullable File attachment, @Nonnull Consumer<String> callback) {
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
                alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), null, 0, "EmailService.sendEmail()", null, e.getMessage());
            }
        } else {
            alertasService.registrarAlerta("Info", "No email set up", null, 0, "EmailService.sendEmail()", null, null);
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendEmailsWithAttachmentFallback(List<String> to, String subject, String body, String email, String pass, File attachment, Consumer<String> callback) {
        alertasService.registrarAlerta("Error", "FALLBACK: sendEmailsWithAttachment failed", null, 0, "EmailService.sendEmailsWithAttachmentFallback()", null, null);
        CompletableFuture.runAsync(() -> callback.accept("Email with attachment failed: Timeout or error - please try again later"));
    }
    
    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendHtmlEmailsFallback")
    public void sendHtmlEmails(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String htmlBody, @Nullable String email, @Nullable String pass, @Nonnull Consumer<String> callback) {
        final String[] status = {null};
        
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
                message.setHeader("Content-Type","text/html; charset=utf-8");
                message.setFrom(new InternetAddress(email));

                // Set multiple recipients
                InternetAddress[] toAddresses = new InternetAddress[to.size()];
                for (int i = 0; i < to.size(); i++) {
                    toAddresses[i] = new InternetAddress(to.get(i));
                }
                message.setRecipients(Message.RecipientType.TO, toAddresses);

                message.setSubject(subject);
                message.setContent(htmlBody, "text/html; charset=utf-8");

                Transport.send(message);
                status[0] = "Sent";
                
            } catch (MessagingException e) {
                status[0] = "Encountered an Error: " + e.getLocalizedMessage();
                alertasService.registrarAlerta("Error", "Error sending HTML email: " + e.getMessage(), null, 0, "EmailService.sendHtmlEmails()", null, e.getMessage());
            }
        } else {
            alertasService.registrarAlerta("Info", "No email set up", null, 0, "EmailService.sendHtmlEmails()", null, null);
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendHtmlEmailsFallback(List<String> to, String subject, String htmlBody, String email, String pass, Consumer<String> callback) {
        alertasService.registrarAlerta("Error", "FALLBACK: sendHtmlEmails failed, notifying via callback", null, 0, "EmailService.sendHtmlEmailsFallback()", null, null);
        CompletableFuture.runAsync(() -> callback.accept("HTML email send failed: Timeout or error - please try again later"));
    }
    
    @Timeout(value = 120, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 2000, jitter = 500)
    @CircuitBreaker(requestVolumeThreshold = 3, failureRatio = 0.5, delay = 15, delayUnit = ChronoUnit.MINUTES)
    @Fallback(fallbackMethod = "processUnreadXmlAttachmentsFallback")
    public void processUnreadXmlAttachments(@Nonnull String email, @Nonnull String pass, @Nonnull Consumer<String> callback) {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps"); // Use IMAP with SSL

        // Counters for statistics
        int totalEmails = 0;
        int emailsWithXmlAttachments = 0;
        int successfullyProcessedFiles = 0;

        try {
            // Set up session and connect to the email server
            Session session = Session.getDefaultInstance(props);
            Store store = session.getStore("imaps");
            store.connect("imap.gmail.com", email, pass);

            // Open the INBOX folder
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            // Search for unread messages
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            totalEmails = messages.length;

            // Ensure save directory exists
            File directory = new File(dirController.getXMLDirPath());
            if (!directory.exists()) {
                directory.mkdirs(); // Create the directory if it doesn't exist
            }

            // Create or open the folders
            Folder noXmlFolder = store.getFolder("NoXMLAttachments");
            if (!noXmlFolder.exists()) {
                noXmlFolder.create(Folder.HOLDS_MESSAGES); // Create if it doesn't exist
            }
            Folder processedFolder = store.getFolder("Processed");
            if (!processedFolder.exists()) {
                processedFolder.create(Folder.HOLDS_MESSAGES); // Create if it doesn't exist
            }

            for (Message message : messages) {
                boolean hasXmlAttachment = false;

                // Check for attachments
                if (message.isMimeType("multipart/*")) {
                    Multipart multipart = (Multipart) message.getContent();

                    for (int i = 0; i < multipart.getCount(); i++) {
                        BodyPart part = multipart.getBodyPart(i);

                        // Check if the part is an attachment and if it is an XML file
                        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) && part.getFileName().endsWith(".xml")) {
                            hasXmlAttachment = true;
                            emailsWithXmlAttachments++;

                            MimeBodyPart mimeBodyPart = (MimeBodyPart) part;

                            // Save the XML attachment to the specified directory
                            File file = new File(directory, mimeBodyPart.getFileName());
                            mimeBodyPart.saveFile(file); // Save directly to the file

                            alertasService.registrarAlerta("Info", "Saved XML attachment: " + file.getAbsolutePath(), null, 0, "EmailService.processUnreadXmlAttachments()", null, null);

                            // Parse the saved XML file
                            try (InputStream inputStream = new FileInputStream(file)) {
                                parser.parseXML(inputStream);
                                successfullyProcessedFiles++;
                            } catch (IOException | RuntimeException e) {
                                alertasService.registrarAlerta("Error", "Error parsing XML file: " + e.getMessage(), null, 0, "EmailService.processUnreadXmlAttachments()", null, e.getMessage());
                            }

                            // Mark the message as read
                            message.setFlag(Flags.Flag.SEEN, true);

                            // Move the message to the "Processed" folder
                            inbox.copyMessages(new Message[]{message}, processedFolder);
                            message.setFlag(Flags.Flag.DELETED, true); // Mark for deletion from INBOX
                            break;
                        }
                    }
                }

                // If no XML attachment was found, move the message to the "NoXMLAttachments" folder
                if (!hasXmlAttachment) {
                    inbox.copyMessages(new Message[]{message}, noXmlFolder);
                    message.setFlag(Flags.Flag.DELETED, true); // Mark for deletion from INBOX
                }
            }

            // Expunge deleted messages and close the folder
            inbox.close(true);  // Expunges deleted messages
            store.close();

            // Callback with detailed information
            callback.accept(String.format(
                "Processing completed: Total emails: %d, Emails with XML attachments: %d, Successfully processed XML files: %d",
                totalEmails, emailsWithXmlAttachments, successfullyProcessedFiles));

        } catch (MessagingException | IOException e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), null, 0, "EmailService.sendEmail()", null, e.getMessage());
            callback.accept("Encountered an Error: " + e.getLocalizedMessage());
        }
    }
    
    private void processUnreadXmlAttachmentsFallback(String email, String pass, Consumer<String> callback) {
        alertasService.registrarAlerta("Error", "FALLBACK: processUnreadXmlAttachments failed due to circuit breaker or repeated failures", null, 0, "EmailService.processUnreadXmlAttachmentsFallback()", null, null);
        CompletableFuture.runAsync(() -> callback.accept("Email processing skipped: Service temporarily unavailable due to repeated failures. Will retry on next scheduled run."));
    }

    @Timeout(value = 120, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 2000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailsWithAttachmentsFallback")
    public void sendEmailsWithAttachments(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nullable List<File> attachments, @Nonnull Consumer<String> callback) {
        final String[] status = {null};

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
                message.setHeader("Content-Type", "multipart/mixed; charset=utf-8");
                message.setFrom(new InternetAddress(email));

                InternetAddress[] toAddresses = new InternetAddress[to.size()];
                for (int i = 0; i < to.size(); i++) {
                    toAddresses[i] = new InternetAddress(to.get(i));
                }
                message.setRecipients(Message.RecipientType.TO, toAddresses);

                message.setSubject(subject);

                Multipart multipart = new MimeMultipart();

                // Body part
                BodyPart bodyPart = new MimeBodyPart();
                bodyPart.setContent(body, "text/plain; charset=utf-8");
                multipart.addBodyPart(bodyPart);

                // Attachments
                if (attachments != null) {
                    for (File attachment : attachments) {
                        if (attachment != null && attachment.exists()) {
                            BodyPart attachmentPart = new MimeBodyPart();
                            DataSource source = new FileDataSource(attachment);
                            attachmentPart.setDataHandler(new DataHandler(source));
                            attachmentPart.setFileName(attachment.getName());
                            multipart.addBodyPart(attachmentPart);
                        }
                    }
                }

                message.setContent(multipart);

                Transport.send(message);
                status[0] = "Sent";

            } catch (MessagingException e) {
                status[0] = "Encountered an Error: " + e.getLocalizedMessage();
                alertasService.registrarAlerta("Error", "Error sending email with attachments: " + e.getMessage(), null, 0, "EmailService.sendEmailsWithAttachments()", null, e.getMessage());
            }
        } else {
            alertasService.registrarAlerta("Info", "No email set up", null, 0, "EmailService.sendEmailsWithAttachments()", null, null);
            status[0] = "No Email Setup!";
        }

        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }

    private void sendEmailsWithAttachmentsFallback(List<String> to, String subject, String body, String email, String pass, List<File> attachments, Consumer<String> callback) {
        alertasService.registrarAlerta("Error", "FALLBACK: sendEmailsWithAttachments failed", null, 0, "EmailService.sendEmailsWithAttachmentsFallback()", null, null);
        CompletableFuture.runAsync(() -> callback.accept("Email with attachments failed: Timeout or error - please try again later"));
    }

}
