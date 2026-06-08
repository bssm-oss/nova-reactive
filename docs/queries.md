<!-- SPDX-License-Identifier: Apache-2.0 -->

# Queries

`ReactiveEntityOperations`는 Nova의 메인 진입점입니다. 단건 CRUD 외에 batch / partial update / projection / native SQL / aggregation / compiled query 진입점을 제공합니다.

## CRUD operations

```java
public interface ReactiveEntityOperations {
    // 단건
    <T>      Mono<T>       save(T entity);
    <T>      Mono<T>       update(T entity, Iterable<String> fields);          // partial update
    <T, ID>  Mono<T>       findById(Class<T> entityType, ID id);
    <T>      Mono<Long>    delete(T entity);
    <T, ID>  Mono<Long>    deleteById(Class<T> entityType, ID id);

    // 다건 / 배치
    <T>      Flux<T>       saveAll(Iterable<T> entities);                       // generated id batch 회수 포함
    <T, ID>  Flux<T>       findAllById(Class<T> entityType, Iterable<ID> ids);   // 단일 IN 쿼리
    <T>      Flux<T>       findAll(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Long>    deleteAll(Iterable<T> entities);
    <T, ID>  Mono<Long>    deleteAllById(Class<T> entityType, Iterable<ID> ids);
    <T>      Mono<Long>    deleteAll(Class<T> entityType, QuerySpec querySpec);  // criteria 기반 bulk delete

    // 집계 / 카운트 / 존재 여부
    <T>      Mono<Long>    count(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec);
    <T, ID>  Mono<Boolean> existsById(Class<T> entityType, ID id);     // 기본 구현은 findById(...).hasElement()

    // 페이지네이션 — content + total을 한 번에 반환
    <T>      Mono<Page<T>>  findAll(Class<T> entityType, QuerySpec querySpec, Pageable pageable);
    <T>      Mono<Slice<T>> findSlice(Class<T> entityType, QuerySpec querySpec, Pageable pageable);

    // 관계 매핑 (@ManyToOne/@OneToMany) — FetchGroup으로 명시적 batch 또는 어노테이션 자동 적용
    <P>      Mono<P>        findById(Class<P> entityType, Object id, FetchGroup<P> fetchGroup);
    <P>      Flux<P>        findAll(Class<P> entityType, FetchGroup<P> fetchGroup);

    // Updater DSL — entity 인스턴스 없이 criteria 기반 partial UPDATE
    <T>      Mono<Long>    update(Class<T> entityType, Updater<T> updater);

    // Projection — entity 일부 컬럼을 record/DTO에 매핑
    <E, P>   Flux<P>       findAll(Projection<E, P> projection, QuerySpec querySpec);

    // Native SQL
              Mono<Long>   executeNative(NativeQuery query);
    <T>      Flux<T>       queryNative(NativeQuery query, Function<RowAccessor, T> mapper);
    <T>      Mono<T>       queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper);

    // Aggregations DSL
    <T>      Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec);
    <T, R>   Flux<R>       aggregate(Class<T> entityType, AggregateSpec spec, Function<RowAccessor, R> mapper);

    // CompiledQuery — SQL 한 번 렌더 후 binding만 교체 재실행
    <T>      Flux<T>       findAll(Class<T> entityType, CompiledQuery query, Object... bindings);
              Mono<Long>   execute(CompiledQuery query, Object... bindings);

    // Transaction
    <R>      Mono<R>       inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback);
}
```

`save`는 `EntityStateDetector`가 식별자 상태를 보고 **insert / update**를 자동으로 선택합니다.
`@Version` 엔티티의 update/delete는 affected rows == 0일 때 `OptimisticLockingFailureException`을 발행하며, `@SoftDelete` 엔티티의 delete는 자동으로 UPDATE로 변환됩니다. `@PrePersist`/`@PreUpdate`/`@PostLoad`/`@PreRemove` 콜백은 audit 적용 직후에 호출되어 사용자가 audit 기본값을 overrides할 수 있습니다.

---

## Query DSL

문자열 SQL을 직접 작성하지 않고, 타입 안전한 술어(predicate)와 정렬/페이징을 조합합니다.

```java
import static io.nova.query.Criteria.*;

QuerySpec spec = QuerySpec.empty()
    .where(and(
        eq("active", true),
        or(like("email", "%@example.com"), isNull("email"))
    ))
    .orderBy(Sort.by("id").descending())
    .page(Pageable.of(0, 20));

Flux<Account> accounts = operations.findAll(Account.class, spec);
Mono<Long>    total    = operations.count(Account.class, spec);
```

