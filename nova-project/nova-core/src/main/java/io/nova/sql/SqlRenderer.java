package io.nova.sql;

import io.nova.metadata.EntityMetadata;
import io.nova.query.QuerySpec;

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
}
