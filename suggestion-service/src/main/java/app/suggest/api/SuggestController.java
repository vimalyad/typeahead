package app.suggest.api;

import app.shared.Suggestion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SuggestController {

    private final SuggestionService suggestionService;
    private final MeterRegistry metrics;

    public SuggestController(SuggestionService suggestionService, MeterRegistry metrics) {
        this.suggestionService = suggestionService;
        this.metrics = metrics;
    }

    @GetMapping("/api/suggest")
    public ResponseEntity<List<Suggestion>> suggest(
            @RequestParam(name = "q", required = false) String q) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok().header("X-Cache", "BYPASS").body(List.of());
        }
        long start = System.nanoTime();
        var result = suggestionService.suggest(q.strip().toLowerCase(Locale.ROOT));
        // Split serving latency by cache outcome — the headline metric of the read path
        // (ARCHITECTURE §10). Percentiles are enabled for this timer in application.yml.
        Timer.builder("suggest.request")
                .tag("cache", result.cacheHit() ? "hit" : "miss")
                .register(metrics)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return ResponseEntity.ok()
                .header("X-Cache", result.cacheHit() ? "HIT" : "MISS")
                .body(result.suggestions());
    }
}
