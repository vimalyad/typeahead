package app.suggest.api;

import app.suggest.cache.CacheKeys;
import app.suggest.cache.RedisRouter;
import app.suggest.index.Index;
import app.suggest.index.IndexBuilder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CacheDebugController {

    private final IndexBuilder indexBuilder;
    private final RedisRouter router;

    public CacheDebugController(IndexBuilder indexBuilder, RedisRouter router) {
        this.indexBuilder = indexBuilder;
        this.router = router;
    }

    @GetMapping("/api/cache/debug")
    public Map<String, Object> debug(@RequestParam("prefix") String prefix) {
        String normalized = prefix.strip().toLowerCase(Locale.ROOT);
        Index index = indexBuilder.live();
        int generation = index != null ? index.generation() : 0;
        String key = CacheKeys.suggest(generation, normalized);
        Integer owner = router.ownerOf(normalized);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prefix", normalized);
        out.put("generation", generation);
        out.put("key", key);
        out.put("ownerNode", owner == null ? "none" : router.label(owner));
        out.put("status", router.exists(normalized, key) ? "HIT" : "MISS");
        return out;
    }
}
