package io.nova.cache;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;

import java.util.Objects;

/**
 * 2차 캐시 활성화 진입점. 기존 {@link ReactiveEntityOperations}(예: {@code Nova.create(...)} 결과)를
 * {@link CachingReactiveEntityOperations}로 감싸 read-through/invalidation을 더한다. 순수 additive —
 * 원본 operations와 그 배선은 그대로 두고 데코레이터만 새로 만든다.
 *
 * <pre>{@code
 * ReactiveEntityOperations base = Nova.create(connectionFactory);
 * ReactiveEntityOperations cached = NovaCache.caching(base);
 * }</pre>
 *
 * <p>{@code @Cacheable}(또는 Nova {@link io.nova.cache.annotation.Cache})이 붙은 엔티티만 캐시되며, 그 외
 * 타입은 캐시 없이 그대로 통과한다.
 */
public final class NovaCache {

    private NovaCache() {
    }

    /**
     * 기본 in-process 프로바이더({@link SimpleReactiveCacheProvider}, 만료/크기 무제한)로 캐싱 operations를 만든다.
     */
    public static ReactiveEntityOperations caching(ReactiveEntityOperations delegate) {
        return caching(delegate, new SimpleReactiveCacheProvider());
    }

    /**
     * 지정한 캐시 옵션(TTL/최대 크기)으로 기본 프로바이더를 구성해 캐싱 operations를 만든다.
     */
    public static ReactiveEntityOperations caching(ReactiveEntityOperations delegate, CacheOptions options) {
        return caching(delegate, new SimpleReactiveCacheProvider(options));
    }

    /**
     * 사용자 지정 {@link io.nova.cache.spi.ReactiveCacheProvider}로 캐싱 operations를 만든다.
     * id 추출용 {@link EntityMetadataFactory}는 기본({@link DefaultNamingStrategy}, codec 미설정)으로 생성된다 —
     * {@code @Json}/커스텀 컨버터 엔티티를 캐시하려면 configured factory를 받는 오버로드를 사용하라.
     */
    public static ReactiveEntityOperations caching(
            ReactiveEntityOperations delegate, io.nova.cache.spi.ReactiveCacheProvider provider) {
        return caching(delegate, provider, new EntityMetadataFactory(new DefaultNamingStrategy()));
    }

    /**
     * 모든 협력자를 명시해 캐싱 operations를 만든다. id 추출에 사용할 {@link EntityMetadataFactory}는 가급적
     * operations 배선에 쓰인 것과 동일하게 넘겨 {@code @Json}/컨버터 엔티티의 메타데이터 빌드 실패를 피한다.
     */
    public static ReactiveEntityOperations caching(
            ReactiveEntityOperations delegate,
            io.nova.cache.spi.ReactiveCacheProvider provider,
            EntityMetadataFactory metadataFactory) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        return new CachingReactiveEntityOperations(
                delegate, metadataFactory, new CacheConfigurationResolver(), provider);
    }
}
