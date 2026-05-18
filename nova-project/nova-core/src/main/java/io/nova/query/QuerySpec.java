package io.nova.query;

public record QuerySpec(Predicate predicate, Sort sort, Pageable pageable, Cursor cursor) {
    public static QuerySpec empty() {
        return new QuerySpec(null, null, null, null);
    }

    public QuerySpec where(Predicate next) {
        return new QuerySpec(next, sort, pageable, cursor);
    }

    public QuerySpec orderBy(Sort next) {
        return new QuerySpec(predicate, next, pageable, cursor);
    }

    public QuerySpec page(Pageable next) {
        return new QuerySpec(predicate, sort, next, cursor);
    }

    /**
     * keyset pagination cursor와 page-size limit을 지정한다. cursor가 설정되면 SQL 렌더 시 OFFSET은
     * 무시되고, cursor 키 비교가 WHERE 절에 lexicographic으로 펼쳐진다. ORDER BY는 cursor field
     * 순서/방향과 일치해야 한다 — 명시적으로 정렬을 적용하려면 {@link #orderBy(Sort)}를 별도로 호출하라.
     */
    public QuerySpec cursor(Cursor next, int limit) {
        return new QuerySpec(predicate, sort, Pageable.of(limit, 0L), next);
    }
}
