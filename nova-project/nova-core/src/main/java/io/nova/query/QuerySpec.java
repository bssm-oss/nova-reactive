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
     * <p>
     * <b>주의</b>: 이 wither는 기존 {@link Pageable}을 {@code Pageable.of(limit, 0L)}로 통째로
     * 덮어쓴다. 기존에 설정한 offset은 무시되며 limit만 인자 값으로 갱신된다. 기존 pageable의
     * limit을 보존하면서 cursor만 추가하려면 {@link #cursor(Cursor)} overload를 사용하라.
     */
    public QuerySpec cursor(Cursor next, int limit) {
        return new QuerySpec(predicate, sort, Pageable.of(limit, 0L), next);
    }

    /**
     * keyset pagination cursor만 추가하고 기존 {@link Pageable}의 limit은 보존한다.
     * cursor가 설정되면 SQL 렌더 시 OFFSET은 무시된다 ({@link #cursor(Cursor, int)} 참고).
     * <p>
     * 기존 {@code pageable}이 {@code null}이면 {@link IllegalStateException}을 던진다 —
     * keyset 페이지네이션도 한 페이지당 행 수 제한이 필요하므로, limit 정보 없이 cursor만 설정하는
     * 호출은 거부된다. 명시적인 limit을 함께 지정하려면 {@link #cursor(Cursor, int)}를 사용하라.
     */
    public QuerySpec cursor(Cursor next) {
        if (pageable == null) {
            throw new IllegalStateException(
                    "cursor(Cursor) requires an existing pageable to preserve its limit; "
                            + "call page(Pageable) first or use cursor(Cursor, int)");
        }
        return new QuerySpec(predicate, sort, Pageable.of(pageable.limit(), 0L), next);
    }
}
