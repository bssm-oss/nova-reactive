package io.nova.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nova(reactive/R2DBC) vs Hibernate ORM(blocking/JDBC) 단일 스레드 CRUD latency 프로파일러.
 * <p>
 * 두 ORM 모두 동일한 jakarta.persistence 엔티티({@link io.nova.benchmark.entity.BenchUser})를 H2 in-memory DB에
 * 대고 구동한다. in-memory라 네트워크/디스크 I/O가 제거되므로 측정값은 사실상 <b>ORM 매핑 + 드라이버 오버헤드</b>다.
 * 각 시나리오는 warmup 후 여러 번 측정해 중앙값을 취한다. 먼저 E2E 정확성 검증을 통과해야 측정에 들어간다.
 */
public final class BenchmarkRunner {

    private static final int N = Integer.getInteger("bench.n", 2000);
    private static final int WARMUP = Integer.getInteger("bench.warmup", 3);
    private static final int MEASURE = Integer.getInteger("bench.measure", 7);
    // 두 풀 모두 20이므로 동시성은 ≤20로 둔다(Hibernate 내장 풀은 풀 초과 시 큐잉 대신 예외를 던진다).
    private static final int CONCURRENCY = Integer.getInteger("bench.concurrency", 16);
    private static final int CONC_TOTAL = Integer.getInteger("bench.concTotal", 20_000);

    private static final String[] SCENARIOS = {"INSERT", "FIND_BY_ID", "FIND_ALL", "UPDATE", "DELETE"};

    public static void main(String[] args) {
        System.out.printf(Locale.ROOT,
                "Nova vs Hibernate ORM — single-thread CRUD on H2 in-memory%n"
                        + "N=%d ops/scenario, warmup=%d, measure=%d (median reported)%n%n",
                N, WARMUP, MEASURE);

        Map<String, Map<String, Double>> latency = new LinkedHashMap<>();
        Map<String, Double> concurrency = new LinkedHashMap<>();
        for (OrmBenchmark orm : List.of(new NovaOrm(), new HibernateOrm())) {
            try (orm) {
                orm.setupSchema();
                verify(orm);
                System.out.printf(Locale.ROOT, "[%s] E2E correctness OK — benchmarking...%n", orm.name());
                latency.put(orm.name(), benchmark(orm));
                // concurrency는 latency 측정(테이블 재생성 포함)이 끝난 뒤 새로 seed해 측정한다.
                List<Long> seed = reseed(orm);
                concurrency.put(orm.name(), orm.concurrentFindOpsPerSec(seed, CONCURRENCY, CONC_TOTAL));
            } catch (Exception exception) {
                throw new RuntimeException("Benchmark failed for " + orm.name(), exception);
            }
        }
        printTable(latency);
        printConcurrency(concurrency);
    }

