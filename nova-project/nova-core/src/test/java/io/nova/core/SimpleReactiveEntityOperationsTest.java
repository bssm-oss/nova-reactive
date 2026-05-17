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
import io.nova.support.fixtures.FixtureEntities.NoDefaultConstructorEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
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
    void saveUsesInsertForNewEntity() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor, new RecordingTransactions());
        SampleAccount account = new SampleAccount(null, "new@nova.io", true);

        StepVerifier.create(operations.save(account))
                .expectNext(account)
                .verifyComplete();

        assertEquals(
                "insert into accounts (email_address, active) values (?, ?)",
                executor.lastStatement.sql()
        );
        assertEquals(List.of("new@nova.io", true), executor.lastStatement.bindings());
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

    private static final class CapturingExecutor implements SqlExecutor {
        private final Deque<RowAccessor> queryOneResults = new ArrayDeque<>();
        private final Deque<List<RowAccessor>> queryManyResults = new ArrayDeque<>();
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
            List<RowAccessor> rows = queryManyResults.removeFirst();
            return Flux.fromIterable(rows).map(mapper);
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
