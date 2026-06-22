package app.suggest.cache;

/**
 * Cache keys embed the build generation: {@code suggest:v<gen>:<prefix>}. On rebuild the
 * generation bumps, so every old key is instantly orphaned (no deletes — they age out via TTL)
 * and the first read under the new generation re-populates from the fresh trie.
 */
public final class CacheKeys {

    public static String suggest(int generation, String prefix) {
        return "suggest:v" + generation + ":" + prefix;
    }

    private CacheKeys() {
    }
}
