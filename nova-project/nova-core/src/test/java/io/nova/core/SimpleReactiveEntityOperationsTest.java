package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.query.Updater;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.support.fixtures.FixtureEntities.AssignedIdAccount;
import io.nova.support.fixtures.FixtureEntities.AuditedAccount;
import io.nova.support.fixtures.FixtureEntities.NoDefaultConstructorEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.SampleOrder;
import io.nova.support.fixtures.FixtureEntities.SoftDeletableAccount;
import io.nova.tx.ReactiveTransactionOperations;
import io.nova.tx.TransactionContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    void saveAppliesAuditTimestampsForNewAuditedEntity() {
        Instant fixed = Instant.parse("2026-05-18T09:30:00Z");
        CapturingExecutor executor = new CapturingExecutor();
        executor.generatedKey = 42L;
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, Clock.fixed(fixed, ZoneOffset.UTC));
        AuditedAccount account = new AuditedAccount(null, "x@nova.io");

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> saved == account
                        && Objects.equals(saved.getId(), 42L)
                        && fixed.equals(saved.getCreatedAt())
                        && fixed.equals(saved.getUpdatedAt()))
                .verifyComplete();

        SqlStatement statement = executor.lastStatement;
        assertEquals(
                "insert into audited_accounts (email_address, created_at, updated_at) values (?, ?, ?)",
                statement.sql()
        );
        assertEquals(List.of("x@nova.io", fixed, fixed), statement.bindings());
    }

    @Test
    void saveAppliesOnlyUpdatedAtForExistingAuditedEntity() {
        Instant fixed = Instant.parse("2026-05-18T09:30:00Z");
        Instant original = Instant.parse("2020-01-01T00:00:00Z");
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, Clock.fixed(fixed, ZoneOffset.UTC));
        AuditedAccount account = new AuditedAccount(7L, "x@nova.io", original, original);

        StepVerifier.create(operations.save(account))
                .expectNext(account)
                .verifyComplete();

        assertEquals(original, account.getCreatedAt(), "createdAt 보존");
        assertEquals(fixed, account.getUpdatedAt(), "updatedAt 갱신");
        SqlStatement statement = executor.lastStatement;
        assertEquals(
                "update audited_accounts set email_address = ?, created_at = ?, updated_at = ? where id = ?",
                statement.sql()
        );
        assertEquals(List.of("x@nova.io", original, fixed, 7L), statement.bindings());
    }

    @Test
    void updateAddsUpdatedAtToPartialUpdateFields() {
        Instant fixed = Instant.parse("2026-05-18T09:30:00Z");
        Instant original = Instant.parse("2020-01-01T00:00:00Z");
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, Clock.fixed(fixed, ZoneOffset.UTC));
        AuditedAccount account = new AuditedAccount(7L, "new@nova.io", original, null);

        StepVerifier.create(operations.update(account, List.of("email")))
                .expectNext(account)
                .verifyComplete();

        assertEquals(fixed, account.getUpdatedAt());
        SqlStatement statement = executor.executedStatements.get(0);
        assertEquals("update audited_accounts set email_address = ?, updated_at = ? where id = ?", statement.sql());
        assertEquals(List.of("new@nova.io", fixed, 7L), statement.bindings());
    }

    @Test
    void saveAllAppliesAuditTimestampsAcrossBatch() {
        Instant fixed = Instant.parse("2026-05-18T09:30:00Z");
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, Clock.fixed(fixed, ZoneOffset.UTC));
        List<AuditedAccount> accounts = List.of(
                new AuditedAccount(null, "a@nova.io"),
                new AuditedAccount(null, "b@nova.io")
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNextCount(2)
                .verifyComplete();

        for (AuditedAccount account : accounts) {
            assertEquals(fixed, account.getCreatedAt());
            assertEquals(fixed, account.getUpdatedAt());
        }
        assertEquals(1, executor.batchCalls.size());
        BatchCall call = executor.batchCalls.get(0);
        assertEquals(
                "insert into audited_accounts (email_address, created_at, updated_at) values (?, ?, ?)",
                call.sql()
        );
        assertEquals(List.of("a@nova.io", fixed, fixed), call.bindingsList().get(0));
        assertEquals(List.of("b@nova.io", fixed, fixed), call.bindingsList().get(1));
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
    void updateWithUpdaterRendersPartialUpdateAndReturnsAffectedRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(7L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.update(
                        SampleAccount.class,
                        Updater.of(SampleAccount.class)
                                .set("email", "x@nova.io")
                                .set("active", true)
                                .where(Criteria.gte("id", 10L))
                ))
                .expectNext(7L)
                .verifyComplete();

        assertEquals(
                "update accounts set email_address = ?, active = ? where id >= ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("x@nova.io", true, 10L), executor.lastStatement.bindings());
    }

    @Test
    void updateWithUpdaterRejectsEmptyFields() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.update(
                        SampleAccount.class,
                        Updater.of(SampleAccount.class).where(Criteria.eq("id", 1L))
                ))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertEquals("Updater requires at least one set(...) assignment", error.getMessage());
                })
                .verify();

        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void updateWithUpdaterRejectsMissingWhere() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.update(
                        SampleAccount.class,
                        Updater.of(SampleAccount.class).set("email", "x@nova.io")
                ))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertEquals("Updater requires a where(...) predicate", error.getMessage());
                })
                .verify();

        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void updateWithUpdaterPropagatesIdFieldRejection() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.update(
                        SampleAccount.class,
                        Updater.of(SampleAccount.class)
                                .set("id", 99L)
                                .where(Criteria.eq("email", "x@nova.io"))
                ))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertTrue(error.getMessage().contains("id property id"),
                            "오류 메시지가 id property 거부를 나타내야 한다: " + error.getMessage());
                })
                .verify();

        assertTrue(executor.executedStatements.isEmpty());
    }

    @Test
    void updateWithUpdaterPropagatesUnknownFieldRejection() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.update(
                        SampleAccount.class,
                        Updater.of(SampleAccount.class)
                                .set("nope", "value")
                                .where(Criteria.eq("id", 1L))
                ))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertTrue(error.getMessage().contains("Unknown property nope"),
                            "오류 메시지가 미지의 property nope를 나타내야 한다: " + error.getMessage());
                })
                .verify();

        assertTrue(executor.executedStatements.isEmpty());
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

    @Test
    void deleteOnSoftDeletableEntityIssuesUpdateAndSetsTimestamp() {
        CapturingExecutor executor = new CapturingExecutor();
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));
        SoftDeletableAccount account = new SoftDeletableAccount(7L, "a@nova.io", null);

        StepVerifier.create(operations.delete(account))
                .expectNext(1L)
                .verifyComplete();

        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where id = ? and deleted_at is null",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(now, 7L), executor.lastStatement.bindings());
        assertEquals(now, account.getDeletedAt());
    }

    @Test
    void deleteByIdOnSoftDeletableEntityIssuesUpdate() {
        CapturingExecutor executor = new CapturingExecutor();
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));

        StepVerifier.create(operations.deleteById(SoftDeletableAccount.class, 7L))
                .expectNext(1L)
                .verifyComplete();

        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where id = ? and deleted_at is null",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(now, 7L), executor.lastStatement.bindings());
    }

    @Test
    void deleteAllByIdOnSoftDeletableEntityIssuesSingleUpdate() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(3L);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));

        StepVerifier.create(operations.deleteAllById(SoftDeletableAccount.class, List.of(10L, 20L, 30L)))
                .expectNext(3L)
                .verifyComplete();

        assertEquals(1, executor.executedStatements.size());
        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where id in (?, ?, ?) and deleted_at is null",
                executor.executedStatements.get(0).sql()
        );
        assertEquals(List.of(now, 10L, 20L, 30L), executor.executedStatements.get(0).bindings());
    }

    @Test
    void deleteAllOnSoftDeletableEntitiesIssuesSingleUpdateAndStampsAll() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(2L);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));
        SoftDeletableAccount first = new SoftDeletableAccount(1L, "a@nova.io", null);
        SoftDeletableAccount second = new SoftDeletableAccount(2L, "b@nova.io", null);

        StepVerifier.create(operations.deleteAll(List.of(first, second)))
                .expectNext(2L)
                .verifyComplete();

        assertEquals(1, executor.executedStatements.size());
        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where id in (?, ?) and deleted_at is null",
                executor.executedStatements.get(0).sql()
        );
        assertEquals(List.of(now, 1L, 2L), executor.executedStatements.get(0).bindings());
        assertEquals(now, first.getDeletedAt());
        assertEquals(now, second.getDeletedAt());
    }

    @Test
    void findByIdOnSoftDeletableEntityAppendsAlivePredicate() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("id", 7L, "email_address", "a@nova.io")));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.findById(SoftDeletableAccount.class, 7L))
                .expectNextMatches(account -> Objects.equals(account.getId(), 7L))
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address, deleted_at as deleted_at from soft_deletable_accounts where id = ? and deleted_at is null",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(7L), executor.lastStatement.bindings());
    }

    @Test
    void findAllOnSoftDeletableEntityAppendsAlivePredicate() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of());
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.findAll(SoftDeletableAccount.class, QuerySpec.empty().where(Criteria.eq("email", "a@nova.io"))))
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address, deleted_at as deleted_at from soft_deletable_accounts where email_address = ? and deleted_at is null",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("a@nova.io"), executor.lastStatement.bindings());
    }

    @Test
    void countOnSoftDeletableEntityFiltersOutDeletedRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("count", 5L)));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.count(SoftDeletableAccount.class, QuerySpec.empty()))
                .expectNext(5L)
                .verifyComplete();

        assertEquals(
                "select count(*) as count from soft_deletable_accounts where deleted_at is null",
                executor.lastStatement.sql()
        );
    }

    @Test
    void existsOnSoftDeletableEntityFiltersOutDeletedRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of()));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.exists(SoftDeletableAccount.class, QuerySpec.empty().where(Criteria.eq("email", "a@nova.io"))))
                .expectNext(true)
                .verifyComplete();

        assertEquals(
                "select 1 from soft_deletable_accounts where email_address = ? and deleted_at is null limit 1",
                executor.lastStatement.sql()
        );
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

    private SimpleReactiveEntityOperations newOperationsWithClock(CapturingExecutor executor, Clock clock) {
        return new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new RecordingDialect(),
                executor,
                new EntityStateDetector(),
                new RecordingTransactions(),
                clock
        );
    }

    private SimpleReactiveEntityOperations newOperationsWithClock(
            CapturingExecutor executor, RecordingTransactions transactions, Clock clock) {
        return new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new RecordingDialect(),
                executor,
                new EntityStateDetector(),
                transactions,
                clock
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
