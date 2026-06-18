package io.nova.metadata;

import java.lang.reflect.Field;
import java.util.List;

/**
 * {@code @ElementCollection} 값 컬렉션의 collection table 매핑 메타데이터. 별도 테이블에 owner FK와 값 컬럼(들)
 * 행으로 원소들을 저장한다.
 * <p>
 * 두 가지 원소 형태를 표현한다:
 * <ul>
 *   <li><b>기본 타입 원소</b>(String/Integer/Long/...): 단일 값 컬럼({@link #valueColumn()})에 저장한다.
 *       {@link #embeddableColumns()}는 빈 리스트다.</li>
 *   <li><b>{@code @Embeddable} 원소</b>: 원소 타입의 영속 필드들을 다중 컬럼({@link #embeddableColumns()})으로
 *       펼쳐 저장한다. 이때 {@link #valueColumn()}은 의미가 없으며 {@link #valueType()}은 {@code @Embeddable}
 *       타입 자신이다.</li>
 * </ul>
 * 부모 테이블에는 컬럼이 없는 marker property다.
 */
public record ElementCollectionInfo(
        String collectionTableName,
        String ownerForeignKeyColumn,
        String valueColumn,
        Class<?> valueType,
        boolean usesSet,
        List<EmbeddableColumn> embeddableColumns
) {
    public ElementCollectionInfo {
        embeddableColumns = embeddableColumns == null ? List.of() : List.copyOf(embeddableColumns);
    }

    /**
     * 기본 타입 원소용 생성자 — {@code @Embeddable} 펼침 컬럼이 없는 단일 값 컬럼 형태.
     */
    public ElementCollectionInfo(
            String collectionTableName,
            String ownerForeignKeyColumn,
            String valueColumn,
            Class<?> valueType,
            boolean usesSet) {
        this(collectionTableName, ownerForeignKeyColumn, valueColumn, valueType, usesSet, List.of());
    }

    /**
     * 원소가 {@code @Embeddable}이면 {@code true} — 값을 다중 컬럼({@link #embeddableColumns()})으로 펼쳐 저장한다.
     */
    public boolean embeddable() {
        return !embeddableColumns.isEmpty();
    }

    /**
     * {@code @Embeddable} 원소의 영속 필드 하나를 collection table 컬럼으로 매핑한 정보. {@code field}는 원소
     * 인스턴스에서 값을 읽고/쓰기 위한 reflective handle이고, {@code columnName}은 (선택적 {@code @AttributeOverride}
     * 반영된) 물리 컬럼 이름, {@code columnType}은 저장 표현의 Java 타입(primitive는 wrapper로 정규화됨)이다.
     */
    public record EmbeddableColumn(Field field, String columnName, Class<?> columnType) {
    }
}
