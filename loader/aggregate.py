#!/usr/bin/env python3
"""Aggregate AmazonQAC parquet shards into a (query, all_time_count) CSV.

popularity is the dataset's global occurrence count for a term, so it is constant
across every row of that exact term — we take it once per exact term (max), then
fold case/whitespace variants together (lower+trim) summing their popularities.
This matches the read-path normalization so trie/cache keys line up. Only the top-N
by count are kept, since the serving trie lives in memory.
"""
import os
import sys
import duckdb

PARQUET_GLOB = os.environ.get("PARQUET_GLOB", "data/train-*.parquet")
TOP_N = int(os.environ.get("TOP_N", "1000000"))
OUT = os.environ.get("OUT", "data/queries.csv")
MEM_LIMIT = os.environ.get("DUCKDB_MEMORY_LIMIT", "4GB")

con = duckdb.connect()
con.execute(f"PRAGMA memory_limit='{MEM_LIMIT}'")
con.execute("PRAGMA enable_progress_bar")

src = f"read_parquet('{PARQUET_GLOB}')"

total = con.execute(f"SELECT count(*) FROM {src}").fetchone()[0]
print(f"rows in source: {total:,}", file=sys.stderr)

con.execute(
    f"""
    COPY (
        SELECT q, sum(p) AS cnt
        FROM (
            SELECT lower(trim(final_search_term)) AS q, max(popularity) AS p
            FROM {src}
            WHERE final_search_term IS NOT NULL
            GROUP BY final_search_term
        )
        WHERE length(q) > 0
        GROUP BY q
        ORDER BY cnt DESC
        LIMIT {TOP_N}
    ) TO '{OUT}' (FORMAT csv, HEADER true)
    """
)

written = con.execute(
    f"SELECT count(*), min(cnt), max(cnt) FROM read_csv('{OUT}', header=true)"
).fetchone()
print(f"wrote {written[0]:,} rows to {OUT} (count range {written[1]}..{written[2]})", file=sys.stderr)
