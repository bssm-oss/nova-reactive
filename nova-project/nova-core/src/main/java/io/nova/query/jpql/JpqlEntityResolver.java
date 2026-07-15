package io.nova.query.jpql;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.InheritanceLayout;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * JPQL 엔티티명 → {@link EntityMetadata} 해석기. JPQL은 테이블명이 아니라 엔티티명(대개 단순 클래스명 또는
 * {@code @Entity(name=...)})을 참조하므로, 실행기 구성 시 등록한 엔티티 클래스들로부터 이름 색인을 만든다.
 * <p>
 * 대소문자를 무시하고 우선 {@code entityName()}으로, 없으면 단순 클래스명으로 매칭한다. 미등록 이름은
 * {@link JpqlException}으로 fail-fast한다.
 */
public final class JpqlEntityResolver {

    private final EntityMetadataFactory metadataFactory;
    private final Map<String, EntityMetadata<?>> byName;

    public JpqlEntityResolver(EntityMetadataFactory metadataFactory, Iterable<Class<?>> entityClasses) {
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        this.byName = new HashMap<>();
        for (Class<?> type : entityClasses) {
            EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(type);
            index(metadata.entityName(), metadata);
            index(type.getSimpleName(), metadata);
        }
    }

    private void index(String name, EntityMetadata<?> metadata) {
        if (name == null || name.isBlank()) {
            return;
        }
        byName.putIfAbsent(key(name), metadata);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** JPQL 엔티티명으로 메타데이터를 찾는다. 미등록이면 fail-fast. */
    public EntityMetadata<?> resolve(String entityName) {
        EntityMetadata<?> metadata = byName.get(key(entityName));
        if (metadata == null) {
            throw new JpqlException("Unknown JPQL entity '" + entityName
                    + "'. Register the entity class with the JpqlExecutor.");
        }
        return metadata;
    }

    /** 클래스로 직접 메타데이터를 얻는다(엔티티 조회 결과 타입 해석 등). */
    public <T> EntityMetadata<T> resolve(Class<T> type) {
        return metadataFactory.getEntityMetadata(type);
    }

    /**
     * JOINED/TABLE_PER_CLASS 상속 루트의 물리 테이블 배치를 해석한다(다형 SELECT 렌더링용). 코어 계약
     * 표면이 아닌 이 패키지 내부 헬퍼다.
     */
    InheritanceLayout inheritanceLayout(Class<?> rootClass) {
        return metadataFactory.inheritanceLayout(rootClass);
    }
}
