package io.nova.dialect.mysql;

import io.nova.sql.AbstractSchemaGenerator;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.SecondaryTableInfo;

import java.util.ArrayList;
import java.util.List;
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
    public int maxSecondPrecision() {
        return 6;
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
        public List<String> createComments(EntityMetadata<?> metadata) {
            List<String> statements = new ArrayList<>();
            if (!metadata.tableDdlDefinition().comment().isEmpty()) {
                statements.add("alter table " + qualifiedTable(metadata) + " comment = "
                        + sqlString(metadata.tableDdlDefinition().comment()));
            }
            for (PersistentProperty property : metadata.primaryColumnMappedProperties()) {
                if (!property.columnDdlDefinition().comment().isEmpty()) {
                    statements.add("alter table " + qualifiedTable(metadata) + " modify "
                            + columnDefinition(property) + " comment " + sqlString(property.columnDdlDefinition().comment()));
                }
            }
            return statements;
        }

        @Override
        public List<String> createSecondaryComments(EntityMetadata<?> metadata, SecondaryTableInfo secondaryTable) {
            List<String> statements = new ArrayList<>();
            for (PersistentProperty property : metadata.secondaryColumnMappedProperties(secondaryTable)) {
                if (!property.columnDdlDefinition().comment().isEmpty()) {
                    statements.add("alter table " + qualifiedSecondaryTable(secondaryTable) + " modify "
                            + columnDefinition(property) + " comment " + sqlString(property.columnDdlDefinition().comment()));
                }
            }
            return statements;
        }

        private static String sqlString(String value) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder hex = new StringBuilder("convert(0x");
            for (byte valueByte : bytes) {
                hex.append(String.format("%02x", valueByte));
            }
            return hex.append(" using utf8mb4)").toString();
        }

        @Override
        protected String identityColumn(io.nova.metadata.PersistentProperty property) {
            return "`" + property.columnName() + "` " + sqlType(property) + " primary key auto_increment";
        }
    }
}
