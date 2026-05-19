package io.nova.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Criteria {
    private Criteria() {
    }

    public static Condition eq(String property, Object value) {
        return new Condition(property, ComparisonOperator.EQ, value);
    }

    public static Condition ne(String property, Object value) {
        return new Condition(property, ComparisonOperator.NE, value);
    }

    public static Condition gt(String property, Object value) {
        return new Condition(property, ComparisonOperator.GT, value);
    }

    public static Condition gte(String property, Object value) {
        return new Condition(property, ComparisonOperator.GTE, value);
    }

    public static Condition lt(String property, Object value) {
        return new Condition(property, ComparisonOperator.LT, value);
    }

    public static Condition lte(String property, Object value) {
        return new Condition(property, ComparisonOperator.LTE, value);
    }

    public static Condition like(String property, Object value) {
        return new Condition(property, ComparisonOperator.LIKE, value);
    }

    /**
     * 주어진 패턴과 일치하지 않는 행을 매칭하는 {@code NOT LIKE} 조건을 만든다.
     * {@code %}와 {@code _} 와일드카드의 escape는 호출자의 책임이다.
     */
    public static Condition notLike(String property, Object value) {
        return new Condition(property, ComparisonOperator.NOT_LIKE, value);
    }

    /**
     * 대소문자를 무시하고 패턴과 일치하는 행을 매칭하는 {@code ILIKE} 조건을 만든다.
     * PostgreSQL은 native {@code ILIKE}로, 그 외 dialect는 {@code lower(col) like lower(?)} 형태로 렌더된다.
     * {@code %}와 {@code _} 와일드카드의 escape는 호출자의 책임이다.
     */
    public static Condition ilike(String property, Object value) {
        return new Condition(property, ComparisonOperator.ILIKE, value);
    }

    /**
     * {@link #ilike(String, Object)}의 부정형이다.
     */
    public static Condition notIlike(String property, Object value) {
        return new Condition(property, ComparisonOperator.NOT_ILIKE, value);
    }

    /**
     * 주어진 prefix로 시작하는 행을 매칭하는 {@code LIKE 'prefix%'} 조건을 만든다.
     * <p>주의: {@code value}의 {@code %}, {@code _}, {@code \\} (dialect별 LIKE escape char) 는
     * 자동 escape되지 않는다 — 호출자가 직접 escape하거나 신뢰된 입력만 전달해야 한다.
     */
    public static Condition startsWith(String property, String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        return new Condition(property, ComparisonOperator.LIKE, prefix + "%");
    }

    /**
     * 주어진 suffix로 끝나는 행을 매칭하는 {@code LIKE '%suffix'} 조건을 만든다.
     * <p>주의: {@code value}의 {@code %}, {@code _}, {@code \\} (dialect별 LIKE escape char) 는
     * 자동 escape되지 않는다 — 호출자가 직접 escape하거나 신뢰된 입력만 전달해야 한다.
     */
    public static Condition endsWith(String property, String suffix) {
        Objects.requireNonNull(suffix, "suffix must not be null");
        return new Condition(property, ComparisonOperator.LIKE, "%" + suffix);
    }

    /**
     * 주어진 substring을 포함하는 행을 매칭하는 {@code LIKE '%substring%'} 조건을 만든다.
     * <p>주의: {@code value}의 {@code %}, {@code _}, {@code \\} (dialect별 LIKE escape char) 는
     * 자동 escape되지 않는다 — 호출자가 직접 escape하거나 신뢰된 입력만 전달해야 한다.
     */
    public static Condition contains(String property, String substring) {
        Objects.requireNonNull(substring, "substring must not be null");
        return new Condition(property, ComparisonOperator.LIKE, "%" + substring + "%");
    }

    /**
     * {@link #startsWith(String, String)}의 대소문자 무시 버전이다. ILIKE로 렌더된다.
     * <p>주의: {@code value}의 {@code %}, {@code _}, {@code \\} (dialect별 LIKE escape char) 는
     * 자동 escape되지 않는다 — 호출자가 직접 escape하거나 신뢰된 입력만 전달해야 한다.
     */
    public static Condition startsWithIgnoreCase(String property, String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        return new Condition(property, ComparisonOperator.ILIKE, prefix + "%");
    }

    /**
     * {@link #endsWith(String, String)}의 대소문자 무시 버전이다. ILIKE로 렌더된다.
     * <p>주의: {@code value}의 {@code %}, {@code _}, {@code \\} (dialect별 LIKE escape char) 는
     * 자동 escape되지 않는다 — 호출자가 직접 escape하거나 신뢰된 입력만 전달해야 한다.
     */
    public static Condition endsWithIgnoreCase(String property, String suffix) {
        Objects.requireNonNull(suffix, "suffix must not be null");
        return new Condition(property, ComparisonOperator.ILIKE, "%" + suffix);
    }

    /**
     * {@link #contains(String, String)}의 대소문자 무시 버전이다. ILIKE로 렌더된다.
     * <p>주의: {@code value}의 {@code %}, {@code _}, {@code \\} (dialect별 LIKE escape char) 는
     * 자동 escape되지 않는다 — 호출자가 직접 escape하거나 신뢰된 입력만 전달해야 한다.
     */
    public static Condition containsIgnoreCase(String property, String substring) {
        Objects.requireNonNull(substring, "substring must not be null");
        return new Condition(property, ComparisonOperator.ILIKE, "%" + substring + "%");
    }

    public static Condition isNull(String property) {
        return new Condition(property, ComparisonOperator.IS_NULL, null);
    }

    public static Condition isNotNull(String property) {
        return new Condition(property, ComparisonOperator.IS_NOT_NULL, null);
    }

    /**
     * 컬렉션의 모든 원소 중 하나와 일치하는 행을 매칭하는 IN 조건을 만든다.
     * <p>
     * 빈 컬렉션은 허용되며, 렌더 시점에 {@code 1 = 0}(항상 거짓)으로 치환된다 — 동적 쿼리에서
     * 비어 있는 입력으로 자연스럽게 흘러들어오는 경우를 위해 fail-fast가 아닌 안전한 동작을 택했다.
     * 원소 자체가 {@code null}인 경우는 SQL의 {@code IN (..., NULL, ...)} 의미가 항상 unknown이므로
     * 빌드 시점에 거부한다.
     */
    public static Condition in(String property, Iterable<?> values) {
        Objects.requireNonNull(values, "values must not be null");
        List<Object> copy = new ArrayList<>();
        int index = 0;
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Criteria.in value at index " + index + " for property " + property + " is null");
            }
            copy.add(value);
            index++;
        }
        return new Condition(property, ComparisonOperator.IN, copy);
    }

    /**
     * 컬렉션의 모든 원소와 일치하지 않는 행을 매칭하는 NOT IN 조건을 만든다.
     * <p>
     * 빈 컬렉션은 허용되며, 렌더 시점에 {@code 1 = 1}(항상 참)로 치환된다 — Hibernate 6.3+/jOOQ와
     * 동일한 동작이다. 원소 {@code null}은 SQL {@code NOT IN (..., NULL, ...)}이 항상 unknown으로
     * 0행을 반환하는 silent buggy 동작을 피하기 위해 빌드 시점에 거부한다.
     */
    public static Condition notIn(String property, Iterable<?> values) {
        Objects.requireNonNull(values, "values must not be null");
        List<Object> copy = new ArrayList<>();
        int index = 0;
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Criteria.notIn value at index " + index + " for property " + property + " is null");
            }
            copy.add(value);
            index++;
        }
        return new Condition(property, ComparisonOperator.NOT_IN, copy);
    }

    /**
     * 양 끝값 inclusive인 BETWEEN 조건을 만든다. {@code low}와 {@code high}는 모두 non-null이어야 한다.
     * {@code low > high}는 SQL 의미상 자연스럽게 0행 매치이므로 빌드 시점에 별도로 검증하지 않는다.
     */
    public static Condition between(String property, Object low, Object high) {
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(high, "high must not be null");
        return new Condition(property, ComparisonOperator.BETWEEN, List.of(low, high));
    }

    public static CompoundPredicate and(Predicate... predicates) {
        return new CompoundPredicate(LogicalOperator.AND, List.of(predicates));
    }

    public static CompoundPredicate or(Predicate... predicates) {
        return new CompoundPredicate(LogicalOperator.OR, List.of(predicates));
    }

    /**
     * 주어진 predicate를 부정한다. SQL상 {@code not (...)}으로 렌더된다.
     */
    public static NegationPredicate not(Predicate inner) {
        return new NegationPredicate(inner);
    }
}
