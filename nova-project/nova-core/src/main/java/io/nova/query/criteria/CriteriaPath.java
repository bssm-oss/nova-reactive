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
 * FROM 소스({@link CriteriaFrom} = 루트 또는 join)의 스칼라 속성을 가리키는 단말 컬럼 경로. 대응하는
 * {@link PersistentProperty}를 미리 해석해 두므로 미존재 필드는 조립 시점에 fail-fast한다. 스칼라 컬럼은
 * 더 이상 탐색할 수 없으므로 여기서 {@link #get(String)}을 호출하면 거부한다(연관 탐색은
 * {@link CriteriaAssociationPath}가 담당한다).
 *
 * @param <X> 속성의 Java 타입
 */
final class CriteriaPath<X> extends AbstractCriteriaExpression<X> implements Path<X>, CriteriaColumnPath {

    private final CriteriaFrom source;
    private final PersistentProperty property;

    CriteriaPath(CriteriaFrom source, PersistentProperty property, Class<X> javaType) {
        super(javaType);
        this.source = source;
        this.property = property;
    }

    @Override
    public EntityMetadata<?> ownerMetadata() {
        return source.metadata();
    }

    @Override
    public PersistentProperty property() {
        return property;
    }

    @Override
    public CriteriaFrom source() {
        return source;
    }

    // --- Path -----------------------------------------------------------------------------------

    @Override
    public Bindable<X> getModel() {
        throw unsupported("Path.getModel()");
    }

    @Override
    public Path<?> getParentPath() {
        if (source instanceof Path<?> path) {
            return path;
        }
        throw unsupported("Path.getParentPath()");
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
        throw new CriteriaException("Cannot navigate '" + attributeName + "' from scalar attribute '"
                + property.propertyName() + "'; only association paths can be navigated further");
    }
}
