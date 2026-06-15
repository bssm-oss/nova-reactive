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

## Methodology

- **DB:** H2 **in-memory** for both (`r2dbc:h2:mem` / `jdbc:h2:mem`). In-memory removes
  network/disk I/O, so the numbers isolate **ORM mapping + driver overhead**, not the database.
- **Fairness:**
  - Both use a connection pool capped at 20 (Nova: `r2dbc-pool`; Hibernate: built-in pool).
  - One operation = one autocommit unit on both sides (Hibernate: a short tx per write;
    Nova: `save().block()` per row). Hibernate batching is disabled (`batch_size=0`).
  - Reads `em.clear()` Hibernate's first-level cache each call so every read is a real DB
    round-trip — Nova has no such cache outside a session, so this matches conditions.
  - Single-threaded latency: Nova's `Mono`/`Flux` are `block()`-ed to impose the same
    synchronous boundary.
- **Measurement:** warmup rounds then N measured rounds per scenario, **median** reported.
- **E2E correctness** is asserted before every profiling run (insert → findAll → findById →
  update → delete with count checks), so the harness doubles as an end-to-end test.

## Representative results

H2 in-memory, single JVM, `N=2000`, median of 7 (Apple Silicon; **numbers vary per run/machine**):

| Scenario    | Nova (reactive)   | Hibernate (blocking) | Nova / Hib |
|-------------|-------------------|----------------------|------------|
| INSERT      | ~133 ms (15k/s)   | ~367 ms (5.5k/s)     | **0.36x** (Nova ~2.8x faster) |
| FIND_BY_ID  | ~85 ms (23k/s)    | ~32 ms (63k/s)       | 2.71x (Hibernate faster) |
| FIND_ALL    | ~4.0 ms           | ~3.6 ms              | 1.11x (≈ even) |
| UPDATE      | ~114 ms (18k/s)   | ~59 ms (34k/s)       | 1.94x (Hibernate faster) |
| DELETE      | ~29 ms            | ~26 ms               | 1.12x (≈ even) |

Concurrent `findById` throughput (concurrency=16, 20k ops): Hibernate ~150k ops/s vs Nova
~57k ops/s.

## Interpretation (honest)

- **Writes (INSERT/UPDATE): Nova is ~2x faster.** Nova's write path is lean — no
  persistence-context, no per-entity dirty-checking/flush graph. Hibernate pays
  unit-of-work overhead on every `persist`/managed-update even for a single row.
- **Point reads (FIND_BY_ID): Hibernate is ~2.7x faster.** A single blocking `em.find` is a
  tight prepared-statement path; Nova pays reactive-assembly overhead (`Mono` per op,
  context lookups, scheduler) for each `block()`-ed call.
- **FIND_ALL / DELETE: roughly even.**
- **Concurrency on in-memory H2 favors blocking.** With H2 in-memory there is **no I/O wait
  to overlap**, so 16 native threads doing microsecond-scale finds out-throughput a reactive
  pipeline whose per-op overhead now dominates. Reactive's throughput advantage comes from
  overlapping *real* I/O latency (network round-trips, slow queries) on a few threads —
  which an in-memory DB cannot exhibit.

## Caveats / future work

- Reactive vs. blocking is an architectural comparison, not a like-for-like one; treat the
  numbers as directional.
- The most representative reactive-advantage benchmark needs **real I/O latency** — a
  PostgreSQL + Testcontainers variant (concurrent load under network latency) is the natural
  next step and is expected to flip the concurrency result in Nova's favour.
- This is a coarse timing harness, not JMH; it avoids JMH setup but does warmup + median to
  reduce noise. For publication-grade microbenchmarks, port the scenarios to JMH.
