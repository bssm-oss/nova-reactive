package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;

/**
 * 하나의 최상위 {@link CriteriaQueryImpl} 조립/렌더 동안 공유되는 상태. 엔티티 클래스를 메타데이터로
 * 해석하는 {@link CriteriaMetamodel}과, join/subquery에서 테이블을 구분하기 위한 alias 시퀀스를 담는다.
 * <p>
 * 서브쿼리는 부모 쿼리와 <b>같은 alias 시퀀스</b>를 공유하는 자식 컨텍스트({@link #child()})를 만들어,
 * correlate된 부모 {@code From}이 부모 스코프에서 이미 발급받은 alias를 그대로 재사용하게 한다. 이렇게
 * 하면 상관 서브쿼리가 outer 테이블 alias를 올바르게 참조한다.
 */
final class CriteriaContext {

    /**
     * alias 카운터. 부모/자식 컨텍스트가 같은 인스턴스를 공유하므로 outer 쿼리와 서브쿼리를 통틀어
     * 테이블 alias가 유일하다.
     */
    private static final class AliasSequence {
        private int next;

        String allocate() {
            return "t" + (next++);
        }
    }

    private final CriteriaMetamodel metamodel;
    private final AliasSequence aliasSequence;

    CriteriaContext(CriteriaMetamodel metamodel) {
        this.metamodel = metamodel;
        this.aliasSequence = new AliasSequence();
    }

    private CriteriaContext(CriteriaMetamodel metamodel, AliasSequence aliasSequence) {
        this.metamodel = metamodel;
        this.aliasSequence = aliasSequence;
    }

    /** 부모와 동일한 alias 시퀀스를 공유하는 서브쿼리용 자식 컨텍스트를 만든다. */
    CriteriaContext child() {
        return new CriteriaContext(metamodel, aliasSequence);
    }

    /** 다음 테이블 alias를 발급한다(예: {@code t0}, {@code t1}). */
    String nextAlias() {
        return aliasSequence.allocate();
    }

    /** 엔티티 클래스의 메타데이터를 해석한다. */
    <T> EntityMetadata<T> resolve(Class<T> entityType) {
        return metamodel.resolve(entityType);
    }
}
