package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;

/**
 * 단일 컬럼으로 해석 가능한 Criteria 경로({@link CriteriaRoot} 또는 {@link CriteriaPath}) 공통 뷰.
 * 스칼라 SQL 렌더와 엔티티 QuerySpec 변환이 컬럼/프로퍼티 정보를 통일된 방식으로 얻는다.
 */
interface CriteriaColumnPath {

    /** 이 경로가 속한 루트 엔티티 메타데이터. */
    EntityMetadata<?> ownerMetadata();

    /**
     * 이 경로가 가리키는 영속 프로퍼티. 루트 자체({@code SELECT root}, {@code count(root)})는
     * 엔티티의 대표 id 프로퍼티로 해석된다.
     */
    PersistentProperty property();

    /**
     * 이 컬럼이 속한 FROM 소스(루트 또는 join). alias 기반 SQL 렌더에서 {@code alias.column}으로
     * 한정할 때 사용한다. join/subquery가 없는 단일 루트 스칼라 경로에서는 alias 없이 unqualified로
     * 렌더되므로 이 값을 무시한다.
     */
    CriteriaFrom source();
}
