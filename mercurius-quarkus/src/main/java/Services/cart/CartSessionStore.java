package Services.cart;

import Models.Articulos.Carrito.CartSessionContext;
import Models.PagoEntry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T37-prep (plan mercurius-jsf-to-api-migration): per-cashier POS cart session
 * store for the future REST POS surface.
 *
 * <p>One {@link CartSessionContext} per authenticated username (user decision:
 * un carrito por cajero logueado). The context itself stays caller-owned
 * mutable state exactly as extracted in T5 — this store only decides WHO owns
 * which instance and for how long. {@link CartSessionContext} is NOT modified.</p>
 *
 * <p>Each entry is wrapped internally with last-access tracking plus the two
 * pieces of per-session state that live OUTSIDE the legacy context (the
 * supervisor price-override authorization recorded by
 * {@code PosResource.overrideAuthorize}, and the payment-entries list edited
 * via POST /payment-entries). Entries idle longer than {@link #TTL_MILLIS}
 * (4 hours) are evicted by a scheduled sweep every 30 minutes; any subsequent
 * {@link #getOrCreate} for the same user simply starts a fresh cart.</p>
 *
 * <p>Thread-safety: {@link ConcurrentHashMap} keyed by username; entry fields
 * are volatile and mutated only through small setter methods, mirroring the
 * request-per-thread JAX-RS access pattern (a single cashier's requests may
 * interleave, but each field write is atomic).</p>
 */
@ApplicationScoped
public class CartSessionStore {

    /** Idle time after which an entry becomes eligible for eviction: 4 hours. */
    public static final long TTL_MILLIS = 4L * 60L * 60L * 1000L;

    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

    /**
     * Internal wrapper around one cashier's cart state. Kept as a static
     * nested class so {@link CartSessionContext} remains untouched while the
     * store owns lastAccess/TTL bookkeeping.
     */
    public static final class Entry {

        private final CartSessionContext cartContext = new CartSessionContext();

        /** Epoch millis of the last {@link #touch()}; clock seam for TTL tests. */
        private volatile long lastAccess = System.currentTimeMillis();

        /** Supervisor username that authorized price overrides, or null. */
        private volatile @Nullable String authorizedBy;

        /** Payment entries staged via POST /payment-entries (mirrors the JSF pagos list). */
        private volatile @Nonnull List<PagoEntry> pagos = new ArrayList<>();

        public @Nonnull CartSessionContext getCartContext() {
            return cartContext;
        }

        public long getLastAccess() {
            return lastAccess;
        }

        /**
         * Clock seam: production code goes through {@link #touch()};
         * tests backdate this value to exercise the TTL sweep without waiting.
         */
        public void setLastAccess(long epochMillis) {
            this.lastAccess = epochMillis;
        }

        public void touch() {
            this.lastAccess = System.currentTimeMillis();
        }

        public @Nullable String getAuthorizedBy() {
            return authorizedBy;
        }

        public void setAuthorizedBy(@Nullable String authorizedBy) {
            this.authorizedBy = authorizedBy;
        }

        public @Nonnull List<PagoEntry> getPagos() {
            return pagos;
        }

        public void setPagos(@Nonnull List<PagoEntry> pagos) {
            this.pagos = new ArrayList<>(pagos);
        }
    }

    /**
     * Returns the entry for {@code username}, creating (or recreating after a
     * TTL eviction) an empty one when absent, and refreshes its last-access
     * timestamp.
     */
    public @Nonnull Entry getOrCreate(@Nonnull String username) {
        Entry entry = sessions.computeIfAbsent(username, key -> new Entry());
        entry.touch();
        return entry;
    }

    /**
     * Drops the entry for {@code username} if present.
     *
     * @return true when an entry was removed
     */
    public boolean remove(@Nonnull String username) {
        return sessions.remove(username) != null;
    }

    /**
     * @return number of currently tracked cashier carts.
     */
    public int activeCount() {
        return sessions.size();
    }

    /**
     * Quartz-free Quarkus scheduler tick: evict entries idle &gt; 4h.
     * Disabled under %test (%test.quarkus.scheduler.enabled=false), where
     * {@link #sweepIdleEntries(long)} is invoked directly instead.
     */
    @Scheduled(every = "30m")
    void sweepScheduled() {
        sweepIdleEntries(System.currentTimeMillis());
    }

    /**
     * Evicts every entry whose last access is older than {@link #TTL_MILLIS}.
     *
     * @param nowEpochMillis current time (injected so tests don't need to wait)
     * @return number of entries evicted
     */
    public int sweepIdleEntries(long nowEpochMillis) {
        int evicted = 0;
        for (Map.Entry<String, Entry> candidate : sessions.entrySet()) {
            long idleFor = nowEpochMillis - candidate.getValue().getLastAccess();
            if (idleFor > TTL_MILLIS && sessions.remove(candidate.getKey(), candidate.getValue())) {
                evicted++;
            }
        }
        return evicted;
    }
}
