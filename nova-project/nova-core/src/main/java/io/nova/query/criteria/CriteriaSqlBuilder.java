package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.sql.Dialect;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Selection;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria 스칼라/집계 쿼리를 dialect SQL로 변환한다. 단일 루트 테이블을 대상으로 unqualified 컬럼을
 * 렌더하므로(H2/PostgreSQL/MySQL 모두 수용) 별칭 배관 없이 SELECT/WHERE/GROUP BY/HAVING/ORDER BY를
 * 만든다. 엔티티/필드→테이블/컬럼 해석은 {@link EntityMetadata}로, bind marker/식별자 quoting은
 * {@link Dialect}로만 태운다 — dialect 특화 로직은 두지 않는다. 이 빌더는 {@code io.nova.query.jpql}의
 * SQL 빌더와 파일을 공유하지 않는 격리된 자체 구현이다.
 */
final class CriteriaSqlBuilder {

    private final Dialect dialect;

    CriteriaSqlBuilder(Dialect dialect) {
        this.dialect = dialect;
    }

    CriteriaSql build(CriteriaQueryImpl<?> query) {
        CriteriaRoot<?> root = query.root();
        EntityMetadata<?> metadata = root.ownerMetadata();
        List<Selection<?>> selections = query.selections();
        if (selections.isEmpty()) {
            throw new CriteriaException("Scalar Criteria query requires an explicit select()/multiselect()");
        }

        Ctx ctx = new Ctx();
        ctx.sql.append("select ");
        if (query.isDistinct()) {
            ctx.sql.append("distinct ");
        }
        for (int i = 0; i < selections.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            renderSelection(ctx, selections.get(i));
            ctx.sql.append(" as ").append(dialect.quote(columnLabel(i)));
        }
        ctx.sql.append(" from ").append(tableRef(metadata));

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

        return new CriteriaSql(ctx.sql.toString(), ctx.bindings, selections.size());
    }

    // --- selection ------------------------------------------------------------------------------

    private void renderSelection(Ctx ctx, Selection<?> selection) {
        if (selection instanceof CriteriaAggregate<?> aggregate) {
            renderAggregate(ctx, aggregate);
        } else if (selection instanceof CriteriaPath<?> path) {
            ctx.sql.append(column(path));
        } else if (selection instanceof CriteriaRoot<?>) {
            throw new CriteriaException("Selecting the entity root alongside scalars is not supported in v1; "
                    + "select individual attributes or aggregates");
        } else {
            throw new CriteriaException("Unsupported selection: "
                    + (selection == null ? "null" : selection.getClass().getSimpleName()));
        }
    }

    private void renderAggregate(Ctx ctx, CriteriaAggregate<?> aggregate) {
        ctx.sql.append(aggregate.function().sqlName()).append('(');
        if (aggregate.function().distinct()) {
            ctx.sql.append("distinct ");
        }
        ctx.sql.append(column(aggregate.operand())).append(')');
    }

    // --- predicate ------------------------------------------------------------------------------

    private void renderPredicate(Ctx ctx, CriteriaPredicate predicate) {
        switch (predicate.kind()) {
            case AND -> renderJunction(ctx, predicate, " and ");
            case OR -> renderJunction(ctx, predicate, " or ");
            case NOT -> {
                ctx.sql.append("not (");
                renderPredicate(ctx, predicate.inner());
                ctx.sql.append(')');
            }
            case COMPARISON -> {
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
            case NULL -> ctx.sql.append(column(predicate.path()))
                    .append(predicate.negated() ? " is not null" : " is null");
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
            // Nova Criteria와 동일한 안전 동작: 빈 IN은 항상-거짓, 빈 NOT IN은 항상-참.
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

    // --- column / table -------------------------------------------------------------------------

    private String tableRef(EntityMetadata<?> metadata) {
        if (metadata.hasInheritance() || metadata.hasSecondaryTables()) {
            throw new CriteriaException("Criteria over inheritance/secondary-table entity '"
                    + metadata.entityType().getSimpleName() + "' is not supported in v1 scalar queries");
        }
        String schema = metadata.schema();
        String table = dialect.quote(metadata.tableName());
        return (schema == null || schema.isBlank()) ? table : dialect.quote(schema) + "." + table;
    }

    private String column(CriteriaColumnPath path) {
        PersistentProperty property = path.property();
        return dialect.quote(property.columnName());
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

    static String columnLabel(int index) {
        return "c" + index;
    }

    private static final class Ctx {
        final StringBuilder sql = new StringBuilder();
        final List<Object> bindings = new ArrayList<>();
    }
}
