package io.nova.query.criteria;

/**
 * {@code from.type()}가 만드는 다형성 타입 식. JPA의 {@code Expression<Class<? extends X>>} 등가로, FROM
 * 소스(루트/조인) 엔티티의 실제 구체 타입(= discriminator)을 나타낸다. 단독 SQL 렌더 대상이 아니라
 * {@code cb.equal(root.type(), Subtype.class)} 형태로 discriminator 술어를 만들 때만 쓰인다 —
 * {@link SimpleCriteriaBuilder#equal(jakarta.persistence.criteria.Expression, Object)}가 이 식을 인식해
 * {@link DiscriminatorPredicate}로 변환한다.
 */
final class CriteriaTypeExpression extends AbstractCriteriaExpression<Class> {

    private final CriteriaFrom source;

    CriteriaTypeExpression(CriteriaFrom source) {
        super(Class.class);
        this.source = source;
    }

    /** 이 타입 식이 가리키는 FROM 소스(루트/조인). */
    CriteriaFrom source() {
        return source;
    }
}
