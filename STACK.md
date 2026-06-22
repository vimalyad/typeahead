# Technology Stack

> Choices and rationale for the search typeahead system. See [`ARCHITECTURE.md`](./ARCHITECTURE.md) for the design.

> **Version note:** pinned versions below are current at time of writing. Generate backends from [start.spring.io](https://start.spring.io) and the frontend via `npm create vite@latest` to pull the latest compatible releases. Verify before pinning in CI.

---

## At a glance

| Layer | Choice | Version |
| ----- | ------ | ------- |
| Language (backend) | **Java** | **21 (LTS)** |
| Backend framework | Spring Boot | 3.x (3.2+ for virtual threads) |
| Build | Gradle (Kotlin DSL) — Maven also fine | 8.x |
| Source of truth | PostgreSQL | 16 |
| Cache | Redis (×3 standalone) | 7 |
| Queue / event log | **Apache Kafka (KRaft mode)** — single broker, no Zookeeper | 3.7+ / 4.x |
| DB access | Spring Data JPA (reads) + JdbcTemplate (batch upsert) | — |
| Migrations | Flyway | — |
| Reverse proxy / static | nginx (alpine) | — |
| Frontend | React + Vite + TypeScript + Tailwind | React 18+, Vite 5+, Node 20+ |
| Metrics | Spring Boot Actuator + Micrometer | — |
| Tests | JUnit 5 + Testcontainers | — |
| Load testing | k6 (or Locust) | — |
| Dataset | **AmazonQAC** (Amazon search logs) | 395M rows / 40M unique terms |
| Dataset loading | DuckDB (Parquet → aggregate → CSV) | — |
| Orchestration | Docker + docker-compose | — |

---

## Why Java 21 specifically

Java 21 is the current LTS, and two of its features map directly onto this system:

- **Virtual threads (Project Loom, GA in 21).** The suggestion service is dominated by short blocking calls (Redis `GET`, then a trie walk on miss). Under load testing that means thousands of concurrent, mostly-blocked requests. With Spring Boot 3.2+ and `spring.threads.virtual.enabled=true`, each request runs on a virtual thread, so high concurrency does not exhaust a fixed platform-thread pool. This is the single biggest reason to be on 21 rather than 17 for a system you intend to load-test.
- **Records.** Used pervasively for immutable value types: the search event (`record SearchEvent(String query, long ts)`), the suggestion DTO (`record Suggestion(String query, double score)`), and the trie's cached top-10 payloads. Records give correct `equals`/`hashCode` for free — handy for the batch worker's aggregation map and for test assertions.
- Plus pattern matching for `switch`, sealed types where a closed set of states helps, and `var` for local inference — all minor quality-of-life wins.

Toolchain: pin Java 21 via the Gradle toolchain (`languageVersion = JavaLanguageVersion.of(21)`) so the build is reproducible regardless of the developer's default JDK.

---

## Backend — Spring Boot 3.x

Two Spring Boot apps (one per service from the architecture): **suggestion-service** (read) and **ingestion-service** (write). Shared value types (events, DTOs) can live in a small shared module or be duplicated — at this size, duplication is acceptable and avoids a coupling module.

Starters:

| Starter | Used by | For |
| ------- | ------- | --- |
| `spring-boot-starter-web` | both | REST endpoints (or `webflux` if you prefer reactive; with virtual threads, plain MVC is simpler and sufficient) |
| `spring-boot-starter-data-jpa` | both | entity reads; the index-builder's full scan |
| `spring-boot-starter-validation` | both | validate `q` / request bodies |
| `spring-boot-starter-actuator` | both | health checks + Micrometer metrics |
| `spring-boot-starter-data-redis` *(Lettuce)* | suggestion | base Redis client (see caveat below) |
| `spring-kafka` | both | producer (ingestion) + manual-ack consumer (batch worker) |

**Redis client caveat.** Spring's `RedisTemplate` assumes one logical Redis. Here we run **three standalone instances and own the routing ourselves** (consistent-hash ring), so we hold a `List<RedisClient>`/connections (Lettuce) indexed by the ring and call the chosen node directly. We deliberately do **not** use Redis Cluster (its internal CRC16 slot hashing would replace the hashing we must demonstrate). Lettuce is preferred over Jedis for its non-blocking core, though with virtual threads either works.

---

## Data — PostgreSQL 16

Single source of truth (`queries` table; schema in ARCHITECTURE.md §9). Replicated/backed-up because it holds the only durable copy of counts (ARCHITECTURE.md §10a). DB sharding is optional and, if added, is by prefix range (ARCHITECTURE.md §9).

- **Reads** (index-builder full scan, ad-hoc): Spring Data JPA repository.
- **Batch upsert** (hot write path): **`JdbcTemplate.batchUpdate`** with `INSERT ... ON CONFLICT ... DO UPDATE`. JPA's per-entity flushing is the wrong tool for a single batched transaction of thousands of upserts; raw JDBC batching is explicit and fast.
- **Migrations:** Flyway (`V1__queries.sql`, etc.) so schema is versioned and the demo is reproducible.

---

## Queue / event log — Apache Kafka (KRaft mode)

The role (ARCHITECTURE.md §5) is a **durable, replayable event log** that decouples `POST /search` from the batch worker and serves as the write-ahead log. We use **Kafka in KRaft mode**.

**KRaft, not Zookeeper.** Modern Kafka replaced Zookeeper with a built-in Raft-based metadata quorum (KRaft). KRaft has been production-ready since Kafka 3.3, is the default, and Zookeeper support was removed in Kafka 4.0. So the deployment is a **single Kafka broker container with no Zookeeper** — one service in compose, not two. ("Kafka + Zookeeper" is the pre-2023 topology and should not be used.)

**Why Kafka here.** The goal is fidelity to how large systems actually ingest query streams: a partitioned, durable, replayable log. Kafka gives the genuine event-log model that maps onto the Elasticsearch-translog / lambda-ingestion story, and consumer-group offsets give exactly the at-least-once semantics the batch worker needs (commit offset only after the Postgres write succeeds).

**Topology for this project:**

| Setting | Value | Reason |
| ------- | ----- | ------ |
| Topic | `search-events` | one topic for submitted searches |
| Partitions | 1 (demo) → N (scale) | single partition keeps ordering trivial and one consumer simple; partition by query key when scaling consumers |
| Consumer group | `batch-writer` | the batch worker; one instance today |
| `enable.auto.commit` | `false` | commit manually **after** the batched upsert → at-least-once |
| Retention | hours–days | enough to replay un-processed events after a crash |

**Client:** `spring-kafka` (`spring-boot-starter` + `org.springframework.kafka:spring-kafka`). Producer in the ingestion service's `POST /search`; a manual-ack `@KafkaListener` (or a plain consumer loop) in the batch worker so offset commit is tied to flush success.

> Honest note: at 100k queries Kafka is heavier than the workload strictly needs — it's a deliberate fidelity choice (ARCHITECTURE.md §13). KRaft mode keeps that cost to a single container. If "easy to run locally" ever outweighs fidelity, Redis Streams is the drop-in lighter substitute, since the consume→aggregate→flush→commit loop is the same shape.

---

## Frontend — React + Vite + TypeScript + Tailwind

- **Vite** dev server with a proxy to the backend in development; **`vite build`** produces a static bundle that **nginx** serves in the container.
- Debounced `GET /suggest`, suggestion dropdown with keyboard navigation, trending section, loading/error/empty states (ARCHITECTURE.md §10).
- TypeScript types mirror the backend DTOs (`Suggestion`, etc.).
- Tailwind for layout; no component library required.

---

## Dataset & loading — AmazonQAC + DuckDB

Dataset: **AmazonQAC** — <https://huggingface.co/datasets/amazon/AmazonQAC>. A large, naturalistic query-autocomplete dataset from U.S. Amazon search logs (Sept 2023), license **CDLA-Permissive-2.0** (permissive, unlike ORCAS's research-only terms). It is the largest fit-for-purpose option for this use case.

Scale and shape: ~395M train rows, **40M unique search terms**, Parquet/Snappy, ~59 GB total. The columns that matter for us:

| Column | Use |
| ------ | --- |
| `final_search_term` | the query string → `queries.query` |
| `popularity` | **precomputed global occurrence count** → `queries.all_time_count` |
| `search_time` | timestamp → optionally seed `queries.recent_score` |
| `prefixes`, `session_id`, `query_id` | not needed for our `(query, count)` model |

Two consequences worth knowing:

- **Counts are essentially built in.** `popularity` is the global count for a term, so we don't aggregate clicks like ORCAS — we read `final_search_term` + `popularity` and dedupe. Because `popularity` is global, a row in *any* shard already carries a term's true count, so processing a **subset of shards** still yields accurate counts for the terms present — useful if downloading all 59 GB is impractical.
- **We load a sample, not all 40M.** The serving trie is in memory, so we take the **top-N by popularity** (default 1M; tunable up toward the full 40M only if the host has the RAM and longer rebuilds are acceptable). 1M product queries keeps the trie in the low hundreds of MB.

Tooling: **DuckDB** reads the Parquet directly (column-pruned, so only the two needed columns are fetched), aggregates, and writes a CSV the loader `COPY`s into Postgres. DuckDB is a build-time tool only — not a runtime dependency of either service.

---

## Edge — nginx

One nginx container: serves the static React build and reverse-proxies `/api/suggest` → suggestion-service and `/api/search` → ingestion-service by path, giving the browser a single origin (no CORS). `upstream` blocks provide optional L7 load balancing if a service is scaled to multiple instances. Not configured as an API gateway — no auth/rate-limit layer is in scope.

---

## Observability & testing

- **Actuator + Micrometer** expose `/suggest` latency (timer, tagged hit/miss), cache hit rate (counter), and batch write counts — the exact numbers the performance report needs. Scrape with Prometheus or just read the Actuator endpoint for the demo.
- **JUnit 5** unit tests for the pure logic: hash-ring key distribution and remap-on-node-change, the trie's post-order top-10 merge correctness, and the decay math.
- **Testcontainers** spins up real Postgres and Redis for integration tests (no mocks for the cache/DB seams).
- **k6** (or Locust) drives load with a Zipfian-skewed prefix mix; measures p50/p95/p99, hit rate, throughput, and write reduction (ARCHITECTURE.md §10).

---

## Containers (docker-compose services)

```
suggestion-service   (Java 21, Spring Boot)   :8081
ingestion-service    (Java 21, Spring Boot)   :8082
redis-1 / redis-2 / redis-3                   :6379 / 6380 / 6381   (cache ring)
kafka                (KRaft mode, no Zookeeper):9092                (topic: search-events)
postgres                                       :5432
nginx                                          :80   → static build + /api/* proxy
```

The Kafka broker runs in **KRaft mode** — a single container configured as both controller and broker (`KAFKA_PROCESS_ROLES=broker,controller`), no separate Zookeeper service. Everything comes up with `docker compose up`, satisfying the "easy to run locally" requirement.
