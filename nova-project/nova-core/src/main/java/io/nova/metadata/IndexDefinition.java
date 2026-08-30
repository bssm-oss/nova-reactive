package io.nova.metadata;

import java.util.List;

/**
 * 엔티티에서 추출한 secondary index 정의. {@link jakarta.persistence.Index}와
 * 1:1로 대응되며, 모든 구성 요소는 검증을 마친 상태로 보관된다.
 */
public record IndexDefinition(String name, List<Column> columns, boolean unique, String options) {
    public IndexDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("IndexDefinition name must be non-blank");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("IndexDefinition columns must not be empty");
        }
        columns = List.copyOf(columns);
        options = options == null ? "" : options;
        if (options.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("IndexDefinition options must not contain NUL");
        }
    }

    /**
     * An index column and its optional sort direction. A {@code null} direction preserves a
     * columnList term without an explicit direction.
     */
    public record Column(String name, Direction direction) {
        public Column {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("IndexDefinition column name must be non-blank");
            }
        }
    }

    public enum Direction {
        ASC,
        DESC
    }
}
