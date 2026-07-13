package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;

import java.util.Objects;

/**
 * Criteria {@code Root} 생성 시 엔티티 클래스를 {@link EntityMetadata}로 해석하는 얇은 어댑터.
 * 실행기가 쓰는 것과 동일한 {@link EntityMetadataFactory}에 위임하므로 테이블/컬럼 매핑이
 * 나머지 엔진과 정확히 일치한다. JPQL 서브시스템의 resolver와 파일을 공유하지 않는
 * 격리된 자체 해석기다(엔티티명 색인이 아니라 Class로 직접 해석한다).
 */
public final class CriteriaMetamodel {

    private final EntityMetadataFactory metadataFactory;

    public CriteriaMetamodel(EntityMetadataFactory metadataFactory) {
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
    }

    /** 엔티티 클래스의 메타데이터를 해석한다. {@code @Entity}가 아니면 팩토리가 fail-fast한다. */
    public <T> EntityMetadata<T> resolve(Class<T> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return metadataFactory.getEntityMetadata(entityType);
    }
}
