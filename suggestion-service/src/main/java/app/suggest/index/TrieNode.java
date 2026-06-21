package app.suggest.index;

import app.shared.Suggestion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One trie node. {@code children} is a map (queries use the full character range, not just
 * a-z), {@code terminal} is set when a query ends here, and {@code topK} is the precomputed
 * best completions of this subtree, filled by the post-order pass at build time.
 */
final class TrieNode {
    final Map<Character, TrieNode> children = new HashMap<>(2);
    Suggestion terminal;
    List<Suggestion> topK = List.of();
}
