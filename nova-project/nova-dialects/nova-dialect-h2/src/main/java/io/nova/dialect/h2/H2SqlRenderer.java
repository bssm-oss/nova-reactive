package io.nova.dialect.h2;

import io.nova.annotation.GenerationType;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.Dialect;

/**
 * H2 dialect용 SQL renderer다. CRUD 모양은 {@link AbstractSqlRenderer}와 동일하며,
 * IDENTITY/AUTO id 컬럼에 한해 INSERT 뒤에 {@code returning} 절을 덧붙여 생성된 키를 회수한다.
 */
final class H2SqlRenderer extends AbstractSqlRenderer {
    H2SqlRenderer(Dialect dialect) {
        super(dialect);
    }

    @Override
    protected String insertSuffix(EntityMetadata<?> metadata) {
        PersistentProperty idProperty = metadata.idProperty();
        if (idProperty == null || !idProperty.generated()) {
            return "";
        }
        // SEQUENCE/UUID는 INSERT 직전 애플리케이션이 값을 채워서 보내므로 returning 절이 필요 없다.
        GenerationType strategy = idProperty.generationType();
        if (strategy == GenerationType.SEQUENCE || strategy == GenerationType.UUID) {
            return "";
        }
        return " returning " + column(idProperty);
    }
}
