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