    /**
     * E2E 정확성 검증 — 측정 전에 각 ORM이 올바른 CRUD 결과를 내는지 확인한다(벤치마크 겸 end-to-end 테스트).
     */
    private static void verify(OrmBenchmark orm) {
        orm.clear();
        List<Long> ids = orm.insert(10);
        require(ids.size() == 10, "insert returned " + ids.size() + " ids");
        require(orm.findAll() == 10, "findAll after insert");
        require(orm.findByIds(ids) == 10, "findByIds found count");
        orm.updateByIds(ids);
        require(orm.findAll() == 10, "findAll after update");
        orm.deleteByIds(ids);
        require(orm.findAll() == 0, "findAll after delete should be 0");
        orm.clear();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("E2E check failed: " + message);
        }
    }

    private static Map<String, Double> benchmark(OrmBenchmark orm) {
        Map<String, Double> medians = new LinkedHashMap<>();

        // INSERT: 매 반복마다 clear 후 N건 insert를 측정.
        medians.put("INSERT", median(() -> orm.clear(), () -> orm.insert(N)));

        // 읽기/수정: 한 번 seed 후 같은 행을 반복 조회/수정(read는 idempotent, update는 age 증가).
        List<Long> seed = reseed(orm);
        medians.put("FIND_BY_ID", median(() -> { }, () -> orm.findByIds(seed)));
        medians.put("FIND_ALL", median(() -> { }, () -> orm.findAll()));
        medians.put("UPDATE", median(() -> { }, () -> orm.updateByIds(seed)));

        // DELETE: 매 반복마다 reseed(untimed) 후 삭제를 측정.
        Holder<List<Long>> toDelete = new Holder<>();
        medians.put("DELETE", median(() -> toDelete.value = reseed(orm), () -> orm.deleteByIds(toDelete.value)));

        orm.clear();
        return medians;
    }

    private static List<Long> reseed(OrmBenchmark orm) {
        orm.clear();
        return orm.insert(N);
    }

    /**
     * setup(untimed) + timed 액션을 warmup 후 MEASURE번 실행해 측정 nanos의 중앙값(ms)을 반환한다.
     */
    private static double median(Runnable setup, Runnable timed) {
        for (int i = 0; i < WARMUP; i++) {
            setup.run();
            timed.run();
        }
        List<Long> samples = new ArrayList<>(MEASURE);
        for (int i = 0; i < MEASURE; i++) {
            setup.run();
            long start = System.nanoTime();
            timed.run();
            samples.add(System.nanoTime() - start);
        }
        samples.sort(Long::compareTo);
        long medianNanos = samples.get(samples.size() / 2);
        return medianNanos / 1_000_000.0;
    }

    private static void printTable(Map<String, Map<String, Double>> results) {
        List<String> orms = new ArrayList<>(results.keySet());
        System.out.printf(Locale.ROOT, "%n=== Results (median total ms for %d ops; lower is better) ===%n%n", N);
        System.out.printf(Locale.ROOT, "%-12s", "Scenario");
        for (String orm : orms) {
            System.out.printf(Locale.ROOT, " | %-28s", orm);
        }
        if (orms.size() == 2) {
            System.out.printf(Locale.ROOT, " | %-14s", "Nova / Hib");
        }
        System.out.println();

        for (String scenario : SCENARIOS) {
            System.out.printf(Locale.ROOT, "%-12s", scenario);
            for (String orm : orms) {
                double ms = results.get(orm).get(scenario);
                double opsPerSec = N / (ms / 1000.0);
                System.out.printf(Locale.ROOT, " | %9.2f ms (%8.0f/s)", ms, opsPerSec);
            }
            if (orms.size() == 2) {
                double a = results.get(orms.get(0)).get(scenario);
                double b = results.get(orms.get(1)).get(scenario);
                System.out.printf(Locale.ROOT, " | %.2fx", a / b);
            }
            System.out.println();
        }
        System.out.printf(Locale.ROOT,
                "%n'Nova / Hib' < 1.00 → Nova faster; > 1.00 → Hibernate faster.%n"
                        + "H2 in-memory isolates ORM+driver overhead (no I/O). Single-thread, autocommit-per-op.%n");
    }

    private static void printConcurrency(Map<String, Double> concurrency) {
        System.out.printf(Locale.ROOT,
                "%n=== Concurrent findById throughput (concurrency=%d, %d ops; higher is better) ===%n%n",
                CONCURRENCY, CONC_TOTAL);
        List<String> orms = new ArrayList<>(concurrency.keySet());
        for (String orm : orms) {
            System.out.printf(Locale.ROOT, "%-32s %12.0f ops/s%n", orm, concurrency.get(orm));
        }
        if (orms.size() == 2) {
            double nova = concurrency.get(orms.get(0));
            double hib = concurrency.get(orms.get(1));
            System.out.printf(Locale.ROOT, "%nNova throughput / Hibernate throughput = %.2fx%n", nova / hib);
        }
        System.out.printf(Locale.ROOT,
                "Nova keeps %d requests in-flight on a few event-loop threads; Hibernate uses %d blocking OS threads.%n",
                CONCURRENCY, CONCURRENCY);
    }

    private static final class Holder<T> {
        private T value;
    }

    private BenchmarkRunner() {
    }
}
