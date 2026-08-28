package Controllers.Api.App;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placeholder pages for navbar routes that have templates but no PageResource yet,
 * or no dedicated template. Returns a small HTML shell using the shared layout
 * concept so the navbar never 404s while the full module lands.
 */
@Path("/app")
@Produces(MediaType.TEXT_HTML)
public class MiscPagesResource {

    private static Response placeholder(String titulo, String descripcion, String href) {
        String html = """
            <!DOCTYPE html>
            <html lang="es"><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
            <title>%s | Mercurius</title><link rel="stylesheet" href="/Mercurius/static/bundle/app.css"/></head>
            <body><nav class="navbar is-black"><div class="navbar-brand"><a class="navbar-item" href="/Mercurius/app"><span class="has-text-weight-bold">Mercurius</span></a></div></nav>
            <section class="section"><div class="container"><div class="box has-text-centered">
            <h1 class="title is-4">%s</h1><p class="subtitle is-6 has-text-grey">%s</p>
            <a class="button is-link mt-4" href="%s">Volver al inicio</a>
            <a class="button is-light mt-4 ml-2" href="/Mercurius/app/dashboard">Ir al dashboard</a>
            </div></div></section></body></html>
            """.formatted(titulo, titulo, descripcion, href);
        return Response.ok(html).type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    @GET @Path("/reportes") @RolesAllowed({"admin","registro","inventario","tributacion"})
    public Response reportes() {
        String html = """
            <!DOCTYPE html><html lang="es"><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
            <title>Reportes | Mercurius</title><link rel="stylesheet" href="/Mercurius/static/bundle/app.css"/></head>
            <body><nav class="navbar is-black"><div class="navbar-brand"><a class="navbar-item" href="/Mercurius/app"><span class="has-text-weight-bold">Mercurius</span></a></div></nav>
            <section class="section"><div class="container">
            <h1 class="title is-4">Reportes</h1><p class="subtitle is-6 has-text-grey">Seleccione un reporte</p>
            <div class="columns is-multiline">
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/articulos/ventas">Ventas por articulo</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/articulos/familias">Ventas por familia</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/articulos/departamentos">Ventas por departamento</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/inventario/resumen">Resumen inventario</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/inventario/alertas">Alertas de stock</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/inventario/merma">Mermas</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/clientes">Clientes</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/usuarios">Usuarios</a></div>
              <div class="column is-4"><a class="box" href="/Mercurius/app/reportes/recibos">Recibos</a></div>
            </div></div></section></body></html>
            """;
        return Response.ok(html).type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    @GET @Path("/impresoras") @RolesAllowed({"admin"})
    public Response impresoras() { return placeholder("Menu de Impresoras","Gestion de impresoras (placeholder)","/Mercurius/app"); }

    @GET @Path("/aplicacion") @RolesAllowed({"admin"})
    public Response aplicacion() { return placeholder("Menu de Aplicacion","Configuracion de aplicacion (placeholder)","/Mercurius/app"); }

    @GET @Path("/backups") @RolesAllowed({"admin"})
    public Response backups() { return placeholder("Backups","Copias de seguridad (placeholder)","/Mercurius/app"); }

    @GET @Path("/correos/reportes") @RolesAllowed({"admin"})
    public Response correosReportes() { return placeholder("Menu de Correos - Reportes","Reportes programados por correo (placeholder)","/Mercurius/app"); }

    @GET @Path("/correos/plantillas") @RolesAllowed({"admin"})
    public Response correosPlantillas() { return placeholder("Plantillas de Correo","Plantillas (placeholder)","/Mercurius/app"); }

    @GET @Path("/registros/log") @RolesAllowed({"admin","registro"})
    public Response registrosLog() { return placeholder("Log de Actividades","Registro de actividades (placeholder)","/Mercurius/app"); }

    @GET @Path("/perfil") @RolesAllowed({"admin","inventario","facturacion","tributacion","usuario","registro"})
    public Response perfil() { return placeholder("Perfil"," Perfil de usuario (placeholder - T22)","/Mercurius/app"); }

    @GET @Path("/tipo-cambio") @RolesAllowed({"admin","tributacion"})
    public Response tipoCambio() { return placeholder("Tipo de Cambio","Consulta de tipo de cambio BCCR - use el widget en el POS","/Mercurius/app/pos"); }
}
