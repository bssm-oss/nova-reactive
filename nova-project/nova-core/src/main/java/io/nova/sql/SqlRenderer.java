package io.nova.sql;

import io.nova.metadata.EntityMetadata;
import io.nova.query.QuerySpec;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * ORM 동작을 특정 dialect에 맞는 SQL과 바인딩 순서로 변환한다.
 */
public interface SqlRenderer {
    SqlStatement insert(EntityMetadata<?> metadata, Object entity);

    SqlStatement update(EntityMetadata<?> metadata, Object entity);

    /**
     * 명시된 property 컬럼만 SET 절에 포함하는 partial UPDATE SQL을 렌더한다. fields는 entity의
     * property name(Java 필드명)이며, id property는 포함될 수 없다. 빈 컬렉션·미존재 field·
     * id field·중복 처리는 구현체의 책임이다. 기본 구현은 외부 SqlRenderer를 직접 구현한 사용자가
     * 자동으로 깨지지 않도록 명시적 예외를 던지며, {@link AbstractSqlRenderer}는 이 메서드를 override한다.
     */
    default SqlStatement update(EntityMetadata<?> metadata, Object entity, Iterable<String> fields) {
        throw new UnsupportedOperationException(
                "SqlRenderer.update(metadata, entity, fields) must be overridden by the implementation");
    }

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

    /**
     * Updater builder가 만든 {@code (field -> value)} 쌍과 WHERE 절로 부분 UPDATE SQL을 렌더한다.
     * 입력 순서를 보존하기 위해 {@link LinkedHashMap}을 받는다. {@code querySpec}의 predicate만
     * 의미가 있으며 sort/pageable은 사용되지 않는다.
     * <p>
     * 기본 구현은 외부 SqlRenderer 직접 구현자가 자동으로 깨지지 않도록 명시적 예외를 던지며,
     * {@link AbstractSqlRenderer}는 이 메서드를 override한다.
     */
    default SqlStatement updateByQuery(
            EntityMetadata<?> metadata,
            LinkedHashMap<String, Object> fieldValues,
            QuerySpec querySpec
    ) {
        throw new UnsupportedOperationException(
                "SqlRenderer.updateByQuery must be overridden by the implementation");
    }

    /**
     * {@code @SoftDelete} 컬럼을 가진 엔티티의 단건 논리 삭제 UPDATE 문을 렌더한다.
     * 기본 구현은 외부 구현체가 자동으로 깨지지 않도록 명시적 예외를 던지고,
     * {@link AbstractSqlRenderer}가 이 메서드를 override한다.
     */
    default SqlStatement softDeleteById(EntityMetadata<?> metadata, Object id, Object deletedAt) {
        throw new UnsupportedOperationException(
                "SqlRenderer.softDeleteById must be overridden by the implementation");
    }

    /**
     * {@code @SoftDelete} 컬럼을 가진 엔티티의 N건 일괄 논리 삭제 UPDATE 문을 렌더한다.
     * 기본 구현은 외부 구현체가 자동으로 깨지지 않도록 명시적 예외를 던지고,
     * {@link AbstractSqlRenderer}가 이 메서드를 override한다.
     */
    default SqlStatement softDeleteByIds(EntityMetadata<?> metadata, List<Object> ids, Object deletedAt) {
        throw new UnsupportedOperationException(
                "SqlRenderer.softDeleteByIds must be overridden by the implementation");
    }
}
