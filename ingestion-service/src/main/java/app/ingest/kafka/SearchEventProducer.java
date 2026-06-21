package app.ingest.kafka;

import app.shared.SearchEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class SearchEventProducer {

    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final String topic;

    public SearchEventProducer(KafkaTemplate<String, String> template,
                               ObjectMapper mapper,
                               @Value("${app.kafka.topic:search-events}") String topic) {
        this.template = template;
        this.mapper = mapper;
        this.topic = topic;
    }

    public void publish(String query) {
        var event = new SearchEvent(query, Instant.now().toEpochMilli());
        try {
            // Key by query so a future multi-partition topic keeps a term on one partition.
            // Wait for the broker ack so the event is durably in the log before we return —
            // otherwise a crash could drop it from the producer buffer and the WAL guarantee
            // (uncommitted events replay) would not actually cover the submission edge.
            template.send(topic, query, mapper.writeValueAsString(event))
                    .get(10, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize search event", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("failed to publish search event", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing search event", e);
        }
    }
}
