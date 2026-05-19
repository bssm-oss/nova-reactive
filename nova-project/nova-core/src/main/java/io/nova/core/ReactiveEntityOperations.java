package io.nova.core;

import io.nova.fetch.FetchGroup;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.NativeQuery;
import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Slice;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 엔티티 저장, 조회, 삭제를 위한 기본 reactive 진입점이다.
 */
public interface ReactiveEntityOperations {
    /**
     * 식별자 상태를 기준으로 insert 또는 update를 선택해 엔티티를 저장한다.
     * {@code @GeneratedValue}로 생성된 식별자는 저장 후 entity에 다시 주입되어 반환된다.
     */
    <T> Mono<T> save(T entity);

    /**
     * 명시한 property 컬럼만 update한다. {@code save(T)}와 달리 SET 절에 빠진 컬럼은 건드리지 않으므로
     * 다중 사용자가 같은 행의 서로 다른 컬럼을 수정하는 환경에서 lost update를 줄일 수 있다.
     * <p>
     * {@code fields}는 entity의 property name(Java 필드명)이며, 빈 컬렉션·미존재 field·id field는
     * 모두 거부된다. entity의 id가 {@code null}이면 {@code Mono.error(IllegalArgumentException)}이
     * 발행된다. 기본 구현은 외부 구현체가 미구현 시 호출자가 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 {@code SqlRenderer.update(metadata, entity, fields)}로 위임한다.
     */
    default <T> Mono<T> update(T entity, Iterable<String> fields) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.update(entity, fields) must be overridden by the implementation"));
    }

    /**
     * 식별자로 단건 엔티티를 조회한다.
     */
    <T, ID> Mono<T> findById(Class<T> entityType, ID id);

    /**
     * 여러 식별자에 해당하는 엔티티를 한 번에 조회한다. 기본 구현은 단건 {@link #findById(Class, Object)}로
     * 폴백하며, 구현체는 {@code IN} 절을 사용한 단일 쿼리로 최적화할 수 있다.
     * <p>
     * 결과 순서는 데이터베이스가 반환하는 순서를 따르므로 {@code ids} 입력 순서와 일치하지 않을 수 있다.
     * 입력 순서가 필요하면 호출자가 결과를 식별자 기준으로 정렬해야 한다. 존재하지 않는 식별자는
     * 결과에 포함되지 않는다.
     */
    default <T, ID> Flux<T> findAllById(Class<T> entityType, Iterable<ID> ids) {
        return Flux.fromIterable(ids).concatMap(id -> findById(entityType, id));
    }

    /**
     * 주어진 쿼리 명세에 맞는 엔티티를 모두 조회한다.
     */
    <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec);

    /**
     * 지정된 entity property만 SELECT 절에 포함시켜 가져온 뒤 {@link Projection#projectionType()}으로
     * 매핑한 결과를 발행한다. projection 타입은 record이거나 명시적인 단일 생성자를 가진 일반 class여야
     * 하며, 생성자 파라미터 개수와 순서가 {@link Projection#fields()} 순서와 일치해야 한다.
     * <p>
     * entity property의 column converter는 적용된 뒤 projection 타입에 주입되며, {@code @SoftDelete}
     * 메타데이터가 있는 entity는 SELECT에 자동으로 alive 조건이 덧붙는다. 검증 실패는 모두
     * {@link IllegalArgumentException}으로 발행된다.
     * <p>
     * 기본 구현은 외부 {@link ReactiveEntityOperations} 직접 구현자가 자동으로 깨지지 않도록 명시적
     * 예외를 던지며, {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
        return Flux.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.findAll(Projection, QuerySpec) must be overridden by the implementation"));
    }

    /**
     * 엔티티가 가진 식별자 값을 사용해 삭제한다.
     */
    <T> Mono<Long> delete(T entity);

    /**
     * 식별자로 직접 엔티티를 삭제한다.
     */
    <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id);

    /**
     * 주어진 쿼리 명세에 맞는 행 수를 반환한다.
     */
    <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec);

    /**
     * 주어진 쿼리 명세에 맞는 행이 하나 이상 존재하는지 반환한다.
     */
    <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec);

    /**
     * 주어진 쿼리 명세에 {@link Pageable}을 적용해 한 페이지의 entity와 총 행 수를 함께 조회한다.
     * 결과는 {@link Page}로 묶여 발행되며, {@link Page#totalElements()}는 LIMIT/OFFSET을 제거한
     * predicate 기준의 전체 행 수를 의미한다 — count 정확성을 위해 {@code SELECT}와 {@code COUNT(*)}는
     * 두 번 발행된다(즉, 트랜잭션 경계 안에서 호출하지 않으면 두 쿼리 사이에 INSERT/DELETE가
     * 끼어들어 미세한 race가 가능하다).
     * <p>
     * {@code pageable}이 {@code null}이면 {@link NullPointerException}이 발행된다. {@code querySpec}이
     * {@code null}이면 {@link QuerySpec#empty()}로 normalize되며, 호출자가 {@code querySpec.page(...)}로
     * 미리 설정한 pageable은 {@code pageable} 인자로 덮어쓰여진다.
     * <p>
     * 기본 구현은 외부 직접 구현자가 자동으로 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <T> Mono<Page<T>> findAll(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.findAll(Class, QuerySpec, Pageable) must be overridden by the implementation"));
    }

    /**
     * 주어진 쿼리 명세에 {@link Pageable}을 적용해 한 페이지의 entity와 다음 페이지 존재 여부를
     * {@link Slice}로 발행한다. {@link #findAll(Class, QuerySpec, Pageable)}와 달리 별도의
     * {@code COUNT(*)} 쿼리를 발행하지 않으므로 비용이 낮으며, 총 페이지 수가 필요 없는
     * infinite scroll 같은 시나리오에 적합하다.
     * <p>
     * 구현체는 일반적으로 {@code limit + 1}만큼 행을 조회한 뒤 한 건 초과 여부로 {@code hasNext}를
     * 결정하고, {@link Slice#content()}에는 정확히 {@code pageable.limit()}개를 노출한다.
     * <p>
     * {@code pageable}이 {@code null}이면 {@link NullPointerException}이 발행된다. {@code querySpec}이
     * {@code null}이면 {@link QuerySpec#empty()}로 normalize되며, 호출자가 미리 설정한 pageable은
     * 인자 값으로 덮어쓰여진다.
     * <p>
     * 기본 구현은 외부 직접 구현자가 자동으로 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <T> Mono<Slice<T>> findSlice(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.findSlice(Class, QuerySpec, Pageable) must be overridden by the implementation"));
    }

    /**
     * 설정된 transaction operations를 사용해 콜백을 트랜잭션 경계 안에서 실행한다.
     */
    <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback);

    /**
     * 여러 엔티티를 한 번에 저장한다. 기본 구현은 단건 {@link #save(Object)}로 폴백한다.
     * 같은 SQL 셰이프끼리 묶어 배치로 실행하는 최적화는 구현체에서 override한다.
     * <p>
     * 배치 경로에서도 데이터베이스가 생성한 ID는 입력 순서대로 각 entity에 다시 주입된다 ({@code @Id}가
     * {@code @GeneratedValue}로 표시된 경우). 따라서 {@link #save(Object)}와 동일하게 호출 이후
     * 반환된 entity 또는 입력 entity 인스턴스에서 생성 ID를 즉시 사용할 수 있다.
     * <p>
     * 입력 Iterable이 List가 아닌 경우 내부에서 한 번 수집하므로, 호출 이후 같은 인스턴스를
     * 다시 사용하려는 경우에는 List로 미리 만들어 전달하는 것을 권장한다. 또한 입력 List를
     * 호출자가 동시에 수정하지 않을 책임이 있다 (방어 카피를 수행하지 않는다).
     */
    default <T> Flux<T> saveAll(Iterable<T> entities) {
        return Flux.fromIterable(entities).concatMap(this::save);
    }

    /**
     * 여러 엔티티를 한 번에 삭제한다. 기본 구현은 단건 {@link #delete(Object)}로 폴백한다.
     * 반환값은 영향 받은 총 행 수의 합이다.
     */
    default <T> Mono<Long> deleteAll(Iterable<T> entities) {
        return Flux.fromIterable(entities).concatMap(this::delete).reduce(0L, Long::sum);
    }

    /**
     * 여러 식별자에 해당하는 엔티티를 한 번에 삭제한다. 기본 구현은 단건 {@link #deleteById(Class, Object)}로 폴백한다.
     * 반환값은 영향 받은 총 행 수의 합이다.
     */
    default <T, ID> Mono<Long> deleteAllById(Class<T> entityType, Iterable<ID> ids) {
        return Flux.fromIterable(ids).concatMap(id -> deleteById(entityType, id)).reduce(0L, Long::sum);
    }

    /**
     * predicate에 일치하는 모든 행을 한 번의 {@code delete from t where ...} 쿼리로 삭제한다.
     * {@code querySpec.predicate()}는 non-null이어야 하며, sort/pageable이 함께 들어오면 거부된다 —
     * DELETE 표준에 sort/limit이 없어 dialect별 동작이 달라지기 때문이다. 반환값은 영향 받은 행 수다.
     * <p>
     * 기본 구현은 구현체가 이 메서드를 미구현 시 외부 호출자가 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 {@code SqlRenderer.deleteByQuery}로 위임한다.
     */
    default <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.deleteAll(Class, QuerySpec) must be overridden by the implementation"));
    }

    /**
     * Updater builder로 지정한 부분 UPDATE를 실행한다. SET 절은 최소 1개의 field 할당과 non-null
     * WHERE 절을 요구한다. 반환값은 영향 받은 행 수다.
     * <p>
     * 기본 구현은 외부 {@link ReactiveEntityOperations} 직접 구현자가 자동으로 깨지지 않도록
     * 명시적 예외를 던지며, {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.update(Class, Updater) must be overridden by the implementation"));
    }

    /**
     * {@link NativeQuery}로 표현된 raw SQL 한 건을 INSERT/UPDATE/DELETE 또는 DDL로 실행한다.
     * 반환값은 영향 받은 행 수이며, 드라이버가 행 수를 보고하지 않는 경우 0일 수 있다.
     */
    default Mono<Long> executeNative(NativeQuery query) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.executeNative must be overridden"));
    }

    /**
     * {@link NativeQuery}로 표현된 raw SELECT를 실행하고 {@code mapper}로 각 행을 변환해 발행한다.
     */
    default <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
        return Flux.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.queryNative must be overridden"));
    }

    /**
     * {@link NativeQuery}로 표현된 raw SELECT를 실행하고 첫 행만 변환해 발행한다. 행이 없으면 빈 {@link Mono}.
     */
    default <T> Mono<T> queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.queryNativeOne must be overridden"));
    }

    /**
     * Aggregations DSL의 {@link AggregateSpec}을 실행해 한 row당 {@link AggregateRow}를 발행한다.
     * 결과 row에는 {@code groupBy} property가 (있다면) 입력 순서대로, 이어서 {@link io.nova.query.Aggregation}의
     * 결과 alias가 포함된다.
     */
    default <T> Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec) {
        return Flux.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.aggregate(Class, AggregateSpec) must be overridden by the implementation"));
    }

    /**
     * Aggregations DSL의 {@link AggregateSpec}을 실행해 결과 row를 사용자 지정 mapper로 변환한다.
     */
    default <T, R> Flux<R> aggregate(Class<T> entityType, AggregateSpec spec, Function<RowAccessor, R> mapper) {
        java.util.Objects.requireNonNull(mapper, "mapper must not be null");
        return aggregate(entityType, spec).map(row -> mapper.apply(new AggregateRowAccessor(row)));
    }

    /**
     * {@link AggregateRow}를 {@link RowAccessor}로 어댑팅한다. type-cast는 호출자 책임이며,
     * driver-side 타입 변환은 수행하지 않는다 — aggregate 결과의 raw type을 그대로 노출한다.
     */
    final class AggregateRowAccessor implements RowAccessor {
        private final AggregateRow row;

        AggregateRowAccessor(AggregateRow row) {
            this.row = row;
        }

        @Override
        public <T> T get(String columnName, Class<T> type) {
            return row.get(columnName, type);
        }
    }

    /**
     * 미리 컴파일된 SELECT {@link CompiledQuery}를 주어진 binding으로 실행하고 결과 행을 엔티티로 매핑한다.
     */
    default <T> Flux<T> findAll(Class<T> entityType, CompiledQuery query, Object... bindings) {
        return Flux.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.findAll(Class, CompiledQuery, Object...) must be overridden by the implementation"));
    }

    /**
     * 미리 컴파일된 INSERT/UPDATE/DELETE {@link CompiledQuery}를 주어진 binding으로 실행한다.
     */
    default Mono<Long> execute(CompiledQuery query, Object... bindings) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.execute(CompiledQuery, Object...) must be overridden by the implementation"));
    }

    /**
     * 식별자로 단건 parent 엔티티를 조회한 뒤 {@link FetchGroup}으로 선언된 child들을 batch로 hydrate한다.
     * 각 child spec은 한 번의 IN 절 쿼리로 묶이므로 spec이 K개면 child query는 K개로 N+1과 무관하다.
     * <p>
     * 기본 구현은 외부 직접 구현자가 자동으로 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <P> Mono<P> findById(Class<P> entityType, Object id, FetchGroup<P> fetchGroup) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.findById(Class, Object, FetchGroup) must be overridden by the implementation"));
    }

    /**
     * 주어진 parent 타입의 모든 엔티티를 조회한 뒤 {@link FetchGroup}으로 선언된 child들을 batch로 hydrate한다.
     * 각 child spec은 한 번의 IN 절 쿼리로 묶이므로 spec이 K개면 child query는 K개로 parent 수와 무관하다.
     * <p>
     * 기본 구현은 외부 직접 구현자가 자동으로 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <P> Flux<P> findAll(Class<P> entityType, FetchGroup<P> fetchGroup) {
        return Flux.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.findAll(Class, FetchGroup) must be overridden by the implementation"));
    }
}
