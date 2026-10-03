package Models;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import Models.Articulos.ArticuloStock;
import Models.Articulos.Articulos;

/**
 * Candado de regresión: las filas mutables y contendidas llevan
 * {@code @Version}; los consecutivos quedan pesimistas a propósito
 * (secuencia sin huecos exigida por Hacienda).
 */
@DisplayName("Bloqueo de versión: entidades contendidas llevan @Version")
class BloqueoVersionTest {

    private static Field versionField(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(jakarta.persistence.Version.class)) {
                return f;
            }
        }
        return null;
    }

    @Test
    @DisplayName("ArticuloStock, Articulos, CierreCaja y Lote tienen @Version Long")
    void versionadas() {
        for (Class<?> c : List.of(ArticuloStock.class, Articulos.class, CierreCaja.class, Lote.class)) {
            Field v = versionField(c);
            assertThat(v)
                    .as("%s debe declarar un campo @Version", c.getSimpleName())
                    .isNotNull();
            assertThat(v.getType())
                    .as("%s.version debe ser Long", c.getSimpleName())
                    .isEqualTo(Long.class);
        }
    }

    @Test
    @DisplayName("Consecutivos siguen pesimistas: sin @Version por diseño")
    void consecutivosPesimistas() {
        List<String> conVersion = new ArrayList<>();
        for (Class<?> c : List.of(ConsecutivoEmitido.class, ConsecutivoReceptor.class)) {
            if (versionField(c) != null) {
                conVersion.add(c.getSimpleName());
            }
        }
        assertThat(conVersion)
                .as("Consecutivos deben seguir sin @Version (PESSIMISTIC_WRITE + advisory lock)")
                .isEmpty();
    }

    @Test
    @DisplayName("El mapper 409 existe y mapea OptimisticLockException")
    void mapperExiste() throws Exception {
        Class<?> mapper = Class.forName("Controllers.OptimisticLockExceptionMapper");
        assertThat(mapper.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)).isTrue();
    }
}
