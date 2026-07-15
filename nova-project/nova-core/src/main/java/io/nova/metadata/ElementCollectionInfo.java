package io.nova.metadata;

import jakarta.persistence.EnumType;
import io.nova.convert.AttributeConverter;

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
        MapKeyInfo mapKey,
        Class<?> valueColumnType,
        AttributeConverter<Object, Object> valueConverter
) {
    public ElementCollectionInfo {
        embeddableColumns = embeddableColumns == null ? List.of() : List.copyOf(embeddableColumns);
        // 저장 표현 컬럼 타입이 명시되지 않으면 도메인 타입을 그대로 저장타입으로 쓴다(기본 스칼라 원소).
        valueColumnType = valueColumnType == null ? valueType : valueColumnType;
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
        this(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType, usesSet,
                List.of(), null, null, null, null);
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
        this(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType, usesSet,
                embeddableColumns, null, null, null, null);
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
                embeddableColumns, orderColumn, null, null, null);
    }

    /**
     * {@code @Embeddable}/{@code @OrderColumn}/{@code Map} key까지 받는 canonical 근접 생성자에서 원소
     * 저장타입/컨버터를 생략한 형태 — 기본 스칼라 원소용. 저장타입은 {@link #valueType()}과 같고
     * 컨버터는 없다({@code null}).
     */
    public ElementCollectionInfo(
            String collectionTableName,
            String ownerForeignKeyColumn,
            String valueColumn,
            Class<?> valueType,
            boolean usesSet,
            List<EmbeddableColumn> embeddableColumns,
            OrderColumnInfo orderColumn,
            MapKeyInfo mapKey) {
        this(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType, usesSet,
                embeddableColumns, orderColumn, mapKey, null, null);
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
     * 기본 타입 원소의 도메인 값을 collection table에 바인딩할 저장 표현으로 인코딩한다 — 스칼라 프로퍼티의
     * {@link PersistentProperty#toColumnValue(Object)}와 동일한 converter 경로를 탄다. enum은 이름/ordinal로,
     * {@code UUID}는 문자열로, {@code @Convert}/{@code @Temporal} 원소는 각 converter 저장 표현으로 바뀐다.
     * 컨버터가 없는 순수 기본 타입(String/Long/...)은 값을 그대로 통과시킨다. {@code null}은 그대로 둔다.
     */
    public Object encodeElementValue(Object value) {
        return (value == null || valueConverter == null) ? value : valueConverter.write(value);
    }

    /**
     * collection table에서 읽은 저장 표현({@link #valueColumnType()} 타입)을 도메인 원소 값으로 디코딩한다 —
     * 스칼라 프로퍼티의 {@link PersistentProperty#toPropertyValue(Object)}와 대칭이다. 컨버터가 없으면 값을
     * 그대로 통과시킨다. {@code null}은 그대로 둔다.
     */
    public Object decodeElementValue(Object stored) {
        return (stored == null || valueConverter == null) ? stored : valueConverter.read(stored);
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
        // Map key 컬럼: @Embeddable key는 다중 컬럼(mapKeyColumns)으로, 그 외(기본/enum/temporal/UUID)는 단일
        // 컬럼(keyColumn)으로 표현한다. 둘 다 mapKey==null이면 map이 아니다.
        CollectionTableDefinition.MapKeyColumn keyColumn = null;
        List<CollectionTableDefinition.ElementColumn> mapKeyColumns = List.of();
        if (mapKey != null) {
            if (mapKey.embeddableKey()) {
                mapKeyColumns = mapKey.embeddableKeyColumns().stream()
                        .map(column -> new CollectionTableDefinition.ElementColumn(
                                column.columnName(), column.columnType()))
                        .toList();
            } else {
                keyColumn = new CollectionTableDefinition.MapKeyColumn(mapKey.keyColumn(), mapKey.keyColumnType());
            }
        }
        // DDL/SQL 컬럼 타입은 도메인 타입(valueType)이 아니라 저장 표현 타입(valueColumnType)을 따라야 한다
        // (enum→varchar/integer, UUID→varchar 등). @Embeddable 원소는 valueColumnType이 valueType과 같아
        // (elementColumns가 실제 컬럼을 결정하므로) 무해하다.
        return new CollectionTableDefinition(
                collectionTableName, ownerForeignKeyColumn, ownerForeignKeyType,
                valueColumn, valueColumnType, elementColumns, orderColumn, keyColumn, mapKeyColumns);
    }

    /**
     * {@code valueConverter}는 저장 표현 인코딩/디코딩을 담당하는 전략 객체일 뿐 매핑 identity의 일부가 아니다.
     * 동일한 원소 매핑이라도 converter 인스턴스가 다르면 record의 기본 equality가 unequal로 판정하므로,
     * converter를 <em>제외한</em> 나머지 매핑 컴포넌트로만 동등성을 정의한다({@code valueColumnType}은 converter와
     * 함께 결정되므로 포함해도 안전하고, 매핑을 구분하는 데 도움이 된다).
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ElementCollectionInfo that)) {
            return false;
        }
        return usesSet == that.usesSet
                && java.util.Objects.equals(collectionTableName, that.collectionTableName)
                && java.util.Objects.equals(ownerForeignKeyColumn, that.ownerForeignKeyColumn)
                && java.util.Objects.equals(valueColumn, that.valueColumn)
                && java.util.Objects.equals(valueType, that.valueType)
                && java.util.Objects.equals(embeddableColumns, that.embeddableColumns)
                && java.util.Objects.equals(orderColumn, that.orderColumn)
                && java.util.Objects.equals(mapKey, that.mapKey)
                && java.util.Objects.equals(valueColumnType, that.valueColumnType);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType,
                usesSet, embeddableColumns, orderColumn, mapKey, valueColumnType);
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
     *       {@link String}(STRING) 또는 {@link Integer}(ORDINAL)로, {@code UUID} key는 {@link String}(varchar)으로,
     *       그 외 기본 타입 key는 자기 자신(wrapper 정규화)으로 저장된다. DDL/SQL 컬럼 타입 유도에 쓴다.</li>
     *   <li>{@code keyEnumType} — enum key의 저장 전략({@code STRING}/{@code ORDINAL}). enum key가 아니면 {@code null}.</li>
     *   <li>{@code keyConverter} — 저장 표현 인코딩/디코딩 converter. 저장타입이 도메인 타입과 다른 non-String
     *       key(예: {@code UUID}→varchar via {@link io.nova.convert.UuidStringConverter})에 설정되며, 저장타입=도메인
     *       타입인 순수 기본 타입과 enum key(operations가 이름/ordinal로 직접 처리)는 {@code null}이다. R2DBC 드라이버가
     *       {@code varchar}→{@code UUID} 직접 디코딩을 못 하는 read-source-type 함정을 EC value와 대칭으로 피한다.</li>
     * </ul>
     * {@code keyType}이 {@code @Embeddable}(다중 컬럼) key이면 {@link #embeddableKeyColumns()}에 펼친 key 컬럼들이
     * 담기고 {@link #keyColumn()}/{@link #keyColumnType()}/{@link #keyEnumType()}/{@code keyConverter}는 의미가
     * 없다(operations가 각 컬럼을 개별 처리한다). {@code keyType}이 단일 {@code @Id} {@code @Entity} key이면
     * {@link #entityKey()}가 {@code true}이며, key 컬럼은 참조 {@code @Id}와 동일한 단일 FK 저장 규칙(단일컬럼 to-one
     * FK와 동일)을 따른다. {@code @MapKey}(엔티티 property를 key로) 및 복합 {@code @Id} entity key 클래스는 v1 범위
     * 밖이며 {@link EntityMetadataFactory}가 fail-fast로 거부한다.
     */
    public record MapKeyInfo(String keyColumn, Class<?> keyType, Class<?> keyColumnType, EnumType keyEnumType,
            AttributeConverter<Object, Object> keyConverter, List<EmbeddableColumn> embeddableKeyColumns,
            boolean entityKey) {
        public MapKeyInfo {
            // @Embeddable(다중 컬럼) key의 펼침 컬럼 목록. 단일 컬럼 key(기본/enum/temporal/UUID/entity)는 빈 리스트다.
            embeddableKeyColumns = embeddableKeyColumns == null ? List.of() : List.copyOf(embeddableKeyColumns);
        }

        /**
         * enum/순수 기본 타입 key용 — 저장 표현 converter 없이({@code null}) 만든다.
         */
        public MapKeyInfo(String keyColumn, Class<?> keyType, Class<?> keyColumnType, EnumType keyEnumType) {
            this(keyColumn, keyType, keyColumnType, keyEnumType, null, List.of(), false);
        }

        /**
         * 저장타입 분리 단일 컬럼 key(UUID→varchar via converter, temporal 등)용 — {@code @Embeddable} 펼침 컬럼 없이
         * 단일 컬럼 + converter로 만든다.
         */
        public MapKeyInfo(String keyColumn, Class<?> keyType, Class<?> keyColumnType, EnumType keyEnumType,
                AttributeConverter<Object, Object> keyConverter) {
            this(keyColumn, keyType, keyColumnType, keyEnumType, keyConverter, List.of(), false);
        }

        /**
         * {@code @MapKeyClass}가 가리키는 {@code @Embeddable} key용 — key를 다중 컬럼({@code embeddableKeyColumns})으로
         * 펼쳐 저장한다. 단일 key 컬럼/enum/converter는 의미가 없어 각각 {@code ""}/{@code null}이며, {@code keyColumnType}은
         * {@code @Embeddable} 타입 자신이다(실제 컬럼 타입은 {@code embeddableKeyColumns}가 결정).
         */
        public static MapKeyInfo embeddable(Class<?> keyType, List<EmbeddableColumn> embeddableKeyColumns) {
            return new MapKeyInfo("", keyType, keyType, null, null, embeddableKeyColumns, false);
        }

        /**
         * {@code @MapKeyClass}가 가리키는 단일 {@code @Id} {@code @Entity} key용 — key 컬럼은 참조 {@code @Id}의
         * 단일컬럼 FK 저장 표현(단일 to-one FK와 동일 규칙)을 그대로 쓴다. {@code keyColumnType}/{@code keyConverter}는
         * 참조 {@code @Id}의 저장타입/converter이고, {@code keyEnumType}은 의미가 없어 {@code null}이다.
         */
        public static MapKeyInfo entity(String keyColumn, Class<?> keyType, Class<?> keyColumnType,
                AttributeConverter<Object, Object> keyConverter) {
            return new MapKeyInfo(keyColumn, keyType, keyColumnType, null, keyConverter, List.of(), true);
        }

        /**
         * key가 {@code @Embeddable}(다중 컬럼)이면 {@code true} — {@link #embeddableKeyColumns()}에 펼친 key 컬럼들이
         * 있고, operations는 각 컬럼을 개별 읽고/써서 key 인스턴스를 재구성한다.
         */
        public boolean embeddableKey() {
            return !embeddableKeyColumns.isEmpty();
        }

        /**
         * key가 enum이면 {@code true}({@link #keyEnumType()}이 {@code STRING}/{@code ORDINAL}).
         */
        public boolean enumKey() {
            return keyEnumType != null;
        }

        /**
         * 도메인 map key를 collection table 바인딩용 저장 표현으로 인코딩한다({@code UUID}→문자열 등). converter가
         * 없으면(순수 기본 타입) 값을 그대로 통과시킨다. enum key는 operations가 이름/ordinal로 별도 처리하므로
         * 여기서는 관여하지 않는다. {@code null}은 그대로 둔다.
         */
        public Object encodeKey(Object key) {
            return (key == null || keyConverter == null) ? key : keyConverter.write(key);
        }

        /**
         * collection table에서 읽은 저장 표현({@link #keyColumnType()} 타입)을 도메인 map key로 디코딩한다 —
         * {@link #encodeKey(Object)}와 대칭. converter가 없으면 값을 그대로 통과시킨다. {@code null}은 그대로 둔다.
         */
        public Object decodeKey(Object stored) {
            return (stored == null || keyConverter == null) ? stored : keyConverter.read(stored);
        }

        /**
         * {@code keyConverter}는 저장 표현 인코딩/디코딩 전략일 뿐 매핑 identity의 일부가 아니다({@link ElementCollectionInfo}의
         * {@code valueConverter} 취급과 동일). 동일한 key 매핑이라도 converter 인스턴스가 다르면 record 기본 equality가
         * unequal로 판정하므로, converter를 제외한 나머지 컴포넌트로만 동등성을 정의한다({@code keyColumnType}은 converter와
         * 함께 결정되므로 포함해도 안전하다).
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MapKeyInfo that)) {
                return false;
            }
            return java.util.Objects.equals(keyColumn, that.keyColumn)
                    && java.util.Objects.equals(keyType, that.keyType)
                    && java.util.Objects.equals(keyColumnType, that.keyColumnType)
                    && keyEnumType == that.keyEnumType
                    && java.util.Objects.equals(embeddableKeyColumns, that.embeddableKeyColumns)
                    && entityKey == that.entityKey;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    keyColumn, keyType, keyColumnType, keyEnumType, embeddableKeyColumns, entityKey);
        }
    }
}
