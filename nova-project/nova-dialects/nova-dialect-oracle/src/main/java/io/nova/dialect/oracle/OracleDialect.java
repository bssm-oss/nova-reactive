package io.nova.dialect.oracle;

import io.nova.annotation.EnumType;
import io.nova.metadata.PersistentProperty;
import io.nova.query.LockMode;
import io.nova.query.QuerySpec;
import io.nova.sql.AbstractSchemaGenerator;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;

/**
 * 위치 기반 {@code ?} bind marker와 Oracle 12c+ 전용 문법을 사용하는 Oracle dialect다.
 *
 * <p>식별자는 PostgreSQL/H2와 동일하게 double-quote로 감싸 대소문자를 보존한다. INSERT 시 생성된
 * IDENTITY 키는 R2DBC SPI의 {@code Statement.returnGeneratedValues(...)} 경로로 회수하므로
 * {@code INSERT ... RETURNING} 절은 emit 하지 않는다 — {@link #usesReturningForGeneratedKeys()}는
 * 기본값 {@code false}를 유지한다.
 *
 * <p>Oracle은 {@code LIMIT/OFFSET}을 지원하지 않으므로 paging은 12c+ 표준
 * {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY} 문법으로 렌더한다. 시퀀스는 {@code FROM DUAL}이
 * 필수이며, row-level {@code FOR SHARE} lock은 지원하지 않는다.
 */
public final class OracleDialect implements Dialect {
    /**
     * oracle-r2dbc는 JDBC 스타일 {@code ?} 위치 marker를 수용하며, 이는 executor의 0-based 위치
     * 바인딩({@code Statement.bind(i, value)})과 정확히 맞는다. 구버전 드라이버가 {@code :name}
     * 형태의 named marker를 요구한다면 이 {@link BindMarkerStrategy} 구현만 교체해 대응할 수 있다.
     */
    private final BindMarkerStrategy bindMarkers = index -> "?";
    private final SqlRenderer sqlRenderer = new OracleSqlRenderer(this);
    private final SchemaGenerator schemaGenerator = new OracleSchemaGenerator(this);

