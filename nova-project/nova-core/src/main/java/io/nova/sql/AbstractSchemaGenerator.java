package io.nova.sql;

import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.EnumType;
import jakarta.persistence.GenerationType;
import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.IndexDefinition;
import io.nova.metadata.InheritanceInfo;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.UniqueConstraintDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 엔티티 메타데이터로부터 최소한의 create table 구문을 만드는 기본 스키마 생성기다.
 */
public abstract class AbstractSchemaGenerator implements SchemaGenerator {
    private final Dialect dialect;

    protected AbstractSchemaGenerator(Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * 서브클래스가 identity 컬럼 등 dialect-specific DDL을 만들 때 식별자 quoting을 일관되게
     * 적용할 수 있도록 dialect 인스턴스를 노출한다.
     */
    protected final Dialect dialect() {
        return dialect;
    }

    @Override
    public String createTable(EntityMetadata<?> metadata) {
        return createTableInternal(metadata, false);
    }

    @Override
    public String createTableIfNotExists(EntityMetadata<?> metadata) {
        return createTableInternal(metadata, true);
    }

    @Override
    public String dropTable(EntityMetadata<?> metadata) {
        return "drop table " + qualifiedTable(metadata);
    }

    @Override
    public String createJoinTable(JoinTableDefinition definition) {
        String ownerColumn = dialect.quote(definition.ownerForeignKeyColumn())
                + " " + fkColumnType(definition.ownerForeignKeyType()) + " not null";
        String targetColumn = dialect.quote(definition.targetForeignKeyColumn())
                + " " + fkColumnType(definition.targetForeignKeyType()) + " not null";
        String primaryKey = "primary key (" + dialect.quote(definition.ownerForeignKeyColumn())
                + ", " + dialect.quote(definition.targetForeignKeyColumn()) + ")";
        return "create table " + dialect.quote(definition.tableName())
                + " (" + ownerColumn + ", " + targetColumn + ", " + primaryKey + ")";
    }

    @Override
    public String createCollectionTable(CollectionTableDefinition definition) {
        // owner FK는 not null, value 컬럼은 nullable(컬렉션 원소 null 허용 가능). 복합 PK는 두지 않는다
        // (List 중복 원소 허용). owner FK 조회 성능은 후속 인덱스 단계에서 보강.
        String ownerColumn = dialect.quote(definition.ownerForeignKeyColumn())
                + " " + fkColumnType(definition.ownerForeignKeyType()) + " not null";
        String valueColumnDef = dialect.quote(definition.valueColumn())
                + " " + elementColumnType(definition.valueType());
        return "create table " + dialect.quote(definition.tableName())
                + " (" + ownerColumn + ", " + valueColumnDef + ")";
    }

    /**
     * {@code @ElementCollection} 값 컬럼의 SQL 타입을 원소 Java 타입으로 결정한다. {@link #sqlType}의 스칼라
     * 분기를 미러하되 property 없이 타입만으로 매핑한다(기본 타입 원소 지원).
     */
    protected String elementColumnType(Class<?> valueType) {
        if (valueType == String.class) {
            return "varchar(255)";
        }
        if (valueType == Long.class || valueType == long.class) {
            return "bigint";
        }
        if (valueType == Integer.class || valueType == int.class) {
            return "integer";
        }
        if (valueType == Boolean.class || valueType == boolean.class) {
            return "boolean";
        }
        if (valueType == Double.class || valueType == double.class) {
            return "double precision";
        }
        if (valueType == Short.class || valueType == short.class) {
            return "smallint";
        }
        if (valueType == java.math.BigDecimal.class) {
            return "numeric(19, 2)";
        }
        if (valueType == java.util.UUID.class) {
            return "varchar(36)";
        }
        throw new IllegalArgumentException("Unsupported @ElementCollection element type: " + valueType.getName());
    }

    @Override
    public String dropJoinTable(String joinTableName) {
        return "drop table " + dialect.quote(joinTableName);
    }

    @Override
    public String dropJoinTableIfExists(String joinTableName) {
        return "drop table if exists " + dialect.quote(joinTableName);
    }

    /**
     * {@code @ManyToMany} link table의 FK 컬럼 SQL 타입을 owner/target {@code @Id}의 Java 타입으로 결정한다.
     * {@link #sqlType(PersistentProperty)}의 스칼라 분기를 미러하되, property 없이 타입만으로 매핑한다.
     */
    protected String fkColumnType(Class<?> idType) {
        if (idType == Long.class || idType == long.class) {
            return "bigint";
        }
        if (idType == Integer.class || idType == int.class) {
            return "integer";
        }
        if (idType == java.util.UUID.class) {
            return "varchar(36)";
        }
        if (idType == String.class) {
            return "varchar(255)";
        }
        throw new IllegalArgumentException("Unsupported @ManyToMany id type for join column: " + idType.getName());
    }

    @Override
    public String dropTableIfExists(EntityMetadata<?> metadata) {
        return "drop table if exists " + qualifiedTable(metadata);
    }

    private String createTableInternal(EntityMetadata<?> metadata, boolean ifNotExists) {
        // raw properties()는 @OneToMany inverse side 같은 비-컬럼 마커도 포함하므로
        // SchemaGenerator가 컬럼 DDL을 만들 때 사용하면 List 타입 컬럼 같은 거짓 컬럼이 섞인다.
        // @EmbeddedId 복합키는 컬럼별 inline PRIMARY KEY 대신 테이블 레벨 제약(primary key (c1, c2))으로 emit한다.
        boolean compositePk = metadata.hasCompositeId();
        List<String> columns = new ArrayList<>();
        for (PersistentProperty property : metadata.columnMappedProperties()) {
            columns.add(columnDefinition(property, compositePk));
        }
        if (compositePk) {
            List<String> pkColumns = new ArrayList<>(metadata.idProperties().size());
            for (PersistentProperty idProperty : metadata.idProperties()) {
                pkColumns.add(dialect.quote(idProperty.columnName()));
            }
            columns.add("primary key (" + String.join(", ", pkColumns) + ")");
        }
        // SINGLE_TABLE 상속: 단일 테이블에 discriminator 컬럼을 추가한다.
        if (metadata.hasInheritance()) {
            columns.add(discriminatorColumnDefinition(metadata));
        }
        return "create table " + (ifNotExists ? "if not exists " : "")
                + qualifiedTable(metadata)
                + " (" + String.join(", ", columns) + ")";
    }

    /**
     * SINGLE_TABLE 상속의 discriminator 컬럼 DDL. STRING은 {@code varchar(length)}, CHAR는 {@code char(1)},
     * INTEGER는 {@code integer}로 매핑하며 항상 not null이다(모든 row가 타입을 가진다).
     */
    protected String discriminatorColumnDefinition(EntityMetadata<?> metadata) {
        InheritanceInfo info = metadata.inheritance();
        String type = switch (info.discriminatorType()) {
            case STRING -> "varchar(" + info.discriminatorLength() + ")";
            case CHAR -> "char(1)";
            case INTEGER -> "integer";
        };
        return dialect.quote(info.discriminatorColumn()) + " " + type + " not null";
    }

    @Override
    public List<String> createIndexes(EntityMetadata<?> metadata) {
        List<String> statements = new ArrayList<>(
                metadata.indexes().size() + metadata.uniqueConstraints().size());
        String quotedTable = qualifiedTable(metadata);
        for (IndexDefinition index : metadata.indexes()) {
            statements.add(
                    "create index " + dialect.quote(index.name()) + " on " + quotedTable
                            + " (" + joinQuoted(index.columns()) + ")");
        }
        for (UniqueConstraintDefinition constraint : metadata.uniqueConstraints()) {
            statements.add(
                    "create unique index " + dialect.quote(constraint.name()) + " on " + quotedTable
                            + " (" + joinQuoted(constraint.columns()) + ")");
        }
        return statements;
    }

    @Override
    public String alterTableAddColumn(EntityMetadata<?> metadata, PersistentProperty newColumn) {
        return "alter table " + qualifiedTable(metadata)
                + " add column " + columnDefinition(newColumn);
    }

    @Override
    public String alterTableDropColumn(EntityMetadata<?> metadata, String columnName) {
        boolean exists = metadata.columnMappedProperties().stream()
                .anyMatch(property -> property.columnName().equals(columnName));
        if (!exists) {
            List<String> knownColumns = metadata.columnMappedProperties().stream()
                    .map(PersistentProperty::columnName)
                    .toList();
            throw new IllegalArgumentException(
                    "alterTableDropColumn refused: unknown column '" + columnName
                            + "' on " + metadata.entityType().getName()
                            + "; known columns: " + knownColumns);
        }
        return "alter table " + qualifiedTable(metadata)
                + " drop column " + dialect.quote(columnName);
    }

    private String joinQuoted(List<String> identifiers) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < identifiers.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(dialect.quote(identifiers.get(i)));
        }
        return builder.toString();
    }

    /**
     * 매핑된 프로퍼티에 대해 primary key, nullability를 포함한 컬럼 정의를 만든다.
     */
    /**
     * 스키마 한정 테이블 참조를 만든다. {@code @Table(schema=...)}이 지정되면 {@code "schema"."table"}
     * 형태로, 아니면 {@code "table"}만 quote해서 반환한다.
     */
    protected String qualifiedTable(EntityMetadata<?> metadata) {
        String quotedTable = dialect.quote(metadata.tableName());
        return metadata.schema().isBlank()
                ? quotedTable
                : dialect.quote(metadata.schema()) + "." + quotedTable;
    }

    protected String columnDefinition(PersistentProperty property) {
        return columnDefinition(property, false);
    }

    /**
     * {@code suppressInlinePrimaryKey}가 {@code true}이면 단일 컬럼 {@code primary key} 수식을 생략한다 —
     * {@code @EmbeddedId} 복합키처럼 PK가 여러 컬럼에 걸쳐 테이블 레벨 제약으로 따로 emit될 때 사용한다.
     */
    protected String columnDefinition(PersistentProperty property, boolean suppressInlinePrimaryKey) {
        if (property.generated() && property.generationType() == GenerationType.IDENTITY) {
            return identityColumn(property);
        }
        // @Column(columnDefinition=...)이 지정되면 dialect가 유도한 타입 대신 raw DDL 조각을 그대로 쓴다.
        String type = property.columnDefinition().isBlank() ? sqlType(property) : property.columnDefinition();
        StringBuilder builder = new StringBuilder()
                .append(dialect.quote(property.columnName()))
                .append(' ')
                .append(type);
        if (property.id() && !suppressInlinePrimaryKey) {
            builder.append(" primary key");
        }
        if (!property.nullable()) {
            builder.append(" not null");
        }
        if (property.unique() && !property.id()) {
            builder.append(" unique");
        }
        return builder.toString();
    }

    /**
     * identity 생성 전략을 사용하는 식별자 컬럼 정의를 반환한다.
     */
    protected String identityColumn(PersistentProperty property) {
        return dialect.quote(property.columnName()) + " " + sqlType(property) + " primary key";
    }

    /**
     * 매핑된 Java 프로퍼티 타입에 대응하는 SQL 컬럼 타입을 결정한다.
     * {@code @Enumerated} 프로퍼티는 enum의 실제 타입과 무관하게 저장 전략에 따라
     * {@code varchar(255)}(STRING) 또는 {@code integer}(ORDINAL)로 고정한다.
     */
    protected String sqlType(PersistentProperty property) {
        if (property.json()) {
            // @Json 컬럼은 Java 타입과 무관하게 dialect가 제공하는 JSON 타입을 사용한다
            // (기본 json, PostgreSQL jsonb). 컬럼 이름/nullability는 일반 컬럼과 동일하게 결정된다.
            return dialect.jsonColumnType();
        }
        if (property.enumerated()) {
            return property.enumType() == EnumType.STRING ? "varchar(255)" : "integer";
        }
        if (property.lob()) {
            // @Lob: byte[]는 binary LOB(BLOB류), 그 외(String 등)는 character LOB(CLOB류).
            return dialect.lobType(property.javaType() == byte[].class);
        }
        // @Convert 변환기가 있으면 도메인 타입(javaType=X)이 아니라 저장 표현 타입(columnType=Y)으로 컬럼을 만든다.
        Class<?> type = property.columnType();
        if (type == String.class) {
            return "varchar(" + property.length() + ")";
        }
        if (type == Long.class || type == long.class) {
            return "bigint";
        }
        if (type == Integer.class || type == int.class) {
            return "integer";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (type == Double.class || type == double.class) {
            return "double precision";
        }
        if (type == java.math.BigDecimal.class) {
            // precision이 지정되면 그대로 numeric(p, s)로, 미지정(0)이면 통화/금액류에 흔히 쓰는
            // numeric(19, 2)를 기본값으로 emit한다. row 디코딩은 columnType()=BigDecimal로 driver가 native 처리한다.
            return property.precision() > 0
                    ? "numeric(" + property.precision() + ", " + property.scale() + ")"
                    : "numeric(19, 2)";
        }
        throw new IllegalArgumentException("Unsupported column type: " + type.getName());
    }
}
