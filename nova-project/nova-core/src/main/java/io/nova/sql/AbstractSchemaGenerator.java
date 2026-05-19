package io.nova.sql;

import io.nova.annotation.EnumType;
import io.nova.annotation.GenerationType;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.IndexDefinition;
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
        List<String> columns = new ArrayList<>();
        for (PersistentProperty property : metadata.properties()) {
            columns.add(columnDefinition(property));
        }
        return "create table " + dialect.quote(metadata.tableName()) + " (" + String.join(", ", columns) + ")";
    }

    @Override
    public List<String> createIndexes(EntityMetadata<?> metadata) {
        List<String> statements = new ArrayList<>(
                metadata.indexes().size() + metadata.uniqueConstraints().size());
        String quotedTable = dialect.quote(metadata.tableName());
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
        return "alter table " + dialect.quote(metadata.tableName())
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
        return "alter table " + dialect.quote(metadata.tableName())
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
    protected String columnDefinition(PersistentProperty property) {
        StringBuilder builder = new StringBuilder()
                .append(dialect.quote(property.columnName()))
                .append(' ')
                .append(sqlType(property));
        if (property.id()) {
            builder.append(" primary key");
        }
        if (!property.nullable()) {
            builder.append(" not null");
        }
        if (property.generated() && property.generationType() == GenerationType.IDENTITY) {
            builder = new StringBuilder(identityColumn(property));
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
        if (property.enumerated()) {
            return property.enumType() == EnumType.STRING ? "varchar(255)" : "integer";
        }
        Class<?> type = property.javaType();
        if (type == String.class) {
            return "varchar(255)";
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
        throw new IllegalArgumentException("Unsupported column type: " + type.getName());
    }
}
