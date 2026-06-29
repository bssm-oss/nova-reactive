package io.nova.dialect.mariadb;

import io.nova.sql.AbstractSchemaGenerator;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;

/**
 * 물음표 bind marker와 MariaDB auto_increment 문법을 사용하는 MariaDB dialect다.
 *
 * <p>MariaDB는 Nova 관점에서 MySQL과 wire 호환이다: backtick 식별자 quoting,
 * {@code ?} positional bind marker, {@code auto_increment} identity 컬럼을 공유한다.
 */
public final class MariaDbDialect implements Dialect {
    private final BindMarkerStrategy bindMarkers = index -> "?";
    private final SqlRenderer sqlRenderer = new MariaDbSqlRenderer(this);
    private final SchemaGenerator schemaGenerator = new MariaDbSchemaGenerator(this);

    @Override
    public String name() {
        return "mariadb";
    }

    @Override
    public String lobType(boolean binary) {
        return binary ? "longblob" : "longtext";
    }

    @Override
    public String timestampColumnType() {
        // MySQL과 동일: TIMESTAMP의 1970–2038 범위/암묵 TZ 변환/ON UPDATE 부작용을 피하려고 @Temporal(TIMESTAMP)은
        // datetime으로 매핑한다.
        return "datetime";
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

    private static final class MariaDbSqlRenderer extends AbstractSqlRenderer {
        private MariaDbSqlRenderer(Dialect dialect) {
            super(dialect);
        }
    }

    private static final class MariaDbSchemaGenerator extends AbstractSchemaGenerator {
        private MariaDbSchemaGenerator(Dialect dialect) {
            super(dialect);
        }

        @Override
        protected String identityColumn(io.nova.metadata.PersistentProperty property) {
            return "`" + property.columnName() + "` " + sqlType(property) + " primary key auto_increment";
        }
    }
}
