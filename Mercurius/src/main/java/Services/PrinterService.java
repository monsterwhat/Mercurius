package Services;

import jakarta.ejb.Stateless; 
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

@Stateless
@Named
public class PrinterService implements Serializable{

    public void printPDFFile(File fileToPrint) {
        try {
            PDDocument document = Loader.loadPDF(fileToPrint);
            // Find the printer...
            PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
            
            if (printService != null) {
                // Create a print job
                try {
                    PrinterJob job = PrinterJob.getPrinterJob();
            
                    job.setPageable(new PDFPageable(document));
                    job.setPrintService(printService);
                    job.print();
                } catch (PrinterException | NullPointerException e) {
                    System.out.println("Error: " + e.getLocalizedMessage());
                }
            } else {
                System.err.println("No print service found.");
            }
        } catch (IOException e) {
            System.out.println("Error: " + e.getLocalizedMessage());
        }
    }
}
