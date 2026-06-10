package io.nova.spring.data.derived;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * derived query part가 사용하는 비교 keyword. 각 값은 method-name suffix(여러 alias 가능) 한 집합과
 * 메서드 파라미터에서 소비할 인자 수를 가지고 있다.
 *
 * <p>{@code EQ}는 별도 suffix가 없으며, 매칭에 실패한 경우의 default keyword다 — 그래서 {@code suffixes()}는
 * 비어 있다. {@link Part}가 EQ로 만들어질 때는 잔여 토큰이 비어 있는 경우에 한한다.
 */
enum Keyword {
    EQ(1),
    NOT(1, "Not", "IsNot"),
    LT(1, "LessThan", "Lt", "Before"),
    LTE(1, "LessThanEqual", "Lte"),
    GT(1, "GreaterThan", "Gt", "After"),
    GTE(1, "GreaterThanEqual", "Gte"),
    LIKE(1, "Like"),
    STARTING_WITH(1, "StartingWith", "StartsWith"),
    ENDING_WITH(1, "EndingWith", "EndsWith"),
    CONTAINING(1, "Containing", "Contains"),
    IN(1, "In"),
    NOT_IN(1, "NotIn"),
    BETWEEN(2, "Between"),
    IS_NULL(0, "IsNull", "Null"),
    IS_NOT_NULL(0, "IsNotNull", "NotNull"),
    IS_TRUE(0, "True", "IsTrue"),
    IS_FALSE(0, "False", "IsFalse");

    /**
     * suffix 매칭 우선순위. 긴 suffix를 먼저 시도해 prefix 충돌을 피한다
     * (예: {@code GreaterThanEqual}이 {@code GreaterThan}보다 먼저 검사돼야 한다).
     */
    static final List<Keyword> MATCHING_ORDER = Arrays.stream(values())
            .filter(k -> !k.suffixes.isEmpty())
            .sorted(Comparator.comparingInt(Keyword::maxSuffixLength).reversed())
            .toList();

    private final int parameterCount;
    private final List<String> suffixes;

    Keyword(int parameterCount, String... suffixes) {
        this.parameterCount = parameterCount;
        this.suffixes = List.of(suffixes);
    }

    int parameterCount() {
        return parameterCount;
    }

    List<String> suffixes() {
        return suffixes;
    }

    private int maxSuffixLength() {
        return suffixes.stream().mapToInt(String::length).max().orElse(0);
    }
}
