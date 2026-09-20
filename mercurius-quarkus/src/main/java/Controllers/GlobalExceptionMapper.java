package Controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
@ApplicationScoped
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        LOG.error("Unhandled exception | source=GlobalExceptionMapper", exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"code\":\"INTERNAL_ERROR\",\"message\":\"Error interno\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
