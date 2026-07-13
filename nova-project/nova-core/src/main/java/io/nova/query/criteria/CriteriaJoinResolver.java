package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;

/**
 * 연관 property 하나를 실제 SQL JOIN 조건으로 해석한다. {@code @ManyToOne}(및 동일 모델링인 owning
 * {@code @OneToOne})은 부모 테이블의 FK 컬럼을 대상 PK와 잇고, {@code @OneToMany}/inverse
 * {@code @OneToOne}은 부모 PK를 자식 테이블의 FK와 잇는다. {@code @ManyToMany}/{@code @ElementCollection}
 * 처럼 link/collection 테이블을 거치는 연관은 v1 join 범위를 벗어나 fail-fast한다.
 * <p>
 * 대상 엔티티/컬럼 이름은 {@link EntityMetadata}에서만 얻으므로(화이트리스트), 렌더된 식별자는 항상
 * 매핑된 컬럼으로 제한된다 — 사용자 입력이 식별자 자리에 끼어들 여지가 없다.
 */
final class CriteriaJoinResolver {

    /**
     * 해석 결과: 대상 엔티티 메타데이터와, {@code on parentAlias.parentColumn = childAlias.childColumn}에
     * 들어갈 두 컬럼 이름.
     */
    record JoinTarget(EntityMetadata<?> targetMetadata, String parentColumn, String childColumn) {
    }

    private CriteriaJoinResolver() {
    }

    static JoinTarget resolve(EntityMetadata<?> ownerMetadata, PersistentProperty association, CriteriaContext context) {
        if (association.manyToMany()) {
            throw new CriteriaException("Criteria join over @ManyToMany association '"
                    + association.propertyName() + "' is not supported (link-table joins are out of scope); "
                    + "@ManyToOne/@OneToOne/@OneToMany are supported");
        }
        if (association.elementCollection()) {
            throw new CriteriaException("Criteria join over @ElementCollection '"
                    + association.propertyName() + "' is not supported");
        }
        if (association.manyToOne()) {
            // owning @ManyToOne / @OneToOne: 부모 테이블 FK → 대상 PK.
            EntityMetadata<?> target = context.resolve(association.manyToOneTargetType());
            String parentColumn = association.columnName();
            String childColumn = singleIdColumn(target, association);
            return new JoinTarget(target, parentColumn, childColumn);
        }
        if (association.oneToMany()) {
            // @OneToMany(mappedBy): 부모 PK → 자식 테이블의 owning FK 컬럼.
            EntityMetadata<?> target = context.resolve(association.oneToManyTargetType());
            String parentColumn = singleIdColumn(ownerMetadata, association);
            String childColumn = mappedByFkColumn(target, association.oneToManyMappedBy(), association);
            return new JoinTarget(target, parentColumn, childColumn);
        }
        if (association.inverseToOne()) {
            // inverse @OneToOne(mappedBy): 부모 PK → 대상 테이블의 owning FK 컬럼. mappedBy로 owning
            // property를 정확히 지목한다(같은 owner 타입 참조가 여러 개여도 모호하지 않게).
            EntityMetadata<?> target = context.resolve(association.javaType());
            String parentColumn = singleIdColumn(ownerMetadata, association);
            String childColumn = owningFkColumnTo(target, ownerMetadata.entityType(), association);
            return new JoinTarget(target, parentColumn, childColumn);
        }
        throw new CriteriaException("Attribute '" + association.propertyName()
                + "' is not a joinable association");
    }

    private static String singleIdColumn(EntityMetadata<?> metadata, PersistentProperty association) {
        if (metadata.hasCompositeId()) {
            throw new CriteriaException("Criteria join involving composite-id entity '"
                    + metadata.entityType().getSimpleName() + "' (via '" + association.propertyName()
                    + "') is not supported in v1");
        }
        return metadata.idProperty().columnName();
    }

    private static String mappedByFkColumn(EntityMetadata<?> childMetadata, String mappedBy, PersistentProperty association) {
        if (mappedBy == null || mappedBy.isBlank()) {
            throw new CriteriaException("@OneToMany '" + association.propertyName()
                    + "' without mappedBy cannot be joined in Criteria v1");
        }
        PersistentProperty owning = childMetadata.findProperty(mappedBy)
                .orElseThrow(() -> new CriteriaException("@OneToMany '" + association.propertyName()
                        + "' mappedBy='" + mappedBy + "' has no matching property on "
                        + childMetadata.entityType().getSimpleName()));
        return owning.columnName();
    }

    private static String owningFkColumnTo(EntityMetadata<?> targetMetadata, Class<?> ownerType, PersistentProperty association) {
        // inverse @OneToOne은 mappedBy를 (oneToMany 필드 자리에) 보관한다. mappedBy로 owning property를
        // 정확히 지목해 FK 컬럼을 얻는다 — 대상 엔티티가 같은 owner 타입으로 여러 owning 관계를 가져도
        // 모호하지 않다.
        String mappedBy = association.oneToManyMappedBy();
        if (mappedBy != null && !mappedBy.isBlank()) {
            PersistentProperty owning = targetMetadata.findProperty(mappedBy)
                    .orElseThrow(() -> new CriteriaException("inverse @OneToOne '" + association.propertyName()
                            + "' mappedBy='" + mappedBy + "' has no matching property on "
                            + targetMetadata.entityType().getSimpleName()));
            if (!owning.manyToOne()) {
                throw new CriteriaException("inverse @OneToOne '" + association.propertyName()
                        + "' mappedBy='" + mappedBy + "' does not point at an owning to-one FK on "
                        + targetMetadata.entityType().getSimpleName());
            }
            return owning.columnName();
        }
        // mappedBy가 없는 경로(정상적인 inverse @OneToOne에서는 발생하지 않음)에서도 owner 타입 참조가
        // 둘 이상이면 silent로 첫 매치를 고르지 않고 명시적으로 거부한다.
        String resolved = null;
        for (PersistentProperty candidate : targetMetadata.manyToOneProperties()) {
            Class<?> referenced = candidate.manyToOneTargetType();
            if (referenced != null && referenced.isAssignableFrom(ownerType)) {
                if (resolved != null) {
                    throw new CriteriaException("inverse @OneToOne '" + association.propertyName()
                            + "' is ambiguous: " + targetMetadata.entityType().getSimpleName()
                            + " declares multiple owning to-one references to "
                            + ownerType.getSimpleName() + "; specify mappedBy to disambiguate");
                }
                resolved = candidate.columnName();
            }
        }
        if (resolved == null) {
            throw new CriteriaException("inverse @OneToOne '" + association.propertyName()
                    + "' has no owning FK on " + targetMetadata.entityType().getSimpleName());
        }
        return resolved;
    }
}
