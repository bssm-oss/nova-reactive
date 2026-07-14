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
 * {@code from.get("assoc")}가 만드는 연관 경로 노드. 두 가지로 쓰인다.
 * <ul>
 *   <li>다시 {@link #get(String)}으로 탐색하면({@code root.get("dept").get("name")}) 부모 소스에 대한
 *       <b>묵시적 INNER join</b>을 만들고 그 join 대상 컬럼을 반환한다.
 *   <li>owning 단건 연관({@code @ManyToOne}/owning {@code @OneToOne})은 그 자체를 술어/select에 쓰면
 *       부모 테이블의 FK 컬럼으로 해석된다({@code cb.equal(e.get("dept"), 5L)},
 *       {@code cb.isNull(e.get("dept"))}).
 * </ul>
 * {@code @OneToMany}/inverse {@code @OneToOne}은 부모 테이블에 컬럼이 없으므로 직접 컬럼으로 쓰면
 * fail-fast하고, {@code get(...)}/{@code join(...)}으로만 탐색할 수 있다.
 */
final class CriteriaAssociationPath extends AbstractCriteriaExpression<Object>
        implements Path<Object>, CriteriaColumnPath {

    private final AbstractCriteriaFrom<?, ?> parent;
    private final PersistentProperty association;

    CriteriaAssociationPath(AbstractCriteriaFrom<?, ?> parent, PersistentProperty association) {
        super(Object.class);
        this.parent = parent;
        this.association = association;
    }

    // --- navigation → implicit join -----------------------------------------------------------

    @Override
    public <Y> Path<Y> get(String attributeName) {
        CriteriaJoin<?, ?> join = parent.implicitJoin(association);
        return join.get(attributeName);
    }

    // --- direct column use (owning to-one only) -----------------------------------------------

    @Override
    public EntityMetadata<?> ownerMetadata() {
        return parent.metadata();
    }

    @Override
    public PersistentProperty property() {
        if (!association.manyToOne()) {
            throw new CriteriaException("Association '" + association.propertyName()
                    + "' has no column on this table; navigate to an attribute (get(\"x\")) or join() it");
        }
        if (association.isCompositeToOne()) {
            // owning to-one을 그 자체로 predicate/selection에 쓰면 단일 FK 컬럼으로 해석된다. 복합키 타겟은 FK가
            // N개 컬럼이라 대표 컬럼 하나만 반환하면 조용한 오답이 되므로(예: cb.equal(e.get("parent"), x),
            // cb.isNull(e.get("parent"))) 명확히 거부한다. @Id 컴포넌트를 개별로 비교하라.
            throw new CriteriaException("Association '" + association.propertyName()
                    + "' references a composite-key target whose foreign key spans multiple columns; it cannot be "
                    + "used as a single column in a predicate/selection — compare its @Id components explicitly");
        }
        return association;
    }

    @Override
    public CriteriaFrom source() {
        return parent;
    }

    // --- Path surface -------------------------------------------------------------------------

    @Override
    public Bindable<Object> getModel() {
        throw unsupported("Path.getModel()");
    }

    @Override
    public Path<?> getParentPath() {
        return parent;
    }

    @Override
    public <Y> Path<Y> get(SingularAttribute<? super Object, Y> attribute) {
        throw unsupported("Path.get(SingularAttribute)");
    }

    @Override
    public <E, C extends java.util.Collection<E>> Expression<C> get(PluralAttribute<? super Object, C, E> attribute) {
        throw unsupported("Path.get(PluralAttribute)");
    }

    @Override
    public <K, V, M extends java.util.Map<K, V>> Expression<M> get(MapAttribute<? super Object, K, V> attribute) {
        throw unsupported("Path.get(MapAttribute)");
    }

    @Override
    public Expression<Class<?>> type() {
        throw unsupported("Path.type()");
    }
}
