package io.nova.schema;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.ForeignKeyDefinition;
import io.nova.metadata.ManyToManyInfo;
import io.nova.metadata.PersistentProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 엔티티 메타데이터와 원본 매핑 어노테이션으로부터 JPA {@code @ForeignKey} 소스 호환 FK 제약을 해석한다.
 * 메타데이터 구조를 변경하지 않으려고(룰: 메타데이터 불변, 충돌 표면 최소화) {@code @ForeignKey}는
 * DDL 생성 시점에 이미 보관된 {@link PersistentProperty#field()}에서 직접 읽는다.
 *
 * <p>honor 규칙(JPA 기본값 정렬):
 * <ul>
 *   <li>{@code @ForeignKey} 미지정(= {@code PROVIDER_DEFAULT}) 또는 어노테이션 부재 → FK 미발행
 *       (Nova 기존 동작 보존, 하위 호환).</li>
 *   <li>{@code ConstraintMode.CONSTRAINT}(독립 {@code @ForeignKey}의 기본값) → 명명/자동명 FK 발행.</li>
 *   <li>{@code ConstraintMode.NO_CONSTRAINT} → 명시적 억제, FK 미발행.</li>
 * </ul>
 *
 * <p>적용 위치: owning {@code @ManyToOne}/{@code @OneToOne}의 {@code @JoinColumn(foreignKey=...)},
 * owning {@code @ManyToMany}의 {@code @JoinTable(foreignKey=, inverseForeignKey=)},
 * {@code @ElementCollection}의 {@code @CollectionTable(foreignKey=...)}.
 *
 * <p>FK + 상속(JOINED/SINGLE_TABLE/TABLE_PER_CLASS)은 물리 테이블 배치가 전략별로 달라 v1 범위 밖이므로,
 * 상속에 참여하는 엔티티는 FK 해석에서 건너뛴다. 다만 그런 엔티티가 {@code @ForeignKey(CONSTRAINT)}를
 * 명시했다면 조용한 미발행은 silent 부정확이므로 fail-fast로 거부해 가시화한다.
 *
 * <p>참조 컬럼은 항상 대상 엔티티의 단일 PK 컬럼이다. {@code @JoinColumn(referencedColumnName)}이 비어 있지
 * 않으면 그 값이 PK 컬럼과 일치할 때만 honor하고, 비-PK 컬럼이나 복합키 대상은 fail-fast로 거부한다(v1은 단일
 * PK 참조만 지원).
 */
final class ForeignKeyConstraints {

    private ForeignKeyConstraints() {
    }

    /**
     * 주어진 엔티티 타입들에서 발행할 FK 제약 정의를 해석한다. (테이블, 컬럼) 시그니처로 dedupe해 같은 타입이
     * 두 번 전달되거나 owning/inverse가 같은 link table을 가리켜도 ALTER가 중복 발행되지 않게 한다.
     */
    static List<ForeignKeyDefinition> resolve(List<Class<?>> types, EntityMetadataFactory factory) {
        LinkedHashMap<String, ForeignKeyDefinition> byKey = new LinkedHashMap<>();
        for (Class<?> type : types) {
            EntityMetadata<?> metadata = factory.getEntityMetadata(type);
            if (metadata.hasInheritance()) {
                // 상속 계층의 FK는 전략별 테이블 배치 차이로 v1 미지원. @ForeignKey(CONSTRAINT) 명시가 없으면
                // 기존 동작대로 조용히 건너뛰지만(테이블/컬럼 생성은 정상), 명시했다면 silent 미발행은 사용자를
                // 오도하므로 fail-fast로 거부한다.
                requireNoExplicitForeignKey(metadata);
                continue;
            }
            collectToOne(metadata, factory, byKey);
            collectManyToMany(metadata, factory, byKey);
            collectElementCollection(metadata, factory, byKey);
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * owning {@code @ManyToOne}/{@code @OneToOne}(둘 다 {@code manyToOne()=true}로 모델링됨)의
     * {@code @JoinColumn(foreignKey=...)}를 해석한다. FK는 자식 테이블의 FK 컬럼 → 대상 엔티티의 PK 컬럼.
     */
    private static void collectToOne(
            EntityMetadata<?> metadata, EntityMetadataFactory factory,
            LinkedHashMap<String, ForeignKeyDefinition> byKey) {
        for (PersistentProperty property : metadata.manyToOneProperties()) {
            JoinColumn joinColumn = property.field().getAnnotation(JoinColumn.class);
            ForeignKey foreignKey = joinColumn == null ? null : joinColumn.foreignKey();
            if (!emit(foreignKey)) {
                continue;
            }
            EntityMetadata<?> target = factory.getEntityMetadata(property.manyToOneTargetType());
            String relationDesc = "@ManyToOne/@OneToOne " + metadata.entityType().getName()
                    + "#" + property.propertyName();
            add(byKey, new ForeignKeyDefinition(
                    metadata.tableName(),
                    foreignKey.name(),
                    List.of(property.columnName()),
                    target.tableName(),
                    referencedColumns(target, joinColumn, relationDesc)));
        }
    }

    /**
     * owning {@code @ManyToMany}의 {@code @JoinTable}에서 owner FK({@code foreignKey})와 inverse
     * FK({@code inverseForeignKey})를 각각 해석한다. 두 FK 모두 link table → 각 엔티티 PK를 참조한다.
     */
    private static void collectManyToMany(
            EntityMetadata<?> metadata, EntityMetadataFactory factory,
            LinkedHashMap<String, ForeignKeyDefinition> byKey) {
        for (PersistentProperty property : metadata.manyToManyProperties()) {
            ManyToManyInfo info = property.manyToManyInfo();
            if (!info.owning()) {
                continue;
            }
            JoinTable joinTable = property.field().getAnnotation(JoinTable.class);
            ForeignKey ownerForeignKey = joinTable == null ? null : joinTable.foreignKey();
            if (emit(ownerForeignKey)) {
                String relationDesc = "@JoinTable(joinColumns) of @ManyToMany "
                        + metadata.entityType().getName() + "#" + property.propertyName();
                add(byKey, new ForeignKeyDefinition(
                        info.joinTableName(),
                        ownerForeignKey.name(),
                        List.of(info.ownerForeignKeyColumn()),
                        metadata.tableName(),
                        referencedColumns(metadata, joinColumns(joinTable, true), relationDesc)));
            }
            ForeignKey inverseForeignKey = joinTable == null ? null : joinTable.inverseForeignKey();
            if (emit(inverseForeignKey)) {
                EntityMetadata<?> target = factory.getEntityMetadata(info.targetType());
                String relationDesc = "@JoinTable(inverseJoinColumns) of @ManyToMany "
                        + metadata.entityType().getName() + "#" + property.propertyName();
                add(byKey, new ForeignKeyDefinition(
                        info.joinTableName(),
                        inverseForeignKey.name(),
                        List.of(info.targetForeignKeyColumn()),
                        target.tableName(),
                        referencedColumns(target, joinColumns(joinTable, false), relationDesc)));
            }
        }
    }

    /**
     * {@code @ElementCollection}의 {@code @CollectionTable(foreignKey=...)}를 해석한다. owner FK 컬럼 →
     * 소유 엔티티 PK 컬럼을 참조한다.
     */
    private static void collectElementCollection(
            EntityMetadata<?> metadata, EntityMetadataFactory factory,
            LinkedHashMap<String, ForeignKeyDefinition> byKey) {
        for (PersistentProperty property : metadata.elementCollectionProperties()) {
            CollectionTable collectionTable = property.field().getAnnotation(CollectionTable.class);
            ForeignKey foreignKey = collectionTable == null ? null : collectionTable.foreignKey();
            if (!emit(foreignKey)) {
                continue;
            }
            ElementCollectionInfo info = property.elementCollectionInfo();
            String relationDesc = "@CollectionTable of @ElementCollection "
                    + metadata.entityType().getName() + "#" + property.propertyName();
            add(byKey, new ForeignKeyDefinition(
                    info.collectionTableName(),
                    foreignKey.name(),
                    List.of(info.ownerForeignKeyColumn()),
                    metadata.tableName(),
                    referencedColumns(metadata, collectionTable.joinColumns(), relationDesc)));
        }
    }

    /**
     * FK가 참조할 대상 컬럼(들)을 해석한다 — Nova v1은 대상 엔티티의 단일 PK 컬럼만 참조한다. 대상이 복합키이거나
     * {@code @JoinColumn}이 여러 컬럼을 선언하면 단일 PK 참조로 표현할 수 없으므로 fail-fast로 거부한다.
     * {@code @JoinColumn(referencedColumnName)}이 비어 있으면 PK 컬럼을 그대로 쓰고, 지정되었으면 PK 컬럼과
     * 일치할 때만 honor하며, 비-PK 컬럼을 가리키면 거부한다(silent 부정확 방지).
     */
    private static List<String> referencedColumns(
            EntityMetadata<?> target, JoinColumn[] joinColumns, String relationDesc) {
        if (target.hasCompositeId()) {
            throw new IllegalStateException(
                    "@ForeignKey on " + relationDesc + " targets composite-key entity "
                            + target.entityType().getName() + " (" + target.idProperties().size()
                            + " id columns); Nova v1 only supports foreign keys referencing a single-column "
                            + "primary key. Remove the @ForeignKey or model the reference without it.");
        }
        String pkColumn = target.idProperty().columnName();
        if (joinColumns.length > 1) {
            throw new IllegalStateException(
                    "@JoinColumn on " + relationDesc + " declares " + joinColumns.length
                            + " columns; Nova v1 only supports single-column foreign keys referencing the target "
                            + "primary key \"" + pkColumn + "\".");
        }
        String referenced = joinColumns.length == 0 ? "" : joinColumns[0].referencedColumnName();
        if (!referenced.isBlank() && !referenced.equals(pkColumn)) {
            throw new IllegalStateException(
                    "@JoinColumn(referencedColumnName=\"" + referenced + "\") on " + relationDesc
                            + " references a non-primary-key column; Nova v1 only supports foreign keys referencing "
                            + "the target primary key \"" + pkColumn + "\".");
        }
        return List.of(pkColumn);
    }

    /**
     * 단일 {@code @JoinColumn}(예: {@code @ManyToOne})을 배열 형태로 어댑트해 {@link #referencedColumns}에 넘긴다.
     */
    private static List<String> referencedColumns(
            EntityMetadata<?> target, JoinColumn joinColumn, String relationDesc) {
        return referencedColumns(
                target,
                joinColumn == null ? new JoinColumn[0] : new JoinColumn[] {joinColumn},
                relationDesc);
    }

    /**
     * {@code @JoinTable}의 owner({@code joinColumns}) 또는 inverse({@code inverseJoinColumns}) 측 조인 컬럼 배열을
     * 반환한다. {@code referencedColumnName} honor 및 복합 조인 컬럼 거부에 사용된다.
     */
    private static JoinColumn[] joinColumns(JoinTable joinTable, boolean owner) {
        if (joinTable == null) {
            return new JoinColumn[0];
        }
        return owner ? joinTable.joinColumns() : joinTable.inverseJoinColumns();
    }

    /**
     * 상속에 참여하는 엔티티가 {@code @ForeignKey(CONSTRAINT)}를 명시했는지 검사해, 명시했다면 fail-fast로 거부한다.
     * FK + 상속(JOINED/SINGLE_TABLE/TABLE_PER_CLASS)은 물리 테이블 배치가 전략별로 달라 v1 미지원이므로, 조용히
     * 건너뛰는 대신 명확한 미지원 메시지로 가시화한다.
     */
    private static void requireNoExplicitForeignKey(EntityMetadata<?> metadata) {
        boolean declared = false;
        for (PersistentProperty property : metadata.manyToOneProperties()) {
            JoinColumn joinColumn = property.field().getAnnotation(JoinColumn.class);
            if (joinColumn != null && emit(joinColumn.foreignKey())) {
                declared = true;
                break;
            }
        }
        if (!declared) {
            for (PersistentProperty property : metadata.manyToManyProperties()) {
                if (!property.manyToManyInfo().owning()) {
                    continue;
                }
                JoinTable joinTable = property.field().getAnnotation(JoinTable.class);
                if (joinTable != null && (emit(joinTable.foreignKey()) || emit(joinTable.inverseForeignKey()))) {
                    declared = true;
                    break;
                }
            }
        }
        if (!declared) {
            for (PersistentProperty property : metadata.elementCollectionProperties()) {
                CollectionTable collectionTable = property.field().getAnnotation(CollectionTable.class);
                if (collectionTable != null && emit(collectionTable.foreignKey())) {
                    declared = true;
                    break;
                }
            }
        }
        if (declared) {
            throw new IllegalStateException(
                    "@ForeignKey(ConstraintMode.CONSTRAINT) on inheritance entity "
                            + metadata.entityType().getName() + " is not supported: Nova v1 does not emit foreign "
                            + "keys for entities participating in @Inheritance (JOINED/SINGLE_TABLE/TABLE_PER_CLASS) "
                            + "because physical table layout differs per strategy. Remove the @ForeignKey or use "
                            + "ConstraintMode.NO_CONSTRAINT.");
        }
    }

    /**
     * {@code @ForeignKey}가 FK 제약을 실제로 발행해야 하는지 — {@code CONSTRAINT}일 때만 발행한다.
     * {@code NO_CONSTRAINT}는 명시적 억제, {@code PROVIDER_DEFAULT}/부재는 Nova 기존 동작(미발행)이다.
     */
    private static boolean emit(ForeignKey foreignKey) {
        return foreignKey != null && foreignKey.value() == ConstraintMode.CONSTRAINT;
    }

    private static void add(
            LinkedHashMap<String, ForeignKeyDefinition> byKey, ForeignKeyDefinition definition) {
        byKey.putIfAbsent(definition.table() + "::" + definition.columns(), definition);
    }
}
