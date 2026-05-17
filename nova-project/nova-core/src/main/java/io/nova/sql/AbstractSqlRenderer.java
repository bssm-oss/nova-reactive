package io.nova.sql;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.query.ComparisonOperator;
import io.nova.query.CompoundPredicate;
import io.nova.query.Condition;
import io.nova.query.Predicate;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 단일 테이블 CRUD와 단순 criteria 렌더링을 위한 기본 SQL 렌더러다.
 */
public abstract class AbstractSqlRenderer implements SqlRenderer {
    private final Dialect dialect;

    protected AbstractSqlRenderer(Dialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public SqlStatement insert(EntityMetadata<?> metadata, Object entity) {
        List<PersistentProperty> properties = metadata.insertableProperties();
        List<String> columns = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        List<Object> bindings = new ArrayList<>();
        for (int index = 0; index < properties.size(); index++) {
            PersistentProperty property = properties.get(index);
            columns.add(column(property));
            markers.add(dialect.bindMarkers().marker(index + 1));
            bindings.add(property.toColumnValue(property.read(entity)));
        }
        String sql = "insert into " + table(metadata) +
                " (" + String.join(", ", columns) + ") values (" + String.join(", ", markers) + ")" +
                insertSuffix(metadata);
        return new SqlStatement(sql, bindings);
    }

    @Override
    public SqlStatement update(EntityMetadata<?> metadata, Object entity) {
        List<PersistentProperty> properties = metadata.updatableProperties();
        List<String> assignments = new ArrayList<>();
        List<Object> bindings = new ArrayList<>();
        int index = 1;
        for (PersistentProperty property : properties) {
            assignments.add(column(property) + " = " + dialect.bindMarkers().marker(index++));
            bindings.add(property.toColumnValue(property.read(entity)));
        }
        bindings.add(metadata.idProperty().read(entity));
        String sql = "update " + table(metadata) + " set " + String.join(", ", assignments) +
                " where " + column(metadata.idProperty()) + " = " + dialect.bindMarkers().marker(index);
        return new SqlStatement(sql, bindings);
    }

    @Override
    public SqlStatement deleteById(EntityMetadata<?> metadata, Object id) {
        return new SqlStatement(
                "delete from " + table(metadata) + " where " + column(metadata.idProperty()) + " = " + dialect.bindMarkers().marker(1),
                List.of(id)
        );
    }

    @Override
    public SqlStatement selectById(EntityMetadata<?> metadata, Object id) {
        return new SqlStatement(
                "select " + selectList(metadata) + " from " + table(metadata) + " where " + column(metadata.idProperty()) + " = " + dialect.bindMarkers().marker(1),
                List.of(id)
        );
    }

    @Override
    public SqlStatement select(EntityMetadata<?> metadata, QuerySpec querySpec) {
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("select ")
                .append(selectList(metadata))
                .append(" from ")
                .append(table(metadata));
        appendWhereClause(sql, context, metadata, querySpec.predicate());
        appendOrderBy(sql, metadata, querySpec.sort());
        appendPage(sql, context, querySpec);
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement count(EntityMetadata<?> metadata, QuerySpec querySpec) {
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("select count(*) as count from ").append(table(metadata));
        appendWhereClause(sql, context, metadata, querySpec.predicate());
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement exists(EntityMetadata<?> metadata, QuerySpec querySpec) {
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("select 1 from ").append(table(metadata));
        appendWhereClause(sql, context, metadata, querySpec.predicate());
        sql.append(" limit 1");
        return new SqlStatement(sql.toString(), context.bindings());
    }

    /**
     * pageable 명세가 있으면 limit/offset용 bind marker를 SQL 뒤에 추가한다.
     */
    protected void appendPage(StringBuilder sql, RenderContext context, QuerySpec querySpec) {
        if (querySpec.pageable() == null) {
            return;
        }
        sql.append(" limit ").append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(querySpec.pageable().limit());
        sql.append(" offset ").append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(querySpec.pageable().offset());
    }

    /**
     * dialect가 returning 같은 추가 insert 구문을 붙여야 할 때 재정의하는 확장 지점이다.
     */
    protected String insertSuffix(EntityMetadata<?> metadata) {
        return "";
    }

    private void appendWhereClause(StringBuilder sql, RenderContext context, EntityMetadata<?> metadata, Predicate predicate) {
        if (predicate == null) {
            return;
        }
        sql.append(" where ").append(renderPredicate(context, metadata, predicate));
    }

    private String renderPredicate(RenderContext context, EntityMetadata<?> metadata, Predicate predicate) {
        if (predicate instanceof Condition condition) {
            PersistentProperty property = findProperty(metadata, condition.property());
            ComparisonOperator operator = condition.operator();
            if (operator == ComparisonOperator.IS_NULL || operator == ComparisonOperator.IS_NOT_NULL) {
                return column(property) + " " + operator.sql();
            }
            String marker = dialect.bindMarkers().marker(context.nextIndex());
            context.addBinding(property.toColumnValue(condition.value()));
            return column(property) + " " + operator.sql() + " " + marker;
        }
        CompoundPredicate compound = (CompoundPredicate) predicate;
        return compound.predicates().stream()
                .map(child -> "(" + renderPredicate(context, metadata, child) + ")")
                .collect(Collectors.joining(" " + compound.operator().name().toLowerCase() + " "));
    }

    private void appendOrderBy(StringBuilder sql, EntityMetadata<?> metadata, Sort sort) {
        if (sort == null || sort.orders().isEmpty()) {
            return;
        }
        String orderBy = sort.orders().stream()
                .map(order -> {
                    PersistentProperty property = findProperty(metadata, order.property());
                    return column(property) + " " + order.direction().name().toLowerCase();
                })
                .collect(Collectors.joining(", "));
        sql.append(" order by ").append(orderBy);
    }

    private PersistentProperty findProperty(EntityMetadata<?> metadata, String propertyName) {
        return metadata.properties().stream()
                .filter(property -> property.propertyName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown property " + propertyName + " on " + metadata.entityType().getName()));
    }

    protected String table(EntityMetadata<?> metadata) {
        return dialect.quote(metadata.tableName());
    }

    protected String column(PersistentProperty property) {
        return dialect.quote(property.columnName());
    }

    private String selectList(EntityMetadata<?> metadata) {
        return metadata.properties().stream()
                .map(property -> column(property) + " as " + dialect.quote(property.columnName()))
                .collect(Collectors.joining(", "));
    }

    protected static final class RenderContext {
        private final List<Object> bindings = new ArrayList<>();

        int nextIndex() {
            return bindings.size() + 1;
        }

        void addBinding(Object value) {
            bindings.add(value);
        }

        List<Object> bindings() {
            return bindings;
        }
    }
}
