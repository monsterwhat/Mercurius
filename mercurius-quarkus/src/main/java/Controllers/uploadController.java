package Controllers;

import Services.ComprobantesRecibidosService;
import Services.Facturas.*;
import Services.AlertasService;
import Utils.Parsers.Parser;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;

/**
 *
 * @author Al
 */

@Getter @Setter @ToString @EqualsAndHashCode
@Named
@RequestScoped
public class uploadController {
    @Inject @Nonnull ComprobantesRecibidosService facturaService;
    @Inject @Nonnull SessionController currentSession;
    @Inject @Nonnull Parser parser;
    @Inject @Nonnull AlertasService alertas;
    @Inject @Nonnull ExecutorService executorService;
    @Nullable
    private UploadedFile file;
    
    // Queue for processing files sequentially
    private static Queue<FileData> fileQueue = new LinkedList<>();
    private static volatile boolean isProcessing = false;
    
    // Inner class to store file data before temp files are deleted
    private static class FileData {
        @Nonnull
        private final String fileName;
        @Nonnull
        private final String contentType;
        @Nonnull
        private final byte[] fileContent;
        @Nonnull
        private final String currentUser;
        
        public FileData(@Nonnull UploadedFile uploadedFile, @Nonnull String currentUser) {
            this.fileName = uploadedFile.getFileName();
            this.contentType = uploadedFile.getContentType();
            this.fileContent = uploadedFile.getContent();
            this.currentUser = currentUser;
        }
        
        @Nonnull
        public String getFileName() { return fileName; }
        @Nonnull
        public String getContentType() { return contentType; }
        @Nonnull
        public byte[] getFileContent() { return fileContent; }
        public long getSize() { return fileContent.length; }
        @Nonnull
        public InputStream getInputStream() { return new ByteArrayInputStream(fileContent); }
        @Nonnull
        public String getCurrentUser() { return currentUser; }
    }
        
