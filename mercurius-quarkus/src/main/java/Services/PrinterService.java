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


    /**
     * Lists the names of every print service (printer) the JVM can see, plus
     * which one is the system default. Used by the /app/impresoras page.
     */
    public @Nonnull java.util.List<String> listarImpresoras() {
        java.util.List<String> names = new java.util.ArrayList<>();
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        if (services != null) {
            for (PrintService service : services) {
                names.add(service.getName());
            }
        }
        return names;
    }

    /** Name of the system default print service, or null if none is set. */
    public @jakarta.annotation.Nullable String defaultImpresora() {
        PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
        return printService != null ? printService.getName() : null;
    }

    public void printPDFFile(@Nonnull File fileToPrint) {
                LOG.info("Attempting to print PDF: " + fileToPrint.getAbsolutePath() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        
        try {
            PDDocument document = Loader.loadPDF(fileToPrint);
            
            PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
                        LOG.info("Default print service: " + (printService != null ? printService.getName() : "null") + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
           
            if (printService != null) {
                try {
                    PrinterJob job = PrinterJob.getPrinterJob();
                                        LOG.info("Created printer job, attempting to print..." + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
           
                    job.setPageable(new PDFPageable(document));
                    job.setPrintService(printService);
                    job.print();
                                        LOG.info("Print job submitted successfully to printer: " + printService.getName() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                } catch (PrinterException e) {
                                        LOG.log(java.util.logging.Level.WARNING, "Printer error: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
                } catch (NullPointerException e) {
                                        LOG.log(java.util.logging.Level.WARNING, "Null pointer error during printing: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
                }
            } else {
                                LOG.log(java.util.logging.Level.WARNING, "No default print service found." + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
                for (PrintService service : services) {
                                        LOG.info("  - " + service.getName() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                }
            }
        } catch (IOException e) {
                        LOG.log(java.util.logging.Level.WARNING, "IO Error loading PDF: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Unexpected error: " + e.getMessage() + " | source=" + "PrinterService.printPDFFile()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
}
