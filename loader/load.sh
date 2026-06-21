#!/usr/bin/env bash
# Load AmazonQAC into Postgres: migrate schema (Flyway), aggregate parquet (DuckDB),
# stream the resulting CSV into the queries table.
#
# Prereqs: docker compose stack up (postgres reachable), parquet shard(s) in ./data,
# python with the duckdb package installed (pip install duckdb).
#
# Tunables: TOP_N (default 1,000,000), PARQUET_GLOB (default data/train-*.parquet).
set -euo pipefail
cd "$(dirname "$0")/.."

PG_USER=${PG_USER:-app}
PG_DB=${PG_DB:-typeahead}
NETWORK=${NETWORK:-typeahead_default}
CSV=${OUT:-data/queries.csv}

echo "==> Applying schema migrations (Flyway)"
docker run --rm --network "$NETWORK" \
  -v "$(pwd)/db/migration:/flyway/sql" \
  flyway/flyway:10 \
  -url=jdbc:postgresql://postgres:5432/"$PG_DB" -user="$PG_USER" -password=app \
  -connectRetries=10 migrate

echo "==> Aggregating parquet -> $CSV (DuckDB)"
OUT="$CSV" python loader/aggregate.py

echo "==> Loading $CSV into queries"
docker compose exec -T postgres \
  psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 \
  -c "TRUNCATE queries" \
  -c "\copy queries(query, all_time_count) FROM STDIN WITH (FORMAT csv, HEADER true)" \
  < "$CSV"

echo "==> Row count"
docker compose exec -T postgres \
  psql -U "$PG_USER" -d "$PG_DB" -tAc "SELECT count(*) FROM queries"
