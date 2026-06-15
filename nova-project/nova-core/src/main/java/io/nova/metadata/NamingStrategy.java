package io.nova.metadata;

public interface NamingStrategy {
    String tableName(Class<?> entityType);

    String columnName(String propertyName);

    /**
     * {@code @ManyToMany} link table의 기본 이름 — JPA 규약대로 {@code owner_table + "_" + target_table}.
     * {@code @JoinTable(name=...)}이 지정되면 그 값이 우선한다.
     */
    default String joinTableName(String ownerTable, String targetTable) {
        return ownerTable + "_" + targetTable;
    }

    /**
     * link table의 기본 join 컬럼 이름 — JPA 규약대로 {@code entityName + "_" + idColumn}(예 {@code student_id}).
     * {@code @JoinColumn(name=...)} / {@code inverseJoinColumns}가 지정되면 그 값이 우선한다.
     */
    default String joinColumnName(String entityName, String idColumn) {
        return columnName(entityName) + "_" + idColumn;
    }
}
