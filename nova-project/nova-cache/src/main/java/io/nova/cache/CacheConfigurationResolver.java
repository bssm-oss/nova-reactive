package io.nova.cache;

import io.nova.cache.annotation.Cache;
import io.nova.cache.annotation.CacheConcurrencyStrategy;
import jakarta.persistence.SharedCacheMode;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 엔티티 타입에서 {@code jakarta.persistence.Cacheable}과 Nova {@link Cache} 애너테이션을 리플렉션으로 읽어
 * {@link CacheConfiguration}으로 해석한다. nova-core 메타데이터를 건드리지 않고 캐시 모듈 안에서 독립적으로
 * 캐시 여부를 판정하므로 core 수정이 불필요하다(격리 우선).
 *
 * <h2>표준 {@link SharedCacheMode} 정책</h2>
 * <p>기본 정책은 JPA {@link SharedCacheMode#ENABLE_SELECTIVE}이며, 생성자로 다른 표준 모드를 지정할 수 있다.
 * 모드별 "캐시 대상" 판정:
 * <ul>
 *   <li>{@link SharedCacheMode#ENABLE_SELECTIVE}(기본), {@link SharedCacheMode#UNSPECIFIED}:
 *       {@code @Cacheable(true)} 또는 {@code @Cache}가 붙은 타입만 캐시한다.</li>
 *   <li>{@link SharedCacheMode#ALL}: 애너테이션과 무관하게 모든 엔티티를 캐시한다({@code @Cacheable(false)}도 무시).</li>
 *   <li>{@link SharedCacheMode#NONE}: 어떤 엔티티도 캐시하지 않는다(모든 캐시 애너테이션 무시).</li>
 *   <li>{@link SharedCacheMode#DISABLE_SELECTIVE}: {@code @Cacheable(false)}로 명시 제외한 타입을 제외한 모든
 *       엔티티를 캐시한다.</li>
 * </ul>
 *
 * <h2>애너테이션 우선순위</h2>
 * <ul>
 *   <li>{@code @Cacheable}이 있으면 그 {@code value()}가 (선택 모드 안에서) 캐시 여부를 결정한다 —
 *       {@code @Cacheable(false)}는 {@code @Cache}보다 우선해 비활성화한다(ALL 모드 제외).</li>
 *   <li>{@code @Cacheable}이 없고 {@code @Cache}만 있으면 캐시 대상으로 간주한다({@code @Cache}가 활성화를 함의).</li>
 * </ul>
 *
 * <p>region은 {@code @Cache.region()}(비면) 또는 엔티티 클래스의 정규 이름이다. 동시성 전략은
 * {@code @Cache.usage()}(기본 {@link CacheConcurrencyStrategy#READ_WRITE})이며, 미지원 전략
 * ({@link CacheConcurrencyStrategy#NONSTRICT_READ_WRITE}, {@link CacheConcurrencyStrategy#TRANSACTIONAL})은
 * 조용히 무시하지 않고 {@link IllegalStateException}으로 fail-fast 거부한다.
 *
 * <p>애너테이션은 클래스 계층을 상향 탐색해 찾는다 — {@code @Inherited} 여부에 의존하지 않는다
 * (프로젝트 학습: generator/marker 애너테이션의 상속 해석 함정 회피). 다형 키 정규화(base findById와 subtype
 * save가 같은 키/region을 공유)는 캐시 애너테이션을 상속 root에 두는 것에 의존한다. {@link SharedCacheMode#ALL}/
 * {@link SharedCacheMode#DISABLE_SELECTIVE}로 <b>애너테이션 없이</b> 캐시되는 타입은 각 구체 타입 자신이
 * canonical이 되므로, 상속 계층 전체의 다형 정규화가 필요하면 root에 {@code @Cacheable}/{@code @Cache}를 붙여라.
 */
public final class CacheConfigurationResolver {

    private static final Set<CacheConcurrencyStrategy> SUPPORTED =
            EnumSet.of(CacheConcurrencyStrategy.READ_ONLY, CacheConcurrencyStrategy.READ_WRITE);

    private final SharedCacheMode sharedCacheMode;
    private final ConcurrentHashMap<Class<?>, CacheConfiguration> cache = new ConcurrentHashMap<>();

    /**
     * 기본 정책({@link SharedCacheMode#ENABLE_SELECTIVE})으로 해석한다.
     */
    public CacheConfigurationResolver() {
        this(SharedCacheMode.ENABLE_SELECTIVE);
    }

    /**
     * 표준 {@link SharedCacheMode} 정책을 지정한다. {@link SharedCacheMode#UNSPECIFIED}는 {@code ENABLE_SELECTIVE}로
     * 취급된다(JPA provider 기본과 일치).
     */
    public CacheConfigurationResolver(SharedCacheMode sharedCacheMode) {
        this.sharedCacheMode = Objects.requireNonNull(sharedCacheMode, "sharedCacheMode must not be null");
    }

    /**
     * 엔티티 타입의 캐시 설정을 해석한다. 결과는 타입별로 캐시된다. 미지원 동시성 전략이면
     * {@link IllegalStateException}을 던진다(fail-fast).
     */
    public CacheConfiguration resolve(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return cache.computeIfAbsent(entityType, this::compute);
    }

    private CacheConfiguration compute(Class<?> entityType) {
        if (sharedCacheMode == SharedCacheMode.NONE) {
            return CacheConfiguration.notCacheable();
        }

        // canonical 타입 = 캐시 애너테이션(@Cacheable 또는 @Cache)을 선언한 조상 중 root-most(Object에 가장
        // 가까운) 클래스. 계층의 어느 진입점(base findById, subtype save)에서 resolve 하든 동일한 canonical이
        // 나오도록, 첫(가장 파생된) 매치에서 멈추지 않고 끝까지 걸으며 마지막 매치를 유지한다. 이 불변식이
        // read/write 캐시 키를 일치시켜 다형 stale read를 막는다.
        Class<?> annotatedType = rootMostAnnotatedClass(entityType);

        // 설정 값은 canonical <b>직접 선언</b> 기준으로 읽는다. canonical의 조상에는 (정의상) 캐시 애너테이션이
        // 없으므로 getDeclaredAnnotation으로 충분하며, canonical보다 <em>아래(subtype)</em>에 선언된 @Cache/
        // @Cacheable은 진입점에 따라 보였다 안 보였다 하므로 일부러 무시한다(무시하지 않으면 region/키가 갈라져
        // 다시 stale). 즉 다형 캐시 설정은 canonical 레벨에 두어야 한다.
        jakarta.persistence.Cacheable cacheable = annotatedType == null
                ? null : annotatedType.getDeclaredAnnotation(jakarta.persistence.Cacheable.class);
        Cache cacheAnn = annotatedType == null
                ? null : annotatedType.getDeclaredAnnotation(Cache.class);

        boolean isCacheable = switch (sharedCacheMode) {
            // ALL: 모든 엔티티 캐시(애너테이션·@Cacheable(false) 무시).
            case ALL -> true;
            // DISABLE_SELECTIVE: @Cacheable(false)로 명시 제외한 타입만 캐시 제외.
            case DISABLE_SELECTIVE -> cacheable == null || cacheable.value();
            // ENABLE_SELECTIVE/UNSPECIFIED: @Cacheable(true) 또는 @Cache가 있어야 캐시.
            default -> cacheable != null ? cacheable.value() : cacheAnn != null;
        };
        if (!isCacheable) {
            return CacheConfiguration.notCacheable();
        }

        // canonical: 애너테이션이 있으면 root-most 선언 클래스, 없으면(ALL/DISABLE_SELECTIVE로 캐시되는 경우)
        // 엔티티 타입 자신. 후자는 상속 계층 전체 다형 정규화를 보장하지 않는다(클래스 javadoc 참고).
        Class<?> canonicalType = annotatedType != null ? annotatedType : entityType;

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
                            + " (Nova supports READ_ONLY and READ_WRITE only)");
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
