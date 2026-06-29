package io.nova.metadata;

import java.util.List;

/**
 * {@code @ElementCollection} collection table의 물리 정의 — DDL 생성과 row SQL 렌더링에 쓰인다. owner FK
 * 컬럼과 값 컬럼(들), 그리고 각 Java 타입(owner {@code @Id} 타입 / 원소 값 타입)을 담는다.
 * <p>
 * 기본 타입 원소는 단일 값 컬럼({@link #valueColumn()}/{@link #valueType()})으로 표현하며
 * {@link #elementColumns()}는 빈 리스트다. {@code @Embeddable} 원소는 {@link #elementColumns()}에 펼쳐진
 * 컬럼들을 담으며, 이때 {@link #valueColumn()}은 의미가 없다. {@code Map<K,V>}는 {@link #mapKey()}가 non-null이며
 * key 컬럼이 추가된다.
 */
public record CollectionTableDefinition(
        String tableName,
        String ownerForeignKeyColumn,
        Class<?> ownerForeignKeyType,
        String valueColumn,
        Class<?> valueType,
        List<ElementColumn> elementColumns,
        OrderColumnInfo orderColumn,
        MapKeyColumn mapKey
) {
    public CollectionTableDefinition {
        elementColumns = elementColumns == null ? List.of() : List.copyOf(elementColumns);
    }

    /**
     * 기본 타입 원소용 생성자 — 단일 값 컬럼만 가지며 순서 컬럼/map key가 없다.
     */
    public CollectionTableDefinition(
            String tableName,
            String ownerForeignKeyColumn,
            Class<?> ownerForeignKeyType,
            String valueColumn,
            Class<?> valueType) {
        this(tableName, ownerForeignKeyColumn, ownerForeignKeyType, valueColumn, valueType, List.of(), null, null);
    }

    /**
     * {@code @Embeddable} 원소용 생성자 — 펼침 컬럼은 있고 순서 컬럼/map key는 없는 형태.
     */
    public CollectionTableDefinition(
            String tableName,
            String ownerForeignKeyColumn,
            Class<?> ownerForeignKeyType,
            String valueColumn,
            Class<?> valueType,
            List<ElementColumn> elementColumns) {
        this(tableName, ownerForeignKeyColumn, ownerForeignKeyType, valueColumn, valueType, elementColumns, null, null);
    }

    /**
     * 정렬({@code @OrderColumn}) 정보까지 받는 생성자 — map key는 없는 형태(List/Set 값 컬렉션).
     */
    public CollectionTableDefinition(
            String tableName,
            String ownerForeignKeyColumn,
            Class<?> ownerForeignKeyType,
            String valueColumn,
            Class<?> valueType,
            List<ElementColumn> elementColumns,
            OrderColumnInfo orderColumn) {
        this(tableName, ownerForeignKeyColumn, ownerForeignKeyType, valueColumn, valueType,
                elementColumns, orderColumn, null);
    }

    /**
     * 원소가 {@code @Embeddable}이면 {@code true} — 값을 {@link #elementColumns()} 다중 컬럼으로 저장한다.
     */
    public boolean embeddable() {
        return !elementColumns.isEmpty();
    }

    /**
     * {@code @OrderColumn}이 선언되어 collection table에 물리 순서 정수 컬럼({@link #orderColumn()})을 두면 {@code true}.
     */
    public boolean ordered() {
        return orderColumn != null;
    }

    /**
     * collection table이 {@code Map<K,V>}를 저장하면 {@code true} — {@link #mapKey()} key 컬럼을 둔다.
     */
    public boolean map() {
        return mapKey != null;
    }

    /**
     * {@code @Embeddable} 원소를 펼친 collection table 컬럼 하나의 물리 정의(컬럼 이름 + 저장 Java 타입).
     */
    public record ElementColumn(String columnName, Class<?> columnType) {
    }

    /**
     * {@code Map<K,V>} collection table의 key 컬럼 물리 정의(컬럼 이름 + 저장 Java 타입). DDL/SQL 렌더링에 쓴다.
     */
    public record MapKeyColumn(String columnName, Class<?> columnType) {
    }
}
