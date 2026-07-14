<!-- SPDX-License-Identifier: Apache-2.0 -->

# Queries

`ReactiveEntityOperations` is Nova's main entry point. Beyond single-row CRUD, it exposes batch operations, partial update, projection, native SQL, aggregations, and compiled queries.

## CRUD operations

```java
public interface ReactiveEntityOperations {
    // Single-row
    <T>      Mono<T>       save(T entity);
    <T>      Mono<T>       update(T entity, Iterable<String> fields);          // partial update
    <T, ID>  Mono<T>       findById(Class<T> entityType, ID id);
    <T>      Mono<Long>    delete(T entity);
    <T, ID>  Mono<Long>    deleteById(Class<T> entityType, ID id);

    // Multi-row / batch
    <T>      Flux<T>       saveAll(Iterable<T> entities);                       // recovers generated ids in batch
    <T, ID>  Flux<T>       findAllById(Class<T> entityType, Iterable<ID> ids);   // single IN query
    <T>      Flux<T>       findAll(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Long>    deleteAll(Iterable<T> entities);
    <T, ID>  Mono<Long>    deleteAllById(Class<T> entityType, Iterable<ID> ids);
    <T>      Mono<Long>    deleteAll(Class<T> entityType, QuerySpec querySpec);  // criteria-based bulk delete

    // Aggregates / counts / existence
    <T>      Mono<Long>    count(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec);
    <T, ID>  Mono<Boolean> existsById(Class<T> entityType, ID id);     // default: findById(...).hasElement()

    // Pagination — returns content and total in one call
    <T>      Mono<Page<T>>  findAll(Class<T> entityType, QuerySpec querySpec, Pageable pageable);
    <T>      Mono<Slice<T>> findSlice(Class<T> entityType, QuerySpec querySpec, Pageable pageable);

    // Relationship mapping (@ManyToOne/@OneToMany) — explicit batch via FetchGroup, or annotation auto-apply
    <P>      Mono<P>        findById(Class<P> entityType, Object id, FetchGroup<P> fetchGroup);
    <P>      Flux<P>        findAll(Class<P> entityType, FetchGroup<P> fetchGroup);

    // Updater DSL — criteria-based partial UPDATE without an entity instance
    <T>      Mono<Long>    update(Class<T> entityType, Updater<T> updater);

    // Projection — map a subset of entity columns to a record / DTO
    <E, P>   Flux<P>       findAll(Projection<E, P> projection, QuerySpec querySpec);

    // Native SQL
              Mono<Long>   executeNative(NativeQuery query);
    <T>      Flux<T>       queryNative(NativeQuery query, Function<RowAccessor, T> mapper);
    <T>      Mono<T>       queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper);

    // Aggregations DSL
    <T>      Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec);
    <T, R>   Flux<R>       aggregate(Class<T> entityType, AggregateSpec spec, Function<RowAccessor, R> mapper);

    // CompiledQuery — render SQL once, swap bindings to re-execute
    <T>      Flux<T>       findAll(Class<T> entityType, CompiledQuery query, Object... bindings);
              Mono<Long>   execute(CompiledQuery query, Object... bindings);

    // Transaction
    <R>      Mono<R>       inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback);
}
```

`save` lets `EntityStateDetector` choose **insert vs update** by inspecting the identifier state.
For `@Version` entities, update/delete emits `OptimisticLockingFailureException` when affected rows are zero. For `@SoftDelete` entities, `delete` is rewritten as an UPDATE automatically. Lifecycle callbacks (`@PrePersist`/`@PreUpdate`/`@PostLoad`/`@PreRemove`) fire right after audit values are applied, so users may override audit defaults inside the callback.

---

## Query DSL

Compose type-safe predicates plus sort and paging without writing raw SQL strings.

```java
import static io.nova.query.Criteria.*;

QuerySpec spec = QuerySpec.empty()
    .where(and(
        eq("active", true),
        or(like("email", "%@example.com"), isNull("email"))
    ))
    .orderBy(Sort.by(Sort.Order.desc("id")))
    .page(Pageable.of(20, 0)); // Pageable.of(limit, offset): 20 rows from offset 0

Flux<Account> accounts = operations.findAll(Account.class, spec);
Mono<Long>    total    = operations.count(Account.class, spec);
```

