package io.nova.query.criteria;

import io.nova.query.Criteria;
import io.nova.query.Predicate;
import io.nova.query.Sort;

import java.util.ArrayList;
import java.util.List;

/**
 * 엔티티 반환 Criteria(단일 루트, 집계/GROUP BY 없음)를 Nova {@code QuerySpec}의 재료 —
 * {@link Predicate}와 {@link Sort} — 로 변환한다. Nova {@code Criteria}는 property 이름을 받고
 * SqlRenderer가 컬럼 매핑/컨버터/soft-delete를 적용하므로, 엔티티 하이드레이션 경로 전체를 재사용한다.
 * 스칼라 경로는 {@link CriteriaSqlBuilder}가 담당하며 이 변환기는 그와 파일을 공유하지 않는다.
 */
final class CriteriaEntityTranslator {

    private CriteriaEntityTranslator() {
    }

    static Predicate toPredicate(CriteriaPredicate predicate) {
        if (predicate instanceof DiscriminatorPredicate) {
            throw new CriteriaException("cb.equal(root.type(), ...) narrows the queried entity type and is "
                    + "handled before QuerySpec translation; it cannot appear as a QuerySpec predicate");
        }
        return switch (predicate.kind()) {
            case AND -> Criteria.and(childArray(predicate));
            case OR -> Criteria.or(childArray(predicate));
            case NOT -> Criteria.not(toPredicate(predicate.inner()));
            case COMPARISON -> comparison(predicate);
            case LIKE -> predicate.negated()
                    ? Criteria.notLike(name(predicate), predicate.value())
                    : Criteria.like(name(predicate), predicate.value());
            case BETWEEN -> Criteria.between(name(predicate), predicate.low(), predicate.high());
            case IN -> predicate.negated()
                    ? Criteria.notIn(name(predicate), predicate.inValues())
                    : Criteria.in(name(predicate), predicate.inValues());
            case NULL -> predicate.negated()
                    ? Criteria.isNotNull(name(predicate))
                    : Criteria.isNull(name(predicate));
            case EXISTS, IN_SUBQUERY, COMPARISON_SUBQUERY, COMPARISON_COLUMN -> throw new CriteriaException(
                    "Subquery/column-to-column predicates are not supported on the entity QuerySpec path; "
                            + "queries using them are executed via the aliased SQL path");
        };
    }

    static Sort toSort(List<CriteriaOrder> orders) {
        List<Sort.Order> result = new ArrayList<>(orders.size());
        for (CriteriaOrder order : orders) {
            String property = order.path().property().propertyName();
            result.add(new Sort.Order(property, order.isAscending() ? Sort.Direction.ASC : Sort.Direction.DESC));
        }
        return new Sort(result);
    }

    private static Predicate comparison(CriteriaPredicate predicate) {
        String field = name(predicate);
        Object value = predicate.value();
        return switch (predicate.op()) {
            case EQ -> Criteria.eq(field, value);
            case NE -> Criteria.ne(field, value);
            case GT -> Criteria.gt(field, value);
            case GE -> Criteria.gte(field, value);
            case LT -> Criteria.lt(field, value);
            case LE -> Criteria.lte(field, value);
        };
    }

    private static Predicate[] childArray(CriteriaPredicate predicate) {
        List<CriteriaPredicate> children = predicate.children();
        Predicate[] translated = new Predicate[children.size()];
        for (int i = 0; i < children.size(); i++) {
            translated[i] = toPredicate(children.get(i));
        }
        return translated;
    }

    private static String name(CriteriaPredicate predicate) {
        io.nova.metadata.PersistentProperty property = predicate.path().property();
        if (property.manyToOne() && property.isCompositeToOne()) {
            // 복합키 타겟 to-one의 다중컬럼 FK는 단일 property 이름으로 표현할 수 없다. 동등(=)/부등(<>)/
            // IS [NOT] NULL만 alias SQL 경로에서 컴포넌트로 전개돼 지원되며, LIKE/BETWEEN/IN 등 이 자리에
            // 도달하는 술어에는 전개할 alias 경로가 없다 — 조용한 오답 대신 정확한 미지원 사유로 fail-fast.
            throw new CriteriaException("Composite-key to-one association '" + property.propertyName()
                    + "' is not supported in a " + predicate.kind() + " predicate (its foreign key spans "
                    + "multiple columns); only equality (=), inequality (<>), and IS [NOT] NULL tests are supported");
        }
        return property.propertyName();
    }
}
