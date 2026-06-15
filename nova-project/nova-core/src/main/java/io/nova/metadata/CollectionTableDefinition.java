package io.nova.metadata;

/**
 * {@code @ElementCollection} collection table의 물리 정의 — DDL 생성과 row SQL 렌더링에 쓰인다. owner FK
 * 컬럼과 값 컬럼, 그리고 각 Java 타입(owner {@code @Id} 타입 / 원소 값 타입)을 담는다. v1은 기본 타입 원소만
 * 지원하므로 값 컬럼은 하나다.
 */
public record CollectionTableDefinition(
        String tableName,
        String ownerForeignKeyColumn,
        Class<?> ownerForeignKeyType,
        String valueColumn,
        Class<?> valueType
) {
}
