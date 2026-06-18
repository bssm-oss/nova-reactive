package io.nova.dialect.postgresql;

import jakarta.persistence.GenerationType;
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
    public String jsonColumnType() {
        // PostgreSQL은 binary JSON 저장 타입인 jsonb를 지원한다 — 인덱싱과 연산자 지원이 우수하므로
        // 기본 json 대신 jsonb로 컬럼을 생성한다.
        return "jsonb";
    }

    @Override
    public String lobType(boolean binary) {
        // PostgreSQL에는 clob/blob이 없다. 문자 LOB은 text, 바이너리 LOB은 bytea를 쓴다.
        return binary ? "bytea" : "text";
    }

    @Override
    public String renderILike(String column, String marker, boolean negate) {
        // PostgreSQL은 native ILIKE/NOT ILIKE 연산자를 지원하므로 lower() 래핑 없이 그대로 사용한다 —
        // pg_trgm GIN/GiST 인덱스로 가속 가능하며 collation 기반 비교를 dialect에 위임할 수 있다.
        return column + (negate ? " not ilike " : " ilike ") + marker;
    }

    @Override
    public String sequenceNextValueSql(String sequenceName) {
        if (sequenceName == null || sequenceName.isBlank()) {
            throw new IllegalArgumentException("sequenceName must not be blank");
        }
        // SQL identifier 외 문자는 EntityMetadataFactory에서 거부하지만 dialect도 자체 방어한다.
        // 단일 행/단일 컬럼 결과의 컬럼 라벨이 driver별로 "nextval", "NEXTVAL", "nextval(...)" 등
        // 다양하게 나오므로 명시적 alias로 고정해 RowAccessor의 컬럼명 기반 조회가 깨지지 않게 한다.
        return "select nextval('" + sequenceName + "') as " + Dialect.SEQUENCE_VALUE_COLUMN;
    }

    @Override
    public String tableGeneratorIncrementSql(
            String table, String valueColumn, String pkColumn, String pkColumnValue, long increment) {
        // PostgreSQL은 표준 UPDATE ... SET v = v + n WHERE pk = '...'을 그대로 받아들인다. 식별자는 dialect
        // quoting("...")으로 감싸고, pkColumnValue는 EntityMetadataFactory가 식별자 패턴으로 검증한다.
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
            // SEQUENCE/UUID는 INSERT 직전에 애플리케이션이 id를 채워서 보내므로 returning 절이 필요 없다.
            GenerationType strategy = idProperty.generationType();
            if (strategy == GenerationType.SEQUENCE || strategy == GenerationType.UUID) {
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
