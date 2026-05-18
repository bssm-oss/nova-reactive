package io.nova.sql;

import io.nova.metadata.EntityMetadata;
import io.nova.query.QuerySpec;

import java.util.List;

/**
 * ORM 동작을 특정 dialect에 맞는 SQL과 바인딩 순서로 변환한다.
 */
public interface SqlRenderer {
    SqlStatement insert(EntityMetadata<?> metadata, Object entity);

    SqlStatement update(EntityMetadata<?> metadata, Object entity);

    SqlStatement deleteById(EntityMetadata<?> metadata, Object id);

    SqlStatement selectById(EntityMetadata<?> metadata, Object id);

    SqlStatement select(EntityMetadata<?> metadata, QuerySpec querySpec);

    SqlStatement count(EntityMetadata<?> metadata, QuerySpec querySpec);

    SqlStatement exists(EntityMetadata<?> metadata, QuerySpec querySpec);

    /**
     * 식별자 N개를 IN 절로 묶어 한 번에 삭제하는 SQL을 렌더한다. 기본 구현은 외부 SqlRenderer를
     * 직접 구현한 사용자가 자동으로 깨지지 않도록 명시적 예외를 던지며, {@link AbstractSqlRenderer}는
     * 이 메서드를 override한다.
     */
    default SqlStatement deleteByIds(EntityMetadata<?> metadata, List<Object> ids) {
        throw new UnsupportedOperationException(
                "SqlRenderer.deleteByIds must be overridden by the implementation");
    }

    /**
     * predicate에 일치하는 행을 한 번에 삭제하는 SQL을 렌더한다. {@code querySpec.predicate()}는
     * 반드시 non-null이어야 하며, DELETE 표준에 sort/limit 절이 없으므로 sort/pageable이 함께
     * 들어오면 거부한다. 기본 구현은 외부 SqlRenderer를 직접 구현한 사용자가 자동으로 깨지지
     * 않도록 명시적 예외를 던지며, {@link AbstractSqlRenderer}는 이 메서드를 override한다.
     */
    default SqlStatement deleteByQuery(EntityMetadata<?> metadata, QuerySpec querySpec) {
        throw new UnsupportedOperationException(
                "SqlRenderer.deleteByQuery must be overridden by the implementation");
    }
}
