package io.nova.dialect.h2;

import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.Dialect;

/**
 * H2 dialect용 SQL renderer다. CRUD 모양은 {@link AbstractSqlRenderer}와 동일하며,
 * 생성된 IDENTITY 키는 INSERT SQL에 RETURNING 절을 덧붙이는 대신 R2DBC SPI의
 * {@code Statement.returnGeneratedValues(...)} 경로로 회수한다 — H2 2.1.214가
 * {@code INSERT ... RETURNING ...} 구문을 지원하지 않기 때문이다.
 */
final class H2SqlRenderer extends AbstractSqlRenderer {
    H2SqlRenderer(Dialect dialect) {
        super(dialect);
    }
}
