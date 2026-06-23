package Controllers;

import Services.ComprobantesRecibidosService;
import Services.Facturas.*;
import Services.AlertasService;
import Utils.Parsers.Parser;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import lombok.Data;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;

/**
 *
 * @author Al
 */

@Data
@Named
@RequestScoped
public class uploadController {
    @Inject ComprobantesRecibidosService facturaService;
    @Inject SessionController currentSession;
    @Inject Parser parser;
    @Inject AlertasService alertas;
    @Inject ExecutorService executorService;
    private UploadedFile file;
    
    // Queue for processing files sequentially
    private static Queue<FileData> fileQueue = new LinkedList<>();
    private static volatile boolean isProcessing = false;
    
    // Inner class to store file data before temp files are deleted
    private static class FileData {
        private final String fileName;
        private final String contentType;
        private final byte[] fileContent;
        private final String currentUser;
        
        public FileData(UploadedFile uploadedFile, String currentUser) throws Exception {
            this.fileName = uploadedFile.getFileName();
            this.contentType = uploadedFile.getContentType();
            this.fileContent = uploadedFile.getContent();
            this.currentUser = currentUser;
        }
        
        public String getFileName() { return fileName; }
        public String getContentType() { return contentType; }
        public byte[] getFileContent() { return fileContent; }
        public long getSize() { return fileContent.length; }
        public InputStream getInputStream() { return new ByteArrayInputStream(fileContent); }
        public String getCurrentUser() { return currentUser; }
    }
        
    public void handleFileUpload(FileUploadEvent event) {
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
                } catch (Exception e) {
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
        } catch (Exception e) {
            alertas.registrarAlerta("Error al procesar archivo", "Error al procesar archivo: " + e.getMessage(), currentSession.getCurrentUser(), 0, "handleFileUpload()", null, e.getMessage());
            alertas.registrarAlerta("Error", "DEBUG: Error in handleFileUpload: " + e.getMessage(), currentSession.getCurrentUser(), 0, "uploadController.handleFileUpload()", null, e.getMessage());
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo procesar el archivo: " + e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
     }
     
private void processQueueAsync() {
         alertas.registrarAlerta("Info", "DEBUG: Starting async processing with virtual threads, isProcessing=" + isProcessing + ", queueSize=" + fileQueue.size(), currentSession.getCurrentUser(), 0, "uploadController.processQueueAsync()", null, null);
         
         // Start virtual thread for better I/O performance
         Thread.startVirtualThread(() -> {
             try {
                 synchronized (uploadController.class) {
                     isProcessing = true;
                 }
                 alertas.registrarAlerta("Info", "DEBUG: Virtual thread started, isProcessing set to true", currentSession.getCurrentUser(), 0, "uploadController.processQueueAsync()", null, null);
                 int fileCount = 0;
                 while (true) {
                     FileData fileData;
                     synchronized (fileQueue) {
                         fileData = fileQueue.poll();
                         if (fileData == null) break;
                     }
                     alertas.registrarAlerta("Info", "DEBUG: Processing file #" + (++fileCount) + ": " + fileData.getFileName() + " in virtual thread", currentSession.getCurrentUser(), 0, "uploadController.processQueueAsync()", null, null);
                     processSingleFile(fileData);
                     alertas.registrarAlerta("Info", "DEBUG: Finished processing file: " + fileData.getFileName(), currentSession.getCurrentUser(), 0, "uploadController.processQueueAsync()", null, null);
                 }
                 synchronized (uploadController.class) {
                     isProcessing = false;
                 }
                 alertas.registrarAlerta("Info", "DEBUG: Virtual thread completed, processed " + fileCount + " files, isProcessing set to false", currentSession.getCurrentUser(), 0, "uploadController.processQueueAsync()", null, null);
             } catch (Exception e) {
                alertas.registrarAlerta("Error", "Error in virtual thread processing: " + e.getMessage(), currentSession.getCurrentUser(), 0, "uploadController.processQueueAsync()", null, e.getMessage());
                synchronized (uploadController.class) {
                     isProcessing = false;
                 }
                 alertas.registrarAlerta("Info", "DEBUG: Virtual thread failed due to error, isProcessing set to false", currentSession.getCurrentUser(), 0, "uploadController.processQueueAsync()", null, null);
             }
         });
     }
     
