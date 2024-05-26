package Utils;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.Serializable;
import lombok.Getter;

/**
 *
 * @author Al
 */

@Named("directoryConfig")
@ApplicationScoped
public class directoryConfig implements Serializable {
    
    @Inject
    private ServletContext servletContext;
    @Getter private String pdfSaveDirectory;
    @Getter private String xmlSaveDirectory;
    
    @PostConstruct
    public void init() {
        if(servletContext != null){
            pdfSaveDirectory = servletContext.getRealPath("/") + "pdfs/";
            xmlSaveDirectory = servletContext.getRealPath("/" + "xmls/");
            createPdfSaveDirectoryIfNeeded();
            createXmlSaveDirectoryIfNeeded();
        }
    }
    
    public void createPdfSaveDirectoryIfNeeded() {
        String pdfSaveDirectory = getPdfSaveDirectory();
        File directory = new File(pdfSaveDirectory);
        if (!directory.exists()) {
            if (directory.mkdirs()) {
                System.out.println("PDF save directory created successfully.");
            } else {
                System.err.println("Failed to create PDF save directory.");
             }
        }
    }
    
    public void createXmlSaveDirectoryIfNeeded(){
        String xmlSaveDirectory = getXmlSaveDirectory();
        File directory = new File(xmlSaveDirectory);
        if (!directory.exists()) {
            if (directory.mkdirs()) {
                System.out.println("XML save directory created successfully.");
            } else {
                System.err.println("Failed to create XML save directory.");
             }
        }
    }
    
    
}
