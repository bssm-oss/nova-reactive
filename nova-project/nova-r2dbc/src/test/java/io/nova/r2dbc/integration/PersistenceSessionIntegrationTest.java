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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
