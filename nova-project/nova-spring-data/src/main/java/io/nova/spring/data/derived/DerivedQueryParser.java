package io.nova.spring.data.derived;

import io.nova.query.Sort;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * method 이름을 {@link DerivedQuery}로 파싱한다. 클래스당 하나 만들어 reuse한다 — {@link EntityPropertyIndex}
 * 가 비싸지는 않지만 매번 다시 만들 이유도 없다.
 *
 * <p>파싱은 4단계로 진행된다:
 * <ol>
 *   <li>method 이름에서 subject prefix를 잘라낸다 ({@code find}, {@code findAll}, {@code findFirst},
 *       {@code findFirst<N>}, {@code findTop}, {@code findTop<N>}, {@code findOne}, {@code count},
 *       {@code exists}, {@code delete}, {@code remove}). 이어지는 {@code By}를 필수로 요구한다 —
 *       derived 경로는 항상 predicate를 가진다. {@code findTop<N>By}/{@code findFirst<N>By}(N &gt;= 2)는
 *       {@link Subject#FIND_ALL}을 유지하며 dispatcher가 DB-side {@code LIMIT N}을 적용하도록
 *       {@link DerivedQuery#limit()}에 N을 싣는다 — N == 1은 기존 bare {@code findFirstBy}/
 *       {@code findTopBy}와 동일하게 {@link Subject#FIND_ONE}로 처리된다.</li>
 *   <li>{@code OrderBy} 토큰을 기준으로 predicate part와 sort part로 분리한다.</li>
 *   <li>predicate part를 top-level {@code Or}로 1차 split, 각 conjunction을 {@code And}로 2차 split.</li>
 *   <li>각 conjunction 토큰에 대해 {@link EntityPropertyIndex}로 property를 greedy 매칭한다. 남은
 *       suffix가 {@code IgnoreCase}로 끝나면 떼어내 {@link Part#ignoreCase()} 플래그로 기록하고, 나머지를
 *       {@link Keyword}로 매핑한다 — 잔여가 비어 있으면 default {@link Keyword#EQ}. {@code IgnoreCase}는
 *       문자열 비교 keyword({@code EQ}/{@code NOT}/{@code LIKE}/{@code StartingWith}/{@code EndingWith}/
 *       {@code Containing})에만 허용되며, 그 외 조합(예: {@code GreaterThanIgnoreCase})은
 *       {@link IllegalArgumentException}으로 즉시 거부한다.</li>
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
    private static final String IGNORE_CASE = "IgnoreCase";

    /**
     * {@code IgnoreCase} suffix를 받아들이는 keyword 집합. 문자열 비교와 무관한 keyword(예:
     * {@code GreaterThan}, {@code Between}, {@code IsNull})에 {@code IgnoreCase}를 붙이는 것은
     * 의미가 없으므로 파싱 시점에 명시적으로 거부한다.
     */
    private static final Set<Keyword> IGNORE_CASE_SUPPORTED = EnumSet.of(
            Keyword.EQ, Keyword.NOT, Keyword.LIKE,
            Keyword.STARTING_WITH, Keyword.ENDING_WITH, Keyword.CONTAINING);

    /**
     * {@code findTop<N>By} / {@code findFirst<N>By} 형태의 명시적 개수 prefix. N이 없는 {@code findTopBy}
     * / {@code findFirstBy}는 {@link #PREFIXES}의 literal entry가 그대로 처리한다(N=1과 동일 동작).
     */
    private static final Pattern FIND_TOP_FIRST_N = Pattern.compile("^find(?:Top|First)(\\d+)By");

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
        Integer limit = subjectMatch.limit();
        if (subject == Subject.FIND_ALL && reactor.core.publisher.Mono.class.isAssignableFrom(method.getReturnType())) {
            if (limit != null) {
                throw new IllegalArgumentException(
                        "Derived query method '" + name + "' requests top " + limit
                                + " result(s) but declares a Mono return type — Mono can only carry a single row;"
                                + " use findFirstBy/findTop1By or change the return type to Flux");
            }
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

        return Optional.of(new DerivedQuery(subject, orGroups, orderings, expectedArgs, limit));
    }

    /**
     * subject prefix를 찾는다. 명시적 개수를 갖는 {@code findTop<N>By}/{@code findFirst<N>By}부터 먼저
     * 시도하고(예: {@code findTop3By}), 매칭되지 않으면 기존 literal {@link #PREFIXES}로 fall back한다.
     *
     * <p>{@code N == 1}은 {@code findFirstBy}/{@code findTopBy}(literal)와 동일하게 {@link Subject#FIND_ONE}
     * (LIMIT 1, {@code Mono})로 취급한다. {@code N >= 2}는 {@link Subject#FIND_ALL}을 유지하되
     * {@link SubjectMatch#limit()}에 N을 실어 dispatcher가 LIMIT N을 적용하도록 한다. {@code N == 0}은
     * 의미가 없으므로 즉시 {@link IllegalArgumentException}으로 거부한다.
     */
    private SubjectMatch matchSubject(String name) {
        Matcher topFirstMatcher = FIND_TOP_FIRST_N.matcher(name);
        if (topFirstMatcher.lookingAt()) {
            String prefix = topFirstMatcher.group(0);
            int n = Integer.parseInt(topFirstMatcher.group(1));
            if (n == 0) {
                throw new IllegalArgumentException(
                        "Derived query method '" + name + "' requests top 0 results, which is meaningless");
            }
            return n == 1
                    ? new SubjectMatch(prefix, Subject.FIND_ONE, null)
                    : new SubjectMatch(prefix, Subject.FIND_ALL, n);
        }
        for (Map.Entry<String, Subject> entry : PREFIXES.entrySet()) {
            if (name.startsWith(entry.getKey())) {
                return new SubjectMatch(entry.getKey(), entry.getValue(), null);
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
        String remainder = m.remainder();
        boolean ignoreCase = false;
        if (remainder.endsWith(IGNORE_CASE)) {
            remainder = remainder.substring(0, remainder.length() - IGNORE_CASE.length());
            ignoreCase = true;
        }
        Keyword keyword = matchKeyword(remainder, methodName, token);
        if (ignoreCase && !IGNORE_CASE_SUPPORTED.contains(keyword)) {
            throw new IllegalArgumentException(
                    "Derived query method '" + methodName + "' part '" + token
                            + "' combines IgnoreCase with keyword " + keyword
                            + ", which is not comparable case-insensitively — supported keywords are "
                            + IGNORE_CASE_SUPPORTED);
        }
        return new Part(m.propertyName(), keyword, ignoreCase);
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

    private record SubjectMatch(String prefix, Subject subject, Integer limit) {
    }
}
