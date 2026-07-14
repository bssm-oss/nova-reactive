package io.nova.query.criteria;

import io.nova.metadata.PersistentProperty;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.metamodel.Attribute;

/**
 * {@code from.join("assoc"[, JoinType])}가 만드는 실제 SQL JOIN 노드. 대상 엔티티 테이블을 자기 alias로
 * 나타내며, 다시 {@link #get(String)}으로 대상 컬럼을 참조하거나 {@link #join(String)}으로 연쇄 join을
 * 만들 수 있다. join 조건 컬럼 쌍({@code on parentAlias.parentColumn = alias.childColumn}, 복합키 타겟은
 * 여러 짝을 {@code and} 결합)은 {@link CriteriaJoinResolver}가 연관 property에서 유도한다. 공통 탐색/alias
 * 동작은 {@link AbstractCriteriaFrom}가 담당한다.
 *
 * @param <Z> 이 join을 만든 부모 소스의 엔티티 타입
 * @param <X> join 대상 엔티티 타입
 */
final class CriteriaJoin<Z, X> extends AbstractCriteriaFrom<Z, X> implements Join<Z, X> {

    private final CriteriaFrom parent;
    private final PersistentProperty association;
    private final JoinType joinType;
    private final CriteriaJoinResolver.JoinTarget target;

    @SuppressWarnings("unchecked")
    CriteriaJoin(
            CriteriaFrom parent,
            PersistentProperty association,
            JoinType joinType,
            CriteriaJoinResolver.JoinTarget target,
            CriteriaContext context) {
        super((Class<X>) target.targetMetadata().entityType(),
                (io.nova.metadata.EntityMetadata<X>) target.targetMetadata(), context);
        this.parent = parent;
        this.association = association;
        this.joinType = joinType;
        this.target = target;
    }

    // --- structural accessors for the SQL builder ---------------------------------------------

    CriteriaFrom parentFrom() {
        return parent;
    }

    /**
     * join 조건 컬럼 쌍들. 단일키 연관은 한 짝, 복합키 타겟 owning to-one은 참조 {@code @Id} 컴포넌트
     * 수만큼의 짝을 담으며, SQL 빌더가 각 짝을 {@code parentAlias.parentColumn = alias.childColumn} 형태로
     * {@code and} 결합한다.
     */
    java.util.List<CriteriaJoinResolver.JoinColumnPair> columnPairs() {
        return target.columnPairs();
    }

    // --- Join surface -------------------------------------------------------------------------

    @Override
    public JoinType getJoinType() {
        return joinType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public From<?, Z> getParent() {
        if (parent instanceof From<?, ?> from) {
            return (From<?, Z>) from;
        }
        throw unsupported("Join.getParent()");
    }

    @Override
    public Path<?> getParentPath() {
        if (parent instanceof Path<?> path) {
            return path;
        }
        throw unsupported("Join.getParentPath()");
    }

    @Override
    public Attribute<? super Z, ?> getAttribute() {
        throw unsupported("Join.getAttribute() (metamodel attribute)");
    }

    @Override
    public Join<Z, X> on(Expression<Boolean> restriction) {
        throw unsupported("Join.on(Expression) (extra join conditions)");
    }

    @Override
    public Join<Z, X> on(Predicate... restrictions) {
        throw unsupported("Join.on(Predicate...) (extra join conditions)");
    }

    @Override
    public Predicate getOn() {
        return null;
    }
}
