package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.Bindable;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

/**
 * {@code root.get("attr")}로 얻는 단일 세그먼트 속성 경로. 대응하는 {@link PersistentProperty}를 미리
 * 해석해 두므로 미존재 필드는 조립 시점에 fail-fast한다. v1은 다중 세그먼트/연관 경로 탐색을 지원하지
 * 않으므로 이 경로에서 다시 {@link #get(String)}을 호출하면 거부한다.
 *
 * @param <X> 속성의 Java 타입
 */
final class CriteriaPath<X> extends AbstractCriteriaExpression<X> implements Path<X>, CriteriaColumnPath {

    private final CriteriaRoot<?> root;
    private final PersistentProperty property;

    CriteriaPath(CriteriaRoot<?> root, PersistentProperty property, Class<X> javaType) {
        super(javaType);
        this.root = root;
        this.property = property;
    }

    @Override
    public EntityMetadata<?> ownerMetadata() {
        return root.ownerMetadata();
    }

    @Override
    public PersistentProperty property() {
        return property;
    }

    // --- Path -----------------------------------------------------------------------------------

    @Override
    public Bindable<X> getModel() {
        throw unsupported("Path.getModel()");
    }

    @Override
    public Path<?> getParentPath() {
        return root;
    }

    @Override
    public <Y> Path<Y> get(SingularAttribute<? super X, Y> attribute) {
        throw unsupported("Path.get(SingularAttribute)");
    }

    @Override
    public <E, C extends java.util.Collection<E>> Expression<C> get(PluralAttribute<? super X, C, E> attribute) {
        throw unsupported("Path.get(PluralAttribute)");
    }

    @Override
    public <K, V, M extends java.util.Map<K, V>> Expression<M> get(MapAttribute<? super X, K, V> attribute) {
        throw unsupported("Path.get(MapAttribute)");
    }

    @Override
    public Expression<Class<? extends X>> type() {
        throw unsupported("Path.type()");
    }

    @Override
    public <Y> Path<Y> get(String attributeName) {
        throw new CriteriaException("Nested/multi-segment path '" + property.propertyName() + "." + attributeName
                + "' is not supported in v1; only single-segment root attributes are available");
    }
}
