package io.nova.graph;

import io.nova.fetch.FetchGroup;

import java.util.List;
import java.util.Objects;

/**
 * 실행 가능한 fetch plan이다 — JPA {@code jakarta.persistence.EntityGraph}의 리액티브 등가물이지만 blocking
 * {@code EntityManager} 계약을 구현하지 않고, Nova의 {@link FetchGroup} 배치 hydration 경로로 해석된 상태로
 * 값을 보관한다.
 *
 * <p>이 타입은 {@link EntityGraphs}(메타데이터를 아는 팩토리)만 만들 수 있다 — 그래야 named 속성이 이미
 * {@link FetchGroup} spec으로 해석돼 있어서, {@link io.nova.core.ReactiveEntityOperations}의 default
 * 오버로드가 메타데이터 접근 없이 {@link #toFetchGroup()}만으로 위임할 수 있다.
 *
 * <p>지정된 연관은 부모 목록당 IN-절 쿼리 한 번으로 배치 로드되므로 N+1이 발생하지 않는다.
 *
 * @param <T> 루트 엔티티 타입
 */
public final class EntityGraph<T> {

    private final Class<T> rootType;
    private final String name;
    private final List<String> attributeNames;
    private final FetchGroup<T> fetchGroup;

    EntityGraph(Class<T> rootType, String name, List<String> attributeNames, FetchGroup<T> fetchGroup) {
        this.rootType = Objects.requireNonNull(rootType, "rootType must not be null");
        this.name = name;
        this.attributeNames = List.copyOf(attributeNames);
        this.fetchGroup = Objects.requireNonNull(fetchGroup, "fetchGroup must not be null");
    }

    public Class<T> rootType() {
        return rootType;
    }

    /** {@code @NamedEntityGraph}에서 유래하면 그 이름, ad-hoc 그래프면 {@code null}. */
    public String name() {
        return name;
    }

    /** 이 그래프가 fetch를 요청하는 최상위 속성 이름들(선언 순서). */
    public List<String> attributeNames() {
        return attributeNames;
    }

    /**
     * 이 그래프에 대응하는 {@link FetchGroup}을 반환한다. 지정된 연관 속성만 spec으로 담겨 있으며,
     * {@link io.nova.core.ReactiveEntityOperations#findAll(Class, FetchGroup)} 등에 그대로 넘길 수 있다.
     */
    public FetchGroup<T> toFetchGroup() {
        return fetchGroup;
    }

    @Override
    public String toString() {
        return "EntityGraph{rootType=" + rootType.getSimpleName()
                + ", name=" + name + ", attributes=" + attributeNames + '}';
    }
}
