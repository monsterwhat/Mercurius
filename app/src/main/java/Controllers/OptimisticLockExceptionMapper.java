package Controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Conflicto de versión: dos escrituras concurrentes sobre la misma fila
 * versionada ({@code ArticuloStock}, {@code Articulos}, {@code CierreCaja},
 * {@code Lote}). La segunda en confirmar recibe 409 para que recargue y
 * reintente, en vez de un 500 o un descarte silencioso.
 */
@Provider
@ApplicationScoped
public class OptimisticLockExceptionMapper implements ExceptionMapper<OptimisticLockException> {

    private static final Logger LOG = Logger.getLogger(OptimisticLockExceptionMapper.class);

    @Override
    public Response toResponse(OptimisticLockException exception) {
        LOG.warn("Conflicto de versión optimista | source=OptimisticLockExceptionMapper", exception);
        return Response.status(Response.Status.CONFLICT)
                .entity("{\"code\":\"VERSION_CONFLICT\",\"message\":\"El registro cambió mientras lo editaba. Recargue y reintente.\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @ServerExceptionMapper
    public Response mapOptimistic(OptimisticLockException exception) {
        return toResponse(exception);
    }
}
