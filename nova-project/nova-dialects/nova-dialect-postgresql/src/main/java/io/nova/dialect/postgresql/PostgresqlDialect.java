package io.nova.dialect.postgresql;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.sql.AbstractSchemaGenerator;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;

/**
 * 번호 기반 bind marker와 PostgreSQL 전용 identity 컬럼 문법을 사용하는 PostgreSQL dialect다.
 */
public final class PostgresqlDialect implements Dialect {
    private final BindMarkerStrategy bindMarkers = index -> "$" + index;
    private final SqlRenderer sqlRenderer = new PostgresqlSqlRenderer(this);
    private final SchemaGenerator schemaGenerator = new PostgresqlSchemaGenerator(this);

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public BindMarkerStrategy bindMarkers() {
        return bindMarkers;
    }

    @Override
    public SqlRenderer sqlRenderer() {
        return sqlRenderer;
    }

    @Override
    public SchemaGenerator schemaGenerator() {
        return schemaGenerator;
    }

    @Override
    public boolean usesReturningForGeneratedKeys() {
        return true;
    }

    @Override
    public String sequenceNextValueSql(String sequenceName) {
        if (sequenceName == null || sequenceName.isBlank()) {
            throw new IllegalArgumentException("sequenceName must not be blank");
        }
        return "select nextval('" + sequenceName + "')";
    }

    private static final class PostgresqlSqlRenderer extends AbstractSqlRenderer {
        private PostgresqlSqlRenderer(Dialect dialect) {
            super(dialect);
        }

        @Override
        protected String insertSuffix(EntityMetadata<?> metadata) {
            PersistentProperty idProperty = metadata.idProperty();
            if (idProperty == null || !idProperty.generated()) {
                return "";
            }
            return " returning " + column(idProperty);
        }
    }

    private static final class PostgresqlSchemaGenerator extends AbstractSchemaGenerator {
        private PostgresqlSchemaGenerator(Dialect dialect) {
            super(dialect);
        }

        @Override
        protected String identityColumn(io.nova.metadata.PersistentProperty property) {
            return switch (property.javaType().getSimpleName()) {
                case "Long", "long" -> "\"" + property.columnName() + "\" bigserial primary key";
                case "Integer", "int" -> "\"" + property.columnName() + "\" serial primary key";
                default -> super.identityColumn(property);
            };
        }
    }
}
