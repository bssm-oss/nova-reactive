package io.nova.sql;

import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.InheritanceLayout;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.SecondaryTableInfo;
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

    /**
     * {@link #update(EntityMetadata, Object)}와 동일하되, {@code @Version} 컬럼의 SET 값으로 렌더러가 내부에서
     * 계산한 다음 버전 대신 호출부가 미리 계산한 {@code precomputedVersion}을 바인딩한다(WHERE의 버전 비교는
     * 그대로 entity의 현재=old 값). 호출부가 SQL 바인딩과 in-memory writeback에 동일 값을 쓰도록 해(single-read)
     * 시간 {@code @Version}의 in-memory/DB 불일치(false-positive 낙관락 실패)를 제거한다. 기본 구현은 precomputed
     * 값을 무시하고 위임하며(비-Abstract 구현체 하위호환), {@link AbstractSqlRenderer}가 override해 실제로 바인딩한다.
     */
    default SqlStatement update(EntityMetadata<?> metadata, Object entity, Object precomputedVersion) {
        return update(metadata, entity);
    }

    /**
     * {@link #update(EntityMetadata, Object, Iterable)}의 single-read 변형. 위 오버로드와 동일한 계약으로 partial
     * UPDATE의 {@code @Version} SET 값을 호출부의 precomputed 값으로 바인딩한다.
     */
    default SqlStatement update(
            EntityMetadata<?> metadata, Object entity, Iterable<String> fields, Object precomputedVersion) {
        return update(metadata, entity, fields);
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
     * {@link #softDeleteByEntity(EntityMetadata, Object, Object)}의 single-read 변형. soft-delete UPDATE의
     * {@code @Version} SET 값을 호출부가 미리 계산한 {@code precomputedVersion}으로 바인딩한다. 기본 구현은
     * precomputed 값을 무시하고 위임하며 {@link AbstractSqlRenderer}가 override한다.
     */
    default SqlStatement softDeleteByEntity(
            EntityMetadata<?> metadata, Object entity, Object deletedAt, Object precomputedVersion) {
        return softDeleteByEntity(metadata, entity, deletedAt);
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
     * link table에서 (owner, target) link row 1건만 삭제하는 구문 — 세션 flush의 최소 diff 동기화가 baseline
     * 대비 제거된 대상만 지울 때 쓴다(full-replace {@link #deleteJoinRows}와 대비). 기본 구현은 미지원이므로
     * dialect base({@link AbstractSqlRenderer})가 override 한다.
     */
    default SqlStatement deleteJoinRow(JoinTableDefinition definition, Object ownerId, Object targetId) {
        throw new UnsupportedOperationException("deleteJoinRow is not supported by this SqlRenderer");
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
     * collection table에서 (owner, value) row 1건만 삭제하는 구문(기본 타입 원소) — 세션 flush의 최소 diff
     * 동기화가 baseline 대비 제거된 값만 지울 때 쓴다(full-replace {@link #deleteCollectionRows}와 대비).
     * Set 의미(중복 없음)에서만 안전하므로 ops 레이어가 중복 원소가 없을 때만 호출한다.
     */
    default SqlStatement deleteCollectionRow(CollectionTableDefinition definition, Object ownerId, Object value) {
        throw new UnsupportedOperationException("deleteCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * collection table에 (owner, value) row 1건을 추가하는 구문(기본 타입 원소).
     */
    default SqlStatement insertCollectionRow(CollectionTableDefinition definition, Object ownerId, Object value) {
        throw new UnsupportedOperationException("insertCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * {@code @OrderColumn} 정렬 collection table에 (owner, value, order) row 1건을 추가하는 구문(기본 타입 원소).
     * {@code orderIndex}는 {@code List} 안의 0-기반 위치다.
     */
    default SqlStatement insertCollectionRow(
            CollectionTableDefinition definition, Object ownerId, Object value, int orderIndex) {
        throw new UnsupportedOperationException("ordered insertCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * {@code @Embeddable} 원소 collection table에 (owner, col1, col2, ...) row 1건을 추가하는 구문.
     * {@code columnValues}는 {@link CollectionTableDefinition#elementColumns()} 순서와 정렬되어야 한다.
     */
    default SqlStatement insertEmbeddableCollectionRow(
            CollectionTableDefinition definition, Object ownerId, java.util.List<Object> columnValues) {
        throw new UnsupportedOperationException("insertEmbeddableCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * {@code @OrderColumn} 정렬 {@code @Embeddable} 원소 collection table에 (owner, col1, col2, ..., order) row
     * 1건을 추가하는 구문. {@code columnValues}는 {@link CollectionTableDefinition#elementColumns()} 순서와
     * 정렬되어야 하며 {@code orderIndex}는 {@code List} 안의 0-기반 위치다.
     */
    default SqlStatement insertEmbeddableCollectionRow(
            CollectionTableDefinition definition, Object ownerId, java.util.List<Object> columnValues, int orderIndex) {
        throw new UnsupportedOperationException(
                "ordered insertEmbeddableCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * 주어진 owner id들의 (owner FK, value) row를 조회하는 구문(hydration 1단계). collection table이
     * {@code Map<K,V>}이면 key 컬럼도 select 목록에 포함한다.
     */
    default SqlStatement selectCollectionRows(CollectionTableDefinition definition, List<Object> ownerIds) {
        throw new UnsupportedOperationException("selectCollectionRows is not supported by this SqlRenderer");
    }

    /**
     * {@code @ElementCollection Map<K,V>} collection table에 (owner, key, value) row 1건을 추가하는 구문
     * (기본 타입 value).
     */
    default SqlStatement insertMapCollectionRow(
            CollectionTableDefinition definition, Object ownerId, Object key, Object value) {
        throw new UnsupportedOperationException("insertMapCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * {@code @ElementCollection Map<K,@Embeddable>} collection table에 (owner, key, col1, col2, ...) row 1건을
     * 추가하는 구문. {@code columnValues}는 {@link CollectionTableDefinition#elementColumns()} 순서와 정렬되어야 한다.
     */
    default SqlStatement insertEmbeddableMapCollectionRow(
            CollectionTableDefinition definition, Object ownerId, Object key, java.util.List<Object> columnValues) {
        throw new UnsupportedOperationException(
                "insertEmbeddableMapCollectionRow is not supported by this SqlRenderer");
    }

    /**
     * {@code @OneToMany(mappedBy)} + {@code @OrderColumn} 정렬에서 child 행의 순서 컬럼을 {@code orderIndex}로
     * 갱신하는 {@code UPDATE child SET orderCol = ? WHERE childPk = ?} 구문. re-save 시 현재 List 위치로 재인덱싱한다.
     */
    default SqlStatement updateOneToManyOrder(
            EntityMetadata<?> childMetadata, String orderColumnName, Object childId, int orderIndex) {
        throw new UnsupportedOperationException("updateOneToManyOrder is not supported by this SqlRenderer");
    }

    /**
     * {@code @OneToMany} + {@code @OrderColumn} fetch 정렬용으로 child의 (PK, order) 행을 조회하는
     * {@code SELECT childPk, orderCol FROM child WHERE fkColumn IN (...)} 구문. 결과로 child를 순서 컬럼 기준
     * 정렬한다.
     */
    default SqlStatement selectOneToManyOrder(
            EntityMetadata<?> childMetadata, String foreignKeyColumn, String orderColumnName, List<Object> parentIds) {
        throw new UnsupportedOperationException("selectOneToManyOrder is not supported by this SqlRenderer");
    }

    // ---------------------------------------------------------------------------------------------
    // @Inheritance(JOINED) — 멀티테이블 INSERT/SELECT/UPDATE/DELETE 렌더링.
    // 기존 단일-테이블 메서드 시그니처는 그대로 두고, 멀티테이블은 아래 additive default 메서드로 표현한다.
    // 실제 reactive 순서(root insert → 생성 id 확보 → subtype insert; delete는 subtype → root)는
    // 엔티티 오퍼레이션 계층이 보장한다. 렌더러는 각 단계의 단일 SQL 문장만 만든다.
    // ---------------------------------------------------------------------------------------------

    /**
     * JOINED 루트 테이블 INSERT를 렌더한다 — 루트 테이블 컬럼들 + discriminator 상수. id가 IDENTITY면
     * id 컬럼은 {@code rootColumns}에서 제외되고 dialect가 생성 키를 회수한다. 호출자는 {@code rootColumns}로
     * insertable한 루트 컬럼만 전달한다.
     */
    default SqlStatement insertJoinedRoot(
            EntityMetadata<?> concreteMetadata,
            String rootTableName,
            List<PersistentProperty> rootColumns,
            Object entity) {
        throw new UnsupportedOperationException("insertJoinedRoot is not supported by this SqlRenderer");
    }

    /**
     * JOINED 서브타입 테이블 INSERT를 렌더한다 — 루트 PK를 FK로 공유하는 id 컬럼 + 서브타입 자기 컬럼들.
     * id는 루트 INSERT에서 확정된 값이 entity에 이미 채워져 있어야 한다.
     */
    default SqlStatement insertJoinedSubtype(
            EntityMetadata<?> concreteMetadata,
            List<PersistentProperty> ownColumns,
            Object entity) {
        throw new UnsupportedOperationException("insertJoinedSubtype is not supported by this SqlRenderer");
    }

    /**
     * JOINED 다형 SELECT(루트 타입 조회)를 렌더한다 — 루트 ⟕ 각 서브타입 테이블 LEFT JOIN 체인 + discriminator.
     * predicate/sort/page는 {@code querySpec}을 따른다(루트 테이블 컬럼 기준).
     */
    default SqlStatement selectJoinedPolymorphic(InheritanceLayout layout, QuerySpec querySpec) {
        throw new UnsupportedOperationException("selectJoinedPolymorphic is not supported by this SqlRenderer");
    }

    /**
     * JOINED 다형 findById를 렌더한다 — {@link #selectJoinedPolymorphic}과 동일한 JOIN 체인에 루트 PK 등치 WHERE.
     */
    default SqlStatement selectJoinedById(InheritanceLayout layout, Object id) {
        throw new UnsupportedOperationException("selectJoinedById is not supported by this SqlRenderer");
    }

    /**
     * JOINED 루트 테이블 UPDATE를 렌더한다(루트 테이블 컬럼만 SET, 루트 PK로 WHERE).
     */
    default SqlStatement updateJoinedRoot(
            EntityMetadata<?> concreteMetadata,
            String rootTableName,
            List<PersistentProperty> rootColumns,
            Object entity) {
        throw new UnsupportedOperationException("updateJoinedRoot is not supported by this SqlRenderer");
    }

    /**
     * JOINED 서브타입 테이블 UPDATE를 렌더한다(서브타입 자기 컬럼만 SET, FK id로 WHERE).
     * 서브타입 자기 컬럼이 없으면 {@code null}을 반환해 호출자가 이 단계를 건너뛰게 한다.
     */
    default SqlStatement updateJoinedSubtype(
            EntityMetadata<?> concreteMetadata,
            List<PersistentProperty> ownColumns,
            Object entity) {
        throw new UnsupportedOperationException("updateJoinedSubtype is not supported by this SqlRenderer");
    }

    /**
     * JOINED 서브타입 테이블에서 id로 DELETE(루트 DELETE보다 먼저 실행해 FK 의존성 보존).
     */
    default SqlStatement deleteJoinedSubtypeById(EntityMetadata<?> concreteMetadata, Object id) {
        throw new UnsupportedOperationException("deleteJoinedSubtypeById is not supported by this SqlRenderer");
    }

    /**
     * JOINED 루트 테이블에서 id로 DELETE(서브타입 DELETE 이후 실행).
     */
    default SqlStatement deleteJoinedRootById(InheritanceLayout layout, Object id) {
        throw new UnsupportedOperationException("deleteJoinedRootById is not supported by this SqlRenderer");
    }

    // ---------------------------------------------------------------------------------------------
    // @Inheritance(TABLE_PER_CLASS) — 다형 UNION ALL SELECT 렌더링.
    // INSERT/UPDATE/DELETE는 구체 타입 단일 테이블 경로(기존 insert/update/deleteById)를 그대로 쓰며,
    // 구체 테이블에 discriminator 물리 컬럼이 없으므로 insert()는 SINGLE_TABLE에서만 discriminator를 emit한다.
    // ---------------------------------------------------------------------------------------------

    /**
     * TABLE_PER_CLASS 다형 SELECT를 렌더한다 — 각 구체 서브타입 테이블을 {@code SELECT <정렬된 컬럼>,
     * '<disc>' AS dtype FROM subN} 으로 만들어 {@code UNION ALL}로 잇는다. 브랜치마다 없는 컬럼은 NULL로 채워
     * 컬럼 순서를 정렬한다. predicate/sort/page는 union 결과 바깥에서 적용한다.
     */
    default SqlStatement selectTablePerClassPolymorphic(InheritanceLayout layout, QuerySpec querySpec) {
        throw new UnsupportedOperationException("selectTablePerClassPolymorphic is not supported by this SqlRenderer");
    }

    /**
     * TABLE_PER_CLASS 다형 findById를 렌더한다 — UNION ALL 결과에 루트 PK 등치 WHERE.
     */
    default SqlStatement selectTablePerClassById(InheritanceLayout layout, Object id) {
        throw new UnsupportedOperationException("selectTablePerClassById is not supported by this SqlRenderer");
    }

    // ---------------------------------------------------------------------------------------------
    // @SecondaryTable — primary 테이블과 보조 테이블에 컬럼을 나눠 저장하는 멀티테이블 INSERT/SELECT/
    // UPDATE/DELETE 렌더링. primary 단일-테이블 메서드 시그니처는 그대로 두고, 보조 테이블은 아래 additive
    // default 메서드로 표현한다. reactive 순서(primary insert로 PK 확보 → 보조 insert; delete는 보조 → primary)는
    // 엔티티 오퍼레이션 계층이 보장한다. 렌더러는 각 단계의 단일 SQL 문장만 만든다.
    // ---------------------------------------------------------------------------------------------

    /**
     * 보조 테이블 INSERT를 렌더한다 — PK 조인 컬럼(= entity의 primary PK 값) + 그 보조 테이블로 라우팅된
     * insertable 컬럼들. primary INSERT가 먼저 실행되어 entity에 PK가 채워져 있어야 한다.
     */
    default SqlStatement insertSecondary(
            EntityMetadata<?> metadata, SecondaryTableInfo secondaryTable, Object entity) {
        throw new UnsupportedOperationException("insertSecondary is not supported by this SqlRenderer");
    }

    /**
     * 보조 테이블 UPDATE를 렌더한다 — 그 보조 테이블의 updatable 컬럼만 SET, PK 조인 컬럼으로 WHERE.
     * updatable 컬럼이 하나도 없으면 {@code null}을 반환해 호출자가 이 단계를 건너뛰게 한다.
     */
    default SqlStatement updateSecondary(
            EntityMetadata<?> metadata, SecondaryTableInfo secondaryTable, Object entity) {
        throw new UnsupportedOperationException("updateSecondary is not supported by this SqlRenderer");
    }

    /**
     * 보조 테이블에서 PK 조인 컬럼 등치로 DELETE(primary DELETE보다 먼저 실행해 FK 의존성 보존).
     */
    default SqlStatement deleteSecondaryById(
            EntityMetadata<?> metadata, SecondaryTableInfo secondaryTable, Object id) {
        throw new UnsupportedOperationException("deleteSecondaryById is not supported by this SqlRenderer");
    }

    /**
     * 보조 테이블을 가진 엔티티의 findById SELECT를 렌더한다 — primary ⟕ 각 보조 테이블 LEFT JOIN + PK 등치 WHERE.
     * {@code @SoftDelete}가 있으면 alive 가드를 덧붙인다.
     */
    default SqlStatement selectByIdWithSecondaryTables(EntityMetadata<?> metadata, Object id) {
        throw new UnsupportedOperationException(
                "selectByIdWithSecondaryTables is not supported by this SqlRenderer");
    }

    /**
     * 보조 테이블을 가진 엔티티의 일반 SELECT를 렌더한다 — primary ⟕ 각 보조 테이블 LEFT JOIN.
     * predicate/sort/page는 {@code querySpec}을 따른다(primary 컬럼 기준).
     */
    default SqlStatement selectWithSecondaryTables(EntityMetadata<?> metadata, QuerySpec querySpec) {
        throw new UnsupportedOperationException(
                "selectWithSecondaryTables is not supported by this SqlRenderer");
    }
}
