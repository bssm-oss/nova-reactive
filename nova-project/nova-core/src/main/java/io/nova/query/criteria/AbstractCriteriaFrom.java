package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import jakarta.persistence.criteria.CollectionJoin;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.MapJoin;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.SetJoin;
import jakarta.persistence.metamodel.CollectionAttribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ListAttribute;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SetAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FROM 절 소스({@link CriteriaRoot}/{@link CriteriaJoin})의 공통 골격. 지원하는 String 기반 탐색
 * ({@code get}/{@code join}/{@code fetch})을 실제로 구현하고, metamodel 기반 오버로드 등 미지원 표면은
 * 전부 fail-fast로 거부한다. alias 발급, 자식 join 등록, 묵시적 join 캐시(같은 연관을 여러 번 탐색해도
 * JOIN은 한 번만)를 이 base가 담당한다.
 *
 * @param <Z> 이 소스를 만든 상위 타입(루트는 자기 자신)
 * @param <X> 이 소스가 나타내는 엔티티 타입
 */
abstract class AbstractCriteriaFrom<Z, X> extends AbstractCriteriaExpression<X>
        implements From<Z, X>, CriteriaColumnPath, CriteriaFrom {

    private final Class<X> entityType;
    private final EntityMetadata<X> entityMetadata;
    private final CriteriaContext context;
    private final List<CriteriaJoin<?, ?>> joins = new ArrayList<>();
    private final Map<String, CriteriaJoin<?, ?>> implicitJoins = new LinkedHashMap<>();
    private final Set<Fetch<X, ?>> fetches = new LinkedHashSet<>();
    private String alias;

    AbstractCriteriaFrom(Class<X> entityType, EntityMetadata<X> entityMetadata, CriteriaContext context) {
        super(entityType);
        this.entityType = entityType;
        this.entityMetadata = entityMetadata;
        this.context = context;
    }

    // --- CriteriaFrom / CriteriaColumnPath ----------------------------------------------------

    @Override
    public EntityMetadata<X> metadata() {
        return entityMetadata;
    }

    @Override
    public EntityMetadata<X> ownerMetadata() {
        return entityMetadata;
    }

    /** 소스 자체는 엔티티의 대표 id property로 해석된다({@code SELECT root}, {@code count(join)}). */
    @Override
    public PersistentProperty property() {
        return entityMetadata.idProperty();
    }

    @Override
    public CriteriaFrom source() {
        return this;
    }

    @Override
    public String alias() {
        if (alias == null) {
            alias = context.nextAlias();
        }
        return alias;
    }

    @Override
    public List<CriteriaJoin<?, ?>> joins() {
        return joins;
    }

    @Override
    public CriteriaContext context() {
        return context;
    }

    Class<X> entityClass() {
        return entityType;
    }

    // --- supported navigation: get(String) ----------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <Y> Path<Y> get(String attributeName) {
        PersistentProperty property = entityMetadata.findProperty(attributeName)
                .orElseThrow(() -> new CriteriaException("Unknown attribute '" + attributeName
                        + "' on entity " + entityType.getSimpleName()));
        if (property.isRelation()) {
            // 연관 property는 묵시적 join으로 확장 가능한 association 경로가 된다. 그 자체를 술어/select에
            // 쓰면(owning to-one) FK 컬럼으로 해석되고, 다시 get(...)하면 join으로 이어진다.
            return (Path<Y>) new CriteriaAssociationPath(this, property);
        }
        return (Path<Y>) new CriteriaPath<>(this, property, (Class<Object>) property.javaType());
    }

    // --- supported navigation: join(String[, JoinType]) ---------------------------------------

    @Override
    public <X2, Y> Join<X2, Y> join(String attributeName) {
        return join(attributeName, JoinType.INNER);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X2, Y> Join<X2, Y> join(String attributeName, JoinType joinType) {
        PersistentProperty property = entityMetadata.findProperty(attributeName)
                .orElseThrow(() -> new CriteriaException("Unknown attribute '" + attributeName
                        + "' on entity " + entityType.getSimpleName()));
        if (!property.isRelation()) {
            throw new CriteriaException("Attribute '" + attributeName + "' on "
                    + entityType.getSimpleName() + " is not an association and cannot be joined");
        }
        return (Join<X2, Y>) createJoin(property, joinType);
    }

    /**
     * 같은 연관에 대한 묵시적 join을 캐시해 재사용한다({@code root.get("d").get("a")}와
     * {@code root.get("d").get("b")}가 하나의 JOIN을 공유). 항상 INNER join이다.
     */
    CriteriaJoin<?, ?> implicitJoin(PersistentProperty association) {
        return implicitJoins.computeIfAbsent(association.propertyName(),
                key -> createJoin(association, JoinType.INNER));
    }

    private CriteriaJoin<?, ?> createJoin(PersistentProperty association, JoinType joinType) {
        if (joinType == JoinType.RIGHT) {
            throw new CriteriaException("RIGHT join is not supported in Criteria v1; use INNER or LEFT");
        }
        CriteriaJoinResolver.JoinTarget target =
                CriteriaJoinResolver.resolve(entityMetadata, association, context);
        CriteriaJoin<?, ?> join = new CriteriaJoin<>(this, association, joinType, target, context);
        joins.add(join);
        return join;
    }

    // --- supported: fetch(String[, JoinType]) → always-eager no-op ----------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <X2, Y> Fetch<X2, Y> fetch(String attributeName) {
        return (Fetch<X2, Y>) fetch(attributeName, JoinType.INNER);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X2, Y> Fetch<X2, Y> fetch(String attributeName, JoinType joinType) {
        PersistentProperty property = entityMetadata.findProperty(attributeName)
                .orElseThrow(() -> new CriteriaException("Unknown attribute '" + attributeName
                        + "' on entity " + entityType.getSimpleName()));
        if (!property.isRelation()) {
            throw new CriteriaException("Attribute '" + attributeName + "' on "
                    + entityType.getSimpleName() + " is not an association and cannot be fetch-joined");
        }
        // Nova는 blocking lazy proxy가 없어 매핑 연관을 항상 eager로 hydrate한다(AGENTS.md #4). 따라서
        // fetch join은 fail-fast하지 않고 수용하되, 별도 SQL fetch를 만들지 않는 no-op 마커로 처리한다 —
        // 실제 연관 로딩은 엔티티 하이드레이션 경로(findAll)의 always-eager 의미로 보장된다.
        CriteriaFetchImpl<X, Y> fetch = new CriteriaFetchImpl<>(this, property, joinType);
        fetches.add((Fetch<X, ?>) fetch);
        return (Fetch<X2, Y>) fetch;
    }

    @Override
    public Set<Fetch<X, ?>> getFetches() {
        return Set.copyOf(fetches);
    }

    // --- From surface: joins view -------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public Set<Join<X, ?>> getJoins() {
        Set<Join<X, ?>> view = new LinkedHashSet<>();
        for (CriteriaJoin<?, ?> join : joins) {
            view.add((Join<X, ?>) join);
        }
        return view;
    }

    @Override
    public boolean isCorrelated() {
        return false;
    }

    @Override
    public From<Z, X> getCorrelationParent() {
        throw unsupported("From.getCorrelationParent()");
    }

    // --- Path surface -------------------------------------------------------------------------

    @Override
    public <Y> Path<Y> get(SingularAttribute<? super X, Y> attribute) {
        throw unsupported("From.get(SingularAttribute)");
    }

    @Override
    public <E, C extends java.util.Collection<E>> Expression<C> get(PluralAttribute<? super X, C, E> attribute) {
        throw unsupported("From.get(PluralAttribute)");
    }

    @Override
    public <K, V, M extends Map<K, V>> Expression<M> get(MapAttribute<? super X, K, V> attribute) {
        throw unsupported("From.get(MapAttribute)");
    }

    @Override
    public Expression<Class<? extends X>> type() {
        throw unsupported("From.type()");
    }

    @Override
    public jakarta.persistence.metamodel.Bindable<X> getModel() {
        throw unsupported("From.getModel() (metamodel)");
    }

    // --- unsupported metamodel-based join overloads -------------------------------------------

    @Override
    public <Y> Join<X, Y> join(SingularAttribute<? super X, Y> attribute) {
        throw metamodelJoins();
    }

    @Override
    public <Y> Join<X, Y> join(SingularAttribute<? super X, Y> attribute, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <Y> CollectionJoin<X, Y> join(CollectionAttribute<? super X, Y> collection) {
        throw metamodelJoins();
    }

    @Override
    public <Y> SetJoin<X, Y> join(SetAttribute<? super X, Y> set) {
        throw metamodelJoins();
    }

    @Override
    public <Y> ListJoin<X, Y> join(ListAttribute<? super X, Y> list) {
        throw metamodelJoins();
    }

    @Override
    public <K, V> MapJoin<X, K, V> join(MapAttribute<? super X, K, V> map) {
        throw metamodelJoins();
    }

    @Override
    public <Y> CollectionJoin<X, Y> join(CollectionAttribute<? super X, Y> collection, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <Y> SetJoin<X, Y> join(SetAttribute<? super X, Y> set, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <Y> ListJoin<X, Y> join(ListAttribute<? super X, Y> list, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <K, V> MapJoin<X, K, V> join(MapAttribute<? super X, K, V> map, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <X2, Y> CollectionJoin<X2, Y> joinCollection(String attributeName) {
        throw metamodelJoins();
    }

    @Override
    public <X2, Y> SetJoin<X2, Y> joinSet(String attributeName) {
        throw metamodelJoins();
    }

    @Override
    public <X2, Y> ListJoin<X2, Y> joinList(String attributeName) {
        throw metamodelJoins();
    }

    @Override
    public <X2, K, V> MapJoin<X2, K, V> joinMap(String attributeName) {
        throw metamodelJoins();
    }

    @Override
    public <X2, Y> CollectionJoin<X2, Y> joinCollection(String attributeName, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <X2, Y> SetJoin<X2, Y> joinSet(String attributeName, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <X2, Y> ListJoin<X2, Y> joinList(String attributeName, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <X2, K, V> MapJoin<X2, K, V> joinMap(String attributeName, JoinType joinType) {
        throw metamodelJoins();
    }

    // --- unsupported metamodel-based fetch overloads ------------------------------------------

    @Override
    public <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute) {
        throw metamodelFetches();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute, JoinType joinType) {
        throw metamodelFetches();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute) {
        throw metamodelFetches();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute, JoinType joinType) {
        throw metamodelFetches();
    }

    // --- also unsupported: entity-type join ----------------------------------------------------

    @Override
    public <Y> Join<X, Y> join(Class<Y> entityClass) {
        throw metamodelJoins();
    }

    @Override
    public <Y> Join<X, Y> join(Class<Y> entityClass, JoinType joinType) {
        throw metamodelJoins();
    }

    @Override
    public <Y> Join<X, Y> join(EntityType<Y> entity) {
        throw metamodelJoins();
    }

    @Override
    public <Y> Join<X, Y> join(EntityType<Y> entity, JoinType joinType) {
        throw metamodelJoins();
    }

    private static CriteriaException metamodelJoins() {
        return new CriteriaException("Metamodel/typed Criteria joins are not supported in v1; "
                + "use join(String) / join(String, JoinType)");
    }

    private static CriteriaException metamodelFetches() {
        return new CriteriaException("Metamodel/typed Criteria fetch joins are not supported in v1; "
                + "use fetch(String) / fetch(String, JoinType)");
    }
}
