package Controllers;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.InputStream;
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
    @Inject FacturasController facturas;
    private UploadedFile file;
        
    public void handleFileUpload(FileUploadEvent event) {
        try {
            file = event.getFile();
            System.out.println("File uploaded:");
            System.out.println("  FileName: " + file.getFileName());
            System.out.println("  Size: " + file.getSize());
            System.out.println("  ContentType: " + file.getContentType());
            
            if (file == null || file.getSize() == 0) {
                FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El archivo está vacío o no se pudo subir");
                FacesContext.getCurrentInstance().addMessage(null, message);
                return;
            }
            
            // Process the file immediately to avoid temp file cleanup issues
            try (InputStream inputStream = file.getInputStream()) {
                facturas.parseXMLFromUploadedFile(file);
            }
            
            FacesMessage message = new FacesMessage("Exito.", file.getFileName() + " se subio y procesó exitosamente.");
            FacesContext.getCurrentInstance().addMessage(null, message);
            System.out.println("File processed successfully: " + file.getFileName());
        } catch (Exception e) {
            System.err.println("Error in handleFileUpload: " + e.getMessage());
            e.printStackTrace();
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo procesar el archivo: " + e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
     }
    
}