    @Override
    public String name() {
        return "oracle";
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
    public String sequenceNextValueSql(String sequenceName) {
        if (sequenceName == null || sequenceName.isBlank()) {
            throw new IllegalArgumentException("sequenceName must not be blank");
        }
        // Oracle은 단순 SELECT에도 FROM 절이 필수이므로 dual pseudo-table을 사용한다. 단일 컬럼은
        // driver별 라벨 차이 없이 RowAccessor가 항상 읽을 수 있도록 명시적 alias로 고정한다.
        return "select " + sequenceName + ".nextval as " + Dialect.SEQUENCE_VALUE_COLUMN + " from dual";
    }

    /**
     * Oracle은 {@code FOR UPDATE}만 row-level pessimistic lock으로 지원한다. ANSI {@code FOR SHARE}
     * (공유 행 잠금)는 Oracle에 대응 구문이 없으므로 {@link LockMode#FOR_SHARE} 요청은
     * {@link UnsupportedOperationException}으로 거부한다 — Oracle의 테이블 레벨 {@code LOCK TABLE ...
     * IN SHARE MODE}는 의미가 다르고 SELECT 꼬리에 이어 붙일 수 없다.
     */
    @Override
    public String lockClause(LockMode mode) {
        return switch (mode) {
            case NONE -> "";
            case FOR_UPDATE -> " for update";
            case FOR_SHARE -> throw new UnsupportedOperationException("Oracle does not support FOR SHARE row locks");
        };
    }

    /**
     * Oracle 12c+ paging renderer다. {@code LIMIT/OFFSET}을 지원하지 않는 Oracle을 위해
     * {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY} 문법을 렌더한다.
     */
    private static final class OracleSqlRenderer extends AbstractSqlRenderer {
        // 부모(AbstractSqlRenderer)의 dialect 필드는 private이라 접근할 수 없으므로 생성자에서 받은
        // dialect를 자체 필드로 보관한다 — appendPage override에서 bind marker 생성에 필요하다.
        private final Dialect dialect;

        private OracleSqlRenderer(Dialect dialect) {
            super(dialect);
            this.dialect = dialect;
        }

        @Override
        protected void appendPage(StringBuilder sql, RenderContext context, QuerySpec querySpec) {
            if (querySpec.pageable() == null) {
                return;
            }
            if (querySpec.cursor() != null) {
                // keyset pagination: cursor가 "어디서부터" 정보를 담으므로 OFFSET 없이 N건만 가져온다.
                sql.append(" fetch first ").append(dialect.bindMarkers().marker(context.nextIndex())).append(" rows only");
                context.addBinding(querySpec.pageable().limit());
                return;
            }
            // Oracle은 OFFSET을 먼저 바인딩한다(base renderer는 limit 먼저). marker 인덱스와 binding
            // 추가 순서가 일치하므로 offset → limit 순서가 정확하다.
            sql.append(" offset ").append(dialect.bindMarkers().marker(context.nextIndex())).append(" rows");
            context.addBinding(querySpec.pageable().offset());
            sql.append(" fetch next ").append(dialect.bindMarkers().marker(context.nextIndex())).append(" rows only");
            context.addBinding(querySpec.pageable().limit());
        }
    }

    /**
     * Oracle 12c+ schema generator다. 컬럼 타입은 Oracle 네이티브 타입으로 매핑하고, IDENTITY
     * 컬럼은 {@code generated always as identity}로 정의한다.
     */
    private static final class OracleSchemaGenerator extends AbstractSchemaGenerator {
        private OracleSchemaGenerator(Dialect dialect) {
            super(dialect);
        }

        /**
         * Java 프로퍼티 타입을 Oracle 네이티브 컬럼 타입으로 매핑한다.
         * <ul>
         *   <li>{@code String} → {@code varchar2(255)}</li>
         *   <li>{@code Long}/{@code long} → {@code number(19)}</li>
         *   <li>{@code Integer}/{@code int} → {@code number(10)}</li>
         *   <li>{@code Double}/{@code double} → {@code binary_double}</li>
         *   <li>{@code Boolean}/{@code boolean} → {@code number(1)}</li>
         * </ul>
         * {@code @Enumerated} 프로퍼티는 STRING이면 {@code varchar2(255)}, ORDINAL이면
         * {@code number(10)}으로 고정한다(base의 {@code varchar(255)}/{@code integer}에 대응).
         * 미지원 타입은 base가 던지는 {@link IllegalArgumentException}을 그대로 전파한다.
         *
         * <p>{@code Boolean → number(1)}은 DDL 레벨 매핑일 뿐이다. 런타임의 Boolean↔NUMBER 변환은
         * 드라이버 또는 AttributeConverter의 책임이며, 이 dialect의 범위는 SQL/스키마 렌더링까지다.
         */
        @Override
        protected String sqlType(PersistentProperty property) {
            if (property.enumerated()) {
                return property.enumType() == EnumType.STRING ? "varchar2(255)" : "number(10)";
            }
            Class<?> type = property.javaType();
            if (type == String.class) {
                return "varchar2(255)";
            }
            if (type == Long.class || type == long.class) {
                return "number(19)";
            }
            if (type == Integer.class || type == int.class) {
                return "number(10)";
            }
            if (type == Boolean.class || type == boolean.class) {
                return "number(1)";
            }
            if (type == Double.class || type == double.class) {
                return "binary_double";
            }
            // 그 외(미지원 타입 등)는 base의 IllegalArgumentException을 그대로 전파한다.
            return super.sqlType(property);
        }

        @Override
        protected String identityColumn(PersistentProperty property) {
            // Oracle 12c+ 표준 IDENTITY 컬럼. id 타입과 무관하게 number(19)로 고정한다.
            return dialect().quote(property.columnName())
                    + " number(19) generated always as identity primary key";
        }
    }
}
