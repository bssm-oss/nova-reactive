package io.nova.query.jpql;

import io.nova.metadata.EntityMetadata;
import io.nova.query.Criteria;
import io.nova.query.Predicate;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.query.jpql.ast.Expression;
import io.nova.query.jpql.ast.JoinClause;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.query.jpql.ast.OrderItem;
import io.nova.query.jpql.ast.SelectItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 엔티티 자체를 반환하는 단순 SELECT(단일 루트, 조인/집계/GROUP BY 없음)를 Nova의 {@link QuerySpec}으로
 * 변환한다. 이렇게 만든 QuerySpec을 기존 {@code ReactiveEntityOperations.findAll(Class, QuerySpec)}에 위임하면
 * 엔티티 하이드레이션(연관 로딩·컨버터·세션 관리)을 전부 재사용한다.
 * <p>
 * QuerySpec/Criteria로 표현할 수 없는 조건(함수/산술/서브쿼리/CASE, escape 있는 LIKE 등)은 v1에서
 * 엔티티 반환 경로로 지원하지 않으며 {@link JpqlException}으로 fail-fast한다.
 */
public final class JpqlEntityQueryPlanner {

    private final JpqlEntityResolver resolver;

    public JpqlEntityQueryPlanner(JpqlEntityResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 엔티티 반환 SELECT 형태인지(단일 엔티티 항목 + group/having/집계 없음). 조인은 모두 {@code JOIN FETCH}일
     * 때만 허용한다 — fetch join은 투영을 바꾸지 않고 fetch plan만 더하므로 엔티티 경로로 라우팅하고, 지정된
     * 연관은 기존 배치 hydration으로 로드된다. 일반(non-fetch) 조인이 하나라도 있으면 엔티티 경로가 아니다.
     */
    public boolean isEntitySelect(JpqlStatement.Select select) {
        if (select.selectItems().size() != 1) {
            return false;
        }
        SelectItem only = select.selectItems().get(0);
        if (!only.isEntity()) {
            return false;
        }
        return allFetchJoins(select.joins())
                && select.groupBy().isEmpty()
                && select.having() == null
                && only.entityAlias().equals(select.rootAlias());
    }

    private static boolean allFetchJoins(List<JoinClause> joins) {
        for (JoinClause join : joins) {
            if (!join.fetch()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 엔티티 반환 SELECT의 {@code JOIN FETCH} 절들을 검증한다. fetch join의 owner는 루트 별칭이어야 하고
     * relation은 루트 엔티티의 알려진 연관 property여야 한다. 위반 시 fail-fast한다.
     * <p>
     * <b>v1 의미(always-eager passthrough):</b> Nova는 매핑 연관을 기본 eager 로드하므로, JOIN FETCH 절은
     * SQL 조인을 만들지 않고 <b>검증 역할과 명시적 의도 표현</b>에 그친다 — 실제 연관 로드는 루트 조회 후
     * 기존 배치 IN-query hydration({@code findAll(entityType, spec)})이 담당하며, 이는 JOIN FETCH 없이도
     * 동일하게 동작한다(cartesian 중복도 없음). EntityGraph와 동일한 always-eager 정합이다.
     */
    private void validateFetchJoins(JpqlStatement.Select select, EntityMetadata<?> rootMetadata) {
        for (JoinClause join : select.joins()) {
            if (!join.fetch()) {
                throw unsupported("a non-fetch JOIN in an entity-returning query");
            }
            if (!join.ownerAlias().equals(select.rootAlias())) {
                throw new JpqlException("JOIN FETCH off a non-root alias ('" + join.ownerAlias()
                        + "') is not supported in v1; fetch joins must originate from the root alias '"
                        + select.rootAlias() + "'");
            }
            io.nova.metadata.PersistentProperty property = rootMetadata.findProperty(join.relation())
                    .orElseThrow(() -> new JpqlException("JOIN FETCH " + join.ownerAlias() + "."
                            + join.relation() + " refers to an unknown field on entity "
                            + rootMetadata.entityType().getSimpleName()));
            if (!property.isRelation()) {
                throw new JpqlException("JOIN FETCH " + join.ownerAlias() + "." + join.relation()
                        + " is not an association on entity " + rootMetadata.entityType().getSimpleName());
            }
        }
    }

    public record EntityPlan(EntityMetadata<?> metadata, QuerySpec spec) {
    }

    /** WHERE/파라미터 해석 없이 루트 엔티티 메타데이터만 해석한다(결과 타입 판별용). */
    public EntityMetadata<?> rootMetadata(JpqlStatement.Select select) {
        return resolver.resolve(select.rootEntity());
    }

    public EntityPlan plan(JpqlStatement.Select select, JpqlParameters parameters) {
        EntityMetadata<?> metadata = resolver.resolve(select.rootEntity());
        String alias = select.rootAlias();
        validateFetchJoins(select, metadata);

        QuerySpec spec = QuerySpec.empty();
        if (select.where() != null) {
            spec = spec.where(translatePredicate(select.where(), alias, parameters));
        }
        if (!select.orderBy().isEmpty()) {
            spec = spec.orderBy(translateSort(select.orderBy(), alias));
        }
        return new EntityPlan(metadata, spec);
    }

    // ----------------------------------------------------------------------------------------
    // Predicate → Nova Criteria
    // ----------------------------------------------------------------------------------------

    private Predicate translatePredicate(
            io.nova.query.jpql.ast.Predicate predicate, String rootAlias, JpqlParameters params) {
        return switch (predicate) {
            case io.nova.query.jpql.ast.Predicate.And and -> Criteria.and(
                    translatePredicate(and.left(), rootAlias, params),
                    translatePredicate(and.right(), rootAlias, params));
            case io.nova.query.jpql.ast.Predicate.Or or -> Criteria.or(
                    translatePredicate(or.left(), rootAlias, params),
                    translatePredicate(or.right(), rootAlias, params));
            case io.nova.query.jpql.ast.Predicate.Not not ->
                    Criteria.not(translatePredicate(not.inner(), rootAlias, params));
            case io.nova.query.jpql.ast.Predicate.Comparison c -> comparison(c, rootAlias, params);
            case io.nova.query.jpql.ast.Predicate.Like like -> like(like, rootAlias, params);
            case io.nova.query.jpql.ast.Predicate.Between b -> Criteria.between(
                    field(b.value(), rootAlias),
                    value(b.low(), params),
                    value(b.high(), params));
            case io.nova.query.jpql.ast.Predicate.Null n -> n.negated()
                    ? Criteria.isNotNull(field(n.value(), rootAlias))
                    : Criteria.isNull(field(n.value(), rootAlias));
            case io.nova.query.jpql.ast.Predicate.InList in -> {
                String field = field(in.value(), rootAlias);
                List<Object> values = new ArrayList<>();
                for (Expression item : in.items()) {
                    values.add(value(item, params));
                }
                yield in.negated() ? Criteria.notIn(field, values) : Criteria.in(field, values);
            }
            case io.nova.query.jpql.ast.Predicate.InSubquery ignored ->
                    throw unsupported("IN (subquery)");
            case io.nova.query.jpql.ast.Predicate.Exists ignored ->
                    throw unsupported("EXISTS (subquery)");
        };
    }

    private Predicate comparison(
            io.nova.query.jpql.ast.Predicate.Comparison c, String rootAlias, JpqlParameters params) {
        String field = field(c.left(), rootAlias);
        Object value = value(c.right(), params);
        return switch (c.op()) {
            case EQ -> value == null ? Criteria.isNull(field) : Criteria.eq(field, value);
            case NE -> value == null ? Criteria.isNotNull(field) : Criteria.ne(field, value);
            case LT -> Criteria.lt(field, value);
            case GT -> Criteria.gt(field, value);
            case LE -> Criteria.lte(field, value);
            case GE -> Criteria.gte(field, value);
        };
    }

    private Predicate like(io.nova.query.jpql.ast.Predicate.Like like, String rootAlias, JpqlParameters params) {
        if (like.escape() != null) {
            throw unsupported("LIKE ... ESCAPE in an entity-returning query");
        }
        String field = field(like.value(), rootAlias);
        Object pattern = value(like.pattern(), params);
        return like.negated() ? Criteria.notLike(field, pattern) : Criteria.like(field, pattern);
    }

    private Sort translateSort(List<OrderItem> orderBy, String rootAlias) {
        List<Sort.Order> orders = new ArrayList<>();
        for (OrderItem item : orderBy) {
            String field = field(item.expression(), rootAlias);
            orders.add(new Sort.Order(field, item.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC));
        }
        return new Sort(orders);
    }

    // ----------------------------------------------------------------------------------------
    // Leaf resolution
    // ----------------------------------------------------------------------------------------

    /** {@code alias.field} 경로에서 Nova Criteria가 쓰는 property name(필드명)을 뽑는다. */
    private String field(Expression expression, String rootAlias) {
        if (!(expression instanceof Expression.Path path)) {
            throw unsupported("non-path operand (" + expression.getClass().getSimpleName()
                    + ") on the left side of a predicate");
        }
        if (!path.alias().equals(rootAlias)) {
            throw unsupported("path over alias '" + path.alias()
                    + "' (only the root alias is available in an entity-returning query)");
        }
        if (path.segments().size() != 1) {
            throw unsupported("multi-segment or bare-alias path '" + path.alias()
                    + (path.segments().isEmpty() ? "" : "." + String.join(".", path.segments())) + "'");
        }
        return path.segments().get(0);
    }

    private Object value(Expression expression, JpqlParameters params) {
        return switch (expression) {
            case Expression.Literal l -> l.value();
            case Expression.NamedParameter p -> params.resolveNamed(p.name());
            case Expression.PositionalParameter p -> params.resolvePositional(p.position());
            default -> throw unsupported("non-literal/parameter value expression ("
                    + expression.getClass().getSimpleName() + ")");
        };
    }

    private static JpqlException unsupported(String what) {
        return new JpqlException("Entity-returning JPQL queries do not support " + what
                + " in v1. Rewrite as a scalar/aggregate projection, or wait for the Criteria API (Wave2 W1).");
    }
}
