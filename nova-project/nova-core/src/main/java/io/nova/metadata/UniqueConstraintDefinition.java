package io.nova.metadata;

import java.util.List;

/**
 * 엔티티에서 추출한 unique constraint 정의. {@link jakarta.persistence.UniqueConstraint}와
 * 1:1로 대응되며, {@code name}/{@code columns}는 검증을 마친 상태로 보관된다.
 */
public record UniqueConstraintDefinition(String name, List<String> columns) {
    public UniqueConstraintDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("UniqueConstraintDefinition name must be non-blank");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("UniqueConstraintDefinition columns must not be empty");
        }
        columns = List.copyOf(columns);
    }
}
