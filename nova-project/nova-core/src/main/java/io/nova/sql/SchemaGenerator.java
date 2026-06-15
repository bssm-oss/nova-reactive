package io.nova.sql;

import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.PersistentProperty;

import java.util.List;

public interface SchemaGenerator {
    String createTable(EntityMetadata<?> metadata);

    /**
     * {@code @ManyToMany} link table을 만드는 {@code CREATE TABLE} 구문을 반환한다. 두 FK 컬럼(owner/target)과
     * 그 둘로 구성된 복합 PK를 가진다. dialect-aware 구현({@link AbstractSchemaGenerator})이 컬럼 타입을
     * 결정하고 식별자를 quote 한다. 기본 구현은 미지원이므로 dialect base가 override 해야 한다.
     */
    default String createJoinTable(JoinTableDefinition definition) {
        throw new UnsupportedOperationException("createJoinTable is not supported by this SchemaGenerator");
    }

    /**
     * {@link #createJoinTable(JoinTableDefinition)}의 idempotent 변형. 기본은 prefix를 재작성한다.
     */
    default String createJoinTableIfNotExists(JoinTableDefinition definition) {
        return createJoinTable(definition).replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * link table을 제거하는 {@code DROP TABLE} 구문. 기본은 raw 이름을 쓰며 dialect base가 quote 한다.
     */
    default String dropJoinTable(String joinTableName) {
        return "drop table " + joinTableName;
    }

    /**
     * link table을 제거하는 idempotent {@code DROP TABLE IF EXISTS} 구문.
     */
    default String dropJoinTableIfExists(String joinTableName) {
        return "drop table if exists " + joinTableName;
    }

    /**
     * {@code @ElementCollection} collection table을 만드는 {@code CREATE TABLE} 구문. (owner FK, value) 컬럼을
     * 가지며 복합 PK는 없다(같은 owner가 같은 값을 List에서 중복 보유할 수 있으므로). dialect base가 override 한다.
     */
    default String createCollectionTable(CollectionTableDefinition definition) {
        throw new UnsupportedOperationException("createCollectionTable is not supported by this SchemaGenerator");
    }

    default String createCollectionTableIfNotExists(CollectionTableDefinition definition) {
        return createCollectionTable(definition).replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * 테이블 생성과 별개로 발행할 secondary index 및 unique constraint DDL을 반환한다.
     * 기본 구현은 빈 리스트를 반환하므로, 구현체가 별도로 override 해야 실제 SQL이 만들어진다.
     */
    default List<String> createIndexes(EntityMetadata<?> metadata) {
        return List.of();
    }

    /**
     * Returns an idempotent {@code CREATE TABLE} statement that no-ops if the
     * table already exists. The default implementation rewrites the prefix of
     * {@link #createTable(EntityMetadata)} so any existing override is honored
     * verbatim. Dialects that need a different syntax (Oracle, which lacks
     * {@code IF NOT EXISTS} on {@code CREATE TABLE}) should override.
     */
    default String createTableIfNotExists(EntityMetadata<?> metadata) {
        String ddl = createTable(metadata);
        return ddl.replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * Returns a {@code DROP TABLE} statement that fails if the table does not
     * exist. Dialect-aware implementations (see {@link AbstractSchemaGenerator})
     * quote the table identifier; the default is a best-effort fallback that
     * uses the raw {@code metadata.tableName()}.
     */
    default String dropTable(EntityMetadata<?> metadata) {
        return "drop table " + metadata.tableName();
    }

    /**
     * Returns an idempotent {@code DROP TABLE} statement that no-ops if the
     * table does not exist. Dialects without {@code IF EXISTS} support (Oracle)
     * should override with the equivalent error-swallowing block.
     */
    default String dropTableIfExists(EntityMetadata<?> metadata) {
        return "drop table if exists " + metadata.tableName();
    }

    /**
     * 기존 테이블에 컬럼을 추가하는 {@code ALTER TABLE ... ADD COLUMN} 구문을 반환한다.
     * 기본 구현은 미지원으로 처리하므로 dialect가 직접 override 해야 한다.
     */
    default String alterTableAddColumn(EntityMetadata<?> metadata, PersistentProperty newColumn) {
        throw new UnsupportedOperationException(
                "alterTableAddColumn is not supported by this SchemaGenerator");
    }

    /**
     * 기존 테이블에서 컬럼을 제거하는 {@code ALTER TABLE ... DROP COLUMN} 구문을 반환한다.
     * 기본 구현은 미지원으로 처리하므로 dialect가 직접 override 해야 한다.
     */
    default String alterTableDropColumn(EntityMetadata<?> metadata, String columnName) {
        throw new UnsupportedOperationException(
                "alterTableDropColumn is not supported by this SchemaGenerator");
    }
}
