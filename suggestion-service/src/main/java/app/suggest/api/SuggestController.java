package app.suggest.api;

import app.shared.Suggestion;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SuggestController {

    private final SuggestionService suggestionService;

    public SuggestController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping("/api/suggest")
    public ResponseEntity<List<Suggestion>> suggest(
            @RequestParam(name = "q", required = false) String q) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok().header("X-Cache", "BYPASS").body(List.of());
        }
        var result = suggestionService.suggest(q.strip().toLowerCase(Locale.ROOT));
        return ResponseEntity.ok()
                .header("X-Cache", result.cacheHit() ? "HIT" : "MISS")
                .body(result.suggestions());
    }
}
