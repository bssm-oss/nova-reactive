package io.nova.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregations DSL의 immutable builder다. {@code SELECT count(distinct col), sum(col2)
 * FROM ... WHERE ... GROUP BY ... HAVING ... ORDER BY ...} 형태의 집계 쿼리 명세를 만든다.
 * <p>
 * 모든 builder 메서드는 새 인스턴스를 반환한다. {@code groupBy}로 명시된 property는 SQL 표준에
 * 따라 {@code SELECT} 절에도 그대로 포함되므로 결과 row에서 함께 읽을 수 있다.
 * <p>
 * {@code where}는 행 필터로 평가되고, {@code having}은 그룹 집계 결과 필터로 평가된다.
 * {@code orderBy}는 결과 정렬에 사용한다 — {@code pageable}은 의도적으로 지원하지 않는다.
 */
public final class AggregateSpec {
    private final List<Aggregation> aggregations;
    private final List<String> groupBy;
    private final Predicate where;
    private final Predicate having;
    private final Sort sort;

    private AggregateSpec(
            List<Aggregation> aggregations,
            List<String> groupBy,
            Predicate where,
            Predicate having,
            Sort sort
    ) {
        this.aggregations = aggregations;
        this.groupBy = groupBy;
        this.where = where;
        this.having = having;
        this.sort = sort;
    }

    /**
     * 하나 이상의 {@link Aggregation}으로 새 {@code AggregateSpec}을 시작한다.
     */
    public static AggregateSpec of(Aggregation first, Aggregation... rest) {
        Objects.requireNonNull(first, "first aggregation must not be null");
        List<Aggregation> all = new ArrayList<>();
        all.add(first);
        if (rest != null) {
            for (int i = 0; i < rest.length; i++) {
                Aggregation aggregation = rest[i];
                if (aggregation == null) {
                    throw new IllegalArgumentException("aggregation at index " + (i + 1) + " is null");
                }
                all.add(aggregation);
            }
        }
        return new AggregateSpec(List.copyOf(all), List.of(), null, null, null);
    }

    /**
     * {@code GROUP BY}에 사용할 property들을 입력 순서대로 지정한 새 인스턴스를 반환한다.
     * 빈 배열은 그룹 없음(전체 집계)을 의미하며, {@code null} 원소는 거부한다.
     */
    public AggregateSpec groupBy(String... properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        List<String> next = new ArrayList<>(properties.length);
        for (int i = 0; i < properties.length; i++) {
            String property = properties[i];
            if (property == null) {
                throw new IllegalArgumentException("groupBy property at index " + i + " is null");
            }
            if (property.isBlank()) {
                throw new IllegalArgumentException("groupBy property at index " + i + " is blank");
            }
            next.add(property);
        }
        return new AggregateSpec(aggregations, List.copyOf(next), where, having, sort);
    }

    /**
     * {@code WHERE} 절 predicate을 지정한 새 인스턴스를 반환한다. {@code null}은 거부한다.
     */
    public AggregateSpec where(Predicate predicate) {
        Objects.requireNonNull(predicate, "where predicate must not be null");
        return new AggregateSpec(aggregations, groupBy, predicate, having, sort);
    }

    /**
     * {@code HAVING} 절 predicate을 지정한 새 인스턴스를 반환한다. {@code null}은 거부한다.
     */
    public AggregateSpec having(Predicate predicate) {
        Objects.requireNonNull(predicate, "having predicate must not be null");
        return new AggregateSpec(aggregations, groupBy, where, predicate, sort);
    }

    /**
     * {@code ORDER BY} 절을 지정한 새 인스턴스를 반환한다. {@code null}은 거부한다.
     */
    public AggregateSpec orderBy(Sort sort) {
        Objects.requireNonNull(sort, "sort must not be null");
        return new AggregateSpec(aggregations, groupBy, where, having, sort);
    }

    public List<Aggregation> aggregations() {
        return aggregations;
    }

    public List<String> groupBy() {
        return groupBy;
    }

    public Predicate where() {
        return where;
    }

    public Predicate having() {
        return having;
    }

    public Sort sort() {
        return sort;
    }
}
