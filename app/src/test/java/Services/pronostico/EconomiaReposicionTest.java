package Services.pronostico;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Pruebas puras de la economia de reposicion. */
class EconomiaReposicionTest {

    @Test
    void smokeReposicion() {
        EconomiaReposicion.Reposicion reposicion = EconomiaReposicion.calcular(
                0, 10.0, 1.0, 7, 0.95, 0, 1);
        assertThat(reposicion.puntoReorden()).isGreaterThanOrEqualTo(10.0);
        assertThat(reposicion.cantidadPedido()).isGreaterThan(0);
    }

    @Nested
    @DisplayName("Nivel de servicio y z")
    class NivelZ {

        @Test
        @DisplayName("z de los niveles habituales coincide con la normal estandar")
        void zHabituales() {
            assertThat(EconomiaReposicion.z(0.50)).isCloseTo(0.0, offset(1e-9));
            assertThat(EconomiaReposicion.z(0.90)).isCloseTo(1.2816, offset(1e-3));
            assertThat(EconomiaReposicion.z(0.95)).isCloseTo(1.6449, offset(1e-3));
            assertThat(EconomiaReposicion.z(0.975)).isCloseTo(1.9600, offset(1e-3));
            assertThat(EconomiaReposicion.z(0.99)).isCloseTo(2.3263, offset(1e-3));
        }

        @Test
        @DisplayName("la razon critica eleva el nivel configurado cuando el faltante cuesta mas")
        void razonCriticaElevaNivel() {
            assertThat(EconomiaReposicion.nivelServicio(0.90, 1.0, 1.0))
                    .isCloseTo(0.90, offset(1e-9));
            assertThat(EconomiaReposicion.nivelServicio(0.90, 9.0, 1.0))
                    .isCloseTo(0.90, offset(1e-9));
            assertThat(EconomiaReposicion.nivelServicio(0.90, 99.0, 1.0))
                    .isCloseTo(0.99, offset(1e-9));
        }

        @Test
        @DisplayName("un nivel fuera de rango cae en 0.95 y no se propaga")
        void nivelInvalido() {
            assertThat(EconomiaReposicion.nivelServicio(Double.NaN, 10, 1)).isEqualTo(0.95);
            assertThat(EconomiaReposicion.nivelServicio(-1, 10, 1)).isEqualTo(0.95);
            assertThat(EconomiaReposicion.nivelServicio(2, 10, 1)).isEqualTo(0.95);
        }
    }

    @Nested
    @DisplayName("Punto de reorden y cantidad de pedido")
    class Reposicion {

        @Test
        @DisplayName("punto = demanda del plazo + z * sigma * sqrt(plazo)")
        void puntoDeReorden() {
            EconomiaReposicion.Reposicion r = EconomiaReposicion.calcular(
                    0, 70.0, 3.0, 7, 0.95, 0, 1);

            double esperado = 70.0 + 1.6449 * 3.0 * Math.sqrt(7.0);
            assertThat(r.puntoReorden()).isCloseTo(esperado, offset(1e-3));
            assertThat(r.stockSeguridad()).isCloseTo(1.6449 * 3.0 * Math.sqrt(7.0), offset(1e-3));
        }

        @Test
        @DisplayName("la cantidad se redondea al alza al multiplo y respeta el minimo")
        void multiploYMinimo() {
            EconomiaReposicion.Reposicion r = EconomiaReposicion.calcular(
                    0, 11.0, 0.0, 7, 0.95, 20, 12);
            assertThat(r.puntoReorden()).isCloseTo(11.0, offset(1e-9));
            assertThat(r.cantidadPedido()).isEqualTo(24);
        }

        @Test
        @DisplayName("sin faltante no se pide nada, aunque haya minimo")
        void sinFaltanteNoPide() {
            EconomiaReposicion.Reposicion r = EconomiaReposicion.calcular(
                    100, 10.0, 0.0, 7, 0.95, 50, 12);
            assertThat(r.cantidadPedido()).isZero();
        }

        @Test
        @DisplayName("mas stock nunca aumenta la cantidad pedida")
        void monotonia() {
            int anterior = Integer.MAX_VALUE;
            for (int stock = 0; stock <= 200; stock += 5) {
                int cantidad = EconomiaReposicion.calcular(
                        stock, 80.0, 4.0, 7, 0.95, 0, 1).cantidadPedido();
                assertThat(cantidad).isLessThanOrEqualTo(anterior);
                anterior = cantidad;
            }
        }
    }
}
