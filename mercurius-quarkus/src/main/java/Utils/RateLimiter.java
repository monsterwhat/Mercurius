package Utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * In-memory sliding window rate limiter per API client.
 * Uses Caffeine cache for efficient, automatic eviction.
 * Rate limits are configured per-client in the ApiClients entity.
 */
@ApplicationScoped
public class RateLimiter {

    private static final Logger LOG = Logger.getLogger(RateLimiter.class);

    // Per-minute window: tracks request timestamps
    private Cache<String, java.util.concurrent.atomic.AtomicInteger> minuteCounters;

    // Per-hour window: tracks request timestamps
    private Cache<String, java.util.concurrent.atomic.AtomicInteger> hourCounters;

    @PostConstruct
    public void init() {
        // Minute window: entries expire after 60 seconds
        minuteCounters = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();

        // Hour window: entries expire after 3600 seconds
        hourCounters = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(3600, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Check if a request from the given client is within rate limits.
     *
     * @param clientId        the API client identifier
     * @param limitPerMin     per-minute limit (from ApiClients entity)
     * @param limitPerHour    per-hour limit (from ApiClients entity)
     * @return null if allowed, or the number of seconds until the minute window resets if denied
     */
    public java.lang.Long checkRateLimit(String clientId, int limitPerMin, int limitPerHour) {
        // Check per-minute limit
        java.util.concurrent.atomic.AtomicInteger minuteCount = minuteCounters.get(clientId,
                k -> new java.util.concurrent.atomic.AtomicInteger(0));
        int currentMinute = minuteCount.incrementAndGet();

        if (currentMinute > limitPerMin) {
            LOG.debug("Rate limit exceeded (per-minute) for client: " + clientId);
            minuteCount.decrementAndGet(); // Don't count the rejected request
            return 60L; // Retry after 60 seconds
        }

        // Check per-hour limit
        java.util.concurrent.atomic.AtomicInteger hourCount = hourCounters.get(clientId,
                k -> new java.util.concurrent.atomic.AtomicInteger(0));
        int currentHour = hourCount.incrementAndGet();

        if (currentHour > limitPerHour) {
            LOG.debug("Rate limit exceeded (per-hour) for client: " + clientId);
            hourCount.decrementAndGet(); // Don't count the rejected request
            return 3600L; // Retry after 3600 seconds
        }

        return null; // Within limits
    }
}
