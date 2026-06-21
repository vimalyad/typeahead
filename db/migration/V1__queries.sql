CREATE TABLE queries (
    query           TEXT PRIMARY KEY,
    all_time_count  BIGINT           NOT NULL DEFAULT 0,
    recent_score    DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_updated    TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- text_pattern_ops supports left-anchored prefix scans (WHERE query LIKE 'iph%')
-- independent of the database collation.
CREATE INDEX idx_queries_query ON queries (query text_pattern_ops);
