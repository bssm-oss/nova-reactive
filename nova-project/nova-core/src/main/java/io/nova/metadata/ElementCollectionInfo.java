package io.nova.metadata;

import jakarta.persistence.EnumType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code @ElementCollection} 값 컬렉션의 collection table 매핑 메타데이터. 별도 테이블에 owner FK와 값 컬럼(들)
 * 행으로 원소들을 저장한다.
 * <p>
 * 세 가지 컬렉션 형태를 표현한다:
 * <ul>
 *   <li><b>기본 타입 원소</b>(String/Integer/Long/...): 단일 값 컬럼({@link #valueColumn()})에 저장한다.
 *       {@link #embeddableColumns()}는 빈 리스트다.</li>
 *   <li><b>{@code @Embeddable} 원소</b>: 원소 타입의 영속 필드들을 다중 컬럼({@link #embeddableColumns()})으로
 *       펼쳐 저장한다. 이때 {@link #valueColumn()}은 의미가 없으며 {@link #valueType()}은 {@code @Embeddable}
 *       타입 자신이다.</li>
 *   <li><b>{@code Map<K,V>}</b>: {@link #mapKey()}가 non-null이며 collection table에 key 컬럼이 추가된다. 값
 *       표현(기본 타입/{@code @Embeddable})은 위 두 형태를 그대로 재사용한다.</li>
 * </ul>
 * 부모 테이블에는 컬럼이 없는 marker property다.
 */
public record ElementCollectionInfo(
        String collectionTableName,
        String ownerForeignKeyColumn,
        String valueColumn,
        Class<?> valueType,
        boolean usesSet,
        List<EmbeddableColumn> embeddableColumns,
        OrderColumnInfo orderColumn,
        MapKeyInfo mapKey
) {
    public ElementCollectionInfo {
        embeddableColumns = embeddableColumns == null ? List.of() : List.copyOf(embeddableColumns);
    }

    /**
     * 기본 타입 원소용 생성자 — {@code @Embeddable} 펼침 컬럼도 순서 컬럼도 map key도 없는 단일 값 컬럼 형태.
     */
    public ElementCollectionInfo(
            String collectionTableName,
            String ownerForeignKeyColumn,
            String valueColumn,
            Class<?> valueType,
            boolean usesSet) {
        this(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType, usesSet, List.of(), null, null);
    }

    /**
     * {@code @Embeddable} 원소용 생성자 — 펼침 컬럼은 있고 순서 컬럼/map key는 없는 형태.
     */
    public ElementCollectionInfo(
            String collectionTableName,
            String ownerForeignKeyColumn,
            String valueColumn,
            Class<?> valueType,
            boolean usesSet,
            List<EmbeddableColumn> embeddableColumns) {
        this(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType, usesSet, embeddableColumns, null, null);
    }

    /**
     * 정렬({@code @OrderColumn}) 정보까지 받는 생성자 — map key는 없는 형태(List/Set 값 컬렉션).
     */
    public ElementCollectionInfo(
            String collectionTableName,
            String ownerForeignKeyColumn,
            String valueColumn,
            Class<?> valueType,
            boolean usesSet,
            List<EmbeddableColumn> embeddableColumns,
            OrderColumnInfo orderColumn) {
        this(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType, usesSet,
                embeddableColumns, orderColumn, null);
    }

    /**
     * 원소가 {@code @Embeddable}이면 {@code true} — 값을 다중 컬럼({@link #embeddableColumns()})으로 펼쳐 저장한다.
     */
    public boolean embeddable() {
        return !embeddableColumns.isEmpty();
    }

    /**
     * {@code @OrderColumn}이 선언되어 물리 순서를 별도 정수 컬럼에 영속하면 {@code true}. {@code @OrderColumn}이
     * 없으면 {@code false}이며 collection table에 순서 컬럼을 두지 않는다.
     */
    public boolean ordered() {
        return orderColumn != null;
    }

    /**
     * 컬렉션이 {@code Map<K,V>}이면 {@code true} — collection table에 {@link #mapKey()} key 컬럼이 추가되고
     * reconcile/hydration이 (owner FK, key, value) 행으로 entry를 저장/복원한다. {@code List}/{@code Set}이면
     * {@code false}이며 {@link #mapKey()}는 {@code null}이다.
     */
    public boolean map() {
        return mapKey != null;
    }

    /**
     * 이 매핑을 DDL/SQL 렌더링용 {@link CollectionTableDefinition}으로 변환한다 — schema 생성과 row 동기화가
     * 같은 정의를 공유하도록 한 자리에서 만든다. {@code ownerForeignKeyType}은 owner {@code @Id}의 Java 타입(호출자가
     * primitive wrap 여부를 결정해 넘긴다). {@code @Embeddable} 펼침 컬럼, {@code @OrderColumn}, {@code Map} key
     * 컬럼을 모두 반영한다.
     */
    public CollectionTableDefinition toCollectionTableDefinition(Class<?> ownerForeignKeyType) {
        List<CollectionTableDefinition.ElementColumn> elementColumns = new ArrayList<>();
        for (EmbeddableColumn column : embeddableColumns) {
            elementColumns.add(new CollectionTableDefinition.ElementColumn(column.columnName(), column.columnType()));
        }
        CollectionTableDefinition.MapKeyColumn keyColumn = mapKey == null
                ? null
                : new CollectionTableDefinition.MapKeyColumn(mapKey.keyColumn(), mapKey.keyColumnType());
        return new CollectionTableDefinition(
                collectionTableName, ownerForeignKeyColumn, ownerForeignKeyType,
                valueColumn, valueType, elementColumns, orderColumn, keyColumn);
    }

    /**
     * {@code @Embeddable} 원소의 영속 필드 하나를 collection table 컬럼으로 매핑한 정보. {@code field}는 원소
     * 인스턴스에서 값을 읽고/쓰기 위한 reflective handle이고, {@code columnName}은 (선택적 {@code @AttributeOverride}
     * 반영된) 물리 컬럼 이름, {@code columnType}은 저장 표현의 Java 타입(primitive는 wrapper로 정규화됨)이다.
     */
    public record EmbeddableColumn(Field field, String columnName, Class<?> columnType) {
    }

    /**
     * {@code @ElementCollection Map<K,V>}의 key 매핑 메타데이터. collection table에 추가되는 key 컬럼의 이름과
     * 저장 표현 타입을 담는다.
     * <ul>
     *   <li>{@code keyType} — 도메인 key 타입(enum 클래스 또는 String/Integer/Long 등 기본 타입).</li>
     *   <li>{@code keyColumnType} — key 컬럼의 저장 표현 Java 타입. enum key는 {@code @MapKeyEnumerated}에 따라
     *       {@link String}(STRING) 또는 {@link Integer}(ORDINAL)로, 기본 타입 key는 자기 자신(wrapper 정규화)으로
     *       저장된다. DDL/SQL 컬럼 타입 유도에 쓴다.</li>
     *   <li>{@code keyEnumType} — enum key의 저장 전략({@code STRING}/{@code ORDINAL}). enum key가 아니면 {@code null}.</li>
     * </ul>
     * {@code @MapKey}(엔티티 property를 key로) 및 {@code @Embeddable} key는 v1 범위 밖이며
     * {@link EntityMetadataFactory}가 fail-fast로 거부한다.
     */
    public record MapKeyInfo(String keyColumn, Class<?> keyType, Class<?> keyColumnType, EnumType keyEnumType) {
        /**
         * key가 enum이면 {@code true}({@link #keyEnumType()}이 {@code STRING}/{@code ORDINAL}).
         */
        public boolean enumKey() {
            return keyEnumType != null;
        }
    }
}
