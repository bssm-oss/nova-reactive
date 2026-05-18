package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.support.fixtures.FixtureEntities.AssignedIdAccount;
import io.nova.support.fixtures.FixtureEntities.NoDefaultConstructorEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.SampleOrder;
import io.nova.tx.ReactiveTransactionOperations;
import io.nova.tx.TransactionContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleReactiveEntityOperationsTest {
    @Test
    void saveUsesInsertWithGeneratedKeyForNewIdentityEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.generatedKey = 42L;
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SampleAccount account = new SampleAccount(null, "new@nova.io", true);

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> saved == account && Objects.equals(saved.getId(), 42L))
                .verifyComplete();

        assertEquals(
                "insert into accounts (email_address, active) values (?, ?)",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("new@nova.io", true), executor.lastStatement.bindings());
        assertEquals("id", executor.lastGeneratedIdColumn);
        assertEquals(Long.class, executor.lastGeneratedIdType);
    }

    @Test
    void saveUsesPlainUpdateForAssignedIdEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        AssignedIdAccount account = new AssignedIdAccount(99L, "assigned@nova.io");

        StepVerifier.create(operations.save(account))
                .expectNext(account)
                .verifyComplete();

        assertEquals(
                "update assigned_accounts set email_address = ? where id = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("assigned@nova.io", 99L), executor.lastStatement.bindings());
        assertEquals(0, executor.generatedKeyCalls);
    }

    @Test
    void saveUsesUpdateForExistingEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SampleAccount account = new SampleAccount(7L, "updated@nova.io", false);

        StepVerifier.create(operations.save(account))
                .expectNext(account)
                .verifyComplete();

        assertEquals(
                "update accounts set email_address = ?, active = ? where id = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("updated@nova.io", false, 7L), executor.lastStatement.bindings());
    }

    @Test
    void updateRunsPartialUpdateForExistingEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SampleAccount account = new SampleAccount(7L, "x@nova.io", true);

        StepVerifier.create(operations.update(account, List.of("email")))
                .expectNext(account)
                .verifyComplete();

        assertEquals(1, executor.executedStatements.size());
        SqlStatement statement = executor.executedStatements.get(0);
        assertEquals("update accounts set email_address = ? where id = ?", statement.sql());
        assertEquals(List.of("x@nova.io", 7L), statement.bindings());
        assertEquals(0, executor.generatedKeyCalls, "partial update는 generated key 경로를 거치지 않아야 한다");
    }

    @Test
    void updateRejectsNullEntityId() {
        SimpleReactiveEntityOperations operations = newOperations(new CapturingExecutor(), new RecordingTransactions());

        StepVerifier.create(operations.update(new SampleAccount(null, "x@nova.io", true), List.of("email")))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertEquals("Entity id must not be null for update", error.getMessage());
                })
                .verify();
    }

    @Test
    void updatePropagatesRendererRejectionForEmptyFields() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SampleAccount account = new SampleAccount(7L, "x@nova.io", true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> operations.update(account, List.of())
        );

        assertEquals("update requires at least one field", exception.getMessage());
        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void updatePropagatesRendererRejectionForIdField() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SampleAccount account = new SampleAccount(7L, "x@nova.io", true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> operations.update(account, List.of("id"))
        );

        assertEquals("Cannot update id property: id", exception.getMessage());
        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void findByIdBuildsSelectAndMapsRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("id", 7L, "email_address", "a@nova.io", "active", true)));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.findById(SampleAccount.class, 7L))
                .expectNextMatches(account -> Objects.equals(account.getId(), 7L)
                        && Objects.equals(account.getEmail(), "a@nova.io")
                        && account.isActive())
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where id = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(7L), executor.lastStatement.bindings());
    }

    @Test
    void findAllBuildsSelectAndMapsRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 7L, "email_address", "a@nova.io", "active", true))
        ));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        QuerySpec spec = QuerySpec.empty()
                .where(Criteria.eq("email", "a@nova.io"))
                .orderBy(Sort.by(Sort.Order.asc("id")))
                .page(Pageable.of(10, 20));

        StepVerifier.create(operations.findAll(SampleAccount.class, spec))
                .expectNextMatches(account -> Objects.equals(account.getId(), 7L) && Objects.equals(account.getEmail(), "a@nova.io"))
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where email_address = ? order by id asc limit ? offset ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("a@nova.io", 10, 20L), executor.lastStatement.bindings());
    }

    @Test
    void findAllByIdUsesSingleInSelect() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 10L, "email_address", "a@nova.io", "active", true)),
                new MapRowAccessor(Map.of("id", 20L, "email_address", "b@nova.io", "active", false)),
                new MapRowAccessor(Map.of("id", 30L, "email_address", "c@nova.io", "active", true))
        ));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.findAllById(SampleAccount.class, List.of(10L, 20L, 30L)))
                .expectNextCount(3)
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where id in (?, ?, ?)",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(10L, 20L, 30L), executor.lastStatement.bindings());
    }

    @Test
    void findAllByIdOnEmptyInputShortCircuits() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.findAllById(SampleAccount.class, List.<Long>of()))
                .verifyComplete();

        assertTrue(executor.executedStatements.isEmpty());
        assertEquals(null, executor.lastStatement, "queryMany는 호출되지 않아야 한다");
    }

    @Test
    void countReadsCountAlias() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("count", 3L)));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.count(SampleAccount.class, QuerySpec.empty().where(Criteria.isNotNull("email"))))
                .expectNext(3L)
                .verifyComplete();

        assertEquals("select count(*) as count from accounts where email_address is not null", executor.lastStatement.sql());
    }

    @Test
    void existsUsesRowPresence() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("exists", true)));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.exists(SampleAccount.class, QuerySpec.empty().where(Criteria.eq("email", "a@nova.io"))))
                .expectNext(true)
                .verifyComplete();

        assertEquals("select 1 from accounts where email_address = ? limit 1", executor.lastStatement.sql());
    }

    @Test
    void existsReturnsFalseWhenNoRowMatches() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.emptyQueryOne = true;
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.exists(SampleAccount.class, QuerySpec.empty()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void deleteUsesEntityId() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.delete(new SampleAccount(9L, "a@nova.io", true)))
                .expectNext(1L)
                .verifyComplete();

        assertEquals("delete from accounts where id = ?", executor.lastStatement.sql());
        assertEquals(List.of(9L), executor.lastStatement.bindings());
    }

    @Test
    void deleteRejectsNullEntityId() {
        SimpleReactiveEntityOperations operations = newOperations(new CapturingExecutor(), new RecordingTransactions());

        StepVerifier.create(operations.delete(new SampleAccount(null, "a@nova.io", true)))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertEquals("Entity id must not be null for delete", error.getMessage());
                })
                .verify();
    }

    @Test
    void deleteByIdRejectsNullId() {
        SimpleReactiveEntityOperations operations = newOperations(new CapturingExecutor(), new RecordingTransactions());

        assertThrows(NullPointerException.class, () -> operations.deleteById(SampleAccount.class, null));
    }

    @Test
    void createTableSqlDelegatesToDialectSchemaGenerator() {
        SimpleReactiveEntityOperations operations = newOperations(new CapturingExecutor(), new RecordingTransactions());

        assertEquals(
                "create table accounts",
                operations.createTableSql(SampleAccount.class)
        );
    }

    @Test
    void inTransactionDelegatesToTransactionOperations() {
        RecordingTransactions transactions = new RecordingTransactions();
        SimpleReactiveEntityOperations operations = newOperations(new CapturingExecutor(), transactions);

        StepVerifier.create(operations.inTransaction(current -> Mono.just(current == operations)))
                .expectNext(true)
                .verifyComplete();

        assertEquals(List.of("begin", "callback"), transactions.events);
    }

    @Test
    void saveAllBatchesNewEntitiesIntoSingleExecuteBatchCall() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = List.of(
                new SampleAccount(null, "a@nova.io", true),
                new SampleAccount(null, "b@nova.io", false),
                new SampleAccount(null, "c@nova.io", true)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNext(accounts.get(0), accounts.get(1), accounts.get(2))
                .verifyComplete();

        assertEquals(1, executor.batchCalls.size(), "new 엔티티 3건은 단일 executeBatch 호출로 묶여야 한다");
        BatchCall call = executor.batchCalls.get(0);
        assertEquals("insert into accounts (email_address, active) values (?, ?)", call.sql());
        assertEquals(3, call.bindingsList().size());
        assertEquals(List.of("a@nova.io", true), call.bindingsList().get(0));
        assertEquals(List.of("b@nova.io", false), call.bindingsList().get(1));
        assertEquals(List.of("c@nova.io", true), call.bindingsList().get(2));
        assertTrue(executor.executedStatements.isEmpty(), "단건 execute 경로는 호출되지 않아야 한다");
    }

    @Test
    void saveAllBatchesExistingEntitiesIntoSingleExecuteBatchCall() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = List.of(
                new SampleAccount(1L, "a@nova.io", true),
                new SampleAccount(2L, "b@nova.io", false)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNext(accounts.get(0), accounts.get(1))
                .verifyComplete();

        assertEquals(1, executor.batchCalls.size());
        BatchCall call = executor.batchCalls.get(0);
        assertEquals("update accounts set email_address = ?, active = ? where id = ?", call.sql());
        assertEquals(2, call.bindingsList().size());
    }

    @Test
    void saveAllSplitsBatchesByEntityStateGroup() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = List.of(
                new SampleAccount(null, "new1@nova.io", true),
                new SampleAccount(5L, "existing@nova.io", false),
                new SampleAccount(null, "new2@nova.io", false)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNextCount(3)
                .verifyComplete();

        assertEquals(2, executor.batchCalls.size(), "new와 existing은 별도 배치로 분리되어야 한다");
        assertEquals("insert into accounts (email_address, active) values (?, ?)", executor.batchCalls.get(0).sql());
        assertEquals(2, executor.batchCalls.get(0).bindingsList().size());
        assertEquals("update accounts set email_address = ?, active = ? where id = ?", executor.batchCalls.get(1).sql());
        assertEquals(1, executor.batchCalls.get(1).bindingsList().size());
    }

    @Test
    void saveAllOnEmptyInputShortCircuits() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.saveAll(List.<SampleAccount>of()))
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty());
        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void deleteAllUsesSingleInDelete() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(3L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = List.of(
                new SampleAccount(1L, "a@nova.io", true),
                new SampleAccount(2L, "b@nova.io", false),
                new SampleAccount(3L, "c@nova.io", true)
        );

        StepVerifier.create(operations.deleteAll(accounts))
                .expectNext(3L)
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty(), "deleteAll은 IN 한 방 쿼리 — executeBatch를 쓰면 안 된다");
        assertEquals(1, executor.executedStatements.size());
        SqlStatement statement = executor.executedStatements.get(0);
        assertEquals("delete from accounts where id in (?, ?, ?)", statement.sql());
        assertEquals(List.of(1L, 2L, 3L), statement.bindings());
    }

    @Test
    void deleteAllOnEmptyInputShortCircuits() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.deleteAll(List.<SampleAccount>of()))
                .expectNext(0L)
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty());
        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void deleteAllByIdUsesSingleInDelete() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(3L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.deleteAllById(SampleAccount.class, List.of(10L, 20L, 30L)))
                .expectNext(3L)
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty(), "deleteAllById는 IN 한 방 쿼리 — executeBatch를 쓰면 안 된다");
        assertEquals(1, executor.executedStatements.size());
        SqlStatement statement = executor.executedStatements.get(0);
        assertEquals("delete from accounts where id in (?, ?, ?)", statement.sql());
        assertEquals(List.of(10L, 20L, 30L), statement.bindings());
    }

    @Test
    void deleteAllByQueryRunsSingleDeleteWithPredicate() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(7L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.deleteAll(
                        SampleAccount.class,
                        QuerySpec.empty().where(Criteria.eq("active", false))))
                .expectNext(7L)
                .verifyComplete();

        assertEquals(1, executor.executedStatements.size());
        SqlStatement statement = executor.executedStatements.get(0);
        assertEquals("delete from accounts where active = ?", statement.sql());
        assertEquals(List.of(false), statement.bindings());
        assertTrue(executor.batchCalls.isEmpty());
    }

    @Test
    void deleteAllByQueryPropagatesRendererRejectionForNullPredicate() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> operations.deleteAll(SampleAccount.class, QuerySpec.empty())
        );

        assertEquals("deleteByQuery requires a non-null predicate", exception.getMessage());
        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void deleteAllByIdOnEmptyInputShortCircuits() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.deleteAllById(SampleAccount.class, List.<Long>of()))
                .expectNext(0L)
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty());
        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void saveAllSplitsBatchesAcrossDifferentEntityTypes() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<Object> mixed = List.of(
                new SampleAccount(null, "a@nova.io", true),
                new SampleOrder(null, "buyer@nova.io", 1500L),
                new SampleAccount(null, "b@nova.io", false),
                new SampleOrder(null, "buyer2@nova.io", 2500L)
        );

        @SuppressWarnings({"rawtypes", "unchecked"})
        ReactiveEntityOperations ops = operations;
        StepVerifier.create(ops.saveAll((Iterable) mixed))
                .expectNextCount(4)
                .verifyComplete();

        assertEquals(2, executor.batchCalls.size(), "다른 entity 타입은 각자 별도 배치로 분리되어야 한다");
        assertEquals("insert into accounts (email_address, active) values (?, ?)", executor.batchCalls.get(0).sql());
        assertEquals(2, executor.batchCalls.get(0).bindingsList().size());
        assertEquals("insert into orders (customer_email, total_cents) values (?, ?)", executor.batchCalls.get(1).sql());
        assertEquals(2, executor.batchCalls.get(1).bindingsList().size());
        assertTrue(executor.executedStatements.isEmpty(), "단건 fallback 경로는 호출되지 않아야 한다");
    }

    @Test
    void deleteAllSplitsInDeletesAcrossDifferentEntityTypes() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(2L);
        executor.executeResults.addLast(1L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<Object> mixed = List.of(
                new SampleAccount(1L, "a@nova.io", true),
                new SampleOrder(101L, "buyer@nova.io", 1500L),
                new SampleAccount(2L, "b@nova.io", false)
        );

        @SuppressWarnings({"rawtypes", "unchecked"})
        ReactiveEntityOperations ops = operations;
        StepVerifier.create(ops.deleteAll((Iterable) mixed))
                .expectNext(3L)
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty());
        assertEquals(2, executor.executedStatements.size(), "다른 entity 타입은 각자 별도 IN 쿼리로 분리되어야 한다");
        assertEquals("delete from accounts where id in (?, ?)", executor.executedStatements.get(0).sql());
        assertEquals(List.of(1L, 2L), executor.executedStatements.get(0).bindings());
        assertEquals("delete from orders where id in (?)", executor.executedStatements.get(1).sql());
        assertEquals(List.of(101L), executor.executedStatements.get(1).bindings());
    }

    @Test
    void deleteAllReportsIndexAndTypeOfFirstNullId() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = new ArrayList<>();
        accounts.add(new SampleAccount(1L, "a@nova.io", true));
        accounts.add(new SampleAccount(null, "b@nova.io", false));
        accounts.add(new SampleAccount(3L, "c@nova.io", true));

        StepVerifier.create(operations.deleteAll(accounts))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertTrue(error.getMessage().contains("index 1"),
                            "오류 메시지가 'index 1'을 포함해야 한다: " + error.getMessage());
                    assertTrue(error.getMessage().contains(SampleAccount.class.getName()),
                            "오류 메시지가 entity 타입명을 포함해야 한다: " + error.getMessage());
                })
                .verify();

        assertTrue(executor.batchCalls.isEmpty());
        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void deleteAllByIdReportsIndexAndTypeOfFirstNullId() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<Long> ids = new ArrayList<>();
        ids.add(10L);
        ids.add(null);
        ids.add(30L);

        StepVerifier.create(operations.deleteAllById(SampleAccount.class, ids))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertTrue(error.getMessage().contains("index 1"),
                            "오류 메시지가 'index 1'을 포함해야 한다: " + error.getMessage());
                    assertTrue(error.getMessage().contains(SampleAccount.class.getName()),
                            "오류 메시지가 entity 타입명을 포함해야 한다: " + error.getMessage());
                })
                .verify();

        assertTrue(executor.batchCalls.isEmpty());
    }

    @Test
    void saveAllFallsBackToSingleExecuteWhenBindingSizeDiffersWithinGroup() {
        CapturingExecutor executor = new CapturingExecutor();
        DivergentBindingsDialect divergentDialect = new DivergentBindingsDialect();
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                divergentDialect,
                executor,
                new EntityStateDetector(),
                new RecordingTransactions()
        );
        List<SampleAccount> accounts = List.of(
                new SampleAccount(null, "a@nova.io", true),
                new SampleAccount(null, "b@nova.io", false)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNext(accounts.get(0), accounts.get(1))
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty(),
                "SQL은 같지만 binding size가 다르면 batch 경로 대신 단건 fallback로 처리되어야 한다");
        assertEquals(2, executor.executedStatements.size());
        assertEquals(1, executor.executedStatements.get(0).bindings().size());
        assertEquals(2, executor.executedStatements.get(1).bindings().size());
    }

    @Test
    void propagatesInstantiationFailuresForEntitiesWithoutDefaultConstructor() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("id", 1L, "name", "nova")));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> operations.findById(NoDefaultConstructorEntity.class, 1L).block()
        );

        assertTrue(exception.getMessage().contains("must expose a no-args constructor"));
    }

    private SimpleReactiveEntityOperations newOperations(CapturingExecutor executor, RecordingTransactions transactions) {
        return new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new RecordingDialect(),
                executor,
                new EntityStateDetector(),
                transactions
        );
    }

    private static final class RecordingDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SqlRenderer renderer = new AbstractSqlRenderer(this) {
        };
        private final SchemaGenerator schemaGenerator = metadata -> "create table " + metadata.tableName();

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return identifier;
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            return schemaGenerator;
        }
    }

    /**
     * SQL은 항상 같지만 binding 개수가 호출마다 1, 2, 3... 으로 달라지는 가짜 dialect.
     * uniformShape 위반 시 fallback 동작을 검증하기 위해 사용.
     */
    private static final class DivergentBindingsDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SchemaGenerator schemaGenerator = metadata -> "create table " + metadata.tableName();
        private final SqlRenderer renderer = new DivergentBindingsRenderer();

        @Override
        public String name() {
            return "divergent";
        }

        @Override
        public String quote(String identifier) {
            return identifier;
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            return schemaGenerator;
        }
    }

    private static final class DivergentBindingsRenderer implements SqlRenderer {
        private int insertCalls;

        @Override
        public SqlStatement insert(io.nova.metadata.EntityMetadata<?> metadata, Object entity) {
            insertCalls++;
            // SQL은 항상 같음. binding size는 호출마다 다르게.
            List<Object> bindings = new ArrayList<>();
            for (int i = 0; i < insertCalls; i++) {
                bindings.add("v" + i);
            }
            return new SqlStatement("insert into accounts (a) values (?)", bindings);
        }

        @Override
        public SqlStatement update(io.nova.metadata.EntityMetadata<?> metadata, Object entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SqlStatement deleteById(io.nova.metadata.EntityMetadata<?> metadata, Object id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SqlStatement selectById(io.nova.metadata.EntityMetadata<?> metadata, Object id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SqlStatement select(io.nova.metadata.EntityMetadata<?> metadata, QuerySpec querySpec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SqlStatement count(io.nova.metadata.EntityMetadata<?> metadata, QuerySpec querySpec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SqlStatement exists(io.nova.metadata.EntityMetadata<?> metadata, QuerySpec querySpec) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CapturingExecutor implements SqlExecutor {
        private final Deque<RowAccessor> queryOneResults = new ArrayDeque<>();
        private final Deque<List<RowAccessor>> queryManyResults = new ArrayDeque<>();
        private final Deque<Long> executeResults = new ArrayDeque<>();
        private final List<BatchCall> batchCalls = new ArrayList<>();
        private final List<SqlStatement> executedStatements = new ArrayList<>();
        private boolean emptyQueryOne;
        private SqlStatement lastStatement;
        private Object generatedKey;
        private String lastGeneratedIdColumn;
        private Class<?> lastGeneratedIdType;
        private int generatedKeyCalls;

        @Override
        public Mono<Long> execute(SqlStatement statement) {
            this.lastStatement = statement;
            this.executedStatements.add(statement);
            long result = executeResults.isEmpty() ? 1L : executeResults.removeFirst();
            return Mono.just(result);
        }

        @Override
        public <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper) {
            this.lastStatement = statement;
            if (emptyQueryOne) {
                return Mono.empty();
            }
            return Mono.fromSupplier(() -> mapper.apply(queryOneResults.removeFirst()));
        }

        @Override
        public <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper) {
            this.lastStatement = statement;
            List<RowAccessor> rows = queryManyResults.removeFirst();
            return Flux.fromIterable(rows).map(mapper);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
            this.lastStatement = statement;
            this.lastGeneratedIdColumn = idColumn;
            this.lastGeneratedIdType = idType;
            this.generatedKeyCalls++;
            if (generatedKey == null) {
                return Mono.empty();
            }
            return Mono.just((T) generatedKey);
        }

        @Override
        public Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
            this.batchCalls.add(new BatchCall(sql, List.copyOf(bindingsList)));
            return Mono.just((long) bindingsList.size());
        }
    }

    private record BatchCall(String sql, List<List<Object>> bindingsList) {
    }

    private record MapRowAccessor(Map<String, Object> values) implements RowAccessor {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String columnName, Class<T> type) {
            return (T) values.get(columnName);
        }
    }

    private static final class RecordingTransactions implements ReactiveTransactionOperations {
        private final List<String> events = new ArrayList<>();

        @Override
        public <T> Mono<T> inTransaction(Function<TransactionContext, Mono<T>> callback) {
            events.add("begin");
            return callback.apply(() -> "test")
                    .doOnSubscribe(ignored -> events.add("callback"));
        }
    }
}
