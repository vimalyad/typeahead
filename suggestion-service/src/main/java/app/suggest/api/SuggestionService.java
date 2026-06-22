package app.suggest.api;

import app.shared.Suggestion;
import app.suggest.cache.CacheKeys;
import app.suggest.cache.RedisRouter;
import app.suggest.index.Index;
import app.suggest.index.IndexBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cache-first read: look up the generation-versioned key on the owning Redis node; on a miss,
 * walk the in-memory trie, write the result back (including empty results — negative caching),
 * and return. The write path never touches Redis; it refreshes lazily on misses.
 *
 * <p>Two stampede defenses guard the miss path (IMPLEMENTATION §3.5):
 * <ul>
 *   <li><b>Single-flight</b> — concurrent misses on the same key collapse onto one trie load;
 *       the rest block on its future. Without it, a cold-but-hot prefix would launch one trie
 *       walk per in-flight request.
 *   <li><b>TTL jitter</b> — each populate gets a randomized TTL so a burst of keys written
 *       together don't all expire on the same tick and re-stampede.
 * </ul>
 */
@Service
public class SuggestionService {

    private static final TypeReference<List<Suggestion>> LIST_TYPE = new TypeReference<>() {
    };

    public record Result(List<Suggestion> suggestions, boolean cacheHit) {
    }

    private final IndexBuilder indexBuilder;
    private final RedisRouter router;
    private final ObjectMapper mapper;
    private final long ttlSeconds;
    private final long jitterSeconds;
    private final Counter hits;
    private final Counter misses;
    private final Counter loads;

    // Keyed by the generation-versioned storage key, so a rebuild (new generation) never lets a
    // stale in-flight load satisfy a request for the new generation — the keys simply differ.
    private final ConcurrentHashMap<String, CompletableFuture<List<Suggestion>>> inflight =
            new ConcurrentHashMap<>();

    public SuggestionService(IndexBuilder indexBuilder,
                             RedisRouter router,
                             ObjectMapper mapper,
                             MeterRegistry metrics,
                             @Value("${app.cache.base-ttl-seconds:60}") long ttlSeconds,
                             @Value("${app.cache.jitter-seconds:15}") long jitterSeconds) {
        this.indexBuilder = indexBuilder;
        this.router = router;
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
        this.jitterSeconds = jitterSeconds;
        this.hits = Counter.builder("suggest.cache.hits").register(metrics);
        this.misses = Counter.builder("suggest.cache.misses").register(metrics);
        // Actual trie loads. With single-flight, loads << misses under a concurrent cold burst.
        this.loads = Counter.builder("suggest.trie.loads").register(metrics);
    }

    public Result suggest(String normalizedPrefix) {
        Index index = indexBuilder.live();
        if (index == null) {
            return new Result(List.of(), false);
        }
        String storageKey = CacheKeys.suggest(index.generation(), normalizedPrefix);

        String cached = router.get(normalizedPrefix, storageKey);
        if (cached != null) {
            hits.increment();
            return new Result(deserialize(cached), true);
        }

        misses.increment();
        return new Result(loadSingleFlight(storageKey, normalizedPrefix, index), false);
    }

    /**
     * Trending = the global top-K, which lives at the trie root (the empty prefix). It goes through
     * the same cache-first path as any prefix, so the hottest key of all stays warm and single-flighted.
     */
    public Result trending() {
        return suggest("");
    }

    private List<Suggestion> loadSingleFlight(String storageKey, String prefix, Index index) {
        CompletableFuture<List<Suggestion>> future = inflight.computeIfAbsent(storageKey,
                k -> CompletableFuture.supplyAsync(() -> {
                    loads.increment();
                    List<Suggestion> result = index.topKFor(prefix);
                    router.setEx(prefix, storageKey, serialize(result), ttlWithJitter());
                    return result;
                }));
        try {
            return future.join();
        } finally {
            // Two-arg remove only clears the entry if it still maps to this future, so a fresh
            // load started after this one completed is never accidentally evicted.
            inflight.remove(storageKey, future);
        }
    }

    private long ttlWithJitter() {
        return ttlSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    }

    private String serialize(List<Suggestion> suggestions) {
        try {
            return mapper.writeValueAsString(suggestions);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize suggestions", e);
        }
    }

    private List<Suggestion> deserialize(String json) {
        try {
            return mapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            // A corrupt/old-format cache entry should not break the read; treat as a miss-equivalent.
            return List.of();
        }
    }
}
