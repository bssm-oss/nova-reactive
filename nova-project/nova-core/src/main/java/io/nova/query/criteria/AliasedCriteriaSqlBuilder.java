package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.sql.Dialect;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Selection;

import java.util.ArrayList;
import java.util.List;

/**
 * join/subquery를 포함한 Criteria 쿼리를 alias 한정 dialect SQL로 렌더한다. 각 FROM 소스(루트/조인)에
 * {@code t0}, {@code t1} … alias를 부여하고 컬럼을 {@code alias.column}으로 한정하며, 연관 property에서
 * 유도한 {@code inner/left join ... on ...} 절과 {@code exists}/{@code in}/스칼라 서브쿼리 술어를 만든다.
 * <p>
 * 값 파라미터는 모두 dialect bind marker로 바인딩되고 테이블/컬럼 식별자는 {@link EntityMetadata}에서만
 * 온다(화이트리스트) — 사용자 입력이 식별자 자리에 끼어들 수 없다. {@code io.nova.query.jpql} SQL 빌더와
 * 파일을 공유하지 않는 격리된 자체 구현으로, 기존 단일 루트 {@link CriteriaSqlBuilder}와도 독립적이다.
 */
final class AliasedCriteriaSqlBuilder {

    private final Dialect dialect;

    AliasedCriteriaSqlBuilder(Dialect dialect) {
        this.dialect = dialect;
    }

    /** 스칼라/집계/투영 SELECT(사용자 선택 목록)를 alias 한정 SQL로 렌더한다. */
    CriteriaSql buildScalar(CriteriaQueryImpl<?> query) {
        CriteriaRoot<?> root = query.root();
        List<Selection<?>> selections = query.selections();
        if (selections.isEmpty()) {
            throw new CriteriaException("Criteria query with joins requires an explicit select()/multiselect()");
        }
        Ctx ctx = new Ctx();
        assignAliases(root);

        ctx.sql.append("select ");
        if (query.isDistinct()) {
            ctx.sql.append("distinct ");
        }
        for (int i = 0; i < selections.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            renderSelection(ctx, selections.get(i));
            ctx.sql.append(" as ").append(dialect.quote(CriteriaSqlBuilder.columnLabel(i)));
        }
        renderFromWhereGroupOrder(ctx, query, root);
        return new CriteriaSql(ctx.sql.toString(), ctx.bindings, selections.size());
    }

    /**
     * 엔티티 반환 쿼리의 2단계 실행 1단계: 루트 id 컬럼만 순서대로 투영한다. 이후 호출자가 이 id들로
     * 기존 하이드레이션 경로에 위임해 완전한 엔티티(연관 포함)를 로드하고 이 순서로 재배열한다.
     */
    CriteriaSql buildRootIdProjection(CriteriaQueryImpl<?> query) {
        CriteriaRoot<?> root = query.root();
        EntityMetadata<?> metadata = root.ownerMetadata();
        if (metadata.hasCompositeId()) {
            throw new CriteriaException("Entity-returning Criteria query with joins/subqueries requires a "
                    + "single-column @Id on the root; composite-id roots are not supported in v1");
        }
        Ctx ctx = new Ctx();
        assignAliases(root);
        ctx.sql.append("select ").append(qualified(root.alias(), metadata.idProperty().columnName()))
                .append(" as ").append(dialect.quote(CriteriaSqlBuilder.columnLabel(0)));
        renderFromWhereGroupOrder(ctx, query, root);
        return new CriteriaSql(ctx.sql.toString(), ctx.bindings, 1);
    }

    // --- shared FROM / WHERE / GROUP BY / HAVING / ORDER BY ------------------------------------

