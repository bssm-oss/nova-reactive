package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.NamedQueryDefinition;
import io.nova.metadata.PersistentProperty;
import io.nova.sql.Dialect;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@code @NamedQuery}/{@code @NamedNativeQuery}로 선언된 명명 쿼리 레지스트리. 구성 시 등록된 엔티티(및
 * {@code @MappedSuperclass}) 클래스들을 {@link EntityMetadataFactory}로 스캔해 정의를 이름으로 색인하고,
 * 이름 충돌은 fail-fast로 거부한다(JPA persistence-unit의 전역 유일 명명 쿼리 규약과 동일).
 * <p>
 * 조회 시 JPQL 명명 쿼리는 Wave1의 {@link JpqlExecutor}/{@link JpqlQuery}로, 네이티브 명명 쿼리는
 * {@link ReactiveEntityOperations#queryNative}/{@link ReactiveEntityOperations#executeNative}로 실행하는
 * 핸들을 만든다. 기존 엔진의 hub 파일이나 보호 계약 시그니처를 변경하지 않는 격리된 진입점이다.
 */
public final class NamedQueryRegistry {

    private final Map<String, NamedQueryDefinition> definitions;
    private final JpqlExecutor jpqlExecutor;
    private final ReactiveEntityOperations operations;
    private final Dialect dialect;
    private final EntityMetadataFactory metadataFactory;
    private final Map<Class<?>, Function<RowAccessor, ?>> nativeEntityMappers = new ConcurrentHashMap<>();

    public NamedQueryRegistry(
            ReactiveEntityOperations operations,
            Dialect dialect,
            EntityMetadataFactory metadataFactory,
            Iterable<Class<?>> entityClasses) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        Objects.requireNonNull(entityClasses, "entityClasses must not be null");
        this.jpqlExecutor = new JpqlExecutor(operations, dialect, metadataFactory, entityClasses);
        this.definitions = new LinkedHashMap<>();
        for (Class<?> type : entityClasses) {
            for (NamedQueryDefinition definition : metadataFactory.namedQueryDefinitions(type)) {
                NamedQueryDefinition existing = definitions.putIfAbsent(definition.name(), definition);
                if (existing != null && !existing.equals(definition)) {
                    throw new NamedQueryException("Duplicate named query '" + definition.name()
                            + "' declared on " + type.getName()
                            + "; named queries must have globally unique names");
                }
            }
        }
    }

    public NamedQueryRegistry(
            ReactiveEntityOperations operations,
            Dialect dialect,
            EntityMetadataFactory metadataFactory,
            Class<?>... entityClasses) {
        this(operations, dialect, metadataFactory, List.of(entityClasses));
    }

    /** 이름이 등록돼 있는지 반환한다. */
    public boolean contains(String name) {
        return definitions.containsKey(name);
    }

    /** 등록된 명명 쿼리 정의를 반환한다. 미등록이면 fail-fast. */
    public NamedQueryDefinition definition(String name) {
        NamedQueryDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new NamedQueryException("No named query registered for '" + name
                    + "'. Declare it with @NamedQuery/@NamedNativeQuery and register the entity class.");
        }
        return definition;
    }

    // ----------------------------------------------------------------------------------------
    // JPQL named queries
    // ----------------------------------------------------------------------------------------

    /** 등록된 JPQL 명명 쿼리를 결과 타입 미지정으로 만든다. 네이티브 쿼리 이름이면 fail-fast. */
    public JpqlQuery<Object> createQuery(String name) {
        return createQuery(name, Object.class);
    }

    /** 등록된 JPQL 명명 쿼리를 {@code resultType}으로 만든다. 네이티브 쿼리 이름이면 fail-fast. */
    public <T> JpqlQuery<T> createQuery(String name, Class<T> resultType) {
        NamedQueryDefinition definition = definition(name);
        if (definition.nativeQuery()) {
            throw new NamedQueryException("Named query '" + name
                    + "' is a @NamedNativeQuery; use createNativeQuery(name) to execute it");
        }
        return jpqlExecutor.createQuery(definition.query(), resultType);
    }

    // ----------------------------------------------------------------------------------------
    // Native named queries
    // ----------------------------------------------------------------------------------------

    /**
     * 등록된 네이티브 명명 쿼리 핸들을 만든다. {@code resultClass}가 선언돼 있으면 결과 행을 그 엔티티로
     * 매핑하며, 없으면 SELECT 결과 매핑이 비어 있으므로 {@link #createNativeQuery(String, Function)}로
     * mapper를 제공하거나 {@code executeUpdate()}로만 실행할 수 있다. JPQL 쿼리 이름이면 fail-fast.
     */
    public NamedNativeQuery<?> createNativeQuery(String name) {
        NamedQueryDefinition definition = requireNative(name);
        Function<RowAccessor, ?> mapper =
                definition.resultClass() == null ? null : entityMapper(definition.resultClass());
        return new NamedNativeQuery<>(definition.query(), mapper, operations, dialect);
    }

    /**
     * 등록된 네이티브 명명 쿼리 핸들을 사용자 지정 row {@code mapper}로 만든다. 스칼라/투영 결과를 임의 타입으로
     * 매핑할 때 사용한다. JPQL 쿼리 이름이면 fail-fast.
     */
    public <T> NamedNativeQuery<T> createNativeQuery(String name, Function<RowAccessor, T> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        NamedQueryDefinition definition = requireNative(name);
        return new NamedNativeQuery<>(definition.query(), mapper, operations, dialect);
    }

    private NamedQueryDefinition requireNative(String name) {
        NamedQueryDefinition definition = definition(name);
        if (!definition.nativeQuery()) {
            throw new NamedQueryException("Named query '" + name
                    + "' is a @NamedQuery (JPQL); use createQuery(name[, type]) to execute it");
        }
        return definition;
    }

    @SuppressWarnings("unchecked")
    private <T> Function<RowAccessor, T> entityMapper(Class<T> resultClass) {
        return (Function<RowAccessor, T>) nativeEntityMappers.computeIfAbsent(resultClass, type -> {
            EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(type);
            List<PersistentProperty> columns = metadata.columnMappedProperties();
            Constructor<?> constructor;
            try {
                constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new NamedQueryException("Native query resultClass " + type.getName()
                        + " must declare a no-arg constructor for entity mapping", e);
            }
            return row -> {
                Object instance;
                try {
                    instance = constructor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new NamedQueryException(
                            "Failed to instantiate native query resultClass " + type.getName(), e);
                }
                for (PersistentProperty property : columns) {
                    Object columnValue = row.get(property.columnName(), property.columnType());
                    property.write(instance, property.toPropertyValue(columnValue));
                }
                return instance;
            };
        });
    }
}
