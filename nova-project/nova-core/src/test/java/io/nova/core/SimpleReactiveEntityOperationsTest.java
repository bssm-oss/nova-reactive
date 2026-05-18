package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.NativeQuery;
import io.nova.query.Pageable;
import io.nova.query.Projection;
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
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.support.fixtures.FixtureEntities.GeneratedVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.SampleOrder;
import io.nova.support.fixtures.FixtureEntities.SequencedAccount;
import io.nova.support.fixtures.FixtureEntities.SoftDeletableAccount;
import io.nova.support.fixtures.FixtureEntities.StringUuidAccount;
import io.nova.support.fixtures.FixtureEntities.UuidAccount;
import io.nova.support.fixtures.FixtureEntities.VersionedAccount;
import io.nova.support.fixtures.FixtureEntities.VersionedSoftDeletableAccount;
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
    void updatePartialIncludesVersionSetAndWhereForVersionedEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        VersionedAccount account = new VersionedAccount(7L, "x@nova.io", 3L);

        StepVerifier.create(operations.update(account, List.of("email")))
                .expectNext(account)
                .verifyComplete();

        assertEquals(1, executor.executedStatements.size());
        SqlStatement statement = executor.executedStatements.get(0);
        assertEquals(
                "update versioned_accounts set email_address = ?, version = ? where id = ? and version = ?",
                statement.sql()
        );
        assertEquals(List.of("x@nova.io", 4L, 7L, 3L), statement.bindings());
        assertEquals(Long.valueOf(4L), account.getVersion());
    }

    @Test
    void updatePartialRaisesOptimisticLockingFailureWhenAffectedZeroForVersionedEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(0L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        VersionedAccount account = new VersionedAccount(7L, "x@nova.io", 3L);

        StepVerifier.create(operations.update(account, List.of("email")))
                .expectErrorSatisfies(error -> {
                    assertEquals(OptimisticLockingFailureException.class, error.getClass());
                    assertTrue(error.getMessage().contains("id=7"));
                    assertTrue(error.getMessage().contains("version=3"));
                })
                .verify();

        assertEquals(Long.valueOf(3L), account.getVersion(), "실패 시 version 필드는 그대로 보존되어야 한다");
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
    void findAllByIdOnSoftDeletableEntityAppendsAliveGuard() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 10L, "email_address", "a@nova.io"))
        ));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.findAllById(SoftDeletableAccount.class, List.of(10L, 20L, 30L)))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address, deleted_at as deleted_at from soft_deletable_accounts where id in (?, ?, ?) and deleted_at is null",
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
    void updateWithUpdaterAutoAddsUpdatedAtForAuditedEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, Clock.fixed(now, ZoneOffset.UTC));

        StepVerifier.create(operations.update(
                        AuditedAccount.class,
                        Updater.of(AuditedAccount.class)
                                .set("email", "x@nova.io")
                                .where(Criteria.eq("id", 7L))
                ))
                .expectNext(1L)
                .verifyComplete();

        assertEquals(
                "update audited_accounts set email_address = ?, updated_at = ? where id = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("x@nova.io", now, 7L), executor.lastStatement.bindings());
    }

    @Test
    void updateWithUpdaterPreservesUserSetUpdatedAt() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        Instant clockNow = Instant.parse("2026-05-18T10:00:00Z");
        Instant userValue = Instant.parse("2020-01-01T00:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, Clock.fixed(clockNow, ZoneOffset.UTC));

        StepVerifier.create(operations.update(
                        AuditedAccount.class,
                        Updater.of(AuditedAccount.class)
                                .set("updatedAt", userValue)
                                .set("email", "x@nova.io")
                                .where(Criteria.eq("id", 7L))
                ))
                .expectNext(1L)
                .verifyComplete();

        assertTrue(executor.lastStatement.bindings().contains(userValue),
                "user-set updatedAt 값이 보존되어야 한다");
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
    void deleteAllByQueryOnSoftDeletableEntityIssuesSoftDeleteUpdate() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(4L);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));

        StepVerifier.create(operations.deleteAll(
                        SoftDeletableAccount.class,
                        QuerySpec.empty().where(Criteria.eq("email", "a@nova.io"))))
                .expectNext(4L)
                .verifyComplete();

        assertEquals(1, executor.executedStatements.size());
        SqlStatement statement = executor.executedStatements.get(0);
        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where email_address = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(List.of(now, "a@nova.io"), statement.bindings());
        assertTrue(executor.batchCalls.isEmpty());
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

    @Test
    void saveInitializesNullVersionToZeroOnInsert() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.generatedKey = 42L;
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        GeneratedVersionedAccount account = new GeneratedVersionedAccount(null, "new@nova.io", null);

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> saved == account
                        && Objects.equals(saved.getId(), 42L)
                        && Objects.equals(saved.getVersion(), 0L))
                .verifyComplete();

        assertEquals(
                "insert into generated_versioned_accounts (email_address, version) values (?, ?)",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("new@nova.io", 0L), executor.lastStatement.bindings());
    }

    @Test
    void savePreservesExplicitVersionOnInsert() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.generatedKey = 42L;
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        GeneratedVersionedAccount account = new GeneratedVersionedAccount(null, "new@nova.io", 5L);

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> Objects.equals(saved.getVersion(), 5L))
                .verifyComplete();

        assertEquals(List.of("new@nova.io", 5L), executor.lastStatement.bindings());
    }

    @Test
    void saveIncrementsVersionOnUpdate() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        VersionedAccount account = new VersionedAccount(7L, "updated@nova.io", 3L);

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> saved == account && Objects.equals(saved.getVersion(), 4L))
                .verifyComplete();

        assertEquals(
                "update versioned_accounts set email_address = ?, version = ? where id = ? and version = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("updated@nova.io", 4L, 7L, 3L), executor.lastStatement.bindings());
    }

    @Test
    void saveRaisesOptimisticLockingFailureWhenUpdateAffectsZeroRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(0L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        VersionedAccount account = new VersionedAccount(7L, "stale@nova.io", 3L);

        StepVerifier.create(operations.save(account))
                .expectErrorSatisfies(error -> {
                    assertEquals(OptimisticLockingFailureException.class, error.getClass());
                    assertTrue(error.getMessage().contains("id=7"));
                    assertTrue(error.getMessage().contains("version=3"));
                })
                .verify();

        assertEquals(Long.valueOf(3L), account.getVersion());
    }

    @Test
    void deleteSendsVersionAwareWhereForVersionedEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.delete(new VersionedAccount(9L, "a@nova.io", 4L)))
                .expectNext(1L)
                .verifyComplete();

        assertEquals(
                "delete from versioned_accounts where id = ? and version = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(9L, 4L), executor.lastStatement.bindings());
    }

    @Test
    void deleteRaisesOptimisticLockingFailureWhenDeleteAffectsZeroRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(0L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.delete(new VersionedAccount(9L, "a@nova.io", 4L)))
                .expectErrorSatisfies(error -> {
                    assertEquals(OptimisticLockingFailureException.class, error.getClass());
                    assertTrue(error.getMessage().contains("id=9"));
                    assertTrue(error.getMessage().contains("version=4"));
                })
                .verify();
    }

    @Test
    void deleteByIdSkipsVersionValidationForVersionedEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(0L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.deleteById(VersionedAccount.class, 9L))
                .expectNext(0L)
                .verifyComplete();

        assertEquals(
                "delete from versioned_accounts where id = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(9L), executor.lastStatement.bindings());
    }

    @Test
    void deleteOnVersionedSoftDeletableEntityCombinesUpdateWithVersionCheck() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));
        VersionedSoftDeletableAccount account = new VersionedSoftDeletableAccount(9L, "a@nova.io", 4L, null);

        StepVerifier.create(operations.delete(account))
                .expectNext(1L)
                .verifyComplete();

        assertEquals(
                "update versioned_soft_deletable_accounts set deleted_at = ?, version = ? where id = ? and version = ? and deleted_at is null",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(now, 5L, 9L, 4L), executor.lastStatement.bindings());
        assertEquals(now, account.getDeletedAt(), "성공 시 deletedAt이 entity에 기록되어야 한다");
        assertEquals(Long.valueOf(5L), account.getVersion(), "성공 시 version이 증가해야 한다");
    }

    @Test
    void deleteOnVersionedSoftDeletableEntityRaisesOptimisticLockingFailureOnZeroAffected() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(0L);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));
        VersionedSoftDeletableAccount account = new VersionedSoftDeletableAccount(9L, "a@nova.io", 4L, null);

        StepVerifier.create(operations.delete(account))
                .expectErrorSatisfies(error -> {
                    assertEquals(OptimisticLockingFailureException.class, error.getClass());
                    assertTrue(error.getMessage().contains("id=9"));
                    assertTrue(error.getMessage().contains("version=4"));
                })
                .verify();

        assertEquals(Long.valueOf(4L), account.getVersion(), "실패 시 version 필드는 그대로 보존되어야 한다");
        assertEquals(null, account.getDeletedAt(), "실패 시 deletedAt도 기록되지 않아야 한다");
    }

    @Test
    void deleteAllOnVersionedSoftDeletableEntitiesFallsBackToPerEntityDeletes() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        executor.executeResults.addLast(1L);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");
        SimpleReactiveEntityOperations operations = newOperationsWithClock(
                executor, new RecordingTransactions(), Clock.fixed(now, ZoneOffset.UTC));
        VersionedSoftDeletableAccount first = new VersionedSoftDeletableAccount(1L, "a@nova.io", 2L, null);
        VersionedSoftDeletableAccount second = new VersionedSoftDeletableAccount(2L, "b@nova.io", 7L, null);

        StepVerifier.create(operations.deleteAll(List.of(first, second)))
                .expectNext(2L)
                .verifyComplete();

        assertEquals(2, executor.executedStatements.size(),
                "@SoftDelete + @Version 그룹은 단건 fallback로 처리되어 entity 수만큼 UPDATE가 실행되어야 한다");
        for (SqlStatement statement : executor.executedStatements) {
            assertTrue(statement.sql().startsWith(
                            "update versioned_soft_deletable_accounts set deleted_at = ?, version = ? where id = ? and version = ?"),
                    "각 UPDATE는 version-check WHERE를 포함해야 한다: " + statement.sql());
        }
        assertEquals(Long.valueOf(3L), first.getVersion());
        assertEquals(Long.valueOf(8L), second.getVersion());
        assertEquals(now, first.getDeletedAt());
        assertEquals(now, second.getDeletedAt());
    }

    @Test
    void saveAllRoutesVersionedUpdatesThroughSingleExecuteFallback() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(1L);
        executor.executeResults.addLast(1L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<VersionedAccount> accounts = List.of(
                new VersionedAccount(1L, "a@nova.io", 2L),
                new VersionedAccount(2L, "b@nova.io", 7L)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNext(accounts.get(0), accounts.get(1))
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty(),
                "@Version 있는 update 그룹은 batch 경로 대신 단건 fallback로 처리되어야 한다");
        assertEquals(2, executor.executedStatements.size());
        assertEquals(Long.valueOf(3L), accounts.get(0).getVersion());
        assertEquals(Long.valueOf(8L), accounts.get(1).getVersion());
    }

    @Test
    void saveOnSequenceEntityFetchesNextValueThenIssuesInsert() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of(Dialect.SEQUENCE_VALUE_COLUMN, 99L)));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SequencedAccount account = new SequencedAccount(null, "seq@nova.io");

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> saved == account && Objects.equals(saved.getId(), 99L))
                .verifyComplete();

        assertEquals(1, executor.executedStatements.size(),
                "execute는 INSERT 한 건만 호출되어야 한다 (sequence 조회는 queryOne 경로)");
        SqlStatement insertStmt = executor.executedStatements.get(0);
        assertEquals(
                "insert into sequenced_accounts (id, email_address) values (?, ?)",
                insertStmt.sql()
        );
        assertEquals(List.of(99L, "seq@nova.io"), insertStmt.bindings());
        assertEquals(0, executor.generatedKeyCalls,
                "SEQUENCE 경로는 executeAndReturnGeneratedKey를 사용하지 않아야 한다");
    }

    @Test
    void saveOnSequenceEntityIssuesSequenceQueryBeforeInsert() {
        OrderedCapturingExecutor executor = new OrderedCapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of(Dialect.SEQUENCE_VALUE_COLUMN, 17L)));
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new RecordingDialect(),
                executor,
                new EntityStateDetector(),
                new RecordingTransactions()
        );

        StepVerifier.create(operations.save(new SequencedAccount(null, "seq@nova.io")))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(List.of("queryOne", "execute"), executor.calls,
                "SEQUENCE 조회가 INSERT 보다 먼저 발생해야 한다");
        assertEquals(
                "select nextval('sequenced_accounts_seq') as " + Dialect.SEQUENCE_VALUE_COLUMN,
                executor.sequenceQuery.sql()
        );
    }

    @Test
    void saveOnSequenceEntityReadsValueByDialectAliasNotDriverColumnLabel() {
        // RowAccessor read는 Dialect.SEQUENCE_VALUE_COLUMN alias만 사용해야 한다.
        // 가짜 driver가 옛 'nextval' 라벨만 채우면 alias 조회는 null을 받고,
        // 그 결과 INSERT는 발행되지 않고 save()는 빈 결과로 완료된다 (id 추측 금지).
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("nextval", 99L)));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SequencedAccount account = new SequencedAccount(null, "seq@nova.io");

        StepVerifier.create(operations.save(account))
                .verifyComplete();

        assertTrue(executor.executedStatements.isEmpty(),
                "alias 조회가 null을 반환하면 INSERT는 발행되지 않아야 한다 (id 추측 금지)");
        org.junit.jupiter.api.Assertions.assertNull(account.getId(),
                "id 채움은 alias가 반환한 값을 통해서만 일어나야 한다");
    }

    @Test
    void saveOnUuidEntityAssignsRandomUuidAndIssuesInsert() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        UuidAccount account = new UuidAccount(null, "uuid@nova.io");

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> saved == account && saved.getId() != null)
                .verifyComplete();

        java.util.UUID assigned = account.getId();
        SqlStatement statement = executor.lastStatement;
        assertEquals(
                "insert into uuid_accounts (id, email_address) values (?, ?)",
                statement.sql()
        );
        assertEquals(List.of(assigned, "uuid@nova.io"), statement.bindings());
        assertEquals(0, executor.generatedKeyCalls);
    }

    @Test
    void saveOnStringUuidEntityAssignsUuidToString() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        StringUuidAccount account = new StringUuidAccount(null, "uuid@nova.io");

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> saved.getId() != null
                        && java.util.UUID.fromString(saved.getId()) != null)
                .verifyComplete();

        SqlStatement statement = executor.lastStatement;
        assertEquals(
                "insert into string_uuid_accounts (id, email_address) values (?, ?)",
                statement.sql()
        );
        assertEquals(2, statement.bindings().size());
        assertTrue(statement.bindings().get(0) instanceof String);
        assertEquals(account.getId(), statement.bindings().get(0));
    }

    @Test
    void saveOnUuidEntityPreservesExplicitlySetId() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        java.util.UUID explicit = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        UuidAccount account = new UuidAccount(explicit, "uuid@nova.io");

        StepVerifier.create(operations.save(account))
                .expectNextMatches(saved -> Objects.equals(saved.getId(), explicit))
                .verifyComplete();
    }

    @Test
    void saveAllOnUuidEntitiesStampsIdsAndBatchesIntoSingleCall() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<UuidAccount> accounts = List.of(
                new UuidAccount(null, "a@nova.io"),
                new UuidAccount(null, "b@nova.io")
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNextCount(2)
                .verifyComplete();

        for (UuidAccount account : accounts) {
            org.junit.jupiter.api.Assertions.assertNotNull(account.getId(), "UUID id가 채워져야 한다");
        }
        assertEquals(1, executor.batchCalls.size());
        BatchCall call = executor.batchCalls.get(0);
        assertEquals("insert into uuid_accounts (id, email_address) values (?, ?)", call.sql());
        assertEquals(2, call.bindingsList().size());
        assertEquals(accounts.get(0).getId(), call.bindingsList().get(0).get(0));
        assertEquals(accounts.get(1).getId(), call.bindingsList().get(1).get(0));
    }

    @Test
    void saveAllOnSequenceEntitiesFallsBackToSingleSaves() {
        OrderedCapturingExecutor executor = new OrderedCapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of(Dialect.SEQUENCE_VALUE_COLUMN, 10L)));
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of(Dialect.SEQUENCE_VALUE_COLUMN, 11L)));
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new RecordingDialect(),
                executor,
                new EntityStateDetector(),
                new RecordingTransactions()
        );
        List<SequencedAccount> accounts = List.of(
                new SequencedAccount(null, "a@nova.io"),
                new SequencedAccount(null, "b@nova.io")
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNextCount(2)
                .verifyComplete();

        assertTrue(executor.batchCalls.isEmpty(),
                "SEQUENCE 그룹은 batch 경로 대신 단건 save로 폴백되어야 한다");
        assertEquals(Long.valueOf(10L), accounts.get(0).getId());
        assertEquals(Long.valueOf(11L), accounts.get(1).getId());
        assertEquals(List.of("queryOne", "execute", "queryOne", "execute"), executor.calls);
    }

    @Test
    void saveAllInitializesVersionOnNewVersionedEntitiesInBatch() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<GeneratedVersionedAccount> accounts = List.of(
                new GeneratedVersionedAccount(null, "a@nova.io", null),
                new GeneratedVersionedAccount(null, "b@nova.io", null)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNext(accounts.get(0), accounts.get(1))
                .verifyComplete();

        assertEquals(1, executor.batchCalls.size());
        BatchCall call = executor.batchCalls.get(0);
        assertEquals("insert into generated_versioned_accounts (email_address, version) values (?, ?)", call.sql());
        assertEquals(List.of("a@nova.io", 0L), call.bindingsList().get(0));
        assertEquals(List.of("b@nova.io", 0L), call.bindingsList().get(1));
        assertEquals(Long.valueOf(0L), accounts.get(0).getVersion());
        assertEquals(Long.valueOf(0L), accounts.get(1).getVersion());
    }

    @Test
    void saveAllPopulatesGeneratedIdsOntoEntitiesInBatch() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.batchGeneratedKeys.addLast(List.of(101L, 102L, 103L));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = List.of(
                new SampleAccount(null, "a@nova.io", true),
                new SampleAccount(null, "b@nova.io", false),
                new SampleAccount(null, "c@nova.io", true)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNext(accounts.get(0), accounts.get(1), accounts.get(2))
                .verifyComplete();

        assertEquals(Long.valueOf(101L), accounts.get(0).getId());
        assertEquals(Long.valueOf(102L), accounts.get(1).getId());
        assertEquals(Long.valueOf(103L), accounts.get(2).getId());
        assertEquals(1, executor.batchCalls.size(), "generated-id 그룹도 단일 batch 호출로 묶여야 한다");
        BatchCall call = executor.batchCalls.get(0);
        assertEquals("insert into accounts (email_address, active) values (?, ?)", call.sql());
        assertEquals(3, call.bindingsList().size());
        assertEquals(List.of("id"), executor.batchGeneratedIdColumns,
                "generated-id batch는 id 컬럼을 명시해 키를 회수해야 한다");
        assertEquals(List.of(Long.class), executor.batchGeneratedIdTypes);
        assertTrue(executor.executedStatements.isEmpty(),
                "generated-id 그룹은 단건 fallback 경로를 거치지 않아야 한다");
    }

    @Test
    void saveAllFailsFastWhenBatchGeneratedKeysAreShorterThanEntities() {
        CapturingExecutor executor = new CapturingExecutor();
        // 3 entity 인데 executor가 2개만 반환 → IllegalStateException으로 즉시 실패.
        executor.batchGeneratedKeys.addLast(List.of(101L, 102L));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = List.of(
                new SampleAccount(null, "a@nova.io", true),
                new SampleAccount(null, "b@nova.io", false),
                new SampleAccount(null, "c@nova.io", true)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalStateException.class, error.getClass());
                    assertTrue(error.getMessage().contains("Batch generated keys count 2"),
                            "오류 메시지가 collected count를 포함해야 한다: " + error.getMessage());
                    assertTrue(error.getMessage().contains("entities count 3"),
                            "오류 메시지가 entity count를 포함해야 한다: " + error.getMessage());
                    assertTrue(error.getMessage().contains("insert into accounts"),
                            "오류 메시지가 SQL을 포함해야 한다: " + error.getMessage());
                })
                .verify();

        // 부분 주입 금지: 한 건도 id가 set되어 있으면 안 된다.
        for (SampleAccount account : accounts) {
            org.junit.jupiter.api.Assertions.assertNull(account.getId(),
                    "fail-fast 경로에서는 어떤 entity도 부분 id 주입 상태가 되면 안 된다");
        }
    }

    @Test
    void saveAllFailsFastWhenBatchGeneratedKeysExceedEntities() {
        CapturingExecutor executor = new CapturingExecutor();
        // 2 entity 인데 executor가 3개를 반환 → IllegalStateException으로 즉시 실패.
        executor.batchGeneratedKeys.addLast(List.of(101L, 102L, 103L));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<SampleAccount> accounts = List.of(
                new SampleAccount(null, "a@nova.io", true),
                new SampleAccount(null, "b@nova.io", false)
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalStateException.class, error.getClass());
                    assertTrue(error.getMessage().contains("Batch generated keys count 3"),
                            "오류 메시지가 collected count를 포함해야 한다: " + error.getMessage());
                    assertTrue(error.getMessage().contains("entities count 2"),
                            "오류 메시지가 entity count를 포함해야 한다: " + error.getMessage());
                })
                .verify();
    }

    @Test
    void saveAllSkipsGeneratedKeyPathForAssignedIdEntities() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        List<AssignedIdAccount> accounts = List.of(
                new AssignedIdAccount(11L, "a@nova.io"),
                new AssignedIdAccount(22L, "b@nova.io")
        );

        StepVerifier.create(operations.saveAll(accounts))
                .expectNext(accounts.get(0), accounts.get(1))
                .verifyComplete();

        assertEquals(1, executor.batchCalls.size());
        assertTrue(executor.batchGeneratedIdColumns.isEmpty(),
                "assigned-id 엔티티 batch는 generated-keys 경로를 호출하지 않아야 한다");
    }

    @Test
    void findAllWithProjectionRecordMapsRowsViaCanonicalConstructor() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 7L, "email_address", "a@nova.io")),
                new MapRowAccessor(Map.of("id", 8L, "email_address", "b@nova.io"))
        ));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        Projection<SampleAccount, AccountEmail> projection = Projection.of(
                SampleAccount.class, AccountEmail.class, List.of("id", "email"));

        StepVerifier.create(operations.findAll(projection, QuerySpec.empty().where(Criteria.eq("active", true))))
                .expectNext(new AccountEmail(7L, "a@nova.io"), new AccountEmail(8L, "b@nova.io"))
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address from accounts where active = ?",
                executor.lastStatement.sql()
        );
        assertEquals(List.of(true), executor.lastStatement.bindings());
    }

    @Test
    void findAllWithProjectionHonorsFieldOrderInBothSqlAndConstructor() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 7L, "email_address", "a@nova.io"))
        ));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        Projection<SampleAccount, EmailFirst> projection = Projection.of(
                SampleAccount.class, EmailFirst.class, List.of("email", "id"));

        StepVerifier.create(operations.findAll(projection, QuerySpec.empty()))
                .expectNext(new EmailFirst("a@nova.io", 7L))
                .verifyComplete();

        assertEquals(
                "select email_address as email_address, id as id from accounts",
                executor.lastStatement.sql()
        );
    }

    @Test
    void findAllWithProjectionWorksForExplicitSingleConstructorClass() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 7L, "email_address", "a@nova.io"))
        ));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        Projection<SampleAccount, AccountSummary> projection = Projection.of(
                SampleAccount.class, AccountSummary.class, List.of("id", "email"));

        StepVerifier.create(operations.findAll(projection, QuerySpec.empty()))
                .expectNextMatches(summary -> Objects.equals(summary.id(), 7L)
                        && Objects.equals(summary.email(), "a@nova.io"))
                .verifyComplete();
    }

    @Test
    void findAllWithProjectionAppendsSoftDeleteAlive() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of());
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        Projection<SoftDeletableAccount, SoftAccountEmail> projection = Projection.of(
                SoftDeletableAccount.class, SoftAccountEmail.class, List.of("id", "email"));

        StepVerifier.create(operations.findAll(projection, QuerySpec.empty()))
                .verifyComplete();

        assertEquals(
                "select id as id, email_address as email_address from soft_deletable_accounts where deleted_at is null",
                executor.lastStatement.sql()
        );
    }

    @Test
    void findAllWithProjectionRejectsUnknownEntityProperty() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        Projection<SampleAccount, AccountEmail> projection = Projection.of(
                SampleAccount.class, AccountEmail.class, List.of("id", "nope"));

        StepVerifier.create(operations.findAll(projection, QuerySpec.empty()))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertTrue(error.getMessage().contains("Unknown property nope"),
                            "오류 메시지가 'Unknown property nope'을 포함해야 한다: " + error.getMessage());
                })
                .verify();
    }

    @Test
    void findAllWithProjectionRejectsConstructorArityMismatch() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        Projection<SampleAccount, AccountEmail> projection = Projection.of(
                SampleAccount.class, AccountEmail.class, List.of("id"));

        StepVerifier.create(operations.findAll(projection, QuerySpec.empty()))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertTrue(error.getMessage().contains("constructor expects"),
                            "오류 메시지가 constructor expects를 포함해야 한다: " + error.getMessage());
                })
                .verify();
    }

    @Test
    void findAllWithProjectionRejectsMultipleConstructorClass() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        Projection<SampleAccount, MultipleConstructorProjection> projection = Projection.of(
                SampleAccount.class, MultipleConstructorProjection.class, List.of("id", "email"));

        StepVerifier.create(operations.findAll(projection, QuerySpec.empty()))
                .expectErrorSatisfies(error -> {
                    assertEquals(IllegalArgumentException.class, error.getClass());
                    assertTrue(error.getMessage().contains("must declare exactly one constructor"),
                            "오류 메시지가 'must declare exactly one constructor'을 포함해야 한다: " + error.getMessage());
                })
                .verify();
    }

    @Test
    void executeNativeForwardsSqlAndBindingsToExecutor() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.executeResults.addLast(7L);
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        NativeQuery query = NativeQuery.of(
                "update accounts set active = ? where email_address like ?",
                List.of(true, "%@nova.io"));

        StepVerifier.create(operations.executeNative(query))
                .expectNext(7L)
                .verifyComplete();

        assertEquals("update accounts set active = ? where email_address like ?", executor.lastStatement.sql());
        assertEquals(List.of(true, "%@nova.io"), executor.lastStatement.bindings());
    }

    @Test
    void executeNativeRejectsNullQuery() {
        SimpleReactiveEntityOperations operations =
                newOperations(new CapturingExecutor(), new RecordingTransactions());

        assertThrows(NullPointerException.class, () -> operations.executeNative(null));
    }

    @Test
    void queryNativeStreamsMappedRows() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("email", "a@nova.io")),
                new MapRowAccessor(Map.of("email", "b@nova.io"))
        ));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        NativeQuery query = NativeQuery.of("select email from accounts where active = ?", List.of(true));

        StepVerifier.create(operations.queryNative(query, row -> row.get("email", String.class)))
                .expectNext("a@nova.io")
                .expectNext("b@nova.io")
                .verifyComplete();

        assertEquals("select email from accounts where active = ?", executor.lastStatement.sql());
        assertEquals(List.of(true), executor.lastStatement.bindings());
    }

    @Test
    void queryNativeRejectsNullArguments() {
        SimpleReactiveEntityOperations operations =
                newOperations(new CapturingExecutor(), new RecordingTransactions());
        NativeQuery query = NativeQuery.of("select 1");

        assertThrows(NullPointerException.class, () -> operations.queryNative(null, row -> row));
        assertThrows(NullPointerException.class, () -> operations.queryNative(query, null));
    }

    @Test
    void queryNativeOneReturnsSingleMappedRow() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("c", 42L)));
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        NativeQuery query = NativeQuery.of("select count(*) as c from accounts where active = ?", List.of(true));

        StepVerifier.create(operations.queryNativeOne(query, row -> row.get("c", Long.class)))
                .expectNext(42L)
                .verifyComplete();

        assertEquals("select count(*) as c from accounts where active = ?", executor.lastStatement.sql());
        assertEquals(List.of(true), executor.lastStatement.bindings());
    }

    @Test
    void queryNativeOneIsEmptyWhenExecutorIsEmpty() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.emptyQueryOne = true;
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());

        StepVerifier.create(operations.queryNativeOne(NativeQuery.of("select 1"), row -> row.get("c", Long.class)))
                .verifyComplete();
    }

    @Test
    void queryNativeOneRejectsNullArguments() {
        SimpleReactiveEntityOperations operations =
                newOperations(new CapturingExecutor(), new RecordingTransactions());
        NativeQuery query = NativeQuery.of("select 1");

        assertThrows(NullPointerException.class, () -> operations.queryNativeOne(null, row -> row));
        assertThrows(NullPointerException.class, () -> operations.queryNativeOne(query, null));
    }

    record AccountEmail(Long id, String email) {
    }

    record EmailFirst(String email, Long id) {
    }

    record SoftAccountEmail(Long id, String email) {
    }

    static final class AccountSummary {
        private final Long id;
        private final String email;

        AccountSummary(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        Long id() {
            return id;
        }

        String email() {
            return email;
        }
    }

    static final class MultipleConstructorProjection {
        private final Long id;
        private final String email;

        MultipleConstructorProjection(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        MultipleConstructorProjection(Long id) {
            this(id, null);
        }
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

        @Override
        public String sequenceNextValueSql(String sequenceName) {
            return "select nextval('" + sequenceName + "') as " + Dialect.SEQUENCE_VALUE_COLUMN;
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

    /**
     * 테스트 픽스처: {@link SqlExecutor} 호출을 캡처하고 미리 적재된 응답을 반환하는 in-memory double.
     * 결과/배치 키는 FIFO {@code Deque}/{@code List}에 적재한 순서대로 소비되며, 호출된 statement와
     * 메타데이터(id column/type, 호출 횟수 등)는 후속 단언을 위해 보존된다.
     */
    private static final class CapturingExecutor implements SqlExecutor {
        private final Deque<RowAccessor> queryOneResults = new ArrayDeque<>();
        private final Deque<List<RowAccessor>> queryManyResults = new ArrayDeque<>();
        private final Deque<Long> executeResults = new ArrayDeque<>();
        private final List<BatchCall> batchCalls = new ArrayList<>();
        private final List<SqlStatement> executedStatements = new ArrayList<>();
        /**
         * 배치 INSERT에 대해 반환할 생성 키 묶음. 비어 있으면
         * {@link #executeBatchAndReturnGeneratedKeys}가 entity 수만큼 {@code 1L, 2L, 3L, ...}의
         * sequential default key를 합성해 emit한다 — 명시적 적재가 필요 없는 기존 배치 테스트의 회귀를 막기 위해서다.
         */
        private final Deque<List<Object>> batchGeneratedKeys = new ArrayDeque<>();
        private final List<String> batchGeneratedIdColumns = new ArrayList<>();
        private final List<Class<?>> batchGeneratedIdTypes = new ArrayList<>();
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

        /**
         * 배치 INSERT를 캡처하고 생성 키를 emit한다.
         * <p>
         * {@link #batchGeneratedKeys}에 응답이 적재되어 있으면 적재 순서대로 꺼내 반환하고,
         * 비어 있으면 {@code bindingsList.size()}만큼 {@code 1L, 2L, 3L, ...}의 default key를
         * 합성해 반환한다 — {@code saveGroup}의 size-mismatch fail-fast를 통과시키면서 명시적
         * 키 적재가 불필요한 기존 배치 테스트가 회귀하지 않게 하기 위한 fixture 편의 동작이다.
         */
        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> executeBatchAndReturnGeneratedKeys(
                String sql, List<List<Object>> bindingsList, String idColumn, Class<T> idType) {
            this.batchCalls.add(new BatchCall(sql, List.copyOf(bindingsList)));
            this.batchGeneratedIdColumns.add(idColumn);
            this.batchGeneratedIdTypes.add(idType);
            List<Object> keys;
            if (!batchGeneratedKeys.isEmpty()) {
                keys = batchGeneratedKeys.removeFirst();
            } else {
                keys = new ArrayList<>(bindingsList.size());
                for (int i = 0; i < bindingsList.size(); i++) {
                    keys.add((long) (i + 1));
                }
            }
            return Flux.fromIterable(keys).map(value -> (T) value);
        }
    }

    private record BatchCall(String sql, List<List<Object>> bindingsList) {
    }

    /**
     * 호출 순서(queryOne vs execute)를 기록해 SEQUENCE 조회와 INSERT의 순서를 검증할 수 있게 한다.
     */
    private static final class OrderedCapturingExecutor implements SqlExecutor {
        private final Deque<RowAccessor> queryOneResults = new ArrayDeque<>();
        private final Deque<Long> executeResults = new ArrayDeque<>();
        private final List<BatchCall> batchCalls = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private SqlStatement sequenceQuery;

        @Override
        public Mono<Long> execute(SqlStatement statement) {
            calls.add("execute");
            long result = executeResults.isEmpty() ? 1L : executeResults.removeFirst();
            return Mono.just(result);
        }

        @Override
        public <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper) {
            calls.add("queryOne");
            if (sequenceQuery == null) {
                sequenceQuery = statement;
            }
            return Mono.fromSupplier(() -> mapper.apply(queryOneResults.removeFirst()));
        }

        @Override
        public <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
            calls.add("executeBatch");
            batchCalls.add(new BatchCall(sql, List.copyOf(bindingsList)));
            return Mono.just((long) bindingsList.size());
        }
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