    private void renderFromWhereGroupOrder(Ctx ctx, CriteriaQueryImpl<?> query, CriteriaRoot<?> root) {
        ctx.sql.append(" from ").append(tableRef(root.ownerMetadata())).append(' ').append(dialect.quote(root.alias()));
        renderJoins(ctx, root);

        CriteriaPredicate where = query.restriction();
        if (where != null) {
            ctx.sql.append(" where ");
            renderPredicate(ctx, where);
        }

        List<Expression<?>> groupBy = query.groupExpressions();
        if (!groupBy.isEmpty()) {
            ctx.sql.append(" group by ");
            for (int i = 0; i < groupBy.size(); i++) {
                if (i > 0) {
                    ctx.sql.append(", ");
                }
                ctx.sql.append(column(columnPath(groupBy.get(i), "group by")));
            }
        }

        CriteriaPredicate having = query.havingPredicate();
        if (having != null) {
            ctx.sql.append(" having ");
            renderPredicate(ctx, having);
        }

        List<CriteriaOrder> orders = query.orders();
        if (!orders.isEmpty()) {
            ctx.sql.append(" order by ");
            for (int i = 0; i < orders.size(); i++) {
                if (i > 0) {
                    ctx.sql.append(", ");
                }
                ctx.sql.append(column(orders.get(i).path())).append(orders.get(i).isAscending() ? " asc" : " desc");
            }
        }
    }

    private void renderJoins(Ctx ctx, CriteriaFrom from) {
        for (CriteriaJoin<?, ?> join : from.joins()) {
            String keyword = join.getJoinType() == JoinType.LEFT ? " left join " : " inner join ";
            ctx.sql.append(keyword)
                    .append(tableRef(join.ownerMetadata())).append(' ').append(dialect.quote(join.alias()))
                    .append(" on ");
            // 단일키는 한 짝, 복합키 타겟 to-one은 모든 FK↔참조 @Id 컴포넌트 짝을 and로 잇는다.
            List<CriteriaJoinResolver.JoinColumnPair> pairs = join.columnPairs();
            for (int i = 0; i < pairs.size(); i++) {
                if (i > 0) {
                    ctx.sql.append(" and ");
                }
                CriteriaJoinResolver.JoinColumnPair pair = pairs.get(i);
                ctx.sql.append(qualified(join.parentFrom().alias(), pair.parentColumn()))
                        .append(" = ")
                        .append(qualified(join.alias(), pair.childColumn()));
            }
            renderJoins(ctx, join);
        }
    }

    private void assignAliases(CriteriaFrom from) {
        from.alias();
        for (CriteriaJoin<?, ?> join : from.joins()) {
            assignAliases(join);
        }
    }

    // --- selection ------------------------------------------------------------------------------

    private void renderSelection(Ctx ctx, Selection<?> selection) {
        if (selection instanceof CriteriaAggregate<?> aggregate) {
            ctx.sql.append(aggregate.function().sqlName()).append('(');
            if (aggregate.function().distinct()) {
                ctx.sql.append("distinct ");
            }
            ctx.sql.append(column(aggregate.operand())).append(')');
        } else if (selection instanceof CriteriaColumnPath path) {
            ctx.sql.append(column(path));
        } else {
            throw new CriteriaException("Unsupported selection in join/subquery query: "
                    + (selection == null ? "null" : selection.getClass().getSimpleName())
                    + "; select individual attributes or aggregates");
        }
    }

    // --- predicate ------------------------------------------------------------------------------

    private void renderPredicate(Ctx ctx, CriteriaPredicate predicate) {
        if (predicate instanceof DiscriminatorPredicate) {
            throw new CriteriaException("cb.equal(root.type(), ...) polymorphic restriction is not supported "
                    + "together with joins/subqueries in v1; use it on a single-root query");
        }
        switch (predicate.kind()) {
            case AND -> renderJunction(ctx, predicate, " and ");
            case OR -> renderJunction(ctx, predicate, " or ");
            case NOT -> {
                ctx.sql.append("not (");
                renderPredicate(ctx, predicate.inner());
                ctx.sql.append(')');
            }
            case COMPARISON -> {
                if (isCompositeToOne(predicate.path())) {
                    renderCompositeToOneComparison(ctx, predicate.path(), predicate.op(), predicate.value());
                    break;
                }
                ctx.sql.append(column(predicate.path())).append(' ').append(predicate.op().symbol()).append(' ');
                bindMarker(ctx, predicate.value());
            }
            case LIKE -> {
                ctx.sql.append(column(predicate.path())).append(predicate.negated() ? " not like " : " like ");
                bindMarker(ctx, predicate.value());
            }
            case BETWEEN -> {
                ctx.sql.append(column(predicate.path())).append(" between ");
                bindMarker(ctx, predicate.low());
                ctx.sql.append(" and ");
                bindMarker(ctx, predicate.high());
            }
            case IN -> renderIn(ctx, predicate);
            case NULL -> {
                if (isCompositeToOne(predicate.path())) {
                    renderCompositeToOneNull(ctx, predicate.path(), predicate.negated());
                    break;
                }
                ctx.sql.append(column(predicate.path()))
                        .append(predicate.negated() ? " is not null" : " is null");
            }
            case EXISTS -> {
                ctx.sql.append(predicate.negated() ? "not exists (" : "exists (");
                renderSubquery(ctx, predicate.subquery(), true);
                ctx.sql.append(')');
            }
            case IN_SUBQUERY -> {
                ctx.sql.append(column(predicate.path())).append(predicate.negated() ? " not in (" : " in (");
                renderSubquery(ctx, predicate.subquery(), false);
                ctx.sql.append(')');
            }
            case COMPARISON_SUBQUERY -> {
                ctx.sql.append(column(predicate.path())).append(' ').append(predicate.op().symbol()).append(" (");
                renderSubquery(ctx, predicate.subquery(), false);
                ctx.sql.append(')');
            }
            case COMPARISON_COLUMN -> ctx.sql.append(column(predicate.path())).append(' ')
                    .append(predicate.op().symbol()).append(' ').append(column(predicate.rightPath()));
        }
    }

