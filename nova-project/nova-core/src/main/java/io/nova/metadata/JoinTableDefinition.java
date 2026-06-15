package io.nova.metadata;

/**
 * {@code @ManyToMany} link table의 물리 정의 — DDL 생성과 link-row SQL 렌더링에 쓰인다. 두 FK 컬럼과 그
 * Java 타입(owner/target 엔티티의 {@code @Id} 타입)을 담으며, 복합 PK는 두 컬럼으로 구성된다. v1은 단일
 * 키 owner/target만 지원하므로 FK는 컬럼 하나씩이다.
 */
public record JoinTableDefinition(
        String tableName,
        String ownerForeignKeyColumn,
        Class<?> ownerForeignKeyType,
        String targetForeignKeyColumn,
        Class<?> targetForeignKeyType
) {
}
