package io.nova.query;

import java.util.Objects;

/**
 * 단일 집계 컬럼 명세다. 사용할 {@link AggregateFunction}과 엔티티의 property name, 결과 컬럼의
 * alias로 구성된다. {@code alias}가 {@code null}이면 기본값으로 함수 이름의 소문자 표현
 * ({@code count}, {@code count_distinct}, {@code sum}, {@code avg}, {@code min}, {@code max})이
 * 사용된다.
 * <p>
 * 인스턴스는 immutable하며 {@link #as(String)}는 새 인스턴스를 반환한다.
 */
public record Aggregation(AggregateFunction function, String property, String alias) {
    public Aggregation {
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(property, "property must not be null");
        if (property.isBlank()) {
            throw new IllegalArgumentException("property must not be blank");
        }
        if (alias != null && alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
    }

    public static Aggregation count(String property) {
        return new Aggregation(AggregateFunction.COUNT, property, null);
    }

    public static Aggregation countDistinct(String property) {
        return new Aggregation(AggregateFunction.COUNT_DISTINCT, property, null);
    }

    public static Aggregation sum(String property) {
        return new Aggregation(AggregateFunction.SUM, property, null);
    }

    public static Aggregation avg(String property) {
        return new Aggregation(AggregateFunction.AVG, property, null);
    }

    public static Aggregation min(String property) {
        return new Aggregation(AggregateFunction.MIN, property, null);
    }

    public static Aggregation max(String property) {
        return new Aggregation(AggregateFunction.MAX, property, null);
    }

    /**
     * 이 집계의 결과 컬럼 alias만 바꾼 새 인스턴스를 반환한다.
     */
    public Aggregation as(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        return new Aggregation(function, property, alias);
    }

    /**
     * alias가 명시되지 않았다면 함수 이름 기반의 기본 alias를 반환한다.
     */
    public String resolvedAlias() {
        if (alias != null) {
            return alias;
        }
        return function.name().toLowerCase();
    }
}
