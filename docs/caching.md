# Caching

`NovaCache.caching(...)` adds a second-level entity cache around `ReactiveEntityOperations`.

## Transactions

A managed `inTransaction` scope always sends entity-loading operations to the delegate, even when a warm entity-cache entry exists. This lets the physical persistence session register its identity and dirty snapshot. `existsById` also bypasses the entity cache in that scope.

Managed reads never mutate shared caches. A successful write in a physical transaction records one global entity/query invalidation and runs it only after physical commit. Participating nested wrappers share that retained invalidation; rollback, error, and cancellation leave warm entries intact. Direct and legacy transaction writes clear after successful delegate completion, and legacy scopes replay the clear after successful completion. Native and compiled writes use the same global rule.

Custom `ReactiveEntityOperations` implementations that execute internal or session-flush SQL must mark successful write completion through the active `PhysicalTransactionScope` or `TransactionWriteObservation`. Calls to explicit write methods through the cache decorator are observed automatically. Joined legacy wrappers reuse the outer observation and eviction buffer, so an inner success never clears before the outer boundary succeeds. These markers defer invalidation until a successful commit without evicting on read-only, rollback, error, or cancellation paths.

Rollback, error, and cancellation never put uncommitted values into the shared cache and do not evict warm entries solely because a physical transaction was opened.

Outside a managed transaction, `findById` remains read-through and can serve a warm cache entry without SQL.

## Detached snapshots and write invalidation

A cacheable `findById` result and every query-cache result are detached, mapping-aware snapshots. Every served hit receives a fresh object graph, so mutating a returned root, mapped PROPERTY association, or collection cannot alter a warm entry. Repeated references and cycles retain their identity within one returned graph only. Mapped accessors and record constructors are used when rebuilding values, including converted and record-backed collection values.

Nova eagerly hydrates associations, so a cached root may contain state from several entity types. Consequently every successful wrapped ORM write (`save`, `update`, every delete variant, and bulk write) clears all entity regions and the query cache, including writes to non-cacheable types. In a physical transaction the global clear is recorded and applied only after commit; this conservative tradeoff prevents associated graph snapshots from becoming stale without evicting on rollback, error, cancellation, or read-only work.
