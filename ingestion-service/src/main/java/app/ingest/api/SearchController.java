package app.ingest.api;

import app.ingest.kafka.SearchEventProducer;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchEventProducer producer;

    public SearchController(SearchEventProducer producer) {
        this.producer = producer;
    }

    @PostMapping("/api/search")
    public Map<String, String> search(@Valid @RequestBody SearchRequest request) {
        // Normalize to the same form the read path uses so counts land on the right key.
        producer.publish(request.query().strip().toLowerCase(Locale.ROOT));
        return Map.of("message", "Searched");
    }
}
