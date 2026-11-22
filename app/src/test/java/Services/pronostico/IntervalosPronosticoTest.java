package Services.pronostico;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Pruebas puras de intervalos empiricos y cobertura. */
class IntervalosPronosticoTest {

    private static final LocalDate LUNES = LocalDate.of(2024, 1, 1);

    @Test
    void smokeIntervaloContieneElPunto() {
        List<SerieDiaria> historia = serie(LUNES, repeticion(60, 4.0));
        IntervalosPronostico.Intervalo intervalo = IntervalosPronostico.intervalo(
                MetodoPronostico.SES, historia, 7, 28.0, 0.95, 1.0);

        assertThat(intervalo.limiteInferior()).isLessThanOrEqualTo(28.0);
        assertThat(intervalo.limiteSuperior()).isGreaterThanOrEqualTo(28.0);
        assertThat(intervalo.limiteInferior()).isGreaterThanOrEqualTo(0.0);
    }

    @Nested
    @DisplayName("Intervalos")
    class Intervalos {

        @Test
        @DisplayName("el intervalo contiene el punto y no baja de cero")
        void contienePuntoYNoNegativo() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(90, 5.0));

            IntervalosPronostico.Intervalo intervalo = IntervalosPronostico.intervalo(
                    MetodoPronostico.SES, historia, 14, 70.0, 0.95, 1.0);

            assertThat(intervalo.limiteInferior()).isLessThanOrEqualTo(70.0);
            assertThat(intervalo.limiteSuperior()).isGreaterThanOrEqualTo(70.0);
            assertThat(intervalo.limiteInferior()).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("con pocos origenes el intervalo se marca como aproximado")
        void pocosOrigenesAproximado() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(10, 5.0));

            IntervalosPronostico.Intervalo intervalo = IntervalosPronostico.intervalo(
                    MetodoPronostico.SES, historia, 7, 35.0, 0.95, 1.0);

            assertThat(intervalo.aproximado()).isTrue();
            assertThat(intervalo.muestras()).isLessThan(IntervalosPronostico.MIN_MUESTRAS_EMPIRICAS);
        }

        @Test
        @DisplayName("cuantil con interpolacion lineal")
        void cuantil() {
            List<Double> valores = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
            assertThat(IntervalosPronostico.cuantil(valores, 0.0)).isEqualTo(1.0);
            assertThat(IntervalosPronostico.cuantil(valores, 0.5)).isCloseTo(3.0, offset(1e-9));
            assertThat(IntervalosPronostico.cuantil(valores, 1.0)).isEqualTo(5.0);
            assertThat(IntervalosPronostico.cuantil(List.of(), 0.5)).isZero();
        }
    }

    @Nested
    @DisplayName("Cobertura empirica")
    class Cobertura {

        @Test
        @DisplayName("una serie constante con error cero produce cobertura medible, no NaN")
        void constanteMedible() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(120, 4.0));

            List<IntervalosPronostico.Cobertura> coberturas = IntervalosPronostico.evaluarCobertura(
                    MetodoPronostico.SES, historia, List.of(7, 14), List.of(0.90, 0.95), 12);

            assertThat(coberturas).hasSize(4);
            for (IntervalosPronostico.Cobertura c : coberturas) {
                assertThat(c.muestras()).isGreaterThanOrEqualTo(2);
                assertThat(c.cobertura()).isBetween(0.0, 1.0);
                assertThat(c.anchoMedio()).isGreaterThanOrEqualTo(0.0);
                assertThat(c.wis()).isGreaterThanOrEqualTo(0.0);
            }
        }

        @Test
        @DisplayName("sin historia no hay cobertura que inventar")
        void sinHistoria() {
            assertThat(IntervalosPronostico.evaluarCobertura(
                    MetodoPronostico.SES, List.of(), List.of(7), List.of(0.95), 8)).isEmpty();
        }

        @Test
        @DisplayName("sigma de un paso es cero en una serie plana y positiva con ruido")
        void sigmaUnPaso() {
            assertThat(IntervalosPronostico.sigmaUnPaso(
                    MetodoPronostico.SES, serie(LUNES, repeticion(60, 3.0)))).isZero();
            assertThat(IntervalosPronostico.sigmaUnPaso(
                    MetodoPronostico.SES, serie(LUNES, alternos(60, 1.0, 9.0)))).isGreaterThan(0.0);
        }
    }

    // ── series sinteticas ────────────────────────────────────────────────

    private static List<SerieDiaria> serie(LocalDate inicio, double... valores) {
        List<SerieDiaria> puntos = new ArrayList<>(valores.length);
        for (int i = 0; i < valores.length; i++) {
            puntos.add(new SerieDiaria(inicio.plusDays(i), valores[i]));
        }
        return puntos;
    }

    private static double[] repeticion(int veces, double valor) {
        double[] salida = new double[veces];
        java.util.Arrays.fill(salida, valor);
        return salida;
    }

    private static double[] alternos(int veces, double a, double b) {
        double[] salida = new double[veces];
        for (int i = 0; i < veces; i++) {
            salida[i] = i % 2 == 0 ? a : b;
        }
        return salida;
    }
}
