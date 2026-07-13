package io.nova.graph;

import io.nova.fetch.FetchGroup;

import java.util.List;
import java.util.Objects;

/**
 * 실행 가능한 fetch plan이다 — JPA {@code jakarta.persistence.EntityGraph}의 리액티브 등가물이지만 blocking
 * {@code EntityManager} 계약을 구현하지 않고, Nova의 {@link FetchGroup} 배치 hydration 경로로 해석된 상태로
 * 값을 보관한다.
 *
 * <p>이 타입은 {@link EntityGraphs}(메타데이터를 아는 팩토리)만 만들 수 있다 — 그래야 fetch plan이 이미
 * {@link FetchGroup}으로 해석돼 있어서, {@link io.nova.core.ReactiveEntityOperations}의 default 오버로드가
 * 메타데이터 접근 없이 {@link #toFetchGroup()}만으로 위임할 수 있다.
 *
 * <p><b>v1 의미(always-eager):</b> 명명된 연관은 부모 목록당 IN-절 쿼리 한 번으로 <b>배치 로드가 보장</b>돼
 * N+1이 없다. 다만 Nova는 매핑 연관을 기본 eager 로드하므로 이 그래프는 미명명 연관을 <b>제외하지 않는다</b>
 * (제외할 lazy 수단이 없음) — 결과는 default eager 조회와 최소 동등 이상이다.
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

    /** 이 그래프가 명시적으로 명명한 최상위 속성 이름들(선언 순서, introspection 용). */
    public List<String> attributeNames() {
        return attributeNames;
    }

    /**
     * 이 그래프에 대응하는 {@link FetchGroup}을 반환한다. always-eager 의미상 매핑된 to-one/to-many 연관
     * 전체를 배치 fetch하는 group이며(명명 연관 ⊆ 이 group),
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
