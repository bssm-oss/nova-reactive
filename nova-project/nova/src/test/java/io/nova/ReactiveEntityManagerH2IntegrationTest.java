package io.nova;

import io.nova.core.ReactiveEntityManager;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReactiveEntityManager}(JPA EntityManager 리액티브 등가)가 production {@link Nova} 배선(managed
 * transaction) 위에서 실제 r2dbc-h2 driver와 end-to-end로 동작하는지 검증한다 — persist→find, merge→find,
 * remove, flush, refresh, detach/clear의 identity map/dirty 의미까지.
 *
 * <p>SQL string 단위 테스트만으로는 세션 auto-flush/롤백/identity 편입이 driver 위에서 성립하는지 검증할 수
 * 없으므로, 이 통합 테스트가 그 수용성을 고정한다.
 */
class ReactiveEntityManagerH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///reactiveem" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    private Mono<Void> createSchema(ConnectionFactory cf) {
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        return schema.create(Person.class);
    }

    @Test
    void persistThenFindRoundTripInTransaction() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf).then(em.inTransaction(e ->
                        e.persist(new Person("ada", 30))
                                .flatMap(saved -> {
                                    assertNotNull(saved.getId(), "IDENTITY id가 채워져야 한다");
                                    return e.find(Person.class, saved.getId());
                                })))
        ).assertNext(found -> {
            assertEquals("ada", found.getName());
            assertEquals(30, found.getAge());
        }).verifyComplete();
    }

    @Test
    void mergeUpsertsExistingRow() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("ben", 40)))
                        .flatMap(saved -> {
                            Person changed = new Person("ben franklin", 41);
                            changed.setId(saved.getId());
                            return em.merge(changed).thenReturn(saved.getId());
                        })
                        .flatMap(id -> em.find(Person.class, id))
        ).assertNext(found -> {
            assertEquals("ben franklin", found.getName());
            assertEquals(41, found.getAge());
        }).verifyComplete();
    }

    @Test
    void removeDeletesRow() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("cleo", 25)))
                        .flatMap(saved -> em.remove(saved).thenReturn(saved.getId()))
                        .flatMap(id -> em.find(Person.class, id))
        ).verifyComplete(); // find는 빈 Mono
    }

    @Test
    void dirtyChangeFlushesAtCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("dan", 50)))
                        .flatMap(saved -> em.inTransaction(e ->
                                        e.find(Person.class, saved.getId())
                                                .doOnNext(p -> p.setName("daniel")))
                                .thenReturn(saved.getId()))
                        .flatMap(id -> em.find(Person.class, id))
        ).assertNext(found -> assertEquals("daniel", found.getName(),
                "managed 엔티티 수정은 commit 시 dirty flush로 반영돼야 한다")).verifyComplete();
    }

    @Test
    void explicitFlushPushesChangeBeforeCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("eve", 60)))
                        .flatMap(saved -> em.inTransaction(e ->
                                        e.find(Person.class, saved.getId())
                                                .flatMap(p -> {
                                                    p.setName("evelyn");
                                                    // flush로 UPDATE를 즉시 발행한 뒤 detach하면 commit-flush는 무시한다.
                                                    // detach 후 find는 세션에 아무 것도 없으므로 DB(=flush 결과)를 그대로 읽는다.
                                                    return e.flush()
                                                            .then(e.detach(p))
                                                            .then(e.find(Person.class, saved.getId()));
                                                })))
        ).assertNext(reloaded -> assertEquals("evelyn", reloaded.getName(),
                "flush는 commit 전에 변경을 DB로 밀어내야 한다")).verifyComplete();
    }

    @Test
    void refreshDiscardsLocalChanges() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("finn", 20)))
                        .flatMap(saved -> em.inTransaction(e ->
                                e.find(Person.class, saved.getId())
                                        .flatMap(p -> {
                                            p.setName("locally-changed");
                                            return e.refresh(p);
                                        })))
        ).assertNext(refreshed -> assertEquals("finn", refreshed.getName(),
                "refresh는 로컬 변경을 폐기하고 DB 상태로 재적재해야 한다")).verifyComplete();
    }

    @Test
    void refreshedEntityDoesNotWriteDiscardedChangeAtCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("gwen", 22)))
                        .flatMap(saved -> em.inTransaction(e ->
                                        e.find(Person.class, saved.getId())
                                                .flatMap(p -> {
                                                    p.setName("throwaway");
                                                    return e.refresh(p);
                                                }))
                                .thenReturn(saved.getId()))
                        .flatMap(id -> em.find(Person.class, id))
        ).assertNext(found -> assertEquals("gwen", found.getName(),
                "refresh 후 clean snapshot이라 commit flush가 폐기된 변경을 쓰지 않아야 한다")).verifyComplete();
    }

    @Test
    void detachPreventsCommitFlush() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("hank", 33)))
                        .flatMap(saved -> em.inTransaction(e ->
                                        e.find(Person.class, saved.getId())
                                                .flatMap(p -> {
                                                    p.setName("won't-persist");
                                                    return e.detach(p);
                                                }))
                                .thenReturn(saved.getId()))
                        .flatMap(id -> em.find(Person.class, id))
        ).assertNext(found -> assertEquals("hank", found.getName(),
                "detach된 엔티티의 변경은 commit flush에서 제외돼야 한다")).verifyComplete();
    }

    @Test
    void clearPreventsCommitFlush() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("iris", 44)))
                        .flatMap(saved -> em.inTransaction(e ->
                                        e.find(Person.class, saved.getId())
                                                .flatMap(p -> {
                                                    p.setName("won't-persist");
                                                    return e.clear();
                                                }))
                                .thenReturn(saved.getId()))
                        .flatMap(id -> em.find(Person.class, id))
        ).assertNext(found -> assertEquals("iris", found.getName(),
                "clear는 모든 managed 엔티티를 detach하므로 변경이 commit되지 않아야 한다")).verifyComplete();
    }

    @Test
    void identityMapReturnsSameInstanceAndContainsIsTrue() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("jane", 55)))
                        .flatMap(saved -> em.inTransaction(e ->
                                e.find(Person.class, saved.getId())
                                        .flatMap(first -> e.find(Person.class, saved.getId())
                                                .flatMap(second -> e.contains(first)
                                                        .map(managed -> first == second && managed)))))
        ).expectNext(Boolean.TRUE).verifyComplete();
    }

    @Test
    void rollbackRevertsDirtyChange() {
        ConnectionFactory cf = freshConnectionFactory();
        ReactiveEntityManager em = Nova.entityManager(cf);

        StepVerifier.create(
                createSchema(cf)
                        .then(em.persist(new Person("kurt", 66)))
                        .flatMap(saved -> em.inTransaction(e ->
                                        e.find(Person.class, saved.getId())
                                                .flatMap(p -> {
                                                    p.setName("kurtis");
                                                    return e.flush()
                                                            .then(Mono.<Person>error(new RuntimeException("boom")));
                                                }))
                                .onErrorResume(ex -> Mono.empty())
                                .thenReturn(saved.getId()))
                        .flatMap(id -> em.find(Person.class, id))
        ).assertNext(found -> assertEquals("kurt", found.getName(),
                "롤백 후 flush된 변경도 원복돼야 한다")).verifyComplete();
    }

    @Entity
    @Table(name = "em_person")
    public static class Person {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        private int age;

        public Person() {
        }

        public Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }
}
