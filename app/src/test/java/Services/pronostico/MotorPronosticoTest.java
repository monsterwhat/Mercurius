package Services.pronostico;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Pruebas unitarias puras del nucleo matematico {@link MotorPronostico}.
 *
 * <p>Sin {@code @QuarkusTest}, sin CDI y sin base de datos: la clase es estatica y pura, de modo
 * que JUnit 5 + AssertJ bastan.
 *
 * <p>La referencia funcional es {@code Services.StockForecastService} (media de 90 dias con factor
 * de tendencia multiplicativo). Estas pruebas fijan los comportamientos que ese motor no tenia:
 * pronostico acotado con tendencia, estacionalidad efectivamente aplicada y tratamiento de la
 * demanda intermitente.
 */
class MotorPronosticoTest {

    /** Lunes de referencia para alinear los dias de semana de las series sinteticas. */
    private static final LocalDate LUNES = LocalDate.of(2024, 1, 1);

    private static final Hiperparametros POR_DEFECTO = Hiperparametros.defecto();

    /** Periodos con venta dentro de una serie de 120 dias (85% de ceros). */
    private static final int[] PICOS_INTERMITENTES = {
            2, 9, 13, 20, 27, 31, 38, 45, 52, 59, 66, 73, 80, 87, 94, 101, 108, 115
    };

    // ------------------------------------------------------------------
    // SES
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Suavizamiento exponencial simple")
    class SesTests {

