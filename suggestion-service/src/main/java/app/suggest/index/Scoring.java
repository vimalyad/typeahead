package app.suggest.index;

/** Blended popularity + recency score, baked into the trie at build time. */
public final class Scoring {

    private final double allTimeWeight;
    private final double recentWeight;

    public Scoring(double allTimeWeight, double recentWeight) {
        this.allTimeWeight = allTimeWeight;
        this.recentWeight = recentWeight;
    }

    public double score(long allTimeCount, double recentScore) {
        // log1p compresses the heavy popularity tail so a single runaway term cannot
        // dominate every prefix; recent_score lifts currently-trending queries.
        return allTimeWeight * Math.log1p(allTimeCount) + recentWeight * recentScore;
    }
}
