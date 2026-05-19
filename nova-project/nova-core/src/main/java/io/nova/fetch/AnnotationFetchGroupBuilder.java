package io.nova.fetch;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;

import java.lang.reflect.Field;
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <P> FetchGroup<P> buildFor(Class<P> parentType) {
        Objects.requireNonNull(parentType, "parentType must not be null");
        EntityMetadata<P> parentMetadata = metadataFactory.getEntityMetadata(parentType);
        FetchGroup.Builder<P> builder = FetchGroup.forParents(parentType);
        PersistentProperty idProperty = parentMetadata.idProperty();
        // @OneToMany — child 측 ManyToOne property의 FK column으로 IN-query를 발행한다.
        for (PersistentProperty oneToMany : parentMetadata.oneToManyProperties()) {
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
            builder.with(
                    (Class<Object>) childType,
                    fkColumn,
                    parentIdExtractor,
                    setter
            );
        }
        // @ManyToOne — child 측 PK column으로 IN-query를 발행한다. parent에서 FK 값을 꺼내 IN 키로 사용.
        for (PersistentProperty manyToOne : parentMetadata.manyToOneProperties()) {
            Class<?> childType = manyToOne.manyToOneTargetType();
            if (childType == null) {
                throw new IllegalStateException(
                        parentType.getName() + "." + manyToOne.propertyName()
                                + " @ManyToOne target type cannot be resolved");
            }
            EntityMetadata<?> childMetadata = metadataFactory.getEntityMetadata(childType);
            String childPkColumn = childMetadata.idProperty().columnName();
            Function<P, Object> fkExtractor = parent -> manyToOne.read(parent);
            BiConsumer<P, Object> singleSetter = (parent, child) ->
                    writeField(parent, manyToOne.field(), child);
            builder.withReferencedParent(
                    (Class<Object>) childType,
                    childPkColumn,
                    fkExtractor,
                    singleSetter
            );
        }
        return builder.build();
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
