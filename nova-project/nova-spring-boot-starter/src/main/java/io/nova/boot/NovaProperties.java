package io.nova.boot;

import io.nova.schema.DdlAuto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Nova starter 환경 설정 prefix {@code nova.*} 바인딩 holder. Spring Boot의
 * relaxed binder가 채우는 mutable Java bean 구조다.
 */
@ConfigurationProperties(prefix = "nova")
public class NovaProperties {

    private final Pool pool = new Pool();
    private final SlowQuery slowQuery = new SlowQuery();

    /**
     * Schema lifecycle policy applied on Spring context startup, mirroring
     * JPA's {@code spring.jpa.hibernate.ddl-auto}. Defaults to
     * {@link DdlAuto#NONE} so the starter never touches an unsuspecting
     * database. Phase 1 supports {@code NONE}, {@code CREATE}, and
     * {@code CREATE_DROP}; the JPA {@code UPDATE} / {@code VALIDATE} modes
     * require schema introspection that is not yet implemented.
     */
    private DdlAuto ddlAuto = DdlAuto.NONE;

    /**
     * Explicit list of packages to scan for {@code @Entity} classes when
     * {@link #ddlAuto} runs the bootstrap. When empty, the starter falls back
     * to the packages registered by
     * {@code @SpringBootApplication}/{@code @EnableAutoConfiguration} via
     * {@code AutoConfigurationPackages}.
     */
    private List<String> entityPackages = new ArrayList<>();

    public Pool getPool() {
        return pool;
    }

    public SlowQuery getSlowQuery() {
        return slowQuery;
    }

    public DdlAuto getDdlAuto() {
        return ddlAuto;
    }

    public void setDdlAuto(DdlAuto ddlAuto) {
        this.ddlAuto = ddlAuto == null ? DdlAuto.NONE : ddlAuto;
    }

    public List<String> getEntityPackages() {
        return entityPackages;
    }

    public void setEntityPackages(List<String> entityPackages) {
        this.entityPackages = entityPackages == null ? new ArrayList<>() : entityPackages;
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
