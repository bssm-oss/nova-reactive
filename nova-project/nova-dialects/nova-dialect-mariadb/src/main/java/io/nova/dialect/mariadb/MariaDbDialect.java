package io.nova.dialect.mariadb;

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
 * 물음표 bind marker와 MariaDB auto_increment 문법을 사용하는 MariaDB dialect다.
 *
 * <p>MariaDB는 Nova 관점에서 MySQL과 wire 호환이다: backtick 식별자 quoting,
 * {@code ?} positional bind marker, {@code auto_increment} identity 컬럼을 공유한다.
 *
 * <p><b>MySQL과의 divergence:</b> MariaDB 10.3+는 네이티브 SEQUENCE 오브젝트를 지원하므로
 * {@link #sequenceNextValueSql(String)}을 override해 {@code @GeneratedValue(SEQUENCE)}를 지원한다
 * (MySQL은 시퀀스가 없어 상속 기본값인 미지원 예외를 그대로 둔다).
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
    public String sequenceNextValueSql(String sequenceName) {
        // MySQL과 달리 MariaDB는 10.3부터 네이티브 SEQUENCE 오브젝트(CREATE SEQUENCE / NEXTVAL())를 지원한다.
        // 상속 기본값(UnsupportedOperationException)을 그대로 두면 실제로 가능한 @GeneratedValue(SEQUENCE)를
        // 잘못 미지원으로 보고하게 된다. NEXTVAL() 인자는 문자열 리터럴이 아니라 시퀀스 식별자이므로(PostgreSQL의
        // nextval('name') 함수 인자와 달리 스키마 오브젝트 참조) backtick으로 quote한다. 결과 컬럼은 driver별
        // 라벨 차이 없이 RowAccessor가 읽을 수 있도록 고정 alias로 노출한다.
        if (sequenceName == null || sequenceName.isBlank()) {
            throw new IllegalArgumentException("sequenceName must not be blank");
        }
        return "select nextval(" + quote(sequenceName) + ") as " + Dialect.SEQUENCE_VALUE_COLUMN;
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
