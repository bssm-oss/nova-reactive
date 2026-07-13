package io.nova.cache;

import jakarta.persistence.Cacheable;
import jakarta.persistence.SharedCacheMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CacheConfigurationResolver}가 표준 {@link SharedCacheMode} 정책을 honor 하는지 검증한다.
 * 기본은 {@code ENABLE_SELECTIVE}(기존 동작), ALL/NONE/DISABLE_SELECTIVE는 표준 시맨틱을 따른다.
 */
class SharedCacheModeResolverTest {

    @Cacheable
    static class Annotated {
    }

    @Cacheable(false)
    static class ExplicitlyOptedOut {
    }

    static class Plain {
    }

    @Test
    void defaultIsEnableSelective() {
        CacheConfigurationResolver resolver = new CacheConfigurationResolver();
        assertTrue(resolver.resolve(Annotated.class).cacheable());
        assertFalse(resolver.resolve(Plain.class).cacheable(), "ENABLE_SELECTIVE는 애너테이션 없는 타입을 캐시하지 않는다");
    }

    @Test
    void noneModeDisablesEverything() {
        CacheConfigurationResolver resolver = new CacheConfigurationResolver(SharedCacheMode.NONE);
        assertFalse(resolver.resolve(Annotated.class).cacheable(), "NONE은 @Cacheable도 무시하고 캐시하지 않는다");
        assertFalse(resolver.resolve(Plain.class).cacheable());
    }

    @Test
    void allModeCachesEveryTypeIncludingUnannotated() {
        CacheConfigurationResolver resolver = new CacheConfigurationResolver(SharedCacheMode.ALL);
        assertTrue(resolver.resolve(Annotated.class).cacheable());
        assertTrue(resolver.resolve(Plain.class).cacheable(), "ALL은 애너테이션 없는 타입도 캐시한다");
        assertTrue(resolver.resolve(ExplicitlyOptedOut.class).cacheable(), "ALL은 @Cacheable(false)도 무시하고 캐시한다");
        assertEquals(Plain.class, resolver.resolve(Plain.class).keyType(),
                "애너테이션 없는 캐시 타입은 자기 자신이 canonical이 된다");
    }

    @Test
    void disableSelectiveCachesAllExceptExplicitOptOut() {
        CacheConfigurationResolver resolver = new CacheConfigurationResolver(SharedCacheMode.DISABLE_SELECTIVE);
        assertTrue(resolver.resolve(Plain.class).cacheable(), "DISABLE_SELECTIVE는 기본적으로 모두 캐시한다");
        assertTrue(resolver.resolve(Annotated.class).cacheable());
        assertFalse(resolver.resolve(ExplicitlyOptedOut.class).cacheable(),
                "@Cacheable(false)로 명시 제외한 타입만 캐시에서 빠진다");
    }

    @Test
    void unspecifiedBehavesAsEnableSelective() {
        CacheConfigurationResolver resolver = new CacheConfigurationResolver(SharedCacheMode.UNSPECIFIED);
        assertTrue(resolver.resolve(Annotated.class).cacheable());
        assertFalse(resolver.resolve(Plain.class).cacheable());
    }
}
