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
     * Connection pool 관련 속성. {@link io.nova.r2dbc.PoolConfig} 시그니처와 1:1로 매핑된다.
     */
    public static class Pool {
        private Integer maxSize;
        private Duration acquireTimeout;
        private Duration idleTimeout;

        public Integer getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(Integer maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getAcquireTimeout() {
            return acquireTimeout;
        }

        public void setAcquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
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
