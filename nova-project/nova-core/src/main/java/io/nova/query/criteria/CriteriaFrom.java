package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;

import java.util.List;

/**
 * SQL FROM 절에 참여하는 테이블 소스({@link CriteriaRoot} 또는 {@link CriteriaJoin})의 공통 뷰.
 * alias 기반 SQL 렌더러({@link AliasedCriteriaSqlBuilder})가 각 테이블에 부여된 alias와 그로부터 뻗어
 * 나간 join들을 트리로 순회할 수 있게 한다.
 */
interface CriteriaFrom {

    /** 이 소스가 나타내는 엔티티의 메타데이터. */
    EntityMetadata<?> metadata();

    /** 이 소스에 부여된 테이블 alias(최초 호출 시 컨텍스트에서 발급). */
    String alias();

    /** 이 소스에서 직접 뻗어 나간 join들(명시적 + 묵시적). declaration/발생 순서를 유지한다. */
    List<CriteriaJoin<?, ?>> joins();

    /** 이 소스를 만든 쿼리/서브쿼리 컨텍스트. */
    CriteriaContext context();
}
