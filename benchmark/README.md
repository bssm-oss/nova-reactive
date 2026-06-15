# Nova vs Hibernate ORM — E2E + performance profiling

A standalone harness that runs the **same `jakarta.persistence` entity** through both
**Nova** (reactive, R2DBC) and **Hibernate ORM** (blocking, JDBC) against H2, verifies
end-to-end correctness, and profiles CRUD performance.

Because Nova reuses the real JPA annotations, `entity/BenchUser.java` is mapped by *both*
ORMs unchanged — the comparison is genuinely apples-to-apples at the mapping level.

## Layout

- Standalone Gradle build; `settings.gradle.kts` uses `includeBuild("..")` so Nova resolves
  to the **local** source (composite build) — it is **not** part of Nova's published build
  graph and is never published to Maven Central.
- `OrmBenchmark` — the shared contract; `NovaOrm` / `HibernateOrm` — the two drivers;
  `BenchmarkRunner` — the `main` that verifies + profiles.

## Run

```bash
cd benchmark
./gradlew run
# tune via system properties:
./gradlew run -Dbench.n=5000 -Dbench.measure=11 -Dbench.concurrency=16
```

## Two backends

The runner profiles both:

1. **H2 in-memory** — no network/disk I/O, so numbers isolate **ORM mapping + driver overhead**.
2. **PostgreSQL** — real socket round-trips (real I/O latency), where the reactive concurrency
   model is observable. Uses Testcontainers if Docker is reachable, otherwise point it at an
   external PG:
   ```bash
   docker run -d --name pg -e POSTGRES_DB=bench -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -p 5433:5432 postgres:16-alpine
   ./gradlew run -Dbench.pg.host=localhost -Dbench.pg.port=5433
   ```

## Methodology

- **Fairness:** both use a 20-connection pool (Nova: `r2dbc-pool`; Hibernate: **HikariCP**, so
  it queues — not throws — under concurrency > pool). One op = one autocommit unit; Hibernate
  batching off (`batch_size=0`); reads `em.clear()` Hibernate's L1 cache each call so every
  read is a real round-trip (Nova has no such cache outside a session); Nova `Mono`/`Flux`
  are `block()`-ed for the single-thread latency boundary.
- **Measurement:** warmup then N measured rounds per scenario, **median**.
- **E2E correctness** is asserted before profiling (insert → findAll → findById → update →
  delete with count checks) — the harness doubles as an end-to-end test.
- The concurrency test also records **peak JVM thread count** to expose the resource model.

## Representative results

Apple Silicon, single JVM; **numbers vary per run/machine**.

### H2 in-memory (ORM overhead only) — N=2000

| Scenario | Nova | Hibernate | Nova / Hib |
|---|---|---|---|
| INSERT | ~132 ms | ~344 ms | **0.38x — Nova ~2.6x faster** |
| FIND_BY_ID | ~100 ms | ~37 ms | 2.69x — Hibernate faster |
| FIND_ALL | ~4.7 ms | ~2.2 ms | 2.12x |
| UPDATE | ~120 ms | ~66 ms | 1.81x |
| DELETE | ~30 ms | ~27 ms | 1.12x (≈ even) |

### PostgreSQL — real socket round-trips — N=500 — three engines

Here we add **Hibernate Reactive** (non-blocking Hibernate on Vert.x) for a true
reactive-vs-reactive comparison. (Hibernate Reactive is PG-only — Vert.x has no H2 client.)

Single-thread latency (median ms, lower better):

| Scenario | Nova (reactive) | Hibernate ORM (blocking) | Hibernate Reactive |
|---|---|---|---|
| INSERT | **254** | 462 | 879 |
| FIND_BY_ID | 352 | **122** | 176 |
| UPDATE | 980 | **464** | 1530 |
| DELETE | **184** | 451 | 613 |

### Concurrent findById (concurrency=200) — the key result

| Engine | throughput | **peak threads** |
|---|---|---|
| Nova (reactive) | 6.6k ops/s | **23** |
| Hibernate ORM (blocking) | 10.9k ops/s | **224** |
| Hibernate Reactive (reactive) | 11.8k ops/s | **24** |

(H2 for reference: Nova 28.6k/s · 9 threads vs Hibernate ORM 101k/s · 210 threads.)

## Interpretation (honest)

- **Writes (INSERT/UPDATE): Nova's lean path wins or ties.** No persistence-context, no
  per-entity dirty-checking/flush. The advantage is large on H2 (~2.6x) and **dissolves under
  PG latency** (≈ even) because the network round-trip dominates the per-op cost.
- **Point reads (FIND_BY_ID, FIND_ALL): Hibernate's tight JDBC path is ~2.7x faster** on both
  backends — Nova pays reactive-assembly overhead (`Mono` per op, context lookups, scheduler)
  per `block()`-ed call, and that overhead is fixed regardless of backend.
- **DELETE: Nova ~1.6x faster on PG.** Nova issues one `DELETE` round-trip; Hibernate's
  `em.find` + `em.remove` is two round-trips, and the second one costs a full network latency —
  invisible on H2, real on PG.
- **Thread-efficiency is a *reactive* trait, not a Nova feature.** This is the headline of the
  three-engine run. At 200 concurrency, **both reactive engines** — Nova (**23 threads**) and
  Hibernate Reactive (**24 threads**) — sustain the load on a handful of event-loop threads,
  while **blocking** Hibernate ORM needs **224** (≈ one OS thread per in-flight request, each
  ~1 MB stack + context-switching). The line that matters is reactive vs. blocking, not
  Nova vs. Hibernate.
- **Throughput is pool-bound** — all three top out near `20 connections / latency`, so no engine
  out-throughputs the others by much at a fixed pool (the reactive engines slightly trail/lead
  within noise; on zero-latency H2, reactive's per-op overhead loses to blocking). The reactive
  win is **resource cost**, not raw throughput at a fixed pool: scale concurrency to thousands
  and the blocking thread-per-request model becomes the bottleneck while the reactive engines do not.
- **Among the reactive engines, the trade-off is weight vs. leanness.** Hibernate Reactive
  carries the full Hibernate engine (persistence context, dirty checking) on Vert.x and was
  competitive-to-ahead on concurrent throughput here; **Nova is the lean one** — it won
  single-op INSERT and DELETE outright (no unit-of-work overhead, one round-trip per delete) but
  pays reactive-assembly overhead on point reads. Pick Nova when you want a minimal reactive
  data-mapper; pick Hibernate Reactive when you want full JPA semantics reactively.

## Caveats

- Reactive vs. blocking is architectural, not like-for-like — treat numbers as directional.
- Coarse timing harness (warmup + median), not JMH. For publication-grade microbenchmarks,
  port to JMH.
- Throughput here is connection-pool-bound by design (equal 20-conn pools); the resource
  (thread/memory) dimension is where the models genuinely diverge.
