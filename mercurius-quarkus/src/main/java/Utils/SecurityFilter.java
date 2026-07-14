package Utils;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Security filter to protect /secured/* paths.
 * Checks for an "authUser" session attribute set by SessionController on login,
 * instead of relying on container-managed FORM auth (which Quarkus does not
 * configure from web.xml declarations alone).
 */
@WebFilter(urlPatterns = {"/secured/*"}, dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.FORWARD})
public class SecurityFilter implements jakarta.servlet.Filter {

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        Object authUser = httpRequest.getSession(false) != null
            ? httpRequest.getSession(false).getAttribute("authUser")
            : null;

        if (authUser == null) {
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/index.xhtml");
            return;
        }

        chain.doFilter(request, response);
    }
}