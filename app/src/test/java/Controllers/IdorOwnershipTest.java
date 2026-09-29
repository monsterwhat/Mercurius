package Controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Controllers.Api.Mercatus.OrderController;
import Models.AtajoUsuario;
import Services.QuickActionsService;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * IDOR regression: caller-derived ownership.
 *
 * <p>These are unit tests on the controllers' ownership decision rather than
 * HTTP tests, and that is deliberate: both resources sit behind
 * {@code PublicApiJwtFilter}, a hand-written {@code ContainerRequestFilter} that
 * validates its own bearer token. {@code @TestSecurity} only satisfies the
 * Quarkus {@code @RolesAllowed} layer, so an HTTP test would get 401 from the
 * filter and never reach the code being pinned here. Exercising the resource
 * methods with a stubbed {@link SecurityContext} tests the actual boundary:
 * whose data may be read or written.</p>
 *
 * <p>What was wrong before:</p>
 * <ul>
 *   <li>{@code QuickActionsController} took the target user from a path
 *       parameter or a JSON body and acted on it, with no role gate and no
 *       ownership check. The three by-id writes had no user reference at all, so
 *       guessing an id was enough to favourite, count or delete anyone's
 *       shortcut.</li>
 *   <li>{@code OrderController} ({@code /api/v1/mercatus/orders}) read as if it
 *       were client-scoped and was not: the list endpoint returned
 *       {@code listAll()} — the whole purchase-order book — and the detail
 *       endpoint assigned the caller's principal to an unused local.
 *       {@code OrdenCompra} links to {@code Usuarios} and has no client
 *       reference, so both endpoints now answer 501 instead of leaking.</li>
 * </ul>
 */
@DisplayName("IDOR: propiedad derivada del llamador")
class IdorOwnershipTest {

    private static SecurityContext contextoPara(String principal) {
        SecurityContext ctx = Mockito.mock(SecurityContext.class);
        Principal p = Mockito.mock(Principal.class);
        when(p.getName()).thenReturn(principal);
        when(ctx.getUserPrincipal()).thenReturn(p);
        return ctx;
    }

    private static QuickActionsController controllerCon(String principal,
                                                        QuickActionsService servicio) {
        QuickActionsController c = new QuickActionsController();
        c.quickActionsService = servicio;
        c.securityContext = contextoPara(principal);
        return c;
    }

    private static AtajoUsuario atajo(long id, String username) {
        AtajoUsuario a = new AtajoUsuario();
        a.setId(id);
        a.setUsername(username);
        a.setActionKey("k" + id);
        a.setActionLabel("Accion " + id);
        return a;
    }

    // ── acciones rapidas: lectura ──────────────────────────────────────────

    @Test
    @DisplayName("no se pueden leer los atajos de otro usuario")
    void noLeeAtajosDeOtro() {
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        when(servicio.getUserShortcuts(anyString())).thenReturn(List.of());
        QuickActionsController c = controllerCon("cajero", servicio);

        Response r = c.getUserShortcuts("jefe");

        assertThat(r.getStatus()).isEqualTo(403);
        // The service must not even be consulted for another user's data.
        verify(servicio, never()).getUserShortcuts(eq("jefe"));
    }

    @Test
    @DisplayName("no se pueden leer los favoritos ni los mas usados de otro usuario")
    void noLeeFavoritosNiMasUsadosDeOtro() {
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        when(servicio.getFavoriteActions(anyString())).thenReturn(List.of());
        when(servicio.getMostUsedActions(anyString(), anyInt())).thenReturn(List.of());
        QuickActionsController c = controllerCon("cajero", servicio);

        assertThat(c.getFavoriteActions("jefe").getStatus()).isEqualTo(403);
        assertThat(c.getMostUsedActions("jefe", 5).getStatus()).isEqualTo(403);
        assertThat(c.quickSearch("jefe", "factura").getStatus()).isEqualTo(403);
        verify(servicio, never()).getFavoriteActions(eq("jefe"));
        verify(servicio, never()).quickSearch(any(), eq("jefe"));
    }

    @Test
    @DisplayName("no se pueden inicializar ni reordenar los atajos de otro usuario")
    void noEscribeAtajosDeOtro() {
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        QuickActionsController c = controllerCon("cajero", servicio);

        assertThat(c.initializeDefaults("jefe").getStatus()).isEqualTo(403);
        assertThat(c.reorderShortcuts("jefe", List.of(1L, 2L)).getStatus()).isEqualTo(403);
        verify(servicio, never()).initializeDefaultShortcuts(eq("jefe"));
        verify(servicio, never()).reorderShortcuts(eq("jefe"), any());
    }

    // ── acciones rapidas: escritura por id ────────────────────────────────

