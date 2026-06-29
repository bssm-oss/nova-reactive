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
 * 상속에 참여하는 엔티티는 FK 해석에서 건너뛴다.
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
                // 상속 계층의 FK는 전략별 테이블 배치 차이로 v1 미지원 — 조용히 건너뛴다(테이블/컬럼 생성은 정상).
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
            add(byKey, new ForeignKeyDefinition(
                    metadata.tableName(),
                    foreignKey.name(),
                    List.of(property.columnName()),
                    target.tableName(),
                    List.of(target.idProperty().columnName())));
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
                add(byKey, new ForeignKeyDefinition(
                        info.joinTableName(),
                        ownerForeignKey.name(),
                        List.of(info.ownerForeignKeyColumn()),
                        metadata.tableName(),
                        List.of(metadata.idProperty().columnName())));
            }
            ForeignKey inverseForeignKey = joinTable == null ? null : joinTable.inverseForeignKey();
            if (emit(inverseForeignKey)) {
                EntityMetadata<?> target = factory.getEntityMetadata(info.targetType());
                add(byKey, new ForeignKeyDefinition(
                        info.joinTableName(),
                        inverseForeignKey.name(),
                        List.of(info.targetForeignKeyColumn()),
                        target.tableName(),
                        List.of(target.idProperty().columnName())));
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
            add(byKey, new ForeignKeyDefinition(
                    info.collectionTableName(),
                    foreignKey.name(),
                    List.of(info.ownerForeignKeyColumn()),
                    metadata.tableName(),
                    List.of(metadata.idProperty().columnName())));
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
