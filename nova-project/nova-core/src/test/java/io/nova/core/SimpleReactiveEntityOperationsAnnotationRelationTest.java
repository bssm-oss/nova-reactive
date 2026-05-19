package io.nova.core;

import io.nova.fetch.FetchGroup;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.QuerySpec;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 어노테이션 기반 관계가 선언된 entity의 {@code findById}/{@code findAll}이 자동으로 child IN-query를
 * 발화하는지, 사용자 {@link FetchGroup}이 함께 주어지면 dedupe되는지 검증한다. R2DBC 호환은 별도
 * integration test가 담당하고, 여기서는 test double SqlExecutor로 SQL 발화 순서와 결과 주입을 확인한다.
 */
class SimpleReactiveEntityOperationsAnnotationRelationTest {
    @Test
    void findAllOnEntityWithOneToManyAutomaticallyHydratesChildren() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        // 1) parent select
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 1L, "name", "ada")),
                new MapRowAccessor(Map.of("id", 2L, "name", "ben"))
        ));
        // 2) child IN-query (annotation-driven)
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 10L, "title", "x", "author_id", 1L)),
                new MapRowAccessor(Map.of("id", 11L, "title", "y", "author_id", 1L)),
                new MapRowAccessor(Map.of("id", 20L, "title", "z", "author_id", 2L))
        ));

        List<AuthorWithBooksAnnotated> result = new ArrayList<>();
        StepVerifier.create(operations.findAll(AuthorWithBooksAnnotated.class, QuerySpec.empty()))
                .recordWith(() -> result)
                .expectNextCount(2)
                .verifyComplete();

        assertEquals(2, result.size());
        assertEquals(List.of(10L, 11L),
                result.get(0).getBooks().stream().map(BookWithAuthorAnnotated::getId).toList());
        assertEquals(List.of(20L),
                result.get(1).getBooks().stream().map(BookWithAuthorAnnotated::getId).toList());
        // 마지막 발화된 SQL은 child IN-query여야 한다.
        assertTrue(executor.lastStatement.sql().contains("annotated_books"),
                "child query는 annotated_books 테이블을 대상으로 한다");
        assertTrue(executor.lastStatement.sql().contains("author_id in"),
                "child IN-query는 author_id 컬럼을 사용한다");
    }

    @Test
    void findByIdOnEntityWithOneToManyAutomaticallyHydratesChildren() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("id", 1L, "name", "ada")));
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 10L, "title", "x", "author_id", 1L)),
                new MapRowAccessor(Map.of("id", 11L, "title", "y", "author_id", 1L))
        ));

        StepVerifier.create(operations.findById(AuthorWithBooksAnnotated.class, 1L))
                .expectNextMatches(parent -> Objects.equals(parent.getId(), 1L)
                        && parent.getBooks() != null
                        && parent.getBooks().size() == 2
                        && Objects.equals(parent.getBooks().get(0).getId(), 10L))
                .verifyComplete();
    }

    @Test
    void findByIdOnEntityWithManyToOneAutomaticallyHydratesSingleReference() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        // parent Book select — author_id 컬럼이 함께 내려와 mapRow는 그 값을 ignore하고 FetchGroup이 주입한다.
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of(
                "id", 100L, "title", "neuromancer", "author_id", 7L)));
        // child(=parent의 author) IN-query
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 7L, "name", "ada"))
        ));

        StepVerifier.create(operations.findById(BookWithAuthorAnnotated.class, 100L))
                .expectNextMatches(book -> Objects.equals(book.getId(), 100L)
                        && book.getAuthor() != null
                        && Objects.equals(book.getAuthor().getId(), 7L)
                        && "ada".equals(book.getAuthor().getName()))
                .verifyComplete();
    }

    @Test
    void findByIdOnEntityWithManyToOneAssignsNullReferenceWhenChildMissing() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of(
                "id", 100L, "title", "ghost", "author_id", 99L)));
        // child IN-query: empty result
        executor.queryManyResults.addLast(List.of());

        StepVerifier.create(operations.findById(BookWithAuthorAnnotated.class, 100L))
                .expectNextMatches(book -> Objects.equals(book.getId(), 100L)
                        && book.getAuthor() == null)
                .verifyComplete();
    }

    @Test
    void findByIdWithExplicitFetchGroupDedupesAgainstAnnotationDerivedSpec() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        // parent
        executor.queryOneResults.addLast(new MapRowAccessor(Map.of("id", 1L, "name", "ada")));
        // user/annotation은 동일한 (BookWithAuthorAnnotated, author_id) 페어이므로 child IN-query는 한 번만 발화된다.
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 10L, "title", "x", "author_id", 1L))
        ));

        FetchGroup<AuthorWithBooksAnnotated> userGroup = FetchGroup.forParents(AuthorWithBooksAnnotated.class)
                .with(BookWithAuthorAnnotated.class, "author_id",
                        AuthorWithBooksAnnotated::getId,
                        AuthorWithBooksAnnotated::setBooks)
                .build();

        StepVerifier.create(operations.findById(AuthorWithBooksAnnotated.class, 1L, userGroup))
                .expectNextMatches(parent -> parent.getBooks() != null && parent.getBooks().size() == 1)
                .verifyComplete();

        // 발화된 statement는 parent select(1) + child IN-query(1) = 2개여야 한다.
        // queryManyResults에서 마지막 리스트가 소진된 뒤 더 이상 child IN-query가 없어야 한다.
        assertTrue(executor.queryManyResults.isEmpty(),
                "user spec과 annotation spec이 동일 (childType, fkColumn) 페어면 child IN-query는 한 번만 발화돼야 한다");
    }

    @Test
    void findAllWithoutAnnotationRelationsTakesZeroOverheadPath() {
        // 관계가 없는 entity는 별도 자동 hydration 경로를 거치지 않고 단일 select만 발화한다.
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 1L, "name", "ada"))
        ));

        // forms a plain SELECT — child IN-query는 발화되지 않는다.
        operations.findAll(io.nova.support.fixtures.FixtureEntities.AssignedIdAccount.class, QuerySpec.empty())
                .as(StepVerifier::create)
                .expectNextCount(1)
                .verifyComplete();

        assertTrue(executor.queryManyResults.isEmpty(),
                "관계 어노테이션이 없으면 추가 IN-query가 발화되지 않아야 한다");
    }

    @Test
    void findByIdReturnsEmptyMonoWhenParentMissingAndSkipsChildQuery() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        executor.emptyQueryOne = true;

        StepVerifier.create(operations.findById(AuthorWithBooksAnnotated.class, 999L))
                .verifyComplete();

        // parent가 없으면 child IN-query는 발화되지 않는다.
        assertTrue(executor.queryManyResults.isEmpty());
    }

    @Test
    void findAllOnAuthorAssignsEmptyListWhenNoChildrenMatch() {
        CapturingExecutor executor = new CapturingExecutor();
        SimpleReactiveEntityOperations operations = newOperations(executor);
        executor.queryManyResults.addLast(List.of(
                new MapRowAccessor(Map.of("id", 1L, "name", "ada"))
        ));
        executor.queryManyResults.addLast(List.of());

        List<AuthorWithBooksAnnotated> result = new ArrayList<>();
        StepVerifier.create(operations.findAll(AuthorWithBooksAnnotated.class, QuerySpec.empty()))
                .recordWith(() -> result)
                .expectNextCount(1)
                .verifyComplete();

        assertNotNull(result.get(0).getBooks());
        assertTrue(result.get(0).getBooks().isEmpty(),
                "매칭되는 child가 없는 parent에는 빈 리스트가 주입되어야 한다");
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

        @Override
        public <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
            return Mono.empty();
        }

        @Override
        public Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
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
