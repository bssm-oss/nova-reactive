package io.nova.fetch;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import io.nova.query.Sort;
import jakarta.persistence.OrderBy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * {@code @ManyToOne}/{@code @OneToMany} 어노테이션이 선언된 entity의 {@link EntityMetadata}에서
 * {@link FetchGroup}을 자동으로 만들어낸다. 결과 FetchGroup은 사용자가 명시한 group과 그대로 merge
 * 가능한 형태이므로 hydration 진입점에서 dedupe만 처리하면 된다.
 *
 * <p>core production code 의존성을 늘리지 않기 위해 reflection 직접 read/write로만 setter를 합성한다 —
 * setter method 이름을 추론하거나 별도 SPI를 끌어들이지 않는다.
 *
 * <p>parent property를 setter로 주입할 때 entity 인스턴스에 해당 필드가 없으면(예: relation 필드가 final이거나
 * static, transient는 metadata 단계에서 이미 제외됨) {@link IllegalStateException}이 hydration 시점에 발화된다.
 *
 * <p>builder는 stateless하므로 {@link EntityMetadataFactory} 한 개만 받아 모든 entity 타입에 재사용한다.
 */
public final class AnnotationFetchGroupBuilder {
    private final EntityMetadataFactory metadataFactory;

    public AnnotationFetchGroupBuilder(EntityMetadataFactory metadataFactory) {
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
    }

    /**
     * 주어진 parent entity 타입의 모든 {@code @ManyToOne}/{@code @OneToMany} property를 spec으로 변환해
     * {@link FetchGroup}을 만든다. 관계가 하나도 없으면 빈 spec 리스트를 가진 FetchGroup이 반환된다.
     */
    public <P> FetchGroup<P> buildFor(Class<P> parentType) {
        Objects.requireNonNull(parentType, "parentType must not be null");
        EntityMetadata<P> parentMetadata = metadataFactory.getEntityMetadata(parentType);
        FetchGroup.Builder<P> builder = FetchGroup.forParents(parentType);
        PersistentProperty idProperty = parentMetadata.idProperty();
        // @OneToMany — child 측 ManyToOne property의 FK column으로 IN-query를 발행한다.
        for (PersistentProperty oneToMany : parentMetadata.oneToManyProperties()) {
            addOneToManySpec(builder, parentType, oneToMany, idProperty);
        }
        // @ManyToOne — child 측 PK column으로 IN-query를 발행한다. parent에서 FK 값을 꺼내 IN 키로 사용.
        for (PersistentProperty manyToOne : parentMetadata.manyToOneProperties()) {
            if (manyToOne.isCompositeToOne()) {
                // 복합키 타겟 to-one은 단일 PK IN-query로 자동 hydrate할 수 없다(FK가 N개 컬럼). mapRow가 이미
                // 복합 id를 채운 참조 stub을 만들어 두므로, 자동 fetch group에서 제외해 그 stub을 그대로 노출한다
                // (복합키 타겟의 full graph fetch는 별도 Wave 범위). read()가 단일 @Id를 못 찾아 던지는 것도 회피한다.
                continue;
            }
            addManyToOneSpec(builder, parentType, manyToOne);
        }
        // inverse @OneToOne — 소유 측(반대편)이 FK를 가지므로 child 측 FK column으로 IN-query를 발행하고 단건만 주입한다.
        for (PersistentProperty oneToOne : parentMetadata.oneToOneInverseProperties()) {
            addInverseOneToOneSpec(builder, parentType, oneToOne, idProperty);
        }
        return builder.build();
    }

