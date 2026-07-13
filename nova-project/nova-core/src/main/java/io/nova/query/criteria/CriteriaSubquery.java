package io.nova.query.criteria;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CollectionJoin;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.MapJoin;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.SetJoin;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code CommonAbstractCriteria.subquery(Class)}가 만드는 서브쿼리. 부모 쿼리와 alias 시퀀스를 공유하는
 * 자식 {@link CriteriaContext}에서 자체 루트/조인/술어를 조립하며, {@code cb.exists(subquery)},
 * {@code path.in(subquery)}, 스칼라 비교({@code cb.gt(path, subquery)})의 피연산자로 쓰인다.
 * <p>
 * {@link #correlate(Root)}로 부모 루트를 참조하면 그 루트는 서브쿼리 FROM에 다시 나열되지 않고
 * (outer 스코프에 이미 있으므로) 서브쿼리 술어에서 outer alias로 상관 참조된다. groupBy/having/distinct와
 * 중첩 서브쿼리, join correlate는 v1에서 fail-fast한다.
 *
 * @param <U> 서브쿼리 결과 타입
 */
final class CriteriaSubquery<U> extends AbstractCriteriaExpression<U> implements Subquery<U> {

    private final Class<U> resultType;
    private final CriteriaContext context;
    private final CommonAbstractCriteria parent;

    private final List<CriteriaRoot<?>> fromRoots = new ArrayList<>();
    private final Set<CriteriaRoot<?>> correlatedRoots = new LinkedHashSet<>();
    private Expression<U> selection;
    private CriteriaPredicate restriction;

    CriteriaSubquery(Class<U> resultType, CriteriaContext context, CommonAbstractCriteria parent) {
        super(resultType);
        this.resultType = resultType;
        this.context = context;
        this.parent = parent;
    }

    // --- structural accessors for the SQL builder ---------------------------------------------

    CriteriaContext subContext() {
        return context;
    }

    List<CriteriaRoot<?>> fromRoots() {
        return fromRoots;
    }

    Set<CriteriaRoot<?>> correlatedRoots() {
        return correlatedRoots;
    }

    Expression<U> selectionExpression() {
        return selection;
    }

    CriteriaPredicate restrictionPredicate() {
        return restriction;
    }

    // --- from -----------------------------------------------------------------------------------

    @Override
    public <Y> Root<Y> from(Class<Y> entityClass) {
        CriteriaRoot<Y> root = new CriteriaRoot<>(entityClass, context.resolve(entityClass), context);
        fromRoots.add(root);
        return root;
    }

    @Override
    public <Y> Root<Y> from(EntityType<Y> entity) {
        throw new CriteriaException("Subquery.from(EntityType) is not supported in v1; use from(Class)");
    }

    // --- correlate ------------------------------------------------------------------------------

    @Override
    public <Y> Root<Y> correlate(Root<Y> parentRoot) {
        if (!(parentRoot instanceof CriteriaRoot<Y> root)) {
            throw new CriteriaException("Subquery.correlate requires a Root built by this CriteriaBuilder");
        }
        correlatedRoots.add(root);
        return root;
    }

    @Override
    public <X, Y> Join<X, Y> correlate(Join<X, Y> parentJoin) {
        throw new CriteriaException("Subquery.correlate(Join) is not supported in v1; correlate the root");
    }

    @Override
    public <X, Y> CollectionJoin<X, Y> correlate(CollectionJoin<X, Y> parentCollection) {
        throw new CriteriaException("Subquery.correlate(CollectionJoin) is not supported in v1");
    }

    @Override
    public <X, Y> SetJoin<X, Y> correlate(SetJoin<X, Y> parentSet) {
        throw new CriteriaException("Subquery.correlate(SetJoin) is not supported in v1");
    }

    @Override
    public <X, Y> ListJoin<X, Y> correlate(ListJoin<X, Y> parentList) {
        throw new CriteriaException("Subquery.correlate(ListJoin) is not supported in v1");
    }

    @Override
    public <X, K, V> MapJoin<X, K, V> correlate(MapJoin<X, K, V> parentMap) {
        throw new CriteriaException("Subquery.correlate(MapJoin) is not supported in v1");
    }

    @Override
    public Set<Join<?, ?>> getCorrelatedJoins() {
        return Set.of();
    }

    // --- select ---------------------------------------------------------------------------------

    @Override
    public Subquery<U> select(Expression<U> expression) {
        this.selection = expression;
        return this;
    }

    @Override
    public Expression<U> getSelection() {
        return selection;
    }

    // --- where ----------------------------------------------------------------------------------

    @Override
    public Subquery<U> where(Expression<Boolean> restriction) {
        this.restriction = asPredicate(restriction);
        return this;
    }

    @Override
    public Subquery<U> where(Predicate... restrictions) {
        this.restriction = conjoin(List.of(restrictions));
        return this;
    }

    @Override
    public Subquery<U> where(List<Predicate> restrictions) {
        this.restriction = conjoin(restrictions);
        return this;
    }

    @Override
    public Predicate getRestriction() {
        return restriction;
    }

    // --- unsupported query shaping -------------------------------------------------------------

    @Override
    public Subquery<U> groupBy(Expression<?>... grouping) {
        throw new CriteriaException("Subquery GROUP BY is not supported in v1");
    }

    @Override
    public Subquery<U> groupBy(List<Expression<?>> grouping) {
        throw new CriteriaException("Subquery GROUP BY is not supported in v1");
    }

    @Override
    public Subquery<U> having(Expression<Boolean> restriction) {
        throw new CriteriaException("Subquery HAVING is not supported in v1");
    }

    @Override
    public Subquery<U> having(Predicate... restrictions) {
        throw new CriteriaException("Subquery HAVING is not supported in v1");
    }

    @Override
    public Subquery<U> having(List<Predicate> restrictions) {
        throw new CriteriaException("Subquery HAVING is not supported in v1");
    }

    @Override
    public Subquery<U> distinct(boolean distinct) {
        if (distinct) {
            throw new CriteriaException("Subquery DISTINCT is not supported in v1");
        }
        return this;
    }

    @Override
    public boolean isDistinct() {
        return false;
    }

    @Override
    public List<Expression<?>> getGroupList() {
        return List.of();
    }

    @Override
    public Predicate getGroupRestriction() {
        return null;
    }

    // --- structural getters --------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public Set<Root<?>> getRoots() {
        Set<Root<?>> roots = new LinkedHashSet<>();
        roots.addAll((List<? extends Root<?>>) (List<?>) fromRoots);
        return roots;
    }

    @Override
    public AbstractQuery<?> getParent() {
        if (parent instanceof AbstractQuery<?> query) {
            return query;
        }
        throw new CriteriaException("Subquery.getParent() is only defined when nested in a query");
    }

    @Override
    public CommonAbstractCriteria getContainingQuery() {
        return parent;
    }

    @Override
    public Class<U> getResultType() {
        return resultType;
    }

    @Override
    public Set<jakarta.persistence.criteria.ParameterExpression<?>> getParameters() {
        return Set.of();
    }

    // --- nested subquery (unsupported) ---------------------------------------------------------

    @Override
    public <V> Subquery<V> subquery(Class<V> type) {
        throw new CriteriaException("Nested Criteria subqueries are not supported in v1");
    }

    @Override
    public <V> Subquery<V> subquery(EntityType<V> type) {
        throw new CriteriaException("Nested Criteria subqueries are not supported in v1");
    }

    // --- helpers --------------------------------------------------------------------------------

    private static CriteriaPredicate asPredicate(Expression<Boolean> expression) {
        if (expression instanceof CriteriaPredicate predicate) {
            return predicate;
        }
        throw new CriteriaException("Subquery WHERE requires a Predicate built by this CriteriaBuilder");
    }

    private static CriteriaPredicate conjoin(List<Predicate> predicates) {
        List<CriteriaPredicate> parts = new ArrayList<>(predicates.size());
        for (Predicate predicate : predicates) {
            if (!(predicate instanceof CriteriaPredicate criteriaPredicate)) {
                throw new CriteriaException("Subquery predicate was not produced by this CriteriaBuilder");
            }
            parts.add(criteriaPredicate);
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return CriteriaPredicate.junction(CriteriaPredicate.Kind.AND, parts);
    }
}
