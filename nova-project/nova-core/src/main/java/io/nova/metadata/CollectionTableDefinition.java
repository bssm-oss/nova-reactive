package io.nova.metadata;

import java.util.List;

/**
 * {@code @ElementCollection} collection table의 물리 정의 — DDL 생성과 row SQL 렌더링에 쓰인다. owner FK
 * 컬럼과 값 컬럼(들), 그리고 각 Java 타입(owner {@code @Id} 타입 / 원소 값 타입)을 담는다.
 * <p>
 * 기본 타입 원소는 단일 값 컬럼({@link #valueColumn()}/{@link #valueType()})으로 표현하며
 * {@link #elementColumns()}는 빈 리스트다. {@code @Embeddable} 원소는 {@link #elementColumns()}에 펼쳐진
 * 컬럼들을 담으며, 이때 {@link #valueColumn()}은 의미가 없다.
 */
public record CollectionTableDefinition(
        String tableName,
        String ownerForeignKeyColumn,
        Class<?> ownerForeignKeyType,
        String valueColumn,
        Class<?> valueType,
        List<ElementColumn> elementColumns
) {
    public CollectionTableDefinition {
        elementColumns = elementColumns == null ? List.of() : List.copyOf(elementColumns);
    }

    /**
     * 기본 타입 원소용 생성자 — 단일 값 컬럼만 가진다.
     */
    public CollectionTableDefinition(
            String tableName,
            String ownerForeignKeyColumn,
            Class<?> ownerForeignKeyType,
            String valueColumn,
            Class<?> valueType) {
        this(tableName, ownerForeignKeyColumn, ownerForeignKeyType, valueColumn, valueType, List.of());
    }

    /**
     * 원소가 {@code @Embeddable}이면 {@code true} — 값을 {@link #elementColumns()} 다중 컬럼으로 저장한다.
     */
    public boolean embeddable() {
        return !elementColumns.isEmpty();
    }

    /**
     * {@code @Embeddable} 원소를 펼친 collection table 컬럼 하나의 물리 정의(컬럼 이름 + 저장 Java 타입).
     */
    public record ElementColumn(String columnName, Class<?> columnType) {
    }
}
