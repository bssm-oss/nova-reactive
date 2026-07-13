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
        // canonical 타입 = 캐시 애너테이션(@Cacheable 또는 @Cache)을 선언한 조상 중 root-most(Object에 가장
        // 가까운) 클래스. 계층의 어느 진입점(base findById, subtype save)에서 resolve 하든 동일한 canonical이
        // 나오도록, 첫(가장 파생된) 매치에서 멈추지 않고 끝까지 걸으며 마지막 매치를 유지한다. 이 불변식이
        // read/write 캐시 키를 일치시켜 다형 stale read를 막는다.
        Class<?> canonicalType = rootMostAnnotatedClass(entityType);
        if (canonicalType == null) {
            return CacheConfiguration.notCacheable();
        }

        // 설정 값은 canonical <b>직접 선언</b> 기준으로 읽는다. canonical의 조상에는 (정의상) 캐시 애너테이션이
        // 없으므로 getDeclaredAnnotation으로 충분하며, canonical보다 <em>아래(subtype)</em>에 선언된 @Cache/
        // @Cacheable은 진입점에 따라 보였다 안 보였다 하므로 일부러 무시한다(무시하지 않으면 region/키가 갈라져
        // 다시 stale). 즉 다형 캐시 설정은 canonical 레벨에 두어야 한다.
        jakarta.persistence.Cacheable cacheable =
                canonicalType.getDeclaredAnnotation(jakarta.persistence.Cacheable.class);
        Cache cacheAnn = canonicalType.getDeclaredAnnotation(Cache.class);

        boolean isCacheable = cacheable != null ? cacheable.value() : cacheAnn != null;
        if (!isCacheable) {
            return CacheConfiguration.notCacheable();
        }

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
     * {@code @Cacheable} 또는 {@code @Cache}를 선언한 <b>root-most(Object에 가장 가까운)</b> 조상 클래스를
     * 반환한다. 계층을 끝까지 걸으며 마지막 매치를 유지하므로, 같은 애너테이션이 base와 subtype 양쪽에
     * 선언되거나 두 애너테이션이 서로 다른 레벨에 있어도 어느 진입점에서든 동일한 canonical이 나온다.
     * 매치가 전혀 없으면 {@code null}.
     */
    private static Class<?> rootMostAnnotatedClass(Class<?> type) {
        Class<?> rootMost = null;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (current.getDeclaredAnnotation(jakarta.persistence.Cacheable.class) != null
                    || current.getDeclaredAnnotation(Cache.class) != null) {
                rootMost = current; // 더 상위 선언이 있으면 덮어써 root-most로 수렴.
            }
        }
        return rootMost;
    }
}
