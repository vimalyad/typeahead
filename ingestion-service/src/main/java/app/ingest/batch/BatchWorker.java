package app.ingest.batch;

import app.shared.SearchEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single consumer thread: poll -> aggregate into a per-query delta map -> flush on size OR
 * timer -> one batched upsert -> commit offsets. Commit happens only after the Postgres write
 * succeeds, giving at-least-once: a crash between write and commit re-delivers, never drops.
 * Keeping consume/aggregate/flush on one thread makes the flushed set exactly the events polled
 * since the last commit, so offset alignment is trivial.
 */
@Component
public class BatchWorker {

    private static final Logger log = LoggerFactory.getLogger(BatchWorker.class);

    private final UpsertDao upsertDao;
    private final ObjectMapper mapper;
    private final String bootstrapServers;
    private final String groupId;
    private final String topic;
    private final int flushSize;
    private final long flushIntervalMs;
    private final double perSecondFactor;

    private final Counter eventsReceived;
    private final Counter dbUpserts;
    private final Counter flushes;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public BatchWorker(UpsertDao upsertDao,
                       ObjectMapper mapper,
                       MeterRegistry metrics,
                       @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                       @Value("${spring.kafka.consumer.group-id:batch-writer}") String groupId,
                       @Value("${app.kafka.topic:search-events}") String topic,
                       @Value("${app.batch.flush-size:1000}") int flushSize,
                       @Value("${app.batch.flush-interval-ms:5000}") long flushIntervalMs,
                       @Value("${app.decay.half-life-seconds:21600}") double halfLifeSeconds) {
        this.upsertDao = upsertDao;
        this.mapper = mapper;
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.topic = topic;
        this.flushSize = flushSize;
        this.flushIntervalMs = flushIntervalMs;
        this.perSecondFactor = Math.pow(0.5, 1.0 / halfLifeSeconds);
        this.eventsReceived = Counter.builder("ingest.events.received").register(metrics);
        this.dbUpserts = Counter.builder("ingest.db.upserts").register(metrics);
        this.flushes = Counter.builder("ingest.flushes").register(metrics);
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::run, "batch-worker");
        thread.start();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.wakeup();
        }
        if (thread != null) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private void run() {
        consumer = new KafkaConsumer<>(consumerProperties());
        consumer.subscribe(List.of(topic));
        Map<String, Long> buffer = new HashMap<>();
        long lastFlush = System.nanoTime();
        try {
            while (running) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var rec : records) {
                    try {
                        SearchEvent ev = mapper.readValue(rec.value(), SearchEvent.class);
                        buffer.merge(ev.query(), 1L, Long::sum);
                        eventsReceived.increment();
                    } catch (Exception parseError) {
                        log.warn("skipping malformed event: {}", rec.value(), parseError);
                    }
                }
                boolean bySize = buffer.size() >= flushSize;
                boolean byTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastFlush) >= flushIntervalMs;
                if (!buffer.isEmpty() && (bySize || byTime)) {
                    Map<String, Long> snapshot = buffer;
                    buffer = new HashMap<>();
                    upsertDao.batchUpsert(snapshot, perSecondFactor);
                    consumer.commitSync();
                    dbUpserts.increment(snapshot.size());
                    flushes.increment();
                    lastFlush = System.nanoTime();
                    log.info("flushed {} distinct queries in one transaction", snapshot.size());
                }
            }
        } catch (WakeupException expectedOnShutdown) {
            // fall through to close
        } finally {
            consumer.close();
        }
    }

    private Properties consumerProperties() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return p;
    }
}
