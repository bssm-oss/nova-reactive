package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.Condition;
import io.nova.query.NativeQuery;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fixed-name dispatch와 derived 경로가 한 proxy 위에서 함께 동작하는지 검증한다 —
 * 기존 {@code save/findById/…}는 그대로, 추가로 정의한 {@code findByEmail/countByActive/…}는
 * derived 경로로 정상 디스패치되어야 한다.
 */
class DerivedRepositoryIntegrationTest {
    private static final EntityMetadataFactory METADATA_FACTORY =
            new EntityMetadataFactory(new DefaultNamingStrategy());


    @Entity
    static final class Account {
        @Id
        Long id;
        String email;
        boolean active;
        Instant createdAt;

        Account(Long id, String email, boolean active) {
            this.id = id;
            this.email = email;
            this.active = active;
        }
    }

    private static final EntityMetadata<Account> ACCOUNT_METADATA =
            METADATA_FACTORY.getEntityMetadata(Account.class);

    interface AccountRepository extends ReactiveCrudRepository<Account, Long> {
        Mono<Account> findByEmail(String email);

        Flux<Account> findByActiveTrue();

        Mono<Long> countByActive(boolean active);

        Mono<Boolean> existsByEmail(String email);

        Mono<Long> deleteByActiveFalse();

        Mono<Account> findFirstByActiveTrueOrderByCreatedAtDesc();

        // 일부러 인자 수 mismatch — startup 시점이 아니라 호출 시점에 명시 메시지로 fail해야 한다.
        Mono<Account> findByEmail(String email, String extra);
    }

    private static AccountRepository newProxy(Operations operations) {
        SimpleReactiveRepository handler = new SimpleReactiveRepository(
                Account.class, Long.class, operations, null, null, ACCOUNT_METADATA);
        return (AccountRepository) Proxy.newProxyInstance(
                AccountRepository.class.getClassLoader(),
                new Class<?>[]{AccountRepository.class},
                handler);
    }

    @Test
    @DisplayName("findByEmail은 findFirst와 달리 Flux를 next()로 줄여 Mono로 반환")
    void findByEmailReducesToMono() {
        Account row = new Account(1L, "a@nova.io", true);
        Operations ops = new Operations();
        ops.nextFindAll = Flux.just(row);

        AccountRepository repo = newProxy(ops);

        StepVerifier.create(repo.findByEmail("a@nova.io"))
                .expectNext(row)
                .verifyComplete();

        QuerySpec spec = (QuerySpec) ops.lastFindAll().args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertEquals("email", c.property());
        assertEquals("a@nova.io", c.value());
    }

    @Test
    @DisplayName("findByActiveTrue는 Flux로 반환되어 전체 결과를 흘려준다")
    void findByActiveTrueReturnsFlux() {
        Account a = new Account(1L, "a@nova.io", true);
        Account b = new Account(2L, "b@nova.io", true);
        Operations ops = new Operations();
        ops.nextFindAll = Flux.just(a, b);

        AccountRepository repo = newProxy(ops);

        StepVerifier.create(repo.findByActiveTrue())
                .expectNext(a, b)
                .verifyComplete();
    }

    @Test
    @DisplayName("countByActive / existsByEmail / deleteByActiveFalse 모두 derived로 디스패치")
    void countExistsDeleteRoutes() {
        Operations ops = new Operations();
        ops.nextCount = Mono.just(7L);
        ops.nextExists = Mono.just(Boolean.TRUE);
        // deleteBy*는 deleteAll(Class, QuerySpec) 로 라우팅 — entity-iterable 기반 delete가 아니다.
        ops.nextDeleteAllBySpec = Mono.just(3L);

        AccountRepository repo = newProxy(ops);

        StepVerifier.create(repo.countByActive(true)).expectNext(7L).verifyComplete();
        StepVerifier.create(repo.existsByEmail("a@nova.io")).expectNext(Boolean.TRUE).verifyComplete();
        StepVerifier.create(repo.deleteByActiveFalse()).expectNext(3L).verifyComplete();
    }

    @Test
    @DisplayName("findFirst 는 LIMIT 1을 적용해 Mono로 첫 행을 돌려준다")
    void findFirstAppliesLimitOne() {
        Account row = new Account(1L, "x@nova.io", true);
        Operations ops = new Operations();
        ops.nextFindAll = Flux.just(row);

        AccountRepository repo = newProxy(ops);

        StepVerifier.create(repo.findFirstByActiveTrueOrderByCreatedAtDesc())
                .expectNext(row)
                .verifyComplete();

        QuerySpec spec = (QuerySpec) ops.lastFindAll().args()[1];
        assertNotNull(spec.pageable(), "first-by must apply pageable");
        assertEquals(1, spec.pageable().limit());
        assertEquals(0L, spec.pageable().offset());
        assertNotNull(spec.sort(), "OrderBy clause must produce a sort");
        assertEquals("createdAt", spec.sort().orders().get(0).property());
    }

    @Test
    @DisplayName("기존 fixed-name(findAll, save, count, deleteById)는 derived 경로와 충돌 없이 동작")
    void fixedNameStillWorks() {
        Operations ops = new Operations();
        ops.nextFindAll = Flux.just(new Account(1L, "a@nova.io", true));
        ops.nextCount = Mono.just(1L);
        ops.nextSave = Mono.just(new Account(1L, "a@nova.io", true));
        ops.nextDeleteById = Mono.just(1L);

        AccountRepository repo = newProxy(ops);

        StepVerifier.create(repo.findAll()).expectNextCount(1).verifyComplete();
        StepVerifier.create(repo.count()).expectNext(1L).verifyComplete();
        StepVerifier.create(repo.save(new Account(0L, "x@nova.io", true)))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(repo.deleteById(1L)).expectNext(1L).verifyComplete();
    }

