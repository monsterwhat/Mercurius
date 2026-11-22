package Services.pronostico;

import Models.PrecisionPronostico;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Pruebas puras de la calculadora que comparten pagina, API y exportacion. */
class CalculadoraPronosticoTest {

    private static final LocalDate LUNES = LocalDate.of(2024, 1, 1);

    @Test
    void smokeCalculadoraProduceHorizonteYPuntoDentroDelIntervalo() {
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

    private static final CalculadoraPronostico.Articulo ARTICULO =
            new CalculadoraPronostico.Articulo(1L, "Articulo", "Depto", "Familia", 100);

    private static final CalculadoraPronostico.Opciones OPCIONES =
            CalculadoraPronostico.Opciones.defecto();

    @Nested
    @DisplayName("Denominador de dias naturales")
    class DiasNaturales {

        @Test
        @DisplayName("5 dias con venta en 90 dias: el promedio es total / 90, no total / 5")
        void demandaDispersaSeDividePorDiasNaturales() {
            List<SerieDiaria> serie = serieDispersa(90, List.of(0, 20, 40, 60, 80), 10.0);

            CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                    ARTICULO, serie, 30, null, OPCIONES, false);

            assertThat(resultado.diasHistoria()).isEqualTo(90);
            assertThat(resultado.diasConDemanda()).isEqualTo(5);
            assertThat(resultado.totalHistorico()).isCloseTo(50.0, offset(1e-9));
            assertThat(resultado.promedioDiarioCalendario())
                    .isCloseTo(50.0 / 90.0, offset(1e-9));
            assertThat(resultado.promedioDiarioCalendario()).isLessThan(50.0 / 5.0);
        }

        @Test
        @DisplayName("duplicar los dias sin venta baja el promedio, no lo deja igual")
        void masCerosBajanElPromedio() {
            List<SerieDiaria> corta = serieDispersa(30, List.of(0, 10, 20), 10.0);
            List<SerieDiaria> larga = serieDispersa(90, List.of(0, 30, 60), 10.0);

            double promedioCorta = CalculadoraPronostico.calcular(
                    ARTICULO, corta, 14, null, OPCIONES, false).promedioDiarioCalendario();
            double promedioLarga = CalculadoraPronostico.calcular(
                    ARTICULO, larga, 14, null, OPCIONES, false).promedioDiarioCalendario();

            assertThat(promedioLarga).isLessThan(promedioCorta);
        }
    }

    @Nested
    @DisplayName("Contrato del pronostico")
    class Contrato {

        @Test
        @DisplayName("salida del largo del horizonte, finita, no negativa y con intervalo que contiene el punto")
        void formaYNoNegatividad() {
            List<SerieDiaria> serie = serieDispersa(90, List.of(0, 20, 40, 60, 80), 10.0);

            CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                    ARTICULO, serie, 30, null, OPCIONES, false);

            assertThat(resultado.diario()).hasSize(30);
            assertThat(resultado.diario()).allSatisfy(v -> {
                assertThat(Double.isFinite(v)).isTrue();
                assertThat(v).isGreaterThanOrEqualTo(0.0);
            });
            assertThat(resultado.demandaTotal()).isGreaterThanOrEqualTo(0.0);
            assertThat(resultado.intervaloInferior()).isLessThanOrEqualTo(resultado.demandaTotal());
            assertThat(resultado.intervaloSuperior()).isGreaterThanOrEqualTo(resultado.demandaTotal());
            assertThat(resultado.intervaloInferior()).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("sin historia no inventa demanda: ceros, INGENUO y marcas de calidad")
        void sinHistoria() {
            CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                    new CalculadoraPronostico.Articulo(2L, "Nuevo", "", "", 0),
                    List.of(), 14, null, OPCIONES, false);

            assertThat(resultado.metodo()).isEqualTo(MetodoPronostico.INGENUO);
            assertThat(resultado.demandaTotal()).isZero();
            assertThat(resultado.diario()).hasSize(14).allSatisfy(v -> assertThat(v).isZero());
            assertThat(resultado.flags()).contains(
                    FlagCalidad.INSUFFICIENT_HISTORY,
                    FlagCalidad.NO_EVALUATION,
                    FlagCalidad.RECENT_STOCKOUT);
        }

