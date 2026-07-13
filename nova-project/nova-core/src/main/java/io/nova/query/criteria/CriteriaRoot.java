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
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.SetJoin;
import jakarta.persistence.metamodel.CollectionAttribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ListAttribute;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SetAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * {@code CriteriaQuery.from(Class)}가 만드는 쿼리 루트. 단일 엔티티 테이블을 나타내며, 속성 참조는
 * {@link #get(String)}으로 {@link CriteriaPath}를 만든다. 미존재 필드와 연관/컬렉션 필드는 v1에서
 * fail-fast한다(스칼라/집계·단순 술어만 지원). JPA {@code From}/{@code FetchParent}의 join/fetch 계열은
 * 전부 미지원으로 거부한다 — 다중 루트/조인/fetch는 Wave2 W3 범위다.
 *
 * @param <X> 루트 엔티티 타입
 */
final class CriteriaRoot<X> extends AbstractCriteriaExpression<X> implements Root<X>, CriteriaColumnPath {

    private final Class<X> entityType;
    private final EntityMetadata<X> metadata;

    CriteriaRoot(Class<X> entityType, EntityMetadata<X> metadata) {
        super(entityType);
        this.entityType = entityType;
        this.metadata = metadata;
    }

    Class<X> entityType() {
        return entityType;
    }

    @Override
    public EntityMetadata<X> ownerMetadata() {
        return metadata;
    }

    /** 루트 자체는 엔티티의 대표 id 프로퍼티로 해석된다({@code SELECT root}, {@code count(root)}). */
    @Override
    public PersistentProperty property() {
        return metadata.idProperty();
    }

    // --- Path.get(String): the one supported navigation ---------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <Y> Path<Y> get(String attributeName) {
        PersistentProperty property = metadata.findProperty(attributeName)
                .orElseThrow(() -> new CriteriaException("Unknown attribute '" + attributeName
                        + "' on entity " + entityType.getSimpleName()));
        if (property.isRelation()) {
            throw new CriteriaException("Attribute '" + attributeName
                    + "' is an association/collection; navigating relations in Criteria is not supported in v1");
        }
        return (Path<Y>) new CriteriaPath<>(this, property, (Class<Object>) property.javaType());
    }

    // --- unsupported Path surface -------------------------------------------------------------

    @Override
    public EntityType<X> getModel() {
        throw unsupported("Root.getModel()");
    }

    @Override
    public Path<?> getParentPath() {
        return null;
    }

    @Override
    public <Y> Path<Y> get(SingularAttribute<? super X, Y> attribute) {
        throw unsupported("Root.get(SingularAttribute)");
    }

    @Override
    public <E, C extends Collection<E>> Expression<C> get(PluralAttribute<? super X, C, E> attribute) {
        throw unsupported("Root.get(PluralAttribute)");
    }

    @Override
    public <K, V, M extends Map<K, V>> Expression<M> get(MapAttribute<? super X, K, V> attribute) {
        throw unsupported("Root.get(MapAttribute)");
    }

    @Override
    public Expression<Class<? extends X>> type() {
        throw unsupported("Root.type()");
    }

    // --- unsupported From surface -------------------------------------------------------------

    @Override
    public Set<Join<X, ?>> getJoins() {
        return Set.of();
    }

    @Override
    public boolean isCorrelated() {
        return false;
    }

    @Override
    public From<X, X> getCorrelationParent() {
        throw unsupported("Root.getCorrelationParent()");
    }

    @Override
    public <Y> Join<X, Y> join(Class<Y> entityClass) {
        throw joins();
    }

    @Override
    public <Y> Join<X, Y> join(Class<Y> entityClass, JoinType joinType) {
        throw joins();
    }

    @Override
    public <Y> Join<X, Y> join(EntityType<Y> entity) {
        throw joins();
    }

    @Override
    public <Y> Join<X, Y> join(EntityType<Y> entity, JoinType joinType) {
        throw joins();
    }

    @Override
    public <Y> Join<X, Y> join(SingularAttribute<? super X, Y> attribute) {
        throw joins();
    }

    @Override
    public <Y> Join<X, Y> join(SingularAttribute<? super X, Y> attribute, JoinType joinType) {
        throw joins();
    }

    @Override
    public <Y> CollectionJoin<X, Y> join(CollectionAttribute<? super X, Y> collection) {
        throw joins();
    }

    @Override
    public <Y> SetJoin<X, Y> join(SetAttribute<? super X, Y> set) {
        throw joins();
    }

    @Override
    public <Y> ListJoin<X, Y> join(ListAttribute<? super X, Y> list) {
        throw joins();
    }

    @Override
    public <K, V> MapJoin<X, K, V> join(MapAttribute<? super X, K, V> map) {
        throw joins();
    }

    @Override
    public <Y> CollectionJoin<X, Y> join(CollectionAttribute<? super X, Y> collection, JoinType joinType) {
        throw joins();
    }

    @Override
    public <Y> SetJoin<X, Y> join(SetAttribute<? super X, Y> set, JoinType joinType) {
        throw joins();
    }

    @Override
    public <Y> ListJoin<X, Y> join(ListAttribute<? super X, Y> list, JoinType joinType) {
        throw joins();
    }

    @Override
    public <K, V> MapJoin<X, K, V> join(MapAttribute<? super X, K, V> map, JoinType joinType) {
        throw joins();
    }

    @Override
    public <X2, Y> Join<X2, Y> join(String attributeName) {
        throw joins();
    }

    @Override
    public <X2, Y> CollectionJoin<X2, Y> joinCollection(String attributeName) {
        throw joins();
    }

    @Override
    public <X2, Y> SetJoin<X2, Y> joinSet(String attributeName) {
        throw joins();
    }

    @Override
    public <X2, Y> ListJoin<X2, Y> joinList(String attributeName) {
        throw joins();
    }

    @Override
    public <X2, K, V> MapJoin<X2, K, V> joinMap(String attributeName) {
        throw joins();
    }

    @Override
    public <X2, Y> Join<X2, Y> join(String attributeName, JoinType joinType) {
        throw joins();
    }

    @Override
    public <X2, Y> CollectionJoin<X2, Y> joinCollection(String attributeName, JoinType joinType) {
        throw joins();
    }

    @Override
    public <X2, Y> SetJoin<X2, Y> joinSet(String attributeName, JoinType joinType) {
        throw joins();
    }

    @Override
    public <X2, Y> ListJoin<X2, Y> joinList(String attributeName, JoinType joinType) {
        throw joins();
    }

    @Override
    public <X2, K, V> MapJoin<X2, K, V> joinMap(String attributeName, JoinType joinType) {
        throw joins();
    }

    // --- unsupported FetchParent surface ------------------------------------------------------

    @Override
    public Set<Fetch<X, ?>> getFetches() {
        return Set.of();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute) {
        throw fetches();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute, JoinType joinType) {
        throw fetches();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute) {
        throw fetches();
    }

    @Override
    public <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute, JoinType joinType) {
        throw fetches();
    }

    @Override
    public <X2, Y> Fetch<X2, Y> fetch(String attributeName) {
        throw fetches();
    }

    @Override
    public <X2, Y> Fetch<X2, Y> fetch(String attributeName, JoinType joinType) {
        throw fetches();
    }

    private static CriteriaException joins() {
        return new CriteriaException("Criteria joins are not supported in v1 (single-root queries only)");
    }

    private static CriteriaException fetches() {
        return new CriteriaException("Criteria fetch joins are not supported in v1");
    }
}
