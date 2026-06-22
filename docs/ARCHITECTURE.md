# Search Typeahead — System Architecture

> Low-latency prefix suggestions, search submission with query-count updates, an in-memory serving index rebuilt offline, a distributed cache with consistent hashing, recency-aware trending, batched writes, and load-test-ready stampede defenses.

**Companion docs:** [`STACK.md`](./STACK.md) — technology choices (Java 21) and why each.

---

## 1. Goal and guiding principle

Serve up to 10 prefix-matching suggestions ranked by popularity (and recency) with low latency, while recording every submitted search so rankings stay current.

One principle drives everything: **decouple the read path from the write path.** Reads (suggestions) are high-volume and latency-critical. Writes (search submissions) are throughput-oriented and processed lazily. A third actor, the **index-builder**, periodically converts write-optimized data into a read-optimized form and bridges the two.

---

## 2. High-level architecture

```
                          ┌──────────────────────────┐
                          │   Browser (React/Vite)    │
                          │   debounced typeahead UI  │
                          └─────────────┬─────────────┘
                                        │  /api/*
                          ┌─────────────▼─────────────┐
                          │            nginx           │
                          │  static build + reverse    │
                          │  proxy by URL path         │
                          └──────┬──────────────┬──────┘
                  /api/suggest   │              │   /api/search
                                 │              │
           ┌─────────────────────▼───┐     ┌────▼──────────────────────┐
           │  Suggestion service     │     │   Ingestion service        │
           │  (READ path)            │     │   (WRITE path)             │
           │  - GET /suggest         │     │   - POST /search           │
           │  - GET /cache/debug     │     │   - batch worker           │
           │  - hash ring            │     └────┬───────────────────────┘
           │  - in-memory TRIE  ◄────┼──┐       │ emit event
           └───┬─────────────────────┘  │       ▼
               │ consistent hash         │  ┌─────────────┐
       ┌───────┼────────┐                │  │  Kafka      │ durable event log
       ▼       ▼        ▼                │  │  (KRaft)    │ topic: search-events
   ┌──────┐┌──────┐┌──────┐              │  └──────┬──────┘
   │RedisN1││RedisN2││RedisN3│           │         │ consume + aggregate + flush
   └──────┘└──────┘└──────┘              │  ┌─────────────┐
       │ miss → in-memory trie           │  │  Postgres   │ source of truth
       └─────────────────────────────────┘  │ counts +    │ (write target)
                                   ▲         │ recency     │
                        rebuild    │         └──────┬──────┘
                   ┌───────────────┴──┐            │ full read at build time
                   │  Index-builder    │◄───────────┘
                   │  Postgres → trie  │
                   │  every 30–60s     │
                   └───────────────────┘
```

Key change from a naive design: **a cache miss falls back to an in-memory trie, not to Postgres.** Postgres is read only at build time. The serve path never touches the database per request.

---

## 3. Components

| Component | Role |
| --------- | ---- |
| **Frontend** | React + Vite + TypeScript + Tailwind. Debounced input, suggestion dropdown, trending section, keyboard nav, loading/error states. |
| **nginx** | Serves the static build; reverse-proxies `/api/suggest` and `/api/search` by path. Single origin (no CORS). Optional L7 load balancing across service instances. |
| **Suggestion service (read)** | `GET /suggest`, `GET /cache/debug`. Holds the consistent-hash ring **and the in-memory trie**. Cache-first, trie on miss. Never writes. |
| **Ingestion service (write)** | `POST /search` — validate, enqueue, return immediately. Hosts the batch worker. |
| **Index-builder** | Periodically reads all rows from Postgres and builds a fresh ranked trie, then atomically swaps it in. The "publish a new version" step. |
| **In-memory trie** | Read-optimized serving structure. Each node caches the top-10 completions of its subtree. O(prefix length) lookups. |
| **Redis (×3)** | Distributed cache of per-prefix top-10 lists. Three standalone instances (not Redis Cluster). Distribution demo + warm-cache absorber under load. |
| **Kafka (KRaft mode)** | Durable, replayable event log between `POST /search` and the batch worker. Single broker, **no Zookeeper**. Topic `search-events`; the batch worker is a consumer group. Acts as the write-ahead log. |
| **Postgres** | Source of truth: counts + recency. Written by the batch worker; read in full only by the index-builder. |