    @Test
    @DisplayName("un atajo ajeno por id no se puede marcar, contar ni borrar")
    void noModificaAtajosAjenosPorId() {
        // The caller owns only id 1; ids 2 and 3 belong to someone else.
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        when(servicio.getUserShortcuts("cajero")).thenReturn(List.of(atajo(1, "cajero")));
        QuickActionsController c = controllerCon("cajero", servicio);

        assertThat(c.toggleFavorite(2L).getStatus()).isEqualTo(404);
        assertThat(c.incrementUsage(2L).getStatus()).isEqualTo(404);
        assertThat(c.deleteShortcut(2L).getStatus()).isEqualTo(404);
        verify(servicio, never()).toggleFavorite(any());
        verify(servicio, never()).incrementUsage(any());
        verify(servicio, never()).deleteShortcut(any());
    }

    @Test
    @DisplayName("un atajo propio por id si se puede marcar, contar y borrar")
    void siModificaAtajosPropiosPorId() {
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        when(servicio.getUserShortcuts("cajero")).thenReturn(List.of(atajo(1, "cajero")));
        when(servicio.toggleFavorite(1L)).thenReturn(atajo(1, "cajero"));
        QuickActionsController c = controllerCon("cajero", servicio);

        assertThat(c.toggleFavorite(1L).getStatus()).isEqualTo(200);
        assertThat(c.incrementUsage(1L).getStatus()).isEqualTo(200);
        assertThat(c.deleteShortcut(1L).getStatus()).isEqualTo(200);
        verify(servicio).toggleFavorite(1L);
        verify(servicio).incrementUsage(1L);
        verify(servicio).deleteShortcut(1L);
    }

    @Test
    @DisplayName("un id inexistente responde 404, no 403")
    void idInexistenteDa404() {
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        when(servicio.getUserShortcuts("cajero")).thenReturn(List.of());
        QuickActionsController c = controllerCon("cajero", servicio);

        assertThat(c.toggleFavorite(999L).getStatus()).isEqualTo(404);
    }

    // ── acciones rapidas: el cuerpo no decide el propietario ──────────────

    @Test
    @DisplayName("el cuerpo no puede plantar un atajo bajo otro usuario")
    void elCuerpoNoPlantaAtajosAjenos() {
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        when(servicio.addShortcut(any())).thenAnswer(i -> i.getArgument(0));
        QuickActionsController c = controllerCon("cajero", servicio);

        AtajoUsuario enviado = atajo(0, "jefe");
        enviado.setActionKey("k-plantada");
        Response r = c.addShortcut(enviado);

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(enviado.getUsername())
                .as("el propietario debe ser el llamador, no el del cuerpo")
                .isEqualTo("cajero");
    }

    // ── acciones rapidas: anonimo ─────────────────────────────────────────

    @Test
    @DisplayName("un llamador anonimo no llega a ningun atajo")
    void anonimoNoLlega() {
        QuickActionsService servicio = Mockito.mock(QuickActionsService.class);
        QuickActionsController c = new QuickActionsController();
        c.quickActionsService = servicio;
        c.securityContext = contextoPara(null);

        assertThat(c.getUserShortcuts("cajero").getStatus()).isEqualTo(403);
        assertThat(c.addShortcut(atajo(0, "cajero")).getStatus()).isEqualTo(403);
        assertThat(c.toggleFavorite(1L).getStatus()).isEqualTo(404);
        verify(servicio, never()).getUserShortcuts(anyString());
    }

    // ── ordenes de compra de mercatus ────────────────────────────────────

    @Test
    @DisplayName("las ordenes de compra de mercatus responden 501 en vez de filtrar datos")
    void ordenesDeCompraRetiradas() {
        OrderController c = new OrderController();
        SecurityContext ctx = Mockito.mock(SecurityContext.class);
        Principal p = Mockito.mock(Principal.class);
        when(p.getName()).thenReturn("42");
        when(ctx.getUserPrincipal()).thenReturn(p);

        Response lista = c.listOrders(ctx, 0, 20);
        Response detalle = c.getOrder(1L, ctx);

        assertThat(lista.getStatus()).isEqualTo(501);
        assertThat(detalle.getStatus()).isEqualTo(501);

        @SuppressWarnings("unchecked")
        Models.DTO.ApiResponse<Object> envelope =
                (Models.DTO.ApiResponse<Object>) lista.getEntity();
        assertThat(envelope.getError().getCode()).isEqualTo("NOT_IMPLEMENTED");
        assertThat(envelope.getError().getMessage())
                .as("el mensaje debe senalar la alternativa ya limitada a su titular")
                .contains("/api/marketplace/orders");
        assertThat(envelope.getData()).as("no se entrega ninguna orden").isNull();
    }
}
