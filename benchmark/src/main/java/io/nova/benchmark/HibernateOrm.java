package io.nova.benchmark;

import io.nova.benchmark.entity.BenchUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hibernate ORM(JDBC + blocking) 구현. EntityManager 하나를 시나리오 전체에서 재사용하되, 쓰기 연산마다
 * 트랜잭션을 열고 읽기 후 {@code em.clear()}로 1차 캐시를 비워 매 조회가 실제 DB 왕복을 하도록 강제한다
 * (Nova는 세션 밖에서 1차 캐시가 없으므로 동일 조건을 맞춘다).
 */
final class HibernateOrm implements OrmBenchmark {

    private final EntityManagerFactory entityManagerFactory;
    private EntityManager em;

    HibernateOrm() {
        // hbm2ddl.auto=create가 EMF 초기화 시 스키마를 만든다.
        this.entityManagerFactory = Persistence.createEntityManagerFactory("bench");
    }

    @Override
    public String name() {
        return "Hibernate ORM (JDBC, blocking)";
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
            BenchUser user = em.find(BenchUser.class, id);
            if (user != null) {
                found++;
            }
            em.clear(); // 매 조회가 DB 왕복하도록 1차 캐시 제거
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
