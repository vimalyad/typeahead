package app.suggest.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Routes cache entries to one of N standalone Redis nodes via a consistent-hash ring the service
 * owns. Routing is by the stable prefix (the shard identity), not the generation-versioned
 * storage key — so a node owns a fixed slice of prefixes across rebuilds, and only node
 * membership changes remap keys. All Redis access is fail-open: a dead node yields a cache miss
 * (served from the trie), never an error. A periodic liveness check rebuilds the ring from the
 * reachable nodes, so losing a node remaps only its share of prefixes onto the survivors.
 */
@Component
public class RedisRouter {

    private static final Logger log = LoggerFactory.getLogger(RedisRouter.class);

    private record Node(int id, String label, RedisClient client,
                        StatefulRedisConnection<String, String> connection,
                        RedisCommands<String, String> commands) {
    }

    private final List<Node> nodes = new ArrayList<>();
    private final int vnodes;
    private volatile Set<Integer> liveIds = new LinkedHashSet<>();
    private volatile HashRing ring;

    public RedisRouter(@Value("${app.redis.nodes:redis-1:6379,redis-2:6379,redis-3:6379}") String nodesCsv,
                       @Value("${app.ring.vnodes:150}") int vnodes,
                       @Value("${app.redis.command-timeout-ms:2000}") long commandTimeoutMs) {
        this.vnodes = vnodes;
        int id = 0;
        for (String hostPort : nodesCsv.split(",")) {
            String trimmed = hostPort.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] hp = trimmed.split(":");
            RedisURI uri = RedisURI.builder()
                    .withHost(hp[0])
                    .withPort(Integer.parseInt(hp[1]))
                    .withTimeout(Duration.ofMillis(commandTimeoutMs))
                    .build();
            RedisClient client = RedisClient.create(uri);
            StatefulRedisConnection<String, String> conn = client.connect();
            nodes.add(new Node(id, trimmed, client, conn, conn.sync()));
            id++;
        }
        refreshLiveness();
    }

    public String label(int nodeId) {
        return nodes.get(nodeId).label();
    }

    /** Owning node id for a prefix under the current (live) ring, or null if none reachable. */
    public Integer ownerOf(String routingKey) {
        return ring.route(routingKey);
    }

    public String get(String routingKey, String storageKey) {
        Integer id = ring.route(routingKey);
        if (id == null) {
            return null;
        }
        try {
            return nodes.get(id).commands().get(storageKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void setEx(String routingKey, String storageKey, String value, long ttlSeconds) {
        Integer id = ring.route(routingKey);
        if (id == null) {
            return;
        }
        try {
            nodes.get(id).commands().setex(storageKey, ttlSeconds, value);
        } catch (RuntimeException ignored) {
            // fail-open: a write that doesn't land just means a future miss
        }
    }

    public boolean exists(String routingKey, String storageKey) {
        Integer id = ring.route(routingKey);
        if (id == null) {
            return false;
        }
        try {
            return nodes.get(id).commands().exists(storageKey) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${app.redis.liveness-interval-ms:5000}")
    public void refreshLiveness() {
        Set<Integer> live = new TreeSet<>();
        for (Node node : nodes) {
            try {
                if ("PONG".equalsIgnoreCase(node.commands().ping())) {
                    live.add(node.id());
                }
            } catch (RuntimeException down) {
                // unreachable -> excluded from the ring this cycle
            }
        }
        if (!live.equals(liveIds)) {
            liveIds = live;
            ring = new HashRing(live, vnodes);
            log.info("cache ring membership changed: live nodes {}", live);
        } else if (ring == null) {
            ring = new HashRing(live, vnodes);
        }
    }

    @PreDestroy
    void shutdown() {
        for (Node node : nodes) {
            try {
                node.connection().close();
                node.client().shutdown();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }
}
