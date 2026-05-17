package io.nova.query;

public record QuerySpec(Predicate predicate, Sort sort, Pageable pageable) {
    public static QuerySpec empty() {
        return new QuerySpec(null, null, null);
    }

    public QuerySpec where(Predicate next) {
        return new QuerySpec(next, sort, pageable);
    }

    public QuerySpec orderBy(Sort next) {
        return new QuerySpec(predicate, next, pageable);
    }

    public QuerySpec page(Pageable next) {
        return new QuerySpec(predicate, sort, next);
    }
}
