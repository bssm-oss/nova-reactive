package io.nova.spring.data.derived;

import io.nova.query.Sort;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * method 이름을 {@link DerivedQuery}로 파싱한다. 클래스당 하나 만들어 reuse한다 — {@link EntityPropertyIndex}
 * 가 비싸지는 않지만 매번 다시 만들 이유도 없다.
 *
 * <p>파싱은 4단계로 진행된다:
 * <ol>
 *   <li>method 이름에서 subject prefix를 잘라낸다 ({@code find}, {@code findAll}, {@code findFirst},
 *       {@code findTop}, {@code findOne}, {@code count}, {@code exists}, {@code delete}, {@code remove}).
 *       이어지는 {@code By}를 필수로 요구한다 — derived 경로는 항상 predicate를 가진다.</li>
 *   <li>{@code OrderBy} 토큰을 기준으로 predicate part와 sort part로 분리한다.</li>
 *   <li>predicate part를 top-level {@code Or}로 1차 split, 각 conjunction을 {@code And}로 2차 split.</li>
 *   <li>각 conjunction 토큰에 대해 {@link EntityPropertyIndex}로 property를 greedy 매칭하고, 남은
 *       suffix를 {@link Keyword}로 매핑한다 — 잔여가 비어 있으면 default {@link Keyword#EQ}.</li>
 * </ol>
 *
 * <p>인식할 수 없는 이름은 {@link Optional#empty()}로 반환한다 — 호출자({@code SimpleReactiveRepository})는
 * 기존 fixed-name dispatch가 처리하지 못한 메서드에 한해 파서를 시도하므로, 두 단계가 모두 실패해야
 * {@code UnsupportedOperationException}이 떨어진다.
 *
 * <p>이름 자체는 파싱되지만 의미적으로 잘못된 경우(예: 등록되지 않은 property, parameter 개수 mismatch)는
 * {@link IllegalArgumentException}을 던진다 — silent하게 empty를 돌려주면 호출자가 fixed-name dispatch
 * 실패와 구분할 수 없어 디버깅이 어려워진다.
 */
public final class DerivedQueryParser {

    private static final String BY = "By";
    private static final String ORDER_BY = "OrderBy";
    private static final String AND = "And";
    private static final String OR = "Or";

    private static final Map<String, Subject> PREFIXES;

    static {
        // 긴 prefix를 먼저 시도해야 한다 — findFirstBy 가 findBy 로 잘못 잘리지 않도록.
        LinkedHashMap<String, Subject> map = new LinkedHashMap<>();
        map.put("findAllBy", Subject.FIND_ALL);
        map.put("findFirstBy", Subject.FIND_ONE);
        map.put("findTopBy", Subject.FIND_ONE);
        map.put("findOneBy", Subject.FIND_ONE);
        map.put("findBy", Subject.FIND_ALL);
        map.put("countBy", Subject.COUNT);
        map.put("existsBy", Subject.EXISTS);
        map.put("deleteBy", Subject.DELETE);
        map.put("removeBy", Subject.DELETE);
        PREFIXES = map;
    }

    private final EntityPropertyIndex propertyIndex;

    public DerivedQueryParser(Class<?> entityType) {
        this.propertyIndex = EntityPropertyIndex.of(entityType);
    }

    /**
     * 주어진 method를 파싱한다. method 이름이 어떤 known prefix와도 매칭되지 않으면
     * {@link Optional#empty()} — 호출자는 기존 dispatch 경로의 fall-through로 이해해야 한다.
     *
     * <p>{@code findBy<X>} 의 경우 메서드 반환 타입을 확인해 {@link reactor.core.publisher.Mono}이면
     * {@link Subject#FIND_ONE}으로 승격한다 — 사용자가 명시적으로 단일 행만 받겠다고 선언한 의미이므로
     * DB-side LIMIT 1을 적용한다. {@code Flux}는 {@link Subject#FIND_ALL} 그대로.
     */
    public Optional<DerivedQuery> tryParse(Method method) {
        String name = method.getName();
        SubjectMatch subjectMatch = matchSubject(name);
        if (subjectMatch == null) {
            return Optional.empty();
        }
        Subject subject = subjectMatch.subject();
        if (subject == Subject.FIND_ALL && reactor.core.publisher.Mono.class.isAssignableFrom(method.getReturnType())) {
            subject = Subject.FIND_ONE;
        }
        String remainder = name.substring(subjectMatch.prefix().length());
        // body는 "By" 이후의 모든 토큰 (predicate + optional OrderBy clause).
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "Derived query method '" + name + "' has no predicate after '" + subjectMatch.prefix() + "'");
        }

        String predicatePart;
        String orderPart;
        int orderByIdx = remainder.indexOf(ORDER_BY);
        if (orderByIdx < 0) {
            predicatePart = remainder;
            orderPart = "";
        } else {
            predicatePart = remainder.substring(0, orderByIdx);
            orderPart = remainder.substring(orderByIdx + ORDER_BY.length());
            if (orderPart.isEmpty()) {
                throw new IllegalArgumentException(
                        "Derived query method '" + name + "' has empty OrderBy clause");
            }
        }
        if (predicatePart.isEmpty()) {
            throw new IllegalArgumentException(
                    "Derived query method '" + name + "' has no predicate before OrderBy");
        }

        List<List<Part>> orGroups = parsePredicate(name, predicatePart);
        List<Ordering> orderings = parseOrderings(name, orderPart);

        int expectedArgs = 0;
        for (List<Part> group : orGroups) {
            for (Part part : group) {
                expectedArgs += part.keyword().parameterCount();
            }
        }
        if (expectedArgs != method.getParameterCount()) {
            throw new IllegalArgumentException(
                    "Derived query method '" + name + "' expects " + expectedArgs
                            + " parameter(s) for its predicate keywords but the method declares "
                            + method.getParameterCount());
        }

        return Optional.of(new DerivedQuery(subject, orGroups, orderings, expectedArgs));
    }

    private SubjectMatch matchSubject(String name) {
        for (Map.Entry<String, Subject> entry : PREFIXES.entrySet()) {
            if (name.startsWith(entry.getKey())) {
                return new SubjectMatch(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    private List<List<Part>> parsePredicate(String methodName, String predicatePart) {
        List<List<Part>> orGroups = new ArrayList<>();
        for (String orChunk : splitOnTopLevelToken(predicatePart, OR)) {
            List<Part> conjunction = new ArrayList<>();
            for (String andChunk : splitOnTopLevelToken(orChunk, AND)) {
                conjunction.add(parsePart(methodName, andChunk));
            }
            if (conjunction.isEmpty()) {
                throw new IllegalArgumentException(
                        "Derived query method '" + methodName + "' has an empty conjunction near '" + orChunk + "'");
            }
            orGroups.add(List.copyOf(conjunction));
        }
        if (orGroups.isEmpty()) {
            throw new IllegalArgumentException(
                    "Derived query method '" + methodName + "' parsed no predicate parts");
        }
        return List.copyOf(orGroups);
    }

    private Part parsePart(String methodName, String token) {
        if (token.isEmpty()) {
            throw new IllegalArgumentException(
                    "Derived query method '" + methodName + "' has an empty predicate part");
        }
        Optional<EntityPropertyIndex.Match> match = propertyIndex.longestPrefixMatch(token);
        if (match.isEmpty()) {
            throw new IllegalArgumentException(
                    "Derived query method '" + methodName + "' references unknown property in '"
                            + token + "'. Known properties: " + propertyIndex.propertyNames());
        }
        EntityPropertyIndex.Match m = match.get();
        Keyword keyword = matchKeyword(m.remainder(), methodName, token);
        return new Part(m.propertyName(), keyword);
    }

    private static Keyword matchKeyword(String remainder, String methodName, String token) {
        if (remainder.isEmpty()) {
            return Keyword.EQ;
        }
        for (Keyword keyword : Keyword.MATCHING_ORDER) {
            for (String suffix : keyword.suffixes()) {
                if (remainder.equals(suffix)) {
                    return keyword;
                }
            }
        }
        throw new IllegalArgumentException(
                "Derived query method '" + methodName + "' part '" + token
                        + "' has unrecognized keyword suffix '" + remainder + "'");
    }

    private List<Ordering> parseOrderings(String methodName, String orderPart) {
        if (orderPart.isEmpty()) {
            return List.of();
        }
        List<Ordering> orderings = new ArrayList<>();
        for (String chunk : splitOnTopLevelToken(orderPart, AND)) {
            if (chunk.isEmpty()) {
                throw new IllegalArgumentException(
                        "Derived query method '" + methodName + "' has an empty OrderBy item");
            }
            Optional<EntityPropertyIndex.Match> match = propertyIndex.longestPrefixMatch(chunk);
            if (match.isEmpty()) {
                throw new IllegalArgumentException(
                        "Derived query method '" + methodName + "' OrderBy references unknown property in '"
                                + chunk + "'. Known properties: " + propertyIndex.propertyNames());
            }
            EntityPropertyIndex.Match m = match.get();
            Sort.Direction direction = switch (m.remainder()) {
                case "", "Asc" -> Sort.Direction.ASC;
                case "Desc" -> Sort.Direction.DESC;
                default -> throw new IllegalArgumentException(
                        "Derived query method '" + methodName + "' OrderBy property '" + m.propertyName()
                                + "' has unrecognized direction suffix '" + m.remainder()
                                + "' — expected '', 'Asc', or 'Desc'");
            };
            orderings.add(new Ordering(m.propertyName(), direction));
        }
        return List.copyOf(orderings);
    }

    /**
     * PascalCase 문자열을 주어진 token 경계에서 분리한다. 토큰은 단어 경계로만 받아들이며(다음 문자가
     * 대문자이거나 문자열 끝), 즉 {@code Andrew}는 {@code And}로 split되지 않는다.
     */
    private static List<String> splitOnTopLevelToken(String input, String token) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i <= input.length() - token.length()) {
            if (input.regionMatches(i, token, 0, token.length())) {
                int next = i + token.length();
                // 단어 경계: 토큰 직후가 문자열 끝이거나 대문자(=새 토큰 시작)여야 한다.
                if (next == input.length() || Character.isUpperCase(input.charAt(next))) {
                    out.add(input.substring(start, i));
                    start = next;
                    i = next;
                    continue;
                }
            }
            i++;
        }
        out.add(input.substring(start));
        return out;
    }

    private record SubjectMatch(String prefix, Subject subject) {
    }
}
