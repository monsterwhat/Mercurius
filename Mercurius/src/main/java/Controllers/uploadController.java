package Controllers;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
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
    private String selectedDirectory;
        
    public void handleFileUpload(FileUploadEvent event) {
        file = event.getFile();
        FacesMessage message = new FacesMessage("Exito.", file.getFileName() + " se subio exitosamente.");
        FacesContext.getCurrentInstance().addMessage(null, message);
        facturas.addFile(file);
    }
    
    public void saveDirectory() {
        File directory = new File(selectedDirectory);
        if (directory.exists() && directory.isDirectory()) {
            FacesMessage message = new FacesMessage("Success", "Directory selected: " + directory.getAbsolutePath());
            FacesContext.getCurrentInstance().addMessage(null, message);
        } else {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Invalid directory path.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
    
}
