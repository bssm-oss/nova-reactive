package io.nova.sql;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.InheritanceInfo;
import io.nova.metadata.InheritanceLayout;
import io.nova.metadata.PersistentProperty;
import io.nova.query.AggregateFunction;
import io.nova.query.AggregateSpec;
import io.nova.query.Aggregation;
import io.nova.query.ComparisonOperator;
import io.nova.query.CompoundPredicate;
import io.nova.query.Condition;
import io.nova.query.Cursor;
import io.nova.query.CursorField;
import io.nova.query.LockMode;
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
import java.util.Set;
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
        // SINGLE_TABLE 상속만 단일 테이블에 물리 discriminator 컬럼을 가진다. JOINED는 루트 테이블에서 별도로
        // 기록하고(insertJoinedRoot), TABLE_PER_CLASS는 물리 discriminator 컬럼이 없으므로 여기서 emit하지 않는다.
        if (metadata.hasInheritance() && metadata.inheritance().singleTable()) {
            columns.add(dialect.quote(metadata.inheritance().discriminatorColumn()));
            markers.add(dialect.bindMarkers().marker(properties.size() + 1));
            bindings.add(metadata.inheritance().discriminatorBindValue());
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
        List<String> idClauses = new ArrayList<>();
        for (PersistentProperty idProperty : metadata.idProperties()) {
            idClauses.add(column(idProperty) + " = " + dialect.bindMarkers().marker(index++));
            bindings.add(idProperty.toColumnValue(idProperty.read(entity)));
        }
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(String.join(", ", assignments))
                .append(" where ").append(String.join(" and ", idClauses));
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

        PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
        Set<String> idPropertyNames = metadata.idProperties().stream()
                .map(PersistentProperty::propertyName)
                .collect(Collectors.toSet());

        List<PersistentProperty> properties = new ArrayList<>(dedupedFields.size());
        for (String fieldName : dedupedFields) {
            if (idPropertyNames.contains(fieldName)) {
                throw new IllegalArgumentException("Cannot update id property: " + fieldName);
            }
            properties.add(findProperty(metadata, fieldName));
        }

        List<String> assignments = new ArrayList<>(properties.size());
        List<Object> bindings = new ArrayList<>(properties.size() + 2);
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
        List<String> idClauses = new ArrayList<>();
        for (PersistentProperty idProperty : metadata.idProperties()) {
            idClauses.add(column(idProperty) + " = " + dialect.bindMarkers().marker(index++));
            bindings.add(idProperty.toColumnValue(idProperty.read(entity)));
        }
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(String.join(", ", assignments))
                .append(" where ").append(String.join(" and ", idClauses));
        if (versionProperty != null) {
            sql.append(" and ").append(column(versionProperty))
                    .append(" = ").append(dialect.bindMarkers().marker(index));
            bindings.add(versionProperty.toColumnValue(versionProperty.read(entity)));
        }
        return new SqlStatement(sql.toString(), bindings);
    }

    @Override
    public SqlStatement deleteById(EntityMetadata<?> metadata, Object id) {
        RenderContext context = new RenderContext();
        String where = idPredicateFromIdObject(context, metadata, id);
        return new SqlStatement(
                "delete from " + table(metadata) + " where " + where,
                context.bindings()
        );
    }

    @Override
    public SqlStatement deleteByEntity(EntityMetadata<?> metadata, Object entity) {
        PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
        RenderContext context = new RenderContext();
        String where = idPredicateFromEntity(context, metadata, entity);
        if (versionProperty == null) {
            return new SqlStatement("delete from " + table(metadata) + " where " + where, context.bindings());
        }
        String versionMarker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(versionProperty.toColumnValue(versionProperty.read(entity)));
        String sql = "delete from " + table(metadata)
                + " where " + where
                + " and " + column(versionProperty) + " = " + versionMarker;
        return new SqlStatement(sql, context.bindings());
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
        rejectCompositeId(metadata, "deleteByIds");
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
    public SqlStatement deleteJoinRows(io.nova.metadata.JoinTableDefinition definition, Object ownerId) {
        String sql = "delete from " + dialect.quote(definition.tableName())
                + " where " + dialect.quote(definition.ownerForeignKeyColumn())
                + " = " + dialect.bindMarkers().marker(1);
        return new SqlStatement(sql, List.of(ownerId));
    }

    @Override
    public SqlStatement insertJoinRow(io.nova.metadata.JoinTableDefinition definition, Object ownerId, Object targetId) {
        String sql = "insert into " + dialect.quote(definition.tableName())
                + " (" + dialect.quote(definition.ownerForeignKeyColumn())
                + ", " + dialect.quote(definition.targetForeignKeyColumn()) + ")"
                + " values (" + dialect.bindMarkers().marker(1) + ", " + dialect.bindMarkers().marker(2) + ")";
        return new SqlStatement(sql, List.of(ownerId, targetId));
    }

    @Override
    public SqlStatement selectJoinRows(io.nova.metadata.JoinTableDefinition definition, List<Object> ownerIds) {
        if (ownerIds.isEmpty()) {
            throw new IllegalArgumentException("selectJoinRows requires at least one owner id");
        }
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("select ")
                .append(dialect.quote(definition.ownerForeignKeyColumn())).append(", ")
                .append(dialect.quote(definition.targetForeignKeyColumn()))
                .append(" from ").append(dialect.quote(definition.tableName()))
                .append(" where ").append(dialect.quote(definition.ownerForeignKeyColumn())).append(" in (");
        for (int i = 0; i < ownerIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(ownerIds.get(i));
        }
        sql.append(")");
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement deleteCollectionRows(io.nova.metadata.CollectionTableDefinition definition, Object ownerId) {
        String sql = "delete from " + dialect.quote(definition.tableName())
                + " where " + dialect.quote(definition.ownerForeignKeyColumn())
                + " = " + dialect.bindMarkers().marker(1);
        return new SqlStatement(sql, List.of(ownerId));
    }

    @Override
    public SqlStatement insertCollectionRow(io.nova.metadata.CollectionTableDefinition definition, Object ownerId, Object value) {
        String sql = "insert into " + dialect.quote(definition.tableName())
                + " (" + dialect.quote(definition.ownerForeignKeyColumn())
                + ", " + dialect.quote(definition.valueColumn()) + ")"
                + " values (" + dialect.bindMarkers().marker(1) + ", " + dialect.bindMarkers().marker(2) + ")";
        return new SqlStatement(sql, java.util.Arrays.asList(ownerId, value));
    }

    @Override
    public SqlStatement insertEmbeddableCollectionRow(
            io.nova.metadata.CollectionTableDefinition definition, Object ownerId, List<Object> columnValues) {
        List<io.nova.metadata.CollectionTableDefinition.ElementColumn> columns = definition.elementColumns();
        if (columnValues.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "insertEmbeddableCollectionRow expects " + columns.size()
                            + " column values but got " + columnValues.size());
        }
        RenderContext context = new RenderContext();
        StringBuilder names = new StringBuilder(dialect.quote(definition.ownerForeignKeyColumn()));
        StringBuilder markers = new StringBuilder(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(ownerId);
        for (int i = 0; i < columns.size(); i++) {
            names.append(", ").append(dialect.quote(columns.get(i).columnName()));
            markers.append(", ").append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(columnValues.get(i));
        }
        String sql = "insert into " + dialect.quote(definition.tableName())
                + " (" + names + ") values (" + markers + ")";
        return new SqlStatement(sql, context.bindings());
    }

    @Override
    public SqlStatement selectCollectionRows(io.nova.metadata.CollectionTableDefinition definition, List<Object> ownerIds) {
        if (ownerIds.isEmpty()) {
            throw new IllegalArgumentException("selectCollectionRows requires at least one owner id");
        }
        RenderContext context = new RenderContext();
        StringBuilder projection = new StringBuilder(dialect.quote(definition.ownerForeignKeyColumn()));
        if (definition.embeddable()) {
            for (io.nova.metadata.CollectionTableDefinition.ElementColumn column : definition.elementColumns()) {
                projection.append(", ").append(dialect.quote(column.columnName()));
            }
        } else {
            projection.append(", ").append(dialect.quote(definition.valueColumn()));
        }
        StringBuilder sql = new StringBuilder("select ")
                .append(projection)
                .append(" from ").append(dialect.quote(definition.tableName()))
                .append(" where ").append(dialect.quote(definition.ownerForeignKeyColumn())).append(" in (");
        for (int i = 0; i < ownerIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(ownerIds.get(i));
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
        if (querySpec.cursor() != null) {
            throw new IllegalArgumentException("deleteByQuery does not support cursor");
        }
        if (querySpec.pageable() != null) {
            throw new IllegalArgumentException("deleteByQuery does not support pageable");
        }
        if (querySpec.lockMode() != LockMode.NONE) {
            throw new IllegalArgumentException("deleteByQuery does not support lockMode");
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
        if (querySpec.cursor() != null) {
            throw new IllegalArgumentException("updateByQuery does not support cursor");
        }
        if (querySpec.pageable() != null) {
            throw new IllegalArgumentException("updateByQuery does not support pageable");
        }
        if (querySpec.lockMode() != LockMode.NONE) {
            throw new IllegalArgumentException("updateByQuery does not support lockMode");
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
    public SqlStatement softDeleteByEntity(EntityMetadata<?> metadata, Object entity, Object deletedAt) {
        PersistentProperty softDeleteProperty = requireSoftDeleteProperty(metadata, "softDeleteByEntity");
        rejectCompositeId(metadata, "softDeleteByEntity");
        PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
        if (versionProperty == null) {
            return softDeleteById(metadata, metadata.idProperty().read(entity), deletedAt);
        }
        PersistentProperty idProperty = metadata.idProperty();
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(column(softDeleteProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(softDeleteProperty.toColumnValue(deletedAt));
        Object currentVersion = versionProperty.read(entity);
        Object nextVersionValue = nextVersion(versionProperty, currentVersion);
        sql.append(", ").append(column(versionProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(versionProperty.toColumnValue(nextVersionValue));
        sql.append(" where ").append(column(idProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(idProperty.toColumnValue(idProperty.read(entity)));
        sql.append(" and ").append(column(versionProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(versionProperty.toColumnValue(currentVersion));
        sql.append(" and ").append(column(softDeleteProperty)).append(" is null");
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement softDeleteById(EntityMetadata<?> metadata, Object id, Object deletedAt) {
        PersistentProperty softDeleteProperty = requireSoftDeleteProperty(metadata, "softDeleteById");
        rejectCompositeId(metadata, "softDeleteById");
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
    public SqlStatement softDeleteByQuery(EntityMetadata<?> metadata, QuerySpec querySpec, Object deletedAt) {
        if (querySpec == null || querySpec.predicate() == null) {
            throw new IllegalArgumentException("softDeleteByQuery requires a non-null predicate");
        }
        if (querySpec.sort() != null && !querySpec.sort().orders().isEmpty()) {
            throw new IllegalArgumentException("softDeleteByQuery does not support sort");
        }
        if (querySpec.pageable() != null) {
            throw new IllegalArgumentException("softDeleteByQuery does not support pageable");
        }
        PersistentProperty softDeleteProperty = requireSoftDeleteProperty(metadata, "softDeleteByQuery");
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("update ").append(table(metadata))
                .append(" set ").append(column(softDeleteProperty)).append(" = ")
                .append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(softDeleteProperty.toColumnValue(deletedAt));
        sql.append(" where ").append(renderPredicate(context, metadata, querySpec.predicate()));
        sql.append(" and ").append(column(softDeleteProperty)).append(" is null");
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement softDeleteByIds(EntityMetadata<?> metadata, List<Object> ids, Object deletedAt) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("softDeleteByIds requires at least one id");
        }
        PersistentProperty softDeleteProperty = requireSoftDeleteProperty(metadata, "softDeleteByIds");
        rejectCompositeId(metadata, "softDeleteByIds");
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
                .append(" where ").append(idPredicateFromIdObject(context, metadata, id));
        if (restrictsToSubtype(metadata)) {
            sql.append(" and ").append(discriminatorRestriction(context, metadata));
        }
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
        appendWhereClause(sql, context, metadata, querySpec.predicate(), querySpec.cursor());
        appendOrderBy(sql, metadata, querySpec.sort());
        appendPage(sql, context, querySpec);
        appendLockClause(sql, querySpec);
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
        appendWhereClause(
                sql,
                context,
                metadata,
                querySpec == null ? null : querySpec.predicate(),
                querySpec == null ? null : querySpec.cursor()
        );
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
        sql.append(existsRowLimitClause());
        return new SqlStatement(sql.toString(), context.bindings());
    }

    /**
     * exists()처럼 "한 행이라도 있는지"만 판정할 때 SELECT 끝에 붙일 row-limit 절을 반환한다.
     * 선행 공백을 포함한다. 기본은 표준 {@code LIMIT 1}이며, LIMIT 미지원 dialect(Oracle)는 override 한다.
     */
    protected String existsRowLimitClause() {
        return " limit 1";
    }

    @Override
    public SqlStatement aggregate(EntityMetadata<?> metadata, AggregateSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        if (spec.aggregations().isEmpty()) {
            throw new IllegalArgumentException("aggregate requires at least one Aggregation");
        }
        RenderContext context = new RenderContext();
        List<String> selectItems = new ArrayList<>(spec.aggregations().size() + spec.groupBy().size());
        for (String groupProperty : spec.groupBy()) {
            PersistentProperty property = findProperty(metadata, groupProperty);
            selectItems.add(column(property) + " as " + dialect.quote(property.columnName()));
        }
        for (Aggregation aggregation : spec.aggregations()) {
            PersistentProperty property = findProperty(metadata, aggregation.property());
            selectItems.add(renderAggregate(aggregation, property) + " as " + dialect.quote(aggregation.resolvedAlias()));
        }
        StringBuilder sql = new StringBuilder("select ")
                .append(String.join(", ", selectItems))
                .append(" from ")
                .append(table(metadata));
        appendWhereClause(sql, context, metadata, spec.where());
        if (!spec.groupBy().isEmpty()) {
            List<String> groupColumns = new ArrayList<>(spec.groupBy().size());
            for (String groupProperty : spec.groupBy()) {
                PersistentProperty property = findProperty(metadata, groupProperty);
                groupColumns.add(column(property));
            }
            sql.append(" group by ").append(String.join(", ", groupColumns));
        }
        if (spec.having() != null) {
            sql.append(" having ").append(renderHaving(context, metadata, spec));
        }
        appendOrderBy(sql, metadata, spec.sort(), spec);
        return new SqlStatement(sql.toString(), context.bindings());
    }

    private String renderAggregate(Aggregation aggregation, PersistentProperty property) {
        AggregateFunction function = aggregation.function();
        String columnExpression = column(property);
        return switch (function) {
            case COUNT -> "count(" + columnExpression + ")";
            case COUNT_DISTINCT -> "count(distinct " + columnExpression + ")";
            case SUM -> "sum(" + columnExpression + ")";
            case AVG -> "avg(" + columnExpression + ")";
            case MIN -> "min(" + columnExpression + ")";
            case MAX -> "max(" + columnExpression + ")";
        };
    }

    /**
     * HAVING 절을 렌더한다. predicate가 SELECT 절 집계의 alias를 참조하면 alias 자체가 아니라
     * underlying aggregate expression(예: {@code count(distinct id)})을 다시 출력한다 —
     * ANSI SQL은 HAVING에서 SELECT alias 참조를 의무화하지 않기 때문에, 모든 dialect에서
     * portable한 형태를 만들기 위해 표현 재계산 정책을 채택한다.
     * <p>
     * MySQL과 PostgreSQL은 HAVING에서 SELECT alias 직접 참조를 허용하지만, alias 재참조와
     * 표현 재계산의 의미가 항상 같다는 보장은 dialect/플래너 별로 달라질 수 있고, alias가 동일
     * 이름 컬럼과 충돌할 가능성도 있다. underlying aggregate를 그대로 다시 출력하면 같은 행을
     * 가리키는 동등한 SQL이 만들어지고, optimizer는 두 표현을 같은 집계 결과로 dedup해 비용
     * 차이를 만들지 않는다.
     */
    private String renderHaving(RenderContext context, EntityMetadata<?> metadata, AggregateSpec spec) {
        AggregatePredicateLookup lookup = new AggregatePredicateLookup(spec);
        return renderPredicateWithLookup(context, metadata, spec.having(), lookup);
    }

    /**
     * {@code querySpec.lockMode()}가 {@link LockMode#NONE}이 아니면 dialect 별 pessimistic lock 절을
     * SQL 끝에 덧붙인다. count/exists/findById 같이 행을 직접 fetch 하지 않는 SQL에는 lock 절을 적용하지
     * 않는다 — 이 메서드는 행을 반환하는 SELECT 에만 호출된다.
     */
    protected void appendLockClause(StringBuilder sql, QuerySpec querySpec) {
        LockMode mode = querySpec.lockMode();
        if (mode == null || mode == LockMode.NONE) {
            return;
        }
        sql.append(dialect.lockClause(mode));
    }

    /**
     * pageable 명세가 있으면 limit/offset용 bind marker를 SQL 뒤에 추가한다. cursor가 함께 설정돼
     * 있으면 keyset pagination이 적용된 것이므로 OFFSET은 생략하고 LIMIT만 렌더한다 — cursor
     * 자체가 "어디서부터" 정보를 담고 있어서 offset 누적이 불필요하다.
     */
    protected void appendPage(StringBuilder sql, RenderContext context, QuerySpec querySpec) {
        if (querySpec.pageable() == null) {
            return;
        }
        sql.append(" limit ").append(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(querySpec.pageable().limit());
        if (querySpec.cursor() != null) {
            return;
        }
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
        appendWhereClause(sql, context, metadata, predicate, null);
    }

    /**
     * predicate, cursor keyset 비교, soft-delete-alive 가드를 모두 {@code and}로 결합해 WHERE 절을 만든다.
     * 모두 비어 있으면 WHERE 절 자체를 생략한다. 절들 사이의 순서는
     * {@code predicate -> cursor -> soft delete}로 고정해 SQL 형태를 안정시킨다.
     */
    private void appendWhereClause(
            StringBuilder sql,
            RenderContext context,
            EntityMetadata<?> metadata,
            Predicate predicate,
            Cursor cursor
    ) {
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        boolean restrictSubtype = restrictsToSubtype(metadata);
        if (predicate == null && cursor == null && softDelete.isEmpty() && !restrictSubtype) {
            return;
        }
        List<String> clauses = new ArrayList<>(4);
        if (predicate != null) {
            clauses.add(renderPredicate(context, metadata, predicate));
        }
        if (cursor != null) {
            clauses.add(renderCursorPredicate(context, metadata, cursor));
        }
        if (restrictSubtype) {
            clauses.add(discriminatorRestriction(context, metadata));
        }
        softDelete.ifPresent(property -> clauses.add(column(property) + " is null"));
        sql.append(" where ").append(String.join(" and ", clauses));
    }

    /**
     * SINGLE_TABLE 상속에서 구체 서브타입을 조회/집계할 때 {@code discriminator = value}로 제한해야
     * 하는지 판정한다. 루트(merged 메타데이터 포함)는 다형 조회이므로 제한하지 않는다.
     */
    private boolean restrictsToSubtype(EntityMetadata<?> metadata) {
        // SINGLE_TABLE만 한 테이블을 공유하므로 구체 서브타입 조회 시 discriminator 제한이 필요하다.
        // JOINED 구체 조회는 별도 JOIN 경로(selectJoinedById)로 처리되고, TABLE_PER_CLASS 구체 테이블은
        // 그 자체로 타입 전용이라 제한이 불필요하다.
        return metadata.hasInheritance() && metadata.inheritance().singleTable()
                && !metadata.isInheritanceRoot();
    }

    /**
     * {@code discriminator = value} 제한 절을 만들고 bind 값을 등록한다.
     */
    private String discriminatorRestriction(RenderContext context, EntityMetadata<?> metadata) {
        String marker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(metadata.inheritance().discriminatorBindValue());
        return dialect.quote(metadata.inheritance().discriminatorColumn()) + " = " + marker;
    }

    /**
     * keyset(cursor) pagination의 lexicographic 비교를 SQL로 펼친다. 정렬 키가 {@code (k1, k2, k3)}
     * 이고 각 방향에 따른 부호가 {@code op_i}일 때:
     * <pre>
     * (k1 op1 v1)
     *   OR (k1 = v1 AND k2 op2 v2)
     *   OR (k1 = v1 AND k2 = v2 AND k3 op3 v3)
     * </pre>
     * ASC 키는 {@code >}, DESC 키는 {@code <}를 사용한다. 등호로만 동률이 깨지지 않는 마지막 항목까지
     * 자연스럽게 다음 페이지로 진행한다.
     */
    private String renderCursorPredicate(RenderContext context, EntityMetadata<?> metadata, Cursor cursor) {
        List<CursorField> fields = cursor.fields();
        StringBuilder sql = new StringBuilder("(");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sql.append(" or ");
            }
            sql.append("(");
            for (int j = 0; j <= i; j++) {
                if (j > 0) {
                    sql.append(" and ");
                }
                CursorField field = fields.get(j);
                PersistentProperty property = findProperty(metadata, field.property());
                String marker = dialect.bindMarkers().marker(context.nextIndex());
                context.addBinding(property.toColumnValue(field.lastValue()));
                String operator = j == i ? cursorOperator(field.direction()) : "=";
                sql.append(column(property)).append(" ").append(operator).append(" ").append(marker);
            }
            sql.append(")");
        }
        sql.append(")");
        return sql.toString();
    }

    private static String cursorOperator(Sort.Direction direction) {
        return direction == Sort.Direction.ASC ? ">" : "<";
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
        return renderPredicateWithLookup(context, metadata, predicate, null);
    }

    /**
     * predicate를 SQL 절로 렌더한다. {@code aliasLookup}이 주어지면 condition의 property 이름이
     * 엔티티 property 대신 집계 alias로 먼저 해석된다 — HAVING 절이 집계 결과를 alias로 참조할 때 사용된다.
     */
    private String renderPredicateWithLookup(
            RenderContext context,
            EntityMetadata<?> metadata,
            Predicate predicate,
            AggregatePredicateLookup aliasLookup
    ) {
        if (predicate instanceof Condition condition) {
            ResolvedExpression expression = resolveExpression(metadata, condition.property(), aliasLookup);
            ComparisonOperator operator = condition.operator();
            if (operator == ComparisonOperator.IS_NULL || operator == ComparisonOperator.IS_NOT_NULL) {
                return expression.sqlExpression() + " " + operator.sql();
            }
            if (operator == ComparisonOperator.IN) {
                return renderInExpression(context, expression, condition.value());
            }
            if (operator == ComparisonOperator.NOT_IN) {
                return renderNotInExpression(context, expression, condition.value());
            }
            if (operator == ComparisonOperator.BETWEEN) {
                return renderBetweenExpression(context, expression, condition.value());
            }
            if (operator == ComparisonOperator.ILIKE || operator == ComparisonOperator.NOT_ILIKE) {
                String marker = dialect.bindMarkers().marker(context.nextIndex());
                context.addBinding(expression.toColumnValue(condition.value()));
                return dialect.renderILike(expression.sqlExpression(), marker, operator == ComparisonOperator.NOT_ILIKE);
            }
            String marker = dialect.bindMarkers().marker(context.nextIndex());
            context.addBinding(expression.toColumnValue(condition.value()));
            return expression.sqlExpression() + " " + operator.sql() + " " + marker;
        }
        if (predicate instanceof NegationPredicate negation) {
            return "not (" + renderPredicateWithLookup(context, metadata, negation.inner(), aliasLookup) + ")";
        }
        CompoundPredicate compound = (CompoundPredicate) predicate;
        return compound.predicates().stream()
                .map(child -> "(" + renderPredicateWithLookup(context, metadata, child, aliasLookup) + ")")
                .collect(Collectors.joining(" " + compound.operator().name().toLowerCase() + " "));
    }

    /**
     * predicate condition의 property 이름을 SQL 표현으로 변환한다. {@code aliasLookup}이 있으면
     * 동일 이름의 집계 alias가 먼저 매칭되며, 그 경우 결과 SQL 표현은 {@code count(distinct x)} 같은
     * 집계 함수 호출이고 binding-time converter는 적용하지 않는다.
     */
    private ResolvedExpression resolveExpression(
            EntityMetadata<?> metadata,
            String name,
            AggregatePredicateLookup aliasLookup
    ) {
        if (aliasLookup != null) {
            Aggregation aggregation = aliasLookup.findAggregationByAlias(name);
            if (aggregation != null) {
                PersistentProperty property = findProperty(metadata, aggregation.property());
                return new ResolvedExpression(renderAggregate(aggregation, property), null);
            }
        }
        PersistentProperty property = findProperty(metadata, name);
        return new ResolvedExpression(column(property), property);
    }

    private String renderInExpression(RenderContext context, ResolvedExpression expression, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(
                    "IN operator requires a Collection value for " + expression.sqlExpression());
        }
        if (collection.isEmpty()) {
            return "1 = 0";
        }
        StringBuilder builder = new StringBuilder(expression.sqlExpression()).append(" in (");
        int index = 0;
        for (Object element : collection) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(expression.toColumnValue(element));
            index++;
        }
        builder.append(")");
        return builder.toString();
    }

    private String renderNotInExpression(RenderContext context, ResolvedExpression expression, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(
                    "NOT IN operator requires a Collection value for " + expression.sqlExpression());
        }
        if (collection.isEmpty()) {
            return "1 = 1";
        }
        StringBuilder builder = new StringBuilder(expression.sqlExpression()).append(" not in (");
        int index = 0;
        for (Object element : collection) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(expression.toColumnValue(element));
            index++;
        }
        builder.append(")");
        return builder.toString();
    }

    private String renderBetweenExpression(RenderContext context, ResolvedExpression expression, Object value) {
        if (!(value instanceof List<?> list) || list.size() != 2) {
            throw new IllegalArgumentException(
                    "BETWEEN operator requires a List of exactly two values for " + expression.sqlExpression());
        }
        String lowMarker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(expression.toColumnValue(list.get(0)));
        String highMarker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(expression.toColumnValue(list.get(1)));
        return expression.sqlExpression() + " between " + lowMarker + " and " + highMarker;
    }

    /**
     * resolveExpression의 결과를 SQL 표현과 (있다면) binding converter로 묶은 wrapper다.
     * 집계 표현(예: {@code count(distinct id)})은 binding 측 converter가 없는 raw 값을 그대로 사용한다.
     */
    private record ResolvedExpression(String sqlExpression, PersistentProperty property) {
        Object toColumnValue(Object value) {
            if (property == null) {
                return value;
            }
            return property.toColumnValue(value);
        }
    }

    private static final class AggregatePredicateLookup {
        private final AggregateSpec spec;

        AggregatePredicateLookup(AggregateSpec spec) {
            this.spec = spec;
        }

        Aggregation findAggregationByAlias(String alias) {
            for (Aggregation aggregation : spec.aggregations()) {
                if (aggregation.resolvedAlias().equals(alias)) {
                    return aggregation;
                }
            }
            return null;
        }
    }

    private void appendOrderBy(StringBuilder sql, EntityMetadata<?> metadata, Sort sort) {
        appendOrderBy(sql, metadata, sort, null);
    }

    /**
     * order by 절을 sql 뒤에 추가한다. {@code spec}이 주어지면 order property가 우선 집계 alias로
     * 해석되어 {@code count(distinct x)} 같은 집계 표현 자체로 정렬한다 — 일치하는 alias가 없으면
     * 일반 property 해석으로 폴백한다. {@code spec}이 null이면 일반 property만 허용한다.
     */
    private void appendOrderBy(StringBuilder sql, EntityMetadata<?> metadata, Sort sort, AggregateSpec spec) {
        if (sort == null || sort.orders().isEmpty()) {
            return;
        }
        AggregatePredicateLookup lookup = spec == null ? null : new AggregatePredicateLookup(spec);
        String orderBy = sort.orders().stream()
                .map(order -> {
                    ResolvedExpression expression = resolveExpression(metadata, order.property(), lookup);
                    return expression.sqlExpression() + " " + order.direction().name().toLowerCase();
                })
                .collect(Collectors.joining(", "));
        sql.append(" order by ").append(orderBy);
    }

    private PersistentProperty findProperty(EntityMetadata<?> metadata, String propertyName) {
        return metadata.findProperty(propertyName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown property " + propertyName + " on " + metadata.entityType().getName()));
    }

    protected String table(EntityMetadata<?> metadata) {
        String quotedTable = dialect.quote(metadata.tableName());
        return metadata.schema().isBlank()
                ? quotedTable
                : dialect.quote(metadata.schema()) + "." + quotedTable;
    }

    protected String column(PersistentProperty property) {
        return dialect.quote(property.columnName());
    }

    /**
     * id 동등 비교 WHERE 절을 렌더하고 바인딩을 등록한다 — 단일 키는 {@code c = ?}, {@code @EmbeddedId}
     * 복합키는 {@code c1 = ? and c2 = ?}. 값은 findById/deleteById에 전달된 id 값 객체에서 꺼낸다(단일은
     * 스칼라, 복합은 {@code @Embeddable} holder).
     */
    private String idPredicateFromIdObject(RenderContext context, EntityMetadata<?> metadata, Object idObject) {
        List<PersistentProperty> idProperties = metadata.idProperties();
        List<String> parts = new ArrayList<>(idProperties.size());
        for (PersistentProperty idProperty : idProperties) {
            String marker = dialect.bindMarkers().marker(context.nextIndex());
            context.addBinding(idProperty.toColumnValue(metadata.idColumnValue(idProperty, idObject)));
            parts.add(column(idProperty) + " = " + marker);
        }
        return String.join(" and ", parts);
    }

    /**
     * id 동등 비교 WHERE 절을 렌더하되 값은 entity 인스턴스에서 직접 읽는다(복합키는 컴포넌트별로).
     */
    private String idPredicateFromEntity(RenderContext context, EntityMetadata<?> metadata, Object entity) {
        List<PersistentProperty> idProperties = metadata.idProperties();
        List<String> parts = new ArrayList<>(idProperties.size());
        for (PersistentProperty idProperty : idProperties) {
            String marker = dialect.bindMarkers().marker(context.nextIndex());
            context.addBinding(idProperty.toColumnValue(idProperty.read(entity)));
            parts.add(column(idProperty) + " = " + marker);
        }
        return String.join(" and ", parts);
    }

    /**
     * {@code @EmbeddedId} 복합키가 아직 지원되지 않는 연산에서 명확히 거부한다(조용한 잘못된 SQL 방지).
     * 단일 키 경로는 영향받지 않는다.
     */
    private static void rejectCompositeId(EntityMetadata<?> metadata, String operation) {
        if (metadata.hasCompositeId()) {
            throw new IllegalArgumentException(
                    operation + " does not support @EmbeddedId composite keys on "
                            + metadata.entityType().getName());
        }
    }

    private String selectList(EntityMetadata<?> metadata) {
        List<String> items = new ArrayList<>();
        for (PersistentProperty property : metadata.columnMappedProperties()) {
            items.add(column(property) + " as " + dialect.quote(property.columnName()));
        }
        // SINGLE_TABLE 상속만 단일 테이블에 물리 discriminator 컬럼을 가진다 — row마다 구체 서브타입을
        // 판별하도록 SELECT한다. JOINED/TABLE_PER_CLASS의 다형 조회는 별도 경로(selectJoined*/selectTablePerClass*)가
        // discriminator를 합성/SELECT하므로 단일-테이블 selectList에서는 emit하지 않는다.
        if (metadata.hasInheritance() && metadata.inheritance().singleTable()) {
            String discriminator = metadata.inheritance().discriminatorColumn();
            items.add(dialect.quote(discriminator) + " as " + dialect.quote(discriminator));
        }
        return String.join(", ", items);
    }

    // =============================================================================================
    // @Inheritance(JOINED)
    // =============================================================================================

    @Override
    public SqlStatement insertJoinedRoot(
            EntityMetadata<?> concreteMetadata,
            String rootTableName,
            List<PersistentProperty> rootColumns,
            Object entity) {
        InheritanceInfo info = concreteMetadata.inheritance();
        List<String> columns = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        List<Object> bindings = new ArrayList<>();
        RenderContext context = new RenderContext();
        for (PersistentProperty property : rootColumns) {
            // 루트 테이블 INSERT에서는 DB가 생성하는 id 컬럼(IDENTITY)을 제외한다.
            if (property.id() && EntityMetadata.isDatabaseGeneratedId(property)) {
                continue;
            }
            columns.add(column(property));
            markers.add(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(property.toColumnValue(property.read(entity)));
        }
        columns.add(dialect.quote(info.discriminatorColumn()));
        markers.add(dialect.bindMarkers().marker(context.nextIndex()));
        context.addBinding(info.discriminatorBindValue());
        String sql = "insert into " + qualifiedRootTable(info, rootTableName)
                + " (" + String.join(", ", columns) + ") values (" + String.join(", ", markers) + ")"
                + insertSuffix(concreteMetadata);
        bindings.addAll(context.bindings());
        return new SqlStatement(sql, bindings);
    }

    @Override
    public SqlStatement insertJoinedSubtype(
            EntityMetadata<?> concreteMetadata,
            List<PersistentProperty> ownColumns,
            Object entity) {
        List<String> columns = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        RenderContext context = new RenderContext();
        for (PersistentProperty property : ownColumns) {
            columns.add(column(property));
            markers.add(dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(property.toColumnValue(property.read(entity)));
        }
        String sql = "insert into " + table(concreteMetadata)
                + " (" + String.join(", ", columns) + ") values (" + String.join(", ", markers) + ")";
        return new SqlStatement(sql, context.bindings());
    }

    @Override
    public SqlStatement selectJoinedPolymorphic(InheritanceLayout layout, QuerySpec querySpec) {
        RenderContext context = new RenderContext();
        StringBuilder sql = new StringBuilder("select ").append(joinedSelectList(layout))
                .append(" from ").append(joinedFromClause(layout));
        appendWhereClause(sql, context, layout.rootMetadata(), querySpec.predicate(), querySpec.cursor());
        appendOrderBy(sql, layout.rootMetadata(), querySpec.sort());
        appendPage(sql, context, querySpec);
        appendLockClause(sql, querySpec);
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement selectJoinedById(InheritanceLayout layout, Object id) {
        RenderContext context = new RenderContext();
        InheritanceInfo info = layout.info();
        PersistentProperty idProperty = layout.rootMetadata().idProperty();
        String marker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(idProperty.toColumnValue(id));
        String sql = "select " + joinedSelectList(layout)
                + " from " + joinedFromClause(layout)
                + " where " + qualifiedRootTable(info, info.rootTableName()) + "."
                + dialect.quote(idProperty.columnName()) + " = " + marker;
        return new SqlStatement(sql, context.bindings());
    }

    @Override
    public SqlStatement updateJoinedRoot(
            EntityMetadata<?> concreteMetadata,
            String rootTableName,
            List<PersistentProperty> rootColumns,
            Object entity) {
        InheritanceInfo info = concreteMetadata.inheritance();
        PersistentProperty idProperty = concreteMetadata.idProperty();
        RenderContext context = new RenderContext();
        List<String> assignments = new ArrayList<>();
        for (PersistentProperty property : rootColumns) {
            if (property.id()) {
                continue;
            }
            assignments.add(column(property) + " = " + dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(property.toColumnValue(property.read(entity)));
        }
        String idMarker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(idProperty.toColumnValue(idProperty.read(entity)));
        String sql = "update " + qualifiedRootTable(info, rootTableName)
                + " set " + String.join(", ", assignments)
                + " where " + dialect.quote(idProperty.columnName()) + " = " + idMarker;
        return new SqlStatement(sql, context.bindings());
    }

    @Override
    public SqlStatement updateJoinedSubtype(
            EntityMetadata<?> concreteMetadata,
            List<PersistentProperty> ownColumns,
            Object entity) {
        PersistentProperty idProperty = concreteMetadata.idProperty();
        RenderContext context = new RenderContext();
        List<String> assignments = new ArrayList<>();
        for (PersistentProperty property : ownColumns) {
            // ownColumns 맨 앞 id(FK)는 SET 대상이 아니라 WHERE 대상이다.
            if (property.id()) {
                continue;
            }
            assignments.add(column(property) + " = " + dialect.bindMarkers().marker(context.nextIndex()));
            context.addBinding(property.toColumnValue(property.read(entity)));
        }
        if (assignments.isEmpty()) {
            // 서브타입 자기 컬럼이 없으면 갱신할 것이 없다 — 호출자가 이 단계를 건너뛴다.
            return null;
        }
        String idMarker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(idProperty.toColumnValue(idProperty.read(entity)));
        String sql = "update " + table(concreteMetadata)
                + " set " + String.join(", ", assignments)
                + " where " + dialect.quote(idProperty.columnName()) + " = " + idMarker;
        return new SqlStatement(sql, context.bindings());
    }

    @Override
    public SqlStatement deleteJoinedSubtypeById(EntityMetadata<?> concreteMetadata, Object id) {
        PersistentProperty idProperty = concreteMetadata.idProperty();
        RenderContext context = new RenderContext();
        String marker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(idProperty.toColumnValue(id));
        String sql = "delete from " + table(concreteMetadata)
                + " where " + dialect.quote(idProperty.columnName()) + " = " + marker;
        return new SqlStatement(sql, context.bindings());
    }

    @Override
    public SqlStatement deleteJoinedRootById(InheritanceLayout layout, Object id) {
        InheritanceInfo info = layout.info();
        PersistentProperty idProperty = layout.rootMetadata().idProperty();
        RenderContext context = new RenderContext();
        String marker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(idProperty.toColumnValue(id));
        String sql = "delete from " + qualifiedRootTable(info, info.rootTableName())
                + " where " + dialect.quote(idProperty.columnName()) + " = " + marker;
        return new SqlStatement(sql, context.bindings());
    }

    /**
     * JOINED 다형 SELECT의 select-list를 만든다 — 루트 PK + 루트 테이블 컬럼 + 각 서브타입 테이블 컬럼 +
     * discriminator. 같은 컬럼 이름은 한 번만 emit하고, 각 컬럼을 {@code table.col as col}로 qualify 한다.
     */
    private String joinedSelectList(InheritanceLayout layout) {
        InheritanceInfo info = layout.info();
        String rootTable = qualifiedRootTable(info, info.rootTableName());
        List<String> items = new ArrayList<>();
        LinkedHashSet<String> emitted = new LinkedHashSet<>();
        for (PersistentProperty property : layout.rootTableColumns()) {
            if (emitted.add(property.columnName())) {
                items.add(rootTable + "." + dialect.quote(property.columnName())
                        + " as " + dialect.quote(property.columnName()));
            }
        }
        for (InheritanceLayout.ConcreteSubtype subtype : layout.subtypes()) {
            String subTable = table(subtype.metadata());
            for (PersistentProperty property : subtype.ownTableColumns()) {
                if (property.id()) {
                    continue;
                }
                if (emitted.add(property.columnName())) {
                    items.add(subTable + "." + dialect.quote(property.columnName())
                            + " as " + dialect.quote(property.columnName()));
                }
            }
        }
        items.add(rootTable + "." + dialect.quote(info.discriminatorColumn())
                + " as " + dialect.quote(info.discriminatorColumn()));
        return String.join(", ", items);
    }

    /**
     * JOINED FROM 절을 만든다 — 루트 테이블에 각 서브타입 테이블을 루트 PK = 서브타입 FK로 LEFT JOIN 한다.
     */
    private String joinedFromClause(InheritanceLayout layout) {
        InheritanceInfo info = layout.info();
        String rootTable = qualifiedRootTable(info, info.rootTableName());
        String rootId = dialect.quote(layout.rootMetadata().idProperty().columnName());
        StringBuilder from = new StringBuilder(rootTable);
        for (InheritanceLayout.ConcreteSubtype subtype : layout.subtypes()) {
            if (subtype.metadata().entityType() == info.root()) {
                continue;
            }
            String subTable = table(subtype.metadata());
            String subId = dialect.quote(subtype.metadata().idProperty().columnName());
            from.append(" left join ").append(subTable)
                    .append(" on ").append(rootTable).append(".").append(rootId)
                    .append(" = ").append(subTable).append(".").append(subId);
        }
        return from.toString();
    }

    /**
     * JOINED 루트 물리 테이블을 schema-aware로 quote 한다. 루트 metadata의 schema를 따른다.
     */
    private String qualifiedRootTable(InheritanceInfo info, String rootTableName) {
        return dialect.quote(rootTableName);
    }

    // =============================================================================================
    // @Inheritance(TABLE_PER_CLASS)
    // =============================================================================================

    @Override
    public SqlStatement selectTablePerClassPolymorphic(InheritanceLayout layout, QuerySpec querySpec) {
        RenderContext context = new RenderContext();
        String union = tablePerClassUnion(layout);
        StringBuilder sql = new StringBuilder("select * from (").append(union).append(") as ")
                .append(dialect.quote("nova_tpc"));
        appendWhereClause(sql, context, layout.rootMetadata(), querySpec.predicate(), querySpec.cursor());
        appendOrderBy(sql, layout.rootMetadata(), querySpec.sort());
        appendPage(sql, context, querySpec);
        appendLockClause(sql, querySpec);
        return new SqlStatement(sql.toString(), context.bindings());
    }

    @Override
    public SqlStatement selectTablePerClassById(InheritanceLayout layout, Object id) {
        RenderContext context = new RenderContext();
        PersistentProperty idProperty = layout.rootMetadata().idProperty();
        String union = tablePerClassUnion(layout);
        String marker = dialect.bindMarkers().marker(context.nextIndex());
        context.addBinding(idProperty.toColumnValue(id));
        String sql = "select * from (" + union + ") as " + dialect.quote("nova_tpc")
                + " where " + dialect.quote(idProperty.columnName()) + " = " + marker;
        return new SqlStatement(sql, context.bindings());
    }

    /**
     * TABLE_PER_CLASS 다형 SELECT의 UNION ALL 본문을 만든다. 모든 서브타입 컬럼 이름을 합집합(union)으로
     * 모아 컬럼 순서를 고정하고, 각 브랜치는 자기 테이블에 존재하는 컬럼은 그대로, 없는 컬럼은 NULL로 정렬한다.
     * 끝에 discriminator 상수({@code '<value>' as dtype})를 합성한다.
     */
    private String tablePerClassUnion(InheritanceLayout layout) {
        InheritanceInfo info = layout.info();
        // 1) 전 서브타입 컬럼 이름 합집합(등장 순서 보존).
        LinkedHashSet<String> allColumns = new LinkedHashSet<>();
        for (InheritanceLayout.ConcreteSubtype subtype : layout.subtypes()) {
            for (PersistentProperty property : subtype.ownTableColumns()) {
                allColumns.add(property.columnName());
            }
        }
        List<String> branches = new ArrayList<>();
        for (InheritanceLayout.ConcreteSubtype subtype : layout.subtypes()) {
            Set<String> present = new LinkedHashSet<>();
            for (PersistentProperty property : subtype.ownTableColumns()) {
                present.add(property.columnName());
            }
            List<String> items = new ArrayList<>();
            for (String columnName : allColumns) {
                if (present.contains(columnName)) {
                    items.add(dialect.quote(columnName) + " as " + dialect.quote(columnName));
                } else {
                    items.add("null as " + dialect.quote(columnName));
                }
            }
            // discriminator 상수는 STRING/CHAR는 따옴표로, INTEGER는 정수 리터럴로 emit한다.
            String discriminatorLiteral = info.discriminatorJavaType() == Integer.class
                    ? subtype.discriminatorValue()
                    : "'" + subtype.discriminatorValue().replace("'", "''") + "'";
            items.add(discriminatorLiteral + " as " + dialect.quote(info.discriminatorColumn()));
            branches.add("select " + String.join(", ", items) + " from " + table(subtype.metadata()));
        }
        return String.join(" union all ", branches);
    }

    protected static final class RenderContext {
        private final List<Object> bindings = new ArrayList<>();

        /**
         * 다음 bind marker가 차지할 1-based 인덱스를 반환한다. dialect별 {@link #appendPage} override가
         * 다른 패키지에서 marker 인덱스를 계산할 수 있도록 {@code public}으로 노출한다 — 노출 범위는
         * {@code protected}인 {@link RenderContext} 타입 자체가 게이트한다.
         */
        public int nextIndex() {
            return bindings.size() + 1;
        }

        /**
         * 렌더링 순서대로 bind 값을 누적한다. executor가 0-based 인덱스로 위치 바인딩하므로 호출 순서가
         * 곧 binding 순서다. dialect별 {@link #appendPage} override가 다른 패키지에서 binding을 추가할 수
         * 있도록 {@code public}으로 노출한다.
         */
        public void addBinding(Object value) {
            bindings.add(value);
        }

        List<Object> bindings() {
            return bindings;
        }
    }
}
