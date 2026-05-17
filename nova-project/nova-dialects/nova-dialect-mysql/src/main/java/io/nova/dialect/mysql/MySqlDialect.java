package io.nova.dialect.mysql;

import io.nova.sql.AbstractSchemaGenerator;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;

/**
 * 물음표 bind marker와 MySQL auto_increment 문법을 사용하는 MySQL dialect다.
 */
public final class MySqlDialect implements Dialect {
    private final BindMarkerStrategy bindMarkers = index -> "?";
    private final SqlRenderer sqlRenderer = new MySqlSqlRenderer(this);
    private final SchemaGenerator schemaGenerator = new MySqlSchemaGenerator(this);

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier + "`";
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

    private static final class MySqlSqlRenderer extends AbstractSqlRenderer {
        private MySqlSqlRenderer(Dialect dialect) {
            super(dialect);
        }
    }

    private static final class MySqlSchemaGenerator extends AbstractSchemaGenerator {
        private MySqlSchemaGenerator(Dialect dialect) {
            super(dialect);
        }

        @Override
        protected String identityColumn(io.nova.metadata.PersistentProperty property) {
            return "`" + property.columnName() + "` " + sqlType(property) + " primary key auto_increment";
        }
    }
}
