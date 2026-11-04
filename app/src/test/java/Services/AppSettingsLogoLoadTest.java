package Services;

import static org.assertj.core.api.Assertions.assertThat;

import Models.ConfiguracionAplicacion;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/**
 * El logo se guarda como {@code @Lob} (OID de PostgreSQL). Leer la
 * configuración fuera de una transacción hacía fallar el barrido IMAP con
 * "Large Objects may not be used in auto-commit mode": {@code returnCurrent()}
 * ahora hidrata la entidad dentro de su propia transacción.
 */
@QuarkusTest
class AppSettingsLogoLoadTest {

    private static final String PERFIL_QA = "QA logo LOB";

    @Inject
    AppSettingsService service;

    @Inject
    EntityManager em;

    @Test
    void leeElLogoFueraDeUnaTransaccion() {
        byte[] logo = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        byte[][] logoAnterior = new byte[1][];
        String[] mimeAnterior = new String[1];
        Integer id = QuarkusTransaction.requiringNew().call(() -> {
            ConfiguracionAplicacion settings = em
                    .createQuery("SELECT a FROM ConfiguracionAplicacion a WHERE a.estatus = true",
                            ConfiguracionAplicacion.class)
                    .setMaxResults(1)
                    .getResultList().stream().findFirst().orElse(null);
            if (settings == null) {
                settings = new ConfiguracionAplicacion();
                settings.setNombrePerfil(PERFIL_QA);
                settings.setEstatus(true);
            } else {
                logoAnterior[0] = settings.getLogo();
                mimeAnterior[0] = settings.getLogoMimeType();
            }
            settings.setLogo(logo);
            settings.setLogoMimeType("image/png");
            if (settings.getId() == 0) {
                em.persist(settings);
            } else {
                settings = em.merge(settings);
            }
            return settings.getId();
        });

        try {
            // Sin transacción abierta en el hilo de test.
            ConfiguracionAplicacion actual = service.returnCurrent();

            assertThat(actual).isNotNull();
            assertThat(actual.getLogo()).isNotNull().hasSize(logo.length);
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                ConfiguracionAplicacion settings = em.find(ConfiguracionAplicacion.class, id);
                if (settings == null) {
                    return;
                }
                if (PERFIL_QA.equals(settings.getNombrePerfil())) {
                    em.remove(settings);
                } else {
                    settings.setLogo(logoAnterior[0]);
                    settings.setLogoMimeType(mimeAnterior[0]);
                }
            });
        }
    }
}
