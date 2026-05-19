package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.NativeQuery;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import io.nova.fetch.FetchGroup;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NovaRepositoryFactoryBean}을 Spring 컨테이너 외부에서 직접 인스턴스화해 setter 주입과
 * proxy 생성, 캐싱 동작을 단위로 검증한다.
 */
class NovaRepositoryFactoryBeanTest {

    static final class Account {
        final long id;

        Account(long id) {
            this.id = id;
        }
    }

    interface AccountRepository extends ReactiveCrudRepository<Account, Long> {
    }

    @Test
    void getObjectReturnsProxyImplementingRepositoryInterface() throws Exception {
        StubEntityOperations operations = new StubEntityOperations();
        NovaRepositoryFactoryBean factoryBean = new NovaRepositoryFactoryBean(AccountRepository.class);
        factoryBean.setEntityOperations(operations);
        factoryBean.afterPropertiesSet();

        Object proxy = factoryBean.getObject();
        assertNotNull(proxy);
        assertTrue(proxy instanceof AccountRepository, "proxy must implement repository interface");
        assertSame(AccountRepository.class, factoryBean.getObjectType());
        assertTrue(factoryBean.isSingleton());

        // delegation smoke test
        AccountRepository repo = (AccountRepository) proxy;
        Account account = new Account(0L);
        StepVerifier.create(repo.save(account))
                .expectNext(account)
                .verifyComplete();
        assertSame(account, operations.lastSavedEntity);
    }

    @Test
    void afterPropertiesSetWithoutEntityOperationsThrows() {
        NovaRepositoryFactoryBean factoryBean = new NovaRepositoryFactoryBean(AccountRepository.class);
        IllegalStateException exception = assertThrows(IllegalStateException.class, factoryBean::afterPropertiesSet);
        assertTrue(exception.getMessage().contains("ReactiveEntityOperations"));
    }

    @Test
    void proxyIsCachedAcrossCalls() {
        StubEntityOperations operations = new StubEntityOperations();
        NovaRepositoryFactoryBean factoryBean = new NovaRepositoryFactoryBean(AccountRepository.class);
        factoryBean.setEntityOperations(operations);
        factoryBean.afterPropertiesSet();

        Object first = factoryBean.getObject();
        Object second = factoryBean.getObject();
        assertSame(first, second, "factory bean must return same proxy instance for singletons");
    }

    @Test
    void nonInterfaceRepositoryTypeIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new NovaRepositoryFactoryBean(Account.class));
        assertTrue(exception.getMessage().contains("interface"));
    }

    /**
     * 단순화된 stub. {@link ReactiveEntityOperations#save(Object)} 외 모든 메서드는 미사용이므로
     * default 또는 빈 publisher를 반환한다.
     */
    private static final class StubEntityOperations implements ReactiveEntityOperations {
        Object lastSavedEntity;
        final AtomicInteger saveCount = new AtomicInteger();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<T> save(T entity) {
            this.lastSavedEntity = entity;
            saveCount.incrementAndGet();
            return (Mono<T>) Mono.just(entity);
        }

        @Override
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            return Mono.empty();
        }

        @Override
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            return Flux.empty();
        }

        @Override
        public <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
            return Flux.empty();
        }

        @Override
        public <T> Mono<Long> delete(T entity) {
            return Mono.just(0L);
        }

        @Override
        public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(false);
        }

        @Override
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            return callback.apply(this);
        }

        @Override
        public <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
            return ReactiveEntityOperations.super.update(entityType, updater);
        }

        @Override
        public Mono<Long> executeNative(NativeQuery query) {
            return ReactiveEntityOperations.super.executeNative(query);
        }

        @Override
        public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
            return ReactiveEntityOperations.super.queryNative(query, mapper);
        }

        @Override
        public <T> Mono<T> queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper) {
            return ReactiveEntityOperations.super.queryNativeOne(query, mapper);
        }

        @Override
        public <T> Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec) {
            return ReactiveEntityOperations.super.aggregate(entityType, spec);
        }

        @Override
        public <T> Flux<T> findAll(Class<T> entityType, CompiledQuery query, Object... bindings) {
            return ReactiveEntityOperations.super.findAll(entityType, query, bindings);
        }

        @Override
        public Mono<Long> execute(CompiledQuery query, Object... bindings) {
            return ReactiveEntityOperations.super.execute(query, bindings);
        }

        @Override
        public <P> Mono<P> findById(Class<P> entityType, Object id, FetchGroup<P> fetchGroup) {
            return ReactiveEntityOperations.super.findById(entityType, id, fetchGroup);
        }

        @Override
        public <P> Flux<P> findAll(Class<P> entityType, FetchGroup<P> fetchGroup) {
            return ReactiveEntityOperations.super.findAll(entityType, fetchGroup);
        }
    }
}
