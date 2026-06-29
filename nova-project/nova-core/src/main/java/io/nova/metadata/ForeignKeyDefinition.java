package io.nova.metadata;

import java.util.List;

/**
 * 완전히 해석된 외래키(FOREIGN KEY) 제약의 물리 정의 — {@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY}
 * DDL 생성에 사용된다. JPA {@code @ForeignKey} 소스 호환을 honor하는 단일 표현이며, 제약을 어디에 붙이는지
 * (관계 FK / {@code @JoinTable} link / {@code @CollectionTable})와 무관하게 동일한 형태로 렌더된다.
 *
 * <ul>
 *   <li>{@link #table()} — 제약이 추가되는(자식) 테이블.</li>
 *   <li>{@link #constraintName()} — {@code @ForeignKey(name=...)}로 지정된 이름. 비어 있으면 dialect가
 *       결정적 자동 이름을 만든다.</li>
 *   <li>{@link #columns()} — 자식 테이블의 FK 컬럼(들). v1은 단일 컬럼이지만 복합 대비 리스트로 둔다.</li>
 *   <li>{@link #referencedTable()} / {@link #referencedColumns()} — 참조되는(부모) 테이블과 그 PK 컬럼(들).</li>
 * </ul>
 *
 * {@code columns}와 {@code referencedColumns}는 같은 개수여야 한다(컬럼 대응).
 */
public record ForeignKeyDefinition(
        String table,
        String constraintName,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns
) {
    public ForeignKeyDefinition {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Foreign key table must not be blank");
        }
        if (referencedTable == null || referencedTable.isBlank()) {
            throw new IllegalArgumentException("Foreign key referencedTable must not be blank");
        }
        constraintName = constraintName == null ? "" : constraintName;
        columns = List.copyOf(columns);
        referencedColumns = List.copyOf(referencedColumns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Foreign key must reference at least one column");
        }
        if (columns.size() != referencedColumns.size()) {
            throw new IllegalArgumentException(
                    "Foreign key column count " + columns.size()
                            + " must match referenced column count " + referencedColumns.size());
        }
    }
}
