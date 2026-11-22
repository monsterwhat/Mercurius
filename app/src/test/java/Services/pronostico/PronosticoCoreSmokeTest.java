package Services.pronostico;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suite plana (sin {@code @Nested}) para que el reporte de Surefire muestre
 * tests ejecutados de forma inequívoca. Los casos detallados siguen en las
 * clases con {@code @Nested}; esta suite cubre el camino feliz de cada núcleo.
 */
class PronosticoCoreSmokeTest {

    private static final LocalDate LUNES = LocalDate.of(2024, 1, 1);

    @Test
    void motorDevuelveHorizonteValido() {
        List<SerieDiaria> historia = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            historia.add(new SerieDiaria(LUNES.plusDays(i), 4.0));
        }
        List<Double> pronostico = MotorPronostico.pronosticar(
                MetodoPronostico.SES, historia, 14, Hiperparametros.defecto());

        assertThat(pronostico).hasSize(14)
                .allSatisfy(v -> {
                    assertThat(Double.isFinite(v)).isTrue();
                    assertThat(v).isGreaterThanOrEqualTo(0.0);
                });
    }

    @Test
    void economiaCalculaPuntoYPedido() {
        EconomiaReposicion.Reposicion reposicion = EconomiaReposicion.calcular(
                0, 10.0, 1.0, 7, 0.95, 0, 1);

        assertThat(reposicion.puntoReorden()).isGreaterThanOrEqualTo(10.0);
        assertThat(reposicion.cantidadPedido()).isGreaterThan(0);
    }

    @Test
    void intervalosContieneElPuntoYNoEsNegativo() {
        List<SerieDiaria> historia = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            historia.add(new SerieDiaria(LUNES.plusDays(i), 4.0));
        }
        IntervalosPronostico.Intervalo intervalo = IntervalosPronostico.intervalo(
                MetodoPronostico.SES, historia, 7, 28.0, 0.95, 1.0);

        assertThat(intervalo.limiteInferior()).isLessThanOrEqualTo(28.0);
        assertThat(intervalo.limiteSuperior()).isGreaterThanOrEqualTo(28.0);
        assertThat(intervalo.limiteInferior()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void calculadoraProduceHorizonteEIntervalo() {
        List<SerieDiaria> historia = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            historia.add(new SerieDiaria(LUNES.plusDays(i), 3.0));
        }
        CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                new CalculadoraPronostico.Articulo(1L, "Articulo", "", "", 20),
                historia, 14, null, CalculadoraPronostico.Opciones.defecto(), false);

        assertThat(resultado.diario()).hasSize(14);
        assertThat(resultado.intervaloInferior()).isLessThanOrEqualTo(resultado.demandaTotal());
        assertThat(resultado.intervaloSuperior()).isGreaterThanOrEqualTo(resultado.demandaTotal());
    }
}
