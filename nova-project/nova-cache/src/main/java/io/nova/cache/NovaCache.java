package io.nova.cache;

import io.nova.cache.spi.ReactiveQueryCache;
import io.nova.core.ReactiveEntityOperations;
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
 * <p>{@code @Cacheable}(또는 Nova {@link io.nova.cache.annotation.Cache})이 붙은 엔티티만 read-through
 * 캐시되며, 그 외 타입의 read는 그대로 통과한다. 모든 타입의 성공한 write는 eager graph 정합성을 위해
 * 전역 캐시 무효화에 참여한다.
 *
 * <p><b>Mapping factory:</b> pass the exact {@link EntityMetadataFactory} used to construct the delegate to
 * {@link #caching(ReactiveEntityOperations, io.nova.cache.spi.ReactiveCacheProvider, EntityMetadataFactory)} or
 * {@link #cachingWithQueryCache(ReactiveEntityOperations, io.nova.cache.spi.ReactiveCacheProvider,
 * EntityMetadataFactory, ReactiveQueryCache)}. Cache snapshots reconstruct mapped converters and JSON values, so
 * an independently-created factory can disagree with the delegate mapping.
 *
 * <p><b>Custom transaction delegates:</b> delegates that execute SQL outside Nova's
 * {@code SimpleReactiveEntityOperations} must mark successful internal/session-flush writes through
 * {@link io.nova.tx.PhysicalTransactionScope#markWriteCompleted()} or the active
 * {@link io.nova.tx.TransactionWriteObservation}. Explicit write methods invoked through the returned cache
 * decorator are observed automatically. An unmarked internal write cannot trigger safe post-commit invalidation.
 */
public final class NovaCache {

    private NovaCache() {
    }

    /**
     * 기본 in-process 프로바이더({@link SimpleReactiveCacheProvider}, 만료/크기 무제한)로 캐싱 operations를 만든다.
     */
    public static ReactiveEntityOperations caching(ReactiveEntityOperations delegate) {
        return caching(delegate, new SimpleReactiveCacheProvider(), delegateMetadataFactory(delegate));
    }

    /**
     * 지정한 캐시 옵션(TTL/최대 크기)으로 기본 프로바이더를 구성해 캐싱 operations를 만든다.
     */
    public static ReactiveEntityOperations caching(ReactiveEntityOperations delegate, CacheOptions options) {
        return caching(delegate, new SimpleReactiveCacheProvider(options), delegateMetadataFactory(delegate));
    }

    /**
     * 사용자 지정 provider로 캐싱 operations를 만든다. The delegate's existing mapping factory is reused so
     * custom Json codecs and registered converters stay identical to the ORM mapping.
     */
    public static ReactiveEntityOperations caching(
            ReactiveEntityOperations delegate, io.nova.cache.spi.ReactiveCacheProvider provider) {
        return caching(delegate, provider, delegateMetadataFactory(delegate));
    }

    /**
     * 모든 협력자를 명시해 캐싱 operations를 만든다. {@code metadataFactory} must be the exact factory used
     * by {@code delegate}; it drives both id resolution and detached graph reconstruction.
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
     * The core operations contract intentionally does not expose mapping configuration. Cache convenience wiring
     * therefore reads the delegate's existing factory rather than constructing a second factory with different JSON
     * codecs or converter registrations. Unknown implementations must use an overload that explicitly supplies it.
     */
    private static EntityMetadataFactory delegateMetadataFactory(ReactiveEntityOperations delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        for (Class<?> type = delegate.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("metadataFactory");
                if (!EntityMetadataFactory.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return (EntityMetadataFactory) field.get(delegate);
            } catch (NoSuchFieldException ignored) {
                // Continue through the concrete delegate hierarchy.
            } catch (IllegalAccessException exception) {
                throw new IllegalArgumentException("Cannot obtain the delegate EntityMetadataFactory", exception);
            }
        }
        throw new IllegalArgumentException("Caching convenience overloads require a delegate exposing its "
                + "EntityMetadataFactory; use the overload that supplies the delegate's exact factory");
    }

    /**
     * 엔티티 캐시에 더해 <b>쿼리 결과 캐시</b>(2차 query cache)까지 배선한다. 기본 in-process 프로바이더 +
     * {@link SimpleReactiveQueryCache}를 사용하며, {@code findAll(Class, QuerySpec)} 결과를 정규화된 스펙 키로
     * 캐시한다(히트 시 0 SQL). 쿼리 캐시는 <b>정합성 리스크상 opt-in</b>이며, 이 오버로드를 쓰지 않으면
     * 기본 동작(쿼리 캐싱 없음)이 그대로 유지된다.
     *
     * <p>무효화 계약: every successful wrapped ORM write clears all entity and query-cache regions because eager
     * graph snapshots can contain associated types. Physical transaction invalidation runs after commit only.
     */
    public static ReactiveEntityOperations cachingWithQueryCache(ReactiveEntityOperations delegate) {
        return cachingWithQueryCache(
                delegate,
                new SimpleReactiveCacheProvider(),
                delegateMetadataFactory(delegate),
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
