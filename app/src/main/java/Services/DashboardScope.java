package Services;

import Models.Usuarios;

/**
 * Alcance de las metricas del dashboard.
 *
 * <p>El dashboard del admin muestra TODO el negocio: las ventas importadas
 * quedaron bajo el usuario {@code MIGRACION} y filtrar por "Admin" dejaba la
 * pantalla casi en cero. Para expresar "sin filtro" se usa {@link #TODOS} y no
 * {@code null}, porque la cache de Quarkus ({@code @CacheResult} en
 * {@link DashboardMetricsService}) no admite claves nulas.</p>
 */
public final class DashboardScope {

    /** Sentinela: "todas las ventas, de todos los usuarios". */
    public static final Usuarios TODOS = new Usuarios();

    private DashboardScope() {
    }

    /**
     * Nombre de usuario con el que se filtra una metrica. {@code null} en la
     * consulta significa "sin filtro" (todo el negocio).
     */
    public static String usernameOf(Usuarios user) {
        if (user == null || user == TODOS) {
            return null;
        }
        return user.getUsername();
    }
}
