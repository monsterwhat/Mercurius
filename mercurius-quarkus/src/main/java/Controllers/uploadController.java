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
            System.out.println("DEBUG: handleFileUpload called");
            // Add uploaded file to queue for sequential processing
            UploadedFile uploadedFile = event.getFile();
            System.out.println("DEBUG: File received: " + (uploadedFile != null ? uploadedFile.getFileName() : "null"));
            
            if (uploadedFile != null) {
                // Capture current user before async processing
                String currentUser = null;
                try {
                    currentUser = currentSession.getCurrentUser().getUsername();
                } catch (Exception e) {
                    System.err.println("Warning: Could not get current user, using default: " + e.getMessage());
                    currentUser = "system";
                }
                
                // Convert to FileData to preserve content before temp file deletion
                FileData fileData = new FileData(uploadedFile, currentUser);
                synchronized (fileQueue) {
                    int beforeSize = fileQueue.size();
                    fileQueue.offer(fileData);
                    int afterSize = fileQueue.size();
                    System.out.println("DEBUG: File added to queue. Queue size: " + beforeSize + " -> " + afterSize + " for user: " + currentUser);
                }
            }
            
            // Start processing queue if not already processing (thread-safe check)
            synchronized (uploadController.class) {
                System.out.println("DEBUG: Checking processing conditions - isProcessing=" + isProcessing + ", queueSize=" + fileQueue.size());
                if (!isProcessing && !fileQueue.isEmpty()) {
                    System.out.println("DEBUG: Starting processQueueAsync");
                    processQueueAsync();
                } else {
                    System.out.println("DEBUG: Not starting async processing - isProcessing=" + isProcessing + ", queueEmpty=" + fileQueue.isEmpty());
                }
            }
            
            // Update UI to show file
            FacesMessage message = new FacesMessage("Exito.", "Archivo '" + uploadedFile.getFileName() + "' agregado a la cola de procesamiento.");
            FacesContext.getCurrentInstance().addMessage(null, message);
            System.out.println("DEBUG: handleFileUpload completed for: " + uploadedFile.getFileName());
        } catch (Exception e) {
            System.err.println("DEBUG: Error in handleFileUpload: " + e.getMessage());
            e.printStackTrace();
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo procesar el archivo: " + e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
     }
     
private void processQueueAsync() {
         System.out.println("DEBUG: Starting async processing with virtual threads, isProcessing=" + isProcessing + ", queueSize=" + fileQueue.size());
         
         // Start virtual thread for better I/O performance
         Thread.startVirtualThread(() -> {
             try {
                 synchronized (uploadController.class) {
                     isProcessing = true;
                 }
                 System.out.println("DEBUG: Virtual thread started, isProcessing set to true");
                 int fileCount = 0;
                 while (true) {
                     FileData fileData;
                     synchronized (fileQueue) {
                         fileData = fileQueue.poll();
                         if (fileData == null) break;
                     }
                     System.out.println("DEBUG: Processing file #" + (++fileCount) + ": " + fileData.getFileName() + " in virtual thread");
                     processSingleFile(fileData);
                     System.out.println("DEBUG: Finished processing file: " + fileData.getFileName());
                 }
                 synchronized (uploadController.class) {
                     isProcessing = false;
                 }
                 System.out.println("DEBUG: Virtual thread completed, processed " + fileCount + " files, isProcessing set to false");
             } catch (Exception e) {
                 System.err.println("Error in virtual thread processing: " + e.getMessage());
                 e.printStackTrace();
                 synchronized (uploadController.class) {
                     isProcessing = false;
                 }
                 System.out.println("DEBUG: Virtual thread failed due to error, isProcessing set to false");
             }
         });
     }
     
     private void processSingleFile(FileData fileData) {
         try {
             System.out.println("DEBUG: Starting processSingleFile for: " + fileData.getFileName());
             System.out.println("DEBUG: File size: " + fileData.getSize() + " bytes");
             System.out.println("DEBUG: File content type: " + fileData.getContentType());
             
             if (fileData == null || fileData.getSize() == 0) {
                 System.out.println("DEBUG: File is null or empty");
                 System.err.println("ERROR: File is null or empty: " + fileData.getFileName());
                 return;
             }
             
             System.out.println("DEBUG: About to process XML file");
             
             // Process file directly without using FacturasController (to avoid ViewScoped issues)
             try (InputStream inputStream = fileData.getInputStream()) {
                 processXMLDirectly(fileData, inputStream);
             }
             System.out.println("DEBUG: XML file processing completed successfully");
             
             System.out.println("DEBUG: File processed successfully: " + fileData.getFileName());
         } catch (Exception e) {
             System.err.println("DEBUG: Error processing file " + fileData.getFileName() + ": " + e.getMessage());
             e.printStackTrace();
             // Don't try to add FacesMessage in async thread - it will be null
System.err.println("ERROR: Failed to process file " + fileData.getFileName() + ": " + e.getMessage());
         }
     }
     
private void processXMLDirectly(FileData fileData, InputStream inputStream) throws Exception {
        System.out.println("DEBUG: Processing XML directly for: " + fileData.getFileName() + " by user: " + fileData.getCurrentUser());
        
        if (fileData == null || fileData.getSize() == 0) {
            System.err.println("File is null or empty: " + fileData.getFileName());
            return;
        }
        
        System.out.println("File details:");
        System.out.println("  FileName: " + fileData.getFileName());
        System.out.println("  Size: " + fileData.getSize());
        System.out.println("  ContentType: " + fileData.getContentType());
        System.out.println("  User: " + fileData.getCurrentUser());
        
        // Mark stream so we can reset after reading preview
        inputStream.mark(1024);
        
        // Read first few bytes to verify file content
        byte[] buffer = new byte[1024];
        int bytesRead = inputStream.read(buffer);
        System.out.println("Read " + bytesRead + " bytes from file");
        
        if (bytesRead > 0) {
            String preview = new String(buffer, 0, Math.min(bytesRead, 200));
            System.out.println("File preview: " + preview);
        }
        
        // Reset stream for parser
        inputStream.reset();
        
// Parse XML using parser service - temporarily store user in ThreadLocal for async access
        try {
            // Store current user in a thread-safe way for parser to access
            Utils.AsyncUserContext.setCurrentUser(fileData.getCurrentUser());
            parser.parseXML(inputStream);
            System.out.println("Successfully processed file: " + fileData.getFileName());
        } finally {
            Utils.AsyncUserContext.clear();
        }
    }
         
}