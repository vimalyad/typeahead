package app.suggest.api;

import app.shared.Suggestion;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrendingController {

    private final SuggestionService suggestionService;

    public TrendingController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping("/api/trending")
    public ResponseEntity<List<Suggestion>> trending() {
        var result = suggestionService.trending();
        return ResponseEntity.ok()
                .header("X-Cache", result.cacheHit() ? "HIT" : "MISS")
                .body(result.suggestions());
    }
}
