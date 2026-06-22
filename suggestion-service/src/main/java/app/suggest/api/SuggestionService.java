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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cache-first read: look up the generation-versioned key on the owning Redis node; on a miss,
 * walk the in-memory trie, write the result back (including empty results — negative caching),
 * and return. The write path never touches Redis; it refreshes lazily on misses.
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
    private final Counter hits;
    private final Counter misses;

    public SuggestionService(IndexBuilder indexBuilder,
                             RedisRouter router,
                             ObjectMapper mapper,
                             MeterRegistry metrics,
                             @Value("${app.cache.base-ttl-seconds:60}") long ttlSeconds) {
        this.indexBuilder = indexBuilder;
        this.router = router;
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
        this.hits = Counter.builder("suggest.cache.hits").register(metrics);
        this.misses = Counter.builder("suggest.cache.misses").register(metrics);
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
        List<Suggestion> result = index.topKFor(normalizedPrefix);
        router.setEx(normalizedPrefix, storageKey, serialize(result), ttlSeconds);
        return new Result(result, false);
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
