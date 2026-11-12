package Services;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Cabys;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El impuesto CAByS se guarda como texto ("4", "13"), pero es un porcentaje:
 * debe ordenarse numericamente sobre TODO el resultado, no solo sobre la
 * pagina visible ni por orden lexicografico ("4" > "13").
 */
@QuarkusTest
@DisplayName("CAByS: orden numerico de impuesto")
class CabysSortTest {

    @Inject
    CabysService cabysService;

    @Test
    void impuestoOrdenaNumericoYLaPaginacionEsGlobal() {
        String prefijo = "SORTIMPUESTO" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        List<Cabys> creados = new ArrayList<>();
        try {
            for (String impuesto : List.of("4", "13", "2", "0", "1")) {
                Cabys c = new Cabys(prefijo + "-" + impuesto,
                        prefijo + " " + impuesto, "Pruebas", impuesto,
                        "https://example.com", "Activo");
                cabysService.create(c);
                creados.add(c);
            }

            assertThat(cabysService.search(prefijo, "impuesto", true, 0, 10))
                    .extracting(Cabys::getImpuesto)
                    .containsExactly("0", "1", "2", "4", "13");

            assertThat(cabysService.search(prefijo, "impuesto", false, 0, 10))
                    .extracting(Cabys::getImpuesto)
                    .containsExactly("13", "4", "2", "1", "0");

            // La segunda pagina continua el orden global, no reinicia el
            // ordenamiento sobre las filas visibles.
            assertThat(cabysService.search(prefijo, "impuesto", true, 2, 2))
                    .extracting(Cabys::getImpuesto)
                    .containsExactly("2", "4");
            assertThat(cabysService.search(prefijo, "impuesto", true, 4, 2))
                    .extracting(Cabys::getImpuesto)
                    .containsExactly("13");
        } finally {
            for (Cabys c : creados) {
                Cabys managed = cabysService.find(c.getCodigo());
                if (managed != null) {
                    cabysService.delete(managed);
                }
            }
        }
    }
}
