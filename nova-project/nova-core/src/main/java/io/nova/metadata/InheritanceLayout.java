package io.nova.metadata;

import java.util.List;

/**
 * JOINED / TABLE_PER_CLASS 상속 계층의 물리 테이블 배치를 표현하는 immutable 메타데이터다.
 * {@link EntityMetadataFactory#inheritanceLayout(Class)}가 빌드하며, SQL 렌더러와 엔티티 오퍼레이션이
 * 다형 SELECT(JOIN/UNION)·멀티테이블 INSERT/DELETE를 구성할 때 공유한다.
 *
 * <p>레지스트리에 등록된 구체 서브타입만 {@link #subtypes()}에 포함되므로, 다형 조회 전에 전 서브타입
 * 메타데이터가 빌드돼 있어야 한다(Spring starter의 entity-packages eager preload가 보장).
 *
 * @param info             계층 공통 inheritance 메타데이터(root/strategy/discriminator)
 * @param rootMetadata     루트 엔티티 메타데이터(JOINED 루트 테이블의 출처). TPC도 다형 쿼리 타깃으로 사용한다.
 * @param rootTableColumns JOINED 루트 테이블에 속하는 컬럼들(루트/그 @MappedSuperclass 조상이 선언). TPC는 빈 리스트.
 * @param subtypes         구체(비-abstract) 서브타입들(루트가 구체면 루트 포함). 등록 순서를 보존한다.
 */
public record InheritanceLayout(
        InheritanceInfo info,
        EntityMetadata<?> rootMetadata,
        List<PersistentProperty> rootTableColumns,
        List<ConcreteSubtype> subtypes
) {
    public InheritanceLayout {
        rootTableColumns = List.copyOf(rootTableColumns);
        subtypes = List.copyOf(subtypes);
    }

    /**
     * 한 구체 서브타입의 물리 테이블 배치.
     *
     * @param metadata         구체 서브타입의 (자기 테이블) 메타데이터
     * @param ownTableColumns  이 서브타입의 자기 테이블 컬럼들 — JOINED는 (루트 PK를 FK로 공유 + 자기 클래스가
     *                         선언한 컬럼), TABLE_PER_CLASS는 모든 상속 컬럼(독립 테이블).
     * @param discriminatorValue 이 서브타입을 식별하는 discriminator 값
     */
    public record ConcreteSubtype(
            EntityMetadata<?> metadata,
            List<PersistentProperty> ownTableColumns,
            String discriminatorValue
    ) {
        public ConcreteSubtype {
            ownTableColumns = List.copyOf(ownTableColumns);
        }
    }
}