     private void processSingleFile(FileData fileData) {
         try {
             alertas.registrarAlerta("Info", "DEBUG: Starting processSingleFile for: " + fileData.getFileName(), currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
             alertas.registrarAlerta("Info", "DEBUG: File size: " + fileData.getSize() + " bytes", currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
             alertas.registrarAlerta("Info", "DEBUG: File content type: " + fileData.getContentType(), currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
             
             if (fileData == null || fileData.getSize() == 0) {
                 alertas.registrarAlerta("Info", "DEBUG: File is null or empty", currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
                 alertas.registrarAlerta("Error", "ERROR: File is null or empty: " + fileData.getFileName(), currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
                 return;
             }
             
             alertas.registrarAlerta("Info", "DEBUG: About to process XML file", currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
             
             // Process file directly without using FacturasController (to avoid ViewScoped issues)
             try (InputStream inputStream = fileData.getInputStream()) {
                 processXMLDirectly(fileData, inputStream);
             }
             alertas.registrarAlerta("Info", "DEBUG: XML file processing completed successfully", currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
             
              alertas.registrarAlerta("Info", "DEBUG: File processed successfully: " + fileData.getFileName(), currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, null);
          } catch (Exception e) {
              alertas.registrarAlerta("Error al procesar archivo", "Archivo: " + fileData.getFileName() + " - Error: " + e.getMessage(), null, 0, "processSingleFile()", fileData.getFileName(), e.getMessage());
            alertas.registrarAlerta("Error", "DEBUG: Error processing file " + fileData.getFileName() + ": " + e.getMessage(), currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, e.getMessage());
            alertas.registrarAlerta("Error", "ERROR: Failed to process file " + fileData.getFileName() + ": " + e.getMessage(), currentSession.getCurrentUser(), 0, "uploadController.processSingleFile()", null, e.getMessage());
          }
     }
     
private void processXMLDirectly(FileData fileData, InputStream inputStream) throws Exception {
        alertas.registrarAlerta("Info", "DEBUG: Processing XML directly for: " + fileData.getFileName() + " by user: " + fileData.getCurrentUser(), currentSession.getCurrentUser(), 0, "uploadController.processXMLDirectly()", null, null);
        
        if (fileData == null || fileData.getSize() == 0) {
            alertas.registrarAlerta("Error", "File is null or empty: " + fileData.getFileName(), currentSession.getCurrentUser(), 0, "uploadController.processXMLDirectly()", null, null);
            return;
        }
        
        alertas.registrarAlerta("Info", "File details: " + fileData.getFileName() + " Size: " + fileData.getSize() + " Type: " + fileData.getContentType() + " User: " + fileData.getCurrentUser(), currentSession.getCurrentUser(), 0, "uploadController.processXMLDirectly()", null, null);
        
        // Mark stream so we can reset after reading preview
        inputStream.mark(1024);
        
        // Read first few bytes to verify file content
        byte[] buffer = new byte[1024];
        int bytesRead = inputStream.read(buffer);
        alertas.registrarAlerta("Info", "Read " + bytesRead + " bytes from file", currentSession.getCurrentUser(), 0, "uploadController.processXMLDirectly()", null, null);
        
        if (bytesRead > 0) {
            String preview = new String(buffer, 0, Math.min(bytesRead, 200));
            alertas.registrarAlerta("Info", "File preview: " + preview, currentSession.getCurrentUser(), 0, "uploadController.processXMLDirectly()", null, null);
        }
        
        // Reset stream for parser
        inputStream.reset();
        
// Parse XML using parser service - temporarily store user in ThreadLocal for async access
        try {
            // Store current user in a thread-safe way for parser to access
            Utils.AsyncUserContext.setCurrentUser(fileData.getCurrentUser());
            parser.parseXML(inputStream);
            alertas.registrarAlerta("Info", "Successfully processed file: " + fileData.getFileName(), currentSession.getCurrentUser(), 0, "uploadController.processXMLDirectly()", null, null);
        } catch (Exception e) {
            alertas.registrarAlerta("Error al parsear XML", "Archivo: " + fileData.getFileName() + " - Error: " + e.getMessage(), null, 0, "processXMLDirectly()", fileData.getFileName(), e.getMessage());
            throw e;
        } finally {
            Utils.AsyncUserContext.clear();
        }
    }
         
}