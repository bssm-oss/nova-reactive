package io.nova.core;

import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlowQueryLoggingListenerTest {
    private static final SqlStatement STATEMENT =
            new SqlStatement("select * from users where ssn = ?", List.of("123-45-6789"));

    @Test
    void belowThresholdProducesNoLog() {
        List<String> log = new ArrayList<>();
        SlowQueryLoggingListener listener =
                new SlowQueryLoggingListener(Duration.ofMillis(100), log::add);

        listener.onAfterExecution(STATEMENT, Duration.ofMillis(50), 1L);

        assertTrue(log.isEmpty(), "임계치 미만은 로그를 남기지 않아야 한다");
    }

    @Test
    void atOrAboveThresholdProducesSingleLogWithFormat() {
        List<String> log = new ArrayList<>();
        SlowQueryLoggingListener listener =
                new SlowQueryLoggingListener(Duration.ofMillis(100), log::add);

        listener.onAfterExecution(STATEMENT, Duration.ofMillis(150), 7L);

        assertEquals(1, log.size(), "임계치 이상은 정확히 한 줄을 로깅해야 한다");
        String entry = log.get(0);
        assertTrue(entry.contains("150 ms"), "elapsed ms가 포함되어야 한다: " + entry);
        assertTrue(entry.contains("affected=7"), "affected row count가 포함되어야 한다: " + entry);
        assertTrue(entry.contains(STATEMENT.sql()), "SQL이 포함되어야 한다: " + entry);
    }

    @Test
    void exactlyAtThresholdLogsToo() {
        List<String> log = new ArrayList<>();
        SlowQueryLoggingListener listener =
                new SlowQueryLoggingListener(Duration.ofMillis(100), log::add);

        listener.onAfterExecution(STATEMENT, Duration.ofMillis(100), 0L);

        assertEquals(1, log.size(), "threshold 동등 시점도 slow로 간주해야 한다");
    }

    @Test
    void zeroThresholdLogsEveryQuery() {
        List<String> log = new ArrayList<>();
        SlowQueryLoggingListener listener =
                new SlowQueryLoggingListener(Duration.ZERO, log::add);

        listener.onAfterExecution(STATEMENT, Duration.ZERO, 0L);
        listener.onAfterExecution(STATEMENT, Duration.ofMillis(1), 0L);

        assertEquals(2, log.size(), "ZERO threshold는 모든 쿼리를 로깅해야 한다");
    }

    @Test
    void logMessageDoesNotContainBindings() {
        List<String> log = new ArrayList<>();
        SlowQueryLoggingListener listener =
                new SlowQueryLoggingListener(Duration.ofMillis(10), log::add);

        listener.onAfterExecution(STATEMENT, Duration.ofMillis(20), 1L);

        assertEquals(1, log.size());
        assertFalse(log.get(0).contains("123-45-6789"),
                "PII 보호를 위해 binding 값은 로그에 노출되어선 안 된다: " + log.get(0));
    }

    @Test
    void onErrorReportsElapsedAndExceptionType() {
        List<String> log = new ArrayList<>();
        SlowQueryLoggingListener listener =
                new SlowQueryLoggingListener(Duration.ofMillis(100), log::add);
        IllegalStateException boom = new IllegalStateException("connection reset");

        listener.onError(STATEMENT, Duration.ofMillis(42), boom);

        assertEquals(1, log.size(), "error 경로는 elapsed 와 함께 한 줄을 로깅해야 한다");
        String entry = log.get(0);
        assertTrue(entry.contains("42 ms"), "elapsed ms 포함 필요: " + entry);
        assertTrue(entry.contains("IllegalStateException"),
                "에러 타입 포함 필요: " + entry);
        assertTrue(entry.contains(STATEMENT.sql()), "SQL 포함 필요: " + entry);
        assertFalse(entry.contains("123-45-6789"),
                "error 경로에서도 binding 값은 노출되어선 안 된다: " + entry);
    }

    @Test
    void onBeforeExecutionIsNoOp() {
        List<String> log = new ArrayList<>();
        SlowQueryLoggingListener listener =
                new SlowQueryLoggingListener(Duration.ZERO, log::add);

        listener.onBeforeExecution(STATEMENT);

        assertTrue(log.isEmpty(), "onBeforeExecution은 no-op이어야 한다");
    }

    @Test
    void nullThresholdRejected() {
        assertThrows(NullPointerException.class,
                () -> new SlowQueryLoggingListener(null));
    }

    @Test
    void nullLoggerRejected() {
        assertThrows(NullPointerException.class,
                () -> new SlowQueryLoggingListener(Duration.ofMillis(1), null));
    }

    @Test
    void negativeThresholdRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SlowQueryLoggingListener(Duration.ofMillis(-1)));
        assertNotNull(ex.getMessage());
    }

    @Test
    void defaultConstructorUsesStderrAndIsConstructible() {
        Duration threshold = Duration.ofMillis(1);
        SlowQueryLoggingListener listener = new SlowQueryLoggingListener(threshold);
        assertEquals(threshold, listener.threshold());
    }
}
