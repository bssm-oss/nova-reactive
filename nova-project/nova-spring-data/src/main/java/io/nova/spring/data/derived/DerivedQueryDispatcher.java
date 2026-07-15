package io.nova.spring.data.derived;

import io.nova.core.ReactiveEntityOperations;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.Predicate;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 파싱된 {@link DerivedQuery}와 메서드 호출 인자를 받아 {@link ReactiveEntityOperations}로 실행한다.
 *
 * <p>호출자가 반환 타입을 직접 지정하기보다는 {@link Subject}가 자연스러운 publisher 타입을 강제한다.
 * {@code FIND_ALL}은 {@link Flux}, {@code FIND_ONE}은 {@link Mono}로 (LIMIT 1 적용),
 * {@code COUNT}/{@code EXISTS}/{@code DELETE}는 모두 {@link Mono} — Spring Data와 동일한 매핑이다.
 * 호출 메서드의 선언된 반환 타입과의 호환성 검사는 본 dispatcher 책임이 아니다(이미 컴파일러가
 * proxy interface 시그니처와 method body에 대해 검사한다).
 */
public final class DerivedQueryDispatcher {

    private final Class<?> entityType;
    private final ReactiveEntityOperations operations;

    public DerivedQueryDispatcher(Class<?> entityType, ReactiveEntityOperations operations) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object dispatch(DerivedQuery query, Object[] args) {
        Object[] safeArgs = args == null ? new Object[0] : args;
        Predicate predicate = buildPredicate(query, safeArgs);
        QuerySpec spec = predicate == null
                ? QuerySpec.empty()
                : QuerySpec.empty().where(predicate);
        if (!query.orderings().isEmpty()) {
            spec = spec.orderBy(buildSort(query.orderings()));
        }
        switch (query.subject()) {
            case FIND_ALL -> {
                if (query.hasPageable()) {
                    // findBy<X>(…, Pageable) — 반환 타입이 결정한 페이징 컨테이너로 감싼다.
                    Pageable pageable = requirePageable(query, safeArgs);
                    return switch (query.pagingResult()) {
                        // Flux<T>: LIMIT/OFFSET만 적용해 한 페이지 행을 스트리밍(총계/hasNext 없음).
                        case FLUX -> operations.findAll((Class) entityType, spec.page(pageable));
                        // Mono<Page<T>>: content + 별도 COUNT(*) 로 totalElements 계산.
                        case PAGE -> operations.findAll((Class) entityType, spec, pageable);
                        // Mono<Slice<T>>: limit+1 fetch 로 hasNext 판정(COUNT 없음).
                        case SLICE -> operations.findSlice((Class) entityType, spec, pageable);
                        case NONE -> throw new IllegalStateException(
                                "Derived query has a Pageable parameter but no paging result shape");
                    };
                }
                if (query.limit() != null) {
                    // findTop<N>By / findFirst<N>By (N >= 2) — DB-side LIMIT N, no OFFSET.
                    spec = spec.page(Pageable.of(query.limit(), 0L));
                }
                return operations.findAll((Class) entityType, spec);
            }
            case FIND_ONE -> {
                // Pageable.of(limit, offset) — Spring과 반대 순서다. LIMIT 1, OFFSET 0.
                QuerySpec singletonSpec = spec.page(Pageable.of(1, 0L));
                return operations.findAll((Class) entityType, singletonSpec).next();
            }
            case COUNT -> {
                return operations.count(entityType, spec);
            }
            case EXISTS -> {
                return operations.exists(entityType, spec);
            }
            case DELETE -> {
                return operations.deleteAll(entityType, spec);
            }
            default -> throw new IllegalStateException("Unknown derived subject: " + query.subject());
        }
    }

    private static Pageable requirePageable(DerivedQuery query, Object[] args) {
        Object raw = args[query.pageableArgIndex()];
        if (raw == null) {
            throw new IllegalArgumentException(
                    "Derived query paging requires a non-null io.nova.query.Pageable argument");
        }
        if (!(raw instanceof Pageable pageable)) {
            throw new IllegalArgumentException(
                    "Derived query paging expected an io.nova.query.Pageable argument but received "
                            + raw.getClass().getName());
        }
        return pageable;
    }

    private static Predicate buildPredicate(DerivedQuery query, Object[] args) {
        if (query.orGroups().isEmpty()) {
            return null;
        }
        ArgumentCursor cursor = new ArgumentCursor(args);
        List<Predicate> orBranches = new ArrayList<>(query.orGroups().size());
        for (List<Part> conjunction : query.orGroups()) {
            List<Predicate> andBranches = new ArrayList<>(conjunction.size());
            for (Part part : conjunction) {
                andBranches.add(toCondition(part, cursor));
            }
            orBranches.add(combineAnd(andBranches));
        }
        return combineOr(orBranches);
    }

