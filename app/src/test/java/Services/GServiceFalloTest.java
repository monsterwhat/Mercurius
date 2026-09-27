package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;

import Models.Usuarios;

/**
 * Characterization tests for the {@link GService#listAll()} failure contract and for
 * its new fail-fast sibling {@link GService#listAllOrThrow()}.
 *
 * <p><strong>UNVERIFIED</strong>: written blind, never executed (no Maven/DB available in
 * the authoring environment). Plain Mockito, no Quarkus boot, no database.
 *
 * <p>What is pinned here:
 * <ul>
 *   <li>{@code listAll()} still returns an EMPTY list on a PersistenceException - the
 *       lenient behaviour every existing caller depends on is preserved, so this change
 *       is a no-op for them.</li>
 *   <li>"no rows" and "query failed" remain indistinguishable through {@code listAll()}
 *       (both yield an empty list). That ambiguity is the documented hazard.</li>
 *   <li>{@code listAllOrThrow()} propagates the PersistenceException instead of
 *       swallowing it - the escape hatch for callers that need correctness.</li>
 *   <li>The JPQL is still assembled from the entity simple name
 *       ({@code "SELECT e FROM Usuarios e"}), so replacing that with a metamodel lookup
 *       later becomes a deliberate, visible decision rather than an accident.</li>
 * </ul>
 */
class GServiceFalloTest {

    /** Minimal concrete GService so the inherited behaviour can be exercised directly. */
    private static class Probe extends GService<Usuarios> {
        @Override
        protected Class<Usuarios> getEntityClass() {
            return Usuarios.class;
        }
    }

    private Probe service;
    private EntityManager em;

    @BeforeEach
    void setUp() {
        service = new Probe();
        em = mock(EntityManager.class);
        service.em = em;
    }

    @SuppressWarnings("unchecked")
    private TypedQuery<Usuarios> queryReturning(List<Usuarios> filas) {
        TypedQuery<Usuarios> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Usuarios.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(filas);
        return query;
    }

    private void queryFailing() {
        when(em.createQuery(anyString(), eq(Usuarios.class)))
                .thenThrow(new PersistenceException("boom"));
    }

    // --- listAll(): lenient behaviour that MUST NOT change ---

    @Test
    void listAll_devuelveTodasLasFilas_cuandoLaConsultaFunciona() {
        List<Usuarios> filas = List.of(new Usuarios(), new Usuarios());
        queryReturning(filas);

        assertThat(service.listAll()).isSameAs(filas);
    }

    @Test
    void listAll_devuelveListaVacia_cuandoLaConsultaLanzaPersistenceException() {
        queryFailing();

        assertThat(service.listAll()).isEmpty();
    }

    @Test
    void listAll_esIndistinguible_entreSinFilasYConsultaFallida() {
        queryReturning(List.of());
        List<Usuarios> sinFilas = service.listAll();

        setUp();
        queryFailing();
        List<Usuarios> consultaFallida = service.listAll();

        // The documented hazard: both outcomes are an empty list, so no caller of
        // listAll() can tell "nothing configured" from "the query exploded".
        assertThat(sinFilas).isEmpty();
        assertThat(consultaFallida).isEqualTo(sinFilas);
    }

    @Test
    void listAll_armaElJpqlConElNombreSimpleDeLaEntidad() {
        queryReturning(List.of());

        service.listAll();

        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(em).createQuery(jpql.capture(), eq(Usuarios.class));
        assertThat(jpql.getValue()).isEqualTo("SELECT e FROM Usuarios e");
    }

    // --- listAllOrThrow(): the fail-fast variant ---

    @Test
    void listAllOrThrow_devuelveTodasLasFilas_cuandoLaConsultaFunciona() {
        List<Usuarios> filas = List.of(new Usuarios());
        queryReturning(filas);

        assertThat(service.listAllOrThrow()).isSameAs(filas);
    }

    @Test
    void listAllOrThrow_propagaLaPersistenceException_cuandoLaConsultaFalla() {
        queryFailing();

        assertThatThrownBy(() -> service.listAllOrThrow())
                .isInstanceOf(PersistenceException.class)
                .hasMessage("boom");
    }

    @Test
    void listAllOrThrow_armaElJpqlConElNombreSimpleDeLaEntidad() {
        queryReturning(List.of());

        service.listAllOrThrow();

        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(em).createQuery(jpql.capture(), eq(Usuarios.class));
        assertThat(jpql.getValue()).isEqualTo("SELECT e FROM Usuarios e");
    }
}
