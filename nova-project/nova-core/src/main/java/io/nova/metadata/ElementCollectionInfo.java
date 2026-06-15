package io.nova.metadata;

/**
 * {@code @ElementCollection} 값 컬렉션의 collection table 매핑 메타데이터. 별도 테이블에 (owner FK, value)
 * 행으로 기본 타입 값들을 저장한다. v1은 기본 타입 원소(String/Integer/Long/...)만 지원하며 {@code @Embeddable}
 * 원소는 보류한다. 이 property는 부모 테이블에 컬럼이 없는 marker다.
 */
public record ElementCollectionInfo(
        String collectionTableName,
        String ownerForeignKeyColumn,
        String valueColumn,
        Class<?> valueType,
        boolean usesSet
) {
}
