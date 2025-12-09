package Services;

import Controllers.Settings.SettingsDirController;
import Utils.Parsers.Parser;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
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

/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class EmailService implements Serializable {
    
    @Inject Parser parser;
    @Inject SettingsDirController dirController;
    
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

    public void processUnreadXmlAttachments(String email, String pass, Consumer<String> callback) {
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

                            System.out.println("Saved XML attachment: " + file.getAbsolutePath());

                            // Parse the saved XML file
                            try (InputStream inputStream = new FileInputStream(file)) {
                                parser.parseXML(inputStream);
                                successfullyProcessedFiles++;
                            } catch (Exception e) {
                                System.out.println("Error parsing XML file: " + e.getMessage());
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
            System.out.println("Error: " + e.getLocalizedMessage());
            callback.accept("Encountered an Error: " + e.getLocalizedMessage());
        }
    }

}
