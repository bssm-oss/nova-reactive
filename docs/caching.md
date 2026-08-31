# Caching

`NovaCache.caching(...)` adds a second-level entity cache around `ReactiveEntityOperations`.

## Transactions

A managed `inTransaction` scope always sends entity-loading operations to the delegate, even when a warm entity-cache entry exists. This lets the physical persistence session register its identity and dirty snapshot. `existsById` also bypasses the entity cache in that scope.

When a managed scope loads any entity root (including fetch-group, graph, paged, and compiled entity-read overloads), Nova eagerly clears every entity-cache region and the query cache. The clear is retained by the physical transaction and is applied again only after a successful physical commit. Participating nested transaction wrappers share that retained invalidation; they do not complete it at the nested boundary. Native and compiled writes use the same retained global clear.

A rollback never puts loaded or uncommitted values into the shared cache. It can leave an entry evicted, so the next non-transactional lookup reads the committed database value.

Outside a managed transaction, `findById` remains read-through and can serve a warm cache entry without SQL.