    private static Predicate toCondition(Part part, ArgumentCursor cursor) {
        String property = part.propertyName();
        boolean ic = part.ignoreCase();
        return switch (part.keyword()) {
            // EQ/NOT/LIKE 대소문자-무시 버전은 core의 ILIKE Condition으로 렌더된다 — PostgreSQL은 native
            // ILIKE, 그 외 dialect는 lower(col) like lower(?) (Criteria.ilike/notIlike javadoc 참고).
            case EQ -> ic
                    ? Criteria.ilike(property, cursor.next(part, 0))
                    : Criteria.eq(property, cursor.next(part, 0));
            case NOT -> ic
                    ? Criteria.notIlike(property, cursor.next(part, 0))
                    : Criteria.ne(property, cursor.next(part, 0));
            case LT -> Criteria.lt(property, cursor.next(part, 0));
            case LTE -> Criteria.lte(property, cursor.next(part, 0));
            case GT -> Criteria.gt(property, cursor.next(part, 0));
            case GTE -> Criteria.gte(property, cursor.next(part, 0));
            case LIKE -> ic
                    ? Criteria.ilike(property, cursor.next(part, 0))
                    : Criteria.like(property, cursor.next(part, 0));
            case STARTING_WITH -> ic
                    ? Criteria.startsWithIgnoreCase(property, requireString(part, cursor.next(part, 0)))
                    : Criteria.startsWith(property, requireString(part, cursor.next(part, 0)));
            case ENDING_WITH -> ic
                    ? Criteria.endsWithIgnoreCase(property, requireString(part, cursor.next(part, 0)))
                    : Criteria.endsWith(property, requireString(part, cursor.next(part, 0)));
            case CONTAINING -> ic
                    ? Criteria.containsIgnoreCase(property, requireString(part, cursor.next(part, 0)))
                    : Criteria.contains(property, requireString(part, cursor.next(part, 0)));
            case IN -> Criteria.in(property, requireIterable(part, cursor.next(part, 0)));
            case NOT_IN -> Criteria.notIn(property, requireIterable(part, cursor.next(part, 0)));
            case BETWEEN -> Criteria.between(property, cursor.next(part, 0), cursor.next(part, 1));
            case IS_NULL -> Criteria.isNull(property);
            case IS_NOT_NULL -> Criteria.isNotNull(property);
            case IS_TRUE -> Criteria.eq(property, Boolean.TRUE);
            case IS_FALSE -> Criteria.eq(property, Boolean.FALSE);
        };
    }

    private static Predicate combineAnd(List<Predicate> predicates) {
        if (predicates.size() == 1) {
            return predicates.get(0);
        }
        return Criteria.and(predicates.toArray(new Predicate[0]));
    }

    private static Predicate combineOr(List<Predicate> predicates) {
        if (predicates.size() == 1) {
            return predicates.get(0);
        }
        return Criteria.or(predicates.toArray(new Predicate[0]));
    }

    private static Sort buildSort(List<Ordering> orderings) {
        Sort.Order[] orders = new Sort.Order[orderings.size()];
        for (int i = 0; i < orderings.size(); i++) {
            Ordering ordering = orderings.get(i);
            orders[i] = ordering.direction() == Sort.Direction.ASC
                    ? Sort.Order.asc(ordering.propertyName())
                    : Sort.Order.desc(ordering.propertyName());
        }
        return Sort.by(orders);
    }

    private static String requireString(Part part, Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    "Derived query keyword " + part.keyword() + " on property '" + part.propertyName()
                            + "' requires a String argument but received " + classOf(value));
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Object> requireIterable(Part part, Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException(
                    "Derived query keyword " + part.keyword() + " on property '" + part.propertyName()
                            + "' requires an Iterable argument but received " + classOf(value));
        }
        return (Iterable<Object>) iterable;
    }

    private static String classOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    /**
     * 호출 인자 배열을 순서대로 소비하는 cursor. {@link IllegalStateException}이 던져지는 경우는
     * parser가 expectedArgs와 method.getParameterCount()를 미리 검증하므로 실제로는 발생하지 않는다 —
     * defensive guard로 남겨 둔다.
     */
    private static final class ArgumentCursor {
        private final Object[] args;
        private int index;

        ArgumentCursor(Object[] args) {
            this.args = args;
        }

        Object next(Part part, int relative) {
            if (index >= args.length) {
                throw new IllegalStateException(
                        "Derived dispatcher ran out of arguments at part '" + part.propertyName()
                                + "' (keyword=" + part.keyword() + ", relative=" + relative + ")");
            }
            return args[index++];
        }
    }
}
