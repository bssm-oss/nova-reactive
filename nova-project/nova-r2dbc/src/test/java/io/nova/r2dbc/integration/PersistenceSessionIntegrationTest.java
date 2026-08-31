package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import io.nova.core.SqlExecutionListener;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import io.nova.tx.Propagation;
import io.nova.tx.TransactionDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 트랜잭션 바인딩 영속성 세션(identity map + 스냅샷 dirty checking + flush)이 H2 in-memory R2DBC driver와
 * end-to-end로 동작하는지 검증한다. {@code inTransaction} 안에서 로드한 엔티티를 수정하면 commit 직전
 * auto-flush가 변경된 컬럼만 부분 UPDATE로 발행해야 한다.
 */
class PersistenceSessionIntegrationTest {
    private CapturingListener listener;
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        listener = new CapturingListener();
        support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Person.class, VersionedPerson.class).block();
    }

    @Test
    void loadModifyCommitIssuesPartialUpdate() {
        Long id = support.operations().save(new Person("ada", 30)).map(Person::getId).block();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Person.class, id).flatMap(person -> {
                            person.setName("ada lovelace"); // age는 그대로
                            return ops.save(person);
                        })))
                .expectNextCount(1)
                .verifyComplete();

        // 세션 flush가 변경된 컬럼만 담은 부분 UPDATE 1건을 발행해야 한다.
        List<String> updates = listener.updates();
        assertEquals(1, updates.size(), "updates=" + updates);
        assertTrue(updates.get(0).contains("name"), updates.get(0));
        assertTrue(!updates.get(0).contains("age"), "부분 UPDATE는 변경되지 않은 age를 SET하지 않아야 한다: " + updates.get(0));

        // commit 후 변경이 반영됐는지 fresh 조회로 확인.
        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals("ada lovelace", person.getName()))
                .verifyComplete();
    }

    @Test
    void noMutationIssuesNoUpdate() {
        Long id = support.operations().save(new Person("ben", 40)).map(Person::getId).block();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Person.class, id)))
                .expectNextCount(1)
                .verifyComplete();

        assertTrue(listener.updates().isEmpty(), "변경 없으면 UPDATE를 내지 않아야 한다: " + listener.updates());
    }

    @Test
    void identityGuaranteeAcrossTwoFindsInOneTransaction() {
        Long id = support.operations().save(new Person("cleo", 25)).map(Person::getId).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Person.class, id)
                                .flatMap(first -> ops.findById(Person.class, id)
                                        .map(second -> first == second))))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void autoFlushMakesChangeVisibleToLaterQueryInSameTransaction() {
        Long id = support.operations().save(new Person("dan", 50)).map(Person::getId).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Person.class, id)
                                .flatMap(person -> {
                                    person.setName("daniel");
                                    return ops.save(person);
                                })
                                .then(ops.findAll(Person.class,
                                        QuerySpec.empty().where(Criteria.eq("name", "daniel"))).collectList())))
                .assertNext(matches -> assertEquals(1, matches.size(),
                        "auto-flush가 변경을 SELECT 전에 반영해야 한다"))
                .verifyComplete();
    }

    @Test
    void rollbackRevertsDirtyChange() {
        Long id = support.operations().save(new Person("eve", 60)).map(Person::getId).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Person.class, id)
                                .flatMap(person -> {
                                    person.setName("evelyn");
                                    return ops.save(person);
                                })
                                .then(Mono.error(new RuntimeException("boom")))))
                .verifyErrorMessage("boom");

        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals("eve", person.getName(), "롤백 후 변경이 원복돼야 한다"))
                .verifyComplete();
    }

    @Test
    void versionIncrementsOnFlush() {
        Long id = support.operations().save(new VersionedPerson("finn")).map(VersionedPerson::getId).block();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(VersionedPerson.class, id).flatMap(person -> {
                            person.setName("finnegan");
                            return ops.save(person);
                        })))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(VersionedPerson.class, id))
                .assertNext(person -> {
                    assertEquals("finnegan", person.getName());
                    assertEquals(1L, person.getVersion(), "@Version은 flush UPDATE에서 1 증가해야 한다");
                })
                .verifyComplete();
    }

    @Test
    void separateRequiredOperationWrappersShareRawPhysicalTransactionSession() {
        Long id = support.operations().save(new Person("shared", 29)).map(Person::getId).block();
        AtomicReference<Person> first = new AtomicReference<>();
        listener.clear();

        StepVerifier.create(support.transactionManager().inTransaction(TransactionDefinition.DEFAULT, ignored ->
                        support.operations().inTransaction(firstOps ->
                                        firstOps.findById(Person.class, id).doOnNext(person -> {
                                            first.set(person);
                                            person.setName("changed once");
                                        }))
                                .then(support.operations().inTransaction(secondOps ->
                                        secondOps.findById(Person.class, id).doOnNext(person ->
                                                assertSame(first.get(), person))))))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, listener.updates().size(),
                "one physical transaction must register and run exactly one session flush");
        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals("changed once", person.getName()))
                .verifyComplete();
    }

    @Test
    void nestedSavepointSharesOuterSessionAndDoesNotRewindManagedState() {
        Long id = support.operations().save(new Person("original", 28)).map(Person::getId).block();
        AtomicReference<Person> outer = new AtomicReference<>();

        StepVerifier.create(support.operations().inTransaction(outerOps ->
                        outerOps.findById(Person.class, id).flatMap(person -> {
                            outer.set(person);
                            person.setName("outer pending");
                            return support.transactionManager().inTransaction(
                                            TransactionDefinition.DEFAULT.with(Propagation.NESTED), ignored ->
                                                    support.operations().findById(Person.class, id)
                                                            .doOnNext(nested -> {
                                                                assertSame(person, nested);
                                                                nested.setName("nested pending");
                                                            })
                                                            .then(Mono.error(new IllegalStateException("rollback savepoint"))))
                                    .onErrorResume(IllegalStateException.class, ignored -> Mono.empty());
                        }).then(outerOps.findById(Person.class, id).doOnNext(restored -> {
                            assertSame(outer.get(), restored);
                            assertEquals("nested pending", restored.getName(),
                                    "savepoint rollback does not rewind managed Java state");
                        }))))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals("nested pending", person.getName()))
                .verifyComplete();
    }

    @Test
    void requiresNewUsesIndependentSessionAndRestoresOuterDirtyIdentity() {
        Long id = support.operations().save(new Person("original", 30)).map(Person::getId).block();
        AtomicReference<Person> outer = new AtomicReference<>();
        AtomicReference<Person> inner = new AtomicReference<>();

        StepVerifier.create(support.operations().inTransaction(outerOps ->
                        outerOps.findById(Person.class, id).flatMap(outerPerson -> {
                            outer.set(outerPerson);
                            outerPerson.setName("outer pending");
                            return support.transactionManager().inTransaction(
                                    TransactionDefinition.requiresNew(), ignored ->
                                            support.operations().findById(Person.class, id).flatMap(first ->
                                                    support.operations().findById(Person.class, id).map(second -> {
                                                        assertSame(first, second);
                                                        assertNotSame(outerPerson, first);
                                                        inner.set(first);
                                                        first.setName("inner committed");
                                                        return first;
                                                    })));
                        }).then(support.transactionManager().inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED), ignored ->
                                        support.operations().findById(Person.class, id).doOnNext(committed ->
                                                assertEquals("inner committed", committed.getName()))))
                                .then(outerOps.findById(Person.class, id).doOnNext(restored -> {
                                    assertSame(outer.get(), restored);
                                    assertNotSame(outer.get(), inner.get());
                                    assertEquals("outer pending", restored.getName());
                                }))))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals("outer pending", person.getName()))
                .verifyComplete();
    }

    @Test
    void requiresNewRollbackEmptyCompletionAndCancellationDoNotLeakSessionState() {
        Long rollbackId = support.operations().save(new Person("rollback original", 31)).map(Person::getId).block();
        AtomicReference<Person> rollbackOuter = new AtomicReference<>();

        StepVerifier.create(support.operations().inTransaction(outerOps ->
                        outerOps.findById(Person.class, rollbackId).flatMap(outer -> {
                            rollbackOuter.set(outer);
                            outer.setName("outer rollback commit");
                            return support.transactionManager().inTransaction(
                                            TransactionDefinition.requiresNew(), ignored ->
                                                    support.operations().findById(Person.class, rollbackId)
                                                            .doOnNext(person -> person.setName("inner rolled back"))
                                                            .then(Mono.error(new IllegalStateException("inner rollback"))))
                                    .onErrorResume(IllegalStateException.class, ignored -> Mono.empty());
                        }).then(support.transactionManager().inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED), ignored ->
                                        support.operations().findById(Person.class, rollbackId).doOnNext(committed ->
                                                assertEquals("rollback original", committed.getName()))))
                                .then(outerOps.findById(Person.class, rollbackId).doOnNext(restored -> {
                                    assertSame(rollbackOuter.get(), restored);
                                    assertEquals("outer rollback commit", restored.getName());
                                }))))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Person.class, rollbackId))
                .assertNext(person -> assertEquals("outer rollback commit", person.getName()))
                .verifyComplete();

        Long emptyId = support.operations().save(new Person("empty original", 32)).map(Person::getId).block();
        StepVerifier.create(support.operations().inTransaction(outerOps ->
                        support.transactionManager().inTransaction(
                                TransactionDefinition.requiresNew(), ignored ->
                                        support.operations().findById(Person.class, emptyId)
                                                .doOnNext(person -> person.setName("empty committed"))
                                                .then())))
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Person.class, emptyId))
                .assertNext(person -> assertEquals("empty committed", person.getName()))
                .verifyComplete();

        Long cancelId = support.operations().save(new Person("cancel original", 33)).map(Person::getId).block();
        AtomicReference<Person> cancelOuter = new AtomicReference<>();
        StepVerifier.create(support.operations().inTransaction(outerOps ->
                        outerOps.findById(Person.class, cancelId).flatMap(outer -> {
                            cancelOuter.set(outer);
                            outer.setName("outer after cancel");
                            return support.transactionManager().inTransaction(
                                            TransactionDefinition.requiresNew(), ignored ->
                                                    support.operations().findById(Person.class, cancelId)
                                                            .doOnNext(person -> person.setName("cancelled inner"))
                                                            .then(Mono.never()))
                                    .timeout(Duration.ofMillis(100))
                                    .onErrorResume(TimeoutException.class, ignored -> Mono.empty());
                        }).then(support.transactionManager().inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED), ignored ->
                                        support.operations().findById(Person.class, cancelId).doOnNext(committed ->
                                                assertEquals("cancel original", committed.getName()))))
                                .then(outerOps.findById(Person.class, cancelId).doOnNext(restored -> {
                                    assertSame(cancelOuter.get(), restored);
                                    assertEquals("outer after cancel", restored.getName());
                                }))))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Person.class, cancelId))
                .assertNext(person -> assertEquals("outer after cancel", person.getName()))
                .verifyComplete();
    }

    @Test
    void notSupportedIsStatelessUntilExplicitAutocommitSaveAndRestoresOuterSession() {
        Long id = support.operations().save(new Person("committed", 34)).map(Person::getId).block();
        AtomicReference<Person> outer = new AtomicReference<>();
        AtomicReference<Person> firstRead = new AtomicReference<>();

        StepVerifier.create(support.operations().inTransaction(outerOps ->
                        outerOps.findById(Person.class, id).flatMap(outerPerson -> {
                            outer.set(outerPerson);
                            outerPerson.setName("outer pending");
                            return support.transactionManager().inTransaction(
                                    TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED), ignored ->
                                            support.operations().findById(Person.class, id).flatMap(first ->
                                                    support.operations().findById(Person.class, id).flatMap(second -> {
                                                        firstRead.set(first);
                                                        assertNotSame(first, second);
                                                        assertEquals("committed", first.getName());
                                                        first.setName("ignored mutation");
                                                        return support.operations().findById(Person.class, id)
                                                                .doOnNext(third -> assertEquals("committed", third.getName()))
                                                                .then(support.operations().save(new Person("autocommit", 35)));
                                                    })));
                        }).then(outerOps.findById(Person.class, id).doOnNext(restored -> {
                            assertSame(outer.get(), restored);
                            assertNotSame(outer.get(), firstRead.get());
                            assertEquals("outer pending", restored.getName());
                        }))))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals("outer pending", person.getName()))
                .verifyComplete();
        StepVerifier.create(support.operations().findAll(Person.class,
                        QuerySpec.empty().where(Criteria.eq("name", "autocommit"))).collectList())
                .assertNext(people -> assertEquals(1, people.size()))
                .verifyComplete();
    }

    private static final class CapturingListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void clear() {
            statements.clear();
        }

        List<String> updates() {
            List<String> result = new ArrayList<>();
            for (String sql : statements) {
                if (sql.toLowerCase(Locale.ROOT).startsWith("update")) {
                    result.add(sql);
                }
            }
            return result;
        }
    }

    @Entity
    @Table(name = "person")
    public static class Person {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        private Integer age;

        public Person() {
        }

        public Person(String name, Integer age) {
            this.name = name;
            this.age = age;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }
    }

    @Entity
    @Table(name = "versioned_person")
    public static class VersionedPerson {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @Version
        private Long version;

        public VersionedPerson() {
        }

        public VersionedPerson(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getVersion() {
            return version;
        }
    }
}