    /**
     * 단일 to-one/to-many 연관 property({@code attributeName})만 담는 {@link FetchGroup}을 만든다. EntityGraph의
     * 중첩(depth&gt;1) fetch에서 특정 레벨의 <b>선언된</b> 연관 하나만 배치 로드할 때 쓴다 — 전체 연관을 담는
     * {@link #buildFor(Class)}와 달리 정확히 그 속성만 fetch해 불필요한 쿼리를 내지 않는다.
     *
     * <p>{@code @ManyToOne}(단일키 타겟)/{@code @OneToMany}/inverse {@code @OneToOne}만 표현 가능하다.
     * {@code @ManyToMany}/{@code @ElementCollection}/비연관 속성/복합키 타겟 to-one은 flat spec으로 표현할 수
     * 없어 {@link IllegalArgumentException}으로 거부한다(호출자가 종류를 미리 분기해야 한다).
     */
    public <P> FetchGroup<P> buildForAttribute(Class<P> parentType, String attributeName) {
        Objects.requireNonNull(parentType, "parentType must not be null");
        Objects.requireNonNull(attributeName, "attributeName must not be null");
        EntityMetadata<P> parentMetadata = metadataFactory.getEntityMetadata(parentType);
        PersistentProperty property = parentMetadata.findProperty(attributeName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Attribute '" + attributeName + "' does not exist on " + parentType.getName()));
        FetchGroup.Builder<P> builder = FetchGroup.forParents(parentType);
        PersistentProperty idProperty = parentMetadata.idProperty();
        if (property.oneToMany()) {
            addOneToManySpec(builder, parentType, property, idProperty);
        } else if (property.inverseToOne()) {
            addInverseOneToOneSpec(builder, parentType, property, idProperty);
        } else if (property.manyToOne()) {
            if (property.isCompositeToOne()) {
                throw new IllegalArgumentException(
                        parentType.getName() + "." + attributeName
                                + " is a composite-key to-one; single-column FK batch fetch is not supported");
            }
            addManyToOneSpec(builder, parentType, property);
        } else {
            throw new IllegalArgumentException(
                    parentType.getName() + "." + attributeName
                            + " is not a to-one/to-many association representable as a FetchGroup spec "
                            + "(@ManyToMany/@ElementCollection/basic attributes are handled on a separate path)");
        }
        return builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P> void addOneToManySpec(
            FetchGroup.Builder<P> builder, Class<P> parentType, PersistentProperty oneToMany,
            PersistentProperty idProperty) {
        Class<?> childType = oneToMany.oneToManyTargetType();
        if (childType == null) {
            throw new IllegalStateException(
                    parentType.getName() + "." + oneToMany.propertyName()
                            + " @OneToMany requires targetEntity to be specified (cannot infer from generic List)");
        }
        String fkColumn = resolveOneToManyForeignKeyColumn(parentType, oneToMany, childType);
        Function<P, Object> parentIdExtractor = parent -> idProperty.read(parent);
        BiConsumer<P, java.util.List<Object>> setter = (parent, children) ->
                writeField(parent, oneToMany.field(), children);
        // @OrderColumn(child 테이블의 물리 순서 컬럼)과 @OrderBy(child property 정렬)는 상호 배타(factory에서 거부)다.
        String orderColumn = oneToMany.oneToManyOrderColumn() != null
                ? oneToMany.oneToManyOrderColumn().columnName()
                : null;
        builder.with(
                (Class<Object>) childType,
                fkColumn,
                parentIdExtractor,
                setter,
                resolveOrderBy(oneToMany.field(), childType),
                orderColumn
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P> void addManyToOneSpec(
            FetchGroup.Builder<P> builder, Class<P> parentType, PersistentProperty manyToOne) {
        Class<?> childType = manyToOne.manyToOneTargetType();
        if (childType == null) {
            throw new IllegalStateException(
                    parentType.getName() + "." + manyToOne.propertyName()
                            + " @ManyToOne target type cannot be resolved");
        }
        EntityMetadata<?> childMetadata = metadataFactory.getEntityMetadata(childType);
        String childPkColumn = childMetadata.idProperty().columnName();
        Function<P, Object> fkExtractor = parent -> manyToOne.read(parent);
        // 관계에 @Access(PROPERTY)가 적용되면 field 직접 대입 대신 JavaBean setter로 참조를 주입한다
        // (access 전략은 PersistentProperty가 생성 시점에 확정해 두므로 여기서는 위임만 한다).
        BiConsumer<P, Object> singleSetter = manyToOne::writeReference;
        builder.withReferencedParent(
                (Class<Object>) childType,
                childPkColumn,
                fkExtractor,
                singleSetter
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P> void addInverseOneToOneSpec(
            FetchGroup.Builder<P> builder, Class<P> parentType, PersistentProperty oneToOne,
            PersistentProperty idProperty) {
        Class<?> childType = oneToOne.oneToManyTargetType();
        if (childType == null) {
            throw new IllegalStateException(
                    parentType.getName() + "." + oneToOne.propertyName()
                            + " inverse @OneToOne requires a resolvable target entity type");
        }
        String fkColumn = resolveOneToManyForeignKeyColumn(parentType, oneToOne, childType);
        Function<P, Object> parentIdExtractor = parent -> idProperty.read(parent);
        // inverse @OneToOne도 @Access(PROPERTY)면 setter로 참조를 주입한다(FIELD면 field 직접 대입).
        BiConsumer<P, Object> singleSetter = oneToOne::writeReference;
        builder.withInverseReference(
                (Class<Object>) childType,
                fkColumn,
                parentIdExtractor,
                singleSetter
        );
    }

    /**
     * {@code @OneToMany} 필드의 {@code @OrderBy}를 child 정렬 {@link Sort}로 변환한다. 애너테이션이 없으면
     * {@code null}(정렬 없음). 값이 비어 있으면 JPA 규약대로 child PK 오름차순. 그 외에는 {@code "prop ASC,
     * prop2 DESC"} 형식을 파싱한다 — 프로퍼티 이름은 child 엔티티 기준이며 SQL 렌더링 단계에서 컬럼으로 매핑된다.
     */
    private Sort resolveOrderBy(Field oneToManyField, Class<?> childType) {
        OrderBy orderBy = oneToManyField.getAnnotation(OrderBy.class);
        if (orderBy == null) {
            return null;
        }
        String value = orderBy.value().trim();
        if (value.isEmpty()) {
            String childId = metadataFactory.getEntityMetadata(childType).idProperty().propertyName();
            return Sort.by(Sort.Order.asc(childId));
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String part : value.split(",")) {
            String[] tokens = part.trim().split("\\s+");
            String property = tokens[0];
            boolean descending = tokens.length > 1 && tokens[1].equalsIgnoreCase("DESC");
            orders.add(descending ? Sort.Order.desc(property) : Sort.Order.asc(property));
        }
        return Sort.by(orders.toArray(new Sort.Order[0]));
    }

    /**
     * child entity의 {@code @ManyToOne mappedBy} property를 찾아 FK 컬럼 이름을 resolve한다.
     * mappedBy가 child entity 안에 존재하지 않거나 그 property가 {@code @ManyToOne}이 아니면 거부한다.
     */
    private String resolveOneToManyForeignKeyColumn(
            Class<?> parentType, PersistentProperty oneToMany, Class<?> childType) {
        String mappedBy = oneToMany.oneToManyMappedBy();
        EntityMetadata<?> childMetadata = metadataFactory.getEntityMetadata(childType);
        PersistentProperty owning = childMetadata.findProperty(mappedBy)
                .orElseThrow(() -> new IllegalStateException(
                        parentType.getName() + "." + oneToMany.propertyName()
                                + " @OneToMany(mappedBy=\"" + mappedBy + "\") does not exist on "
                                + childType.getName()));
        if (!owning.manyToOne()) {
            throw new IllegalStateException(
                    parentType.getName() + "." + oneToMany.propertyName()
                            + " @OneToMany(mappedBy=\"" + mappedBy + "\") refers to a non-@ManyToOne property on "
                            + childType.getName());
        }
        return owning.columnName();
    }

    private static void writeField(Object instance, Field field, Object value) {
        field.setAccessible(true);
        try {
            field.set(instance, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot write relation field " + field.getDeclaringClass().getName() + "." + field.getName(),
                    exception);
        }
    }
}
