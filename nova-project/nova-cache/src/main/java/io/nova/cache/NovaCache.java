package io.nova.cache;

import io.nova.cache.spi.ReactiveQueryCache;
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
 *
 * <p><b>주의(커스텀 컨버터):</b> 무효화 경로는 write 성공 <i>후</i> {@link EntityMetadataFactory}로 엔티티의
 * id를 읽는다. 기본 factory 오버로드는 {@code Nova.create}의 기본과 동일한 미설정 {@code JsonCodec}을 쓰므로
 * {@code @Json} 엔티티도 안전하다(id 추출은 JSON 인코딩을 트리거하지 않는다). 그러나 delegate 배선에서
 * {@code EntityMetadataFactory.registerConverter(...)}로 커스텀 {@code @Convert} 컨버터를 등록했다면, 그 factory를
 * {@link #caching(ReactiveEntityOperations, io.nova.cache.spi.ReactiveCacheProvider, EntityMetadataFactory)}
 * 오버로드로 <b>동일하게</b> 넘겨야 한다. 그러지 않으면 metadata 빌드가 write 성공 후에 실패해 "행은 이미
 * 써졌는데 save()가 error"인 혼란을 유발할 수 있다.
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
     * id 추출용 {@link EntityMetadataFactory}는 기본({@link DefaultNamingStrategy}, {@code Nova.create}와 동일한
     * 미설정 codec)으로 생성된다. delegate에 <b>커스텀 {@code @Convert} 컨버터를 registerConverter로 등록</b>했다면
     * 무효화 시 metadata 빌드가 깨질 수 있으므로 factory를 명시하는 3-인자 오버로드를 사용하라(클래스 javadoc의
     * 커스텀 컨버터 주의 참고).
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

    /**
     * 엔티티 캐시에 더해 <b>쿼리 결과 캐시</b>(2차 query cache)까지 배선한다. 기본 in-process 프로바이더 +
     * {@link SimpleReactiveQueryCache}를 사용하며, {@code findAll(Class, QuerySpec)} 결과를 정규화된 스펙 키로
     * 캐시한다(히트 시 0 SQL). 쿼리 캐시는 <b>정합성 리스크상 opt-in</b>이며, 이 오버로드를 쓰지 않으면
     * 기본 동작(쿼리 캐싱 없음)이 그대로 유지된다.
     *
     * <p>무효화 계약: 어떤 write든(save/update/delete/bulk/native) 대상 엔티티 타입의 쿼리 결과를 통째로
     * 무효화한다. 트랜잭션은 commit 후 재무효화되고 rollback 시 stale을 남기지 않는다({@link ReactiveQueryCache}
     * javadoc 참고).
     */
    public static ReactiveEntityOperations cachingWithQueryCache(ReactiveEntityOperations delegate) {
        return cachingWithQueryCache(
                delegate,
                new SimpleReactiveCacheProvider(),
                new EntityMetadataFactory(new DefaultNamingStrategy()),
                new SimpleReactiveQueryCache());
    }

    /**
     * 모든 협력자(엔티티 캐시 프로바이더 + metadata factory + {@link ReactiveQueryCache})를 명시해 엔티티 캐시와
     * 쿼리 결과 캐시를 함께 배선한다. {@code queryCache}가 {@code null}이면 쿼리 캐싱만 비활성화되고 엔티티
     * 캐시는 그대로 동작한다(opt-in).
     */
    public static ReactiveEntityOperations cachingWithQueryCache(
            ReactiveEntityOperations delegate,
            io.nova.cache.spi.ReactiveCacheProvider provider,
            EntityMetadataFactory metadataFactory,
            ReactiveQueryCache queryCache) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        return new CachingReactiveEntityOperations(
                delegate, metadataFactory, new CacheConfigurationResolver(), provider, queryCache);
    }
}
