<!-- SPDX-License-Identifier: Apache-2.0 -->

# Observability

Modules and hooks for observing SQL execution, gathering metrics, and probing connection-pool reachability.

## SQL execution hook

The `SqlExecutionListener` interface lets you observe every SQL call. The R2DBC adapter fires before / after / error events on the execute, query, and batch paths.

```java
SqlExecutionListener slowLog = new SlowQueryLoggingListener(Duration.ofMillis(100));
SqlExecutionListener composite = CompositeSqlExecutionListener.of(slowLog, otherListener);

R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect, composite);
```

`SlowQueryLoggingListener` only logs queries above the threshold and never exposes bindings (PII safety). `onError` always logs, regardless of the threshold.

---

## Metrics (Micrometer)

`nova-metrics-micrometer` ships `MicrometerSqlExecutionListener`, a `SqlExecutionListener` implementation. It records a timer for every execute / query / batch path and increments an error counter on failure.

```kotlin
// build.gradle.kts
implementation("io.github.bssm-oss:nova-metrics-micrometer:2.0.0")
implementation("io.micrometer:micrometer-core:1.14.0")
```

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.nova.metrics.micrometer.MicrometerSqlExecutionListener;

MeterRegistry registry = /* PrometheusMeterRegistry, SimpleMeterRegistry, etc. */;

// Uses the default prefix "nova.sql"
SqlExecutionListener listener = new MicrometerSqlExecutionListener(registry);

// Or with a custom prefix
SqlExecutionListener listener = new MicrometerSqlExecutionListener(registry, "app.db");

R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect, listener);
```

Emitted meters:

| Meter                    | Type     | Tags                                              | Meaning                            |
|--------------------------|----------|---------------------------------------------------|------------------------------------|
| `<prefix>.query`         | Timer    | `outcome=success`                                  | Elapsed time of successful queries |
| `<prefix>.query`         | Timer    | `outcome=error`, `exception=<SimpleName>`         | Elapsed time of failed queries     |
| `<prefix>.errors`        | Counter  | `outcome=error`, `exception=<SimpleName>`         | Failure count                       |

> **Cardinality warning**: for anonymous lambda / anonymous-class exceptions, `getSimpleName()` returns an empty string and falls back to `exception=Anonymous`. In scenarios with unbounded user-defined and driver-wrapped exceptions, time-series cardinality (e.g. in Prometheus) can explode; consider an upstream policy that limits exception wrapping.

To combine with other listeners, use `CompositeSqlExecutionListener.of(...)`.

---

## Connection pool (nova-r2dbc)

Beyond R2DBC pool implementations, Nova exposes a lightweight reachability-probe helper.

```java
PoolConfig config = PoolConfig.of(2, 10);   // initialSize, maxSize
PoolHealthProbe probe = new SimplePoolHealthProbe(connectionFactory);
Mono<PoolHealth> health = probe.probe();    // reachable / counter info
```

The probe performs an acquire / release smoke test using only `ConnectionFactory` — no `r2dbc-pool` dependency is required. Counters stay at zero; only `reachable` is meaningful.
