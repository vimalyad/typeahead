package app.suggest.api;

import app.shared.Suggestion;
import app.suggest.index.Index;
import app.suggest.index.IndexBuilder;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SuggestController {

    private final IndexBuilder indexBuilder;

    public SuggestController(IndexBuilder indexBuilder) {
        this.indexBuilder = indexBuilder;
    }

    @GetMapping("/api/suggest")
    public List<Suggestion> suggest(@RequestParam(name = "q", required = false) String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        Index index = indexBuilder.live();
        if (index == null) {
            return List.of();
        }
        return index.topKFor(q.strip().toLowerCase(Locale.ROOT));
    }
}