Supported operators: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `like`, `in`, `notIn`, `between`, `isNull`, `isNotNull`, combined with `and` / `or` / `not`.

Empty-collection semantics: `in([])` renders as `1=0` (always false) and `notIn([])` renders as `1=1` (always true). Following the Hibernate 6.3+ / jOOQ convention, Nova emits SQL with clear semantics rather than throwing NPE.

### Updater DSL — partial UPDATE without an entity

```java
operations.update(Account.class, Updater.of(Account.class)
        .set("email", "x@nova.io")
        .set("active", true)
        .where(Criteria.gte("id", 10L)));
```

If `@UpdatedAt` exists in the metadata, it is added to the SET clause automatically.

### Projection — record / DTO mapping

```java
record AccountEmail(Long id, String email) {}

Projection<Account, AccountEmail> projection = Projection.of(
        Account.class, AccountEmail.class, List.of("id", "email"));

Flux<AccountEmail> rows = operations.findAll(projection,
        QuerySpec.empty().where(Criteria.eq("active", true)));
```

Uses the record canonical constructor, or a single explicit constructor. Soft-delete alive guard applies automatically.

### Aggregations DSL

```java
AggregateSpec spec = AggregateSpec.of(
        Aggregation.countDistinct("email").as("unique_emails"),
        Aggregation.sum("balance").as("total"))
    .groupBy("active")
    .having(Criteria.gt("total", 0L));

Flux<AggregateRow> rows = operations.aggregate(Account.class, spec);
```

Supported: `count`, `countDistinct`, `sum`, `avg`, `min`, `max`. Combines with `groupBy`, `having`, and `orderBy`. `AggregateRow.get(column, type)` returns the driver's raw type unchanged.

### Page / Slice — content + total pagination

```java
import io.nova.query.Page;
import io.nova.query.PageRequest;

Mono<Page<Account>> page = operations.findAll(
        Account.class,
        QuerySpec.empty().where(Criteria.eq("active", true)),
        PageRequest.of(2, 20).toPageable());          // page index 2, 20 rows per page

page.subscribe(p -> {
    p.content();        // List<Account>
    p.totalElements();  // long — full row count
    p.totalPages();     // int
    p.hasNext();        // boolean
});
```

`findAll(Class, QuerySpec, Pageable)` issues the SELECT and COUNT via `Mono.zip` so content and `totalElements` arrive together. When you do not need a total count, `findSlice(...)` uses the `limit+1` trick to decide `hasNext` without an extra COUNT query.

`PageRequest` exposes a Spring Data-friendly page-number / size API with chainable `next()` / `previous()` / `first()` and a `toPageable()` conversion. Where limit / offset semantics read more naturally, you can keep using `Pageable.of(limit, offset)` directly.

### Cursor (keyset) pagination

```java
Cursor cursor = Cursor.of(CursorField.asc("id", lastId));
QuerySpec spec = QuerySpec.empty()
        .where(Criteria.eq("active", true))
        .orderBy(Sort.by(Sort.Order.asc("id")))
        .cursor(cursor, 20);
```

When a cursor is set, OFFSET is ignored and a lexicographic keyset condition is appended to WHERE automatically.

### NativeQuery — raw SQL

```java
NativeQuery query = NativeQuery.of("select count(*) from accounts where active = ?")
        .bind(true);

Mono<Long> total = operations.queryNativeOne(query,
        row -> row.get("count", Long.class));
```

### CompiledQuery — render SQL once, swap bindings

```java
CompiledQuery compiled = dialect.sqlRenderer().compileSelect(metadata,
        QuerySpec.empty().where(Criteria.eq("email", "placeholder")));

// Reuse the same SQL with different bindings — no renderer call per execution
Flux<Account> a = operations.findAll(Account.class, compiled, "a@nova.io");
Flux<Account> b = operations.findAll(Account.class, compiled, "b@nova.io");
```
