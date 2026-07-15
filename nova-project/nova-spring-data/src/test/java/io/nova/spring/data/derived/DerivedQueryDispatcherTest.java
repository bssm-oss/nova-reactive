package io.nova.spring.data.derived;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.CompoundPredicate;
import io.nova.query.Condition;
import io.nova.query.ComparisonOperator;
import io.nova.query.LogicalOperator;
import io.nova.query.NativeQuery;
import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Predicate;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Slice;
import io.nova.query.Sort;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedQueryDispatcherTest {

    static final class Account {
        Long id;
        String email;
        boolean active;
        Instant createdAt;
        int loginCount;
    }

    interface AccountRepository {
        Flux<Account> findByEmail(String email);

        Mono<Account> findFirstByActiveTrue();

        Mono<Long> countByActive(boolean active);

        Mono<Boolean> existsByEmail(String email);

        Mono<Long> deleteByActiveFalse();

        Flux<Account> findByLoginCountBetween(int low, int high);

        Flux<Account> findByEmailIn(Iterable<String> emails);

        Flux<Account> findByEmailStartingWith(String prefix);

        Flux<Account> findByEmailAndActiveTrue(String email);

        Flux<Account> findByEmailOrActiveFalse(String email);

        Flux<Account> findByActiveTrueOrderByLoginCountDesc();

        Flux<Account> findTop2ByActiveTrue();

        Flux<Account> findByEmailIgnoreCase(String email);

        Flux<Account> findByEmailContainingIgnoreCase(String chunk);

        // --- T2b: Pageable / Page / Slice ---
        Flux<Account> findByActiveTrue(Pageable pageable);

        Mono<Page<Account>> findByActive(boolean active, Pageable pageable);

        Mono<Slice<Account>> findByEmailContaining(String chunk, Pageable pageable);
    }

    private final CapturingOperations operations = new CapturingOperations();
    private final DerivedQueries derived = new DerivedQueries(Account.class, operations);

    private Method method(String name, Class<?>... params) {
        try {
            return AccountRepository.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("findBy → findAll(entity, QuerySpec) 위임")
    void findByDelegatesToFindAll() {
        operations.nextFindAll = Flux.empty();

        Object result = derived.tryDispatch(method("findByEmail", String.class),
                new Object[]{"x@nova.io"}).orElseThrow();
        StepVerifier.create((Flux<?>) result).verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findAll", last.name());
        assertSame(Account.class, last.args()[0]);
        QuerySpec spec = (QuerySpec) last.args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertEquals("email", c.property());
        assertSame(ComparisonOperator.EQ, c.operator());
        assertEquals("x@nova.io", c.value());
    }

    @Test
    @DisplayName("findFirstBy → LIMIT 1 으로 첫 행만")
    void findFirstByAppliesLimitOne() {
        operations.nextFindAll = Flux.empty();

        Object result = derived.tryDispatch(method("findFirstByActiveTrue"),
                new Object[0]).orElseThrow();
        StepVerifier.create((Mono<?>) result).verifyComplete();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        assertNotNull(spec.pageable(), "expected page() to be applied");
        assertEquals(1, spec.pageable().limit(), "limit must be 1 for first-by");
        assertEquals(0L, spec.pageable().offset());
    }

    @Test
    @DisplayName("countBy → count(entity, QuerySpec)")
    void countByDelegatesToCount() {
        @SuppressWarnings("unchecked")
        Mono<Long> result = (Mono<Long>) derived.tryDispatch(method("countByActive", boolean.class),
                new Object[]{true}).orElseThrow();
        StepVerifier.create(result).expectNext(0L).verifyComplete();
        assertEquals("count", operations.lastInvocation().name());
    }

    @Test
    @DisplayName("existsBy → exists(entity, QuerySpec)")
    void existsByDelegatesToExists() {
        @SuppressWarnings("unchecked")
        Mono<Boolean> result = (Mono<Boolean>) derived.tryDispatch(method("existsByEmail", String.class),
                new Object[]{"x@nova.io"}).orElseThrow();
        StepVerifier.create(result).expectNext(Boolean.FALSE).verifyComplete();
        assertEquals("exists", operations.lastInvocation().name());
    }

    @Test
    @DisplayName("deleteBy → deleteAll(entity, QuerySpec)")
    void deleteByDelegatesToDeleteAll() {
        @SuppressWarnings("unchecked")
        Mono<Long> result = (Mono<Long>) derived.tryDispatch(method("deleteByActiveFalse"),
                new Object[0]).orElseThrow();
        StepVerifier.create(result).expectNext(0L).verifyComplete();
        assertEquals("deleteAllBySpec", operations.lastInvocation().name());
    }

    @Test
    @DisplayName("Between은 두 인자를 순서대로 소비해 BETWEEN condition을 만든다")
    void betweenConsumesTwoArgs() {
        operations.nextFindAll = Flux.empty();

        derived.tryDispatch(method("findByLoginCountBetween", int.class, int.class),
                new Object[]{0, 9}).orElseThrow();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertSame(ComparisonOperator.BETWEEN, c.operator());
        assertEquals("loginCount", c.property());
        assertEquals(List.of(0, 9), c.value());
    }

    @Test
    @DisplayName("In은 Iterable을 받아 IN condition을 만든다")
    void inAcceptsIterable() {
        operations.nextFindAll = Flux.empty();

        derived.tryDispatch(method("findByEmailIn", Iterable.class),
                new Object[]{List.of("a@nova.io", "b@nova.io")}).orElseThrow();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertSame(ComparisonOperator.IN, c.operator());
        assertEquals(List.of("a@nova.io", "b@nova.io"), c.value());
    }

    @Test
    @DisplayName("StartingWith은 'prefix%' 패턴으로 LIKE condition을 만든다")
    void startingWithAppendsPercent() {
        operations.nextFindAll = Flux.empty();

        derived.tryDispatch(method("findByEmailStartingWith", String.class),
                new Object[]{"a"}).orElseThrow();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertSame(ComparisonOperator.LIKE, c.operator());
        assertEquals("a%", c.value());
    }

    @Test
    @DisplayName("And 는 AND CompoundPredicate, Or 는 OR CompoundPredicate")
    void connectorsProduceExpectedShape() {
        operations.nextFindAll = Flux.empty();

        derived.tryDispatch(method("findByEmailAndActiveTrue", String.class),
                new Object[]{"a@nova.io"}).orElseThrow();
        QuerySpec andSpec = (QuerySpec) operations.lastInvocation().args()[1];
        CompoundPredicate andPredicate = assertInstanceOf(CompoundPredicate.class, andSpec.predicate());
        assertSame(LogicalOperator.AND, andPredicate.operator());
        assertEquals(2, andPredicate.predicates().size());

        operations.invocations.clear();
        derived.tryDispatch(method("findByEmailOrActiveFalse", String.class),
                new Object[]{"a@nova.io"}).orElseThrow();
        QuerySpec orSpec = (QuerySpec) operations.lastInvocation().args()[1];
        CompoundPredicate orPredicate = assertInstanceOf(CompoundPredicate.class, orSpec.predicate());
        assertSame(LogicalOperator.OR, orPredicate.operator());
        assertEquals(2, orPredicate.predicates().size());
    }

    @Test
    @DisplayName("OrderBy 절은 QuerySpec.sort 로 그대로 전달된다")
    void orderByAppliedToSort() {
        operations.nextFindAll = Flux.empty();

        derived.tryDispatch(method("findByActiveTrueOrderByLoginCountDesc"),
                new Object[0]).orElseThrow();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        Sort sort = spec.sort();
        assertNotNull(sort);
        assertEquals(1, sort.orders().size());
        Sort.Order order = sort.orders().get(0);
        assertEquals("loginCount", order.property());
        assertSame(Sort.Direction.DESC, order.direction());
    }

    @Test
    @DisplayName("findTop2By → FIND_ALL 유지 + LIMIT 2 pageable")
    void findTopNAppliesLimit() {
        operations.nextFindAll = Flux.empty();

        Object result = derived.tryDispatch(method("findTop2ByActiveTrue"), new Object[0]).orElseThrow();
        StepVerifier.create((Flux<?>) result).verifyComplete();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        assertNotNull(spec.pageable(), "findTop<N>By must apply a pageable");
        assertEquals(2, spec.pageable().limit());
        assertEquals(0L, spec.pageable().offset());
    }

    @Test
    @DisplayName("IgnoreCase(EQ)는 ILIKE condition으로 렌더된다")
    void ignoreCaseEqualityUsesIlike() {
        operations.nextFindAll = Flux.empty();

        derived.tryDispatch(method("findByEmailIgnoreCase", String.class),
                new Object[]{"A@nova.io"}).orElseThrow();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertSame(ComparisonOperator.ILIKE, c.operator());
        assertEquals("email", c.property());
        assertEquals("A@nova.io", c.value());
    }

    @Test
    @DisplayName("IgnoreCase(Containing)은 %substring% 패턴의 ILIKE condition으로 렌더된다")
    void ignoreCaseContainingUsesIlikeWithWildcards() {
        operations.nextFindAll = Flux.empty();

        derived.tryDispatch(method("findByEmailContainingIgnoreCase", String.class),
                new Object[]{"nova"}).orElseThrow();

        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertSame(ComparisonOperator.ILIKE, c.operator());
        assertEquals("%nova%", c.value());
    }

    @Test
    @DisplayName("Flux + Pageable → findAll(entity, spec.page(pageable)) 로 LIMIT/OFFSET 적용")
    void fluxPageableAppliesPageToSpec() {
        operations.nextFindAll = Flux.empty();
        Pageable pageable = Pageable.of(5, 10L);

        Object result = derived.tryDispatch(method("findByActiveTrue", Pageable.class),
                new Object[]{pageable}).orElseThrow();
        StepVerifier.create((Flux<?>) result).verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findAll", last.name());
        QuerySpec spec = (QuerySpec) last.args()[1];
        assertNotNull(spec.pageable());
        assertEquals(5, spec.pageable().limit());
        assertEquals(10L, spec.pageable().offset());
    }

    @Test
    @DisplayName("Mono<Page> → findAll(entity, spec, pageable) 로 위임(spec에는 page 미적용, pageable 별도 전달)")
    void monoPageDelegatesToPagedFindAll() {
        Pageable pageable = Pageable.of(3, 0L);

        @SuppressWarnings("unchecked")
        Mono<Page<Object>> result = (Mono<Page<Object>>) derived.tryDispatch(
                method("findByActive", boolean.class, Pageable.class),
                new Object[]{true, pageable}).orElseThrow();
        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findAllPaged", last.name());
        QuerySpec spec = (QuerySpec) last.args()[1];
        assertNull(spec.pageable(), "spec은 page 미적용 — pageable은 세 번째 인자로 전달");
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertEquals("active", c.property());
        assertSame(pageable, last.args()[2]);
    }

    @Test
    @DisplayName("Mono<Slice> → findSlice(entity, spec, pageable) 로 위임")
    void monoSliceDelegatesToFindSlice() {
        Pageable pageable = Pageable.of(4, 0L);

        @SuppressWarnings("unchecked")
        Mono<Slice<Object>> result = (Mono<Slice<Object>>) derived.tryDispatch(
                method("findByEmailContaining", String.class, Pageable.class),
                new Object[]{"nova", pageable}).orElseThrow();
        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        Invocation last = operations.lastInvocation();
        assertEquals("findSlice", last.name());
        assertSame(pageable, last.args()[2]);
        Condition c = assertInstanceOf(Condition.class, ((QuerySpec) last.args()[1]).predicate());
        assertSame(ComparisonOperator.LIKE, c.operator());
        assertEquals("%nova%", c.value());
    }

    record Invocation(String name, Object[] args) {
    }

    /**
     * derived 경로가 호출하는 4개 메서드만 기록한다. 그 외 메서드는 호출되면 즉시 fail시킨다 — 의도하지
     * 않은 경로로 새는지 검증하기 위함.
     */
    static final class CapturingOperations implements ReactiveEntityOperations {
        final List<Invocation> invocations = new ArrayList<>();
        Flux<?> nextFindAll = Flux.empty();
        Mono<Long> nextCount = Mono.just(0L);
        Mono<Boolean> nextExists = Mono.just(Boolean.FALSE);
        Mono<Long> nextDelete = Mono.just(0L);

        Invocation lastInvocation() {
            if (invocations.isEmpty()) {
                throw new IllegalStateException("no invocation recorded");
            }
            return invocations.get(invocations.size() - 1);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            invocations.add(new Invocation("findAll", new Object[]{entityType, querySpec}));
            return (Flux<T>) nextFindAll;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<Page<T>> findAll(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
            invocations.add(new Invocation("findAllPaged", new Object[]{entityType, querySpec, pageable}));
            return (Mono<Page<T>>) (Mono<?>) Mono.just(new Page<>(List.of(), 0L, pageable));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<Slice<T>> findSlice(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
            invocations.add(new Invocation("findSlice", new Object[]{entityType, querySpec, pageable}));
            return (Mono<Slice<T>>) (Mono<?>) Mono.just(new Slice<>(List.of(), pageable, false));
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
        public <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
            invocations.add(new Invocation("deleteAllBySpec", new Object[]{entityType, querySpec}));
            return nextDelete;
        }

        // 아래는 derived 경로에서 호출되면 안 되는 메서드. fail-fast 가드.

        @Override
        public <T> Mono<T> save(T entity) {
            throw new AssertionError("derived path should not call save");
        }

        @Override
        public <T> Flux<T> saveAll(Iterable<T> entities) {
            throw new AssertionError("derived path should not call saveAll");
        }

        @Override
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            throw new AssertionError("derived path should not call findById");
        }

        @Override
        public <T> Mono<Long> delete(T entity) {
            throw new AssertionError("derived path should not call delete(entity)");
        }

        @Override
        public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
            throw new AssertionError("derived path should not call deleteById");
        }

        @Override
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            throw new AssertionError("derived path should not call inTransaction");
        }

        @Override
        public <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
            throw new AssertionError("derived path should not call findAll(Projection, ...)");
        }

        @Override
        public <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
            throw new AssertionError("derived path should not call update(Updater)");
        }

        @Override
        public <T> Mono<T> update(T entity, Iterable<String> fields) {
            throw new AssertionError("derived path should not call update(entity, fields)");
        }

        @Override
        public Mono<Long> executeNative(NativeQuery query) {
            throw new AssertionError("derived path should not call executeNative");
        }

        @Override
        public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
            throw new AssertionError("derived path should not call queryNative");
        }

        @Override
        public <T> Mono<T> queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper) {
            throw new AssertionError("derived path should not call queryNativeOne");
        }

        @Override
        public <T> Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec) {
            throw new AssertionError("derived path should not call aggregate");
        }

        @Override
        public <T> Flux<T> findAll(Class<T> entityType, CompiledQuery query, Object... bindings) {
            throw new AssertionError("derived path should not call findAll(CompiledQuery, ...)");
        }

        @Override
        public Mono<Long> execute(CompiledQuery query, Object... bindings) {
            throw new AssertionError("derived path should not call execute(CompiledQuery, ...)");
        }
    }

    // 빌드 경고를 줄이기 위해 사용된 ComparisonOperator 들이 코어에 존재함을 확인하는 트리비얼 가드.
    @Test
    @DisplayName("Condition value 가 null 인 IsNull은 NULL 인자 처리")
    void isNullProducesNullValue() {
        operations.nextFindAll = Flux.empty();

        interface NullRepo {
            Flux<Account> findByEmailIsNull();
        }
        Method m;
        try {
            m = NullRepo.class.getMethod("findByEmailIsNull");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        derived.tryDispatch(m, new Object[0]);
        QuerySpec spec = (QuerySpec) operations.lastInvocation().args()[1];
        Condition c = assertInstanceOf(Condition.class, spec.predicate());
        assertSame(ComparisonOperator.IS_NULL, c.operator());
        assertEquals("email", c.property());
        assertNull(c.value());
        // OrderBy 절이 없으므로 sort 도 적용되지 않는다.
        assertTrue(spec.sort() == null || spec.sort().orders().isEmpty(),
                "no order by clause should be applied");
    }
}
