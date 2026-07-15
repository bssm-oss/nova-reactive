package io.nova.query.jpql;

import io.nova.metadata.EntityMetadata;
import io.nova.query.Criteria;
import io.nova.query.Predicate;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.query.jpql.ast.ComparisonOp;
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
     * 엔티티 자체를 반환하지만 필터 목적의 non-fetch JOIN이 있는 SELECT 형태인지. 이런 쿼리는 Nova의
     * {@link QuerySpec}(조인 미지원)으로 직접 표현할 수 없으므로, 실행 계층이 조인/WHERE를 스칼라 id 투영으로
     * 먼저 뽑고 그 id 집합을 IN 조건으로 하이드레이션하는 2단계 경로로 처리한다. group/having/집계는 제외한다.
     */
    public boolean isJoinedEntitySelect(JpqlStatement.Select select) {
        if (select.selectItems().size() != 1) {
            return false;
        }
        SelectItem only = select.selectItems().get(0);
        if (!only.isEntity() || !only.entityAlias().equals(select.rootAlias())) {
            return false;
        }
        return !select.joins().isEmpty()
                && !allFetchJoins(select.joins())
                && select.groupBy().isEmpty()
                && select.having() == null;
    }

    /**
     * 엔티티 반환 ORDER BY를 루트 필드 한정 {@link Sort}로 변환한다. 조인 별칭/다세그먼트 경로 정렬은 IN
     * 하이드레이션 후 재현할 수 없으므로 fail-fast한다. 비어 있으면 {@code null}.
     */
    public Sort translateRootOrderBy(List<OrderItem> orderBy, String rootAlias, EntityMetadata<?> rootMeta) {
        if (orderBy.isEmpty()) {
            return null;
        }
        return translateSort(orderBy, rootAlias, rootMeta);
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

        // TYPE(e) = Subtype 제한은 QuerySpec 술어가 아니라 조회 대상 엔티티 타입을 서브타입으로 좁혀서
        // 처리한다 — 코어 findAll(Subtype)이 이미 discriminator 제한을 적용하므로 하이드레이션을 그대로 재사용한다.
        TypeNarrowing narrowing = narrowByType(select.where(), alias, metadata);

        QuerySpec spec = QuerySpec.empty();
        if (narrowing.remaining() != null) {
            spec = spec.where(translatePredicate(narrowing.remaining(), alias, metadata, parameters));
        }
        if (!select.orderBy().isEmpty()) {
            spec = spec.orderBy(translateSort(select.orderBy(), alias, metadata));
        }
        return new EntityPlan(narrowing.target(), spec);
    }

    // ----------------------------------------------------------------------------------------
    // TYPE(e) polymorphic narrowing (entity-returning path)
    // ----------------------------------------------------------------------------------------

    /** 조회 대상 서브타입과 TYPE 제한을 제외한 나머지 WHERE 술어. */
    private record TypeNarrowing(EntityMetadata<?> target, io.nova.query.jpql.ast.Predicate remaining) {
    }

    /**
     * 최상위 AND 체인에서 {@code TYPE(root) = Subtype} 제한을 추출해 조회 대상 타입을 좁히고, 나머지 술어를
     * 반환한다. TYPE/TREAT가 좁힘 불가 위치(OR/NOT/부등식/IN/부분식)에 있으면 fail-fast하며 스칼라 경로로
     * 재작성하도록 안내한다.
     */
    private TypeNarrowing narrowByType(
            io.nova.query.jpql.ast.Predicate where, String rootAlias, EntityMetadata<?> baseMeta) {
        List<io.nova.query.jpql.ast.Predicate> remaining = new ArrayList<>();
        EntityMetadata<?>[] target = {baseMeta};
        boolean[] narrowed = {false};
        flattenTypeNarrowing(where, rootAlias, baseMeta, remaining, target, narrowed);
        return new TypeNarrowing(target[0], combineAnd(remaining));
    }

    private void flattenTypeNarrowing(
            io.nova.query.jpql.ast.Predicate predicate, String rootAlias, EntityMetadata<?> baseMeta,
            List<io.nova.query.jpql.ast.Predicate> remaining, EntityMetadata<?>[] target, boolean[] narrowed) {
        if (predicate == null) {
            return;
        }
        if (predicate instanceof io.nova.query.jpql.ast.Predicate.And and) {
            flattenTypeNarrowing(and.left(), rootAlias, baseMeta, remaining, target, narrowed);
            flattenTypeNarrowing(and.right(), rootAlias, baseMeta, remaining, target, narrowed);
            return;
        }
        if (predicate instanceof io.nova.query.jpql.ast.Predicate.Comparison c
                && c.op() == ComparisonOp.EQ
                && c.left() instanceof Expression.Type type
                && c.right() instanceof Expression.EntityTypeLiteral lit) {
            if (!type.alias().equals(rootAlias)) {
                throw unsupported("TYPE(...) over a non-root alias '" + type.alias() + "'");
            }
            if (narrowed[0]) {
                throw new JpqlException("Multiple TYPE(...) = Subtype restrictions in an entity-returning query "
                        + "are not supported; a row has exactly one concrete type");
            }
            target[0] = resolveEntitySubtype(baseMeta, lit.entityName());
            narrowed[0] = true;
            return;
        }
        if (mentionsPolymorphic(predicate)) {
            throw unsupported("TYPE(...)/TREAT(...) in this position of an entity-returning query; "
                    + "use 'WHERE TYPE(e) = Subtype', or project scalar columns");
        }
        remaining.add(predicate);
    }

    private EntityMetadata<?> resolveEntitySubtype(EntityMetadata<?> baseMeta, String subtypeName) {
        if (!baseMeta.hasInheritance()) {
            throw new JpqlException("TYPE(...) requires an @Inheritance entity; '"
                    + baseMeta.entityType().getSimpleName() + "' is not polymorphic");
        }
        if (!baseMeta.inheritance().singleTable()) {
            throw new JpqlException("TYPE(...) narrowing is only supported for SINGLE_TABLE inheritance in v1; '"
                    + baseMeta.entityType().getSimpleName() + "' uses " + baseMeta.inheritance().strategy());
        }
        EntityMetadata<?> subMeta = resolver.resolve(subtypeName);
        if (!subMeta.inheritance().present() || !subMeta.inheritance().sameHierarchy(baseMeta.inheritance())) {
            throw new JpqlException("TYPE(...) target '" + subtypeName + "' is not a subtype in the same "
                    + "inheritance hierarchy as '" + baseMeta.entityType().getSimpleName() + "'");
        }
        return subMeta;
    }

    private static io.nova.query.jpql.ast.Predicate combineAnd(List<io.nova.query.jpql.ast.Predicate> parts) {
        if (parts.isEmpty()) {
            return null;
        }
        io.nova.query.jpql.ast.Predicate combined = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            combined = new io.nova.query.jpql.ast.Predicate.And(combined, parts.get(i));
        }
        return combined;
    }

    private static boolean mentionsPolymorphic(io.nova.query.jpql.ast.Predicate predicate) {
        return switch (predicate) {
            case io.nova.query.jpql.ast.Predicate.And and ->
                    mentionsPolymorphic(and.left()) || mentionsPolymorphic(and.right());
            case io.nova.query.jpql.ast.Predicate.Or or ->
                    mentionsPolymorphic(or.left()) || mentionsPolymorphic(or.right());
            case io.nova.query.jpql.ast.Predicate.Not not -> mentionsPolymorphic(not.inner());
            case io.nova.query.jpql.ast.Predicate.Comparison c ->
                    isPolymorphic(c.left()) || isPolymorphic(c.right());
            case io.nova.query.jpql.ast.Predicate.Like like ->
                    isPolymorphic(like.value()) || isPolymorphic(like.pattern());
            case io.nova.query.jpql.ast.Predicate.Between b ->
                    isPolymorphic(b.value()) || isPolymorphic(b.low()) || isPolymorphic(b.high());
            case io.nova.query.jpql.ast.Predicate.Null n -> isPolymorphic(n.value());
            case io.nova.query.jpql.ast.Predicate.InList in ->
                    isPolymorphic(in.value()) || in.items().stream().anyMatch(JpqlEntityQueryPlanner::isPolymorphic);
            case io.nova.query.jpql.ast.Predicate.InSubquery in -> isPolymorphic(in.value());
            case io.nova.query.jpql.ast.Predicate.Exists ignored -> false;
        };
    }

    private static boolean isPolymorphic(Expression expression) {
        return expression instanceof Expression.Type
                || expression instanceof Expression.Treat
                || expression instanceof Expression.EntityTypeLiteral;
    }

    // ----------------------------------------------------------------------------------------
    // Predicate → Nova Criteria
    // ----------------------------------------------------------------------------------------

    private Predicate translatePredicate(
            io.nova.query.jpql.ast.Predicate predicate, String rootAlias, EntityMetadata<?> rootMeta,
            JpqlParameters params) {
        return switch (predicate) {
            case io.nova.query.jpql.ast.Predicate.And and -> Criteria.and(
                    translatePredicate(and.left(), rootAlias, rootMeta, params),
                    translatePredicate(and.right(), rootAlias, rootMeta, params));
            case io.nova.query.jpql.ast.Predicate.Or or -> Criteria.or(
                    translatePredicate(or.left(), rootAlias, rootMeta, params),
                    translatePredicate(or.right(), rootAlias, rootMeta, params));
            case io.nova.query.jpql.ast.Predicate.Not not ->
                    Criteria.not(translatePredicate(not.inner(), rootAlias, rootMeta, params));
            case io.nova.query.jpql.ast.Predicate.Comparison c -> comparison(c, rootAlias, rootMeta, params);
            case io.nova.query.jpql.ast.Predicate.Like like -> like(like, rootAlias, rootMeta, params);
            case io.nova.query.jpql.ast.Predicate.Between b -> Criteria.between(
                    field(b.value(), rootAlias, rootMeta),
                    value(b.low(), params),
                    value(b.high(), params));
            case io.nova.query.jpql.ast.Predicate.Null n -> n.negated()
                    ? Criteria.isNotNull(field(n.value(), rootAlias, rootMeta))
                    : Criteria.isNull(field(n.value(), rootAlias, rootMeta));
            case io.nova.query.jpql.ast.Predicate.InList in -> {
                String field = field(in.value(), rootAlias, rootMeta);
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
            io.nova.query.jpql.ast.Predicate.Comparison c, String rootAlias, EntityMetadata<?> rootMeta,
            JpqlParameters params) {
        String field = field(c.left(), rootAlias, rootMeta);
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

    private Predicate like(
            io.nova.query.jpql.ast.Predicate.Like like, String rootAlias, EntityMetadata<?> rootMeta,
            JpqlParameters params) {
        if (like.escape() != null) {
            throw unsupported("LIKE ... ESCAPE in an entity-returning query");
        }
        String field = field(like.value(), rootAlias, rootMeta);
        Object pattern = value(like.pattern(), params);
        return like.negated() ? Criteria.notLike(field, pattern) : Criteria.like(field, pattern);
    }

    private Sort translateSort(List<OrderItem> orderBy, String rootAlias, EntityMetadata<?> rootMeta) {
        List<Sort.Order> orders = new ArrayList<>();
        for (OrderItem item : orderBy) {
            String field = field(item.expression(), rootAlias, rootMeta);
            orders.add(new Sort.Order(field, item.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC));
        }
        return new Sort(orders);
    }

    // ----------------------------------------------------------------------------------------
    // Leaf resolution
    // ----------------------------------------------------------------------------------------

    /** {@code alias.field} 경로에서 Nova Criteria가 쓰는 property name(필드명)을 뽑는다. */
    private String field(Expression expression, String rootAlias, EntityMetadata<?> rootMeta) {
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
        String name = path.segments().get(0);
        rejectCompositeToOne(name, rootMeta);
        return name;
    }

    /**
     * 이 엔티티 반환 경로는 property를 단일 컬럼({@link io.nova.metadata.PersistentProperty#columnName()})으로만
     * 해석하는 코어 {@code AbstractSqlRenderer}에 위임한다. 복합키 타겟 to-one은 그 FK가 N개 컬럼이지만
     * {@code columnName()}이 <b>첫 FK 컬럼</b>만 대표로 돌려주므로, 이 자리에서 비교/정렬하면 나머지 컴포넌트를
     * 조용히 누락한 SQL(첫 컬럼만 비교)이 되어 <b>silent wrong-row</b> 위험이 있다. 등치/IS NULL 포함 모든 연산에서
     * build-time에 명확히 거부하고, 컴포넌트로 전개되는 대안(스칼라 프로젝션 또는 Criteria API)으로 안내한다.
     */
    private void rejectCompositeToOne(String propertyName, EntityMetadata<?> rootMeta) {
        rootMeta.findProperty(propertyName).ifPresent(property -> {
            if (property.manyToOne() && property.isCompositeToOne()) {
                throw new JpqlException("Composite-key to-one association '" + propertyName
                        + "' cannot be used in a WHERE/ORDER BY of an entity-returning JPQL query (its foreign key "
                        + "spans multiple columns; this path would compare only the first column and could return "
                        + "wrong rows). Rewrite it as a scalar projection (e.g. SELECT c.id FROM ... WHERE c."
                        + propertyName + " <op> :ref, which expands to all foreign-key components), or express the "
                        + "query with the Criteria API.");
            }
        });
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
                + ". Rewrite it as a scalar/aggregate projection, or express the query with the Criteria API.");
    }
}
