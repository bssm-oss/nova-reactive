package io.nova.sql;

import io.nova.annotation.GenerationType;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;

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

    @Override
    public String createTable(EntityMetadata<?> metadata) {
        List<String> columns = new ArrayList<>();
        for (PersistentProperty property : metadata.properties()) {
            columns.add(columnDefinition(property));
        }
        return "create table " + dialect.quote(metadata.tableName()) + " (" + String.join(", ", columns) + ")";
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
     */
    protected String sqlType(PersistentProperty property) {
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
