package app.suggest.index;

import app.shared.Suggestion;
import java.util.List;

/**
 * An immutable, read-only serving index: the trie root plus the build generation. The whole
 * object is swapped atomically on rebuild, so a reader sees one consistent (root, generation)
 * pair and never a half-rebuilt trie.
 */
public final class Index {

    private final TrieNode root;
    private final int generation;

    Index(TrieNode root, int generation) {
        this.root = root;
        this.generation = generation;
    }

    public int generation() {
        return generation;
    }

    /** Top suggestions for {@code prefix} (already normalized), or empty if no node matches. */
    public List<Suggestion> topKFor(String prefix) {
        TrieNode node = root;
        for (int i = 0; i < prefix.length(); i++) {
            node = node.children.get(prefix.charAt(i));
            if (node == null) {
                return List.of();
            }
        }
        return node.topK;
    }
}
