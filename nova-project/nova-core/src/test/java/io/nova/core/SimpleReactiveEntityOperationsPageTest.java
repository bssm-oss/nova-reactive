package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Slice;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimpleReactiveEntityOperations#findAll(Class, QuerySpec, Pageable)}와
 * {@link SimpleReactiveEntityOperations#findSlice(Class, QuerySpec, Pageable)}의 SQL 발행
 * 시퀀스와 결과 wrapping을 in-memory {@link SqlExecutor} double로 검증한다. 두 메서드 모두
 * 외부 driver를 거치지 않으므로 dialect 비종속이며, R2DBC integration은 별도 테스트가 보장한다.
 */
class SimpleReactiveEntityOperationsPageTest {

    @Test
    void findAllByPageableEmitsSelectAndCountStatementsAndAggregatesIntoPage() {
        PageCapturingExecutor executor = new PageCapturingExecutor();
        // SELECT 결과로 3건 적재, COUNT 결과로 totalElements 10 적재
        executor.queryManyResults.addLast(List.of(
                row(101L, "a@nova.io", true),
                row(102L, "b@nova.io", false),
                row(103L, "c@nova.io", true)
        ));
        executor.queryOneResults.addLast(row(Map.of("count", 10L)));

        SimpleReactiveEntityOperations operations = newOperations(executor);

        Pageable pageable = Pageable.of(3, 6L); // 0-based page index = 2
        StepVerifier.create(operations.findAll(SampleAccount.class, QuerySpec.empty(), pageable))
                .assertNext(page -> {
                    assertEquals(3, page.content().size());
                    assertEquals(10L, page.totalElements());
                    assertEquals(2, page.number(), "offset/limit = 6/3 = 2");
                    assertEquals(3, page.size());
                    assertEquals(4, page.totalPages(), "ceil(10 / 3) = 4");
                    assertTrue(page.hasNext(), "page 2/3(0-based) → hasNext=true");
                    assertTrue(page.hasPrevious());
                    assertFalse(page.isEmpty());
                    // entity 매핑 검증 (id 한 건만 sanity check)
                    assertEquals(101L, page.content().get(0).getId());
                })
                .verifyComplete();

        // SELECT와 COUNT 두 statement가 모두 발행되어야 한다.
        List<SqlStatement> issued = executor.allStatements;
        assertEquals(2, issued.size(),
                "findAll(Class, QuerySpec, Pageable)는 SELECT + COUNT 두 statement를 발행한다");
        long countCount = issued.stream().filter(s -> s.sql().startsWith("select count(")).count();
        long selectCount = issued.size() - countCount;
        assertEquals(1, selectCount, "SELECT 한 건이 발행되어야 한다");
        assertEquals(1, countCount, "COUNT 한 건이 발행되어야 한다");
        // SELECT statement에는 LIMIT/OFFSET 바인딩이 포함되어야 한다.
        SqlStatement selectStmt = issued.stream().filter(s -> !s.sql().startsWith("select count(")).findFirst().orElseThrow();
        assertTrue(selectStmt.sql().contains("limit"),
                "SELECT 절에 limit 클로즈가 포함되어야 한다: " + selectStmt.sql());
        assertTrue(selectStmt.bindings().contains(3),
                "SELECT 바인딩에 limit 값 3이 있어야 한다: " + selectStmt.bindings());
        assertTrue(selectStmt.bindings().contains(6L),
                "SELECT 바인딩에 offset 값 6 이 있어야 한다: " + selectStmt.bindings());
        // COUNT statement에는 LIMIT/OFFSET 바인딩이 빠져야 한다 (정확성 보장).
        SqlStatement countStmt = issued.stream().filter(s -> s.sql().startsWith("select count(")).findFirst().orElseThrow();
        assertFalse(countStmt.sql().contains("limit"),
                "COUNT 쿼리에는 limit이 없어야 한다: " + countStmt.sql());
    }

    @Test
    void findAllByPageableComputesCorrectPageNumberFromOffset() {
        PageCapturingExecutor executor = new PageCapturingExecutor();
        executor.queryManyResults.addLast(List.of(row(1L, "x@nova.io", true)));
        executor.queryOneResults.addLast(row(Map.of("count", 1L)));

        SimpleReactiveEntityOperations operations = newOperations(executor);
        Pageable pageable = Pageable.of(5, 0L);

        StepVerifier.create(operations.findAll(SampleAccount.class, QuerySpec.empty(), pageable))
                .assertNext(page -> {
                    assertEquals(0, page.number(), "offset 0 / limit 5 = 0");
                    assertFalse(page.hasNext(), "총 1건 / 페이지 크기 5 → 단일 페이지");
                    assertFalse(page.hasPrevious());
                })
                .verifyComplete();
    }

    @Test
    void findSliceProbesOneExtraRowToDetectHasNextAndTrimsContent() {
        PageCapturingExecutor executor = new PageCapturingExecutor();
        // limit 2 요청 → 내부적으로 limit 3으로 조회. 3건 반환 → hasNext=true, content는 2건으로 잘림.
        executor.queryManyResults.addLast(List.of(
                row(1L, "a@nova.io", true),
                row(2L, "b@nova.io", true),
                row(3L, "c@nova.io", true)
        ));

        SimpleReactiveEntityOperations operations = newOperations(executor);
        Pageable pageable = Pageable.of(2, 0L);

        StepVerifier.create(operations.findSlice(SampleAccount.class, QuerySpec.empty(), pageable))
                .assertNext(slice -> {
                    assertEquals(2, slice.content().size(), "content는 limit 만큼 trim 되어야 한다");
                    assertTrue(slice.hasNext(), "limit+1번째 행이 존재했으므로 hasNext=true");
                    assertEquals(0, slice.number());
                    assertFalse(slice.hasPrevious());
                    assertEquals(1L, slice.content().get(0).getId());
                    assertEquals(2L, slice.content().get(1).getId());
                })
                .verifyComplete();

        // findSlice는 COUNT 쿼리를 발행하지 않아야 한다.
        assertEquals(1, executor.allStatements.size(),
                "findSlice는 SELECT 한 번만 발행한다 (COUNT 없음)");
        SqlStatement issued = executor.allStatements.get(0);
        assertTrue(issued.bindings().contains(3),
                "SELECT는 limit+1 = 3으로 조회해야 한다: " + issued.bindings());
    }

    @Test
    void findSliceReturnsHasNextFalseWhenRowsAreAtOrBelowLimit() {
        PageCapturingExecutor executor = new PageCapturingExecutor();
        // limit 5 요청 → 내부적으로 limit 6으로 조회. 4건 반환 → hasNext=false, content 그대로 4건.
        executor.queryManyResults.addLast(List.of(
                row(1L, "a@nova.io", true),
                row(2L, "b@nova.io", true),
                row(3L, "c@nova.io", true),
                row(4L, "d@nova.io", true)
        ));

        SimpleReactiveEntityOperations operations = newOperations(executor);
        Pageable pageable = Pageable.of(5, 5L);

        StepVerifier.create(operations.findSlice(SampleAccount.class, QuerySpec.empty(), pageable))
                .assertNext(slice -> {
                    assertEquals(4, slice.content().size());
                    assertFalse(slice.hasNext(), "limit+1번째 행이 없었으므로 hasNext=false");
                    assertEquals(1, slice.number(), "offset 5 / limit 5 = 1");
                    assertTrue(slice.hasPrevious());
                })
                .verifyComplete();
    }

    @Test
    void findSliceTreatsExactlyLimitRowsAsLastPage() {
        PageCapturingExecutor executor = new PageCapturingExecutor();
        // limit 2 요청 → 정확히 2건 반환 → hasNext=false. probe가 3건째를 잡지 못한 경우.
        executor.queryManyResults.addLast(List.of(
                row(1L, "a@nova.io", true),
                row(2L, "b@nova.io", true)
        ));

        SimpleReactiveEntityOperations operations = newOperations(executor);
        Pageable pageable = Pageable.of(2, 0L);

        StepVerifier.create(operations.findSlice(SampleAccount.class, QuerySpec.empty(), pageable))
                .assertNext(slice -> {
                    assertEquals(2, slice.content().size());
                    assertFalse(slice.hasNext(), "정확히 limit 행이면 hasNext=false");
                })
                .verifyComplete();
    }

    private static MapRowAccessor row(Long id, String email, boolean active) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", id);
        values.put("email_address", email);
        values.put("active", active);
        return new MapRowAccessor(values);
    }

    private static MapRowAccessor row(Map<String, Object> values) {
        return new MapRowAccessor(new HashMap<>(values));
    }

    private static SimpleReactiveEntityOperations newOperations(PageCapturingExecutor executor) {
        return new SimpleReactiveEntityOperations(
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new TestDialect(),
                executor,
                new EntityStateDetector(),
                new InlineTransactions()
        );
    }

    private static final class TestDialect implements Dialect {
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
     * SELECT/COUNT 호출을 캡처하는 fixture executor. {@link #allStatements}에 모든 SQL을
     * 발행 순서대로 기록해 두 쿼리(SELECT, COUNT)가 모두 발행되었는지를 단언할 수 있게 한다.
     */
    private static final class PageCapturingExecutor implements SqlExecutor {
        private final Deque<List<MapRowAccessor>> queryManyResults = new ArrayDeque<>();
        private final Deque<MapRowAccessor> queryOneResults = new ArrayDeque<>();
        private final List<SqlStatement> allStatements = new ArrayList<>();

        @Override
        public Mono<Long> execute(SqlStatement statement) {
            allStatements.add(statement);
            return Mono.just(1L);
        }

        @Override
        public <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper) {
            allStatements.add(statement);
            assertNotNull(mapper, "mapper must not be null");
            return Mono.fromSupplier(() -> mapper.apply(queryOneResults.removeFirst()));
        }

        @Override
        public <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper) {
            allStatements.add(statement);
            assertNotNull(mapper, "mapper must not be null");
            List<MapRowAccessor> rows = queryManyResults.removeFirst();
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

    private static final class InlineTransactions implements ReactiveTransactionOperations {
        @Override
        public <T> Mono<T> inTransaction(Function<TransactionContext, Mono<T>> callback) {
            return callback.apply(() -> "test");
        }
    }
}
