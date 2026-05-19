package io.nova.dialect.h2;

import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;

/**
 * 물음표 bind marker와 double-quote 식별자, identity 컬럼을 지원하는 H2 dialect다.
 *
 * <p>H2는 ANSI SQL 호환 모드에서 식별자를 double-quote로 감싸고 prepared statement는 positional
 * {@code ?} marker를 사용한다. identity 컬럼은 {@code GENERATED ALWAYS AS IDENTITY}를 사용하며,
 * INSERT 시 생성된 키는 R2DBC SPI의 {@code Statement.returnGeneratedValues(...)} 경로로 회수한다 —
 * H2 2.1.214는 {@code INSERT ... RETURNING ...} 구문을 지원하지 않으므로 PostgreSQL과 달리
 * RETURNING 절을 사용할 수 없다.
 */
public final class H2Dialect implements Dialect {
    private final BindMarkerStrategy bindMarkers = index -> "?";
    private final SqlRenderer sqlRenderer = new H2SqlRenderer(this);
    private final SchemaGenerator schemaGenerator = new H2SchemaGenerator(this);

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public BindMarkerStrategy bindMarkers() {
        return bindMarkers;
    }

    @Override
    public SqlRenderer sqlRenderer() {
        return sqlRenderer;
    }

    @Override
    public SchemaGenerator schemaGenerator() {
        return schemaGenerator;
    }

    @Override
    public Class<?> nullBindClass() {
        // r2dbc-h2 1.0.0은 Statement.bindNull(index, Object.class)를 거부하므로
        // ("Cannot encode null parameter of type java.lang.Object") driver가 받아들이는
        // String.class를 fallback로 사용한다. driver는 String null binding을 모든 컬럼 타입에
        // 안전하게 디코딩할 수 있다.
        return String.class;
    }
}
