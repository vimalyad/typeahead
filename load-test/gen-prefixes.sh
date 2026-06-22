#!/usr/bin/env bash
# Emits load-test/prefixes.json: 2-3 character prefixes weighted by query popularity,
# so a few hot prefixes dominate — the Zipfian skew the load test needs to exercise the
# cache hot path (ARCHITECTURE §4.3, IMPLEMENTATION §5.3). Run after the dataset is loaded.
set -euo pipefail

DB_CONTAINER="${DB_CONTAINER:-typeahead-postgres-1}"
OUT="$(dirname "$0")/prefixes.json"

docker exec -i "$DB_CONTAINER" psql -U app -d typeahead -tA > "$OUT" <<'SQL'
WITH px AS (
  SELECT lower(left(query, len)) AS p, all_time_count AS w
  FROM queries, (VALUES (2), (3)) AS l(len)
  WHERE length(query) >= len AND query ~ '^[a-z0-9]'
),
agg AS (
  SELECT p, sum(w) AS w FROM px GROUP BY p ORDER BY sum(w) DESC LIMIT 300
)
SELECT json_agg(json_build_object('p', p, 'w', w) ORDER BY w DESC) FROM agg;
SQL

echo "wrote $OUT ($(wc -c < "$OUT") bytes)"
