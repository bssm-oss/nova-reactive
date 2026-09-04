# Caching

`NovaCache.caching(...)` adds a second-level entity cache around `ReactiveEntityOperations`.

## Transactions

A managed `inTransaction` scope always sends entity-loading operations to the delegate, even when a warm entity-cache entry exists. This lets the physical persistence session register its identity and dirty snapshot. `existsById` also bypasses the entity cache in that scope.

When a managed scope loads any entity root (including fetch-group, graph, paged, and compiled entity-read overloads), Nova eagerly clears every entity-cache region and the query cache. The clear is retained by the physical transaction and is applied again only after a successful physical commit. Participating nested transaction wrappers share that retained invalidation; they do not complete it at the nested boundary. Native and compiled writes use the same retained global clear.

A rollback never puts loaded or uncommitted values into the shared cache. It can leave an entry evicted, so the next non-transactional lookup reads the committed database value.

Outside a managed transaction, `findById` remains read-through and can serve a warm cache entry without SQL.

## Detached snapshots and write invalidation

A cacheable `findById` result and every query-cache result are detached, mapping-aware snapshots. Every served hit receives a fresh object graph, so mutating a returned root, mapped PROPERTY association, or collection cannot alter a warm entry. Repeated references and cycles retain their identity within one returned graph only. Mapped accessors and record constructors are used when rebuilding values, including converted and record-backed collection values.

Nova eagerly hydrates associations, so a cached root may contain state from several entity types. Consequently every successful wrapped ORM write (`save`, `update`, every delete variant, and bulk write) clears all entity regions and the query cache, including writes to non-cacheable types. In a physical transaction that global clear is applied immediately and replayed after commit; this conservative tradeoff prevents associated graph snapshots from becoming stale.
