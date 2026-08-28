package Services;

import jakarta.annotation.Nonnull;
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

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(PrinterService.class.getName());


    public void printPDFFile(@Nonnull File fileToPrint) {
                LOG.info("Attempting to print PDF: " + fileToPrint.getAbsolutePath() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        
        try {
            PDDocument document = Loader.loadPDF(fileToPrint);
            
            PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
                        LOG.info("Default print service: " + (printService != null ? printService.getName() : "null") + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
           
            if (printService != null) {
                try {
                    PrinterJob job = PrinterJob.getPrinterJob();
                                        LOG.info("Created printer job, attempting to print..." + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
           
                    job.setPageable(new PDFPageable(document));
                    job.setPrintService(printService);
                    job.print();
                                        LOG.info("Print job submitted successfully to printer: " + printService.getName() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                } catch (PrinterException e) {
                                        LOG.log(java.util.logging.Level.WARNING, "Printer error: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
                } catch (NullPointerException e) {
                                        LOG.log(java.util.logging.Level.WARNING, "Null pointer error during printing: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
                }
            } else {
                                LOG.log(java.util.logging.Level.WARNING, "No default print service found." + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
                for (PrintService service : services) {
                                        LOG.info("  - " + service.getName() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                }
            }
        } catch (IOException e) {
                        LOG.log(java.util.logging.Level.WARNING, "IO Error loading PDF: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Unexpected error: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
}
