package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;

/**
 * {@code cb.equal(root.type(), Subtype.class)}가 만드는 다형성 discriminator 술어. SINGLE_TABLE 상속
 * 계층에서 {@code discriminator = subtypeValue}로 렌더되거나(스칼라 경로), 엔티티 반환 경로에서는 조회
 * 대상 타입을 서브타입으로 좁히는 데 쓰인다(코어 {@code findAll(Subtype)}가 discriminator 제한을 적용).
 * <p>
 * {@link CriteriaPredicate}를 상속하되 렌더/변환 지점에서 {@code instanceof}로 먼저 가로채므로 base의
 * kind 기반 switch에는 도달하지 않는다.
 */
final class DiscriminatorPredicate extends CriteriaPredicate {

    private final EntityMetadata<?> baseMetadata;
    private final EntityMetadata<?> subtypeMetadata;

    DiscriminatorPredicate(EntityMetadata<?> baseMetadata, EntityMetadata<?> subtypeMetadata) {
        super(Kind.COMPARISON, null, null, null, null, null, null, null, null, false);
        this.baseMetadata = baseMetadata;
        this.subtypeMetadata = subtypeMetadata;
    }

    /** 계층 루트(또는 좌변 소스) 엔티티 메타데이터 — discriminator 컬럼 출처. */
    EntityMetadata<?> baseMetadata() {
        return baseMetadata;
    }

    /** 이 술어가 지정하는 구체 서브타입 — 조회 타입 narrowing 및 discriminator 값 출처. */
    EntityMetadata<?> subtypeMetadata() {
        return subtypeMetadata;
    }

    /** discriminator 컬럼 이름(계층 공통). */
    String discriminatorColumn() {
        return baseMetadata.inheritance().discriminatorColumn();
    }

    /** 이 서브타입을 식별하는 discriminator 바인딩 값. */
    Object discriminatorValue() {
        return subtypeMetadata.inheritance().discriminatorBindValue();
    }
}