Stack: Java 21 / Spring Boot for both services. One `docker-compose.yml` brings up both services, three Redis instances, Postgres, and a single Kafka broker (KRaft mode).

---

## 4. Read path — serving suggestions

### 4.1 Flow

1. UI **debounces** keystrokes (no request per character).
2. `GET /suggest?q=<prefix>` hits the suggestion service.
3. Form the cache key with the current build generation: `suggest:v<gen>:<prefix>`. Compute `hash(key) → Redis node` via the consistent-hash ring; `GET`.
4. **Hit** → return the cached top-10.
5. **Miss** → walk the **in-memory trie** to the prefix node (microseconds), read its precomputed top-10, write it back into the owning Redis node with a jittered TTL, return.

Input handling: empty/missing → empty; lowercased for case-insensitive matching; no-match → empty list, cached (negative caching).

### 4.2 Consistent hashing (cache distribution)

- Hash ring as a `TreeMap<Long, NodeId>`; each Redis node inserted as ~150 **virtual nodes**.
- Lookup: `ring.ceilingEntry(hash(key))`, wrapping to `firstEntry`.
- **Sharding, not load balancing** — each node owns a disjoint slice of keys; the hash names the single owner. Logic lives in the read service, not a separate box.
- Not Redis Cluster (its internal CRC16-mod-slot hashing would hide the mechanism we must own).
- `GET /cache/debug?prefix=<prefix>` reports owning node + hit/miss. Removing a node remaps only ~1/N of keys.

### 4.3 Why a miss is cheap now

The miss path is an **in-memory trie walk**, not a Postgres scan, so hit-vs-miss is fast-vs-fast. Postgres is off the per-request path entirely. Trade-off accepted: the Redis-vs-backend latency gap in the performance report shrinks (both are fast) — we trade a flashy cache-win graph for architectural honesty.

### 4.4 Stampede defenses (load-bearing under load testing)

| Defense | Prevents | Note |
| ------- | -------- | ---- |
| **Single-flight / coalescing** | Hot-key stampede — many concurrent misses on one expired key | First request rebuilds; others wait and share. N misses → 1 trie read. |
| **TTL jitter** (`60s ± rand(0–15s)`) | Synchronized-expiry stampede — many *different* keys expiring at the same instant | Smooths the miss *rate*, not staleness. |
| **Generation keys** + **top-K warming** | Rebuild-time miss burst (all keys orphaned at once on a new generation) | Builder pre-populates the hottest prefixes into the new generation before traffic hits. |

These two stampede shapes (one hot key vs many keys at once) need different defenses; use both. Honest note: with an in-memory miss path a stampede hits a microsecond trie walk, not a slow DB — so these defenses primarily demonstrate the failure mode, and would be truly load-bearing if the miss path were a sharded database.

---

## 5. Write path — search submission and batching

### 5.1 Flow

1. `POST /search` validates, produces an event `{query, ts}` to the Kafka topic `search-events`, returns `{"message":"Searched"}` immediately. Never touches Postgres directly.
2. The **batch worker** accumulates events into an in-memory `Map<String, Long>` keyed by query. Duplicates collapse — 500 "iphone" events become one `+500`.
3. **Flush trigger: size OR timer, whichever comes first** — flush when the map holds N distinct queries/events, **or** every few seconds (e.g. 5s). The timer is essential: it bounds staleness and crash-loss during quiet periods when the size trigger never fires.
4. Flush the **whole map** (not selected queries): atomically swap the buffer for a fresh empty one, then apply all deltas in **one batched transaction** — an upsert per query:
   ```sql
   INSERT INTO queries (query, all_time_count, recent_score, last_updated)
   VALUES (:q, :delta, :delta, now())
   ON CONFLICT (query) DO UPDATE SET
     all_time_count = queries.all_time_count + EXCLUDED.all_time_count,
     recent_score   = queries.recent_score * pow(:factor, extract(epoch FROM now() - queries.last_updated))
                      + EXCLUDED.recent_score,
     last_updated   = now();
   ```
