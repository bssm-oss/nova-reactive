package io.nova.r2dbc;

import io.nova.core.SqlExecutionListener;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2dbcSqlExecutor가 SqlExecutionListener를 모든 execute/query/batch 경로에 일관되게 호출하는지 검증한다.
 */
class R2dbcSqlExecutorListenerTest {
    private static final Dialect NOOP_DIALECT = new Dialect() {
        @Override public String name() { return "noop"; }
        @Override public String quote(String identifier) { return identifier; }
        @Override public BindMarkerStrategy bindMarkers() { return index -> "?"; }
        @Override public SqlRenderer sqlRenderer() { throw new UnsupportedOperationException(); }
        @Override public SchemaGenerator schemaGenerator() { throw new UnsupportedOperationException(); }
    };

    private ConnectionFactory connectionFactory;
    private CapturingListener listener;
    private R2dbcSqlExecutor executor;

    @BeforeEach
    void setUp() {
        String dbName = "test_" + UUID.randomUUID().toString().replace("-", "");
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        listener = new CapturingListener();
        executor = new R2dbcSqlExecutor(connectionFactory, NOOP_DIALECT, listener);

        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table accounts (id bigint primary key, email varchar(255) not null unique, active boolean not null)",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();

        // 스키마 생성도 listener에 보여야 한다 (before + after). 후속 단언이 쉬워지도록 reset.
        assertEquals(2, listener.events.size());
        assertEquals("before", listener.events.get(0).kind);
        assertEquals("after", listener.events.get(1).kind);
        listener.events.clear();
    }

