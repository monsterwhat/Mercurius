package Services;

import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;

/**
 *
 * @author Al
 */

@ApplicationScoped
@Named
public class PrinterService implements Serializable{

    @Inject AlertasService alertasService;

    public void printPDFFile(File fileToPrint) {
        alertasService.registrarAlerta("Info", "Attempting to print PDF: " + fileToPrint.getAbsolutePath(), null, 0, "PrinterService.printPDFFile()", null, null);
        
        try {
            PDDocument document = Loader.loadPDF(fileToPrint);
            
            PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
            alertasService.registrarAlerta("Info", "Default print service: " + (printService != null ? printService.getName() : "null"), null, 0, "PrinterService.printPDFFile()", null, null);
           
            if (printService != null) {
                try {
                    PrinterJob job = PrinterJob.getPrinterJob();
                    alertasService.registrarAlerta("Info", "Created printer job, attempting to print...", null, 0, "PrinterService.printPDFFile()", null, null);
           
                    job.setPageable(new PDFPageable(document));
                    job.setPrintService(printService);
                    job.print();
                    alertasService.registrarAlerta("Info", "Print job submitted successfully to printer: " + printService.getName(), null, 0, "PrinterService.printPDFFile()", null, null);
                } catch (PrinterException e) {
                    alertasService.registrarAlerta("Error", "Printer error: " + e.getMessage(), null, 0, "PrinterService.printPDFFile()", null, e.getMessage());
                } catch (NullPointerException e) {
                    alertasService.registrarAlerta("Error", "Null pointer error during printing: " + e.getMessage(), null, 0, "PrinterService.printPDFFile()", null, e.getMessage());
                }
            } else {
                alertasService.registrarAlerta("Error", "No default print service found.", null, 0, "PrinterService.printPDFFile()", null, null);
                PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
                for (PrintService service : services) {
                    alertasService.registrarAlerta("Info", "  - " + service.getName(), null, 0, "PrinterService.printPDFFile()", null, null);
                }
            }
        } catch (IOException e) {
            alertasService.registrarAlerta("Error", "IO Error loading PDF: " + e.getMessage(), null, 0, "PrinterService.printPDFFile()", null, e.getMessage());
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Unexpected error: " + e.getMessage(), null, 0, "PrinterService.printPDFFile()", null, e.getMessage());
        }
    }
}
