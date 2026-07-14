package io.nova.query.jpql;

import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.ManyToManyInfo;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JPQL AST를 dialect SQL로 변환한다(스칼라/집계/DTO 투영 SELECT와 벌크 UPDATE/DELETE 경로). 엔티티명→테이블명,
 * 필드명→컬럼명 해석은 {@link EntityMetadata}로 하고, bind marker/식별자 quoting은 {@link Dialect}로 태운다.
 * dialect 특화 로직은 여기 두지 않고 오직 {@link Dialect#quote(String)}/{@link Dialect#bindMarkers()}만 쓴다.
 * <p>
 * 다세그먼트 경로(예 {@code a.dept.name})는 {@code @ManyToOne}/owning {@code @OneToOne} 연관을 따라 INNER JOIN을
 * 자동 생성해 해석한다(묵시 조인). 같은 (소유자, 관계) 경로는 한 조인으로 dedupe한다. 컬렉션 경로는 지원하지
 * 않고 fail-fast한다.
 * <p>
 * 엔티티 자체를 반환하는 단순 SELECT는 이 빌더가 아니라 {@link JpqlEntityQueryPlanner}가 기존 엔티티
 * 하이드레이션 경로(QuerySpec)로 처리한다. 이 빌더는 스칼라/DTO 결과와 벌크 변경만 담당한다.
 */
public final class JpqlSqlBuilder {

    /** 1:1로 표준/H2 SQL에 매핑되는 스칼라 함수 allowlist(대문자). 그 외 함수는 fail-fast. */
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "LOWER", "UPPER", "LENGTH", "TRIM", "CONCAT", "ABS", "MOD", "SQRT",
            "COALESCE", "NULLIF", "SUBSTRING", "LOCATE",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP");
    private static final Set<String> NO_ARG_FUNCTIONS = Set.of("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP");

    /**
     * {@code CAST(x AS type)}의 JPQL 타입 토큰 → 안전한 표준 SQL 타입 화이트리스트. 여기 없는 타입은 fail-fast하며,
     * 사용자 입력 문자열이 SQL에 그대로 삽입되지 않도록 값만 매핑한다.
     */
    private static final Map<String, String> CAST_TYPES = Map.ofEntries(
            Map.entry("STRING", "varchar"),
            Map.entry("CHAR", "varchar"),
            Map.entry("VARCHAR", "varchar"),
            Map.entry("TEXT", "varchar"),
            Map.entry("INTEGER", "integer"),
            Map.entry("INT", "integer"),
            Map.entry("LONG", "bigint"),
            Map.entry("BIGINT", "bigint"),
            Map.entry("SHORT", "smallint"),
            Map.entry("SMALLINT", "smallint"),
            Map.entry("DOUBLE", "double precision"),
            Map.entry("FLOAT", "real"),
            Map.entry("REAL", "real"),
            Map.entry("DECIMAL", "decimal"),
            Map.entry("BIGDECIMAL", "decimal"),
            Map.entry("NUMERIC", "decimal"),
            Map.entry("BOOLEAN", "boolean"),
            Map.entry("DATE", "date"),
            Map.entry("TIME", "time"),
            Map.entry("TIMESTAMP", "timestamp"));

    /** {@code FUNCTION('native', ...)}의 native 함수명 화이트리스트 정규식 — 식별자 문자만 허용(SQL injection 방지). */
    private static final java.util.regex.Pattern NATIVE_FN_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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
        ctx.block = new Block(select.rootEntity(), select.rootAlias(), select.joins());
        bindRoot(ctx.scope, select.rootEntity(), select.rootAlias());
        bindJoins(ctx.scope, select.joins());

        // 각 절을 개별 버퍼로 렌더한다(묵시 조인은 렌더 중 수집된다). 최종 SQL 순서대로 렌더하므로 bind
        // 순서가 marker 위치와 정확히 일치한다.
        // 상속 discriminator 제한(구체 서브타입 루트 + TREAT downcast)을 미리 수집해 WHERE에 병합한다.
        // 렌더 순서(select → where → ...)를 유지하므로 bind marker 위치가 정확히 정렬된다.
        List<DiscriminatorConstraint> constraints = collectDiscriminatorConstraints(ctx, select);

        int[] columns = {0};
        String selectSql = capture(ctx, () -> renderSelectionList(ctx, select.selectItems(), columns));
        String whereSql = (constraints.isEmpty() && select.where() == null)
                ? null : capture(ctx, () -> renderWhereWithConstraints(ctx, constraints, select.where()));
        String groupSql = select.groupBy().isEmpty()
                ? null : capture(ctx, () -> renderGroupByBody(ctx, select.groupBy()));
        String havingSql = select.having() == null
                ? null : capture(ctx, () -> renderPredicate(ctx, select.having()));
        String orderSql = select.orderBy().isEmpty()
                ? null : capture(ctx, () -> renderOrderByBody(ctx, select.orderBy()));

        ctx.sql.append("select ");
        if (select.distinct()) {
            ctx.sql.append("distinct ");
        }
        ctx.sql.append(selectSql);
        appendFrom(ctx, ctx.block);
        if (whereSql != null) {
            ctx.sql.append(" where ").append(whereSql);
        }
        if (groupSql != null) {
            ctx.sql.append(" group by ").append(groupSql);
        }
        if (havingSql != null) {
            ctx.sql.append(" having ").append(havingSql);
        }
        if (orderSql != null) {
            ctx.sql.append(" order by ").append(orderSql);
        }
        return new TranslatedSql(ctx.sql.toString(), ctx.bindings, TranslatedSql.ResultKind.SCALAR, columns[0]);
    }

    public TranslatedSql buildUpdate(JpqlStatement.Update update) {
        EntityMetadata<?> metadata = resolver.resolve(update.rootEntity());
        requireNoInheritanceForBulk(metadata, "UPDATE");
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
            ctx.sql.append(columnOnly(a.target(), metadata)).append(" = ");
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
        requireNoInheritanceForBulk(metadata, "DELETE");
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

    /** 벌크 UPDATE/DELETE는 discriminator 제한/다중 테이블을 v1에서 다루지 않으므로 상속 엔티티를 거부한다. */
    private static void requireNoInheritanceForBulk(EntityMetadata<?> metadata, String kind) {
        if (metadata.hasInheritance()) {
            throw new JpqlException("Bulk " + kind + " over @Inheritance entity '"
                    + metadata.entityType().getSimpleName() + "' is not supported in v1");
        }
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
    // Section capture
    // ----------------------------------------------------------------------------------------

    /** {@code r}이 {@code ctx.sql}에 렌더한 결과를 별도 버퍼로 캡처해 문자열로 돌려준다. bind는 전역 순서로 누적된다. */
    private String capture(Ctx ctx, Runnable r) {
        StringBuilder previous = ctx.sql;
        StringBuilder buffer = new StringBuilder();
        ctx.sql = buffer;
        try {
            r.run();
        } finally {
            ctx.sql = previous;
        }
        return buffer.toString();
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
                        + " is only supported for @ManyToOne/owning @OneToOne relations in v1; "
                        + "other association joins are deferred");
            }
            EntityMetadata<?> target = resolver.resolve(relation.manyToOneTargetType());
            scope.bind(join.alias(), target);
        }
    }

    /** {@code from} 절 + 명시 조인 + 묵시 조인을 {@code ctx.sql}에 붙인다. */
    private void appendFrom(Ctx ctx, Block block) {
        EntityMetadata<?> rootMeta = resolver.resolve(block.rootEntity);
        ctx.sql.append(" from ").append(tableRef(rootMeta)).append(' ').append(block.rootAlias);
        for (JoinClause join : block.explicitJoins) {
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
        for (ImplicitJoin ij : block.implicitByKey.values()) {
            ctx.sql.append(" join ")
                    .append(tableRef(ij.targetMeta)).append(' ').append(ij.alias)
                    .append(" on ")
                    .append(ij.ownerAlias).append('.').append(dialect.quote(ij.fkColumn))
                    .append(" = ")
                    .append(ij.alias).append('.').append(dialect.quote(ij.targetIdColumn));
        }
    }

    // ----------------------------------------------------------------------------------------
    // SELECT list
    // ----------------------------------------------------------------------------------------

    private void renderSelectionList(Ctx ctx, List<SelectItem> items, int[] columns) {
        for (SelectItem item : items) {
            if (item.isEntity()) {
                throw new JpqlException("Entity selection (SELECT " + item.entityAlias()
                        + ") is not handled by the scalar builder; this is an internal routing error");
            }
            if (item.isConstructor()) {
                // NEW 생성자 프로젝션: 각 인자를 스칼라 컬럼으로 투영한다(인스턴스화는 JpqlQuery가 담당).
                for (Expression arg : item.constructorCall().arguments()) {
                    renderColumn(ctx, arg, columns);
                }
            } else {
                renderColumn(ctx, item.expression(), columns);
            }
        }
    }

    private void renderColumn(Ctx ctx, Expression expr, int[] columns) {
        if (columns[0] > 0) {
            ctx.sql.append(", ");
        }
        renderExpression(ctx, expr);
        // 안정적 컬럼 라벨을 부여해 결과를 위치 대신 이름으로 읽을 수 있게 한다. dialect quote로 감싸
        // 드라이버가 라벨 대소문자를 보존하도록 한다(H2는 unquoted 식별자를 대문자로 접기 때문).
        ctx.sql.append(" as ").append(dialect.quote(JpqlQuery.columnLabel(columns[0])));
        columns[0]++;
    }

    private void renderGroupByBody(Ctx ctx, List<Expression.Path> groupBy) {
        for (int i = 0; i < groupBy.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            ctx.sql.append(pathColumn(ctx, groupBy.get(i)));
        }
    }

    private void renderOrderByBody(Ctx ctx, List<OrderItem> orderBy) {
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
            case Expression.Cast cast -> renderCast(ctx, cast);
            case Expression.Case c -> renderCase(ctx, c);
            case Expression.ScalarSubquery s -> {
                ctx.sql.append('(');
                renderSubquery(ctx, s.subquery());
                ctx.sql.append(')');
            }
            case Expression.Type t -> ctx.sql.append(discriminatorColumnRef(ctx, t.alias()));
            case Expression.EntityTypeLiteral lit -> {
                EntityMetadata<?> meta = resolver.resolve(lit.entityName());
                if (!meta.hasInheritance()) {
                    throw new JpqlException("TYPE(...) comparison against '" + lit.entityName()
                            + "' requires an @Inheritance entity; it has no discriminator");
                }
                ctx.bind(new JpqlBinding.Literal(meta.inheritance().discriminatorBindValue()));
                ctx.sql.append(marker(ctx));
            }
            case Expression.Treat tr -> ctx.sql.append(treatColumn(ctx, tr));
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
        // FUNCTION('native_fn', args...) — dialect native 함수 이스케이프.
        if (name.equals("FUNCTION")) {
            renderNativeFunction(ctx, fn);
            return;
        }
        // SIZE(collectionPath) — 컬렉션 크기를 상관 COUNT 서브쿼리로.
        if (name.equals("SIZE")) {
            renderSize(ctx, fn);
            return;
        }
        if (!ALLOWED_FUNCTIONS.contains(name)) {
            throw new JpqlException("Function '" + fn.name() + "' is not supported. Supported: "
                    + ALLOWED_FUNCTIONS + " plus CAST(x AS type), FUNCTION('native', ...), SIZE(collection)");
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

    private void renderNativeFunction(Ctx ctx, Expression.FunctionCall fn) {
        List<Expression> args = fn.arguments();
        if (args.isEmpty() || !(args.get(0) instanceof Expression.Literal lit)
                || !(lit.value() instanceof String rawName)) {
            throw new JpqlException("FUNCTION(...) requires the native function name as a string literal first "
                    + "argument, e.g. FUNCTION('date_trunc', 'month', e.createdAt)");
        }
        if (!NATIVE_FN_NAME.matcher(rawName).matches()) {
            throw new JpqlException("FUNCTION native name '" + rawName + "' is not a valid SQL identifier; "
                    + "only [A-Za-z_][A-Za-z0-9_]* is allowed");
        }
        ctx.sql.append(rawName).append('(');
        for (int i = 1; i < args.size(); i++) {
            if (i > 1) {
                ctx.sql.append(", ");
            }
            renderExpression(ctx, args.get(i));
        }
        ctx.sql.append(')');
    }

    private void renderSize(Ctx ctx, Expression.FunctionCall fn) {
        if (!ctx.qualify) {
            throw new JpqlException("SIZE(...) is not supported in a bulk UPDATE/DELETE");
        }
        if (fn.arguments().size() != 1 || !(fn.arguments().get(0) instanceof Expression.Path path)
                || path.segments().size() != 1) {
            throw new JpqlException("SIZE(...) requires a single collection association path, e.g. SIZE(a.books)");
        }
        EntityMetadata<?> ownerMeta = ctx.scope.resolve(path.alias());
        String field = path.segments().get(0);
        PersistentProperty property = ownerMeta.findProperty(field)
                .orElseThrow(() -> new JpqlException("Unknown field '" + field + "' on entity "
                        + ownerMeta.entityType().getSimpleName()));
        String ownerId = dialect.quote(ownerMeta.idProperty().columnName());
        String ownerRef = path.alias() + "." + ownerId;
        String alias = ctx.nextImplicitAlias();

        String table;
        String fkColumn;
        if (property.oneToMany() && !property.oneToManyMappedBy().isBlank()) {
            EntityMetadata<?> childMeta = resolver.resolve(collectionElementType(property, field));
            PersistentProperty inverse = childMeta.findProperty(property.oneToManyMappedBy())
                    .orElseThrow(() -> new JpqlException("@OneToMany(mappedBy='" + property.oneToManyMappedBy()
                            + "') on '" + field + "' points to an unknown inverse field on "
                            + childMeta.entityType().getSimpleName()));
            table = tableRef(childMeta);
            fkColumn = dialect.quote(inverse.columnName());
        } else if (property.elementCollection()) {
            ElementCollectionInfo info = property.elementCollectionInfo();
            table = dialect.quote(info.collectionTableName());
            fkColumn = dialect.quote(info.ownerForeignKeyColumn());
        } else if (property.manyToMany()) {
            ManyToManyInfo info = property.manyToManyInfo();
            table = dialect.quote(info.joinTableName());
            fkColumn = dialect.quote(info.ownerForeignKeyColumn());
        } else {
            throw new JpqlException("SIZE('" + field + "') is only supported for @OneToMany(mappedBy), "
                    + "@ElementCollection, or @ManyToMany collections");
        }
        ctx.sql.append("(select count(*) from ").append(table).append(' ').append(alias)
                .append(" where ").append(alias).append('.').append(fkColumn)
                .append(" = ").append(ownerRef).append(')');
    }

    /** {@code @OneToMany}의 원소 엔티티 타입을 해석한다. 메타데이터에 없으면 필드 제네릭 시그니처에서 추론한다. */
    private static Class<?> collectionElementType(PersistentProperty property, String field) {
        Class<?> declared = property.oneToManyTargetType();
        if (declared != null) {
            return declared;
        }
        java.lang.reflect.Type generic = property.field().getGenericType();
        if (generic instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[args.length - 1] instanceof Class<?> element) {
                return element;
            }
        }
        throw new JpqlException("Cannot determine the element type of @OneToMany '" + field
                + "' for SIZE(...); declare @OneToMany(targetEntity=...)");
    }

    private void renderCast(Ctx ctx, Expression.Cast cast) {
        String sqlType = CAST_TYPES.get(cast.targetType());
        if (sqlType == null) {
            throw new JpqlException("CAST target type '" + cast.targetType() + "' is not supported. Supported: "
                    + CAST_TYPES.keySet());
        }
        ctx.sql.append("cast(");
        renderExpression(ctx, cast.value());
        ctx.sql.append(" as ").append(sqlType).append(')');
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

        // v1은 서브쿼리 안에 discriminator 제한을 주입하지 않는다(collectDiscriminatorConstraints는 outer
        // SELECT에서만 돈다). 따라서 서브쿼리 root가 구체 서브타입이면 공유 물리 테이블이 필터 없이 풀려
        // 다른 서브타입 행까지 매칭되고, 서브쿼리 안의 TYPE/TREAT도 discriminator 없이 downcast된다 —
        // 조용한 오답 대신 명확히 fail-fast한다(Criteria join/subquery 경로와 동일한 정책).
        if (rootMeta.hasInheritance() && !rootMeta.isInheritanceRoot()) {
            throw new JpqlException("JPQL subquery over concrete subtype '"
                    + rootMeta.entityType().getSimpleName() + "' is not supported in v1: the discriminator "
                    + "restriction is not applied inside subqueries. Query the inheritance root in the subquery "
                    + "and filter with TYPE(...) there, or restructure the query.");
        }
        if (usesPolymorphic(sub.selection()) || usesPolymorphic(sub.where()) || usesPolymorphic(sub.having())) {
            throw new JpqlException("TYPE(...)/TREAT(...) inside a JPQL subquery is not supported in v1: the "
                    + "discriminator restriction is not applied inside subqueries, so the downcast would silently "
                    + "match other subtypes. Move the polymorphic condition to the top-level query.");
        }

        Scope savedScope = ctx.scope;
        Block savedBlock = ctx.block;
        boolean savedQualify = ctx.qualify;
        ctx.scope = child;
        ctx.block = new Block(sub.rootEntity(), sub.rootAlias(), sub.joins());
        // 서브쿼리는 자체 FROM 별칭을 SQL에 내보내므로 항상 qualify한다(바깥이 벌크여도).
        ctx.qualify = true;
        try {
            String selectionSql = capture(ctx, () -> {
                if (sub.selection() == null) {
                    ctx.sql.append('1');
                } else {
                    renderExpression(ctx, sub.selection());
                }
            });
            String whereSql = sub.where() == null
                    ? null : capture(ctx, () -> renderPredicate(ctx, sub.where()));
            String groupSql = sub.groupBy().isEmpty()
                    ? null : capture(ctx, () -> renderGroupByBody(ctx, sub.groupBy()));
            String havingSql = sub.having() == null
                    ? null : capture(ctx, () -> renderPredicate(ctx, sub.having()));

            ctx.sql.append("select ").append(selectionSql);
            appendFrom(ctx, ctx.block);
            if (whereSql != null) {
                ctx.sql.append(" where ").append(whereSql);
            }
            if (groupSql != null) {
                ctx.sql.append(" group by ").append(groupSql);
            }
            if (havingSql != null) {
                ctx.sql.append(" having ").append(havingSql);
            }
        } finally {
            ctx.scope = savedScope;
            ctx.block = savedBlock;
            ctx.qualify = savedQualify;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Column / table resolution
    // ----------------------------------------------------------------------------------------

    private String tableRef(EntityMetadata<?> metadata) {
        // SINGLE_TABLE 상속은 모든 서브타입이 한 물리 테이블을 공유하므로 스칼라/집계 SELECT가 그 테이블을
        // 직접 참조할 수 있다(구체 서브타입 루트/TREAT는 discriminator 제한을 별도로 붙인다). JOINED/
        // TABLE_PER_CLASS(다중 테이블)와 @SecondaryTable은 단일 FROM으로 표현할 수 없어 v1에서 fail-fast한다.
        if (metadata.hasSecondaryTables()
                || (metadata.hasInheritance() && !metadata.inheritance().singleTable())) {
            throw new JpqlException("JPQL over non-SINGLE_TABLE inheritance/secondary-table entity '"
                    + metadata.entityType().getSimpleName() + "' is not supported in v1 scalar/bulk queries");
        }
        String schema = metadata.schema();
        String table = dialect.quote(metadata.tableName());
        return (schema == null || schema.isBlank()) ? table : dialect.quote(schema) + "." + table;
    }

    /**
     * 경로를 SQL 컬럼 참조로 변환한다. 단일 세그먼트는 소유 별칭 컬럼으로, 다세그먼트는 중간 to-one 연관을
     * 묵시 INNER JOIN으로 전개해 마지막 별칭 컬럼으로 해석한다. {@code ctx.qualify}면 {@code alias."column"}.
     */
    private String pathColumn(Ctx ctx, Expression.Path path) {
        EntityMetadata<?> ownerMeta = ctx.scope.resolve(path.alias());
        List<String> segments = path.segments();
        if (segments.isEmpty()) {
            // 순수 별칭 → 그 엔티티의 id 컬럼(COUNT(e) 등).
            String col = dialect.quote(ownerMeta.idProperty().columnName());
            return ctx.qualify ? path.alias() + "." + col : col;
        }
        if (segments.size() == 1) {
            return resolveColumn(ctx, path.alias(), ownerMeta, segments.get(0));
        }
        // 다세그먼트: 묵시 조인이 필요하므로 unqualified(벌크) 컨텍스트에서는 지원하지 않는다.
        if (!ctx.qualify) {
            throw new JpqlException("Multi-segment path '" + path.alias() + "."
                    + String.join(".", segments) + "' requires a join and is not supported in bulk UPDATE/DELETE");
        }
        String currentAlias = path.alias();
        EntityMetadata<?> currentMeta = ownerMeta;
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            EntityMetadata<?> segMeta = currentMeta;
            PersistentProperty prop = currentMeta.findProperty(segment)
                    .orElseThrow(() -> new JpqlException("Unknown field '" + segment + "' on entity "
                            + segMeta.entityType().getSimpleName()));
            if (!prop.manyToOne()) {
                throw new JpqlException("Path segment '" + segment + "' navigates a collection or non-association "
                        + "field; implicit joins only support @ManyToOne/owning @OneToOne");
            }
            EntityMetadata<?> targetMeta = resolver.resolve(prop.manyToOneTargetType());
            currentAlias = implicitJoin(ctx, currentAlias, segment, prop, targetMeta);
            currentMeta = targetMeta;
        }
        return resolveColumn(ctx, currentAlias, currentMeta, segments.get(segments.size() - 1));
    }

    /** {@code alias.field} 단일 세그먼트를 컬럼 참조로. to-one 관계면 FK 컬럼, 컬렉션이면 fail-fast. */
    private String resolveColumn(Ctx ctx, String alias, EntityMetadata<?> metadata, String field) {
        PersistentProperty property = metadata.findProperty(field)
                .orElseThrow(() -> new JpqlException("Unknown field '" + field + "' on entity "
                        + metadata.entityType().getSimpleName()));
        if (property.isRelation() && !property.manyToOne()) {
            throw new JpqlException("Path over collection/association field '" + field
                    + "' is not supported; use an explicit JOIN or SIZE(...)");
        }
        String col = dialect.quote(property.columnName());
        return ctx.qualify ? alias + "." + col : col;
    }

    /** {@code (ownerAlias, relation)}에 대한 묵시 INNER JOIN을 만들거나 재사용하고 대상 별칭을 돌려준다. */
    private String implicitJoin(Ctx ctx, String ownerAlias, String relation,
                                PersistentProperty prop, EntityMetadata<?> targetMeta) {
        String key = ownerAlias + ' ' + relation;
        ImplicitJoin existing = ctx.block.implicitByKey.get(key);
        if (existing != null) {
            return existing.alias;
        }
        String alias = ctx.nextImplicitAlias();
        ctx.scope.bind(alias, targetMeta);
        ImplicitJoin ij = new ImplicitJoin(
                alias, ownerAlias, targetMeta, prop.columnName(), targetMeta.idProperty().columnName());
        ctx.block.implicitByKey.put(key, ij);
        return alias;
    }

    /** UPDATE SET 대상용: 별칭 없이 컬럼만. */
    private String columnOnly(Expression.Path target, EntityMetadata<?> metadata) {
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

    // ----------------------------------------------------------------------------------------
    // Inheritance: TYPE / TREAT / discriminator
    // ----------------------------------------------------------------------------------------

    /** {@code TYPE(alias)} → 별칭 엔티티의 discriminator 컬럼 참조. SINGLE_TABLE 상속만 지원한다. */
    private String discriminatorColumnRef(Ctx ctx, String alias) {
        EntityMetadata<?> meta = ctx.scope.resolve(alias);
        requireSingleTableInheritance(meta, "TYPE(" + alias + ")");
        String col = dialect.quote(meta.inheritance().discriminatorColumn());
        return ctx.qualify ? alias + "." + col : col;
    }

    /** {@code TREAT(alias AS Subtype).field} → 서브타입 속성 컬럼 참조(SINGLE_TABLE, 단일 세그먼트). */
    private String treatColumn(Ctx ctx, Expression.Treat treat) {
        EntityMetadata<?> baseMeta = ctx.scope.resolve(treat.alias());
        EntityMetadata<?> subMeta = resolveTreatSubtype(baseMeta, treat.subtype());
        if (treat.segments().isEmpty()) {
            throw new JpqlException("TREAT(" + treat.alias() + " AS " + treat.subtype()
                    + ") must be followed by an attribute in a scalar projection, e.g. TREAT(" + treat.alias()
                    + " AS " + treat.subtype() + ").field");
        }
        if (treat.segments().size() != 1) {
            throw new JpqlException("Multi-segment TREAT path 'TREAT(" + treat.alias() + " AS " + treat.subtype()
                    + ")." + String.join(".", treat.segments()) + "' is not supported in v1");
        }
        return resolveColumn(ctx, treat.alias(), subMeta, treat.segments().get(0));
    }

    /** downcast 대상 서브타입 메타데이터를 해석하고 같은 SINGLE_TABLE 계층인지 검증한다. */
    private EntityMetadata<?> resolveTreatSubtype(EntityMetadata<?> baseMeta, String subtypeName) {
        requireSingleTableInheritance(baseMeta, "TREAT");
        EntityMetadata<?> subMeta = resolver.resolve(subtypeName);
        if (!subMeta.inheritance().present() || !subMeta.inheritance().sameHierarchy(baseMeta.inheritance())) {
            throw new JpqlException("TREAT target '" + subtypeName + "' is not a subtype in the same inheritance "
                    + "hierarchy as '" + baseMeta.entityType().getSimpleName() + "'");
        }
        return subMeta;
    }

    private static void requireSingleTableInheritance(EntityMetadata<?> meta, String context) {
        if (!meta.hasInheritance()) {
            throw new JpqlException(context + " requires an @Inheritance entity; '"
                    + meta.entityType().getSimpleName() + "' is not polymorphic");
        }
        if (!meta.inheritance().singleTable()) {
            throw new JpqlException(context + " is only supported for SINGLE_TABLE inheritance in v1; '"
                    + meta.entityType().getSimpleName() + "' uses " + meta.inheritance().strategy());
        }
    }

    /**
     * 구체 서브타입 루트와 TREAT downcast가 유도하는 discriminator 제한을 수집한다. (별칭, discriminator 값)
     * 기준으로 dedupe해 같은 제한을 중복 렌더하지 않는다.
     */
    private List<DiscriminatorConstraint> collectDiscriminatorConstraints(Ctx ctx, JpqlStatement.Select select) {
        LinkedHashMap<String, DiscriminatorConstraint> byKey = new LinkedHashMap<>();
        EntityMetadata<?> rootMeta = ctx.scope.resolve(select.rootAlias());
        if (rootMeta.hasInheritance() && rootMeta.inheritance().singleTable() && !rootMeta.isInheritanceRoot()) {
            byKey.putIfAbsent(constraintKey(select.rootAlias(), rootMeta.inheritance().discriminatorBindValue()),
                    new DiscriminatorConstraint(select.rootAlias(),
                            rootMeta.inheritance().discriminatorColumn(),
                            rootMeta.inheritance().discriminatorBindValue()));
        }
        collectTreats(select, (alias, subtype) -> {
            EntityMetadata<?> baseMeta = ctx.scope.resolve(alias);
            EntityMetadata<?> subMeta = resolveTreatSubtype(baseMeta, subtype);
            Object value = subMeta.inheritance().discriminatorBindValue();
            byKey.putIfAbsent(constraintKey(alias, value),
                    new DiscriminatorConstraint(alias, baseMeta.inheritance().discriminatorColumn(), value));
        });
        return new ArrayList<>(byKey.values());
    }

    private static String constraintKey(String alias, Object value) {
        return alias + ' ' + value;
    }

    /** discriminator 제한(있으면)을 먼저 렌더하고 사용자 WHERE를 {@code and}로 이어 붙인다. */
    private void renderWhereWithConstraints(Ctx ctx, List<DiscriminatorConstraint> constraints, Predicate where) {
        boolean first = true;
        for (DiscriminatorConstraint c : constraints) {
            if (!first) {
                ctx.sql.append(" and ");
            }
            String col = dialect.quote(c.column());
            ctx.sql.append(ctx.qualify ? c.alias() + "." + col : col).append(" = ");
            ctx.bind(new JpqlBinding.Literal(c.value()));
            ctx.sql.append(marker(ctx));
            first = false;
        }
        if (where != null) {
            if (!first) {
                ctx.sql.append(" and ");
            }
            renderPredicate(ctx, where);
        }
    }

    /** select/where/having/order/group의 모든 식을 훑어 TREAT downcast(별칭, 서브타입)를 수집한다. */
    private void collectTreats(JpqlStatement.Select select, java.util.function.BiConsumer<String, String> sink) {
        for (SelectItem item : select.selectItems()) {
            if (item.isConstructor()) {
                for (Expression arg : item.constructorCall().arguments()) {
                    walkTreats(arg, sink);
                }
            } else if (!item.isEntity()) {
                walkTreats(item.expression(), sink);
            }
        }
        walkTreats(select.where(), sink);
        walkTreats(select.having(), sink);
        for (OrderItem order : select.orderBy()) {
            walkTreats(order.expression(), sink);
        }
        for (Expression.Path path : select.groupBy()) {
            walkTreats(path, sink);
        }
    }

    private void walkTreats(Expression expression, java.util.function.BiConsumer<String, String> sink) {
        if (expression == null) {
            return;
        }
        switch (expression) {
            case Expression.Treat tr -> sink.accept(tr.alias(), tr.subtype());
            case Expression.Arithmetic a -> {
                walkTreats(a.left(), sink);
                walkTreats(a.right(), sink);
            }
            case Expression.Aggregate agg -> walkTreats(agg.argument(), sink);
            case Expression.FunctionCall fn -> fn.arguments().forEach(a -> walkTreats(a, sink));
            case Expression.Cast cast -> walkTreats(cast.value(), sink);
            case Expression.Case c -> {
                for (WhenClause when : c.whens()) {
                    walkTreats(when.condition(), sink);
                    walkTreats(when.result(), sink);
                }
                walkTreats(c.elseResult(), sink);
            }
            default -> {
                // Path/Literal/파라미터/Type/EntityTypeLiteral/ScalarSubquery: TREAT를 품지 않는다.
            }
        }
    }

    private void walkTreats(Predicate predicate, java.util.function.BiConsumer<String, String> sink) {
        if (predicate == null) {
            return;
        }
        switch (predicate) {
            case Predicate.And and -> {
                walkTreats(and.left(), sink);
                walkTreats(and.right(), sink);
            }
            case Predicate.Or or -> {
                walkTreats(or.left(), sink);
                walkTreats(or.right(), sink);
            }
            case Predicate.Not not -> walkTreats(not.inner(), sink);
            case Predicate.Comparison c -> {
                walkTreats(c.left(), sink);
                walkTreats(c.right(), sink);
            }
            case Predicate.Like like -> {
                walkTreats(like.value(), sink);
                walkTreats(like.pattern(), sink);
            }
            case Predicate.Between b -> {
                walkTreats(b.value(), sink);
                walkTreats(b.low(), sink);
                walkTreats(b.high(), sink);
            }
            case Predicate.Null n -> walkTreats(n.value(), sink);
            case Predicate.InList in -> {
                walkTreats(in.value(), sink);
                in.items().forEach(i -> walkTreats(i, sink));
            }
            case Predicate.InSubquery in -> walkTreats(in.value(), sink);
            case Predicate.Exists ignored -> {
                // 서브쿼리 내부의 TREAT는 v1 수집 범위 밖(rare); 서브쿼리 렌더가 필요 시 fail-fast한다.
            }
        }
    }

    /** 서브쿼리 스코프에 TYPE(...)/TREAT(...)가 등장하는지 검사한다(discriminator 미주입 → fail-fast 판정용). */
    private static boolean usesPolymorphic(Expression expression) {
        if (expression == null) {
            return false;
        }
        return switch (expression) {
            case Expression.Type ignored -> true;
            case Expression.Treat ignored -> true;
            case Expression.Arithmetic a -> usesPolymorphic(a.left()) || usesPolymorphic(a.right());
            case Expression.Aggregate agg -> usesPolymorphic(agg.argument());
            case Expression.FunctionCall fn -> fn.arguments().stream().anyMatch(JpqlSqlBuilder::usesPolymorphic);
            case Expression.Cast cast -> usesPolymorphic(cast.value());
            case Expression.Case c -> c.whens().stream()
                    .anyMatch(w -> usesPolymorphic(w.condition()) || usesPolymorphic(w.result()))
                    || usesPolymorphic(c.elseResult());
            // 중첩 서브쿼리는 자체 renderSubquery 호출에서 다시 검사된다.
            default -> false;
        };
    }

    private static boolean usesPolymorphic(Predicate predicate) {
        if (predicate == null) {
            return false;
        }
        return switch (predicate) {
            case Predicate.And and -> usesPolymorphic(and.left()) || usesPolymorphic(and.right());
            case Predicate.Or or -> usesPolymorphic(or.left()) || usesPolymorphic(or.right());
            case Predicate.Not not -> usesPolymorphic(not.inner());
            case Predicate.Comparison c -> usesPolymorphic(c.left()) || usesPolymorphic(c.right());
            case Predicate.Like like -> usesPolymorphic(like.value()) || usesPolymorphic(like.pattern());
            case Predicate.Between b ->
                    usesPolymorphic(b.value()) || usesPolymorphic(b.low()) || usesPolymorphic(b.high());
            case Predicate.Null n -> usesPolymorphic(n.value());
            case Predicate.InList in ->
                    usesPolymorphic(in.value()) || in.items().stream().anyMatch(JpqlSqlBuilder::usesPolymorphic);
            case Predicate.InSubquery in -> usesPolymorphic(in.value());
            case Predicate.Exists ignored -> false;
        };
    }

    /** 구체 서브타입/TREAT downcast가 유도하는 {@code discriminator = value} 제한. */
    private record DiscriminatorConstraint(String alias, String column, Object value) {
    }

    private String marker(Ctx ctx) {
        // bind()가 방금 추가한 슬롯의 1-기반 인덱스로 marker를 만든다.
        return dialect.bindMarkers().marker(ctx.bindings.size());
    }

    // ----------------------------------------------------------------------------------------
    // Internal mutable state
    // ----------------------------------------------------------------------------------------

    private static final class Ctx {
        StringBuilder sql = new StringBuilder();
        final List<JpqlBinding> bindings = new ArrayList<>();
        Scope scope;
        Block block;
        /** true면 컬럼을 {@code alias."col"}로 qualify한다. 벌크 UPDATE/DELETE 최상위에서만 false. */
        boolean qualify = true;
        private int implicitAliasSeq;

        Ctx(Scope scope) {
            this.scope = scope;
        }

        void bind(JpqlBinding binding) {
            bindings.add(binding);
        }

        /** 현재 스코프 체인과 충돌하지 않는 묵시 조인용 별칭을 만든다. */
        String nextImplicitAlias() {
            String alias;
            do {
                alias = "j" + (implicitAliasSeq++);
            } while (scope.contains(alias));
            return alias;
        }
    }

    /** 하나의 FROM 블록(최상위 쿼리 또는 서브쿼리)에 대한 명시/묵시 조인 상태. */
    private static final class Block {
        final String rootEntity;
        final String rootAlias;
        final List<JoinClause> explicitJoins;
        final LinkedHashMap<String, ImplicitJoin> implicitByKey = new LinkedHashMap<>();

        Block(String rootEntity, String rootAlias, List<JoinClause> explicitJoins) {
            this.rootEntity = rootEntity;
            this.rootAlias = rootAlias;
            this.explicitJoins = explicitJoins;
        }
    }

    /** 다세그먼트 경로에서 자동 생성한 INNER JOIN. */
    private static final class ImplicitJoin {
        final String alias;
        final String ownerAlias;
        final EntityMetadata<?> targetMeta;
        final String fkColumn;
        final String targetIdColumn;

        ImplicitJoin(String alias, String ownerAlias, EntityMetadata<?> targetMeta,
                     String fkColumn, String targetIdColumn) {
            this.alias = alias;
            this.ownerAlias = ownerAlias;
            this.targetMeta = targetMeta;
            this.fkColumn = fkColumn;
            this.targetIdColumn = targetIdColumn;
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

        boolean contains(String alias) {
            if (aliases.containsKey(alias)) {
                return true;
            }
            return parent != null && parent.contains(alias);
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
