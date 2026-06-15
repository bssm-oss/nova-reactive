package io.nova.benchmark;

import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nova(reactive/R2DBC) vs Hibernate ORM(blocking/JDBC) CRUD 프로파일러.
 * <p>
 * 두 backend로 동일 시나리오를 돌린다:
 * <ul>
 *   <li><b>H2 in-memory</b> — 네트워크/디스크 I/O가 없어 측정값이 곧 ORM 매핑+드라이버 오버헤드다.</li>
 *   <li><b>PostgreSQL(Testcontainers)</b> — 실제 소켓 왕복 지연이 있어, 적은 스레드로 다중 in-flight를
 *       유지하는 reactive 동시성 거동이 드러난다.</li>
 * </ul>
 * 두 ORM 모두 동일한 jakarta.persistence 엔티티({@link io.nova.benchmark.entity.BenchUser})를 사용하고,
 * 커넥션 풀은 20으로 맞춘다(Nova: r2dbc-pool / Hibernate: HikariCP). 각 시나리오는 warmup 후 median을 취하며,
 * 측정 전에 E2E 정확성 검증을 통과해야 한다.
 */
public final class BenchmarkRunner {

    private static final int N_H2 = Integer.getInteger("bench.n", 2000);
    private static final int N_PG = Integer.getInteger("bench.nPg", 500);
    private static final int WARMUP = Integer.getInteger("bench.warmup", 3);
    private static final int MEASURE = Integer.getInteger("bench.measure", 7);
    private static final int POOL = Integer.getInteger("bench.pool", 20);
    private static final int CONCURRENCY = Integer.getInteger("bench.concurrency", 64);
    private static final int CONC_TOTAL = Integer.getInteger("bench.concTotal", 20_000);

    private static final String[] SCENARIOS = {"INSERT", "FIND_BY_ID", "FIND_ALL", "UPDATE", "DELETE"};

    public static void main(String[] args) {
        runPhase("H2 in-memory — ORM overhead only (no I/O wait)",
                List.of(NovaOrm.h2(POOL), HibernateOrm.h2()), N_H2);

        System.out.printf(Locale.ROOT, "%n%n");
        runPostgresPhase();
    }

    // Postgres 단계: 외부 PG(-Dbench.pg.host) 우선, 없으면 Testcontainers, 둘 다 안되면 스킵.

