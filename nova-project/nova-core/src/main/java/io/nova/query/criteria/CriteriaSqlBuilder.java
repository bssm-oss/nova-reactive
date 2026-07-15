package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.InheritanceLayout;
import io.nova.metadata.PersistentProperty;
import io.nova.query.QuerySpec;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Selection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Criteria 스칼라/집계 쿼리를 dialect SQL로 변환한다. 단일 루트 테이블을 대상으로 unqualified 컬럼을
 * 렌더하므로(H2/PostgreSQL/MySQL 모두 수용) 별칭 배관 없이 SELECT/WHERE/GROUP BY/HAVING/ORDER BY를
 * 만든다. 엔티티/필드→테이블/컬럼 해석은 {@link EntityMetadata}로, bind marker/식별자 quoting은
 * {@link Dialect}로만 태운다 — dialect 특화 로직은 두지 않는다. 이 빌더는 {@code io.nova.query.jpql}의
 * SQL 빌더와 파일을 공유하지 않는 격리된 자체 구현이다.
 */
final class CriteriaSqlBuilder {

    private final Dialect dialect;
    private final EntityMetadataFactory metadataFactory;

    CriteriaSqlBuilder(Dialect dialect, EntityMetadataFactory metadataFactory) {
        this.dialect = dialect;
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
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

        // 구체 서브타입 루트 / cb.treat downcast가 유도하는 discriminator 제한을 WHERE 앞에 병합한다.
        List<DiscriminatorConstraint> constraints = collectDiscriminatorConstraints(query);
        CriteriaPredicate where = query.restriction();
        if (!constraints.isEmpty() || where != null) {
            ctx.sql.append(" where ");
            boolean first = true;
            for (DiscriminatorConstraint c : constraints) {
                if (!first) {
                    ctx.sql.append(" and ");
                }
                ctx.sql.append(dialect.quote(c.column())).append(" = ");
                bindMarker(ctx, c.value());
                first = false;
            }
            if (where != null) {
                if (!first) {
                    ctx.sql.append(" and ");
                }
                renderPredicate(ctx, where);
            }
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
        if (predicate instanceof DiscriminatorPredicate dp) {
            ctx.sql.append(dialect.quote(dp.discriminatorColumn())).append(" = ");
            bindMarker(ctx, dp.discriminatorValue());
            return;
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
            case EXISTS, IN_SUBQUERY, COMPARISON_SUBQUERY, COMPARISON_COLUMN -> throw new CriteriaException(
                    "Subquery/column-to-column predicates require the aliased Criteria SQL path");
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

    /** 파생 테이블(derived table)에 부여하는 고정 alias. Criteria 스칼라 빌더는 컬럼을 unqualified로만 렌더하므로 값 자체는 임의적이다. */
    private static final String POLYMORPHIC_ROOT_ALIAS = "nova_criteria_root";

    private String tableRef(EntityMetadata<?> metadata) {
        // SINGLE_TABLE 상속은 서브타입이 루트 테이블을 공유하므로 스칼라 SELECT가 직접 참조할 수 있다
        // (구체 서브타입/treat는 discriminator 제한을 별도로 붙인다). @SecondaryTable은 단일 FROM으로
        // 표현할 수 없어 v1에서 fail-fast한다. JOINED/TABLE_PER_CLASS는 새 FROM 형태를 만들지 않고 기존
        // 다형 SELECT 렌더러(JOIN/UNION을 flat 컬럼 + discriminator로 감싼 파생 테이블)를 재사용한다 —
        // 그러면 SINGLE_TABLE과 동일한 모양이 되어 discriminator 제한 로직을 그대로 쓸 수 있다.
        if (metadata.hasSecondaryTables()) {
            throw new CriteriaException("Criteria over a secondary-table entity '"
                    + metadata.entityType().getSimpleName() + "' is not supported in v1 scalar queries");
        }
        if (metadata.hasInheritance() && (metadata.inheritance().joined() || metadata.inheritance().tablePerClass())) {
            InheritanceLayout layout = metadataFactory.inheritanceLayout(metadata.inheritance().root());
            SqlStatement derived = metadata.inheritance().joined()
                    ? dialect.sqlRenderer().selectJoinedPolymorphic(layout, QuerySpec.empty())
                    : dialect.sqlRenderer().selectTablePerClassPolymorphic(layout, QuerySpec.empty());
            return "(" + derived.sql() + ") as " + POLYMORPHIC_ROOT_ALIAS;
        }
        String schema = metadata.schema();
        String table = dialect.quote(metadata.tableName());
        return (schema == null || schema.isBlank()) ? table : dialect.quote(schema) + "." + table;
    }

    private String column(CriteriaColumnPath path) {
        PersistentProperty property = path.property();
        if (property.manyToOne() && property.isCompositeToOne()) {
            // 복합키 타겟 to-one은 FK가 N개 컬럼이라 단일 컬럼으로 축약 불가. 비교/IS NULL은
            // requiresAliasedSql이 alias 경로로 라우팅해 컴포넌트로 전개하므로 여기 도달하면(SELECT 투영/
            // GROUP BY/ORDER BY 등) 축약 불가 위치다 — 조용한 오답 대신 명확히 거부한다.
            throw new CriteriaException("Composite-key to-one association '" + property.propertyName()
                    + "' cannot be used as a single column (its foreign key spans multiple columns); "
                    + "it is only supported in a join or an equality/IS NULL comparison");
        }
        // ownerMetadata()는 cb.treat(root, Sub).get("field")(CriteriaTreatedRoot)뿐 아니라 plain
        // cq.from(Sub.class)/root.get("field")(concrete-subtype-root)에서도 그 서브타입 메타데이터를
        // 돌려준다 — 두 경로 모두 같은 JOINED derived-table dedupe 함정에 노출되므로 하나의 guard로 커버한다.
        requireUnshadowedJoinedColumn(path.ownerMetadata(), property);
        return dialect.quote(property.columnName());
    }

    /**
     * JOINED 다형 SELECT의 파생 테이블은 컬럼명으로 dedupe한다(루트 먼저, 그다음 서브타입을 등록 순서대로).
     * {@code cb.treat(root, Sub).get("field")}와 concrete-subtype-root의 plain {@code root.get("field")}
     * (예: {@code cq.from(Sub.class)})가 가리키는 컬럼이 루트나 더 먼저 등록된 형제 서브타입의 동명 컬럼에
     * 가려지면 그 값을 조용히 대신 읽게 된다(비매칭 행에서는 NULL) — silent wrong-column 대신 명확히 거부한다.
     */
    private void requireUnshadowedJoinedColumn(EntityMetadata<?> subMeta, PersistentProperty property) {
        if (!subMeta.hasInheritance() || !subMeta.inheritance().joined() || subMeta.isInheritanceRoot()) {
            return;
        }
        InheritanceLayout layout = metadataFactory.inheritanceLayout(subMeta.inheritance().root());
        // 루트(또는 @MappedSuperclass 조상)가 선언해 상속받은 필드는 애초에 root table column 자체이므로
        // 충돌이 아니다 — 이 값은 이 서브타입의 "자기 테이블 기여분"이 아니라 root에서 오는 게 정답이다.
        boolean inheritedFromRoot = layout.rootTableColumns().stream()
                .anyMatch(rootProp -> rootProp.propertyName().equals(property.propertyName()));
        if (inheritedFromRoot) {
            return;
        }
        String columnName = property.columnName();
        String ref = subMeta.entityType().getSimpleName() + ".get(\"" + property.propertyName() + "\")";
        for (PersistentProperty rootProp : layout.rootTableColumns()) {
            if (rootProp.columnName().equals(columnName)) {
                throw new CriteriaException(ref + " refers to column '" + columnName + "', which collides "
                        + "with a root column of the same name in the JOINED derived table; the derived SELECT "
                        + "exposes only the root's value for that column name. Rename the column to disambiguate.");
            }
        }
        for (InheritanceLayout.ConcreteSubtype subtype : layout.subtypes()) {
            if (subtype.metadata().entityType() == subMeta.entityType()) {
                return;
            }
            for (PersistentProperty siblingProp : subtype.ownTableColumns()) {
                if (!siblingProp.id() && siblingProp.columnName().equals(columnName)) {
                    throw new CriteriaException(ref + " refers to column '" + columnName
                            + "', which collides with a same-named column already contributed by '"
                            + subtype.metadata().entityType().getSimpleName() + "' earlier in the JOINED derived "
                            + "table; the derived SELECT exposes only the first-registered source's value for "
                            + "that column name. Rename the column to disambiguate.");
                }
            }
        }
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

    // --- inheritance discriminator constraints --------------------------------------------------

    /**
     * 구체 서브타입 루트({@code from(Sub.class)})와 {@code cb.treat(root, Sub)} downcast가 유도하는
     * {@code discriminator = value} 제한을 수집한다. (컬럼, 값) 기준으로 dedupe한다.
     */
    private List<DiscriminatorConstraint> collectDiscriminatorConstraints(CriteriaQueryImpl<?> query) {
        LinkedHashMap<String, DiscriminatorConstraint> byKey = new LinkedHashMap<>();
        EntityMetadata<?> rootMeta = query.root().ownerMetadata();
        if (rootMeta.hasInheritance() && !rootMeta.isInheritanceRoot()) {
            addConstraint(byKey, rootMeta);
        }
        for (Selection<?> selection : query.selections()) {
            addTreatFromSelection(byKey, selection);
        }
        addTreatFromPredicate(byKey, query.restriction());
        addTreatFromPredicate(byKey, query.havingPredicate());
        return new ArrayList<>(byKey.values());
    }

    private void addTreatFromSelection(LinkedHashMap<String, DiscriminatorConstraint> byKey, Selection<?> selection) {
        if (selection instanceof CriteriaAggregate<?> aggregate) {
            addTreatFromColumn(byKey, aggregate.operand());
        } else if (selection instanceof CriteriaColumnPath path) {
            addTreatFromColumn(byKey, path);
        }
    }

    private void addTreatFromPredicate(LinkedHashMap<String, DiscriminatorConstraint> byKey, CriteriaPredicate p) {
        if (p == null || p instanceof DiscriminatorPredicate) {
            return;
        }
        switch (p.kind()) {
            case AND, OR -> {
                for (CriteriaPredicate child : p.children()) {
                    addTreatFromPredicate(byKey, child);
                }
            }
            case NOT -> addTreatFromPredicate(byKey, p.inner());
            case COMPARISON, LIKE, BETWEEN, IN, NULL -> addTreatFromColumn(byKey, p.path());
            default -> {
                // 서브쿼리/컬럼 대 컬럼 술어는 이 스칼라 빌더가 처리하지 않는다.
            }
        }
    }

    private void addTreatFromColumn(LinkedHashMap<String, DiscriminatorConstraint> byKey, CriteriaColumnPath column) {
        if (column != null && column.source() instanceof CriteriaTreatedRoot<?> treated) {
            addConstraint(byKey, treated.metadata());
        }
    }

    private static void addConstraint(
            LinkedHashMap<String, DiscriminatorConstraint> byKey, EntityMetadata<?> subtypeMetadata) {
        String column = subtypeMetadata.inheritance().discriminatorColumn();
        Object value = subtypeMetadata.inheritance().discriminatorBindValue();
        byKey.putIfAbsent(column + ' ' + value, new DiscriminatorConstraint(column, value));
    }

    private record DiscriminatorConstraint(String column, Object value) {
    }

    static String columnLabel(int index) {
        return "c" + index;
    }

    private static final class Ctx {
        final StringBuilder sql = new StringBuilder();
        final List<Object> bindings = new ArrayList<>();
    }
}
