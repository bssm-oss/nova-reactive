package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.NativeQuery;
import io.nova.query.Pageable;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import io.nova.fetch.FetchGroup;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 프록시가 메서드 호출을 fake {@link ReactiveEntityOperations}로 정확히 위임하는지 검증한다.
 * 각 호출은 fake에 method name + entity type + arg를 기록하고, 동시에 호출 결과 publisher가
 * 그대로 흘러가는지 StepVerifier로 확인한다.
 */
class SimpleReactiveRepositoryTest {

    static final class User {
        final long id;
        final String email;

        User(long id, String email) {
            this.id = id;
            this.email = email;
        }
    }

    interface UserRepository extends ReactiveCrudRepository<User, Long> {
    }

    private static UserRepository newProxy(RecordingEntityOperations operations) {
        SimpleReactiveRepository handler = new SimpleReactiveRepository(User.class, Long.class, operations);
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                handler);
    }

    @Test
    void saveDelegatesToEntityOperations() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        User user = new User(0L, "a@nova.io");
        operations.nextSave = Mono.just(user);

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.save(user))
                .expectNext(user)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("save", last.name());
        assertSame(user, last.args()[0]);
    }

    @Test
    void saveAllDelegatesToEntityOperations() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        User a = new User(0L, "a@nova.io");
        User b = new User(0L, "b@nova.io");
        operations.nextSaveAll = Flux.just(a, b);

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.saveAll(List.of(a, b)))
                .expectNext(a, b)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("saveAll", last.name());
        assertEquals(List.of(a, b), last.args()[0]);
    }

    @Test
    void findByIdDelegatesEntityTypeAndId() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        User found = new User(42L, "found@nova.io");
        operations.nextFindById = Mono.just(found);

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.findById(42L))
                .expectNext(found)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findById", last.name());
        assertSame(User.class, last.args()[0]);
        assertEquals(42L, last.args()[1]);
    }

    @Test
    void existsByIdDelegatesToEntityOperationsExistsById() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextExistsById = Mono.just(Boolean.TRUE);

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.existsById(7L))
                .expectNext(Boolean.TRUE)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("existsById", last.name());
        assertSame(User.class, last.args()[0]);
        assertEquals(7L, last.args()[1]);
    }

    @Test
    void findAllNoArgPassesEmptyQuerySpec() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextFindAll = Flux.empty();

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.findAll())
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findAll", last.name());
        assertSame(User.class, last.args()[0]);
        QuerySpec spec = (QuerySpec) last.args()[1];
        assertEquals(QuerySpec.empty(), spec);
    }

    @Test
    void findAllWithQuerySpecPropagates() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextFindAll = Flux.empty();
        QuerySpec spec = QuerySpec.empty();

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.findAll(spec))
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findAll", last.name());
        assertSame(spec, last.args()[1]);
    }

    @Test
    void findAllWithPageableAppliesPageOnEmptySpec() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextFindAll = Flux.empty();

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.findAll(Pageable.of(20, 0L)))
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findAll", last.name());
        QuerySpec spec = (QuerySpec) last.args()[1];
        assertEquals(Pageable.of(20, 0L), spec.pageable());
    }

    @Test
    void findAllByIdDelegates() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextFindAllById = Flux.empty();
        List<Long> ids = List.of(1L, 2L, 3L);

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.findAllById(ids))
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findAllById", last.name());
        assertSame(User.class, last.args()[0]);
        assertEquals(ids, last.args()[1]);
    }

    @Test
    void countDelegatesWithEmptySpec() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextCount = Mono.just(5L);

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.count())
                .expectNext(5L)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("count", last.name());
        assertEquals(QuerySpec.empty(), last.args()[1]);
    }

    @Test
    void deleteByIdDelegates() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextDeleteById = Mono.just(1L);

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.deleteById(99L))
                .expectNext(1L)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("deleteById", last.name());
        assertSame(User.class, last.args()[0]);
        assertEquals(99L, last.args()[1]);
    }

    @Test
    void deleteDelegates() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextDelete = Mono.just(1L);
        User u = new User(1L, "x");

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.delete(u))
                .expectNext(1L)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("delete", last.name());
        assertSame(u, last.args()[0]);
    }

    @Test
    void deleteAllDelegates() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        operations.nextDeleteAll = Mono.just(2L);
        List<User> users = List.of(new User(1L, "x"), new User(2L, "y"));

        UserRepository repository = newProxy(operations);

        StepVerifier.create(repository.deleteAll(users))
                .expectNext(2L)
                .verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("deleteAll", last.name());
        assertSame(users, last.args()[0]);
    }

    @Test
    void objectMethodsDoNotReachEntityOperations() {
        RecordingEntityOperations operations = new RecordingEntityOperations();
        UserRepository repository = newProxy(operations);
        // identity-based equals/hashCode/toString
        assertEquals(repository, repository);
        assertFalse(repository.equals(new Object()));
        assertNotNull(repository.toString());
        // hashCode does not blow up
        repository.hashCode();
        assertEquals(0, operations.invocations.size(), "Object methods must not invoke entity operations");
    }

    /**
     * 호출을 record하는 fake. 각 메서드에 대한 "다음 응답" publisher 필드를 두고, invoke 시 기록과
     * 함께 그 publisher를 반환한다. 사용하지 않는 메서드는 빈 publisher로 둔다.
     */
    static final class RecordingEntityOperations implements ReactiveEntityOperations {
        final List<Invocation> invocations = new ArrayList<>();

        Mono<?> nextSave;
        Flux<?> nextSaveAll = Flux.empty();
        Mono<?> nextFindById;
        Mono<Boolean> nextExists = Mono.just(Boolean.FALSE);
        Mono<Boolean> nextExistsById = Mono.just(Boolean.FALSE);
        Flux<?> nextFindAll = Flux.empty();
        Flux<?> nextFindAllById = Flux.empty();
        Mono<Long> nextCount = Mono.just(0L);
        Mono<Long> nextDeleteById = Mono.just(0L);
        Mono<Long> nextDelete = Mono.just(0L);
        Mono<Long> nextDeleteAll = Mono.just(0L);

        Invocation lastInvocation() {
            if (invocations.isEmpty()) {
                throw new IllegalStateException("no invocation recorded");
            }
            return invocations.get(invocations.size() - 1);
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
        public <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
            invocations.add(new Invocation("findAllProjection", new Object[]{projection, querySpec}));
            return Flux.empty();
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
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            invocations.add(new Invocation("inTransaction", new Object[]{callback}));
            return callback.apply(this);
        }

        @Override
        public <T> Mono<Long> deleteAll(Iterable<T> entities) {
            invocations.add(new Invocation("deleteAll", new Object[]{entities}));
            return nextDeleteAll;
        }

        @Override
        public <T, ID> Mono<Long> deleteAllById(Class<T> entityType, Iterable<ID> ids) {
            return ReactiveEntityOperations.super.deleteAllById(entityType, ids);
        }

        @Override
        public <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
            return ReactiveEntityOperations.super.deleteAll(entityType, querySpec);
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

    record Invocation(String name, Object[] args) {
    }
}
