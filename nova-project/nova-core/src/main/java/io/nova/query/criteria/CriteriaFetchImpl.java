package io.nova.query.criteria;

import io.nova.metadata.PersistentProperty;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.FetchParent;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

import java.util.Set;

/**
 * {@code from.fetch("assoc")}가 반환하는 fetch join 핸들. Nova는 blocking lazy proxy 없이 매핑 연관을
 * 항상 eager로 hydrate하므로(AGENTS.md #4), fetch join은 별도 SQL을 만들지 않는 no-op 마커로 수용된다 —
 * 실제 연관 로딩은 엔티티 하이드레이션 경로의 always-eager 의미(JPQL EntityGraph 사이클에서 확립)로
 * 보장된다. 중첩 fetch는 v1에서 fail-fast한다.
 *
 * @param <Z> fetch를 만든 부모 소스 타입
 * @param <X> fetch 대상 연관 타입
 */
final class CriteriaFetchImpl<Z, X> implements Fetch<Z, X> {

    private final FetchParent<?, Z> parent;
    private final PersistentProperty association;
    private final JoinType joinType;

    CriteriaFetchImpl(FetchParent<?, Z> parent, PersistentProperty association, JoinType joinType) {
        this.parent = parent;
        this.association = association;
        this.joinType = joinType;
    }

    @Override
    public JoinType getJoinType() {
        return joinType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public FetchParent<?, Z> getParent() {
        return parent;
    }

    @Override
    public Attribute<? super Z, ?> getAttribute() {
        throw new CriteriaException("Fetch.getAttribute() (metamodel attribute) is not supported in v1");
    }

    @Override
    public Set<Fetch<X, ?>> getFetches() {
        return Set.of();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute) {
        throw nestedFetch();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute, JoinType jt) {
        throw nestedFetch();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute) {
        throw nestedFetch();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute, JoinType jt) {
        throw nestedFetch();
    }

    @Override
    public <X2, Y> Fetch<X2, Y> fetch(String attributeName) {
        throw nestedFetch();
    }

    @Override
    public <X2, Y> Fetch<X2, Y> fetch(String attributeName, JoinType jt) {
        throw nestedFetch();
    }

    PersistentProperty association() {
        return association;
    }

    private static CriteriaException nestedFetch() {
        return new CriteriaException("Nested Criteria fetch joins are not supported in v1");
    }
}
