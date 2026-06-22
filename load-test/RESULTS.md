# Load test results

Scenario: `suggest.js` — 200 VUs, ramp 30s → hold 2m → ramp-down 30s. Prefixes sampled by
inverse-CDF from `prefixes.json` (300 prefixes weighted by real query popularity, so traffic
is Zipfian-skewed). ~10% of iterations also `POST /api/search`. Dataset: 1M queries; trie
holds the top 300k; rebuild every 15s (demo cadence).

k6 ran inside the compose network (`grafana/k6`). Two targets:
- **edge** — `http://web:80` (nginx single origin; routes both `/suggest` and `/search`).
- **service** — `http://suggestion-service:8081` (read path only, isolates serving latency).

## Latency — `/api/suggest` (client-side, k6)

| Path | hit rate | throughput | p50 | p90 | p95 | p99 |
| ---- | -------- | ---------- | --- | --- | --- | --- |
| **via nginx edge** | 99.77% | 10,159 req/s | 9.9 ms | 20.4 ms | 25.7 ms | 53.9 ms |
| **direct to service** | 99.85% | 16,981 req/s | 7.9 ms | 16.1 ms | 20.1 ms | 37.8 ms |

Split by cache outcome (edge run): **hit** med 9.9 ms / p95 25.7 ms; **miss** med 13.7 ms /
p95 32.9 ms. Misses stay cheap because they hit the in-memory trie, never the DB.

The read path meets the latency targets (p95 < 25 ms, p99 < 60 ms) at the service. Through the edge
p95 lands ~25–37 ms run-to-run under 200 VUs — but that spread is **host contention + the 15 s
rebuild miss-bursts, not the nginx hop**. Isolating the hop with a low-concurrency, hits-only
probe (10 VUs, same warm prefixes, direct vs edge) shows the proxy adds only **~0.7 ms at p95**
(direct p95 1.37 ms → edge p95 2.08 ms). So at 200 VUs the bottleneck is the backend under
concurrency, not the edge.

> Note: an attempt to shave the edge with `upstream` keepalive pools (HTTP/1.1, `Connection ""`)
> was tested and **reverted** — under 200 VUs it consistently made p95 *worse* (~37 ms vs ~27 ms
> for direct `proxy_pass` in a same-machine A/B), as a small idle pool churns against high
> concurrency. With the hop already sub-millisecond in isolation, there was nothing to gain.

## Latency — server side (Micrometer `suggest_request_seconds`, split by `cache`)

Pure serving time, no client/network: **hit avg ≈ 3.5 ms**, **miss avg ≈ 8.5 ms**
(`/actuator/prometheus` exposes per-outcome histogram buckets for `histogram_quantile`).
So the bulk of the client-side number is transport, not work.

## Cache hit rate

**99.8%** in steady state — the Zipfian ceiling. A few hot prefixes stay warm; single-flight +
TTL jitter keep the miss trickle from dogpiling. Over the edge run: 1,658,138 hits of
1,661,802 reads. Server counters: 4.43M hits vs 7,789 misses vs 6,703 trie loads — loads < misses
confirms single-flight collapsing concurrent cold reads.

## Write reduction (`/api/search` → Postgres)

Counters on ingestion-service across the edge run:

| Metric | Δ during run |
| ------ | ------------ |
| `ingest.events.received` | 166,699 |
| `ingest.db.upserts` (rows) | 10,746 |
| `ingest.flushes` (DB transactions) | 37 |

**166,699 search events collapsed into 37 batched DB transactions** — ~4,500 events per write.
Folding duplicate queries within each batch gives 10,746 row upserts (**≈ 15.5 : 1** event-to-row).
DB write rate is governed by the flush size/interval, not search QPS — the point of the batch worker.

## Index rebuild

~1.5 s to build the 300k-query trie (≈2.6M nodes); swapped atomically every 15 s in the demo.
Generation bumps orphan old cache keys, which age out via TTL — visible as a brief miss blip per
rebuild that single-flight absorbs.

## Knobs to re-measure against

`flush-size` / `flush-interval-ms` (write batching vs staleness), `base-ttl-seconds` /
`jitter-seconds` (miss smoothing), `rebuild-interval-ms` (freshness vs build cost),
`ring.vnodes` (key distribution).

## Reproduce

```bash
docker compose up -d                       # full stack
./load-test/gen-prefixes.sh                # regenerate prefixes.json from Postgres
docker run --rm --network typeahead_default -v "$PWD/load-test:/scripts" \
  -e BASE_URL=http://web:80 grafana/k6 run /scripts/suggest.js
```
