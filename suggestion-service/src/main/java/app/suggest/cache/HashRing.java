package app.suggest.cache;

import com.google.common.hash.Hashing;
import java.util.Collection;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Consistent-hash ring over cache node ids. Each node is placed at ~{@code vnodes} points on a
 * 64-bit ring; a key is owned by the first node clockwise of its hash (wrapping at the end).
 * This is sharding the read service owns in-process — not Redis Cluster. Adding/removing a node
 * only remaps the keys in the arcs it covers (~1/N), not the whole keyspace.
 */
final class HashRing {

    private final NavigableMap<Long, Integer> ring = new TreeMap<>();

    HashRing(Collection<Integer> nodeIds, int vnodes) {
        for (int nodeId : nodeIds) {
            for (int v = 0; v < vnodes; v++) {
                ring.put(hash(nodeId + "#" + v), nodeId);
            }
        }
    }

    /** Owning node id for the key, or {@code null} if the ring is empty (all nodes down). */
    Integer route(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        var entry = ring.ceilingEntry(hash(key));
        return (entry != null ? entry : ring.firstEntry()).getValue();
    }

    boolean isEmpty() {
        return ring.isEmpty();
    }

    static long hash(String s) {
        return Hashing.murmur3_128().hashUnencodedChars(s).asLong();
    }
}
