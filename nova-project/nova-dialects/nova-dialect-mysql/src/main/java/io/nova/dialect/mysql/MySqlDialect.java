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
    public String lobType(boolean binary) {
        return binary ? "longblob" : "longtext";
    }

    @Override
    public String timestampColumnType() {
        // MySQL의 TIMESTAMP는 1970–2038 범위로 제한되고 세션 TZ 기준 암묵 UTC 변환 + 첫 컬럼에 암묵
        // DEFAULT/ON UPDATE CURRENT_TIMESTAMP가 붙어 @Temporal(TIMESTAMP) 값이 조용히 손상될 수 있다.
        // datetime은 범위가 넓고 TZ 무변환이라 java.util.Date 매핑에 충실하다(Hibernate와 동일 선택).
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

    @Override
    public String tableGeneratorIncrementSql(
            String table, String valueColumn, String pkColumn, String pkColumnValue, long increment) {
        // MySQL은 표준 UPDATE를 그대로 받아들인다. 식별자는 backtick으로 quote 하고, increment→select는
        // InnoDB row-level lock으로 동시 발급의 atomicity가 보장된다.
        return "update " + quote(table)
                + " set " + quote(valueColumn) + " = " + quote(valueColumn) + " + " + increment
                + " where " + quote(pkColumn) + " = '" + pkColumnValue + "'";
    }

    @Override
    public String tableGeneratorSelectSql(
            String table, String valueColumn, String pkColumn, String pkColumnValue) {
        return "select " + quote(valueColumn) + " as " + Dialect.TABLE_GENERATOR_VALUE_COLUMN
                + " from " + quote(table)
                + " where " + quote(pkColumn) + " = '" + pkColumnValue + "'";
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
