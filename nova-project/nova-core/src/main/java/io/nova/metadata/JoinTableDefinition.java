package io.nova.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code @ManyToMany} link table의 물리 정의 — DDL 생성과 link-row SQL 렌더링에 쓰인다. owner/target 각각의
 * FK를 <b>컬럼 리스트</b>로 보관해 단일키(컬럼 1개)와 복합키({@code @EmbeddedId}/{@code @IdClass}, 컬럼 N개)를
 * 같은 모델로 표현한다. 각 {@link ForeignKeyColumn}은 참조 {@code @Id} 컴포넌트 순서대로 정렬되며 write/read/DDL/FK
 * 네 경로가 이 순서를 단일 소스로 공유한다(컬럼 순서 어긋남은 silent 손상이므로 한 자리에서 결정한다).
 */
public record JoinTableDefinition(
        String tableName,
        List<ForeignKeyColumn> ownerForeignKeyColumns,
        List<ForeignKeyColumn> targetForeignKeyColumns
) {
    public JoinTableDefinition {
        ownerForeignKeyColumns = List.copyOf(ownerForeignKeyColumns);
        targetForeignKeyColumns = List.copyOf(targetForeignKeyColumns);
    }

    /** link table FK 컬럼 1개 — 컬럼명과 참조 {@code @Id}의 물리 저장 특성을 담는다. */
    public record ForeignKeyColumn(String columnName, ColumnStorage storage) {
    }

    /** owner 또는 target FK가 2개 이상 컬럼(복합키)인지 여부. */
    public boolean composite() {
        return ownerForeignKeyColumns.size() > 1 || targetForeignKeyColumns.size() > 1;
    }

    /**
     * owner/target 메타데이터와 {@link ManyToManyInfo}로부터 link table 정의를 조립한다. 단일키·복합키를 모두
     * 처리하며 DDL·runtime SQL이 동일한 정의를 공유하도록 단일 자리에서 컬럼 저장 특성/순서를 결정한다.
     */
    public static JoinTableDefinition of(
            EntityMetadata<?> ownerMetadata, ManyToManyInfo info, EntityMetadata<?> targetMetadata) {
        return new JoinTableDefinition(
                info.joinTableName(),
                columnsFor(ownerMetadata, info.ownerForeignKeyColumns()),
                columnsFor(targetMetadata, info.targetForeignKeyColumns()));
    }

    private static List<ForeignKeyColumn> columnsFor(
            EntityMetadata<?> metadata, List<ManyToManyInfo.JoinColumnRef> refs) {
        if (!metadata.hasCompositeId()) {
            ManyToManyInfo.JoinColumnRef ref = refs.get(0);
            return List.of(new ForeignKeyColumn(ref.columnName(), ColumnStorage.from(metadata.idProperty())));
        }
        // 복합키: 참조 컬럼명으로 @Id 컴포넌트를 찾아 전체 저장 특성을 재사용한다.
        Map<String, PersistentProperty> byColumn = new LinkedHashMap<>();
        for (PersistentProperty idProperty : metadata.idProperties()) {
            byColumn.put(idProperty.columnName(), idProperty);
        }
        List<ForeignKeyColumn> columns = new ArrayList<>(refs.size());
        for (ManyToManyInfo.JoinColumnRef ref : refs) {
            PersistentProperty idProperty = byColumn.get(ref.referencedColumnName());
            if (idProperty == null) {
                throw new IllegalStateException("@ManyToMany join column \"" + ref.columnName()
                        + "\" references unknown @Id column \"" + ref.referencedColumnName()
                        + "\" on " + metadata.entityType().getName());
            }
            columns.add(new ForeignKeyColumn(ref.columnName(), ColumnStorage.from(idProperty)));
        }
        return columns;
    }
}
