package Utils;

import jakarta.inject.Inject;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import Controllers.SessionController;
import java.io.IOException;

/**
 * Security filter to protect /secured/* paths
 */
@WebFilter(urlPatterns = {"/secured/*"}, dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.FORWARD})
public class SecurityFilter implements jakarta.servlet.Filter {
    
    @Inject
    SessionController sessionController;
    
    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // Check if user is authenticated
        if (!sessionController.isValid()) {
            // Redirect to login page if not authenticated
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/index.xhtml");
            return;
        }
        
        // Continue with the request if authenticated
        chain.doFilter(request, response);
    }
}