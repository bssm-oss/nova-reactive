package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.tx.ReactiveTransactionOperations;
import io.nova.tx.TransactionContext;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @MapsId} 파생 식별자 save 경로(존재확인으로 insert/update 분기, 연관 PK를 owner @Id로 복사,
 * 미영속/누락 연관 fail-fast)를 test double로 검증한다. 실제 driver round-trip은 별도 H2 integration test가 담당한다.
 */
class SimpleReactiveEntityOperationsMapsIdTest {

    @Test
    void saveCopiesAssociatedPrimaryKeyIntoOwnerIdBeforeInsert() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        executor.emptyQueryOne = true; // 존재확인 SELECT → 없음 → insert 경로

        Master master = new Master();
        master.id = 42L;
        Detail detail = new Detail();
        detail.master = master;

        StepVerifier.create(operations.save(detail))
                .expectNextMatches(saved -> Objects.equals(saved.id, 42L)
                        && saved.master == master)
                .verifyComplete();

        assertEquals(42L, detail.id, "owner @Id가 연관 엔티티 PK로 파생되어야 한다");
        assertTrue(executor.lastStatement.sql().toLowerCase().contains("insert"),
                "존재하지 않으면 INSERT가 발화되어야 한다");
    }

    @Test
    void saveTakesUpdatePathWhenDerivedRowAlreadyExists() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        // 존재확인 SELECT가 row를 반환 → update 경로
        executor.queryOneResults.addLast(new MapRowAccessor(java.util.Map.of("id", 42L, "master_id", 42L)));

        Master master = new Master();
        master.id = 42L;
        Detail detail = new Detail();
        detail.master = master;

        StepVerifier.create(operations.save(detail))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(42L, detail.id);
        assertTrue(executor.lastStatement.sql().toLowerCase().contains("update"),
                "이미 존재하면 UPDATE가 발화되어야 한다");
    }

    @Test
    void saveRejectsNullAssociation() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);

        Detail detail = new Detail();
        detail.master = null;

        StepVerifier.create(operations.save(detail))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void saveRejectsAssociationWithNullPrimaryKey() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);

        Detail detail = new Detail();
        detail.master = new Master(); // id == null (미영속)

        StepVerifier.create(operations.save(detail))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    private SimpleReactiveEntityOperations newOperations(CapturingExecutor executor) {
        return new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new RecordingDialect(),
                executor,
                new EntityStateDetector(),
                new RecordingTransactions()
        );
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "maps_id_master")
    static class Master {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "maps_id_detail")
    static class Detail {
        @Id
        Long id;

        @OneToOne
        @MapsId
        @JoinColumn(name = "master_id")
        Master master;
    }

    // --- test doubles -------------------------------------------------------

    private static final class CapturingExecutor implements SqlExecutor {
        private final Deque<RowAccessor> queryOneResults = new ArrayDeque<>();
        private boolean emptyQueryOne;
        private SqlStatement lastStatement;

        @Override
        public Mono<Long> execute(SqlStatement statement) {
            this.lastStatement = statement;
            return Mono.just(1L);
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
            return Flux.empty();
        }

        @Override
        public <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
            return Mono.empty();
        }

        @Override
        public Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
            return Mono.just((long) bindingsList.size());
        }
    }

    private record MapRowAccessor(java.util.Map<String, Object> values) implements RowAccessor {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String columnName, Class<T> type) {
            return (T) values.get(columnName);
        }
    }

    private static final class RecordingTransactions implements ReactiveTransactionOperations {
        @Override
        public <T> Mono<T> inTransaction(Function<TransactionContext, Mono<T>> callback) {
            return callback.apply(() -> "test");
        }
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
}
