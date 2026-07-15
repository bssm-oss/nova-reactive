package io.nova.dialect.oracle;

import jakarta.persistence.EnumType;
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
    public String listForeignKeyNamesSql() {
        // Oracle엔 information_schema가 없으므로 user_constraints(현재 스키마)에서 referential 제약('R')을 읽는다.
        return "select constraint_name as " + Dialect.FOREIGN_KEY_NAME_COLUMN
                + " from user_constraints where constraint_type = 'R'";
    }

    @Override
    public String timeColumnType() {
        // Oracle에는 TIME-only 데이터타입이 없다(DATE/TIMESTAMP는 날짜 성분을 포함). ANSI `time` 토큰을 그대로
        // 내보내면 ORA-00902(invalid datatype)로 DDL이 깨지므로, 조용히 잘못된 컬럼을 만드는 대신 fail-fast 한다.
        // @Temporal(DATE)/@Temporal(TIMESTAMP)는 Oracle 토큰 date/timestamp가 유효하므로 그대로 동작한다.
        throw new UnsupportedOperationException(
                "Oracle has no TIME-only column type; @Temporal(TemporalType.TIME) is not supported on Oracle");
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

    @Override
    public String listTablesSql() {
        // Oracle은 information_schema가 없으므로 현재 스키마의 테이블을 user_tables에서 조회한다.
        return "select table_name as " + Dialect.TABLE_NAME_COLUMN + " from user_tables";
    }

    @Override
    public String listColumnsSql(String tableName) {
        return "select column_name as " + Dialect.COLUMN_NAME_COLUMN
                + " from user_tab_columns where upper(table_name) = upper('" + tableName + "')";
    }

    /**
     * {@code @Json} 컬럼에 사용할 SQL 타입을 반환한다. Oracle 21c+는 native {@code JSON} 타입을
     * 지원하지만 그 이전 버전(12c~19c)은 없으므로, 광범위 호환을 위해 JSON 문자열을 길이 제한 없는
     * {@code clob}로 매핑한다. 21c+ 전용 배포는 이 메서드를 override 해서 native {@code json}을 쓸 수 있다.
     */
    @Override
    public String jsonColumnType() {
        return "clob";
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
     * Oracle 12c+ SQL renderer다. {@code LIMIT/OFFSET}을 지원하지 않는 Oracle을 위해 paging은
     * {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}로, exists()의 single-row 가드는
     * {@code FETCH FIRST 1 ROWS ONLY}로 렌더한다.
     */
    private static final class OracleSqlRenderer extends AbstractSqlRenderer {
        // 부모(AbstractSqlRenderer)의 dialect 필드는 private이라 접근할 수 없으므로 생성자에서 받은
        // dialect를 자체 필드로 보관한다 — appendPage override에서 bind marker 생성에 필요하다.
        private final Dialect dialect;

        private OracleSqlRenderer(Dialect dialect) {
            super(dialect);
            this.dialect = dialect;
        }

        /**
         * Oracle은 {@code LIMIT}을 지원하지 않으므로 exists()의 single-row 가드를 12c+ 표준
         * {@code FETCH FIRST 1 ROWS ONLY}로 렌더한다 — 기본 {@code LIMIT 1}은 ORA-00933으로 실패한다.
         */
        @Override
        protected String existsRowLimitClause() {
            return " fetch first 1 rows only";
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
         * Oracle 12.2 미만은 식별자가 30바이트로 제한된다(12.2+는 128). 자동 생성 FK 제약 이름이 어떤 배포에서도
         * 깨지지 않도록 보수적으로 30으로 줄여, 멱등 발행 시에도 카탈로그 이름과 정확히 일치하게 한다.
         */
        @Override
        protected int maxConstraintNameLength() {
            return 30;
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
            // base AbstractSchemaGenerator.sqlType은 @Json을 최우선으로 분기한다. Oracle은 dispatch 전체를
            // 재구현하므로 같은 우선순위로 json 가드를 가장 먼저 둔다 — 빠뜨리면 @Json String 같은 property가
            // 아래 javaType 분기에서 varchar2(255)로 잘못 매핑된다. jsonColumnType()은 OracleDialect가
            // clob로 override 한다(12c~19c 호환; 21c+ native JSON은 배포별 override).
            if (property.json()) {
                return dialect().jsonColumnType();
            }
            if (property.enumerated()) {
                return property.enumType() == EnumType.STRING ? "varchar2(255)" : "number(10)";
            }
            if (property.lob()) {
                return dialect().lobType(property.javaType() == byte[].class);
            }
            Class<?> type = property.javaType();
            if (type == String.class) {
                return "varchar2(" + property.length() + ")";
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
            if (type == java.math.BigDecimal.class) {
                // Oracle은 임의 정밀도 수치를 number(p, s)로 표현한다. precision 미지정(0)이면 통화류 기본값
                // number(19, 2)를 emit한다 — base의 numeric(19, 2)와 동일 의미.
                return property.precision() > 0
                        ? "number(" + property.precision() + ", " + property.scale() + ")"
                        : "number(19, 2)";
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

        /**
         * 복합키 타겟 to-one의 FK 컬럼과 {@code @ManyToMany} link table 컬럼의 SQL 타입을 Oracle 네이티브
         * 토큰으로 매핑한다. base {@link AbstractSchemaGenerator#foreignKeyColumnType(Class, int)}는 ANSI
         * 토큰({@code bigint}/{@code integer}/{@code varchar}/{@code boolean})을 emit하는데, Oracle에는
         * {@code bigint}/{@code boolean} 타입이 없어 DDL이 ORA-00902로 깨지고 {@code varchar}/{@code integer}는
         * 이 dialect의 스칼라 {@link #sqlType} 매핑({@code number(19)}/{@code varchar2})과 불일치한다. 복합키
         * 관계 컬럼도 스칼라 컬럼과 동일한 Oracle 타입을 쓰도록 여기서 재매핑한다({@code oracleColumnType}).
         */
        @Override
        protected String foreignKeyColumnType(Class<?> columnType, int length) {
            return oracleColumnType(columnType, length);
        }

        /**
         * {@code @ManyToMany}/{@code @ElementCollection} owner-FK 컬럼(참조 {@code @Id} 도메인 타입)의 Oracle
         * 네이티브 타입. base {@link AbstractSchemaGenerator#fkColumnType(Class)}의 ANSI 토큰과 같은 이유로
         * 재매핑한다. base는 String을 {@code varchar(255)}로 고정하므로 Oracle은 {@code varchar2(255)}로 맞춘다.
         */
        @Override
        protected String fkColumnType(Class<?> idType) {
            return oracleColumnType(idType, 255);
        }

        /**
         * {@code @ElementCollection} 값/키 컬럼(저장 표현 타입)의 Oracle 네이티브 타입. base
         * {@link AbstractSchemaGenerator#elementColumnType(Class)}의 ANSI 토큰과 같은 이유로 재매핑한다.
         */
        @Override
        protected String elementColumnType(Class<?> valueType) {
            return oracleColumnType(valueType, 255);
        }

        /**
         * 저장 표현 Java 타입을 Oracle 네이티브 컬럼 타입으로 매핑한다 — FK/link/element 컬럼 세 경로가 스칼라
         * {@link #sqlType}와 동일한 토큰을 쓰도록 단일 자리에 모은다. {@code length}는 {@code varchar2} 컬럼
         * 길이에만 쓰인다(비-문자 타입에서는 무시). {@code @Temporal} 저장타입의 실제 SQL 토큰은 dialect가
         * 결정하며(Oracle은 {@code TIME}-only 타입이 없어 {@link OracleDialect#timeColumnType()}가 fail-fast),
         * 진짜 미지원 타입은 base와 동일하게 fail-fast 한다.
         */
        private String oracleColumnType(Class<?> type, int length) {
            if (type == String.class) {
                return "varchar2(" + length + ")";
            }
            if (type == Long.class || type == long.class) {
                return "number(19)";
            }
            if (type == Integer.class || type == int.class) {
                return "number(10)";
            }
            if (type == Short.class || type == short.class) {
                return "number(5)";
            }
            if (type == Boolean.class || type == boolean.class) {
                return "number(1)";
            }
            if (type == Double.class || type == double.class) {
                return "binary_double";
            }
            if (type == Float.class || type == float.class) {
                return "binary_float";
            }
            if (type == java.math.BigDecimal.class) {
                return "number(19, 2)";
            }
            if (type == java.util.UUID.class) {
                return "varchar2(36)";
            }
            if (type == java.time.LocalDate.class) {
                return dialect().dateColumnType();
            }
            if (type == java.time.LocalTime.class) {
                return dialect().timeColumnType();
            }
            if (type == java.time.LocalDateTime.class) {
                return dialect().timestampColumnType();
            }
            throw new IllegalArgumentException("Unsupported Oracle relation/element column type: " + type.getName());
        }

        /**
         * Oracle has no {@code CREATE TABLE IF NOT EXISTS} syntax. Wrap the raw
         * {@code CREATE} DDL in a PL/SQL anonymous block and swallow ORA-00955
         * (name already used by an existing object), re-raising any other error
         * so column / syntax problems are not hidden. {@code EXECUTE IMMEDIATE}
         * avoids the embedded-quote escaping required by static DDL in PL/SQL.
         * <p>Every idempotent {@code CREATE} surface (entity, join, collection,
         * secondary, generator, JOINED-inheritance) routes through this single
         * helper so the ORA-00955 guard is applied uniformly — the base's
         * ANSI {@code create table if not exists} would fail with ORA-00922.
         */
        private String createIfNotExistsBlock(String createDdl) {
            String inner = createDdl.replace("'", "''");
            return "begin execute immediate '" + inner
                    + "'; exception when others then if sqlcode != -955 then raise; end if; end;";
        }

        /**
         * Oracle has no {@code DROP TABLE IF EXISTS} syntax. Wrap the raw
         * {@code DROP} DDL in a PL/SQL block and swallow ORA-00942 (table or
         * view does not exist), re-raising anything else. Every idempotent
         * {@code DROP} surface routes through this helper — the base's ANSI
         * {@code drop table if exists} would fail on Oracle.
         */
        private String dropIfExistsBlock(String dropDdl) {
            String inner = dropDdl.replace("'", "''");
            return "begin execute immediate '" + inner
                    + "'; exception when others then if sqlcode != -942 then raise; end if; end;";
        }

        @Override
        public String createTableIfNotExists(io.nova.metadata.EntityMetadata<?> metadata) {
            return createIfNotExistsBlock(createTable(metadata));
        }

        /**
         * {@code purge} keeps the recycle bin clean so a follow-up
         * {@code CREATE TABLE} of the same name does not collide with a
         * recently dropped object.
         */
        @Override
        public String dropTableIfExists(io.nova.metadata.EntityMetadata<?> metadata) {
            return dropIfExistsBlock("drop table " + dialect().quote(metadata.tableName()) + " purge");
        }

        // --- @ManyToMany link-table idempotent DDL --------------------------

        @Override
        public String createJoinTableIfNotExists(io.nova.metadata.JoinTableDefinition definition) {
            return createIfNotExistsBlock(createJoinTable(definition));
        }

        @Override
        public String dropJoinTableIfExists(String joinTableName) {
            return dropIfExistsBlock("drop table " + dialect().quote(joinTableName) + " purge");
        }

        // --- @ElementCollection collection-table idempotent DDL -------------

        @Override
        public String createCollectionTableIfNotExists(io.nova.metadata.CollectionTableDefinition definition) {
            return createIfNotExistsBlock(createCollectionTable(definition));
        }

        // --- @SecondaryTable idempotent DDL ---------------------------------

        @Override
        public String createSecondaryTableIfNotExists(
                io.nova.metadata.EntityMetadata<?> metadata, io.nova.metadata.SecondaryTableInfo secondaryTable) {
            return createIfNotExistsBlock(createSecondaryTable(metadata, secondaryTable));
        }

        @Override
        public String dropSecondaryTableIfExists(io.nova.metadata.SecondaryTableInfo secondaryTable) {
            return dropIfExistsBlock(dropSecondaryTable(secondaryTable) + " purge");
        }

        // --- JOINED inheritance idempotent CREATE ---------------------------

        /**
         * The base emits {@code create table if not exists} for the JOINED root
         * table when {@code ifNotExists} is set; wrap the plain DDL in the
         * ORA-00955 block instead. Non-idempotent creation is left to the base.
         */
        @Override
        public String createJoinedRootTable(io.nova.metadata.InheritanceLayout layout, boolean ifNotExists) {
            String ddl = super.createJoinedRootTable(layout, false);
            return ifNotExists ? createIfNotExistsBlock(ddl) : ddl;
        }

        @Override
        public String createJoinedSubtypeTable(
                io.nova.metadata.InheritanceLayout layout,
                io.nova.metadata.InheritanceLayout.ConcreteSubtype subtype,
                boolean ifNotExists) {
            String ddl = super.createJoinedSubtypeTable(layout, subtype, false);
            return ifNotExists ? createIfNotExistsBlock(ddl) : ddl;
        }

        // --- @TableGenerator counter-table DDL ------------------------------

        /**
         * The base {@link AbstractSchemaGenerator#createTableGenerator} emits a
         * {@code bigint} counter column and ANSI {@code varchar(255)} PK — Oracle
         * has no {@code bigint} type, so the inherited DDL dies with ORA-00902
         * (the same defect class as the FK/link/element token leaks). Re-map to
         * Oracle-native {@code number(19)} / {@code varchar2(255)}.
         */
        @Override
        public String createTableGenerator(io.nova.metadata.TableGeneratorInfo info) {
            String pkColumn = dialect().quote(info.pkColumnName()) + " varchar2(255) not null primary key";
            String valueColumn = dialect().quote(info.valueColumnName()) + " number(19) not null";
            return "create table " + dialect().quote(info.table())
                    + " (" + pkColumn + ", " + valueColumn + ")";
        }

        @Override
        public String createTableGeneratorIfNotExists(io.nova.metadata.TableGeneratorInfo info) {
            return createIfNotExistsBlock(createTableGenerator(info));
        }

        @Override
        public String dropTableGeneratorIfExists(String generatorTableName) {
            // base also leaves the identifier unquoted; quote + purge like the other Oracle drops.
            return dropIfExistsBlock("drop table " + dialect().quote(generatorTableName) + " purge");
        }

        // --- ddl-auto=UPDATE column additions -------------------------------

        /**
         * Oracle's {@code ALTER TABLE ... ADD} takes the column list directly
         * (optionally parenthesised) and rejects the ANSI {@code ADD COLUMN}
         * keyword with ORA-00905. Reuse the base column rendering (which routes
         * through the Oracle {@link #sqlType} override) and wrap it in Oracle's
         * {@code ADD (...)} form.
         */
        @Override
        public String alterTableAddColumn(
                io.nova.metadata.EntityMetadata<?> metadata, PersistentProperty newColumn) {
            return "alter table " + qualifiedTable(metadata) + " add (" + columnDefinition(newColumn) + ")";
        }

        @Override
        public String addOneToManyOrderColumn(io.nova.metadata.EntityMetadata<?> childMetadata, String orderColumnName) {
            // Same ORA-00905 fix as alterTableAddColumn; mirror the base's `integer` order-column token
            // (valid on Oracle) but under the `ADD (...)` syntax.
            return "alter table " + qualifiedTable(childMetadata)
                    + " add (" + dialect().quote(orderColumnName) + " integer)";
        }
    }
}