지원 연산자: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `like`, `in`, `notIn`, `between`,
`isNull`, `isNotNull`, 그리고 이들을 묶는 `and` / `or` / `not`.

빈 컬렉션 처리: `in([])` → `1=0` (항상 false), `notIn([])` → `1=1` (항상 true). Hibernate 6.3+/jOOQ 컨벤션을 따라 NPE 대신 의미가 명확한 SQL을 발행합니다.

### Updater DSL — entity 없이 partial UPDATE

```java
operations.update(Account.class, Updater.of(Account.class)
        .set("email", "x@nova.io")
        .set("active", true)
        .where(Criteria.where("id").gte(10L)));
```

`@UpdatedAt`이 metadata에 있으면 자동으로 SET 절에 포함됩니다.

### Projection — record / DTO 매핑

```java
record AccountEmail(Long id, String email) {}

Projection<Account, AccountEmail> projection = Projection.of(
        Account.class, AccountEmail.class, List.of("id", "email"));

Flux<AccountEmail> rows = operations.findAll(projection,
        QuerySpec.empty().where(Criteria.eq("active", true)));
```

record canonical constructor 또는 단일 명시적 constructor를 사용. soft delete alive guard 자동 적용.

### Aggregations DSL

```java
AggregateSpec spec = AggregateSpec.of(
        Aggregation.countDistinct("email").as("unique_emails"),
        Aggregation.sum("balance").as("total"))
    .groupBy("active")
    .having(Criteria.where("total").gt(0L));

Flux<AggregateRow> rows = operations.aggregate(Account.class, spec);
```

지원: `count`, `countDistinct`, `sum`, `avg`, `min`, `max`. `groupBy`, `having`, `orderBy` 조합. `AggregateRow.get(column, type)`은 driver raw type을 그대로 노출.

### Page / Slice — content + total 페이지네이션

```java
import io.nova.query.Page;
import io.nova.query.PageRequest;

Mono<Page<Account>> page = operations.findAll(
        Account.class,
        QuerySpec.empty().where(Criteria.eq("active", true)),
        PageRequest.of(2, 20).toPageable());          // page index 2, 20건/page

page.subscribe(p -> {
    p.content();        // List<Account>
    p.totalElements();  // long — 전체 행 수
    p.totalPages();     // int
    p.hasNext();        // boolean
});
```

`findAll(Class, QuerySpec, Pageable)`은 SELECT와 COUNT를 `Mono.zip`으로 함께 발행해 content와 `totalElements`를 한 번에 반환합니다. 전체 카운트가 필요 없으면 `findSlice(...)`가 `limit+1` 트릭으로 count 쿼리 없이 `hasNext`만 판정합니다.

`PageRequest`는 Spring Data 친화적인 page-number/size API를 제공하며 `next()`/`previous()`/`first()` chain과 `toPageable()` 변환을 노출합니다. 기존 limit/offset 의미가 자연스러운 곳에서는 `Pageable.of(limit, offset)`을 그대로 써도 됩니다.

### Cursor (keyset) pagination

```java
Cursor cursor = Cursor.of(CursorField.asc("id", lastId));
QuerySpec spec = QuerySpec.empty()
        .where(Criteria.eq("active", true))
        .orderBy(Sort.by(Sort.Order.asc("id")))
        .cursor(cursor, 20);
```

cursor 설정 시 OFFSET 무시, lexicographic keyset 조건이 WHERE에 자동 추가됩니다.

### NativeQuery — raw SQL

```java
NativeQuery query = NativeQuery.of("select count(*) from accounts where active = ?")
        .bind(true);

Mono<Long> total = operations.queryNativeOne(query,
        row -> row.get("count", Long.class));
```

### CompiledQuery — SQL 한 번 렌더, binding만 교체

```java
CompiledQuery compiled = dialect.sqlRenderer().compileSelect(metadata,
        QuerySpec.empty().where(Criteria.eq("email", "placeholder")));

// 동일 SQL을 다른 binding으로 반복 실행 — renderer 호출 없음
Flux<Account> a = operations.findAll(Account.class, compiled, "a@nova.io");
Flux<Account> b = operations.findAll(Account.class, compiled, "b@nova.io");
```
