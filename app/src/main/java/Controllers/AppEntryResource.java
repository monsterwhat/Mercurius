package Controllers;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;

@Path("/app")
@PermitAll
public class AppEntryResource {

    private static Response redirectToDashboard() {
        return Response.seeOther(URI.create("/app/dashboard")).build();
    }

    @GET
    @Path("{slash:/?}")
    public Response app() {
        return redirectToDashboard();
    }
}