    public void handleFileUpload(@Nonnull FileUploadEvent event) {
        try {
            alertas.registrarAlerta("Info", "DEBUG: handleFileUpload called", currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, null);
            // Add uploaded file to queue for sequential processing
            UploadedFile uploadedFile = event.getFile();
            alertas.registrarAlerta("Info", "DEBUG: File received: " + (uploadedFile != null ? uploadedFile.getFileName() : "null"), currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, null);
            
            if (uploadedFile != null) {
                // Capture current user before async processing
                String currentUser = null;
                try {
                    currentUser = currentSession.getCurrentUser().getUsername();
                } catch (RuntimeException e) {
                    alertas.registrarAlerta("Warning", "Warning: Could not get current user, using default: " + e.getMessage(), null, 0, "uploadController.handleFileUpload()", null, e.getMessage());
                    currentUser = "system";
                }
                
                // Convert to FileData to preserve content before temp file deletion
                FileData fileData = new FileData(uploadedFile, currentUser);
                synchronized (fileQueue) {
                    int beforeSize = fileQueue.size();
                    fileQueue.offer(fileData);
                    int afterSize = fileQueue.size();
                    alertas.registrarAlerta("Info", "DEBUG: File added to queue. Queue size: " + beforeSize + " -> " + afterSize + " for user: " + currentUser, currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, null);
                }
            }
            
            // Start processing queue if not already processing (thread-safe check)
            synchronized (uploadController.class) {
                alertas.registrarAlerta("Info", "DEBUG: Checking processing conditions - isProcessing=" + isProcessing + ", queueSize=" + fileQueue.size(), currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, null);
                if (!isProcessing && !fileQueue.isEmpty()) {
                    alertas.registrarAlerta("Info", "DEBUG: Starting processQueueAsync", currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, null);
                    processQueueAsync();
                } else {
                    alertas.registrarAlerta("Info", "DEBUG: Not starting async processing - isProcessing=" + isProcessing + ", queueEmpty=" + fileQueue.isEmpty(), currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, null);
                }
            }
            
            // Update UI to show file
            FacesMessage message = new FacesMessage("Exito.", "Archivo '" + uploadedFile.getFileName() + "' agregado a la cola de procesamiento.");
            FacesContext.getCurrentInstance().addMessage(null, message);
            alertas.registrarAlerta("Info", "DEBUG: handleFileUpload completed for: " + uploadedFile.getFileName(), currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, null);
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error al procesar archivo", "Error al procesar archivo: " + e.getMessage(), currentSession.getCurrentUser(), 0, "handleFileUpload()", null, e.getMessage());
            alertas.registrarAlerta("Error", "DEBUG: Error in handleFileUpload: " + e.getMessage(), currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, e.getMessage());
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo procesar el archivo: " + e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
     }
     
private void processQueueAsync() {
         // Capture current user before spawning virtual thread (session context won't be available inside)
         Models.Users currentUser;
         try {
             currentUser = currentSession.getCurrentUser();
         } catch (RuntimeException e) {
             currentUser = null;
         }

         alertas.registrarAlerta("Info", "DEBUG: Starting async processing with virtual threads, isProcessing=" + isProcessing + ", queueSize=" + fileQueue.size(), currentUser, 0, "uploadController.processQueueAsync()", null, null);

         final Models.Users capturedUser = currentUser;

         // Start virtual thread for better I/O performance
         Thread.startVirtualThread(() -> {
             try {
                 synchronized (uploadController.class) {
                     isProcessing = true;
                 }
                 alertas.registrarAlerta("Info", "DEBUG: Virtual thread started, isProcessing set to true", capturedUser, 0, "uploadController.processQueueAsync()", null, null);
                 int fileCount = 0;
                 while (true) {
                     FileData fileData;
                     synchronized (fileQueue) {
                         fileData = fileQueue.poll();
                         if (fileData == null) break;
                     }
                      alertas.registrarAlerta("Info", "DEBUG: Processing file #" + (++fileCount) + ": " + fileData.getFileName() + " in virtual thread", capturedUser, 0, "uploadController.processQueueAsync()", null, null);
                      processSingleFile(fileData, capturedUser);
                      alertas.registrarAlerta("Info", "DEBUG: Finished processing file: " + fileData.getFileName(), capturedUser, 0, "uploadController.processQueueAsync()", null, null);
                 }
                 synchronized (uploadController.class) {
                     isProcessing = false;
                 }
                 alertas.registrarAlerta("Info", "DEBUG: Virtual thread completed, processed " + fileCount + " files, isProcessing set to false", capturedUser, 0, "uploadController.processQueueAsync()", null, null);
             } catch (RuntimeException e) {
                alertas.registrarAlerta("Error", "Error in virtual thread processing: " + e.getMessage(), capturedUser, 0, "uploadController.processQueueAsync()", null, e.getMessage());
                synchronized (uploadController.class) {
                     isProcessing = false;
                 }
                 alertas.registrarAlerta("Info", "DEBUG: Virtual thread failed due to error, isProcessing set to false", capturedUser, 0, "uploadController.processQueueAsync()", null, null);
             }
         });
     }
     
       private void processSingleFile(FileData fileData, @Nullable Models.Users user) {
          // NOTE: This runs inside a virtual thread where @SessionScoped beans are NOT available.
          // The `user` parameter is captured from the HTTP request context before the thread starts.
           try {
              alertas.registrarAlerta("Info", "DEBUG: Starting processSingleFile for: " + fileData.getFileName(), user, 0, "uploadController.processSingleFile()", null, null);
              alertas.registrarAlerta("Info", "DEBUG: File size: " + fileData.getSize() + " bytes", user, 0, "uploadController.processSingleFile()", null, null);
              alertas.registrarAlerta("Info", "DEBUG: File content type: " + fileData.getContentType(), user, 0, "uploadController.processSingleFile()", null, null);
              
              if (fileData == null || fileData.getSize() == 0) {
                  alertas.registrarAlerta("Info", "DEBUG: File is null or empty", user, 0, "uploadController.processSingleFile()", null, null);
                  alertas.registrarAlerta("Error", "ERROR: File is null or empty: " + fileData.getFileName(), user, 0, "uploadController.processSingleFile()", null, null);
                  return;
              }
              
              alertas.registrarAlerta("Info", "DEBUG: About to process XML file", user, 0, "uploadController.processSingleFile()", null, null);
              
              // Process file directly without using FacturasController (to avoid ViewScoped issues)
              try (InputStream inputStream = fileData.getInputStream()) {
                  processXMLDirectly(fileData, inputStream, user);
              }
              alertas.registrarAlerta("Info", "DEBUG: XML file processing completed successfully", user, 0, "uploadController.processSingleFile()", null, null);
              
               alertas.registrarAlerta("Info", "DEBUG: File processed successfully: " + fileData.getFileName(), user, 0, "uploadController.processSingleFile()", null, null);
            } catch (IOException | RuntimeException e) {
                 alertas.registrarAlerta("Error al procesar archivo", "Archivo: " + fileData.getFileName() + " - Error: " + e.getMessage(), null, 0, "processSingleFile()", fileData.getFileName(), e.getMessage());
             alertas.registrarAlerta("Error", "DEBUG: Error processing file " + fileData.getFileName() + ": " + e.getMessage(), user, 0, "uploadController.processSingleFile()", null, e.getMessage());
             alertas.registrarAlerta("Error", "ERROR: Failed to process file " + fileData.getFileName() + ": " + e.getMessage(), user, 0, "uploadController.processSingleFile()", null, e.getMessage());
           }
      }
     
private void processXMLDirectly(FileData fileData, InputStream inputStream, @Nullable Models.Users user) throws IOException {
        alertas.registrarAlerta("Info", "DEBUG: Processing XML directly for: " + fileData.getFileName() + " by user: " + fileData.getCurrentUser(), user, 0, "uploadController.processXMLDirectly()", null, null);
        
        if (fileData == null || fileData.getSize() == 0) {
            alertas.registrarAlerta("Error", "File is null or empty: " + fileData.getFileName(), user, 0, "uploadController.processXMLDirectly()", null, null);
            return;
        }
        
        alertas.registrarAlerta("Info", "File details: " + fileData.getFileName() + " Size: " + fileData.getSize() + " Type: " + fileData.getContentType() + " User: " + fileData.getCurrentUser(), user, 0, "uploadController.processXMLDirectly()", null, null);
        
        // Mark stream so we can reset after reading preview
        inputStream.mark(1024);
        
        // Read first few bytes to verify file content
        byte[] buffer = new byte[1024];
        int bytesRead = inputStream.read(buffer);
        alertas.registrarAlerta("Info", "Read " + bytesRead + " bytes from file", user, 0, "uploadController.processXMLDirectly()", null, null);
        
        if (bytesRead > 0) {
            String preview = new String(buffer, 0, Math.min(bytesRead, 200));
            alertas.registrarAlerta("Info", "File preview: " + preview, user, 0, "uploadController.processXMLDirectly()", null, null);
        }
        
        // Reset stream for parser
        inputStream.reset();
        
// Parse XML using parser service - temporarily store user in ThreadLocal for async access
        try {
            // Store current user in a thread-safe way for parser to access
            Utils.AsyncUserContext.setCurrentUser(fileData.getCurrentUser());
            parser.parseXML(inputStream);
            alertas.registrarAlerta("Info", "Successfully processed file: " + fileData.getFileName(), user, 0, "uploadController.processXMLDirectly()", null, null);
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error al parsear XML", "Archivo: " + fileData.getFileName() + " - Error: " + e.getMessage(), null, 0, "processXMLDirectly()", fileData.getFileName(), e.getMessage());
            throw e;
        } finally {
            Utils.AsyncUserContext.clear();
        }
    }
         
}