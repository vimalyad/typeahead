package app.shared;

/** A submitted search, produced to the event log and consumed by the batch worker. */
public record SearchEvent(String query, long ts) {}
