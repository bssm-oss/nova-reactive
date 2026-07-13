package io.nova.query.jpql;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.query.jpql.ast.Assignment;
import io.nova.query.jpql.ast.Expression;
import io.nova.query.jpql.ast.JoinClause;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.query.jpql.ast.OrderItem;
import io.nova.query.jpql.ast.Predicate;
import io.nova.query.jpql.ast.SelectItem;
import io.nova.query.jpql.ast.Subquery;
import io.nova.query.jpql.ast.WhenClause;
import io.nova.sql.Dialect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JPQL AST를 dialect SQL로 변환한다(스칼라/집계 SELECT와 벌크 UPDATE/DELETE 경로). 엔티티명→테이블명,
 * 필드명→컬럼명 해석은 {@link EntityMetadata}로 하고, bind marker/식별자 quoting은 {@link Dialect}로 태운다.
 * dialect 특화 로직은 여기 두지 않고 오직 {@link Dialect#quote(String)}/{@link Dialect#bindMarkers()}만 쓴다.
 * <p>
 * 엔티티 자체를 반환하는 단순 SELECT는 이 빌더가 아니라 {@link JpqlEntityQueryPlanner}가 기존 엔티티
 * 하이드레이션 경로(QuerySpec)로 처리한다. 이 빌더는 스칼라 결과와 벌크 변경만 담당한다.
 */
public final class JpqlSqlBuilder {

    /** 1:1로 표준/H2 SQL에 매핑되는 스칼라 함수 allowlist(대문자). 그 외 함수는 fail-fast. */
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "LOWER", "UPPER", "LENGTH", "TRIM", "CONCAT", "ABS", "MOD", "SQRT",
            "COALESCE", "NULLIF", "SUBSTRING",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP");
    private static final Set<String> NO_ARG_FUNCTIONS = Set.of("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP");

    private final Dialect dialect;
    private final JpqlEntityResolver resolver;

    public JpqlSqlBuilder(Dialect dialect, JpqlEntityResolver resolver) {
        this.dialect = dialect;
        this.resolver = resolver;
    }

    // ----------------------------------------------------------------------------------------
    // Public entry points
    // ----------------------------------------------------------------------------------------

    public TranslatedSql buildScalarSelect(JpqlStatement.Select select) {
        Ctx ctx = new Ctx(new Scope(null));
        bindRoot(ctx.scope, select.rootEntity(), select.rootAlias());
        bindJoins(ctx.scope, select.joins());

        ctx.sql.append("select ");
        if (select.distinct()) {
            ctx.sql.append("distinct ");
        }
        renderSelectionList(ctx, select.selectItems());
        appendFromAndJoins(ctx, select.rootEntity(), select.rootAlias(), select.joins());
        if (select.where() != null) {
            ctx.sql.append(" where ");
            renderPredicate(ctx, select.where());
        }
        appendGroupBy(ctx, select.groupBy());
        if (select.having() != null) {
            ctx.sql.append(" having ");
            renderPredicate(ctx, select.having());
        }
        appendOrderBy(ctx, select.orderBy());
        return new TranslatedSql(
                ctx.sql.toString(), ctx.bindings, TranslatedSql.ResultKind.SCALAR, select.selectItems().size());
    }

    public TranslatedSql buildUpdate(JpqlStatement.Update update) {
        EntityMetadata<?> metadata = resolver.resolve(update.rootEntity());
        String alias = requireBulkAlias(update.rootAlias(), update.rootEntity(), "UPDATE");
        // 벌크 UPDATE는 단일 테이블이므로 별칭을 SQL에 내보내지 않고 컬럼을 unqualified로 렌더한다
        // (표준 SQL/H2/PG/MySQL 모두 수용). 별칭은 필드 해석 목적으로만 스코프에 바인딩한다.
        Ctx ctx = new Ctx(new Scope(null));
        ctx.qualify = false;
        ctx.scope.bind(alias, metadata);

        ctx.sql.append("update ").append(tableRef(metadata)).append(" set ");
        List<Assignment> assignments = update.assignments();
        for (int i = 0; i < assignments.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            Assignment a = assignments.get(i);
            ctx.sql.append(columnOnly(a.target(), alias, metadata)).append(" = ");
            renderExpression(ctx, a.value());
        }
        if (update.where() != null) {
            ctx.sql.append(" where ");
            renderPredicate(ctx, update.where());
        }
        return new TranslatedSql(ctx.sql.toString(), ctx.bindings, TranslatedSql.ResultKind.MUTATION, 0);
    }

    public TranslatedSql buildDelete(JpqlStatement.Delete delete) {
        EntityMetadata<?> metadata = resolver.resolve(delete.rootEntity());
        String alias = requireBulkAlias(delete.rootAlias(), delete.rootEntity(), "DELETE");
        Ctx ctx = new Ctx(new Scope(null));
        ctx.qualify = false;
        ctx.scope.bind(alias, metadata);

        ctx.sql.append("delete from ").append(tableRef(metadata));
        if (delete.where() != null) {
            ctx.sql.append(" where ");
            renderPredicate(ctx, delete.where());
        }
        return new TranslatedSql(ctx.sql.toString(), ctx.bindings, TranslatedSql.ResultKind.MUTATION, 0);
    }

    private static String requireBulkAlias(String alias, String entityName, String kind) {
        if (alias == null) {
            throw new JpqlException("Bulk " + kind + " on '" + entityName
                    + "' requires an explicit alias in v1 (e.g. " + kind + " " + entityName
                    + " e ... e.field), so field references can be resolved unambiguously");
        }
        return alias;
    }

    // ----------------------------------------------------------------------------------------
    // FROM / JOIN
    // ----------------------------------------------------------------------------------------

    private void bindRoot(Scope scope, String entityName, String alias) {
        EntityMetadata<?> metadata = resolver.resolve(entityName);
        if (alias == null) {
            throw new JpqlException("A FROM alias is required for '" + entityName + "' in scalar/aggregate queries");
        }
        scope.bind(alias, metadata);
    }

    private void bindJoins(Scope scope, List<JoinClause> joins) {
        for (JoinClause join : joins) {
            if (join.fetch()) {
                throw new JpqlException("JOIN FETCH is only valid in an entity-returning SELECT; "
                        + "it cannot be used with a scalar/aggregate projection or inside a subquery");
            }
            EntityMetadata<?> owner = scope.resolve(join.ownerAlias());
            PersistentProperty relation = owner.findProperty(join.relation())
                    .orElseThrow(() -> new JpqlException("Unknown relation '" + join.ownerAlias() + "."
                            + join.relation() + "' on entity " + owner.entityType().getSimpleName()));
            if (!relation.manyToOne()) {
                throw new JpqlException("JOIN " + join.ownerAlias() + "." + join.relation()
                        + " is only supported for @ManyToOne relations in v1; "
                        + "other association joins are deferred to Wave2 W3");
            }
            EntityMetadata<?> target = resolver.resolve(relation.manyToOneTargetType());
            scope.bind(join.alias(), target);
        }
    }

    private void appendFromAndJoins(Ctx ctx, String rootEntity, String rootAlias, List<JoinClause> joins) {
        EntityMetadata<?> rootMeta = resolver.resolve(rootEntity);
        ctx.sql.append(" from ").append(tableRef(rootMeta)).append(' ').append(rootAlias);
        for (JoinClause join : joins) {
            EntityMetadata<?> owner = ctx.scope.resolve(join.ownerAlias());
            PersistentProperty relation = owner.findProperty(join.relation()).orElseThrow();
            EntityMetadata<?> target = resolver.resolve(relation.manyToOneTargetType());
            ctx.sql.append(join.inner() ? " join " : " left join ")
                    .append(tableRef(target)).append(' ').append(join.alias())
                    .append(" on ")
                    .append(join.ownerAlias()).append('.').append(dialect.quote(relation.columnName()))
                    .append(" = ")
                    .append(join.alias()).append('.').append(dialect.quote(target.idProperty().columnName()));
        }
    }

    // ----------------------------------------------------------------------------------------
    // SELECT list
    // ----------------------------------------------------------------------------------------

    private void renderSelectionList(Ctx ctx, List<SelectItem> items) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            SelectItem item = items.get(i);
            if (item.isEntity()) {
                throw new JpqlException("Entity selection (SELECT " + item.entityAlias()
                        + ") is not handled by the scalar builder; this is an internal routing error");
            }
            renderExpression(ctx, item.expression());
            // 안정적 컬럼 라벨을 부여해 결과를 위치 대신 이름으로 읽을 수 있게 한다. dialect quote로 감싸
            // 드라이버가 라벨 대소문자를 보존하도록 한다(H2는 unquoted 식별자를 대문자로 접기 때문).
            ctx.sql.append(" as ").append(dialect.quote(JpqlQuery.columnLabel(i)));
        }
    }

    private void appendGroupBy(Ctx ctx, List<Expression.Path> groupBy) {
        if (groupBy.isEmpty()) {
            return;
        }
        ctx.sql.append(" group by ");
        for (int i = 0; i < groupBy.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            ctx.sql.append(pathColumn(ctx, groupBy.get(i)));
        }
    }

    private void appendOrderBy(Ctx ctx, List<OrderItem> orderBy) {
        if (orderBy.isEmpty()) {
            return;
        }
        ctx.sql.append(" order by ");
        for (int i = 0; i < orderBy.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            renderExpression(ctx, orderBy.get(i).expression());
            ctx.sql.append(orderBy.get(i).ascending() ? " asc" : " desc");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Predicate
    // ----------------------------------------------------------------------------------------

    private void renderPredicate(Ctx ctx, Predicate predicate) {
        switch (predicate) {
            case Predicate.And and -> {
                ctx.sql.append('(');
                renderPredicate(ctx, and.left());
                ctx.sql.append(" and ");
                renderPredicate(ctx, and.right());
                ctx.sql.append(')');
            }
            case Predicate.Or or -> {
                ctx.sql.append('(');
                renderPredicate(ctx, or.left());
                ctx.sql.append(" or ");
                renderPredicate(ctx, or.right());
                ctx.sql.append(')');
            }
            case Predicate.Not not -> {
                ctx.sql.append("not (");
                renderPredicate(ctx, not.inner());
                ctx.sql.append(')');
            }
            case Predicate.Comparison c -> {
                renderExpression(ctx, c.left());
                ctx.sql.append(' ').append(c.op().symbol()).append(' ');
                renderExpression(ctx, c.right());
            }
            case Predicate.Like like -> {
                renderExpression(ctx, like.value());
                ctx.sql.append(like.negated() ? " not like " : " like ");
                renderExpression(ctx, like.pattern());
                if (like.escape() != null) {
                    ctx.sql.append(" escape ");
                    ctx.bind(new JpqlBinding.Literal(String.valueOf(like.escape().charValue())));
                    ctx.sql.append(marker(ctx));
                }
            }
            case Predicate.Between b -> {
                renderExpression(ctx, b.value());
                ctx.sql.append(b.negated() ? " not between " : " between ");
                renderExpression(ctx, b.low());
                ctx.sql.append(" and ");
                renderExpression(ctx, b.high());
            }
            case Predicate.Null n -> {
                renderExpression(ctx, n.value());
                ctx.sql.append(n.negated() ? " is not null" : " is null");
            }
            case Predicate.InList in -> {
                renderExpression(ctx, in.value());
                ctx.sql.append(in.negated() ? " not in (" : " in (");
                List<Expression> items = in.items();
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        ctx.sql.append(", ");
                    }
                    renderExpression(ctx, items.get(i));
                }
                ctx.sql.append(')');
            }
            case Predicate.InSubquery in -> {
                renderExpression(ctx, in.value());
                ctx.sql.append(in.negated() ? " not in (" : " in (");
                renderSubquery(ctx, in.subquery());
                ctx.sql.append(')');
            }
            case Predicate.Exists ex -> {
                ctx.sql.append(ex.negated() ? "not exists (" : "exists (");
                renderSubquery(ctx, ex.subquery());
                ctx.sql.append(')');
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Expression
    // ----------------------------------------------------------------------------------------

    private void renderExpression(Ctx ctx, Expression expression) {
        switch (expression) {
            case Expression.Literal lit -> {
                ctx.bind(new JpqlBinding.Literal(lit.value()));
                ctx.sql.append(marker(ctx));
            }
            case Expression.NamedParameter p -> {
                ctx.bind(new JpqlBinding.Named(p.name()));
                ctx.sql.append(marker(ctx));
            }
            case Expression.PositionalParameter p -> {
                ctx.bind(new JpqlBinding.Positional(p.position()));
                ctx.sql.append(marker(ctx));
            }
            case Expression.Path path -> ctx.sql.append(pathColumn(ctx, path));
            case Expression.Arithmetic a -> {
                ctx.sql.append('(');
                renderExpression(ctx, a.left());
                ctx.sql.append(' ').append(a.op().symbol()).append(' ');
                renderExpression(ctx, a.right());
                ctx.sql.append(')');
            }
            case Expression.Aggregate agg -> renderAggregate(ctx, agg);
            case Expression.FunctionCall fn -> renderFunction(ctx, fn);
            case Expression.Case c -> renderCase(ctx, c);
            case Expression.ScalarSubquery s -> {
                ctx.sql.append('(');
                renderSubquery(ctx, s.subquery());
                ctx.sql.append(')');
            }
        }
    }

    private void renderAggregate(Ctx ctx, Expression.Aggregate agg) {
        ctx.sql.append(agg.op().name().toLowerCase(Locale.ROOT)).append('(');
        if (agg.distinct()) {
            ctx.sql.append("distinct ");
        }
        if (agg.argument() == null) {
            ctx.sql.append('*');
        } else {
            renderExpression(ctx, agg.argument());
        }
        ctx.sql.append(')');
    }

    private void renderFunction(Ctx ctx, Expression.FunctionCall fn) {
        String name = fn.name().toUpperCase(Locale.ROOT);
        if (!ALLOWED_FUNCTIONS.contains(name)) {
            throw new JpqlException("Function '" + fn.name() + "' is not supported in v1. Supported: "
                    + ALLOWED_FUNCTIONS);
        }
        if (NO_ARG_FUNCTIONS.contains(name)) {
            if (!fn.arguments().isEmpty()) {
                throw new JpqlException(name + " does not take arguments");
            }
            ctx.sql.append(name.toLowerCase(Locale.ROOT));
            return;
        }
        ctx.sql.append(name.toLowerCase(Locale.ROOT)).append('(');
        List<Expression> args = fn.arguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            renderExpression(ctx, args.get(i));
        }
        ctx.sql.append(')');
    }

    private void renderCase(Ctx ctx, Expression.Case c) {
        ctx.sql.append("case");
        for (WhenClause when : c.whens()) {
            ctx.sql.append(" when ");
            renderPredicate(ctx, when.condition());
            ctx.sql.append(" then ");
            renderExpression(ctx, when.result());
        }
        if (c.elseResult() != null) {
            ctx.sql.append(" else ");
            renderExpression(ctx, c.elseResult());
        }
        ctx.sql.append(" end");
    }

    // ----------------------------------------------------------------------------------------
    // Subquery
    // ----------------------------------------------------------------------------------------

    private void renderSubquery(Ctx ctx, Subquery sub) {
        Scope child = new Scope(ctx.scope);
        EntityMetadata<?> rootMeta = resolver.resolve(sub.rootEntity());
        if (sub.rootAlias() == null) {
            throw new JpqlException("Subquery FROM '" + sub.rootEntity() + "' requires an alias");
        }
        child.bind(sub.rootAlias(), rootMeta);
        bindJoins(child, sub.joins());

        Scope saved = ctx.scope;
        boolean savedQualify = ctx.qualify;
        ctx.scope = child;
        // 서브쿼리는 자체 FROM 별칭을 SQL에 내보내므로 항상 qualify한다(바깥이 벌크여도).
        ctx.qualify = true;
        try {
            ctx.sql.append("select ");
            if (sub.selection() == null) {
                ctx.sql.append('1');
            } else {
                renderExpression(ctx, sub.selection());
            }
            appendFromAndJoins(ctx, sub.rootEntity(), sub.rootAlias(), sub.joins());
            if (sub.where() != null) {
                ctx.sql.append(" where ");
                renderPredicate(ctx, sub.where());
            }
            appendGroupBy(ctx, sub.groupBy());
            if (sub.having() != null) {
                ctx.sql.append(" having ");
                renderPredicate(ctx, sub.having());
            }
        } finally {
            ctx.scope = saved;
            ctx.qualify = savedQualify;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Column / table resolution
    // ----------------------------------------------------------------------------------------

    private String tableRef(EntityMetadata<?> metadata) {
        if (metadata.hasInheritance() || metadata.hasSecondaryTables()) {
            throw new JpqlException("JPQL over inheritance/secondary-table entity '"
                    + metadata.entityType().getSimpleName() + "' is not supported in v1 scalar/bulk queries");
        }
        String schema = metadata.schema();
        String table = dialect.quote(metadata.tableName());
        return (schema == null || schema.isBlank()) ? table : dialect.quote(schema) + "." + table;
    }

    /** 경로를 SQL 컬럼 참조로 변환한다. {@code ctx.qualify}면 {@code alias."column"}, 아니면 {@code "column"}. */
    private String pathColumn(Ctx ctx, Expression.Path path) {
        EntityMetadata<?> metadata = ctx.scope.resolve(path.alias());
        String prefix = ctx.qualify ? path.alias() + "." : "";
        if (path.segments().isEmpty()) {
            // 순수 별칭 → 그 엔티티의 id 컬럼(COUNT(e) 등).
            return prefix + dialect.quote(metadata.idProperty().columnName());
        }
        if (path.segments().size() > 1) {
            throw new JpqlException("Multi-segment path '" + path.alias() + "."
                    + String.join(".", path.segments()) + "' is not supported in v1; "
                    + "add an explicit JOIN and reference the joined alias");
        }
        String field = path.segments().get(0);
        PersistentProperty property = metadata.findProperty(field)
                .orElseThrow(() -> new JpqlException("Unknown field '" + field + "' on entity "
                        + metadata.entityType().getSimpleName()));
        if (property.isRelation() && !property.manyToOne()) {
            throw new JpqlException("Path over collection/association field '" + field
                    + "' is not supported in v1; use an explicit JOIN");
        }
        return prefix + dialect.quote(property.columnName());
    }

    /** UPDATE SET 대상용: 별칭 없이 컬럼만. */
    private String columnOnly(Expression.Path target, String alias, EntityMetadata<?> metadata) {
        if (target.segments().size() != 1) {
            throw new JpqlException("UPDATE SET target must be a single field, got '" + target.alias()
                    + (target.segments().isEmpty() ? "" : "." + String.join(".", target.segments())) + "'");
        }
        String field = target.segments().get(0);
        PersistentProperty property = metadata.findProperty(field)
                .orElseThrow(() -> new JpqlException("Unknown field '" + field + "' on entity "
                        + metadata.entityType().getSimpleName()));
        if (property.id()) {
            throw new JpqlException("Cannot assign the @Id field '" + field + "' in a bulk UPDATE");
        }
        return dialect.quote(property.columnName());
    }

    private String marker(Ctx ctx) {
        // bind()가 방금 추가한 슬롯의 1-기반 인덱스로 marker를 만든다.
        return dialect.bindMarkers().marker(ctx.bindings.size());
    }

    // ----------------------------------------------------------------------------------------
    // Internal mutable state
    // ----------------------------------------------------------------------------------------

    private static final class Ctx {
        final StringBuilder sql = new StringBuilder();
        final List<JpqlBinding> bindings = new ArrayList<>();
        Scope scope;
        /** true면 컬럼을 {@code alias."col"}로 qualify한다. 벌크 UPDATE/DELETE 최상위에서만 false. */
        boolean qualify = true;

        Ctx(Scope scope) {
            this.scope = scope;
        }

        void bind(JpqlBinding binding) {
            bindings.add(binding);
        }
    }

    /** 별칭 → 메타데이터 스코프. 서브쿼리 상관 참조를 위해 부모 스코프로 위임한다. */
    private static final class Scope {
        private final Scope parent;
        private final Map<String, EntityMetadata<?>> aliases = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        void bind(String alias, EntityMetadata<?> metadata) {
            if (aliases.putIfAbsent(alias, metadata) != null) {
                throw new JpqlException("Duplicate alias '" + alias + "' in JPQL query");
            }
        }

        EntityMetadata<?> resolve(String alias) {
            EntityMetadata<?> metadata = aliases.get(alias);
            if (metadata != null) {
                return metadata;
            }
            if (parent != null) {
                return parent.resolve(alias);
            }
            throw new JpqlException("Unknown alias '" + alias + "' in JPQL query");
        }
    }
}
