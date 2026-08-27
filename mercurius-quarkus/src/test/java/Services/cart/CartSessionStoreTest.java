package Services.cart;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T37-prep unit scenarios for {@link CartSessionStore}: one cart per username,
 * remove/activeCount bookkeeping and the 4h TTL sweep (invoked directly because
 * %test disables the Quarkus scheduler — %test.quarkus.scheduler.enabled=false).
 *
 * <p>Lives in {@code Services.cart} so it can drive the clock seam
 * ({@code Entry#setLastAccess}) without reflection.</p>
 */
@QuarkusTest
class CartSessionStoreTest {

    @Inject
    CartSessionStore store;

    private static String uniqueUser(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void getOrCreateReturnsSameEntryPerUsername() {
        String user = uniqueUser("t37same");
        CartSessionStore.Entry first = store.getOrCreate(user);
        CartSessionStore.Entry second = store.getOrCreate(user);

        assertThat(second).isSameAs(first);
        assertThat(second.getCartContext()).isSameAs(first.getCartContext());
        store.remove(user);
    }

    @Test
    void distinctUsernamesGetDistinctContexts() {
        String userA = uniqueUser("t37iso-a");
        String userB = uniqueUser("t37iso-b");

        CartSessionStore.Entry entryA = store.getOrCreate(userA);
        CartSessionStore.Entry entryB = store.getOrCreate(userB);

        assertThat(entryB).isNotSameAs(entryA);
        assertThat(entryB.getCartContext()).isNotSameAs(entryA.getCartContext());

        // Mutation on A never bleeds into B (user-isolation foundation).
        entryA.getCartContext().setCodigoBarra("ONLY-A");
        assertThat(entryB.getCartContext().getCodigoBarra()).isNull();

        store.remove(userA);
        store.remove(userB);
    }

    @Test
    void activeCountTracksAddedAndRemovedEntries() {
        String userA = uniqueUser("t37count-a");
        String userB = uniqueUser("t37count-b");
        int before = store.activeCount();

        store.getOrCreate(userA);
        store.getOrCreate(userB);
        assertThat(store.activeCount()).isEqualTo(before + 2);

        store.remove(userA);
        assertThat(store.activeCount()).isEqualTo(before + 1);

        store.remove(userB);
        assertThat(store.activeCount()).isEqualTo(before);
    }

    @Test
    void removeIsFalseForUnknownUsername() {
        assertThat(store.remove(uniqueUser("t37ghost"))).isFalse();
    }

    @Test
    void sweepEvictsOnlyEntriesIdleBeyondTtl() {
        String idle = uniqueUser("t37ttl-idle");
        String fresh = uniqueUser("t37ttl-fresh");
        store.getOrCreate(idle);
        store.getOrCreate(fresh);

        long now = System.currentTimeMillis();
        store.getOrCreate(idle).setLastAccess(now - CartSessionStore.TTL_MILLIS - 60_000L);

        int evicted = store.sweepIdleEntries(now);

        assertThat(evicted).isGreaterThanOrEqualTo(1);
        assertThat(store.activeCount()).isGreaterThanOrEqualTo(1);
        // The fresh entry survives; the idle one is gone.
        assertThat(store.remove(fresh)).isTrue();
        assertThat(store.remove(idle)).isFalse();
    }

    @Test
    void sweptUserStartsFreshCartOnNextGetOrCreate() {
        String user = uniqueUser("t37ttl-recreate");
        CartSessionStore.Entry original = store.getOrCreate(user);
        original.getCartContext().setCodigoBarra("STALE");

        long now = System.currentTimeMillis();
        original.setLastAccess(now - CartSessionStore.TTL_MILLIS - 60_000L);
        store.sweepIdleEntries(now);

        CartSessionStore.Entry recreated = store.getOrCreate(user);
        assertThat(recreated).isNotSameAs(original);
        assertThat(recreated.getCartContext().getCodigoBarra())
                .as("a TTL-evicted cashier must start with an empty cart")
                .isNullOrEmpty();
        store.remove(user);
    }

    @Test
    void touchRefreshesLastAccessAndPreventsEviction() {
        String user = uniqueUser("t37ttl-touch");
        CartSessionStore.Entry entry = store.getOrCreate(user);
        long now = System.currentTimeMillis();
        entry.setLastAccess(now - CartSessionStore.TTL_MILLIS - 60_000L);
        entry.touch();

        int evicted = store.sweepIdleEntries(System.currentTimeMillis());
        assertThat(evicted).isZero();
        assertThat(store.remove(user)).isTrue();
    }
}