5. **Write to Postgres first, then commit the Kafka offset** → at-least-once. (Disable auto-commit; commit only after the batched upsert succeeds.)

### 5.2 Rejected: per-query flush threshold

Do **not** add "write a query once its count reaches N." Two failure modes:
- *Eager per-query write* is anti-batching — it generates more individual writes, defeating the point.
- *Discard queries under N at flush* is lossy — it strands the long tail (most queries are searched once or twice but must still be counted and become suggestable).
And it buys nothing: one flush is one transaction whether it carries 50 rows or 5,000 (cost is the transaction/round-trip, not row count), and users only see changes after the next trie rebuild, not after a flush.

### 5.3 Durability and trade-offs

- Write-then-commit ordering → **at-least-once**; a crash between them re-delivers events (worst case: small double-count).
- The **durable Kafka log is the WAL** — events whose offset has not been committed survive a crash and are re-delivered on restart, so the in-flight buffer is never the only copy (a pure in-memory map would be at-most-once). Retention keeps `search-events` long enough to replay if needed.
- Double-counting is acceptable: ranking needs only **approximate** counts. Exactness would need event-ID dedup or transactional offset commits — a deliberate trade-off.
- Optional sampling alternative (rubric mentions it): count a sampled 1/k of events and multiply — an accuracy-for-throughput trade, distinct from the flush trigger.

### 5.4 Write reduction (evidence to report)

N raw events → M distinct-query writes (M ≪ N) because traffic is skewed to popular terms. Report events received vs DB writes issued per window.

---

## 6. Index-builder

The builder converts write-optimized rows into a read-optimized trie. No single structure is good at both: Postgres rows are cheap to update but expensive to prefix-query; the trie is expensive to update but trivial to read.

**Build steps:**
1. Full read of `queries` from Postgres (offline, not per-request).
2. Insert each query into a fresh trie.
3. **Post-order pass:** each node computes its top-10 by merging its children's top-10 lists plus any query terminating at the node, keeping the 10 highest by blended score. *Correctness note:* you must genuinely merge — one child's 10 best can all outrank another child's — not sample.
4. Bump the build **generation** (`v<gen>` → `v<gen+1>`).
5. Optionally warm the top-K hottest prefixes into Redis under the new generation.
6. **Atomically swap** the new trie in (replace a reference) so in-flight reads see either the whole old or whole new trie, never a mix. Read-only between builds → no locks.

**Cadence:** timed rebuild every 30–60s (cheap for 100k entries; bounds staleness predictably). Preferred over rebuild-per-flush, which over-rebuilds relative to how often rankings meaningfully change.

**Why it matters:** it closes the freshness loop. Without it, batch writes would update Postgres but never affect a single suggestion. It is the local equivalent of the lambda batch layer / Lucene segment build / versioned-artifact publish.

---

## 7. Cache freshness — how Redis gets updated

Nothing actively updates Redis. It updates **lazily**, on the first miss after a key expires or its generation flips. The write path never touches Redis.

**Generation-versioned keys (chosen).** The cache key embeds the build generation: `suggest:v<gen>:<prefix>`. On rebuild, the generation bumps; all old keys are instantly orphaned (no deletes — they age out), and the first read under the new generation re-populates from the fresh trie. This collapses TTL lag into rebuild lag: Redis goes current the moment the trie does, with no write-through and no per-key invalidation.

Rejected: **write-through** — would force per-prefix, cross-node fan-out (a query touches `i`, `ip`, `iph`… on possibly different nodes) and re-couple write path to cache topology. Buys nothing once the trie is fast.

**Staleness budget (additive, not max):**
```
search recorded
  └─ flush lag    (≤ ~5s)        batch worker
      └─ rebuild lag (≤ ~30–60s) index-builder
          └─ visible in suggestions
```
With generation keys, TTL lag folds into rebuild lag. Total visibility lag ≈ flush + rebuild. Fine for typeahead.

---

## 8. Trending — recency-aware ranking

