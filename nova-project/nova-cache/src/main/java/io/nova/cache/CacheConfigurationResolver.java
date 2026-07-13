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
        Class<?> cacheableDecl = findDeclaringClass(entityType, jakarta.persistence.Cacheable.class);
        Class<?> cacheDecl = findDeclaringClass(entityType, Cache.class);
        jakarta.persistence.Cacheable cacheable =
                cacheableDecl == null ? null : cacheableDecl.getDeclaredAnnotation(jakarta.persistence.Cacheable.class);
        Cache cacheAnn = cacheDecl == null ? null : cacheDecl.getDeclaredAnnotation(Cache.class);

        boolean isCacheable = cacheable != null ? cacheable.value() : cacheAnn != null;
        if (!isCacheable) {
            return CacheConfiguration.notCacheable();
        }

        // canonical 타입 = 캐시 애너테이션을 선언한 조상 중 root-most(Object에 가장 가까운) 클래스.
        // 계층 어느 진입점(base findById, subtype save)에서 resolve 하든 같은 조상 선언 클래스를 찾으므로
        // 그중 상위 클래스를 canonical로 고정하면 read/write 키가 동일해진다.
        Class<?> canonicalType = higher(cacheableDecl, cacheDecl);

        String region;
        CacheConcurrencyStrategy usage;
        if (cacheAnn != null) {
            region = cacheAnn.region().isBlank() ? canonicalType.getName() : cacheAnn.region();
            usage = cacheAnn.usage();
        } else {
            region = canonicalType.getName();
            usage = CacheConcurrencyStrategy.READ_WRITE;
        }

        if (!SUPPORTED.contains(usage)) {
            throw new IllegalStateException(
                    "Unsupported cache concurrency strategy " + usage + " on " + entityType.getName()
                            + " (Nova v1 supports READ_ONLY and READ_WRITE only)");
        }

        return new CacheConfiguration(true, canonicalType, region, usage);
    }

    /**
     * 두 선언 클래스 중 상위(root-most) 클래스를 반환한다. 하나가 {@code null}이면 다른 하나를 반환한다.
     * 단일 상속 체인에서 두 조상은 항상 비교 가능하므로 {@link Class#isAssignableFrom(Class)}로 상하 판단한다.
     */
    private static Class<?> higher(Class<?> a, Class<?> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        // a가 b의 상위(또는 동일)면 a가 root-most.
        return a.isAssignableFrom(b) ? a : b;
    }

    private static Class<?> findDeclaringClass(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotationType) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (current.getDeclaredAnnotation(annotationType) != null) {
                return current;
            }
        }
        return null;
    }
}