        @Test
        @DisplayName("serie constante: pronostico plano igual a la constante")
        void sesConstante() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(30, 5L));

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.SES, historia, 7, POR_DEFECTO);

            assertThat(pronostico).hasSize(7);
            assertThat(pronostico)
                    .allSatisfy(v -> assertThat(v).isCloseTo(5.0, offset(1e-9)));
        }

        @Test
        @DisplayName("serie constante con horizonte largo: sigue plana (no hay tendencia que mantener)")
        void sesConstanteHorizonteLargo() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(400, 12L));

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.SES, historia, 365, POR_DEFECTO);

            assertThat(pronostico).hasSize(365);
            assertThat(pronostico).allSatisfy(v -> assertThat(v).isEqualTo(12.0));
        }

        @Test
        @DisplayName("nivel de SES con alfa = 1 es la ultima observacion")
        void sesAlfaUno() {
            List<SerieDiaria> historia = serie(LUNES, 4L, 9L, 3L, 11L);

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.SES, historia, 3, new Hiperparametros(1.0, 0.05, 0.1, 0.9));

            assertThat(pronostico).containsExactly(11.0, 11.0, 11.0);
        }

        @Test
        @DisplayName("historia de un solo punto: no lanza excepcion")
        void sesUnPunto() {
            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.SES, serie(LUNES, 42L), 4, POR_DEFECTO);

            assertThat(pronostico).containsExactly(42.0, 42.0, 42.0, 42.0);
        }
    }

    // ------------------------------------------------------------------
    // Holt amortiguado: el "pin" anti-explosion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Holt con tendencia amortiguada")
    class HoltAmortiguadoTests {

        @Test
        @DisplayName("tendencia +2%/dia: pronostico a 30 dias acotado por debajo de 3x la ultima observacion")
        void tendenciaAcotadaEnHorizonteLargo() {
            List<SerieDiaria> historia = serieGeometrica(LUNES, 90, 100.0, 1.02);
            double ultima = historia.get(historia.size() - 1).cantidad();

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_AMORTIGUADO, historia, 30, POR_DEFECTO);

            assertThat(pronostico).hasSize(30);
            assertThat(pronostico).allSatisfy(v -> assertThat(Double.isFinite(v)).isTrue());
            // El pin anti-explosion: el motor antiguo multiplicaba la prediccion por un factor
            // geometrico y a 30 dias devolvia cifras absurdas.
            assertThat(pronostico.get(29))
                    .as("pronostico a 30 dias sobre una tendencia del 2 por ciento diario")
                    .isLessThan(3.0 * ultima);
            // La pendiente sigue siendo positiva y razonable (no se aplana a cero).
            assertThat(pronostico.get(29)).isGreaterThan(pronostico.get(0));
            assertThat(pronostico.get(0)).isGreaterThan(0.0);
            // Monotono creciente: la suma phi + phi^2 + ... + phi^h crece con h.
            for (int i = 1; i < pronostico.size(); i++) {
                assertThat(pronostico.get(i)).isGreaterThanOrEqualTo(pronostico.get(i - 1));
            }
        }

        @Test
        @DisplayName("phi = 0.9 comprime mucho mas el horizonte que phi = 1.0")
        void amortiguacionReduceExplosion() {
            List<SerieDiaria> historia = serieGeometrica(LUNES, 60, 100.0, 1.05);

            double conAmortiguacion = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_AMORTIGUADO, historia, 30,
                    new Hiperparametros(0.1, 0.05, 0.1, 0.9)).get(29);
            double sinAmortiguacion = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_AMORTIGUADO, historia, 30,
                    new Hiperparametros(0.1, 0.05, 0.1, 1.0)).get(29);

            assertThat(conAmortiguacion)
                    .as("con phi = 0.9 el horizonte se comprime frente a phi = 1.0")
                    .isLessThan(sinAmortiguacion * 0.75);
        }

        @Test
        @DisplayName("serie decreciente: el pronostico se acota a 0 (la demanda no puede ser negativa)")
        void pronosticoNegativoSeAcota() {
            List<SerieDiaria> historia = serie(LUNES, 10L, 5L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_AMORTIGUADO, historia, 7, POR_DEFECTO);

            assertThat(pronostico).hasSize(7)
                    .allSatisfy(v -> assertThat(v).isGreaterThanOrEqualTo(0.0));
            assertThat(pronostico.get(6)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("serie plana: pronostico plano en el nivel")
        void seriePlanaSinTendencia() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(50, 7L));

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_AMORTIGUADO, historia, 5, POR_DEFECTO);

            assertThat(pronostico)
                    .allSatisfy(v -> assertThat(v).isCloseTo(7.0, offset(1e-9)));
        }
    }

    // ------------------------------------------------------------------
    // Holt-Winters: estacionalidad semanal
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Holt-Winters semanal")
    class HoltWintersTests {

        @Test
        @DisplayName("recupera el factor semanal: fin de semana = 2x dia laborable (+-20%)")
        void recuperaFactorSemanal() {
            // 8 ciclos completos: 10 unidades de lunes a viernes y 20 sabado y domingo.
            List<SerieDiaria> historia = serieSemanal(LUNES, 8);

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_WINTERS, historia, 14, POR_DEFECTO);

            assertThat(pronostico).hasSize(14);
            LocalDate ultimaFecha = historia.get(historia.size() - 1).fecha();
            double sumaFinDeSemana = 0.0;
            int diasFinDeSemana = 0;
            double sumaLaborable = 0.0;
            int diasLaborables = 0;
            for (int h = 1; h <= 14; h++) {
                DayOfWeek dia = ultimaFecha.plusDays(h).getDayOfWeek();
                if (dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY) {
                    sumaFinDeSemana += pronostico.get(h - 1);
                    diasFinDeSemana++;
                } else {
                    sumaLaborable += pronostico.get(h - 1);
                    diasLaborables++;
                }
            }
            assertThat(diasFinDeSemana).isEqualTo(4);
            assertThat(diasLaborables).isEqualTo(10);
            double mediaFinDeSemana = sumaFinDeSemana / diasFinDeSemana;
            double mediaLaborable = sumaLaborable / diasLaborables;

            assertThat(mediaLaborable).isGreaterThan(0.0);
            assertThat(mediaFinDeSemana / mediaLaborable)
                    .as("razon fin de semana / laborable en el horizonte")
                    .isBetween(1.6, 2.4);
        }

        @Test
        @DisplayName("con menos de 28 observaciones lanza IllegalArgumentException")
        void holtWintersHistoriaCortaLanza() {
            List<SerieDiaria> historia = serieSemanal(LUNES, 1);

            assertThatThrownBy(() -> MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_WINTERS, historia, 7, POR_DEFECTO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("28");
        }

        @Test
        @DisplayName("con exactamente 28 observaciones ya es aplicable")
        void holtWintersEnElLimite() {
            List<SerieDiaria> historia = serieSemanal(LUNES, 4);

            assertThat(MotorPronostico.elegible(MetodoPronostico.HOLT_WINTERS, historia)).isTrue();
            assertThat(MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_WINTERS, historia, 7, POR_DEFECTO)).hasSize(7);
        }
    }

    // ------------------------------------------------------------------
    // SBA: demanda intermitente
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("SBA (Croston ajustado)")
    class SbaTests {

        @Test
        @DisplayName("85 por ciento de ceros con picos: pronostico finito y dentro de [0, maximo del pico]")
        void intermitenteAcotado() {
            List<SerieDiaria> historia = serieIntermitente(LUNES, 120, PICOS_INTERMITENTES);
            double maxPico = historia.stream().mapToLong(SerieDiaria::cantidad).max().orElseThrow();

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.SBA, historia, 30, POR_DEFECTO);

            assertThat(pronostico).hasSize(30);
            assertThat(pronostico).allSatisfy(v -> {
                assertThat(Double.isFinite(v)).isTrue();
                assertThat(v).isGreaterThanOrEqualTo(0.0);
                assertThat(v).isLessThanOrEqualTo(maxPico);
            });
            // El valor debe ser informativo: ni colapsar a cero ni igualarse al maximo del pico.
            assertThat(pronostico.get(0)).isGreaterThan(0.0);
            assertThat(pronostico.get(0)).isLessThan(maxPico);
        }

        @Test
        @DisplayName("picos periodicos constantes: pronostico plano igual a (1 - alfa/2) * Z / P")
        void picosPeriodicosPlanos() {
            // 30 ventas de 30 unidades en 90 dias, una cada 3: 30 eventos no nulos separados por
            // intervalos de 3 periodos, de modo que P arranca en 3.
            List<SerieDiaria> historia = serie(LUNES, picosCada(90, 3, 30L));

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.SBA, historia, 14, POR_DEFECTO);

            // La recurrencia P = alfa * 1 + (1 - alfa) * Pprev arranca en el intervalo medio (3) y
            // decae hacia 1; Z se queda en 30 porque todos los picos son iguales.
            double intervalo = 3.0;
            for (int i = 0; i < 30; i++) {
                intervalo = 0.1 + 0.9 * intervalo;
            }
            double esperado = (1.0 - 0.1 / 2.0) * 30.0 / intervalo;

            assertThat(pronostico).hasSize(14);
            assertThat(pronostico.get(0)).isCloseTo(esperado, offset(1e-9));
            assertThat(pronostico.get(13)).isCloseTo(esperado, offset(1e-9));
            // P ha decaido: el pronostico por periodo es mayor que el tamano medio del pico / 3.
            assertThat(pronostico.get(0)).isGreaterThan(9.5);
            assertThat(pronostico.get(0)).isLessThanOrEqualTo(30.0);
        }

        @Test
        @DisplayName("historia sin ninguna venta: devuelve ceros, no explota ni lanza")
        void sinVentasDevuelveCeros() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(60, 0L));

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.SBA, historia, 10, POR_DEFECTO);

            assertThat(pronostico).containsExactly(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        @Test
        @DisplayName("la serie intermitente sintetica cae en el regimen INTERMITENTE")
        void intermitenteSeClasifica() {
            List<SerieDiaria> historia = serieIntermitente(LUNES, 120, PICOS_INTERMITENTES);

            assertThat(MotorPronostico.clasificar(historia)).isEqualTo(Regimen.INTERMITENTE);
        }
    }

    // ------------------------------------------------------------------
    // Ingenuo, estacional ingenuo y media movil
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Metodos ingenuos y media movil")
    class BasicosTests {

        @Test
        @DisplayName("INGENUO repite la ultima observacion")
        void ingenuo() {
            List<SerieDiaria> historia = serie(LUNES, 4L, 9L, 3L, 11L);

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.INGENUO, historia, 4, POR_DEFECTO);

            assertThat(pronostico).containsExactly(11.0, 11.0, 11.0, 11.0);
        }

        @Test
        @DisplayName("MEDIA_MOVIL promedia las ultimas min(28, n) observaciones")
        void mediaMovil() {
            List<SerieDiaria> historia = serie(LUNES, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.MEDIA_MOVIL, historia, 3, POR_DEFECTO);

            assertThat(pronostico).containsExactly(5.5, 5.5, 5.5);
        }

        @Test
        @DisplayName("MEDIA_MOVIL con historia larga solo mira los ultimos 28 dias")
        void mediaMovilVentanaAcotada() {
            List<SerieDiaria> historia = new ArrayList<>(serie(LUNES, repeticion(24, 2L)));
            historia.addAll(serie(LUNES.plusDays(24), repeticion(28, 50L)));

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.MEDIA_MOVIL, historia, 2, POR_DEFECTO);

            assertThat(pronostico).containsExactly(50.0, 50.0);
        }

        @Test
        @DisplayName("ESTACIONAL_INGENUO repite el valor de hace 7 dias")
        void estacionalIngenuo() {
            List<SerieDiaria> historia = serie(LUNES, ascendentes(20));

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.ESTACIONAL_INGENUO, historia, 8, POR_DEFECTO);

            // Indice = n - 1 + h - 7 con n = 20: h = 1 toma el indice 13 (dia 14).
            assertThat(pronostico.get(0)).isEqualTo(14.0);
            // h = 7 repite el mismo dia de semana de la ultima observacion (indice 19).
            assertThat(pronostico.get(6)).isEqualTo(20.0);
            // h = 8 sale de la historia: recurre al ultimo domingo disponible (indice 13).
            assertThat(pronostico.get(7)).isEqualTo(14.0);
        }

        @Test
        @DisplayName("ESTACIONAL_INGENUO con historia menor que un ciclo no falla")
        void estacionalIngenuoHistoriaCorta() {
            List<SerieDiaria> historia = serie(LUNES, 6L, 8L);

            List<Double> pronostico = MotorPronostico.pronosticar(
                    MetodoPronostico.ESTACIONAL_INGENUO, historia, 5, POR_DEFECTO);

            assertThat(pronostico).hasSize(5)
                    .allSatisfy(v -> assertThat(v).isGreaterThanOrEqualTo(0.0));
        }
    }

    // ------------------------------------------------------------------
    // MASE y sesgo escalado
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Metricas de evaluacion")
    class MetricasTests {

        private static final List<Double> REALES = List.of(10.0, 12.0, 11.0, 13.0, 12.0);

        /** MAE del ingenuo dentro de la muestra: (0 + 2 + 1 + 2 + 1) / 5 = 1.2. */
        private static final double MAE_INGENUO = 1.2;

        @Test
        @DisplayName("MASE de un pronostico perfecto es exactamente 0")
        void masePerfecto() {
            assertThat(MotorPronostico.mase(REALES, REALES, MAE_INGENUO)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("MASE > 1 cuando el modelo es peor que el ingenuo")
        void masePeorQueIngenuo() {
            List<Double> pesimo = List.of(0.0, 0.0, 0.0, 0.0, 0.0);

            double mase = MotorPronostico.mase(REALES, pesimo, MAE_INGENUO);

            // MAE = (10 + 12 + 11 + 13 + 12) / 5 = 11.6 -> MASE = 11.6 / 1.2 = 9.666...
            assertThat(mase).isCloseTo(9.6667, offset(1e-3));
            assertThat(mase).isGreaterThan(1.0);
        }

        @Test
        @DisplayName("MASE menor que 1 cuando el modelo bate al ingenuo en la muestra")
        void maseMejorQueIngenuo() {
            List<Double> casiPerfecto = List.of(10.5, 11.5, 11.5, 12.5, 12.5);

            double mase = MotorPronostico.mase(REALES, casiPerfecto, MAE_INGENUO);

            // MAE = 0.5 -> MASE = 0.5 / 1.2 = 0.4167
            assertThat(mase).isCloseTo(0.4167, offset(1e-3));
            assertThat(mase).isLessThan(1.0);
        }

        @Test
        @DisplayName("MASE es NaN si el MAE ingenuo en la muestra es 0")
        void maseDenominadorCero() {
            assertThat(MotorPronostico.mase(REALES, REALES, 0.0)).isNaN();
        }

        @Test
        @DisplayName("MASE es NaN si no hay pares comparables")
        void maseSinPares() {
            assertThat(MotorPronostico.mase(List.of(), List.of(), MAE_INGENUO)).isNaN();
            assertThat(MotorPronostico.mase(null, null, MAE_INGENUO)).isNaN();
        }

        @Test
        @DisplayName("sesgoEscalado: positivo = subpronostico, negativo = sobrepronostico")
        void sesgoEscaladoSigno() {
            List<Double> subpronostico = List.of(9.0, 11.0, 10.0, 12.0, 11.0);
            List<Double> sobrepronostico = List.of(11.0, 13.0, 12.0, 14.0, 13.0);

            assertThat(MotorPronostico.sesgoEscalado(REALES, subpronostico, MAE_INGENUO))
                    .isCloseTo(0.8333, offset(1e-3));
            assertThat(MotorPronostico.sesgoEscalado(REALES, sobrepronostico, MAE_INGENUO))
                    .isCloseTo(-0.8333, offset(1e-3));
            assertThat(MotorPronostico.sesgoEscalado(REALES, REALES, MAE_INGENUO)).isEqualTo(0.0);
            assertThat(MotorPronostico.sesgoEscalado(REALES, REALES, 0.0)).isNaN();
        }

        @Test
        @DisplayName("fuera de muestra: Holt-Winters bate a SES en una serie con semana")
        void holtWintersBateSesFueraDeMuestra() {
            List<SerieDiaria> completa = serieSemanal(LUNES, 10);
            List<SerieDiaria> historia = List.copyOf(completa.subList(0, 56));
            List<Double> reales = new ArrayList<>();
            for (int i = 56; i < 70; i++) {
                reales.add((double) completa.get(i).cantidad());
            }
            double maeIngenuo = maeIngenuoEnMuestra(historia);

            double maseHoltWinters = MotorPronostico.mase(reales,
                    MotorPronostico.pronosticar(MetodoPronostico.HOLT_WINTERS, historia, 14, POR_DEFECTO),
                    maeIngenuo);
            double maseSes = MotorPronostico.mase(reales,
                    MotorPronostico.pronosticar(MetodoPronostico.SES, historia, 14, POR_DEFECTO),
                    maeIngenuo);

            assertThat(maeIngenuo).isGreaterThan(0.0);
            assertThat(maseHoltWinters).as("MASE de Holt-Winters").isLessThan(0.5);
            // SES no modela la semana: solo puede igualar al ingenuo.
            assertThat(maseHoltWinters).isLessThan(maseSes);
        }
    }

    // ------------------------------------------------------------------
    // Clasificacion ADI / CV^2
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Clasificacion de regimen (cuadrante ADI / CV^2)")
    class ClasificacionTests {

        @Test
        @DisplayName("SUAVE: ADI = 1.00 y CV^2 = 0.002")
        void suave() {
            List<SerieDiaria> historia = serie(LUNES, alternos(100, 10L, 11L));

            assertThat(MotorPronostico.clasificar(historia)).isEqualTo(Regimen.SUAVE);
        }

        @Test
        @DisplayName("ERRATICO: ADI = 1.00 y CV^2 = 0.88")
        void erratico() {
            List<SerieDiaria> historia = serie(LUNES, alternos(100, 1L, 30L));

            assertThat(MotorPronostico.clasificar(historia)).isEqualTo(Regimen.ERRATICO);
        }

        @Test
        @DisplayName("INTERMITENTE: ADI = 3.00 y CV^2 = 0 (magnitudes constantes)")
        void intermitente() {
            List<SerieDiaria> historia = serie(LUNES, picosCada(90, 3, 10L));

            assertThat(MotorPronostico.clasificar(historia)).isEqualTo(Regimen.INTERMITENTE);
        }

        @Test
        @DisplayName("GRUMOSO: ADI = 3.00 y CV^2 = 0.53")
        void grumoso() {
            List<SerieDiaria> historia = serie(LUNES, picosCadaVariables(90, 3, 5L, 30L));

            assertThat(MotorPronostico.clasificar(historia)).isEqualTo(Regimen.GRUMOSO);
        }

        @Test
        @DisplayName("SIN_DATOS: historia vacia, nula o completamente nula")
        void sinDatos() {
            assertThat(MotorPronostico.clasificar(List.of())).isEqualTo(Regimen.SIN_DATOS);
            assertThat(MotorPronostico.clasificar(null)).isEqualTo(Regimen.SIN_DATOS);
            assertThat(MotorPronostico.clasificar(serie(LUNES, repeticion(45, 0L))))
                    .isEqualTo(Regimen.SIN_DATOS);
        }

        @Test
        @DisplayName("los dias sin movimiento se cuentan como ceros (rejilla diaria completa)")
        void huecosSeRellenanConCero() {
            // 20 dias con venta cada 3 dentro de un rango de 58 dias: ADI = 58 / 20 = 2.9.
            List<SerieDiaria> historia = new ArrayList<>();
            for (int k = 0; k < 20; k++) {
                historia.add(new SerieDiaria(LUNES.plusDays(3L * k), 10L));
            }

            assertThat(MotorPronostico.clasificar(historia)).isEqualTo(Regimen.INTERMITENTE);
            // La media movil promedia los 28 ultimos periodos de la rejilla: 10 ceros de 10 en
            // 28 periodos -> 100 / 28 = 3.5714. Sin rellenar huecos daria 10.
            assertThat(MotorPronostico.pronosticar(
                    MetodoPronostico.MEDIA_MOVIL, historia, 1, POR_DEFECTO).get(0))
                    .isCloseTo(3.5714, offset(1e-3));
        }

        @Test
        @DisplayName("candidatos por regimen")
        void candidatosPorRegimen() {
            assertThat(MotorPronostico.candidatos(Regimen.SUAVE))
                    .containsExactly(MetodoPronostico.SES, MetodoPronostico.HOLT_AMORTIGUADO,
                            MetodoPronostico.MEDIA_MOVIL);
            assertThat(MotorPronostico.candidatos(Regimen.ERRATICO))
                    .containsExactly(MetodoPronostico.SES, MetodoPronostico.MEDIA_MOVIL);
            assertThat(MotorPronostico.candidatos(Regimen.INTERMITENTE))
                    .containsExactly(MetodoPronostico.SBA, MetodoPronostico.SES,
                            MetodoPronostico.ESTACIONAL_INGENUO);
            assertThat(MotorPronostico.candidatos(Regimen.GRUMOSO))
                    .containsExactly(MetodoPronostico.SBA, MetodoPronostico.SES);
            assertThat(MotorPronostico.candidatos(Regimen.SIN_DATOS))
                    .containsExactly(MetodoPronostico.INGENUO);
        }

        @Test
        @DisplayName("cada candidato propuesto para el regimen es elegible con historia suficiente")
        void candidatosSonElegibles() {
            List<SerieDiaria> suave = serie(LUNES, alternos(120, 10L, 11L));
            List<SerieDiaria> intermitente = serieIntermitente(LUNES, 120, PICOS_INTERMITENTES);

            for (MetodoPronostico metodo : MotorPronostico.candidatos(MotorPronostico.clasificar(suave))) {
                assertThat(MotorPronostico.elegible(metodo, suave)).isTrue();
            }
            for (MetodoPronostico metodo
                    : MotorPronostico.candidatos(MotorPronostico.clasificar(intermitente))) {
                assertThat(MotorPronostico.elegible(metodo, intermitente)).isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // Guardas de elegibilidad
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Guardas de elegibilidad")
    class ElegibleTests {

        @Test
        @DisplayName("HOLT_WINTERS con 10 observaciones no es elegible")
        void holtWintersDiezObservaciones() {
            List<SerieDiaria> siete = serieSemanal(LUNES, 1);
            List<SerieDiaria> diez = serie(LUNES,
                    10L, 20L, 10L, 20L, 10L, 20L, 10L, 20L, 10L, 20L, 0L, 0L);

            assertThat(siete).hasSize(7);
            assertThat(diez).hasSize(12);
            assertThat(MotorPronostico.elegible(MetodoPronostico.HOLT_WINTERS, siete)).isFalse();
            assertThat(MotorPronostico.elegible(MetodoPronostico.HOLT_WINTERS, diez)).isFalse();
            assertThat(MotorPronostico.elegible(MetodoPronostico.HOLT_WINTERS, List.of())).isFalse();
        }

        @Test
        @DisplayName("HOLT_WINTERS con 28 observaciones si es elegible")
        void holtWintersVeintiocho() {
            assertThat(MotorPronostico.elegible(MetodoPronostico.HOLT_WINTERS, serieSemanal(LUNES, 4)))
                    .isTrue();
        }

        @Test
        @DisplayName("SBA exige al menos 3 demandas no nulas")
        void sbaExigeTresNoNulos() {
            List<SerieDiaria> dosNoNulos = serie(LUNES, 5L, 0L, 0L, 0L, 8L, 0L, 0L);
            List<SerieDiaria> tresNoNulos = serie(LUNES, 5L, 0L, 0L, 0L, 8L, 0L, 0L, 3L);

            assertThat(MotorPronostico.elegible(MetodoPronostico.SBA, dosNoNulos)).isFalse();
            assertThat(MotorPronostico.elegible(MetodoPronostico.SBA, tresNoNulos)).isTrue();
        }

        @Test
        @DisplayName("SES, Holt amortiguado y media movil exigen 2 observaciones")
        void suavizadosExigenDos() {
            List<SerieDiaria> uno = serie(LUNES, 5L);
            List<SerieDiaria> dos = serie(LUNES, 5L, 6L);

            for (MetodoPronostico metodo : List.of(MetodoPronostico.SES,
                    MetodoPronostico.HOLT_AMORTIGUADO, MetodoPronostico.MEDIA_MOVIL)) {
                assertThat(MotorPronostico.elegible(metodo, uno)).as("metodo %s con 1 obs", metodo).isFalse();
                assertThat(MotorPronostico.elegible(metodo, dos)).as("metodo %s con 2 obs", metodo).isTrue();
            }
        }

        @Test
        @DisplayName("los metodos ingenuos basta con una observacion")
        void ingenuosConUnaSobra() {
            List<SerieDiaria> uno = serie(LUNES, 5L);

            assertThat(MotorPronostico.elegible(MetodoPronostico.INGENUO, uno)).isTrue();
            assertThat(MotorPronostico.elegible(MetodoPronostico.ESTACIONAL_INGENUO, uno)).isTrue();
            assertThat(MotorPronostico.elegible(MetodoPronostico.INGENUO, List.of())).isFalse();
            assertThat(MotorPronostico.elegible(MetodoPronostico.ESTACIONAL_INGENUO, List.of())).isFalse();
        }

        @Test
        @DisplayName("sin historia no hay nada elegible; un metodo nulo tampoco")
        void sinHistoriaNoHayNadaElegible() {
            for (MetodoPronostico metodo : MetodoPronostico.values()) {
                assertThat(MotorPronostico.elegible(metodo, List.of()))
                        .as("metodo %s sin historia", metodo).isFalse();
                assertThat(MotorPronostico.elegible(metodo, null))
                        .as("metodo %s con historia nula", metodo).isFalse();
            }
            assertThat(MotorPronostico.elegible(null, serie(LUNES, 1L))).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Robustez del contrato publico
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Robustez del contrato")
    class RobustezTests {

        @Test
        @DisplayName("historia vacia: todos los metodos devuelven ceros sin lanzar")
        void historiaVaciaDevuelveCeros() {
            for (MetodoPronostico metodo : MetodoPronostico.values()) {
                if (metodo == MetodoPronostico.HOLT_WINTERS) {
                    continue; // unica excepcion documentada, cubierta en su propio grupo
                }
                List<Double> pronostico = MotorPronostico.pronosticar(metodo, List.of(), 5, POR_DEFECTO);
                assertThat(pronostico)
                        .as("metodo %s sin historia", metodo)
                        .containsExactly(0.0, 0.0, 0.0, 0.0, 0.0);
            }
        }

        @Test
        @DisplayName("historia de un solo punto: todos los metodos salvo Holt-Winters responden")
        void historiaDeUnPunto() {
            for (MetodoPronostico metodo : MetodoPronostico.values()) {
                if (metodo == MetodoPronostico.HOLT_WINTERS) {
                    continue;
                }
                List<Double> pronostico = MotorPronostico.pronosticar(
                        metodo, serie(LUNES, 17L), 4, POR_DEFECTO);
                assertThat(pronostico).as("metodo %s con un punto", metodo).hasSize(4)
                        .allSatisfy(v -> {
                            assertThat(Double.isFinite(v)).isTrue();
                            assertThat(v).isGreaterThanOrEqualTo(0.0);
                        });
            }
        }

        @Test
        @DisplayName("la salida tiene siempre horizonteDias elementos y es inmutable")
        void formaDeLaSalida() {
            List<SerieDiaria> historia = serieSemanal(LUNES, 4);

            for (MetodoPronostico metodo : MetodoPronostico.values()) {
                List<Double> pronostico = MotorPronostico.pronosticar(metodo, historia, 9, POR_DEFECTO);
                assertThat(pronostico).as("metodo %s", metodo).hasSize(9);
                assertThatThrownBy(() -> pronostico.set(0, 1.0))
                        .as("la salida de %s no debe ser modificable", metodo)
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }

        @Test
        @DisplayName("horizonte cero o negativo devuelve lista vacia")
        void horizonteNoPositivo() {
            List<SerieDiaria> historia = serieSemanal(LUNES, 4);

            assertThat(MotorPronostico.pronosticar(MetodoPronostico.SES, historia, 0, POR_DEFECTO)).isEmpty();
            assertThat(MotorPronostico.pronosticar(MetodoPronostico.SES, historia, -5, POR_DEFECTO)).isEmpty();
        }

        @Test
        @DisplayName("hiperparametros nulos caen en los valores por defecto")
        void hiperparametrosNulos() {
            List<SerieDiaria> historia = serie(LUNES, repeticion(40, 6L));

            assertThat(MotorPronostico.pronosticar(MetodoPronostico.SES, historia, 3, null))
                    .isEqualTo(MotorPronostico.pronosticar(MetodoPronostico.SES, historia, 3, POR_DEFECTO));
        }

        @Test
        @DisplayName("hiperparametros imposibles caen en los valores por defecto en lugar de propagarse")
        void hiperparametrosFueraDeRango() {
            List<SerieDiaria> historia = serieGeometrica(LUNES, 60, 100.0, 1.03);
            Hiperparametros imposibles = new Hiperparametros(5.0, -2.0, Double.NaN, 7.0);

            List<Double> conImposibles = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_AMORTIGUADO, historia, 10, imposibles);
            List<Double> conDefecto = MotorPronostico.pronosticar(
                    MetodoPronostico.HOLT_AMORTIGUADO, historia, 10, POR_DEFECTO);

            assertThat(conImposibles).containsExactlyElementsOf(conDefecto);
            assertThat(conImposibles).allSatisfy(v -> assertThat(Double.isFinite(v)).isTrue());
        }

        @Test
        @DisplayName("metodo nulo: excepcion explicita de contrato")
        void metodoNulo() {
            assertThatThrownBy(() -> MotorPronostico.pronosticar(null, serie(LUNES, 1L), 3, POR_DEFECTO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("fechas repetidas se agregan y los huecos se rellenan con cero")
        void rejillaDiaria() {
            List<SerieDiaria> historia = List.of(
                    new SerieDiaria(LUNES, 10L),
                    new SerieDiaria(LUNES, 5L),          // mismo dia: 15 en total
                    new SerieDiaria(LUNES.plusDays(4), 20L));

            // Rejilla de 5 periodos: 15, 0, 0, 0, 20. INGENUO toma el ultimo.
            assertThat(MotorPronostico.pronosticar(MetodoPronostico.INGENUO, historia, 2, POR_DEFECTO))
                    .containsExactly(20.0, 20.0);
            // MEDIA_MOVIL promedia los 5 periodos: 35 / 5 = 7.
            assertThat(MotorPronostico.pronosticar(MetodoPronostico.MEDIA_MOVIL, historia, 1, POR_DEFECTO))
                    .containsExactly(7.0);
            // 2 periodos no nulos de 5 -> ADI = 2.5; magnitudes 15 y 20 -> CV^2 bajo.
            assertThat(MotorPronostico.clasificar(historia)).isEqualTo(Regimen.INTERMITENTE);
        }

        @Test
        @DisplayName("Hiperparametros.defecto() expone los valores del dominio")
        void valoresPorDefecto() {
            assertThat(Hiperparametros.defecto().alfa()).isEqualTo(0.1);
            assertThat(Hiperparametros.defecto().beta()).isEqualTo(0.05);
            assertThat(Hiperparametros.defecto().gamma()).isEqualTo(0.1);
            assertThat(Hiperparametros.defecto().phi()).isEqualTo(0.9);
        }
    }

    // ------------------------------------------------------------------
    // Constructores de series sinteticas
    // ------------------------------------------------------------------

    private static List<SerieDiaria> serie(LocalDate inicio, long... cantidades) {
        List<SerieDiaria> puntos = new ArrayList<>(cantidades.length);
        for (int i = 0; i < cantidades.length; i++) {
            puntos.add(new SerieDiaria(inicio.plusDays(i), cantidades[i]));
        }
        return puntos;
    }

    /** Serie de crecimiento geometrico diario a partir de {@code base} y multiplicada por {@code factor}. */
    private static List<SerieDiaria> serieGeometrica(LocalDate inicio, int periodos, double base, double factor) {
        List<SerieDiaria> puntos = new ArrayList<>(periodos);
        double valor = base;
        for (int i = 0; i < periodos; i++) {
            puntos.add(new SerieDiaria(inicio.plusDays(i), Math.round(valor)));
            valor *= factor;
        }
        return puntos;
    }

    /** Serie con ceros salvo en las posiciones indicadas, con magnitud pseudoaleatoria estable. */
    private static List<SerieDiaria> serieIntermitente(LocalDate inicio, int periodos, int[] indicesNoNulos) {
        Set<Integer> picos = new HashSet<>();
        for (int indice : indicesNoNulos) {
            picos.add(indice);
        }
        List<SerieDiaria> puntos = new ArrayList<>(periodos);
        for (int i = 0; i < periodos; i++) {
            long cantidad = picos.contains(i) ? (long) (20 + ((i * 7) % 25)) : 0L;
            puntos.add(new SerieDiaria(inicio.plusDays(i), cantidad));
        }
        return puntos;
    }

    /** Serie semanal pura: 10 unidades de lunes a viernes y 20 sabado y domingo. */
    private static List<SerieDiaria> serieSemanal(LocalDate inicio, int ciclos) {
        List<SerieDiaria> puntos = new ArrayList<>(ciclos * 7);
        for (int dia = 0; dia < ciclos * 7; dia++) {
            DayOfWeek dow = inicio.plusDays(dia).getDayOfWeek();
            long cantidad = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? 20L : 10L;
            puntos.add(new SerieDiaria(inicio.plusDays(dia), cantidad));
        }
        return puntos;
    }

    private static long[] repeticion(int veces, long valor) {
        long[] salida = new long[veces];
        Arrays.fill(salida, valor);
        return salida;
    }

    private static long[] alternos(int veces, long a, long b) {
        long[] salida = new long[veces];
        for (int i = 0; i < veces; i++) {
            salida[i] = (i % 2 == 0) ? a : b;
        }
        return salida;
    }

    private static long[] ascendentes(int periodos) {
        long[] salida = new long[periodos];
        for (int i = 0; i < periodos; i++) {
            salida[i] = i + 1L;
        }
        return salida;
    }

    private static long[] picosCada(int periodos, int cada, long magnitud) {
        long[] salida = new long[periodos];
        for (int i = 0; i < periodos; i += cada) {
            salida[i] = magnitud;
        }
        return salida;
    }

    private static long[] picosCadaVariables(int periodos, int cada, long magnitudA, long magnitudB) {
        long[] salida = new long[periodos];
        int pico = 0;
        for (int i = 0; i < periodos; i += cada) {
            salida[i] = ((pico++ % 2 == 0) ? magnitudA : magnitudB);
        }
        return salida;
    }

    /** MAE del pronostico ingenuo dentro de la muestra: F(t) = D(t-1). */
    private static double maeIngenuoEnMuestra(List<SerieDiaria> historia) {
        if (historia.size() < 2) {
            return 0.0;
        }
        double suma = 0.0;
        for (int t = 1; t < historia.size(); t++) {
            suma += Math.abs(historia.get(t).cantidad() - historia.get(t - 1).cantidad());
        }
        return suma / (historia.size() - 1);
    }
}
