package io.nova.metadata;

/**
 * {@code @ManyToMany} 관계의 link table 매핑 메타데이터. owning side({@code @JoinTable})와
 * inverse side({@code mappedBy}) 모두 이 정보를 들고 hydration·link 동기화에 사용한다.
 * <p>
 * 컬럼 이름은 항상 <em>이 엔티티</em> 기준으로 정규화된다 — {@link #ownerForeignKeyColumn()}은 link table에서
 * 이 엔티티의 id를 가리키는 컬럼, {@link #targetForeignKeyColumn()}은 대상 엔티티의 id를 가리키는 컬럼이다.
 * inverse side는 owning side의 {@code @JoinTable}을 reflect한 뒤 두 컬럼을 swap해 이 규약을 맞춘다(hydration 대칭성).
 */
public record ManyToManyInfo(
        boolean owning,
        Class<?> targetType,
        String joinTableName,
        String ownerForeignKeyColumn,
        String targetForeignKeyColumn,
        String mappedBy,
        boolean usesSet
) {
}
