<!-- SPDX-License-Identifier: Apache-2.0 -->

# Observability

SQL 실행 관찰, 메트릭 수집, connection pool reachability를 위한 모듈과 hook입니다.

## SQL execution hook

모든 SQL 실행을 감시할 수 있는 `SqlExecutionListener` 인터페이스를 제공합니다. R2DBC adapter가 execute/query/batch 경로의 before/after/error를 발화합니다.

```java
SqlExecutionListener slowLog = new SlowQueryLoggingListener(Duration.ofMillis(100));
SqlExecutionListener composite = CompositeSqlExecutionListener.of(slowLog, otherListener);

R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect, composite);
```

`SlowQueryLoggingListener`는 threshold 이상 쿼리만 로깅하며 bindings는 PII 보호로 노출하지 않습니다. `onError`는 threshold와 무관하게 항상 로깅.

---

## Metrics (Micrometer)

`nova-metrics-micrometer` 모듈은 `SqlExecutionListener`를 구현한 `MicrometerSqlExecutionListener`를 제공합니다. 모든 execute / query / batch 경로에 대해 timer를 기록하고 실패 시 error counter를 함께 증가시킵니다.

```kotlin
// build.gradle.kts
implementation("io.github.bssm-oss:nova-metrics-micrometer:1.0.1")
implementation("io.micrometer:micrometer-core:1.14.0")
```

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.nova.metrics.micrometer.MicrometerSqlExecutionListener;

MeterRegistry registry = /* PrometheusMeterRegistry, SimpleMeterRegistry 등 */;

// 기본 prefix "nova.sql" 사용
SqlExecutionListener listener = new MicrometerSqlExecutionListener(registry);

// 또는 커스텀 prefix
SqlExecutionListener listener = new MicrometerSqlExecutionListener(registry, "app.db");

R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect, listener);
```

발행되는 meter:

| Meter                    | Type     | Tags                                              | 의미                              |
|--------------------------|----------|---------------------------------------------------|-----------------------------------|
| `<prefix>.query`         | Timer    | `outcome=success`                                  | 성공한 SQL 실행 elapsed time      |
| `<prefix>.query`         | Timer    | `outcome=error`, `exception=<SimpleName>`         | 실패한 SQL 실행 elapsed time      |
| `<prefix>.errors`        | Counter  | `outcome=error`, `exception=<SimpleName>`         | 실패 횟수                          |

> **Cardinality 주의**: 익명 lambda/anonymous class 예외의 경우 `getSimpleName()`이 빈 문자열이 되어 `exception=Anonymous`로 fallback됩니다. 사용자 정의 예외와 driver-wrapped 예외가 무한히 누적되는 시나리오에서는 Prometheus 등의 시계열 cardinality가 폭주할 수 있으므로 upstream에서 예외 wrapping을 제한하는 정책을 권장합니다.

다른 listener와 함께 쓰려면 `CompositeSqlExecutionListener.of(...)`로 합치세요.

---

## Connection pool (nova-r2dbc)

R2DBC pool 외에 reachability probe만 노출하는 가벼운 헬퍼를 제공합니다.

```java
PoolConfig config = PoolConfig.of(2, 10);   // initialSize, maxSize
PoolHealthProbe probe = new SimplePoolHealthProbe(connectionFactory);
Mono<PoolHealth> health = probe.probe();    // reachable / 카운터 정보
```

`r2dbc-pool` 의존성을 추가하지 않고 `ConnectionFactory`만 사용해 acquire/release smoke test를 수행합니다 (counters는 0, `reachable`만 의미).
