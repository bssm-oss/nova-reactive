package io.nova.cache;

import io.nova.cache.annotation.Cache;
import io.nova.cache.annotation.CacheConcurrencyStrategy;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 엔티티 타입에서 {@code jakarta.persistence.Cacheable}과 Nova {@link Cache} 애너테이션을 리플렉션으로 읽어
 * {@link CacheConfiguration}으로 해석한다. nova-core 메타데이터를 건드리지 않고 캐시 모듈 안에서 독립적으로
 * 캐시 여부를 판정하므로 core 수정이 불필요하다(격리 우선).
 *
 * <p>해석 규칙(JPA {@code ENABLE_SELECTIVE} 시맨틱):
 * <ul>
 *   <li>{@code @Cacheable}이 있으면 그 {@code value()}가 캐시 여부를 결정한다({@code @Cacheable(false)}는 비활성).</li>
 *   <li>{@code @Cacheable}이 없고 {@code @Cache}만 있으면 캐시 대상으로 간주한다({@code @Cache}가 활성화를 함의).</li>
 *   <li>둘 다 없으면 캐시하지 않는다.</li>
 * </ul>
 *
 * <p>region은 {@code @Cache.region()}(비면) 또는 엔티티 클래스의 정규 이름이다. 동시성 전략은
 * {@code @Cache.usage()}(기본 {@link CacheConcurrencyStrategy#READ_WRITE})이며, v1 미지원 전략
 * ({@link CacheConcurrencyStrategy#NONSTRICT_READ_WRITE}, {@link CacheConcurrencyStrategy#TRANSACTIONAL})은
 * 조용히 무시하지 않고 {@link IllegalStateException}으로 fail-fast 거부한다.
 *
 * <p>애너테이션은 클래스 계층을 상향 탐색해 찾는다 — {@code @Inherited} 여부에 의존하지 않는다
 * (프로젝트 학습: generator/marker 애너테이션의 상속 해석 함정 회피).
 */
public final class CacheConfigurationResolver {

    private static final Set<CacheConcurrencyStrategy> SUPPORTED =
            EnumSet.of(CacheConcurrencyStrategy.READ_ONLY, CacheConcurrencyStrategy.READ_WRITE);

    private final ConcurrentHashMap<Class<?>, CacheConfiguration> cache = new ConcurrentHashMap<>();

    /**
     * 엔티티 타입의 캐시 설정을 해석한다. 결과는 타입별로 캐시된다. 미지원 동시성 전략이면
     * {@link IllegalStateException}을 던진다(fail-fast).
     */
    public CacheConfiguration resolve(Class<?> entityType) {
        java.util.Objects.requireNonNull(entityType, "entityType must not be null");
        return cache.computeIfAbsent(entityType, this::compute);
    }

    private CacheConfiguration compute(Class<?> entityType) {
        jakarta.persistence.Cacheable cacheable = findAnnotation(entityType, jakarta.persistence.Cacheable.class);
        Cache cacheAnn = findAnnotation(entityType, Cache.class);

        boolean isCacheable;
        if (cacheable != null) {
            isCacheable = cacheable.value();
        } else {
            isCacheable = cacheAnn != null;
        }

        if (!isCacheable) {
            return CacheConfiguration.notCacheable();
        }

        String region;
        CacheConcurrencyStrategy usage;
        if (cacheAnn != null) {
            region = cacheAnn.region().isBlank() ? entityType.getName() : cacheAnn.region();
            usage = cacheAnn.usage();
        } else {
            region = entityType.getName();
            usage = CacheConcurrencyStrategy.READ_WRITE;
        }

        if (!SUPPORTED.contains(usage)) {
            throw new IllegalStateException(
                    "Unsupported cache concurrency strategy " + usage + " on " + entityType.getName()
                            + " (Nova v1 supports READ_ONLY and READ_WRITE only)");
        }

        return new CacheConfiguration(true, region, usage);
    }

    private static <A extends java.lang.annotation.Annotation> A findAnnotation(Class<?> type, Class<A> annotationType) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            A found = current.getDeclaredAnnotation(annotationType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
