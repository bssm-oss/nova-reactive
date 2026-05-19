package io.nova.dialect.h2;

import io.nova.metadata.PersistentProperty;
import io.nova.sql.AbstractSchemaGenerator;
import io.nova.sql.Dialect;

/**
 * H2 dialect용 schema generator다. 식별자 컬럼은 ANSI SQL {@code GENERATED ALWAYS AS IDENTITY}
 * 문법을 사용해 정의한다. 그 외 컬럼 타입 매핑은 {@link AbstractSchemaGenerator}의 기본 매핑을
 * 그대로 사용한다 — H2는 {@code BIGINT}, {@code INTEGER}, {@code BOOLEAN}, {@code VARCHAR},
 * {@code DOUBLE PRECISION}을 모두 그대로 수용한다.
 */
final class H2SchemaGenerator extends AbstractSchemaGenerator {
    H2SchemaGenerator(Dialect dialect) {
        super(dialect);
    }

    @Override
    protected String identityColumn(PersistentProperty property) {
        return dialect().quote(property.columnName()) + " " + sqlType(property)
                + " generated always as identity primary key";
    }
}
