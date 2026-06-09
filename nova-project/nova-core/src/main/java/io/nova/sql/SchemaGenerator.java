package io.nova.sql;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;

import java.util.List;

public interface SchemaGenerator {
    String createTable(EntityMetadata<?> metadata);

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
