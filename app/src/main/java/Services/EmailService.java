package Services;

import Controllers.Settings.SettingsDirController;
import Models.ConfiguracionAplicacion;
import Models.Correos.ConfiguracionConexion;
import Models.Correos.ProveedorCorreo;
import Services.AppSettingsService;
import Utils.Parsers.Parser;
import Utils.XmlSeguro;
import org.jboss.logging.Logger;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
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

    private static final Logger LOG = Logger.getLogger(EmailService.class);

    @Inject @Nonnull Parser parser;
    @Inject @Nonnull SettingsDirController dirController;

    /**
     * Source of the provider endpoints. Every sender and the mailbox sweep read
     * the current settings row through here instead of the smtp.gmail.com /
     * imap.gmail.com constants this service used to carry, which is what made
     * the whole mail subsystem Gmail-only.
     */
    @Inject @Nonnull AppSettingsService appSettingsService;
    
    /**
     * Resolves the SMTP endpoints for the current configuration.
     *
     * <p>Read per call rather than cached: the settings row can change while
     * the app runs (the point of the feature), and a cached endpoint would keep
     * sending through the provider the operator just switched away from. A
     * settings read that fails (no row yet, table not migrated) degrades to the
     * Gmail preset, which is exactly what the hard-coded constants did.</p>
     */
    @Nonnull
    ConfiguracionConexion.Resuelta conexionActual() {
        ConfiguracionConexion.Resuelta porDefecto =
                ConfiguracionConexion.resolver(ProveedorCorreo.GMAIL, null, null, null, null);
        try {
            ConfiguracionAplicacion ajustes = appSettingsService.returnCurrent();
            if (ajustes == null) {
                return porDefecto;
            }
            String puerto = ajustes.getImapCorreoPuerto() != null
                    ? String.valueOf(ajustes.getImapCorreoPuerto()) : null;
            return ConfiguracionConexion.resolver(
                    ProveedorCorreo.desdeClave(ajustes.getProveedorCorreo()),
                    ajustes.getImapCorreoHost(), puerto,
                    ajustes.getSmtpCorreoHost(),
                    ajustes.getSmtpCorreoPuerto() != null
                            ? String.valueOf(ajustes.getSmtpCorreoPuerto()) : null);
        } catch (RuntimeException e) {
            LOG.warn("No se pudo leer la configuración del proveedor de correo; se usa Gmail"
                    + " | source=EmailService.conexionActual() | despues=" + e.getMessage());
            return porDefecto;
        }
    }

    /** SMTP props for the configured provider; identical to the old Gmail block when unconfigured. */
    @Nonnull
    private Properties propsSmtp() {
        return conexionActual().propiedadesSmtp();
    }

    @Timeout(value = 30, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailFallback")
    public void sendEmail(@Nonnull String to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nonnull Consumer<String> callback) {
        final String[] status = {null}; // Declare status as an array
        
        if (email != null && pass != null) {
            Properties props = propsSmtp();

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
                                LOG.warn("Error: " + e.getMessage() + " | source=" + "EmailService.sendEmail()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            }
        } else {
                        LOG.info("No email set up" + " | source=" + "EmailService.sendEmail()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendEmailFallback(String to, String subject, String body, String email, String pass, Consumer<String> callback) {
                LOG.warn("FALLBACK: sendEmail failed, notifying via callback" + " | source=" + "EmailService.sendEmailFallback()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        CompletableFuture.runAsync(() -> callback.accept("Email send failed: Timeout or error - please try again later"));
    }
    
    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailsFallback")
    public void sendEmails(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nonnull Consumer<String> callback) {
        final String[] status = {null}; // Declare status as an array
        
        if (email != null && pass != null) {
            Properties props = propsSmtp();

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
                                LOG.warn("Error: " + e.getMessage() + " | source=" + "EmailService.sendEmail()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            }
        } else {
                        LOG.info("No email set up" + " | source=" + "EmailService.sendEmail()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendEmailsFallback(List<String> to, String subject, String body, String email, String pass, Consumer<String> callback) {
                LOG.warn("FALLBACK: sendEmails failed, notifying via callback" + " | source=" + "EmailService.sendEmailsFallback()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        CompletableFuture.runAsync(() -> callback.accept("Email send failed: Timeout or error - please try again later"));
    }
    
    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailsWithAttachmentFallback")
    public void sendEmailsWithAttachment(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nullable File attachment, @Nonnull Consumer<String> callback) {
        final String[] status = {null}; // Declare status as an array
        
        if (email != null && pass != null) {
            Properties props = propsSmtp();

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
                                LOG.warn("Error: " + e.getMessage() + " | source=" + "EmailService.sendEmail()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            }
        } else {
                        LOG.info("No email set up" + " | source=" + "EmailService.sendEmail()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendEmailsWithAttachmentFallback(List<String> to, String subject, String body, String email, String pass, File attachment, Consumer<String> callback) {
                LOG.warn("FALLBACK: sendEmailsWithAttachment failed" + " | source=" + "EmailService.sendEmailsWithAttachmentFallback()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        CompletableFuture.runAsync(() -> callback.accept("Email with attachment failed: Timeout or error - please try again later"));
    }
    
    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @Fallback(fallbackMethod = "sendHtmlEmailsFallback")
    public void sendHtmlEmails(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String htmlBody, @Nullable String email, @Nullable String pass, @Nonnull Consumer<String> callback) {
        final String[] status = {null};
        
        if (email != null && pass != null) {
            Properties props = propsSmtp();

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
                                LOG.warn("Error sending HTML email: " + e.getMessage() + " | source=" + "EmailService.sendHtmlEmails()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            }
        } else {
                        LOG.info("No email set up" + " | source=" + "EmailService.sendHtmlEmails()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            status[0] = "No Email Setup!";
        }
        
        // Complete the CompletableFuture asynchronously
        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }
    
    private void sendHtmlEmailsFallback(List<String> to, String subject, String htmlBody, String email, String pass, Consumer<String> callback) {
                LOG.warn("FALLBACK: sendHtmlEmails failed, notifying via callback" + " | source=" + "EmailService.sendHtmlEmailsFallback()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
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
        int savedPdfAttachments = 0;

        try {
            // Endpoints del proveedor configurado (Gmail por omision, asi que
            // una instalacion que nunca toco el ajuste se conecta igual que
            // antes). Antes esto era la constante "imap.gmail.com".
            ConfiguracionConexion.Resuelta conexion = conexionActual();

            // Set up session and connect to the email server
            Session session = Session.getDefaultInstance(props);
            Store store = session.getStore("imaps");
            store.connect(conexion.imapHost(), conexion.imapPuerto(), email, pass);

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

            // Los PDF van a su propia carpeta, que ya existe en el perfil
            // (createPDFDir) y es donde PDFGenerator deja las facturas. Se
            // guardan ahi, y no junto a los XML, para que el directorio de XML
            // siga siendo solo lo que el parser consume.
            File pdfDirectory = new File(dirController.getPDFDirPath());
            if (!pdfDirectory.exists()) {
                pdfDirectory.mkdirs();
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
                ResultadoAdjuntos resultado = procesarAdjuntosDe(message, directory, pdfDirectory);

                emailsWithXmlAttachments += resultado.xmlVistos;
                successfullyProcessedFiles += resultado.xmlProcesados;
                savedPdfAttachments += resultado.pdfGuardados;

                if (resultado.esRecibo()) {
                    // Recibo: queda marcado como leido y se archiva en Processed.
                    message.setFlag(Flags.Flag.SEEN, true);
                    inbox.copyMessages(new Message[]{message}, processedFolder);
                } else {
                    // Sin XML utilizable: a NoXMLAttachments (mismo nombre de
                    // carpeta que antes).
                    inbox.copyMessages(new Message[]{message}, noXmlFolder);
                }
                message.setFlag(Flags.Flag.DELETED, true); // Mark for deletion from INBOX
            }

            // Expunge deleted messages and close the folder
            inbox.close(true);  // Expunges deleted messages
            store.close();

            // Callback with detailed information. El prefijo "Processing completed"
            // lo lee ProgramadorTareas.handleEmailProcess(), asi que no cambia.
            callback.accept(String.format(
                "Processing completed: Total emails: %d, Emails with XML attachments: %d, Successfully processed XML files: %d, Saved PDF attachments: %d",
                totalEmails, emailsWithXmlAttachments, successfullyProcessedFiles, savedPdfAttachments));

        } catch (MessagingException e) {
                        LOG.warn("Error: " + e.getMessage() + " | source=" + "EmailService.sendEmail()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            callback.accept("Encountered an Error: " + e.getLocalizedMessage());
        }    }

    /**
     * What one message contributed to the sweep.
     *
     * @param esRecibo        true when at least one Hacienda XML was parsed
     *                        successfully; drives Processed vs NoXMLAttachments
     * @param xmlVistos       XML attachments the sender sent, whether or not
     *                        they could be used (reported, not a decision)
     * @param xmlProcesados   XML files handed to the parser without error
     * @param pdfGuardados    PDF companions written to the PDF directory
     */
    record ResultadoAdjuntos(boolean esRecibo, int xmlVistos, int xmlProcesados, int pdfGuardados) {
    }

    /**
     * Walks one message's attachments: saves every PDF companion and feeds
     * every Hacienda XML to the parser.
     *
     * <p>Extracted from the mailbox loop so the receipt rule is testable
     * without an IMAP server — {@code jakarta.mail} builds these messages in
     * memory, which is all this method touches.</p>
     *
     * <p><b>The receipt rule.</b> A message is a receipt when at least one XML
     * parsed. The previous code decided on "had an .xml attachment" and
     * {@code break}ed out of the loop on the first one, which meant a message
     * carrying XML + PDF saved only the XML (the PDF was never reached) and a
     * message whose XML failed to parse was still filed as Processed. Both are
     * fixed here: every attachment is visited, and only a successful parse
     * counts.</p>
     *
     * <p>A PDF is stored next to the XML directory and never parsed: it is a
     * human-readable representation of a document the XML already carries, and
     * running it through the XML parser would fail and, before the fix, would
     * have mis-filed the message.</p>
     */
    @Nonnull
    ResultadoAdjuntos procesarAdjuntosDe(@Nonnull Message message,
                                         @Nonnull File directorioXml,
                                         @Nonnull File directorioPdf) {
        int xmlVistos = 0;
        int xmlProcesados = 0;
        int pdfGuardados = 0;

        try {
            // Check for attachments
            if (!message.isMimeType("multipart/*")) {
                return new ResultadoAdjuntos(false, 0, 0, 0);
            }
            Multipart multipart = (Multipart) message.getContent();

            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);

                // getFileName() can be null (a MIME part with no filename
                // parameter); the old code dereferenced it directly and threw
                // an NPE that aborted the whole mailbox sweep.
                String nombreAdjunto = part.getFileName();
                if (nombreAdjunto == null
                        || !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    continue;
                }
                String minusculas = nombreAdjunto.toLowerCase(Locale.ROOT);

                // PDF acompañante: se guarda, nunca se parsea. El nombre lo
                // elige el remitente, asi que pasa por la misma puerta de
                // seguridad que el XML.
                if (minusculas.endsWith(".pdf")) {
                    File pdf = resolverNombreSeguroAdjunto(directorioPdf, nombreAdjunto, ".pdf");
                    if (pdf == null) {
                        LOG.warn("Nombre de adjunto PDF rechazado por el remitente: "
                                + nombreAdjunto + " | source=EmailService.procesarAdjuntosDe()");
                        continue;
                    }
                    try {
                        ((MimeBodyPart) part).saveFile(pdf);
                        pdfGuardados++;
                        LOG.info("Saved PDF attachment: " + pdf.getAbsolutePath()
                                + " | source=EmailService.procesarAdjuntosDe()");
                    } catch (IOException | MessagingException e) {
                        LOG.warn("No se pudo guardar el PDF adjunto " + nombreAdjunto + ": " + e.getMessage()
                                + " | source=EmailService.procesarAdjuntosDe()");
                    }
                    continue;
                }

                if (!minusculas.endsWith(".xml")) {
                    continue;
                }
                xmlVistos++;

                // The sender of the e-mail controls this string, so it is never
                // used as a path. See resolverNombreSeguroAdjunto().
                File file = resolverNombreSeguroAdjunto(directorioXml, nombreAdjunto);
                if (file == null) {
                    LOG.warn("Nombre de adjunto XML rechazado por el remitente: "
                            + nombreAdjunto + " | source=EmailService.procesarAdjuntosDe()");
                    continue;
                }
                ((MimeBodyPart) part).saveFile(file);

                LOG.info("Saved XML attachment: " + file.getAbsolutePath()
                        + " | source=EmailService.procesarAdjuntosDe()");

                // Same hardened gate the upload path uses. This call had none,
                // so an e-mailed DOCTYPE reached the parser (and its
                // external-entity-capable consumers) unchecked.
                byte[] contenido = Files.readAllBytes(file.toPath());
                String xml = new String(contenido, StandardCharsets.UTF_8).trim();
                if (XmlSeguro.contieneDoctype(xml)) {
                    LOG.warn("Adjunto XML con DOCTYPE rechazado: " + file.getName()
                            + " | source=EmailService.procesarAdjuntosDe()");
                    continue;
                }

                // Parse the saved XML file
                try (InputStream inputStream = new FileInputStream(file)) {
                    parser.parseXML(inputStream);
                    xmlProcesados++;
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Error parsing XML file: " + e.getMessage()
                            + " | source=EmailService.procesarAdjuntosDe()"
                            + " | despues=" + e.getMessage());
                }
            }
        } catch (MessagingException | IOException e) {
            // Un mensaje ilegible no puede abortar el barrido de los demas: se
            // registra y sigue. Sin esto, un solo correo corrupto dejaba la
            // bandeja sin revisar hasta el siguiente ciclo de 15 minutos.
            LOG.warn("No se pudieron leer los adjuntos del mensaje: " + e.getMessage()
                    + " | source=EmailService.procesarAdjuntosDe()");
        }

        return new ResultadoAdjuntos(xmlProcesados > 0, xmlVistos, xmlProcesados, pdfGuardados);
    }

    /**
     * Turns an e-mail attachment filename into a path INSIDE {@code directorio},
     * or returns {@code null} if it cannot be done safely.
     *
     * <p>The filename is chosen by whoever sent the message. Passing it to
     * {@code new File(directory, nombre)} lets that sender escape the target
     * directory with {@code ../} segments and choose the destination extension,
     * so a remote party could overwrite arbitrary files the service account can
     * write. Sanitising is therefore mandatory, not cosmetic.</p>
     *
     * <p>Three layers, because any one alone is defeatable:</p>
     * <ol>
     *   <li>Take the last path segment only, so no separator survives.</li>
     *   <li>Reject outright if anything remains suspicious ({@code ..}, a
     *       Windows drive prefix, a NUL, or characters outside a conservative
     *       allowlist). Names are usually plain, so rejection is cheap.</li>
     *   <li>Re-verify with {@code getCanonicalPath()} that the result really is
     *       under the canonical target — belt and braces against any encoding
     *       trick that survived 1 and 2.</li>
     * </ol>
     *
     * @return the resolved file, or {@code null} to skip this attachment.
     */
    @Nullable
    static File resolverNombreSeguroAdjunto(@Nonnull File directorio,
                                            @Nonnull String nombreOriginal) {
        return resolverNombreSeguroAdjunto(directorio, nombreOriginal, ".xml");
    }

    /**
     * Same three layers, for an attachment whose extension is not necessarily
     * {@code .xml}.
     *
     * <p>The PDF companions added to the mailbox sweep arrive through the very
     * same hostile channel as the XMLs — the filename is whatever the remote
     * sender typed — so they get the same gate rather than a copy of it that
     * could drift. {@code extension} must include the dot and is matched
     * case-insensitively against a lowercase name, so an operator cannot widen
     * the allowlist by passing {@code ".p\u0066"} and friends.</p>
     *
     * @param extension required lowercase extension, e.g. {@code ".xml"} or
     *                  {@code ".pdf"}
     */
    @Nullable
    static File resolverNombreSeguroAdjunto(@Nonnull File directorio,
                                            @Nonnull String nombreOriginal,
                                            @Nonnull String extension) {
        // 1. Strip any directory component the sender embedded.
        String nombre = nombreOriginal;
        int ultimoSeparador = Math.max(nombre.lastIndexOf('/'), nombre.lastIndexOf('\\'));
        if (ultimoSeparador >= 0) {
            nombre = nombre.substring(ultimoSeparador + 1);
        }
        nombre = nombre.trim();
        if (nombre.isEmpty() || nombre.length() > 200) {
            return null;
        }
        // 2. Reject anything still structurally suspicious.
        if (nombre.contains("..") || nombre.indexOf('\0') >= 0
                || nombre.contains(":") || nombre.equals(".") || nombre.equals("..")) {
            return null;
        }
        // El nombre se compara en minúsculas para que ".XML" (valido en
        // Windows, y por tanto enviable) tambien pase, pero la expresion
        // regular exige que la extension sea exactamente la esperada: nada de
        // "factura.pdf.xml".
        String nombreNormalizado = nombre.toLowerCase(Locale.ROOT);
        String sufijoEsperado = extension.startsWith(".")
                ? extension.toLowerCase(Locale.ROOT)
                : "." + extension.toLowerCase(Locale.ROOT);
        if (!nombreNormalizado.endsWith(sufijoEsperado)) {
            return null;
        }
        String cuerpo = nombreNormalizado.substring(0, nombreNormalizado.length() - sufijoEsperado.length());
        if (cuerpo.isEmpty() || !cuerpo.matches("[a-z0-9._-]+")) {
            return null;
        }
        // 3. Confirm the canonical path really lands inside the target.
        try {
            File destino = new File(directorio, nombre);
            String canonicoDestino = destino.getCanonicalPath();
            String canonicoBase = directorio.getCanonicalPath();
            if (!canonicoDestino.startsWith(canonicoBase + File.separator)) {
                LOG.warn("Adjunto descartado: la ruta resuelta sale del directorio destino");
                return null;
            }
            return destino;
        } catch (IOException e) {
            LOG.warn("No se pudo resolver la ruta del adjunto: " + e.getMessage());
            return null;
        }
    }

    private void processUnreadXmlAttachmentsFallback(String email, String pass, Consumer<String> callback) {
                LOG.warn("FALLBACK: processUnreadXmlAttachments failed due to circuit breaker or repeated failures" + " | source=" + "EmailService.processUnreadXmlAttachmentsFallback()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        CompletableFuture.runAsync(() -> callback.accept("Email processing skipped: Service temporarily unavailable due to repeated failures. Will retry on next scheduled run."));
    }

    @Timeout(value = 120, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 2000, jitter = 500)
    @Fallback(fallbackMethod = "sendEmailsWithAttachmentsFallback")
    public void sendEmailsWithAttachments(@Nonnull List<String> to, @Nonnull String subject, @Nonnull String body, @Nullable String email, @Nullable String pass, @Nullable List<File> attachments, @Nonnull Consumer<String> callback) {
        final String[] status = {null};

        if (email != null && pass != null) {
            Properties props = propsSmtp();

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
                                LOG.warn("Error sending email with attachments: " + e.getMessage() + " | source=" + "EmailService.sendEmailsWithAttachments()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            }
        } else {
                        LOG.info("No email set up" + " | source=" + "EmailService.sendEmailsWithAttachments()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            status[0] = "No Email Setup!";
        }

        CompletableFuture.runAsync(() -> callback.accept(status[0]));
    }

    private void sendEmailsWithAttachmentsFallback(List<String> to, String subject, String body, String email, String pass, List<File> attachments, Consumer<String> callback) {
                LOG.warn("FALLBACK: sendEmailsWithAttachments failed" + " | source=" + "EmailService.sendEmailsWithAttachmentsFallback()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        CompletableFuture.runAsync(() -> callback.accept("Email with attachments failed: Timeout or error - please try again later"));
    }

}
