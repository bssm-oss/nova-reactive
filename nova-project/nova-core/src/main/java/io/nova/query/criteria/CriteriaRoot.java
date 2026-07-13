package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;

/**
 * {@code CriteriaQuery.from(Class)}가 만드는 쿼리 루트. 단일 엔티티 테이블을 나타내며, 스칼라 속성은
 * {@link #get(String)}으로 {@link CriteriaPath}가 되고, 연관 속성은 {@link CriteriaAssociationPath}가 되어
 * 묵시적 join으로 확장되거나 {@link #join(String)}으로 명시적 SQL JOIN이 된다. join/탐색의 공통 동작은
 * {@link AbstractCriteriaFrom}가 담당한다.
 * <p>
 * 상관 서브쿼리에서 {@code subquery.correlate(root)}로 참조되면 이 루트는 outer 쿼리의 FROM에 남고
 * 서브쿼리는 correlate된 루트의 alias를 그대로 참조한다.
 *
 * @param <X> 루트 엔티티 타입
 */
final class CriteriaRoot<X> extends AbstractCriteriaFrom<X, X> implements Root<X> {

    CriteriaRoot(Class<X> entityType, EntityMetadata<X> metadata, CriteriaContext context) {
        super(entityType, metadata, context);
    }

    Class<X> entityType() {
        return entityClass();
    }

    // --- Path / Root surface ------------------------------------------------------------------

    @Override
    public EntityType<X> getModel() {
        throw unsupported("Root.getModel()");
    }

    @Override
    public Path<?> getParentPath() {
        return null;
    }

    @Override
    public From<X, X> getCorrelationParent() {
        throw unsupported("Root.getCorrelationParent()");
    }
}
