package app.suggest.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HashRingTest {

    private static final int VNODES = 150;
    private static final int KEYS = 100_000;

    private static String key(int i) {
        return "suggest:v1:prefix-" + i;
    }

    @Test
    void distributesKeysRoughlyEvenlyAcrossNodes() {
        HashRing ring = new HashRing(List.of(0, 1, 2), VNODES);
        int[] counts = new int[3];
        for (int i = 0; i < KEYS; i++) {
            counts[ring.route(key(i))]++;
        }
        // Each of 3 nodes should own ~33% of keys; allow a generous tolerance.
        for (int c : counts) {
            double share = (double) c / KEYS;
            assertThat(share).isBetween(0.28, 0.39);
        }
    }

    @Test
    void removingANodeRemapsOnlyAboutOneThirdOfKeys() {
        HashRing before = new HashRing(List.of(0, 1, 2), VNODES);
        HashRing after = new HashRing(List.of(0, 1), VNODES); // node 2 removed

        Map<String, Integer> ownerBefore = new HashMap<>();
        for (int i = 0; i < KEYS; i++) {
            ownerBefore.put(key(i), before.route(key(i)));
        }

        int moved = 0;
        int survivorKeysStayed = 0;
        int survivorKeysTotal = 0;
        for (int i = 0; i < KEYS; i++) {
            String k = key(i);
            int wasOn = ownerBefore.get(k);
            int nowOn = after.route(k);
            if (wasOn != nowOn) {
                moved++;
            }
            if (wasOn != 2) {
                survivorKeysTotal++;
                if (nowOn == wasOn) {
                    survivorKeysStayed++;
                }
            }
        }

        // Only the departed node's keys (~1/3) should move; the rest stay put.
        double movedShare = (double) moved / KEYS;
        assertThat(movedShare).isBetween(0.28, 0.39);

        // Keys not on the removed node must not be reshuffled (the consistent-hashing property).
        assertThat(survivorKeysStayed).isEqualTo(survivorKeysTotal);
    }
}
