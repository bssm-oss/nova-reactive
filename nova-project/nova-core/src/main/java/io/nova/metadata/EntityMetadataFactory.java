package io.nova.metadata;

import io.nova.annotation.Column;
import io.nova.annotation.CreatedAt;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;
import io.nova.annotation.UpdatedAt;
import io.nova.convert.AttributeConverter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nova 매핑 어노테이션이 선언된 엔티티 클래스의 리플렉션 메타데이터를 생성하고 캐시한다.
 */
public final class EntityMetadataFactory {
    private static final Set<Class<?>> SUPPORTED_AUDIT_TYPES =
            Set.of(Instant.class, LocalDateTime.class, OffsetDateTime.class);

    private final NamingStrategy namingStrategy;
    private final Map<Class<?>, EntityMetadata<?>> cache = new ConcurrentHashMap<>();
    private final Map<Class<?>, AttributeConverter<?, ?>> converters = new ConcurrentHashMap<>();

    public EntityMetadataFactory(NamingStrategy namingStrategy) {
        this.namingStrategy = namingStrategy;
    }

    /**
     * 프로퍼티 타입용 converter를 등록해 컬럼 값과 프로퍼티 값 사이의 변환에 사용한다.
     */
    public <X, Y> void registerConverter(Class<X> propertyType, AttributeConverter<X, Y> converter) {
        converters.put(propertyType, converter);
    }

    /**
     * 엔티티 타입의 메타데이터를 반환하며, 없으면 처음 접근 시 생성해 캐시한다.
     */
    @SuppressWarnings("unchecked")
    public <T> EntityMetadata<T> getEntityMetadata(Class<T> entityType) {
        EntityMetadata<?> cached = cache.get(entityType);
        if (cached != null) {
            return (EntityMetadata<T>) cached;
        }
        EntityMetadata<T> created = createMetadata(entityType);
        cache.put(entityType, created);
        return created;
    }

    private <T> EntityMetadata<T> createMetadata(Class<T> entityType) {
        Entity entity = entityType.getAnnotation(Entity.class);
        if (entity == null) {
            throw new IllegalArgumentException(entityType.getName() + " is not annotated with @Entity");
        }

        Table table = entityType.getAnnotation(Table.class);
        String entityName = entity.name().isBlank() ? entityType.getSimpleName() : entity.name();
        String tableName = table != null && !table.value().isBlank() ? table.value() : namingStrategy.tableName(entityType);

        List<PersistentProperty> properties = new ArrayList<>();
        PersistentProperty idProperty = null;
        PersistentProperty createdAtProperty = null;
        PersistentProperty updatedAtProperty = null;
        for (Field field : entityType.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            PersistentProperty property = createProperty(field);
            properties.add(property);
            if (property.id()) {
                if (idProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @Id properties");
                }
                idProperty = property;
            }
            if (property.createdAt()) {
                if (property.id()) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + property.propertyName() + " cannot be both @Id and @CreatedAt");
                }
                if (!SUPPORTED_AUDIT_TYPES.contains(property.javaType())) {
                    throw new IllegalArgumentException(
                            "Unsupported audit type " + property.javaType().getName()
                                    + " on " + entityType.getName() + "." + property.propertyName()
                                    + "; supported: Instant, LocalDateTime, OffsetDateTime");
                }
                if (createdAtProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @CreatedAt properties");
                }
                createdAtProperty = property;
            }
            if (property.updatedAt()) {
                if (property.id()) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + property.propertyName() + " cannot be both @Id and @UpdatedAt");
                }
                if (!SUPPORTED_AUDIT_TYPES.contains(property.javaType())) {
                    throw new IllegalArgumentException(
                            "Unsupported audit type " + property.javaType().getName()
                                    + " on " + entityType.getName() + "." + property.propertyName()
                                    + "; supported: Instant, LocalDateTime, OffsetDateTime");
                }
                if (updatedAtProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @UpdatedAt properties");
                }
                updatedAtProperty = property;
            }
        }

        if (idProperty == null) {
            throw new IllegalArgumentException(entityType.getName() + " must declare a field annotated with @Id");
        }

        return new EntityMetadata<>(entityType, entityName, tableName, properties, idProperty);
    }

    private PersistentProperty createProperty(Field field) {
        Column column = field.getAnnotation(Column.class);
        GeneratedValue generatedValue = field.getAnnotation(GeneratedValue.class);
        String columnName = column != null && !column.value().isBlank()
                ? column.value()
                : namingStrategy.columnName(field.getName());

        return new PersistentProperty(
                field,
                field.getName(),
                columnName,
                field.getType(),
                field.isAnnotationPresent(Id.class),
                column == null || column.nullable(),
                generatedValue == null ? GenerationType.NONE : generatedValue.strategy(),
                converters.get(field.getType()),
                field.isAnnotationPresent(CreatedAt.class),
                field.isAnnotationPresent(UpdatedAt.class)
        );
    }
}
