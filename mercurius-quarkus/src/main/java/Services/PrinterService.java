package Services;

import jakarta.enterprise.context.ApplicationScoped; 
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

    public void printPDFFile(File fileToPrint) {
        System.out.println("Attempting to print PDF: " + fileToPrint.getAbsolutePath());
        
        try {
            PDDocument document = Loader.loadPDF(fileToPrint);
            
            // Find the printer...
            PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
            System.out.println("Default print service: " + (printService != null ? printService.getName() : "null"));
           
            if (printService != null) {
                // Create a print job
                try {
                    PrinterJob job = PrinterJob.getPrinterJob();
                    System.out.println("Created printer job, attempting to print...");
           
                    job.setPageable(new PDFPageable(document));
                    job.setPrintService(printService);
                    job.print();
                    System.out.println("Print job submitted successfully to printer: " + printService.getName());
                } catch (PrinterException e) {
                    System.err.println("Printer error: " + e.getLocalizedMessage());
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    System.err.println("Null pointer error during printing: " + e.getLocalizedMessage());
                    e.printStackTrace();
                }
            } else {
                System.err.println("No default print service found. Available printers:");
                PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
                for (PrintService service : services) {
                    System.out.println("  - " + service.getName());
                }
            }
        } catch (IOException e) {
            System.err.println("IO Error loading PDF: " + e.getLocalizedMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getLocalizedMessage());
            e.printStackTrace();
        }
    }
}
