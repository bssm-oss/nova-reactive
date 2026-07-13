package io.nova.cache;

import io.nova.cache.annotation.Cache;
import io.nova.cache.annotation.CacheConcurrencyStrategy;
import jakarta.persistence.Cacheable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CacheConfigurationResolver}가 {@code @Cacheable}/{@code @Cache}를 honor 하고, 미지원 동시성 전략을
 * fail-fast로 reject 하는지 검증한다(조용한 무시 금지).
 */
class CacheConfigurationResolverTest {

    private final CacheConfigurationResolver resolver = new CacheConfigurationResolver();

    @Test
    void cacheableEntityUsesDefaultRegionAndReadWrite() {
        CacheConfiguration config = resolver.resolve(PlainCacheable.class);
        assertTrue(config.cacheable());
        assertEquals(PlainCacheable.class.getName(), config.region());
        assertEquals(CacheConcurrencyStrategy.READ_WRITE, config.usage());
    }

    @Test
    void cacheAnnotationHonorsRegionAndUsage() {
        CacheConfiguration config = resolver.resolve(RegionScoped.class);
        assertTrue(config.cacheable());
        assertEquals("people", config.region());
        assertEquals(CacheConcurrencyStrategy.READ_ONLY, config.usage());
    }

    @Test
    void cacheAnnotationImpliesCacheableWithoutCacheableAnnotation() {
        CacheConfiguration config = resolver.resolve(CacheOnly.class);
        assertTrue(config.cacheable());
        assertEquals("orders", config.region());
    }

    @Test
    void plainEntityIsNotCacheable() {
        assertFalse(resolver.resolve(NotAnnotated.class).cacheable());
    }

    @Test
    void cacheableFalseDisablesEvenWithCacheAnnotation() {
        CacheConfiguration config = resolver.resolve(ExplicitlyDisabled.class);
        assertFalse(config.cacheable(), "@Cacheable(false)가 @Cache보다 우선해 캐시를 비활성화해야 한다");
    }

    @Test
    void inheritedCacheableIsResolvedFromSuperclass() {
        CacheConfiguration config = resolver.resolve(SubOfCacheable.class);
        assertTrue(config.cacheable(), "부모의 @Cacheable을 클래스 계층 탐색으로 찾아야 한다");
    }

    @Test
    void baseAndSubtypeShareCanonicalKeyTypeAndRegion() {
        // 다형 findById(base)와 save(subtype)가 같은 캐시 키를 쓰도록 keyType/region이 root로 정규화되어야 한다.
        CacheConfiguration base = resolver.resolve(PlainCacheable.class);
        CacheConfiguration sub = resolver.resolve(SubOfCacheable.class);

        assertEquals(PlainCacheable.class, base.keyType());
        assertEquals(PlainCacheable.class, sub.keyType(), "subtype resolve도 root canonical keyType을 써야 한다");
        assertEquals(base.region(), sub.region(), "subtype과 base는 동일 region이어야 다형 stale이 없다");
    }

    @Test
    void nonCacheableConfigExposesNeutralKeyType() {
        CacheConfiguration config = resolver.resolve(NotAnnotated.class);
        assertFalse(config.cacheable());
        assertEquals(Object.class, config.keyType());
    }

    @Test
    void cacheableRedeclaredOnSubtypeStillResolvesToRootCanonical() {
        // @Cacheable가 base와 subtype 양쪽에 선언되어도 canonical은 root-most(base)여야 한다 —
        // 아니면 resolve(base)와 resolve(subtype)의 키 컴포넌트가 갈라져 다형 stale read가 재발한다.
        CacheConfiguration base = resolver.resolve(RedeclaredBase.class);
        CacheConfiguration sub = resolver.resolve(RedeclaredSub.class);

        assertEquals(RedeclaredBase.class, base.keyType());
        assertEquals(RedeclaredBase.class, sub.keyType(),
                "subtype에 @Cacheable를 재선언해도 canonical keyType은 root여야 한다");
        assertEquals(base.region(), sub.region());
    }

    @Test
    void cacheOnSubtypeOnlyStillSharesRootCacheableRegion() {
        // @Cacheable는 root, @Cache(region)는 subtype에만 → root findById에서는 @Cache가 안 보이므로
        // region이 root.name으로, subtype resolve에서는 "x"로 갈라지면 stale. root-most 정규화로 region 일치해야 한다.
        CacheConfiguration base = resolver.resolve(RootCacheable.class);
        CacheConfiguration sub = resolver.resolve(SubWithCache.class);

        assertEquals(RootCacheable.class, base.keyType(),
                "@Cache가 subtype에만 있어도 canonical keyType은 root-most @Cacheable 선언 클래스여야 한다");
        assertEquals(RootCacheable.class, sub.keyType());
        assertEquals(base.region(), sub.region(),
                "base와 subtype이 동일 region을 써야 다형 findById/save가 같은 캐시 인스턴스를 공유한다");
    }

    @Test
    void unsupportedConcurrencyStrategyFailsFast() {
        IllegalStateException nonstrict = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(NonstrictStrategy.class));
        assertTrue(nonstrict.getMessage().contains("NONSTRICT_READ_WRITE"));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(TransactionalStrategy.class));
    }

    // --- fixtures ----------------------------------------------------------

    @Cacheable
    static class PlainCacheable {
    }

    @Cacheable
    @Cache(region = "people", usage = CacheConcurrencyStrategy.READ_ONLY)
    static class RegionScoped {
    }

    @Cache(region = "orders")
    static class CacheOnly {
    }

    static class NotAnnotated {
    }

    @Cacheable(false)
    @Cache(region = "nope")
    static class ExplicitlyDisabled {
    }

    static class SubOfCacheable extends PlainCacheable {
    }

    @Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
    static class NonstrictStrategy {
    }

    @Cache(usage = CacheConcurrencyStrategy.TRANSACTIONAL)
    static class TransactionalStrategy {
    }

    // 시나리오 1: @Cacheable를 base와 subtype에 각각 재선언(nearest-declaring이면 canonical이 갈라진다).
    @Cacheable
    static class RedeclaredBase {
    }

    @Cacheable
    static class RedeclaredSub extends RedeclaredBase {
    }

    // 시나리오 2: @Cacheable는 root에만, @Cache(region)는 subtype에만.
    @Cacheable
    static class RootCacheable {
    }

    @Cache(region = "sub-region")
    static class SubWithCache extends RootCacheable {
    }
}
