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

    /**
     * link table FK 컬럼 1개 — 컬럼명과 그 SQL 컬럼 타입 유도에 쓰이는 저장 표현 Java 타입, 그리고 varchar 계열
     * 컬럼 길이. 단일키는 {@code @Id}의 Java 타입(레거시 호환), 복합키는 참조 컴포넌트의 저장타입을 담는다.
     */
    public record ForeignKeyColumn(String columnName, Class<?> columnType, int length) {
    }

    /**
     * 단일키 owner/target용 하위 호환 생성자(FK 컬럼 1개씩). 레거시 호출부/테스트가 owner/target {@code @Id}의
     * Java 타입을 직접 넘길 때 쓴다. 길이는 varchar 기본값 {@code 255}로 고정한다(단일키 DDL은 길이를 참조하지
     * 않던 기존 동작 보존).
     */
    public JoinTableDefinition(
            String tableName,
            String ownerForeignKeyColumn,
            Class<?> ownerForeignKeyType,
            String targetForeignKeyColumn,
            Class<?> targetForeignKeyType) {
        this(tableName,
                List.of(new ForeignKeyColumn(ownerForeignKeyColumn, ownerForeignKeyType, 255)),
                List.of(new ForeignKeyColumn(targetForeignKeyColumn, targetForeignKeyType, 255)));
    }

    /**
     * 첫 owner FK 컬럼명(단일키 하위 호환). 복합키에서는 첫 컬럼만 보므로 컬럼 전체는 {@link #ownerForeignKeyColumns()}를 쓴다.
     */
    public String ownerForeignKeyColumn() {
        return ownerForeignKeyColumns.get(0).columnName();
    }

    public Class<?> ownerForeignKeyType() {
        return ownerForeignKeyColumns.get(0).columnType();
    }

    public String targetForeignKeyColumn() {
        return targetForeignKeyColumns.get(0).columnName();
    }

    public Class<?> targetForeignKeyType() {
        return targetForeignKeyColumns.get(0).columnType();
    }

    /**
     * owner 또는 target FK가 2개 이상 컬럼(복합키)인지 여부.
     */
    public boolean composite() {
        return ownerForeignKeyColumns.size() > 1 || targetForeignKeyColumns.size() > 1;
    }

    /**
     * owner/target 메타데이터와 {@link ManyToManyInfo}로부터 link table 정의를 조립한다. 단일키·복합키를 모두
     * 처리하며 DDL·runtime SQL이 동일한 정의를 공유하도록 단일 자리에서 컬럼 타입/순서를 결정한다. 복합키는 참조
     * {@code @Id} 컴포넌트의 저장타입/길이를, 단일키는 {@code @Id}의 Java 타입(레거시 호환)을 컬럼 타입으로 쓴다.
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
            // 단일키: 레거시 동작 보존 — 참조 @Id의 도메인 Java 타입 + varchar 255. FK 컬럼은 1개다.
            ManyToManyInfo.JoinColumnRef ref = refs.get(0);
            return List.of(new ForeignKeyColumn(
                    ref.columnName(), wrapPrimitive(metadata.idProperty().javaType()), 255));
        }
        // 복합키: 참조 컬럼명으로 @Id 컴포넌트를 찾아 그 저장타입/길이를 쓴다(read-source-type 함정 회피).
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
            columns.add(new ForeignKeyColumn(
                    ref.columnName(), wrapPrimitive(idProperty.columnType()), idProperty.length()));
        }
        return columns;
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        return type;
    }
}