    private static void runPostgresPhase() {
        // 1) 외부 PostgreSQL이 -Dbench.pg.host로 지정되면 그것을 쓴다(예: docker CLI로 띄운 컨테이너).
        String host = System.getProperty("bench.pg.host");
        if (host != null) {
            int port = Integer.getInteger("bench.pg.port", 5432);
            String db = System.getProperty("bench.pg.db", "bench");
            String user = System.getProperty("bench.pg.user", "bench");
            String password = System.getProperty("bench.pg.password", "bench");
            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
            runPhase("PostgreSQL (external) — real socket round-trips",
                    List.of(NovaOrm.postgres(host, port, db, user, password, POOL),
                            HibernateOrm.postgres(jdbcUrl, user, password),
                            HibernateReactiveOrm.postgres(jdbcUrl, user, password)),
                    N_PG);
            return;
        }
        // 2) 아니면 Testcontainers로 시도. Docker 환경이 없으면 스킵(전체 실행을 깨지 않는다).
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("bench").withUsername("bench").withPassword("bench")) {
            System.out.println("Starting PostgreSQL container (Testcontainers)...");
            pg.start();
            runPhase("PostgreSQL via Testcontainers — real socket round-trips",
                    List.of(NovaOrm.postgres(pg, POOL), HibernateOrm.postgres(pg),
                            HibernateReactiveOrm.postgres(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())),
                    N_PG);
        } catch (Throwable docker) {
            System.out.printf(Locale.ROOT,
                    "%n[PostgreSQL phase skipped] Docker/Testcontainers unavailable: %s%n"
                            + "Provide an external PG with -Dbench.pg.host=... (port/db/user/password optional).%n",
                    docker.getMessage());
        }
    }

    private static void runPhase(String label, List<OrmBenchmark> orms, int n) {
        System.out.printf(Locale.ROOT,
                "%n############################################################%n# %s%n"
                        + "# pool=%d, N=%d, warmup=%d, measure=%d, concurrency=%d (%d ops)%n"
                        + "############################################################%n%n",
                label, POOL, n, WARMUP, MEASURE, CONCURRENCY, CONC_TOTAL);

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Map<String, Map<String, Double>> latency = new LinkedHashMap<>();
        Map<String, ConcResult> concurrency = new LinkedHashMap<>();
        for (OrmBenchmark orm : orms) {
            try (orm) {
                orm.setupSchema();
                verify(orm);
                System.out.printf(Locale.ROOT, "[%s] E2E correctness OK — benchmarking...%n", orm.name());
                latency.put(orm.name(), benchmark(orm, n));
                List<Long> seed = reseed(orm, n);
                threads.resetPeakThreadCount();
                double opsPerSec = orm.concurrentFindOpsPerSec(seed, CONCURRENCY, CONC_TOTAL);
                concurrency.put(orm.name(), new ConcResult(opsPerSec, threads.getPeakThreadCount()));
            } catch (Exception exception) {
                throw new RuntimeException("Benchmark failed for " + orm.name(), exception);
            }
        }
        printTable(latency, n);
        printConcurrency(concurrency);
    }

    private record ConcResult(double opsPerSec, int peakThreads) {
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

    private static Map<String, Double> benchmark(OrmBenchmark orm, int n) {
        Map<String, Double> medians = new LinkedHashMap<>();
        medians.put("INSERT", median(orm::clear, () -> orm.insert(n)));

        List<Long> seed = reseed(orm, n);
        medians.put("FIND_BY_ID", median(() -> { }, () -> orm.findByIds(seed)));
        medians.put("FIND_ALL", median(() -> { }, orm::findAll));
        medians.put("UPDATE", median(() -> { }, () -> orm.updateByIds(seed)));

        Holder<List<Long>> toDelete = new Holder<>();
        medians.put("DELETE", median(() -> toDelete.value = reseed(orm, n), () -> orm.deleteByIds(toDelete.value)));

        orm.clear();
        return medians;
    }

    private static List<Long> reseed(OrmBenchmark orm, int n) {
        orm.clear();
        return orm.insert(n);
    }

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
        return samples.get(samples.size() / 2) / 1_000_000.0;
    }

    private static void printTable(Map<String, Map<String, Double>> results, int n) {
        List<String> orms = new ArrayList<>(results.keySet());
        System.out.printf(Locale.ROOT, "%n--- Latency (median total ms for %d ops; lower is better) ---%n%n", n);
        System.out.printf(Locale.ROOT, "%-12s", "Scenario");
        for (String orm : orms) {
            System.out.printf(Locale.ROOT, " | %-30s", orm);
        }
        if (orms.size() == 2) {
            System.out.printf(Locale.ROOT, " | %-12s", "Nova / Hib");
        }
        System.out.println();
        for (String scenario : SCENARIOS) {
            System.out.printf(Locale.ROOT, "%-12s", scenario);
            for (String orm : orms) {
                double ms = results.get(orm).get(scenario);
                System.out.printf(Locale.ROOT, " | %9.2f ms (%8.0f/s)", ms, n / (ms / 1000.0));
            }
            if (orms.size() == 2) {
                double a = results.get(orms.get(0)).get(scenario);
                double b = results.get(orms.get(1)).get(scenario);
                System.out.printf(Locale.ROOT, " | %.2fx", a / b);
            }
            System.out.println();
        }
        System.out.println("\n'Nova / Hib' < 1.00 → Nova faster; > 1.00 → Hibernate faster.");
    }

    private static void printConcurrency(Map<String, ConcResult> concurrency) {
        System.out.printf(Locale.ROOT,
                "%n--- Concurrent findById (concurrency=%d, %d ops) ---%n%n", CONCURRENCY, CONC_TOTAL);
        List<String> orms = new ArrayList<>(concurrency.keySet());
        System.out.printf(Locale.ROOT, "%-32s %14s %14s%n", "", "throughput", "peak threads");
        for (String orm : orms) {
            ConcResult result = concurrency.get(orm);
            System.out.printf(Locale.ROOT, "%-32s %10.0f ops/s %14d%n", orm, result.opsPerSec(), result.peakThreads());
        }
        ConcResult nova = concurrency.get(orms.get(0));
        System.out.printf(Locale.ROOT, "%n(vs Nova, sustaining %d concurrency)%n", CONCURRENCY);
        for (int i = 1; i < orms.size(); i++) {
            ConcResult other = concurrency.get(orms.get(i));
            System.out.printf(Locale.ROOT, "  Nova / %-34s throughput = %.2fx   threads: %d vs %d%n",
                    orms.get(i), nova.opsPerSec() / other.opsPerSec(), nova.peakThreads(), other.peakThreads());
        }
        System.out.printf(Locale.ROOT,
                "(Throughput is pool-bound at the shared %d-connection cap. The reactive engines (Nova,%n"
                        + " Hibernate Reactive) sustain the concurrency on a few event-loop threads; blocking%n"
                        + " Hibernate ORM needs ~one OS thread per in-flight request.)%n", POOL);
    }

    private static final class Holder<T> {
        private T value;
    }

    private BenchmarkRunner() {
    }
}
