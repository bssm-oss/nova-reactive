package io.nova.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Nova starter 환경 설정 prefix {@code nova.*} 바인딩 holder. Spring Boot의
 * relaxed binder가 채우는 mutable Java bean 구조다.
 */
@ConfigurationProperties(prefix = "nova")
public class NovaProperties {

    private final Pool pool = new Pool();
    private final SlowQuery slowQuery = new SlowQuery();

    public Pool getPool() {
        return pool;
    }

    public SlowQuery getSlowQuery() {
        return slowQuery;
    }

    /**
     * Connection pool 관련 속성. {@link io.nova.r2dbc.PoolConfig} 필드와 1:1로 매핑된다 —
     * 어느 하나라도 명시되면 {@code NovaAutoConfiguration}이 {@code PoolConfig} bean을
     * 등록하며, 미명시 필드는 {@link io.nova.r2dbc.PoolConfig#defaults()}의 기본값을 채택한다.
     */
    public static class Pool {
        private Integer initialSize;
        private Integer maxSize;
        private Duration maxIdleTime;
        private Duration acquireTimeout;

        public Integer getInitialSize() {
            return initialSize;
        }

        public void setInitialSize(Integer initialSize) {
            this.initialSize = initialSize;
        }

        public Integer getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(Integer maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getMaxIdleTime() {
            return maxIdleTime;
        }

        public void setMaxIdleTime(Duration maxIdleTime) {
            this.maxIdleTime = maxIdleTime;
        }

        public Duration getAcquireTimeout() {
            return acquireTimeout;
        }

        public void setAcquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
        }

        boolean hasAnyOverride() {
            return initialSize != null || maxSize != null || maxIdleTime != null || acquireTimeout != null;
        }
    }

    /**
     * {@link io.nova.core.SlowQueryLoggingListener} 활성화 임계치. {@code thresholdMs}가
     * {@code null}이면 listener bean은 등록되지 않는다.
     */
    public static class SlowQuery {
        private Long thresholdMs;

        public Long getThresholdMs() {
            return thresholdMs;
        }

        public void setThresholdMs(Long thresholdMs) {
            this.thresholdMs = thresholdMs;
        }
    }
}
