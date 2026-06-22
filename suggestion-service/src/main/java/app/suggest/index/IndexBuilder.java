package app.suggest.index;

import app.shared.Suggestion;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Builds the read-optimized trie from Postgres and publishes it via an atomic reference.
 * Two passes: insert every query (score baked in), then a post-order DFS where each node
 * merges its children's top-K lists plus its own terminal into its own top-K. Carrying K per
 * child is sufficient: a node's true top-K is a subset of the union of its children's top-K.
 */
@Component
public class IndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);

    private static final String LOAD_SQL =
            "SELECT query, all_time_count, recent_score FROM queries "
            + "ORDER BY all_time_count DESC LIMIT ?";

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final int maxQueries;
    private final int maxSuggestions;
    private final Scoring scoring;

    private final AtomicReference<Index> live = new AtomicReference<>();
    private final AtomicInteger generation = new AtomicInteger(0);

    public IndexBuilder(JdbcTemplate jdbc,
                        MeterRegistry metrics,
                        @Value("${app.index.max-queries:300000}") int maxQueries,
                        @Value("${app.index.max-suggestions:10}") int maxSuggestions,
                        @Value("${app.index.weights.all-time:1.0}") double allTimeWeight,
                        @Value("${app.index.weights.recent:1.0}") double recentWeight) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.maxQueries = maxQueries;
        this.maxSuggestions = maxSuggestions;
        this.scoring = new Scoring(allTimeWeight, recentWeight);
    }

    public Index live() {
        return live.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void buildOnStartup() {
        rebuild();
    }

    /**
     * Periodic rebuild that closes the freshness loop: batch-written counts in Postgres become
     * visible only when the trie is rebuilt and atomically swapped in. initialDelay == the
     * interval so this never races the startup build.
     */
    @Scheduled(fixedDelayString = "${app.builder.rebuild-interval-ms:45000}",
               initialDelayString = "${app.builder.rebuild-interval-ms:45000}")
    public void scheduledRebuild() {
        rebuild();
    }

    public synchronized void rebuild() {
        long start = System.nanoTime();
        TrieNode root = new TrieNode();
        int[] count = {0};
        jdbc.setFetchSize(10_000);
        jdbc.query(LOAD_SQL, rs -> {
            String query = rs.getString(1);
            double score = scoring.score(rs.getLong(2), rs.getDouble(3));
            insert(root, query, score);
            count[0]++;
        }, maxQueries);

        long nodes = computeTopK(root);
        int gen = generation.incrementAndGet();
        live.set(new Index(root, gen));

        long ms = (System.nanoTime() - start) / 1_000_000;
        metrics.gauge("suggest.index.generation", generation);
        log.info("built index gen={} from {} queries ({} nodes) in {} ms", gen, count[0], nodes, ms);
    }

    private static void insert(TrieNode root, String query, double score) {
        TrieNode node = root;
        for (int i = 0; i < query.length(); i++) {
            node = node.children.computeIfAbsent(query.charAt(i), k -> new TrieNode());
        }
        node.terminal = new Suggestion(query, score);
    }

    /** Post-order: fill each node's topK from its children plus its terminal. Returns node count. */
    private long computeTopK(TrieNode node) {
        PriorityQueue<Suggestion> heap =
                new PriorityQueue<>(Comparator.comparingDouble(Suggestion::score));
        long nodes = 1;
        if (node.terminal != null) {
            offer(heap, node.terminal);
        }
        for (TrieNode child : node.children.values()) {
            nodes += computeTopK(child);
            for (Suggestion s : child.topK) {
                offer(heap, s);
            }
        }
        List<Suggestion> ranked = new ArrayList<>(heap);
        ranked.sort(Comparator.comparingDouble(Suggestion::score).reversed());
        node.topK = List.copyOf(ranked);
        return nodes;
    }

    private void offer(PriorityQueue<Suggestion> heap, Suggestion s) {
        if (heap.size() < maxSuggestions) {
            heap.offer(s);
        } else if (s.score() > heap.peek().score()) {
            heap.poll();
            heap.offer(s);
        }
    }
}
