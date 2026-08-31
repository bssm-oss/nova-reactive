# Caching

`NovaCache.caching(...)` adds a second-level entity cache around `ReactiveEntityOperations`.

## Transactions

A managed `inTransaction` scope always sends entity-loading operations to the delegate, even when a warm entity-cache entry exists. This lets the physical persistence session register its identity and dirty snapshot. `existsById` also bypasses the entity cache in that scope.

When a managed scope loads a cacheable root, Nova eagerly evicts that entity entry and its query-cache partition. The invalidation is retained by the physical transaction and is applied again only after a successful physical commit. Participating nested transaction wrappers share that retained invalidation; they do not complete it at the nested boundary.

A rollback never puts loaded or uncommitted values into the shared cache. It can leave an entry evicted, so the next non-transactional lookup reads the committed database value.

Outside a managed transaction, `findById` remains read-through and can serve a warm cache entry without SQL.