    @Test
    @DisplayName("parameter mismatch는 IllegalArgumentException으로 즉시 fail — silently swallow하지 않음")
    void parameterMismatchFails() {
        Operations ops = new Operations();
        AccountRepository repo = newProxy(ops);
        assertThrows(IllegalArgumentException.class,
                () -> repo.findByEmail("a@nova.io", "extra"));
    }

    @Test
    @DisplayName("derived 매칭 실패는 여전히 UnsupportedOperationException Mono.error")
    void unsupportedFallthrough() {
        Operations ops = new Operations();
        AccountRepository repo = newProxy(ops);
        // 별도 interface로 derived prefix가 없는 이름을 만들어 호출
        interface OtherRepo extends ReactiveCrudRepository<Account, Long> {
            Mono<Account> totallyUnknownMethod(String x);
        }
        SimpleReactiveRepository handler = new SimpleReactiveRepository(
                Account.class, Long.class, ops, null, null, ACCOUNT_METADATA);
        OtherRepo other = (OtherRepo) Proxy.newProxyInstance(
                OtherRepo.class.getClassLoader(),
                new Class<?>[]{OtherRepo.class},
                handler);
        StepVerifier.create(other.totallyUnknownMethod("x"))
                .expectErrorMatches(t -> t instanceof UnsupportedOperationException
                        && t.getMessage().contains("Unsupported repository method"))
                .verify();
        assertTrue(ops.invocations.isEmpty(), "no underlying ops call should happen");
    }

    record Invocation(String name, Object[] args) {
    }

    static final class Operations implements ReactiveEntityOperations {
        final List<Invocation> invocations = new ArrayList<>();
        Mono<?> nextSave = Mono.empty();
        Mono<Long> nextDeleteById = Mono.just(0L);
        Mono<Long> nextDelete = Mono.just(0L);
        Mono<Long> nextDeleteAll = Mono.just(0L);
        Mono<Long> nextDeleteAllBySpec = Mono.just(0L);
        Mono<?> nextFindById = Mono.empty();
        Mono<Boolean> nextExistsById = Mono.just(Boolean.FALSE);
        Flux<?> nextSaveAll = Flux.empty();
        Flux<?> nextFindAll = Flux.empty();
        Mono<Long> nextCount = Mono.just(0L);
        Mono<Boolean> nextExists = Mono.just(Boolean.FALSE);
        Flux<?> nextFindAllById = Flux.empty();

        Invocation lastFindAll() {
            for (int i = invocations.size() - 1; i >= 0; i--) {
                if (invocations.get(i).name().equals("findAll")) {
                    return invocations.get(i);
                }
            }
            throw new IllegalStateException("no findAll recorded");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<T> save(T entity) {
            invocations.add(new Invocation("save", new Object[]{entity}));
            return (Mono<T>) nextSave;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> saveAll(Iterable<T> entities) {
            invocations.add(new Invocation("saveAll", new Object[]{entities}));
            return (Flux<T>) nextSaveAll;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            invocations.add(new Invocation("findById", new Object[]{entityType, id}));
            return (Mono<T>) nextFindById;
        }

        @Override
        public <T, ID> Mono<Boolean> existsById(Class<T> entityType, ID id) {
            invocations.add(new Invocation("existsById", new Object[]{entityType, id}));
            return nextExistsById;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> Flux<T> findAllById(Class<T> entityType, Iterable<ID> ids) {
            invocations.add(new Invocation("findAllById", new Object[]{entityType, ids}));
            return (Flux<T>) nextFindAllById;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            invocations.add(new Invocation("findAll", new Object[]{entityType, querySpec}));
            return (Flux<T>) nextFindAll;
        }

        @Override
        public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
            invocations.add(new Invocation("count", new Object[]{entityType, querySpec}));
            return nextCount;
        }

        @Override
        public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
            invocations.add(new Invocation("exists", new Object[]{entityType, querySpec}));
            return nextExists;
        }

        @Override
        public <T> Mono<Long> delete(T entity) {
            invocations.add(new Invocation("delete", new Object[]{entity}));
            return nextDelete;
        }

        @Override
        public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
            invocations.add(new Invocation("deleteById", new Object[]{entityType, id}));
            return nextDeleteById;
        }

        @Override
        public <T> Mono<Long> deleteAll(Iterable<T> entities) {
            invocations.add(new Invocation("deleteAll", new Object[]{entities}));
            return nextDeleteAll;
        }

        @Override
        public <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
            invocations.add(new Invocation("deleteAllBySpec", new Object[]{entityType, querySpec}));
            return nextDeleteAllBySpec;
        }

        @Override
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            return callback.apply(this);
        }

        @Override
        public <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
            return Flux.empty();
        }

        @Override
        public <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
            return ReactiveEntityOperations.super.update(entityType, updater);
        }

        @Override
        public <T> Mono<T> update(T entity, Iterable<String> fields) {
            return ReactiveEntityOperations.super.update(entity, fields);
        }

        @Override
        public Mono<Long> executeNative(NativeQuery query) {
            return Mono.empty();
        }

        @Override
        public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
            return Flux.empty();
        }

        @Override
        public <T> Mono<T> queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper) {
            return Mono.empty();
        }

        @Override
        public <T> Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec) {
            return Flux.empty();
        }

        @Override
        public <T> Flux<T> findAll(Class<T> entityType, CompiledQuery query, Object... bindings) {
            return Flux.empty();
        }

        @Override
        public Mono<Long> execute(CompiledQuery query, Object... bindings) {
            return Mono.empty();
        }
    }
}
