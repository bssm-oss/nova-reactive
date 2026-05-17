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
}
