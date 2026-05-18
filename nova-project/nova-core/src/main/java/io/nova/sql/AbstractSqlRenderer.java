package io.nova.sql;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.query.ComparisonOperator;
import io.nova.query.CompoundPredicate;
import io.nova.query.Condition;
import io.nova.query.NegationPredicate;
import io.nova.query.Predicate;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
        List<String> assignments = new ArrayList<>();
        List<Object> bindings = new ArrayList<>();
        int index = 1;
        for (PersistentProperty property : properties) {
            assignments.add(column(property) + " = " + dialect.bindMarkers().marker(index++));
            if (versionProperty != null && property.equals(versionProperty)) {
                Object current = property.read(entity);
                Object next = nextVersion(property, current);
                bindings.add(property.toColumnValue(next));
            } else {
                bindings.add(property.toColumnValue(property.read(entity)));
            }
        }
        bindings.add(metadata.idProperty().read(entity));
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(String.join(", ", assignments))
                .append(" where ").append(column(metadata.idProperty()))
                .append(" = ").append(dialect.bindMarkers().marker(index++));
        if (versionProperty != null) {
            sql.append(" and ").append(column(versionProperty))
                    .append(" = ").append(dialect.bindMarkers().marker(index));
            bindings.add(versionProperty.toColumnValue(versionProperty.read(entity)));
        }
        return new SqlStatement(sql.toString(), bindings);
    }

    @Override
    public SqlStatement update(EntityMetadata<?> metadata, Object entity, Iterable<String> fields) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(fields, "fields must not be null");

        LinkedHashSet<String> dedupedFields = new LinkedHashSet<>();
        for (String field : fields) {
            Objects.requireNonNull(field, "field name must not be null");
            dedupedFields.add(field);
        }
        if (dedupedFields.isEmpty()) {
            throw new IllegalArgumentException("update requires at least one field");
        }

        PersistentProperty idProperty = metadata.idProperty();
        String idPropertyName = idProperty.propertyName();

        List<PersistentProperty> properties = new ArrayList<>(dedupedFields.size());
        for (String fieldName : dedupedFields) {
            if (fieldName.equals(idPropertyName)) {
                throw new IllegalArgumentException("Cannot update id property: " + fieldName);
            }
            properties.add(findProperty(metadata, fieldName));
        }

        List<String> assignments = new ArrayList<>(properties.size());
        List<Object> bindings = new ArrayList<>(properties.size() + 1);
        int index = 1;
        for (PersistentProperty property : properties) {
            assignments.add(column(property) + " = " + dialect.bindMarkers().marker(index++));
            bindings.add(property.toColumnValue(property.read(entity)));
        }
        bindings.add(idProperty.read(entity));
        String sql = "update " + table(metadata) + " set " + String.join(", ", assignments) +
                " where " + column(idProperty) + " = " + dialect.bindMarkers().marker(index);
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
    public SqlStatement deleteByEntity(EntityMetadata<?> metadata, Object entity) {
        PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
        if (versionProperty == null) {
            return deleteById(metadata, metadata.idProperty().read(entity));
        }
        List<Object> bindings = new ArrayList<>(2);
        bindings.add(metadata.idProperty().read(entity));
        bindings.add(versionProperty.toColumnValue(versionProperty.read(entity)));
        String sql = "delete from " + table(metadata)
                + " where " + column(metadata.idProperty()) + " = " + dialect.bindMarkers().marker(1)
                + " and " + column(versionProperty) + " = " + dialect.bindMarkers().marker(2);
        return new SqlStatement(sql, bindings);
    }

    /**
     * 현재 버전 값에 1을 더한 다음 값을 계산한다. {@code Long}, {@code Integer}, {@code Short}만
     * 지원하며 {@code null}은 0으로 간주해 1을 반환한다.
     */
    protected Object nextVersion(PersistentProperty versionProperty, Object current) {
        Class<?> type = versionProperty.javaType();
        if (type == Long.class) {
            long value = current == null ? 0L : ((Number) current).longValue();
            return value + 1L;
        }
        if (type == Integer.class) {
            int value = current == null ? 0 : ((Number) current).intValue();
            return value + 1;
        }
        if (type == Short.class) {
            short value = current == null ? (short) 0 : ((Number) current).shortValue();
            return (short) (value + 1);
        }
        throw new IllegalStateException("Unsupported version type " + type.getName());
    }

    @Override
    public SqlStatement deleteByIds(EntityMetadata<?> metadata, List<Object> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("deleteByIds requires at least one id");
        }
        PersistentProperty idProperty = metadata.idProperty();
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("delete from ").append(table(metadata))
                .append(" where ").append(column(idProperty)).append(" in (");
        for (int i = 0; i < ids.size(); i++) {
            Object id = ids.get(i);
            if (id == null) {
                throw new IllegalArgumentException("deleteByIds id at index " + i + " is null");
            }
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(idProperty.toColumnValue(id));
        }
        sql.append(")");
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement deleteByQuery(EntityMetadata<?> metadata, QuerySpec querySpec) {
        if (querySpec.predicate() == null) {
            throw new IllegalArgumentException("deleteByQuery requires a non-null predicate");
        }
        if (querySpec.sort() != null && !querySpec.sort().orders().isEmpty()) {
            throw new IllegalArgumentException("deleteByQuery does not support sort");
        }
        if (querySpec.pageable() != null) {
            throw new IllegalArgumentException("deleteByQuery does not support pageable");
        }
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("delete from ").append(table(metadata));
        appendWhereClause(sql, context, metadata, querySpec.predicate());
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement updateByQuery(
            EntityMetadata<?> metadata,
            LinkedHashMap<String, Object> fieldValues,
            QuerySpec querySpec
    ) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            throw new IllegalArgumentException("updateByQuery requires at least one field assignment");
        }
        if (querySpec == null || querySpec.predicate() == null) {
            throw new IllegalArgumentException("updateByQuery requires a non-null where predicate");
        }
        if (querySpec.sort() != null && !querySpec.sort().orders().isEmpty()) {
            throw new IllegalArgumentException("updateByQuery does not support sort");
        }
        if (querySpec.pageable() != null) {
            throw new IllegalArgumentException("updateByQuery does not support pageable");
        }
        RenderContext context = new RenderContext();
        List<String> assignments = new ArrayList<>(fieldValues.size());
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            PersistentProperty property = findProperty(metadata, entry.getKey());
            if (property.id()) {
                throw new IllegalArgumentException(
                        "Cannot update id property " + property.propertyName() + " on " + metadata.entityType().getName());
            }
            String marker = dialect.bindMarkers().marker(context.nextIndex());
            assignments.add(column(property) + " = " + marker);
            context.addBinding(property.toColumnValue(entry.getValue()));
        }
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(String.join(", ", assignments))
                .append(" where ").append(renderPredicate(context, metadata, querySpec.predicate()));
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement softDeleteById(EntityMetadata<?> metadata, Object id, Object deletedAt) {
        PersistentProperty softDeleteProperty = requireSoftDeleteProperty(metadata, "softDeleteById");
        PersistentProperty idProperty = metadata.idProperty();
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(column(softDeleteProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(softDeleteProperty.toColumnValue(deletedAt));
        sql.append(" where ").append(column(idProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(idProperty.toColumnValue(id));
        sql.append(" and ").append(column(softDeleteProperty)).append(" is null");
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement softDeleteByIds(EntityMetadata<?> metadata, List<Object> ids, Object deletedAt) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("softDeleteByIds requires at least one id");
        }
        PersistentProperty softDeleteProperty = requireSoftDeleteProperty(metadata, "softDeleteByIds");
        PersistentProperty idProperty = metadata.idProperty();
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(column(softDeleteProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(softDeleteProperty.toColumnValue(deletedAt));
        sql.append(" where ").append(column(idProperty)).append(" in (");
        for (int i = 0; i < ids.size(); i++) {
            Object id = ids.get(i);
            if (id == null) {
                throw new IllegalArgumentException("softDeleteByIds id at index " + i + " is null");
            }
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(idProperty.toColumnValue(id));
        }
        sql.append(") and ").append(column(softDeleteProperty)).append(" is null");
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement selectById(EntityMetadata<?> metadata, Object id) {
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("select ").append(selectList(metadata))
                .append(" from ").append(table(metadata))
                .append(" where ").append(column(metadata.idProperty())).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(id);
        appendSoftDeleteAlive(sql, metadata, " and ");
        return new SqlStatement(sql.toString(), context.bindings());
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
    public SqlStatement selectProjection(EntityMetadata<?> metadata, List<String> fields, QuerySpec querySpec) {
        Objects.requireNonNull(fields, "fields must not be null");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("selectProjection requires at least one field");
        }
        List<String> projectionColumns = new ArrayList<>(fields.size());
        for (String fieldName : fields) {
            Objects.requireNonNull(fieldName, "field name must not be null");
            PersistentProperty property = findProperty(metadata, fieldName);
            projectionColumns.add(column(property) + " as " + dialect.quote(property.columnName()));
        }
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("select ")
                .append(String.join(", ", projectionColumns))
                .append(" from ")
                .append(table(metadata));
        appendWhereClause(sql, context, metadata, querySpec == null ? null : querySpec.predicate());
        if (querySpec != null) {
            appendOrderBy(sql, metadata, querySpec.sort());
            appendPage(sql, context, querySpec);
        }
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
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        if (predicate == null && softDelete.isEmpty()) {
            return;
        }
        sql.append(" where ");
        if (predicate != null) {
            sql.append(renderPredicate(context, metadata, predicate));
            if (softDelete.isPresent()) {
                sql.append(" and ").append(column(softDelete.get())).append(" is null");
            }
            return;
        }
        sql.append(column(softDelete.get())).append(" is null");
    }

    private void appendSoftDeleteAlive(StringBuilder sql, EntityMetadata<?> metadata, String prefix) {
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        if (softDelete.isEmpty()) {
            return;
        }
        sql.append(prefix).append(column(softDelete.get())).append(" is null");
    }

    private PersistentProperty requireSoftDeleteProperty(EntityMetadata<?> metadata, String operation) {
        return metadata.softDeleteProperty()
                .orElseThrow(() -> new IllegalArgumentException(
                        operation + " requires @SoftDelete on " + metadata.entityType().getName()));
    }

    private String renderPredicate(RenderContext context, EntityMetadata<?> metadata, Predicate predicate) {
        if (predicate instanceof Condition condition) {
            PersistentProperty property = findProperty(metadata, condition.property());
            ComparisonOperator operator = condition.operator();
            if (operator == ComparisonOperator.IS_NULL || operator == ComparisonOperator.IS_NOT_NULL) {
                return column(property) + " " + operator.sql();
            }
            if (operator == ComparisonOperator.IN) {
                return renderInPredicate(context, property, condition.value());
            }
            if (operator == ComparisonOperator.NOT_IN) {
                return renderNotInPredicate(context, property, condition.value());
            }
            if (operator == ComparisonOperator.BETWEEN) {
                return renderBetweenPredicate(context, property, condition.value());
            }
            String marker = dialect.bindMarkers().marker(context.nextIndex());
            context.addBinding(property.toColumnValue(condition.value()));
            return column(property) + " " + operator.sql() + " " + marker;
        }
        if (predicate instanceof NegationPredicate negation) {
            return "not (" + renderPredicate(context, metadata, negation.inner()) + ")";
        }
        CompoundPredicate compound = (CompoundPredicate) predicate;
        return compound.predicates().stream()
                .map(child -> "(" + renderPredicate(context, metadata, child) + ")")
                .collect(Collectors.joining(" " + compound.operator().name().toLowerCase() + " "));
    }

    /**
     * IN 조건을 col in (?, ?, ?) 로 펼친다. 빈 컬렉션은 Hibernate 6.3+/jOOQ와 동일하게
     * {@code 1 = 0}(항상 거짓)으로 치환해 0행 매칭을 보장한다.
     */
    private String renderInPredicate(RenderContext context, PersistentProperty property, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(
                    "IN operator requires a Collection value for property " + property.propertyName());
        }
        if (collection.isEmpty()) {
            return "1 = 0";
        }
        StringBuilder builder = new StringBuilder(column(property)).append(" in (");
        int index = 0;
        for (Object element : collection) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(property.toColumnValue(element));
            index++;
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * NOT IN 조건을 col not in (?, ?, ?) 로 펼친다. 빈 컬렉션은 Hibernate 6.3+/jOOQ와 동일하게
     * {@code 1 = 1}(항상 참)로 치환한다 — "0개 ID 중 어느 것과도 다른 행"은 모든 행에 해당.
     */
    private String renderNotInPredicate(RenderContext context, PersistentProperty property, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(
                    "NOT IN operator requires a Collection value for property " + property.propertyName());
        }
        if (collection.isEmpty()) {
            return "1 = 1";
        }
        StringBuilder builder = new StringBuilder(column(property)).append(" not in (");
        int index = 0;
        for (Object element : collection) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(property.toColumnValue(element));
            index++;
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * BETWEEN 조건을 col between ? and ? 로 펼친다. 양 끝 값 inclusive (SQL 표준).
     */
    private String renderBetweenPredicate(RenderContext context, PersistentProperty property, Object value) {
        if (!(value instanceof List<?> list) || list.size() != 2) {
            throw new IllegalArgumentException(
                    "BETWEEN operator requires a List of exactly two values for property " + property.propertyName());
        }
        String lowMarker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(property.toColumnValue(list.get(0)));
        String highMarker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(property.toColumnValue(list.get(1)));
        return column(property) + " between " + lowMarker + " and " + highMarker;
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