    private void renderJunction(Ctx ctx, CriteriaPredicate predicate, String separator) {
        List<CriteriaPredicate> children = predicate.children();
        ctx.sql.append('(');
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                ctx.sql.append(separator);
            }
            renderPredicate(ctx, children.get(i));
        }
        ctx.sql.append(')');
    }

    private void renderIn(Ctx ctx, CriteriaPredicate predicate) {
        List<Object> values = predicate.inValues();
        if (values.isEmpty()) {
            ctx.sql.append(predicate.negated() ? "1 = 1" : "1 = 0");
            return;
        }
        ctx.sql.append(column(predicate.path())).append(predicate.negated() ? " not in (" : " in (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            bindMarker(ctx, values.get(i));
        }
        ctx.sql.append(')');
    }

    // --- subquery -------------------------------------------------------------------------------

    private void renderSubquery(Ctx ctx, CriteriaSubquery<?> subquery, boolean forExists) {
        if (subquery == null) {
            throw new CriteriaException("Subquery predicate has no subquery");
        }
        List<CriteriaRoot<?>> fromRoots = subquery.fromRoots();
        if (fromRoots.isEmpty()) {
            throw new CriteriaException("Subquery must declare at least one from(Class) root");
        }
        for (CriteriaRoot<?> subRoot : fromRoots) {
            assignAliases(subRoot);
        }

        ctx.sql.append("select ");
        if (forExists) {
            ctx.sql.append('1');
        } else {
            Expression<?> selection = subquery.selectionExpression();
            if (!(selection instanceof CriteriaColumnPath || selection instanceof CriteriaAggregate<?>)) {
                throw new CriteriaException("Subquery used in IN/comparison must select a single attribute "
                        + "or aggregate via subquery.select(...)");
            }
            renderSelection(ctx, (Selection<?>) selection);
        }

        ctx.sql.append(" from ");
        for (int i = 0; i < fromRoots.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            CriteriaRoot<?> subRoot = fromRoots.get(i);
            ctx.sql.append(tableRef(subRoot.ownerMetadata())).append(' ').append(dialect.quote(subRoot.alias()));
            renderJoins(ctx, subRoot);
        }

        CriteriaPredicate where = subquery.restrictionPredicate();
        if (where != null) {
            ctx.sql.append(" where ");
            renderPredicate(ctx, where);
        }
    }

    // --- column / table -------------------------------------------------------------------------

    private String tableRef(EntityMetadata<?> metadata) {
        if (metadata.hasInheritance() || metadata.hasSecondaryTables()) {
            throw new CriteriaException("Criteria join/subquery over inheritance/secondary-table entity '"
                    + metadata.entityType().getSimpleName() + "' is not supported in v1");
        }
        String schema = metadata.schema();
        String table = dialect.quote(metadata.tableName());
        return (schema == null || schema.isBlank()) ? table : dialect.quote(schema) + "." + table;
    }

    private String column(CriteriaColumnPath path) {
        PersistentProperty property = path.property();
        if (property.manyToOne() && property.isCompositeToOne()) {
            // 복합키 타겟 to-one을 단일 컬럼으로 렌더하는 자리(SELECT 투영/GROUP BY/ORDER BY/집계)는 대표 컬럼
            // 하나로 축약할 수 없다 — 조용한 오답 대신 명확히 거부한다. 비교/IS NULL만 컴포넌트로 전개된다.
            throw new CriteriaException("Composite-key to-one association '" + property.propertyName()
                    + "' cannot be used as a single column here (its foreign key spans multiple columns); "
                    + "it is only supported in a join, an equality/inequality comparison, or an IS [NOT] NULL test");
        }
        CriteriaFrom source = path.source();
        String columnName = property.columnName();
        if (source == null) {
            return dialect.quote(columnName);
        }
        return qualified(source.alias(), columnName);
    }

    /** 경로가 복합키 타겟 owning to-one을 가리키는지(다중컬럼 FK). */
    private static boolean isCompositeToOne(CriteriaColumnPath path) {
        if (path == null) {
            return false;
        }
        PersistentProperty property = path.property();
        return property.manyToOne() && property.isCompositeToOne();
    }

    /**
     * {@code alias.parent = :ref} / {@code alias.parent <> :ref}를 모든 FK 컴포넌트 비교로 전개한다.
     * {@code =}는 각 컴포넌트 eq의 {@code and}, {@code <>}는 각 컴포넌트 neq의 {@code or}(튜플 부등)로 렌더하며,
     * 각 bind 값은 참조 엔티티에서 해당 {@code @Id} 컴포넌트를 꺼내 저장 표현으로 인코딩한다.
     */
    private void renderCompositeToOneComparison(Ctx ctx, CriteriaColumnPath path, CompareOp op, Object reference) {
        boolean eq = op == CompareOp.EQ;
        boolean ne = op == CompareOp.NE;
        if (!eq && !ne) {
            throw new CriteriaException("Ordering comparison (" + op.symbol() + ") on composite-key to-one '"
                    + path.property().propertyName() + "' is not supported; only equal/notEqual are, "
                    + "or compare its @Id components individually");
        }
        String alias = path.source() == null ? null : path.source().alias();
        List<io.nova.metadata.ToOneForeignKeyColumn> columns = path.property().toOneForeignKey().columns();
        ctx.sql.append('(');
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ctx.sql.append(ne ? " or " : " and ");
            }
            io.nova.metadata.ToOneForeignKeyColumn fk = columns.get(i);
            ctx.sql.append(alias == null ? dialect.quote(fk.columnName()) : qualified(alias, fk.columnName()))
                    .append(eq ? " = " : " <> ");
            Object domain = reference == null ? null : fk.readReferencedValue(reference);
            bindMarker(ctx, fk.toColumnValue(domain));
        }
        ctx.sql.append(')');
    }

    /** {@code alias.parent IS [NOT] NULL}을 모든 FK 컬럼의 IS [NOT] NULL {@code and}로 전개한다. */
    private void renderCompositeToOneNull(Ctx ctx, CriteriaColumnPath path, boolean negated) {
        String alias = path.source() == null ? null : path.source().alias();
        List<io.nova.metadata.ToOneForeignKeyColumn> columns = path.property().toOneForeignKey().columns();
        ctx.sql.append('(');
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ctx.sql.append(" and ");
            }
            io.nova.metadata.ToOneForeignKeyColumn fk = columns.get(i);
            ctx.sql.append(alias == null ? dialect.quote(fk.columnName()) : qualified(alias, fk.columnName()))
                    .append(negated ? " is not null" : " is null");
        }
        ctx.sql.append(')');
    }

    private String qualified(String alias, String columnName) {
        return dialect.quote(alias) + "." + dialect.quote(columnName);
    }

    private CriteriaColumnPath columnPath(Expression<?> expression, String context) {
        if (expression instanceof CriteriaColumnPath path) {
            return path;
        }
        throw new CriteriaException(context + " requires a single-column attribute path");
    }

    private void bindMarker(Ctx ctx, Object value) {
        ctx.bindings.add(value);
        ctx.sql.append(dialect.bindMarkers().marker(ctx.bindings.size()));
    }

    private static final class Ctx {
        final StringBuilder sql = new StringBuilder();
        final List<Object> bindings = new ArrayList<>();
    }
}
