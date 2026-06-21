package app.shared;

/** A ranked suggestion: the query text and its blended score. */
public record Suggestion(String query, double score) {}
