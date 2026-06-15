package io.nova.benchmark;

import io.nova.benchmark.entity.BenchUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hibernate ORM(JDBC + blocking) 구현. backend별 jdbc 접속 정보를 런타임에 주입해 EMF를 만든다(스키마는
 * hbm2ddl=create). EntityManager 하나를 시나리오 전체에서 재사용하되 쓰기마다 트랜잭션을 열고 읽기 후
 * {@code em.clear()}로 1차 캐시를 비워 매 조회가 실제 DB 왕복을 하도록 강제한다.
 */
final class HibernateOrm implements OrmBenchmark {

    private final String name;
    private final EntityManagerFactory entityManagerFactory;
    private EntityManager em;

    private HibernateOrm(String name, Map<String, Object> jdbcProperties) {
        this.name = name;
        this.entityManagerFactory = Persistence.createEntityManagerFactory("bench", jdbcProperties);
    }

    static HibernateOrm h2() {
        return new HibernateOrm("Hibernate ORM (JDBC, blocking)",
                jdbc("jdbc:h2:mem:hibbench;DB_CLOSE_DELAY=-1", "org.h2.Driver", "sa", ""));
    }

    static HibernateOrm postgres(String jdbcUrl, String user, String password) {
        return new HibernateOrm("Hibernate ORM (JDBC, blocking)",
                jdbc(jdbcUrl, "org.postgresql.Driver", user, password));
    }

    static HibernateOrm postgres(PostgreSQLContainer<?> container) {
        return postgres(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static Map<String, Object> jdbc(String url, String driver, String user, String password) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("jakarta.persistence.jdbc.url", url);
        properties.put("jakarta.persistence.jdbc.driver", driver);
        properties.put("jakarta.persistence.jdbc.user", user);
        properties.put("jakarta.persistence.jdbc.password", password);
        return properties;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setupSchema() {
        this.em = entityManagerFactory.createEntityManager();
    }

    @Override
    public void clear() {
        em.getTransaction().begin();
        em.createQuery("delete from BenchUser").executeUpdate();
        em.getTransaction().commit();
        em.clear();
    }

    @Override
    public List<Long> insert(int n) {
        List<Long> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BenchUser user = new BenchUser("user" + i, "user" + i + "@nova.io", 20 + (i % 50));
            em.getTransaction().begin();
            em.persist(user);
            em.getTransaction().commit();
            ids.add(user.getId());
        }
        em.clear();
        return ids;
    }

    @Override
    public int findByIds(List<Long> ids) {
        int found = 0;
        for (Long id : ids) {
            if (em.find(BenchUser.class, id) != null) {
                found++;
            }
            em.clear();
        }
        return found;
    }

    @Override
    public int findAll() {
        List<BenchUser> all = em.createQuery("from BenchUser", BenchUser.class).getResultList();
        em.clear();
        return all.size();
    }

    @Override
    public void updateByIds(List<Long> ids) {
        for (Long id : ids) {
            em.getTransaction().begin();
            BenchUser user = em.find(BenchUser.class, id);
            user.setAge(user.getAge() + 1);
            em.getTransaction().commit();
            em.clear();
        }
    }

    @Override
    public void deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            em.getTransaction().begin();
            BenchUser user = em.find(BenchUser.class, id);
            if (user != null) {
                em.remove(user);
            }
            em.getTransaction().commit();
            em.clear();
        }
    }

    @Override
    public double concurrentFindOpsPerSec(List<Long> ids, int concurrency, int totalOps) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger counter = new AtomicInteger();
        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>(concurrency);
        for (int t = 0; t < concurrency; t++) {
            futures.add(pool.submit(() -> {
                // EntityManager는 thread-safe하지 않으므로 워커마다 자기 EM을 연다(blocking 동시 실행).
                EntityManager localEm = entityManagerFactory.createEntityManager();
                try {
                    int i;
                    while ((i = counter.getAndIncrement()) < totalOps) {
                        localEm.find(BenchUser.class, ids.get(i % ids.size()));
                        localEm.clear();
                    }
                } finally {
                    localEm.close();
                }
            }));
        }
        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        } finally {
            pool.shutdown();
        }
        long elapsed = System.nanoTime() - start;
        return totalOps / (elapsed / 1_000_000_000.0);
    }

    @Override
    public void close() {
        if (em != null && em.isOpen()) {
            em.close();
        }
        entityManagerFactory.close();
    }
}
