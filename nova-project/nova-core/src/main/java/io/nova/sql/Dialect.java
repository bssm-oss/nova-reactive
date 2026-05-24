package io.nova.sql;

import io.nova.query.LockMode;

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

    /**
     * 값이 {@code null}인 binding에 대해 R2DBC {@code Statement.bindNull(index, type)}으로 전달할
     * Java 타입을 반환한다. 기본값은 {@link Object} 클래스다 — driver가 type-agnostic null encoding을
     * 지원하면 이 기본값으로 충분하다.
     * <p>
     * 일부 driver(예: r2dbc-h2)는 {@code Object.class} null binding을 거부하므로 해당 dialect는
     * driver가 받아들이는 fallback 타입(예: {@link String} 클래스)을 override 해서 반환해야 한다.
     * binding 위치마다 expected 컬럼 타입을 전달할 수 없는 경우의 안전한 fallback로 쓰인다.
     */
    default Class<?> nullBindClass() {
        return Object.class;
    }

    /**
     * 대소문자를 무시한 패턴 매칭(ILIKE) SQL 표현을 만든다. {@code negate}가 {@code true}면 부정형을 반환한다.
     * <p>
     * 기본 구현은 양 변을 {@code lower(...)}로 감싸 {@code lower(col) like lower(?)}를 생성한다 —
     * MySQL/H2처럼 컬럼 collation에 따라 case-insensitive 동작이 달라지는 dialect에서도 결정적인
     * case-insensitive 비교를 보장한다. PostgreSQL처럼 native {@code ILIKE}를 지원하는 dialect는
     * 이 메서드를 override 해서 {@code col ilike ?} 형태로 더 간결하게 렌더할 수 있다.
     * <p>
     * 부정형은 {@code lower(...) not like lower(...)}로 한 표현 안에 부정자를 두어 NULL 처리와 SQL
     * three-valued logic 동작이 표준 {@code NOT LIKE}와 정확히 일치하도록 만든다.
     */
    default String renderILike(String column, String marker, boolean negate) {
        String operator = negate ? "not like" : "like";
        return "lower(" + column + ") " + operator + " lower(" + marker + ")";
    }

    /**
     * {@code @Json}으로 표시된 컬럼에 사용할 SQL 타입을 반환한다. 기본값은 표준 {@code json}이며,
     * PostgreSQL처럼 binary JSON 타입을 지원하는 dialect는 {@code jsonb} 등으로 override 한다.
     * 컬럼 이름과 nullability는 schema generator가 일반 컬럼과 동일하게 결정하고, 이 메서드는 타입
     * 토큰만 제공한다.
     */
    default String jsonColumnType() {
        return "json";
    }

    /**
     * 주어진 {@link LockMode}에 해당하는 pessimistic lock 절을 반환한다. 절은 SELECT SQL의
     * 맨 뒤에 그대로 이어 붙일 수 있도록 선행 공백을 포함한다. {@link LockMode#NONE}은 빈 문자열이다.
     * <p>
     * 기본 구현은 표준 SQL {@code FOR UPDATE}와 {@code FOR SHARE} 절을 사용하며,
     * PostgreSQL, MySQL 8.0 이상, H2 모두 이 형태를 받아들인다. dialect 별 변형이 필요한 경우
     * 이 메서드를 override 한다.
     */
    default String lockClause(LockMode mode) {
        return switch (mode) {
            case NONE -> "";
            case FOR_UPDATE -> " for update";
            case FOR_SHARE -> " for share";
        };
    }
}
