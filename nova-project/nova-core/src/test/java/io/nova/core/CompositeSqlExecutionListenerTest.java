package io.nova.core;

import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeSqlExecutionListenerTest {
    private static final SqlStatement STATEMENT = new SqlStatement("select 1", List.of());

    @Test
    void onBeforeExecutionFansOutInDeclaredOrder() {
        List<String> order = new ArrayList<>();
        SqlExecutionListener first = new RecordingListener("first", order);
        SqlExecutionListener second = new RecordingListener("second", order);
        SqlExecutionListener third = new RecordingListener("third", order);

        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(List.of(first, second, third));

        composite.onBeforeExecution(STATEMENT);

        assertEquals(List.of("first:before", "second:before", "third:before"), order);
    }

    @Test
    void onAfterExecutionFansOutInDeclaredOrderWithSameArguments() {
        List<String> order = new ArrayList<>();
        List<Long> rowCounts = new ArrayList<>();
        List<Duration> elapseds = new ArrayList<>();
        SqlExecutionListener first = new SqlExecutionListener() {
            @Override
            public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
                order.add("first:after");
                rowCounts.add(affectedRows);
                elapseds.add(elapsed);
            }
        };
        SqlExecutionListener second = new SqlExecutionListener() {
            @Override
            public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
                order.add("second:after");
                rowCounts.add(affectedRows);
                elapseds.add(elapsed);
            }
        };

        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(first, second);
        Duration elapsed = Duration.ofMillis(7);
        composite.onAfterExecution(STATEMENT, elapsed, 3L);

        assertEquals(List.of("first:after", "second:after"), order);
        assertEquals(List.of(3L, 3L), rowCounts);
        assertEquals(List.of(elapsed, elapsed), elapseds);
    }

    @Test
    void onErrorFansOutInDeclaredOrderWithSameThrowable() {
        List<Throwable> seen = new ArrayList<>();
        SqlExecutionListener first = new SqlExecutionListener() {
            @Override
            public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
                seen.add(error);
            }
        };
        SqlExecutionListener second = new SqlExecutionListener() {
            @Override
            public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
                seen.add(error);
            }
        };

        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(first, second);
        IllegalStateException boom = new IllegalStateException("boom");
        composite.onError(STATEMENT, Duration.ZERO, boom);

        assertEquals(2, seen.size());
        assertSame(boom, seen.get(0));
        assertSame(boom, seen.get(1));
    }

    @Test
    void onBeforeExecutionContinuesCallingOtherListenersAfterFailure() {
        List<String> order = new ArrayList<>();
        SqlExecutionListener firstFails = new SqlExecutionListener() {
            @Override
            public void onBeforeExecution(SqlStatement statement) {
                order.add("first:before");
                throw new IllegalStateException("first boom");
            }
        };
        SqlExecutionListener secondFails = new SqlExecutionListener() {
            @Override
            public void onBeforeExecution(SqlStatement statement) {
                order.add("second:before");
                throw new IllegalArgumentException("second boom");
            }
        };
        SqlExecutionListener thirdOk = new RecordingListener("third", order);

        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(firstFails, secondFails, thirdOk);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> composite.onBeforeExecution(STATEMENT));

        assertEquals("first boom", thrown.getMessage(),
                "가장 먼저 발생한 예외가 throw되어야 한다");
        assertEquals(1, thrown.getSuppressed().length,
                "이후 예외는 suppressed로 attach되어야 한다");
        assertTrue(thrown.getSuppressed()[0] instanceof IllegalArgumentException);
        assertEquals("second boom", thrown.getSuppressed()[0].getMessage());

        assertEquals(List.of("first:before", "second:before", "third:before"), order,
                "한 listener가 throw해도 이후 listener는 계속 호출되어야 한다");
    }

    @Test
    void onAfterExecutionContinuesCallingOtherListenersAfterFailure() {
        List<String> order = new ArrayList<>();
        SqlExecutionListener firstFails = new SqlExecutionListener() {
            @Override
            public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
                order.add("first:after");
                throw new IllegalStateException("first boom");
            }
        };
        SqlExecutionListener secondOk = new SqlExecutionListener() {
            @Override
            public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
                order.add("second:after");
            }
        };

        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(firstFails, secondOk);

        assertThrows(IllegalStateException.class,
                () -> composite.onAfterExecution(STATEMENT, Duration.ZERO, 1L));

        assertEquals(List.of("first:after", "second:after"), order);
    }

    @Test
    void onErrorContinuesCallingOtherListenersAfterFailure() {
        List<String> order = new ArrayList<>();
        SqlExecutionListener firstFails = new SqlExecutionListener() {
            @Override
            public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
                order.add("first:error");
                throw new IllegalStateException("listener-side boom");
            }
        };
        SqlExecutionListener secondOk = new SqlExecutionListener() {
            @Override
            public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
                order.add("second:error");
            }
        };

        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(firstFails, secondOk);

        assertThrows(IllegalStateException.class,
                () -> composite.onError(STATEMENT, Duration.ZERO, new RuntimeException("original")));

        assertEquals(List.of("first:error", "second:error"), order);
    }

    @Test
    void delegatesListIsUnmodifiable() {
        SqlExecutionListener noop = SqlExecutionListener.NO_OP;
        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(List.of(noop));

        assertThrows(UnsupportedOperationException.class,
                () -> composite.delegates().add(noop));
    }

    @Test
    void compositeWithoutListenersIsNoOp() {
        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(List.of());

        composite.onBeforeExecution(STATEMENT);
        composite.onAfterExecution(STATEMENT, Duration.ZERO, 0L);
        composite.onError(STATEMENT, Duration.ZERO, new RuntimeException("x"));
    }

    @Test
    void ofWithZeroListenersReturnsNoOp() {
        SqlExecutionListener listener = CompositeSqlExecutionListener.of();

        assertSame(SqlExecutionListener.NO_OP, listener,
                "0개 listener는 NO_OP을 반환해야 한다");
    }

    @Test
    void ofWithSingleListenerReturnsItDirectly() {
        SqlExecutionListener only = new RecordingListener("only", new ArrayList<>());

        SqlExecutionListener listener = CompositeSqlExecutionListener.of(only);

        assertSame(only, listener,
                "1개 listener는 그대로 반환하여 불필요한 wrapping을 피해야 한다");
    }

    @Test
    void ofWithMultipleListenersReturnsComposite() {
        List<String> order = new ArrayList<>();
        SqlExecutionListener first = new RecordingListener("first", order);
        SqlExecutionListener second = new RecordingListener("second", order);

        SqlExecutionListener listener = CompositeSqlExecutionListener.of(first, second);

        assertTrue(listener instanceof CompositeSqlExecutionListener,
                "2개 이상은 Composite로 wrapping되어야 한다: " + listener.getClass());
        listener.onBeforeExecution(STATEMENT);
        assertEquals(List.of("first:before", "second:before"), order);
    }

    @Test
    void ofRejectsNullArray() {
        assertThrows(NullPointerException.class,
                () -> CompositeSqlExecutionListener.of((SqlExecutionListener[]) null));
    }

    @Test
    void ofRejectsNullElement() {
        assertThrows(NullPointerException.class,
                () -> CompositeSqlExecutionListener.of(SqlExecutionListener.NO_OP, null));
    }

    @Test
    void ofRejectsSingleNullElement() {
        assertThrows(NullPointerException.class,
                () -> CompositeSqlExecutionListener.of((SqlExecutionListener) null));
    }

    @Test
    void addReturnsNewCompositeAndDoesNotMutateOriginal() {
        List<String> order = new ArrayList<>();
        SqlExecutionListener first = new RecordingListener("first", order);
        SqlExecutionListener second = new RecordingListener("second", order);

        CompositeSqlExecutionListener original = new CompositeSqlExecutionListener(List.of(first));
        CompositeSqlExecutionListener extended = original.add(second);

        assertNotSame(original, extended, "add는 새 Composite를 반환해야 한다");
        assertEquals(1, original.delegates().size(),
                "원본 Composite의 delegate 목록은 변하면 안 된다");
        assertSame(first, original.delegates().get(0));
        assertEquals(2, extended.delegates().size());
        assertSame(first, extended.delegates().get(0));
        assertSame(second, extended.delegates().get(1));

        extended.onBeforeExecution(STATEMENT);
        assertEquals(List.of("first:before", "second:before"), order);
    }

    @Test
    void addRejectsNull() {
        CompositeSqlExecutionListener composite =
                new CompositeSqlExecutionListener(List.of(SqlExecutionListener.NO_OP));

        assertThrows(NullPointerException.class, () -> composite.add(null));
    }

    @Test
    void withoutNoOpStripsNoOpDelegates() {
        List<String> order = new ArrayList<>();
        SqlExecutionListener real = new RecordingListener("real", order);

        CompositeSqlExecutionListener composite = new CompositeSqlExecutionListener(
                List.of(SqlExecutionListener.NO_OP, real, SqlExecutionListener.NO_OP));

        CompositeSqlExecutionListener stripped = composite.withoutNoOp();

        assertNotSame(composite, stripped);
        assertEquals(List.of(real), stripped.delegates());
        assertEquals(3, composite.delegates().size(),
                "원본은 변하지 않아야 한다");
    }

    @Test
    void withoutNoOpReturnsSameInstanceWhenNothingToStrip() {
        SqlExecutionListener real = new RecordingListener("real", new ArrayList<>());
        CompositeSqlExecutionListener composite =
                new CompositeSqlExecutionListener(List.of(real));

        CompositeSqlExecutionListener result = composite.withoutNoOp();

        assertSame(composite, result,
                "제거 대상이 없으면 동일 instance를 그대로 반환해야 한다");
    }

    private record RecordingListener(String name, List<String> order) implements SqlExecutionListener {
        @Override
        public void onBeforeExecution(SqlStatement statement) {
            order.add(name + ":before");
        }
    }
}
