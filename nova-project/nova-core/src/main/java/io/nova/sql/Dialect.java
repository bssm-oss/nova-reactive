package io.nova.sql;

/**
 * 데이터베이스별 SQL 렌더링과 스키마 생성 규칙을 캡슐화한다.
 */
public interface Dialect {
    String name();

    String quote(String identifier);

    BindMarkerStrategy bindMarkers();

    SqlRenderer sqlRenderer();

    SchemaGenerator schemaGenerator();

    /**
     * insert 후 생성된 키를 RETURNING 절(예: PostgreSQL의 {@code returning ...})로 회수하는지 여부를 반환한다.
     * 기본값은 {@code false}이며, {@code Statement.returnGeneratedValues}를 사용하는 dialect는 그대로 둔다.
     */
    default boolean usesReturningForGeneratedKeys() {
        return false;
    }

    /**
     * 주어진 시퀀스에서 다음 값을 가져오는 SELECT 구문을 반환한다. 시퀀스를 지원하지 않는 dialect는
     * 기본 구현이 {@link UnsupportedOperationException}을 던진다.
     *
     * <p>구문은 반드시 단일 컬럼을 {@link #SEQUENCE_VALUE_COLUMN} alias로 노출해야 한다.
     * 그래야 driver별 컬럼 라벨 차이(예: {@code nextval}, {@code NEXTVAL}) 없이
     * {@code RowAccessor}가 항상 같은 이름으로 값을 읽을 수 있다.
     */
    default String sequenceNextValueSql(String sequenceName) {
        throw new UnsupportedOperationException(name() + " dialect does not support sequences");
    }

    /**
     * SEQUENCE nextval 결과의 단일 컬럼 alias. dialect는 {@link #sequenceNextValueSql(String)}이
     * 생성하는 SQL의 컬럼을 이 이름으로 노출해야 하고, core operations는 이 이름으로 값을 읽는다.
     */
    String SEQUENCE_VALUE_COLUMN = "nova_seq_value";
}
