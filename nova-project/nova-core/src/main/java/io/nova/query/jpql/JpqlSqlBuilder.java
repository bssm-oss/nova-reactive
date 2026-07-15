package io.nova.query.jpql;

import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.InheritanceLayout;
import io.nova.metadata.ManyToManyInfo;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.ToOneForeignKeyColumn;
import io.nova.query.QuerySpec;
import io.nova.query.jpql.ast.Assignment;
import io.nova.query.jpql.ast.ComparisonOp;
import io.nova.query.jpql.ast.Expression;
import io.nova.query.jpql.ast.JoinClause;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.query.jpql.ast.OrderItem;
import io.nova.query.jpql.ast.Predicate;
import io.nova.query.jpql.ast.SelectItem;
import io.nova.query.jpql.ast.Subquery;
import io.nova.query.jpql.ast.WhenClause;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;

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
        List<TranslatedSql.ResultSlot> slots = new ArrayList<>();
        String selectSql = capture(ctx, () -> renderSelectionList(ctx, select.selectItems(), columns, slots));
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
        return new TranslatedSql(ctx.sql.toString(), ctx.bindings, TranslatedSql.ResultKind.SCALAR, columns[0], slots);
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
            // 복합키 타겟 to-one은 FK가 N개 컬럼이며 appendFrom이 모든 컴포넌트 짝을 ON에 렌더한다.
            EntityMetadata<?> target = resolver.resolve(relation.manyToOneTargetType());
            scope.bind(join.alias(), target);
        }
    }

    /** {@code from} 절 + 명시 조인 + 묵시 조인을 {@code ctx.sql}에 붙인다. */
    private void appendFrom(Ctx ctx, Block block) {
        EntityMetadata<?> rootMeta = resolver.resolve(block.rootEntity);
        ctx.sql.append(" from ");
        rootFromClause(ctx, rootMeta, block.rootAlias);
        for (JoinClause join : block.explicitJoins) {
            EntityMetadata<?> owner = ctx.scope.resolve(join.ownerAlias());
            PersistentProperty relation = owner.findProperty(join.relation()).orElseThrow();
            EntityMetadata<?> target = resolver.resolve(relation.manyToOneTargetType());
            ctx.sql.append(join.inner() ? " join " : " left join ")
                    .append(tableRef(target)).append(' ').append(join.alias())
                    .append(" on ");
            appendJoinOn(ctx, join.ownerAlias(), join.alias(), joinColumnPairs(relation, target));
        }
        for (ImplicitJoin ij : block.implicitByKey.values()) {
            ctx.sql.append(" join ")
                    .append(tableRef(ij.targetMeta)).append(' ').append(ij.alias)
                    .append(" on ");
            appendJoinOn(ctx, ij.ownerAlias, ij.alias, ij.columnPairs);
        }
    }

    /**
     * 루트 FROM 절을 렌더한다. JOINED/TABLE_PER_CLASS 상속 루트는 <b>새 FROM 형태를 만들지 않는다</b> —
     * 이미 다형 SELECT가 JOIN/UNION을 파생 테이블(derived table)로 감싸 flat 컬럼 + discriminator 컬럼을
     * 노출하므로(SINGLE_TABLE이 자기 물리 테이블에 갖는 것과 동일한 모양), 그 렌더러 출력을 재사용해 이 쿼리의
     * root alias를 부여한다 — 그러면 기존 SINGLE_TABLE TYPE/TREAT/discriminator 렌더 경로가 그대로 동작한다.
     * 그 외(SINGLE_TABLE/비상속)는 기존처럼 물리 테이블을 직접 참조한다.
     */
    private void rootFromClause(Ctx ctx, EntityMetadata<?> rootMeta, String alias) {
        if (rootMeta.hasInheritance()
                && (rootMeta.inheritance().joined() || rootMeta.inheritance().tablePerClass())) {
            InheritanceLayout layout = resolver.inheritanceLayout(rootMeta.inheritance().root());
            SqlStatement derived = rootMeta.inheritance().joined()
                    ? dialect.sqlRenderer().selectJoinedPolymorphic(layout, QuerySpec.empty())
                    : dialect.sqlRenderer().selectTablePerClassPolymorphic(layout, QuerySpec.empty());
            ctx.sql.append('(').append(derived.sql()).append(") as ").append(alias);
            return;
        }
        ctx.sql.append(tableRef(rootMeta)).append(' ').append(alias);
    }

    /** {@code ownerAlias.fk = targetAlias.targetId} 짝들을 {@code and}로 이어 ON 절 본문을 붙인다. */
    private void appendJoinOn(Ctx ctx, String ownerAlias, String targetAlias, List<ColumnPair> pairs) {
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                ctx.sql.append(" and ");
            }
            ColumnPair pair = pairs.get(i);
            ctx.sql.append(ownerAlias).append('.').append(dialect.quote(pair.fkColumn()))
                    .append(" = ")
                    .append(targetAlias).append('.').append(dialect.quote(pair.targetColumn()));
        }
    }

    /**
     * owning to-one 관계의 join ON 컬럼 짝들. 단일키는 {@code fk = target.id} 한 짝, 복합키 타겟은 각 FK
     * 컬럼을 참조 {@code @Id} 컴포넌트 컬럼과 짝지어 순서대로 담는다(read/write/DDL과 동일 순서 소스).
     */
    private static List<ColumnPair> joinColumnPairs(PersistentProperty relation, EntityMetadata<?> target) {
        if (relation.isCompositeToOne()) {
            List<ColumnPair> pairs = new ArrayList<>();
            for (ToOneForeignKeyColumn fk : relation.toOneForeignKey().columns()) {
                pairs.add(new ColumnPair(fk.columnName(), fk.referencedColumnName()));
            }
            return List.copyOf(pairs);
        }
        return List.of(new ColumnPair(relation.columnName(), target.idProperty().columnName()));
    }

    // ----------------------------------------------------------------------------------------
    // SELECT list
    // ----------------------------------------------------------------------------------------

    private void renderSelectionList(
            Ctx ctx, List<SelectItem> items, int[] columns, List<TranslatedSql.ResultSlot> slots) {
        for (SelectItem item : items) {
            if (item.isEntity()) {
                throw new JpqlException("Entity selection (SELECT " + item.entityAlias()
                        + ") is not handled by the scalar builder; this is an internal routing error");
            }
            if (item.isConstructor()) {
                // NEW 생성자 프로젝션: 각 인자를 스칼라 컬럼으로 투영한다(인스턴스화는 JpqlQuery가 담당). 복합키
                // to-one은 1:1 컬럼-인자 매핑이 성립하지 않으므로 v1에서는 명확히 거부한다(조용한 축약 대신).
                for (Expression arg : item.constructorCall().arguments()) {
                    if (compositeToOneRef(ctx, arg) != null) {
                        throw new JpqlException("Composite-key to-one association cannot be used as a "
                                + "SELECT NEW constructor argument in v1 (its foreign key spans multiple columns); "
                                + "select its @Id components explicitly instead");
                    }
                    renderColumn(ctx, arg, columns);
                }
            } else {
                renderPlainSelectItem(ctx, item.expression(), columns, slots);
            }
        }
    }

    /**
     * 평범한(비-{@code NEW}) SELECT 항목 1개를 렌더한다. 복합키 타겟 to-one terminal({@code SELECT c.parent})이면
     * {@link #renderCompositeToOneProjection}으로 N개 물리 컬럼 + 논리 슬롯 1개를 만들고, 그 외에는 기존처럼 컬럼
     * 1개 + scalar 슬롯 1개를 만든다.
     */
    private void renderPlainSelectItem(
            Ctx ctx, Expression expr, int[] columns, List<TranslatedSql.ResultSlot> slots) {
        CompositeToOneRef ref = compositeToOneRef(ctx, expr);
        if (ref != null) {
            renderCompositeToOneProjection(ctx, ref, columns, slots);
            return;
        }
        int start = columns[0];
        renderColumn(ctx, expr, columns);
        slots.add(new TranslatedSql.ResultSlot(start, 1, null));
    }

    /**
     * 복합키 타겟 to-one terminal 투영({@code SELECT c.parent}): 참조 {@code @Id} 컴포넌트 순서대로(canonical
     * {@code ToOneForeignKey} 순서) 각 FK 컬럼을 {@code alias."fkcol" as "cK"}로 렌더하고, 그 물리 컬럼 구간을
     * 논리 슬롯 1개로 묶는다({@code compositeFk}가 채워짐). read 경로({@link JpqlQuery})가 같은 순서로 디코드해
     * id-stub을 조립하므로, 여기서 순서를 재정렬하면 silent corruption이 된다.
     */
    private void renderCompositeToOneProjection(
            Ctx ctx, CompositeToOneRef ref, int[] columns, List<TranslatedSql.ResultSlot> slots) {
        int start = columns[0];
        List<ToOneForeignKeyColumn> fkColumns = ref.property().toOneForeignKey().columns();
        for (ToOneForeignKeyColumn fkColumn : fkColumns) {
            if (columns[0] > 0) {
                ctx.sql.append(", ");
            }
            appendCompositeColumn(ctx, ref.alias(), fkColumn);
            ctx.sql.append(" as ").append(dialect.quote(JpqlQuery.columnLabel(columns[0])));
            columns[0]++;
        }
        slots.add(new TranslatedSql.ResultSlot(start, fkColumns.size(), ref.property().toOneForeignKey()));
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

    /**
     * GROUP BY 절 본문. 각 경로가 복합키 타겟 to-one terminal이면 {@link #compositeToOneRef}로 감지해
     * canonical FK 컬럼 순서({@code toOneForeignKey().columns()})대로 N개 컬럼으로 전개하고, 아니면 기존
     * 단일 컬럼 해석({@link #pathColumn})을 쓴다. 복합 item 1개가 N개 컬럼으로 늘어날 수 있어 콤마는
     * item 인덱스가 아니라 실제 emit되는 컬럼 스트림 기준 단일 플래그로 관리한다.
     */
    private void renderGroupByBody(Ctx ctx, List<Expression.Path> groupBy) {
        boolean first = true;
        for (Expression.Path p : groupBy) {
            CompositeToOneRef ref = compositeToOneRef(ctx, p);
            if (ref != null) {
                for (ToOneForeignKeyColumn column : ref.property().toOneForeignKey().columns()) {
                    if (!first) {
                        ctx.sql.append(", ");
                    }
                    first = false;
                    appendCompositeColumn(ctx, ref.alias(), column);
                }
            } else {
                if (!first) {
                    ctx.sql.append(", ");
                }
                first = false;
                ctx.sql.append(pathColumn(ctx, p));
            }
        }
    }

    /**
     * ORDER BY 절 본문. 복합키 타겟 to-one terminal이면 canonical FK 컬럼 순서로 전개하며, 모든 컴포넌트에
     * 항목의 동일 방향({@code asc}/{@code desc})을 붙인다. 최종 소유 별칭은 다중세그먼트 path의 owner alias인
     * {@link CompositeToOneRef#alias()}를 쓴다({@code OrderItem.expression().alias()}가 아니다). 그 외는
     * 기존 {@link #renderExpression} + 방향 렌더를 쓴다. 콤마는 GROUP BY와 동일하게 컬럼 스트림 기준 플래그로 관리한다.
     */
    private void renderOrderByBody(Ctx ctx, List<OrderItem> orderBy) {
        boolean first = true;
        for (OrderItem item : orderBy) {
            String dir = item.ascending() ? " asc" : " desc";
            CompositeToOneRef ref = compositeToOneRef(ctx, item.expression());
            if (ref != null) {
                for (ToOneForeignKeyColumn column : ref.property().toOneForeignKey().columns()) {
                    if (!first) {
                        ctx.sql.append(", ");
                    }
                    first = false;
                    appendCompositeColumn(ctx, ref.alias(), column);
                    ctx.sql.append(dir);
                }
            } else {
                if (!first) {
                    ctx.sql.append(", ");
                }
                first = false;
                renderExpression(ctx, item.expression());
                ctx.sql.append(dir);
            }
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
            case Predicate.Comparison c -> renderComparison(ctx, c);
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
            case Predicate.Between b -> renderBetween(ctx, b);
            case Predicate.Null n -> renderNull(ctx, n);
            case Predicate.InList in -> renderInList(ctx, in);
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

    /**
     * 비교 술어를 렌더한다. 한쪽이 복합키 타겟 to-one terminal({@code c.parent})이고 다른 쪽이 파라미터/리터럴
     * (참조 엔티티)이면 각 FK 컴포넌트 비교로 전개한다({@code =}→컴포넌트 eq의 and, {@code <>}→컴포넌트 neq의 or).
     * 그 외는 기존 좌/우 식 렌더로 처리한다.
     */
    private void renderComparison(Ctx ctx, Predicate.Comparison c) {
        CompositeToOneRef ref = compositeToOneRef(ctx, c.left());
        if (ref != null) {
            renderCompositeComparison(ctx, ref, c.op(), c.right());
            return;
        }
        ref = compositeToOneRef(ctx, c.right());
        if (ref != null) {
            // 참조가 우변이면 연산 방향을 뒤집는다({@code :x < c.parent} == {@code c.parent > :x}).
            renderCompositeComparison(ctx, ref, flipOperand(c.op()), c.left());
            return;
        }
        renderExpression(ctx, c.left());
        ctx.sql.append(' ').append(c.op().symbol()).append(' ');
        renderExpression(ctx, c.right());
    }

    /** 복합키 to-one 참조가 비교의 우변에 올 때 좌우를 바꾸기 위한 연산자 방향 반전(등치/부등은 대칭). */
    private static ComparisonOp flipOperand(ComparisonOp op) {
        return switch (op) {
            case LT -> ComparisonOp.GT;
            case GT -> ComparisonOp.LT;
            case LE -> ComparisonOp.GE;
            case GE -> ComparisonOp.LE;
            case EQ -> ComparisonOp.EQ;
            case NE -> ComparisonOp.NE;
        };
    }

    /** IS [NOT] NULL 술어. 복합키 to-one terminal이면 모든 FK 컬럼의 IS [NOT] NULL을 and로 전개한다. */
    private void renderNull(Ctx ctx, Predicate.Null n) {
        CompositeToOneRef ref = compositeToOneRef(ctx, n.value());
        if (ref != null) {
            List<ToOneForeignKeyColumn> columns = ref.property().toOneForeignKey().columns();
            ctx.sql.append('(');
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    ctx.sql.append(" and ");
                }
                appendCompositeColumn(ctx, ref.alias(), columns.get(i));
                ctx.sql.append(n.negated() ? " is not null" : " is null");
            }
            ctx.sql.append(')');
            return;
        }
        renderExpression(ctx, n.value());
        ctx.sql.append(n.negated() ? " is not null" : " is null");
    }

    /**
     * BETWEEN 술어. 복합키 to-one terminal이면 {@code parent BETWEEN :lo AND :hi}를
     * {@code (parent >= :lo) and (parent <= :hi)}의 lexicographic 다중컬럼 전개로 렌더한다(NOT BETWEEN은 {@code not (...)}).
     */
    private void renderBetween(Ctx ctx, Predicate.Between b) {
        CompositeToOneRef ref = compositeToOneRef(ctx, b.value());
        if (ref != null) {
            List<ToOneForeignKeyColumn> columns = ref.property().toOneForeignKey().columns();
            JpqlBinding low = compositeComparisonSource(b.low(), ref);
            JpqlBinding high = compositeComparisonSource(b.high(), ref);
            if (b.negated()) {
                ctx.sql.append("not ");
            }
            ctx.sql.append('(');
            renderCompositeOrdering(ctx, ref.alias(), columns, low, ComparisonOp.GE);
            ctx.sql.append(" and ");
            renderCompositeOrdering(ctx, ref.alias(), columns, high, ComparisonOp.LE);
            ctx.sql.append(')');
            return;
        }
        renderExpression(ctx, b.value());
        ctx.sql.append(b.negated() ? " not between " : " between ");
        renderExpression(ctx, b.low());
        ctx.sql.append(" and ");
        renderExpression(ctx, b.high());
    }

    /**
     * IN (리스트) 술어. 복합키 to-one terminal이면 각 참조를 컴포넌트 동등의 {@code and}로 묶고 그 그룹들을
     * {@code or}로 잇는 OR-of-ANDs 다중컬럼 IN으로 전개한다(row-value IN 미지원 dialect 회피). NOT IN은 {@code not (...)}.
     * 빈 리스트는 {@code 1 = 0}(NOT IN은 {@code 1 = 1})로 단락한다.
     */
    private void renderInList(Ctx ctx, Predicate.InList in) {
        CompositeToOneRef ref = compositeToOneRef(ctx, in.value());
        List<Expression> items = in.items();
        if (ref != null) {
            if (items.isEmpty()) {
                ctx.sql.append(in.negated() ? "1 = 1" : "1 = 0");
                return;
            }
            List<ToOneForeignKeyColumn> columns = ref.property().toOneForeignKey().columns();
            if (in.negated()) {
                ctx.sql.append("not ");
            }
            ctx.sql.append('(');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    ctx.sql.append(" or ");
                }
                JpqlBinding source = compositeComparisonSource(items.get(i), ref);
                renderCompositeEquality(ctx, ref.alias(), columns, source, false);
            }
            ctx.sql.append(')');
            return;
        }
        renderExpression(ctx, in.value());
        ctx.sql.append(in.negated() ? " not in (" : " in (");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                ctx.sql.append(", ");
            }
            renderExpression(ctx, items.get(i));
        }
        ctx.sql.append(')');
    }

    /** 식이 복합키 타겟 to-one terminal 경로면 그 참조(별칭+관계)를, 아니면 {@code null}을 돌려준다. */
    private CompositeToOneRef compositeToOneRef(Ctx ctx, Expression expression) {
        if (!(expression instanceof Expression.Path path)) {
            return null;
        }
        ResolvedPath resolved = resolvePath(ctx, path);
        PersistentProperty property = resolved.property();
        if (property.manyToOne() && property.isCompositeToOne()) {
            return new CompositeToOneRef(resolved.alias(), property);
        }
        return null;
    }

    private void renderCompositeComparison(Ctx ctx, CompositeToOneRef ref, ComparisonOp op, Expression other) {
        JpqlBinding source = compositeComparisonSource(other, ref);
        List<ToOneForeignKeyColumn> columns = ref.property().toOneForeignKey().columns();
        switch (op) {
            case EQ -> renderCompositeEquality(ctx, ref.alias(), columns, source, false);
            case NE -> renderCompositeEquality(ctx, ref.alias(), columns, source, true);
            case LT, LE, GT, GE -> renderCompositeOrdering(ctx, ref.alias(), columns, source, op);
        }
    }

    /**
     * {@code =}는 각 FK 컴포넌트 eq의 {@code and}(튜플 동등), {@code <>}는 각 컴포넌트 neq의 {@code or}
     * (튜플 부등)로 전개한다. 각 값은 참조 엔티티에서 해당 {@code @Id} 컴포넌트를 꺼내 저장 표현으로 인코딩한다.
     */
    private void renderCompositeEquality(
            Ctx ctx, String alias, List<ToOneForeignKeyColumn> columns, JpqlBinding source, boolean ne) {
        ctx.sql.append('(');
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ctx.sql.append(ne ? " or " : " and ");
            }
            ToOneForeignKeyColumn column = columns.get(i);
            appendCompositeColumn(ctx, alias, column);
            ctx.sql.append(ne ? " <> " : " = ");
            ctx.bind(new JpqlBinding.Component(source, column));
            ctx.sql.append(marker(ctx));
        }
        ctx.sql.append(')');
    }

    /**
     * {@code <}/{@code <=}/{@code >}/{@code >=}를 컴포넌트 순서(canonical {@code ToOneForeignKey} 순서)에 따른
     * lexicographic 다중컬럼 비교로 전개한다. n개 컴포넌트에 대해 각 항 k는 앞선 컴포넌트들의 동등({@code =})과
     * k번째 컴포넌트의 strict 비교를 {@code and}로 잇고, 그 항들을 {@code or}로 묶는다. {@code <=}/{@code >=}는
     * 마지막 컴포넌트만 non-strict로 두어 튜플 동등 케이스를 포함한다.
     */
    private void renderCompositeOrdering(
            Ctx ctx, String alias, List<ToOneForeignKeyColumn> columns, JpqlBinding source, ComparisonOp op) {
        String strict = (op == ComparisonOp.LT || op == ComparisonOp.LE) ? "<" : ">";
        boolean orEqualLast = op == ComparisonOp.LE || op == ComparisonOp.GE;
        int n = columns.size();
        ctx.sql.append('(');
        for (int k = 0; k < n; k++) {
            if (k > 0) {
                ctx.sql.append(" or ");
            }
            ctx.sql.append('(');
            for (int j = 0; j < k; j++) {
                appendCompositeColumn(ctx, alias, columns.get(j));
                ctx.sql.append(" = ");
                ctx.bind(new JpqlBinding.Component(source, columns.get(j)));
                ctx.sql.append(marker(ctx));
                ctx.sql.append(" and ");
            }
            appendCompositeColumn(ctx, alias, columns.get(k));
            String cmp = (k == n - 1 && orEqualLast) ? strict + "=" : strict;
            ctx.sql.append(' ').append(cmp).append(' ');
            ctx.bind(new JpqlBinding.Component(source, columns.get(k)));
            ctx.sql.append(marker(ctx));
            ctx.sql.append(')');
        }
        ctx.sql.append(')');
    }

    /** 복합키 to-one 비교의 우변(참조 엔티티) 바인딩 소스. 파라미터/리터럴만 허용한다. */
    private static JpqlBinding compositeComparisonSource(Expression other, CompositeToOneRef ref) {
        return switch (other) {
            case Expression.NamedParameter p -> new JpqlBinding.Named(p.name());
            case Expression.PositionalParameter p -> new JpqlBinding.Positional(p.position());
            case Expression.Literal l -> new JpqlBinding.Literal(l.value());
            default -> throw new JpqlException("Composite-key to-one '" + ref.property().propertyName()
                    + "' can only be compared to a bound parameter or entity reference literal, not "
                    + other.getClass().getSimpleName());
        };
    }

    private void appendCompositeColumn(Ctx ctx, String alias, ToOneForeignKeyColumn column) {
        String col = dialect.quote(column.columnName());
        ctx.sql.append(ctx.qualify ? alias + "." + col : col);
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
        return columnOf(ctx, resolvePath(ctx, path));
    }

    /**
     * 경로를 최종 소유 별칭/프로퍼티까지 해석한다(중간 to-one 세그먼트는 묵시 INNER JOIN으로 전개하며, 복합키
     * 타겟이면 각 레벨에서 다중컬럼 ON을 만든다). 최종 세그먼트가 복합키 to-one이어도 예외를 던지지 않고 그대로
     * 반환한다 — 단일 컬럼 축약 여부는 호출부가 결정한다({@link #columnOf}는 fail-fast, 비교/IS NULL은 컴포넌트 전개).
     */
    private ResolvedPath resolvePath(Ctx ctx, Expression.Path path) {
        EntityMetadata<?> ownerMeta = ctx.scope.resolve(path.alias());
        List<String> segments = path.segments();
        if (segments.isEmpty()) {
            // 순수 별칭 → 그 엔티티의 대표 id property(COUNT(e) 등).
            return new ResolvedPath(path.alias(), ownerMeta, ownerMeta.idProperty());
        }
        // 다세그먼트: 묵시 조인이 필요하므로 unqualified(벌크) 컨텍스트에서는 지원하지 않는다.
        if (segments.size() > 1 && !ctx.qualify) {
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
            // 중간 세그먼트가 복합키 to-one이면 implicitJoin이 모든 컴포넌트 짝을 ON에 렌더한다(각 레벨 복합 ON).
            currentAlias = implicitJoin(ctx, currentAlias, segment, prop, targetMeta);
            currentMeta = targetMeta;
        }
        String field = segments.get(segments.size() - 1);
        EntityMetadata<?> finalMeta = currentMeta;
        PersistentProperty property = finalMeta.findProperty(field)
                .orElseThrow(() -> new JpqlException("Unknown field '" + field + "' on entity "
                        + finalMeta.entityType().getSimpleName()));
        return new ResolvedPath(currentAlias, finalMeta, property);
    }

    /** 해석된 경로를 단일 컬럼 참조로. 컬렉션/복합키 to-one은 단일 컬럼 자리에서 fail-fast한다. */
    private String columnOf(Ctx ctx, ResolvedPath resolved) {
        PersistentProperty property = resolved.property();
        if (property.isRelation() && !property.manyToOne()) {
            throw new JpqlException("Path over collection/association field '" + property.propertyName()
                    + "' is not supported; use an explicit JOIN or SIZE(...)");
        }
        if (property.manyToOne() && property.isCompositeToOne()) {
            // 단일 컬럼 자리(SELECT 투영/산술/함수 인자 등)에서 복합키 to-one은 대표 컬럼 하나로 축약할 수 없다
            // — 조용한 오답 대신 거부한다. 비교(= <> < <= > >=)/BETWEEN/IN/IS NULL/GROUP BY/ORDER BY는 각 렌더가
            // 컴포넌트로 전개한다(renderComparison/renderNull/renderBetween/renderInList/renderGroupByBody/renderOrderByBody).
            throw new JpqlException("Reference to composite-key to-one association '" + property.propertyName()
                    + "' as a single column is not supported (its foreign key spans multiple columns); "
                    + "reference its @Id components explicitly instead, not as a SELECT projection");
        }
        EntityMetadata<?> metadata = resolved.metadata();
        if (metadata.hasInheritance() && metadata.inheritance().joined() && !metadata.isInheritanceRoot()) {
            // plain 'alias.field'도 concrete-subtype-root(예: FROM JCar c) 경로로 도달할 수 있다 — TREAT
            // 경로와 동일한 dedupe 함정에 노출되므로 같은 guard를 적용한다.
            requireUnshadowedJoinedColumn(metadata, property);
        }
        String col = dialect.quote(property.columnName());
        return ctx.qualify ? resolved.alias() + "." + col : col;
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
        if (property.manyToOne() && property.isCompositeToOne()) {
            // terminal 단일 세그먼트로 복합키 to-one을 단일 컬럼 자리(SELECT c.parent 등)에서 참조하면 대표 FK
            // 컬럼 하나만 반환돼 조용한 오답이 된다(join의 silent-first-column과 같은 부류). 명확히 거부한다.
            // (WHERE 비교/BETWEEN/IN/IS NULL은 컴포넌트 전개 경로가 별도로 처리한다.)
            throw new JpqlException("Reference to composite-key to-one association '" + field
                    + "' as a single column is not supported (its foreign key spans multiple columns); "
                    + "select its @Id components explicitly instead");
        }
        if (metadata.hasInheritance() && metadata.inheritance().joined() && !metadata.isInheritanceRoot()) {
            requireUnshadowedJoinedColumn(metadata, property);
        }
        String col = dialect.quote(property.columnName());
        return ctx.qualify ? alias + "." + col : col;
    }

    /**
     * JOINED 다형 SELECT의 파생 테이블(select list)은 컬럼명으로 dedupe한다(루트 먼저, 그다음 서브타입을
     * 등록 순서대로 — {@code AbstractSqlRenderer#joinedSelectList} 참고) — 즉 같은 컬럼명이 먼저 emit된
     * 소스의 값만 노출되고, 그 뒤에 같은 이름을 쓰는 다른 소스는 그 값을 조용히 대신 읽는다(비매칭 행에서는
     * NULL). {@code TREAT(... AS Subtype).field}뿐 아니라 concrete-subtype-root의 plain {@code alias.field}
     * (예: {@code FROM JCar c} 다음 {@code c.tag})도 같은 함정에 노출된다 — 가리키는 컬럼이 실제로 이
     * 서브타입 자기 테이블의 기여분이 아니라 루트나 더 먼저 등록된 형제 서브타입의 동명 컬럼에 가려진 것이면,
     * silent wrong-column(비매칭 행에서 NULL) 대신 명확히 거부한다.
     */
    private void requireUnshadowedJoinedColumn(EntityMetadata<?> subMeta, PersistentProperty property) {
        String columnName = property.columnName();
        InheritanceLayout layout = resolver.inheritanceLayout(subMeta.inheritance().root());
        // 루트(또는 @MappedSuperclass 조상)가 선언해 상속받은 필드는 애초에 root table column 자체이므로
        // 충돌이 아니다 — 이 값은 이 서브타입의 "자기 테이블 기여분"이 아니라 root에서 오는 게 정답이다.
        boolean inheritedFromRoot = layout.rootTableColumns().stream()
                .anyMatch(rootProp -> rootProp.propertyName().equals(property.propertyName()));
        if (inheritedFromRoot) {
            return;
        }
        String ref = subMeta.entityType().getSimpleName() + "." + property.propertyName();
        for (PersistentProperty rootProp : layout.rootTableColumns()) {
            if (rootProp.columnName().equals(columnName)) {
                throw new JpqlException("'" + ref + "' refers to column '" + columnName + "', which collides "
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
                    throw new JpqlException("'" + ref + "' refers to column '" + columnName + "', which collides "
                            + "with a same-named column already contributed by '"
                            + subtype.metadata().entityType().getSimpleName() + "' earlier in the JOINED derived "
                            + "table; the derived SELECT exposes only the first-registered source's value for "
                            + "that column name. Rename the column to disambiguate.");
                }
            }
        }
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
        ImplicitJoin ij = new ImplicitJoin(alias, ownerAlias, targetMeta, joinColumnPairs(prop, targetMeta));
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

    /**
     * {@code TYPE(alias)} → 별칭 엔티티의 discriminator 컬럼 참조. SINGLE_TABLE/JOINED/TABLE_PER_CLASS
     * 모두 지원한다 — JOINED/TPC는 discriminator가 다형 SELECT 파생 테이블의 컬럼으로 노출되므로(루트에서
     * 항상 한 번만 emit되어 컬럼명 충돌이 없다) SINGLE_TABLE과 동일하게 참조할 수 있다.
     */
    private String discriminatorColumnRef(Ctx ctx, String alias) {
        EntityMetadata<?> meta = ctx.scope.resolve(alias);
        requirePolymorphic(meta, "TYPE(" + alias + ")");
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

    /** downcast 대상 서브타입 메타데이터를 해석하고 같은 상속 계층인지 검증한다(3전략 모두 허용). */
    private EntityMetadata<?> resolveTreatSubtype(EntityMetadata<?> baseMeta, String subtypeName) {
        requirePolymorphic(baseMeta, "TREAT");
        EntityMetadata<?> subMeta = resolver.resolve(subtypeName);
        if (!subMeta.inheritance().present() || !subMeta.inheritance().sameHierarchy(baseMeta.inheritance())) {
            throw new JpqlException("TREAT target '" + subtypeName + "' is not a subtype in the same inheritance "
                    + "hierarchy as '" + baseMeta.entityType().getSimpleName() + "'");
        }
        return subMeta;
    }

    private static void requirePolymorphic(EntityMetadata<?> meta, String context) {
        if (!meta.hasInheritance()) {
            throw new JpqlException(context + " requires an @Inheritance entity; '"
                    + meta.entityType().getSimpleName() + "' is not polymorphic");
        }
    }

    /**
     * 구체 서브타입 루트와 TREAT downcast가 유도하는 discriminator 제한을 수집한다. (별칭, discriminator 값)
     * 기준으로 dedupe해 같은 제한을 중복 렌더하지 않는다.
     */
    private List<DiscriminatorConstraint> collectDiscriminatorConstraints(Ctx ctx, JpqlStatement.Select select) {
        LinkedHashMap<String, DiscriminatorConstraint> byKey = new LinkedHashMap<>();
        EntityMetadata<?> rootMeta = ctx.scope.resolve(select.rootAlias());
        if (rootMeta.hasInheritance() && !rootMeta.isInheritanceRoot()) {
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

    /** 다세그먼트 경로에서 자동 생성한 INNER JOIN. 복합키 타겟이면 {@code columnPairs}가 N개 짝을 담는다. */
    private static final class ImplicitJoin {
        final String alias;
        final String ownerAlias;
        final EntityMetadata<?> targetMeta;
        final List<ColumnPair> columnPairs;

        ImplicitJoin(String alias, String ownerAlias, EntityMetadata<?> targetMeta, List<ColumnPair> columnPairs) {
            this.alias = alias;
            this.ownerAlias = ownerAlias;
            this.targetMeta = targetMeta;
            this.columnPairs = columnPairs;
        }
    }

    /** join ON 한 짝: owner 테이블 FK 컬럼 ↔ target 테이블 참조 컬럼(단일키는 target @Id). */
    private record ColumnPair(String fkColumn, String targetColumn) {
    }

    /** 경로 해석 결과: 최종 세그먼트가 위치한 별칭, 그 소유 메타데이터, 그 프로퍼티. */
    private record ResolvedPath(String alias, EntityMetadata<?> metadata, PersistentProperty property) {
    }

    /** 복합키 to-one terminal 비교/IS NULL 대상: FK가 걸린 별칭과 그 관계 프로퍼티. */
    private record CompositeToOneRef(String alias, PersistentProperty property) {
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
