package Utils;

import Controllers.Settings.SettingsDirController;
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
    
    @Inject SettingsDirController dirController;
    
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String filePath = dirController.getFacturasDirPath() + request.getPathInfo();

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