- `recent_score` per query, decayed lazily by `factor^elapsed` at each batch write (no global sweep). Exponential decay prevents permanently over-ranking a brief spike.
- Blended ranking baked into the trie at build time: `score = w1·log(all_time_count) + w2·recent_score`.
- Trending = `ORDER BY recent_score DESC`, surfaced at build time.
- Same `GET /suggest` serves both basic (all-time) and enhanced (recency) rankings.

---

## 9. Data model (Postgres)

```sql
CREATE TABLE queries (
    query           TEXT PRIMARY KEY,
    all_time_count  BIGINT           NOT NULL DEFAULT 0,
    recent_score    DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_updated    TIMESTAMPTZ      NOT NULL DEFAULT now()
);
-- supports left-anchored prefix scans if ever needed: WHERE query LIKE 'iph%'
CREATE INDEX idx_queries_query ON queries (query text_pattern_ops);
```

### Dataset

Source: **AmazonQAC** — <https://huggingface.co/datasets/amazon/AmazonQAC> (CDLA-Permissive-2.0). A naturalistic query-autocomplete dataset from U.S. Amazon search logs (Sept 2023): ~395M rows over **40M unique search terms**, Parquet/Snappy. Each row carries a `final_search_term` and a precomputed **`popularity`** (global occurrence count), so `(query, count)` needs almost no derivation — `popularity` maps straight to `all_time_count`. The `search_time` field optionally seeds `recent_score`. Queries are product/e-commerce in flavor. Because the trie is in memory, we load the **top-N by popularity** (default 1M) rather than all 40M.

---

## 10. Load testing & capacity

Since the system is load-tested, the stampede defenses (§4.4) are exercised for real — a load generator concentrating on hot prefixes triggers exactly the synchronized-expiry and hot-key dogpiles they defend against.

