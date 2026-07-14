package io.nova.query.criteria;

import io.nova.metadata.EntityMetadata;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;

/**
 * {@code cb.treat(root, Subtype.class)}가 만드는 downcast된 루트. SINGLE_TABLE 상속에서 서브타입은 루트와
 * 같은 물리 테이블을 공유하므로, 이 루트의 {@link #get(String)}은 서브타입 속성(상속 + 서브타입 고유)을
 * 그 공유 테이블 컬럼으로 해석한다. downcast는 discriminator 제한을 함의하며, 이 소스에서 뻗은 컬럼이
 * SELECT/WHERE에 등장하면 {@link CriteriaSqlBuilder}가 {@code discriminator = subtypeValue}를 자동으로
 * 붙인다.
 *
 * @param <T> downcast 대상 서브타입
 */
final class CriteriaTreatedRoot<T> extends AbstractCriteriaFrom<T, T> implements Root<T> {

    CriteriaTreatedRoot(Class<T> subtype, EntityMetadata<T> subtypeMetadata, CriteriaContext context) {
        super(subtype, subtypeMetadata, context);
    }

    // --- Root surface -------------------------------------------------------------------------

    @Override
    public EntityType<T> getModel() {
        throw unsupported("Root.getModel()");
    }

    @Override
    public Path<?> getParentPath() {
        return null;
    }

    @Override
    public From<T, T> getCorrelationParent() {
        throw unsupported("Root.getCorrelationParent()");
    }
}