    @Test
    void noArgConstructorDelegatesToNoOpListener() {
        R2dbcSqlExecutor noopExecutor = new R2dbcSqlExecutor(connectionFactory, NOOP_DIALECT);
        // listener를 명시하지 않은 생성자도 정상 동작해야 한다.
        StepVerifier.create(noopExecutor.execute(new SqlStatement(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(99L, "noop@nova.io", true))))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void executeFiresBeforeThenAfterWithDriverUpdateRowCount() {
        SqlStatement insert = new SqlStatement(
                "insert into accounts (id, email, active) values (?, ?, ?)",
                List.of(1L, "a@nova.io", true));

        StepVerifier.create(executor.execute(insert))
                .expectNext(1L)
                .verifyComplete();

        assertEquals(2, listener.events.size());
        Event before = listener.events.get(0);
        Event after = listener.events.get(1);
        assertEquals("before", before.kind);
        assertSame(insert, before.statement);
        assertEquals("after", after.kind);
        assertSame(insert, after.statement);
        assertEquals(1L, after.affectedRows);
        assertNotNull(after.elapsed);
        assertTrue(after.elapsed.toNanos() >= 0);
    }

    @Test
    void queryOneFiresAfterWithRowCountOneWhenMatchFound() {
        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(1L, "a@nova.io", true))))
                .expectNextCount(1)
                .verifyComplete();
        listener.events.clear();

        SqlStatement select = new SqlStatement(
                "select email from accounts where id = ?", List.of(1L));

        StepVerifier.create(executor.queryOne(select, row -> row.get("email", String.class)))
                .expectNext("a@nova.io")
                .verifyComplete();

        assertEquals(2, listener.events.size());
        assertEquals("before", listener.events.get(0).kind);
        Event after = listener.events.get(1);
        assertEquals("after", after.kind);
        assertSame(select, after.statement);
        assertEquals(1L, after.affectedRows);
    }

    @Test
    void queryOneFiresAfterWithRowCountZeroWhenNoMatch() {
        SqlStatement select = new SqlStatement(
                "select email from accounts where id = ?", List.of(404L));

        StepVerifier.create(executor.queryOne(select, row -> row.get("email", String.class)))
                .verifyComplete();

        assertEquals(2, listener.events.size());
        Event after = listener.events.get(1);
        assertEquals("after", after.kind);
        assertEquals(0L, after.affectedRows);
    }

    @Test
    void queryManyFiresAfterWithEmittedRowCount() {
        List<List<Object>> bindings = List.of(
                List.of(1L, "a@nova.io", true),
                List.of(2L, "b@nova.io", false),
                List.of(3L, "c@nova.io", true));

        StepVerifier.create(executor.executeBatch(
                        "insert into accounts (id, email, active) values (?, ?, ?)", bindings))
                .expectNext(3L)
                .verifyComplete();
        listener.events.clear();

        SqlStatement select = new SqlStatement(
                "select id from accounts order by id", List.of());

        StepVerifier.create(executor.queryMany(select, row -> row.get("id", Long.class)))
                .expectNext(1L, 2L, 3L)
                .verifyComplete();

        assertEquals(2, listener.events.size());
        Event after = listener.events.get(1);
        assertEquals("after", after.kind);
        assertEquals(3L, after.affectedRows);
    }

    @Test
    void executeBatchFiresAfterWithTotalRowCount() {
        List<List<Object>> bindings = List.of(
                List.of(1L, "a@nova.io", true),
                List.of(2L, "b@nova.io", false));

        StepVerifier.create(executor.executeBatch(
                        "insert into accounts (id, email, active) values (?, ?, ?)", bindings))
                .expectNext(2L)
                .verifyComplete();

        assertEquals(2, listener.events.size());
        Event before = listener.events.get(0);
        Event after = listener.events.get(1);
        assertEquals("before", before.kind);
        assertEquals("after", after.kind);
        assertEquals(2L, after.affectedRows);
    }

    @Test
    void emptyExecuteBatchSkipsListenerEntirely() {
        StepVerifier.create(executor.executeBatch(
                        "insert into accounts (id, email, active) values (?, ?, ?)", List.of()))
                .expectNext(0L)
                .verifyComplete();

        assertEquals(0, listener.events.size(),
                "빈 batch는 driver를 호출하지 않으므로 listener도 호출되지 않는다");
    }

    @Test
    void executeFiresOnErrorWhenDriverThrows() {
        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(1L, "dup@nova.io", true))))
                .expectNextCount(1)
                .verifyComplete();
        listener.events.clear();

        SqlStatement duplicate = new SqlStatement(
                "insert into accounts (id, email, active) values (?, ?, ?)",
                List.of(2L, "dup@nova.io", true));

        StepVerifier.create(executor.execute(duplicate))
                .expectError()
                .verify();

        assertEquals(2, listener.events.size());
        assertEquals("before", listener.events.get(0).kind);
        Event errorEvent = listener.events.get(1);
        assertEquals("error", errorEvent.kind);
        assertSame(duplicate, errorEvent.statement);
        assertNotNull(errorEvent.error);
        assertNotNull(errorEvent.elapsed);
    }

    @Test
    void executeBatchFiresOnErrorWhenBindingViolatesConstraint() {
        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(1L, "dup@nova.io", true))))
                .expectNextCount(1)
                .verifyComplete();
        listener.events.clear();

        StepVerifier.create(executor.executeBatch(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(
                                List.of(2L, "ok@nova.io", true),
                                List.of(3L, "dup@nova.io", false))))
                .expectError()
                .verify();

        assertTrue(listener.events.size() >= 2);
        assertEquals("before", listener.events.get(0).kind);
        assertEquals("error", listener.events.get(listener.events.size() - 1).kind);
    }

    @Test
    void queryOneFiresOnErrorWhenSqlInvalid() {
        SqlStatement invalid = new SqlStatement("select * from no_such_table", List.of());

        StepVerifier.create(executor.queryOne(invalid, row -> row.get("id", Long.class)))
                .expectError()
                .verify();

        assertEquals(2, listener.events.size());
        assertEquals("before", listener.events.get(0).kind);
        Event errorEvent = listener.events.get(1);
        assertEquals("error", errorEvent.kind);
        assertNotNull(errorEvent.error);
    }

    @Test
    void queryManyFiresOnErrorWhenSqlInvalid() {
        SqlStatement invalid = new SqlStatement("select * from no_such_table", List.of());

        StepVerifier.create(executor.queryMany(invalid, row -> row.get("id", Long.class)))
                .expectError()
                .verify();

        assertTrue(listener.events.size() >= 2);
        assertEquals("before", listener.events.get(0).kind);
        assertEquals("error", listener.events.get(listener.events.size() - 1).kind);
    }

    /**
     * 모든 hook 호출을 기록하는 test-local listener.
     */
    private static final class CapturingListener implements SqlExecutionListener {
        final List<Event> events = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            events.add(new Event("before", statement, null, 0L, null));
        }

        @Override
        public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
            events.add(new Event("after", statement, elapsed, affectedRows, null));
        }

        @Override
        public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
            events.add(new Event("error", statement, elapsed, 0L, error));
        }
    }

    private record Event(String kind, SqlStatement statement, Duration elapsed, long affectedRows, Throwable error) {
    }
}
