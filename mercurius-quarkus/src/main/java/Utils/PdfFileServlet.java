package Utils;

import Services.DirectoryService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 *
 * @author Al
 */

@WebServlet("/facturas/*")
public class PdfFileServlet extends HttpServlet {
    
    @Inject @Nonnull DirectoryService dirService;
    
    protected void doGet(@Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response) throws ServletException, IOException {
        String filePath = dirService.getFacturasDirPath() + request.getPathInfo();

        File pdfFile = new File(filePath);
        if (pdfFile.exists() && !pdfFile.isDirectory()) {
            response.setContentType("application/pdf");
            response.setHeader("Content-Disposition", "inline; filename=\"" + pdfFile.getName() + "\"");
            Files.copy(pdfFile.toPath(), response.getOutputStream());
            response.flushBuffer();
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}

