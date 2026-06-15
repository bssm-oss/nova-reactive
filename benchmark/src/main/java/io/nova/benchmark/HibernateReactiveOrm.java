package io.nova.benchmark;

import io.nova.benchmark.entity.BenchUser;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.hibernate.reactive.stage.Stage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hibernate Reactive(Vert.x, 논블로킹) 구현 — Nova와의 진짜 reactive-vs-reactive 비교용. JDBC가 아니라
 * Vert.x reactive PG 클라이언트를 쓰므로 적은 event-loop 스레드로 다중 in-flight를 처리한다(blocking
 * Hibernate ORM과 대비). PostgreSQL 전용(Vert.x에 H2 클라이언트가 없음).
 * <p>
 * {@link OrmBenchmark}의 동기 경계를 맞추기 위해 단건 연산은 {@code CompletionStage}를 {@code join()}하고,
 * 동시성 시나리오는 Reactor로 bounded-concurrency를 부여한다(Nova의 flatMap과 동일 방식).
 */
final class HibernateReactiveOrm implements OrmBenchmark {

    private final EntityManagerFactory entityManagerFactory;
    private final Stage.SessionFactory sessionFactory;

    private HibernateReactiveOrm(String jdbcUrl, String user, String password) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("jakarta.persistence.jdbc.url", jdbcUrl);
        properties.put("jakarta.persistence.jdbc.user", user);
        properties.put("jakarta.persistence.jdbc.password", password);
        this.entityManagerFactory = Persistence.createEntityManagerFactory("bench-reactive", properties);
        this.sessionFactory = entityManagerFactory.unwrap(Stage.SessionFactory.class);
    }

    static HibernateReactiveOrm postgres(String jdbcUrl, String user, String password) {
        return new HibernateReactiveOrm(jdbcUrl, user, password);
    }

    @Override
    public String name() {
        return "Hibernate Reactive (Vert.x, non-blocking)";
    }

    @Override
    public void setupSchema() {
        // hbm2ddl=create가 SessionFactory 부트스트랩 시 스키마를 만든다(별도 작업 불필요).
    }

    @Override
    public void clear() {
        sessionFactory.withTransaction((session, tx) ->
                session.createMutationQuery("delete from BenchUser").executeUpdate())
                .toCompletableFuture().join();
    }

    @Override
    public List<Long> insert(int n) {
        List<Long> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BenchUser user = new BenchUser("user" + i, "user" + i + "@nova.io", 20 + (i % 50));
            sessionFactory.withTransaction((session, tx) -> session.persist(user)).toCompletableFuture().join();
            ids.add(user.getId());
        }
        return ids;
    }

    @Override
    public int findByIds(List<Long> ids) {
        int found = 0;
        for (Long id : ids) {
            BenchUser user = sessionFactory.withSession(session -> session.find(BenchUser.class, id))
                    .toCompletableFuture().join();
            if (user != null) {
                found++;
            }
        }
        return found;
    }

    @Override
    public int findAll() {
        List<BenchUser> all = sessionFactory.withSession(session ->
                        session.createSelectionQuery("from BenchUser", BenchUser.class).getResultList())
                .toCompletableFuture().join();
        return all.size();
    }

    @Override
    public void updateByIds(List<Long> ids) {
        for (Long id : ids) {
            sessionFactory.withTransaction((session, tx) ->
                            session.find(BenchUser.class, id).thenAccept(user -> user.setAge(user.getAge() + 1)))
                    .toCompletableFuture().join();
        }
    }

    @Override
    public void deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            sessionFactory.withTransaction((session, tx) ->
                            session.find(BenchUser.class, id)
                                    .thenCompose(user -> user == null
                                            ? CompletableFuture.completedFuture(null)
                                            : session.remove(user)))
                    .toCompletableFuture().join();
        }
    }

    @Override
    public double concurrentFindOpsPerSec(List<Long> ids, int concurrency, int totalOps) {
        long start = System.nanoTime();
        // concurrency개를 in-flight로 유지하며 HR의 CompletionStage 기반 find를 totalOps번 실행(논블로킹).
        Flux.range(0, totalOps)
                .flatMap(i -> Mono.fromCompletionStage(() ->
                        sessionFactory.withSession(session ->
                                session.find(BenchUser.class, ids.get(i % ids.size())))), concurrency)
                .then()
                .block();
        long elapsed = System.nanoTime() - start;
        return totalOps / (elapsed / 1_000_000_000.0);
    }

    @Override
    public void close() {
        sessionFactory.close();
        entityManagerFactory.close();
    }
}
