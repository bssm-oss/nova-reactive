package io.nova.metadata;

import java.util.List;

/**
 * 엔티티에서 추출한 secondary index 정의. {@link jakarta.persistence.Index}와
 * 1:1로 대응되며, {@code name}/{@code columns}는 검증을 마친 상태로 보관된다.
 */
public record IndexDefinition(String name, List<String> columns) {
    public IndexDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("IndexDefinition name must be non-blank");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("IndexDefinition columns must not be empty");
        }
        columns = List.copyOf(columns);
    }
}