- **Drive load** with k6 / Locust / wrk against `/suggest` (skewed prefix mix to mimic Zipfian traffic) and `/search`.
- **Measure:** p50/p95/p99 latency for `/suggest` split by hit vs miss; cache hit rate; throughput (req/s); DB writes vs raw events (write reduction); rebuild duration and frequency.
- **Expected behavior:** hit rate climbs toward the Zipfian ceiling (often 90%+) as hot prefixes stay warm; p95 stays low because misses hit the trie, not the DB; the batch worker keeps DB write rate roughly constant regardless of search QPS (that's the whole point).
- **Fail-open:** if the suggestion service is saturated or down, the UI shows no suggestions and the user can still type and submit — typeahead is best-effort, not critical-path.
- **Knobs to tune under load:** flush size/interval (write batching vs staleness), TTL + jitter window (miss rate smoothing), rebuild cadence (freshness vs build cost), virtual-node count (key distribution evenness).

---

## 10a. Replication policy

Rule: **replicate sources of truth, not regenerable caches.**

| Tier | Replicas? | Why |
| ---- | --------- | --- |
| **Postgres** | Yes (or at minimum a documented backup/WAL story) | Holds the only durable copy of accumulated counts/recency. Loss is unrecoverable. |
| **Redis (×3)** | No | Pure cache of derived data. Every entry is regenerable from the in-memory trie in microseconds. |

A Redis node dying loses ~1/N of *cached copies of data we still have*. Those requests miss, fall back to the trie, and re-warm — a self-healing **miss spike, not data loss**. The usual reason to replicate cache nodes (avoid a cold-cache stampede on the slow DB during failover) does not apply here, because the fallback is the in-memory trie, not a database, and single-flight + jitter (§4.4) absorb the spike. Read scaling is already provided by the consistent-hash ring spreading keys across nodes — sharding, not replication. Three standalone nodes also keep the consistent-hashing demo legible (drop a node, watch ~1/3 remap and re-warm) instead of hiding it behind failover machinery.

---

## 10b. Responsibility boundaries

Service-level single-responsibility (distinct from SOLID, which governs *classes*, not service/process boundaries):

| Owner | Owns this state | Background job it hosts |
| ----- | --------------- | ----------------------- |
| **Ingestion (write) service** | recording events → Postgres | **batch worker** (queue consumer + aggregator + flusher) |
| **Suggestion (read) service** | serving from the in-memory trie | **index-builder** (Postgres → trie, atomic swap) |

- The **batch worker** is a component *inside* ingestion, not a separate service — accepting and persisting an event are the front and back of one responsibility ("record searches"), split in time by the queue for batching. The queue already decouples acceptance from processing, so a service boundary would add operational cost for isolation we already have.
- The **index-builder** lives *inside* the read service because the trie must live in the same process that serves from it; the service that owns the artifact owns the thing that builds it.
- **No service mutates another's internal state.** They meet only at shared infrastructure both treat as external: the **queue** and **Postgres**. The builder reading Postgres is a consumer reading a shared source of truth through its schema, not reaching into the write service.
- Scale-up path: the batch worker can become its own horizontally-scaled consumer tier — at which point it needs **consumer groups + idempotent aggregation** to avoid double-counting across instances. Single in-process worker today sidesteps that.

---

## 11. API summary

| API | Purpose | Behavior |
| --- | ------- | -------- |
| `GET /suggest?q=<prefix>` | Fetch suggestions | Up to 10 prefix matches, blended popularity + recency. Cache-first, in-memory trie on miss. |
| `POST /search` | Submit a search | Returns `{"message":"Searched"}`; enqueues an event for batched counting. |
| `GET /cache/debug?prefix=<prefix>` | Debug cache routing | Owning Redis node + hit/miss. |

---

## 12. Mapping to real systems

| This design | Real-world equivalent |
| ----------- | --------------------- |
| Index-builder + ranked trie | Lambda **batch layer** producing a serving artifact; Lucene segment build. |
| In-memory trie (read-only, swapped) | **FST** at hyperscale (suffix-shared, memory-dense) — at 100k a trie is the right tool; FST is the scale-up evolution. |
| Generation-versioned keys + atomic swap | Versioned **immutable index artifact** + pointer flip. |
| Consistent-hash ring over Redis | Elasticsearch sharding (but ES uses `hash mod shard_count` → rehashing rigidity that consistent hashing avoids). |
| Kafka log as WAL | Elasticsearch **translog** (durable, replayable write log). |
| Cache/rebuild visibility lag | Elasticsearch **refresh interval** (near-real-time). |
| nginx | Reverse proxy + static server (+ optional L7 LB); not an API gateway. |

---

## 13. Design rationale (talking points)

Defend each choice by its *reason*, not its label:

- **Read/write split** — serve path is latency-critical/stateless; ingestion is throughput-oriented/stateful; recompute must never touch read latency. The queue is the durable event log that decouples them.
- **In-memory trie as miss path** — keeps Postgres off the per-request read path; a miss is microseconds. Cost: smaller measurable cache-win, accepted for fidelity.
- **Index-builder** — the only thing that propagates batch-written counts into what users see; rebuild = publish-new-version.
- **Consistent hashing over Redis** — partition prefix keys across nodes, ~1/N remap on membership change. Sharding, owned in app code. (At this scale Redis is a distribution demo + warm-cache absorber, not a latency win — say so.)
- **Size-or-timer, whole-map flush; no per-query gate** — batching already minimizes writes; the timer bounds staleness; a per-query gate is either anti-batching or lossy.
- **Generation keys, not write-through** — Redis goes current at rebuild without cross-node fan-out or coupling.
- **TTL jitter + single-flight** — defend the two distinct stampede shapes; load-bearing under load testing.

> Honest framing: the microservice split, the queue, and (if added) DB sharding are deliberate learning artifacts demonstrating real architecture, not necessities at 100k queries. State that and defend each on principle.

---

## 14. Rubric coverage

| Component | Marks | Where |
| --------- | ----- | ----- |
| Basic implementation | 60 | Ingestion + UI (§3, §11), `/suggest` + `/search` (§4, §5), distributed cache via consistent hashing (§4.2), in-memory serving index (§4, §6). |
| Trending searches | 20 | Lazy-decay scoring, blended ranking, build-time surfacing (§8). |
| Batch writes | 20 | Aggregating buffer, size-or-timer whole-map flush, batched upsert, durability + rejected alternatives (§5). |
