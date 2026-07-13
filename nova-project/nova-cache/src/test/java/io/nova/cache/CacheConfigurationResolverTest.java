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
}
