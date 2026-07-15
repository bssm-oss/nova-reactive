package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code jakarta.persistence.criteria.CriteriaQuery} 구현. 단일 루트 SELECT를 조립한다 — 선택 목록,
 * WHERE 술어, GROUP BY, HAVING, ORDER BY, DISTINCT를 담아 {@link ReactiveCriteriaQuery}가 실행 시
 * 엔티티/스칼라 경로로 변환한다. {@code subquery(Class)}로 서브쿼리를 만들 수 있으며, join/서브쿼리를
 * 포함한 쿼리는 {@link #requiresAliasedSql()}로 판별해 alias 한정 SQL 경로로 실행된다. 다중 루트/파라미터
 * 표현식은 v1 미지원으로 fail-fast한다.
 *
 * <p><b>v1 제약</b>:
 * <ul>
 *   <li>HAVING은 grouping 컬럼에 대한 술어만 지원한다. 집계 함수 피연산자를 쓰는 대표 용례
 *       (예: {@code having(cb.gt(cb.count(root), 5L))})는 v1 미지원이며 조립 시점에 fail-fast한다
 *       — Criteria 술어 모델이 컬럼 경로 피연산자만 받기 때문이다.
 *   <li>{@code distinct(true)}는 스칼라/집계 경로에서만 반영된다. 엔티티 반환 경로는 {@code QuerySpec}에
 *       DISTINCT 표현 수단이 없어 실행 시 fail-fast한다(조용한 무시 금지).
 * </ul>
 *
 * @param <T> 쿼리 결과 타입
 */
final class CriteriaQueryImpl<T> implements CriteriaQuery<T> {

    private final Class<T> resultType;
    private final CriteriaMetamodel metamodel;
    private final CriteriaContext context;

    private CriteriaRoot<?> root;
    private final List<Selection<?>> selections = new ArrayList<>();
    private CriteriaPredicate restriction;
    private final List<Expression<?>> groupList = new ArrayList<>();
    private CriteriaPredicate having;
    private final List<CriteriaOrder> orders = new ArrayList<>();
    private boolean distinct;

    CriteriaQueryImpl(Class<T> resultType, CriteriaMetamodel metamodel) {
        this.resultType = Objects.requireNonNull(resultType, "resultType must not be null");
        this.metamodel = metamodel;
        this.context = new CriteriaContext(metamodel);
    }

    // --- structural accessors for the executor ------------------------------------------------

    CriteriaRoot<?> root() {
        if (root == null) {
            throw new CriteriaException("CriteriaQuery has no root; call from(entityClass) first");
        }
        return root;
    }

    List<Selection<?>> selections() {
        return selections;
    }

    CriteriaPredicate restriction() {
        return restriction;
    }

    List<Expression<?>> groupExpressions() {
        return groupList;
    }

    CriteriaPredicate havingPredicate() {
        return having;
    }

    List<CriteriaOrder> orders() {
        return orders;
    }

    // --- from -----------------------------------------------------------------------------------

    @Override
    public <X> Root<X> from(Class<X> entityClass) {
        if (root != null) {
            throw new CriteriaException("Multiple query roots are not supported in v1; "
                    + "the query already has root " + root.entityType().getSimpleName());
        }
        EntityMetadata<X> metadata = metamodel.resolve(entityClass);
        CriteriaRoot<X> created = new CriteriaRoot<>(entityClass, metadata, context);
        this.root = created;
        return created;
    }

    @Override
    public <X> Root<X> from(EntityType<X> entity) {
        throw new CriteriaException("from(EntityType) is not supported in v1; use from(Class)");
    }

    // --- select ---------------------------------------------------------------------------------

    @Override
    public CriteriaQuery<T> select(Selection<? extends T> selection) {
        selections.clear();
        selections.add(selection);
        return this;
    }

    @Override
    public CriteriaQuery<T> multiselect(Selection<?>... items) {
        selections.clear();
        selections.addAll(List.of(items));
        return this;
    }

    @Override
    public CriteriaQuery<T> multiselect(List<Selection<?>> items) {
        selections.clear();
        selections.addAll(items);
        return this;
    }

    // --- where ----------------------------------------------------------------------------------

    @Override
    public CriteriaQuery<T> where(Expression<Boolean> restriction) {
        this.restriction = asPredicate(restriction);
        return this;
    }

    @Override
    public CriteriaQuery<T> where(Predicate... restrictions) {
        this.restriction = conjoin(List.of(restrictions));
        return this;
    }

    @Override
    public CriteriaQuery<T> where(List<Predicate> restrictions) {
        this.restriction = conjoin(restrictions);
        return this;
    }

    // --- groupBy / having -----------------------------------------------------------------------

    @Override
    public CriteriaQuery<T> groupBy(Expression<?>... grouping) {
        groupList.clear();
        groupList.addAll(List.of(grouping));
        return this;
    }

    @Override
    public CriteriaQuery<T> groupBy(List<Expression<?>> grouping) {
        groupList.clear();
        groupList.addAll(grouping);
        return this;
    }

    @Override
    public CriteriaQuery<T> having(Expression<Boolean> restriction) {
        this.having = asPredicate(restriction);
        return this;
    }

    @Override
    public CriteriaQuery<T> having(Predicate... restrictions) {
        this.having = conjoin(List.of(restrictions));
        return this;
    }

    @Override
    public CriteriaQuery<T> having(List<Predicate> restrictions) {
        this.having = conjoin(restrictions);
        return this;
    }

    // --- orderBy / distinct ---------------------------------------------------------------------

    @Override
    public CriteriaQuery<T> orderBy(Order... o) {
        orders.clear();
        addOrders(List.of(o));
        return this;
    }

    @Override
    public CriteriaQuery<T> orderBy(List<Order> o) {
        orders.clear();
        addOrders(o);
        return this;
    }

    @Override
    public CriteriaQuery<T> distinct(boolean distinct) {
        this.distinct = distinct;
        return this;
    }

    // --- getters (AbstractQuery / CommonAbstractCriteria) ---------------------------------------

    @Override
    public List<Order> getOrderList() {
        return List.copyOf(orders);
    }

    @Override
    public Set<Root<?>> getRoots() {
        return root == null ? Set.of() : Set.of(root);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Selection<T> getSelection() {
        if (selections.isEmpty()) {
            return (Selection<T>) root();
        }
        if (selections.size() == 1) {
            return (Selection<T>) selections.get(0);
        }
        throw new CriteriaException("Compound selection has no single Selection<T> view");
    }

    @Override
    public List<Expression<?>> getGroupList() {
        return List.copyOf(groupList);
    }

    @Override
    public Predicate getGroupRestriction() {
        return having;
    }

    @Override
    public Predicate getRestriction() {
        return restriction;
    }

    @Override
    public boolean isDistinct() {
        return distinct;
    }

    @Override
    public Class<T> getResultType() {
        return resultType;
    }

    @Override
    public Set<ParameterExpression<?>> getParameters() {
        return Set.of();
    }

    @Override
    public <U> Subquery<U> subquery(Class<U> type) {
        return new CriteriaSubquery<>(type, context.child(), this);
    }

    @Override
    public <U> Subquery<U> subquery(EntityType<U> type) {
        throw new CriteriaException("subquery(EntityType) is not supported in v1; use subquery(Class)");
    }

    /**
     * 이 쿼리가 join(명시적/묵시적) 또는 서브쿼리 술어를 포함해 alias 한정 SQL 경로로 실행돼야 하는지.
     * {@code false}이면 기존 단일 루트 경로(엔티티 QuerySpec / unqualified 스칼라 SQL)로 실행된다.
     */
    boolean requiresAliasedSql() {
        if (root != null && !root.joins().isEmpty()) {
            return true;
        }
        return usesAliasedPredicate(restriction) || usesAliasedPredicate(having);
    }

    private static boolean usesAliasedPredicate(CriteriaPredicate predicate) {
        if (predicate == null) {
            return false;
        }
        switch (predicate.kind()) {
            case EXISTS, IN_SUBQUERY, COMPARISON_SUBQUERY, COMPARISON_COLUMN:
                return true;
            case NOT:
                return usesAliasedPredicate(predicate.inner());
            case AND, OR:
                for (CriteriaPredicate child : predicate.children()) {
                    if (usesAliasedPredicate(child)) {
                        return true;
                    }
                }
                return false;
            case COMPARISON, NULL, BETWEEN, IN:
                // 복합키 타겟 owning to-one을 그 자체로 비교(= <> < <= > >=)/IS NULL/BETWEEN/IN하면 FK가 N개
                // 컬럼이므로 단일 루트 QuerySpec/unqualified 스칼라 경로로는 표현할 수 없다. 컴포넌트 전개를
                // 담당하는 alias 경로로 라우팅한다(단일키 to-one/스칼라는 기존 경로 그대로). LIKE는 복합키에
                // 텍스트 표현이 없어 라우팅하지 않고 기존 경로에서 fail-fast한다.
                return referencesCompositeToOne(predicate.path());
            default:
                return false;
        }
    }

    private static boolean referencesCompositeToOne(CriteriaColumnPath path) {
        if (path == null) {
            return false;
        }
        io.nova.metadata.PersistentProperty property = path.property();
        return property.manyToOne() && property.isCompositeToOne();
    }

    // --- helpers --------------------------------------------------------------------------------

    private void addOrders(List<Order> incoming) {
        for (Order order : incoming) {
            if (!(order instanceof CriteriaOrder criteriaOrder)) {
                throw new CriteriaException("Order was not produced by this CriteriaBuilder");
            }
            orders.add(criteriaOrder);
        }
    }

    private static CriteriaPredicate asPredicate(Expression<Boolean> expression) {
        if (expression instanceof CriteriaPredicate predicate) {
            return predicate;
        }
        throw new CriteriaException("WHERE/HAVING requires a Predicate built by this CriteriaBuilder, got "
                + (expression == null ? "null" : expression.getClass().getSimpleName()));
    }

    private static CriteriaPredicate conjoin(List<Predicate> predicates) {
        List<CriteriaPredicate> parts = new ArrayList<>(predicates.size());
        for (Predicate predicate : predicates) {
            if (!(predicate instanceof CriteriaPredicate criteriaPredicate)) {
                throw new CriteriaException("Predicate was not produced by this CriteriaBuilder");
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
