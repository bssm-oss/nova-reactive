package io.nova.sql;

import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.JoinTableDefinition;
import io.nova.query.AggregateSpec;
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

    /**
     * 지정한 entity property 이름 목록만 SELECT 절에 포함하는 projection 조회 SQL을 렌더한다.
     * {@code fields}는 entity property name(Java 필드명)이며 입력 순서를 그대로 유지한다.
     * 빈 컬렉션·미존재 field 처리는 구현체의 책임이다. FROM·WHERE·ORDER BY·페이지는 기존
     * {@link #select(EntityMetadata, QuerySpec)}와 동일한 규칙을 따르며, {@code @SoftDelete}
     * 메타데이터가 있으면 자동으로 alive 조건을 덧붙인다.
     * <p>
     * 기본 구현은 외부 SqlRenderer를 직접 구현한 사용자가 자동으로 깨지지 않도록 명시적 예외를
     * 던지며, {@link AbstractSqlRenderer}는 이 메서드를 override한다.
     */
    default SqlStatement selectProjection(EntityMetadata<?> metadata, List<String> fields, QuerySpec querySpec) {
        throw new UnsupportedOperationException(
                "SqlRenderer.selectProjection must be overridden by the implementation");
    }

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

    /**
     * {@code @SoftDelete} 컬럼을 가진 엔티티에 대해 predicate에 일치하는 행을 한 번에 논리 삭제하는
     * UPDATE 문을 렌더한다. {@code querySpec.predicate()}는 반드시 non-null이어야 하며, sort/pageable이
     * 함께 들어오면 거부한다. 이미 soft delete된 행(deletedAt is not null)은 대상에서 자동 제외해야 한다.
     * 기본 구현은 외부 구현체가 자동으로 깨지지 않도록 명시적 예외를 던지고,
     * {@link AbstractSqlRenderer}가 이 메서드를 override한다.
     */
    default SqlStatement softDeleteByQuery(EntityMetadata<?> metadata, QuerySpec querySpec, Object deletedAt) {
        throw new UnsupportedOperationException(
                "SqlRenderer.softDeleteByQuery must be overridden by the implementation");
    }

    /**
     * 엔티티 인스턴스 기반 delete를 렌더한다. {@code @Version}이 선언된 경우 WHERE 절에 현재 버전 비교를
     * 함께 포함시켜 optimistic locking 검증을 수행해야 한다. 기본 구현은 id-only delete로 폴백한다.
     */
    default SqlStatement deleteByEntity(EntityMetadata<?> metadata, Object entity) {
        return deleteById(metadata, metadata.idProperty().read(entity));
    }

    /**
     * {@code @SoftDelete}와 {@code @Version}이 동시에 선언된 엔티티에 대해 인스턴스 기반의 단건 논리
     * 삭제 UPDATE를 렌더한다.
     */
    default SqlStatement softDeleteByEntity(EntityMetadata<?> metadata, Object entity, Object deletedAt) {
        throw new UnsupportedOperationException(
                "SqlRenderer.softDeleteByEntity must be overridden by the implementation");
    }

    /**
     * Aggregations DSL의 {@link AggregateSpec}을 dialect별 SQL로 렌더한다.
     */
    default SqlStatement aggregate(EntityMetadata<?> metadata, AggregateSpec spec) {
        throw new UnsupportedOperationException(
                "SqlRenderer.aggregate must be overridden by the implementation");
    }

    /**
     * 주어진 metadata와 query 명세로 SELECT SQL을 한 번 렌더해 {@link CompiledQuery}로 반환한다.
     * 동일 SQL을 다른 binding으로 반복 실행할 때 renderer 호출 비용을 절감한다.
     */
    default CompiledQuery compileSelect(EntityMetadata<?> metadata, QuerySpec querySpec) {
        SqlStatement statement = select(metadata, querySpec);
        return new SimpleCompiledQuery(statement.sql(), statement.bindings().size());
    }

    /**
     * 주어진 metadata와 query 명세로 DELETE SQL을 한 번 렌더해 {@link CompiledQuery}로 반환한다.
     */
    default CompiledQuery compileDelete(EntityMetadata<?> metadata, QuerySpec querySpec) {
        SqlStatement statement = deleteByQuery(metadata, querySpec);
        return new SimpleCompiledQuery(statement.sql(), statement.bindings().size());
    }

    /**
     * 주어진 metadata와 field-value 쌍, query 명세로 UPDATE SQL을 한 번 렌더해 {@link CompiledQuery}로 반환한다.
     */
    default CompiledQuery compileUpdate(
            EntityMetadata<?> metadata,
            LinkedHashMap<String, Object> fieldValues,
            QuerySpec querySpec
    ) {
        SqlStatement statement = updateByQuery(metadata, fieldValues, querySpec);
        return new SimpleCompiledQuery(statement.sql(), statement.bindings().size());
    }

    /**
     * {@code @ManyToMany} link table에서 owner의 link row를 모두 삭제하는 구문(full-replace 동기화의 1단계).
     * 기본 구현은 미지원이므로 dialect base({@link AbstractSqlRenderer})가 override 한다.
     */
    default SqlStatement deleteJoinRows(JoinTableDefinition definition, Object ownerId) {
        throw new UnsupportedOperationException("deleteJoinRows is not supported by this SqlRenderer");
    }

    /**
     * link table에 (owner, target) link row 1건을 추가하는 구문. 다중 행 VALUES 비호환 dialect(Oracle)
     * 회피를 위해 target당 단건으로 발행한다.
     */
    default SqlStatement insertJoinRow(JoinTableDefinition definition, Object ownerId, Object targetId) {
        throw new UnsupportedOperationException("insertJoinRow is not supported by this SqlRenderer");
    }

    /**
     * 주어진 owner id들에 대한 link row(owner FK, target FK)를 조회하는 구문(2-hop hydration의 1단계).
     */
    default SqlStatement selectJoinRows(JoinTableDefinition definition, List<Object> ownerIds) {
        throw new UnsupportedOperationException("selectJoinRows is not supported by this SqlRenderer");
    }

    /**
     * {@code @ElementCollection} collection table에서 owner의 값 row를 모두 삭제하는 구문(full-replace 1단계).
     */
    default SqlStatement deleteCollectionRows(CollectionTableDefinition definition, Object ownerId) {
        throw new UnsupportedOperationException("deleteCollectionRows is not supported by this SqlRenderer");
    }

    /**
     * collection table에 (owner, value) row 1건을 추가하는 구문.
     */
    default SqlStatement insertCollectionRow(CollectionTableDefinition definition, Object ownerId, Object value) {
        throw new UnsupportedOperationException("insertCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * 주어진 owner id들의 (owner FK, value) row를 조회하는 구문(hydration 1단계).
     */
    default SqlStatement selectCollectionRows(CollectionTableDefinition definition, List<Object> ownerIds) {
        throw new UnsupportedOperationException("selectCollectionRows is not supported by this SqlRenderer");
    }
}