        @Test
        @DisplayName("la seleccion persistida manda si sigue siendo elegible")
        void seleccionPersistida() {
            List<SerieDiaria> serie = serieDispersa(90, List.of(0, 20, 40, 60, 80), 10.0);
            PrecisionPronostico seleccion = new PrecisionPronostico();
            seleccion.setMetodo(MetodoPronostico.SES.name());
            seleccion.setMase(0.8);
            seleccion.setSesgo(0.1);
            seleccion.setMae(2.0);
            seleccion.setHorizonteDias(30);

            CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                    ARTICULO, serie, 30, seleccion, OPCIONES, false);

            assertThat(resultado.metodo()).isEqualTo(MetodoPronostico.SES);
            assertThat(resultado.mase()).isEqualTo(0.8);
            assertThat(resultado.flags()).doesNotContain(FlagCalidad.NO_EVALUATION);
        }

        @Test
        @DisplayName("una seleccion no elegible cae al respaldo y lo declara")
        void seleccionNoElegible() {
            List<SerieDiaria> serie = serieDispersa(10, List.of(0, 5), 10.0);
            PrecisionPronostico seleccion = new PrecisionPronostico();
            seleccion.setMetodo(MetodoPronostico.HOLT_WINTERS.name());
            seleccion.setHorizonteDias(30);

            CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                    ARTICULO, serie, 30, seleccion, OPCIONES, false);

            assertThat(resultado.metodo()).isNotEqualTo(MetodoPronostico.HOLT_WINTERS);
            assertThat(resultado.motivoFallback()).isEqualTo("METODO_NO_ELEGIBLE");
            assertThat(resultado.flags()).contains(FlagCalidad.NO_EVALUATION);
        }
    }

    @Nested
    @DisplayName("Reposicion")
    class Reposicion {

        @Test
        @DisplayName("mas stock nunca aumenta la cantidad sugerida")
        void monotoniaStock() {
            List<SerieDiaria> serie = serieDispersa(90, List.of(0, 10, 20, 30, 40, 50), 8.0);
            int anterior = Integer.MAX_VALUE;
            for (int stock = 0; stock <= 200; stock += 10) {
                CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                        new CalculadoraPronostico.Articulo(1L, "Articulo", "", "", stock),
                        serie, 30, null, OPCIONES, false);
                assertThat(resultado.cantidadSugerida()).isLessThanOrEqualTo(anterior);
                anterior = resultado.cantidadSugerida();
            }
        }

        @Test
        @DisplayName("una orden de compra abierta reduce la cantidad a pedir")
        void ordenAbiertaReducePedido() {
            List<SerieDiaria> serie = serieDispersa(90, List.of(0, 10, 20, 30, 40, 50), 8.0);
            CalculadoraPronostico.Articulo sinOrden =
                    new CalculadoraPronostico.Articulo(1L, "Articulo", "", "", 0);

            int sinOrdenCantidad = CalculadoraPronostico.calcular(
                    sinOrden, serie, 30, null, OPCIONES, false, false, 0.0)
                    .cantidadSugerida();
            int conOrdenCantidad = CalculadoraPronostico.calcular(
                    sinOrden, serie, 30, null, OPCIONES, false, false, 50.0)
                    .cantidadSugerida();

            assertThat(conOrdenCantidad).isLessThan(sinOrdenCantidad);
        }

        @Test
        @DisplayName("la demanda dispersa se marca intermitente y sin evaluacion")
        void flagsDeDemandaDispersa() {
            List<SerieDiaria> serie = serieDispersa(90, List.of(0, 20, 40, 60, 80), 10.0);

            CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                    ARTICULO, serie, 30, null, OPCIONES, false);

            assertThat(resultado.flags()).contains(
                    FlagCalidad.INTERMITTENT_DEMAND,
                    FlagCalidad.NO_EVALUATION);
        }
    }

    // ── series sinteticas ────────────────────────────────────────────────

    private static List<SerieDiaria> serieDispersa(int dias, List<Integer> posiciones, double cantidad) {
        List<SerieDiaria> serie = new ArrayList<>(dias);
        for (int i = 0; i < dias; i++) {
            serie.add(new SerieDiaria(LUNES.plusDays(i), posiciones.contains(i) ? cantidad : 0.0));
        }
        return serie;
    }
}
