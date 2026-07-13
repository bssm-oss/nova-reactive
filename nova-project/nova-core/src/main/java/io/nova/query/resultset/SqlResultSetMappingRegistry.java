package io.nova.query.resultset;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.NamedQueryDefinition;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.SqlResultSetMappingDefinition;
import io.nova.query.NativeQuery;
import io.nova.query.jpql.NamedNativeQuery;
import io.nova.query.jpql.NamedQueryRegistry;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * JPA {@code @SqlResultSetMapping}(native 결과 매핑)의 리액티브 등가 registry. 구성 시 등록된 엔티티(및
 * {@code @MappedSuperclass})에 선언된 {@code @SqlResultSetMapping}을
 * {@link EntityMetadataFactory#sqlResultSetMappings(Class)}로 스캔해 이름으로 색인하고, 이름 충돌은 JPA
 * persistence-unit 규약대로 fail-fast로 거부한다.
 * <p>
 * 매핑 이름을 지정해 native SQL 결과 row를 다음 규칙으로 변환한다:
 * <ul>
 *   <li>{@code @EntityResult} → 지정 엔티티로 매핑. {@code @FieldResult}로 컬럼 별칭을 엔티티 속성에 연결하며,
 *       미지정 속성은 엔티티 기본 컬럼명을 별칭으로 쓴다. 컬럼 converter/도메인 타입 복원은 엔티티 매핑
 *       규칙을 그대로 재사용한다.</li>
 *   <li>{@code @ConstructorResult} → DTO 생성자를 리플렉션으로 호출. {@code @ColumnResult} 순서대로 인자를
 *       채우고, 인자 개수가 맞는 public 생성자가 없거나 모호하면 fail-fast 한다.</li>
 *   <li>{@code @ColumnResult} → 스칼라 컬럼. {@code type}이 지정되면 그 타입으로 강제 변환한다.</li>
 * </ul>
 * 한 매핑에 여러 종류가 섞여 있으면 row당 {@code Object[]}를 (entities → classes → columns 순서로) 조립하고,
 * 결과 원소가 하나면 그 값 자체를 발행한다.
 * <p>
 * {@code io.nova.query.jpql}의 {@link NamedNativeQuery}/{@link NamedQueryRegistry}를 수정하지 않고 그 public
 * API만 재사용하는 격리된 진입점이다 — {@code :name}/{@code ?n} 파라미터 변환과 바인딩은
 * {@link #createNativeQuery(NamedQueryRegistry, String)}가 {@link NamedNativeQuery}에 위임한다.
 */
public final class SqlResultSetMappingRegistry {

    private final ReactiveEntityOperations operations;
    private final EntityMetadataFactory metadataFactory;
    private final Map<String, SqlResultSetMappingDefinition> definitions;
    private final Map<String, Function<RowAccessor, Object>> compiledMappers = new ConcurrentHashMap<>();

    public SqlResultSetMappingRegistry(
            ReactiveEntityOperations operations,
            EntityMetadataFactory metadataFactory,
            Iterable<Class<?>> entityClasses) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        Objects.requireNonNull(entityClasses, "entityClasses must not be null");
        Map<String, SqlResultSetMappingDefinition> indexed = new LinkedHashMap<>();
        for (Class<?> type : entityClasses) {
            for (SqlResultSetMappingDefinition definition : metadataFactory.sqlResultSetMappings(type)) {
                SqlResultSetMappingDefinition existing = indexed.putIfAbsent(definition.name(), definition);
                if (existing != null && !existing.equals(definition)) {
                    throw new IllegalStateException("Duplicate @SqlResultSetMapping '" + definition.name()
                            + "' declared on " + type.getName()
                            + "; result set mapping names must be globally unique");
                }
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    public SqlResultSetMappingRegistry(
            ReactiveEntityOperations operations,
            EntityMetadataFactory metadataFactory,
            Class<?>... entityClasses) {
        this(operations, metadataFactory, List.of(entityClasses));
    }

    /** 매핑 이름이 등록돼 있는지 반환한다. */
    public boolean contains(String name) {
        return definitions.containsKey(name);
    }

    /** 등록된 매핑 이름 집합(수정 불가 뷰). */
    public Set<String> mappingNames() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    /** 등록된 매핑 정의를 반환한다. 미등록이면 fail-fast. */
    public SqlResultSetMappingDefinition definition(String name) {
        SqlResultSetMappingDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("No @SqlResultSetMapping registered for '" + name
                    + "'. Declare it with @SqlResultSetMapping and register the entity class.");
        }
        return definition;
    }

    // ----------------------------------------------------------------------------------------
    // Ad-hoc native queries
    // ----------------------------------------------------------------------------------------

    /**
     * 임의의 {@link NativeQuery} SELECT 결과를 등록된 매핑으로 변환해 발행한다. binding은 호출자가 SQL의
     * bind marker 순서대로 {@link NativeQuery}에 담아야 한다.
     */
    public Flux<Object> queryNative(NativeQuery query, String mappingName) {
        Objects.requireNonNull(query, "query must not be null");
        Function<RowAccessor, Object> mapper = mapper(mappingName);
        return Flux.defer(() -> operations.queryNative(query, mapper));
    }

    /**
     * binding 없는 native SELECT 문자열을 등록된 매핑으로 변환해 발행한다.
     */
    public Flux<Object> queryNative(String sql, String mappingName) {
        return queryNative(NativeQuery.of(sql), mappingName);
    }

    // ----------------------------------------------------------------------------------------
    // Named native queries (@NamedNativeQuery(resultSetMapping=...))
    // ----------------------------------------------------------------------------------------

    /**
     * {@code @NamedNativeQuery(resultSetMapping=...)}로 선언된 명명 네이티브 쿼리 핸들을 만든다. 명명 쿼리의
     * {@code :name}/{@code ?n} 파라미터 변환과 바인딩은 {@link NamedQueryRegistry#createNativeQuery(String,
     * Function)}가 반환하는 {@link NamedNativeQuery}에 위임하고, 결과 매핑만 이 registry가 제공한다.
     * <p>
     * 명명 쿼리가 {@code resultSetMapping}을 선언하지 않았거나(={@code resultClass} 기반) 참조하는 매핑이
     * 미등록이면 fail-fast 한다.
     */
    public NamedNativeQuery<Object> createNativeQuery(NamedQueryRegistry namedRegistry, String queryName) {
        Objects.requireNonNull(namedRegistry, "namedRegistry must not be null");
        NamedQueryDefinition definition = namedRegistry.definition(queryName);
        String mappingName = definition.resultSetMapping();
        if (mappingName == null) {
            throw new IllegalArgumentException("@NamedNativeQuery '" + queryName
                    + "' does not declare a resultSetMapping; use NamedQueryRegistry.createNativeQuery(name[, mapper])");
        }
        return namedRegistry.createNativeQuery(queryName, mapper(mappingName));
    }

    // ----------------------------------------------------------------------------------------
    // Mapper compilation
    // ----------------------------------------------------------------------------------------

    private Function<RowAccessor, Object> mapper(String mappingName) {
        return compiledMappers.computeIfAbsent(mappingName, name -> compile(definition(name)));
    }

    private Function<RowAccessor, Object> compile(SqlResultSetMappingDefinition definition) {
        List<Function<RowAccessor, Object>> parts = new ArrayList<>(definition.resultElementCount());
        if (definition.entities().size() > 1) {
            throw new IllegalStateException("@SqlResultSetMapping '" + definition.name()
                    + "' declares multiple @EntityResult entries; multi-entity join mapping is not supported");
        }
        for (SqlResultSetMappingDefinition.EntityMapping entity : definition.entities()) {
            parts.add(entityMapper(definition.name(), entity));
        }
        for (SqlResultSetMappingDefinition.ConstructorMapping constructor : definition.classes()) {
            parts.add(constructorMapper(definition.name(), constructor));
        }
        for (SqlResultSetMappingDefinition.ColumnMapping column : definition.columns()) {
            parts.add(columnMapper(column));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        List<Function<RowAccessor, Object>> ordered = List.copyOf(parts);
        return row -> {
            Object[] values = new Object[ordered.size()];
            for (int i = 0; i < ordered.size(); i++) {
                values[i] = ordered.get(i).apply(row);
            }
            return values;
        };
    }

    private Function<RowAccessor, Object> entityMapper(
            String mappingName, SqlResultSetMappingDefinition.EntityMapping entity) {
        Class<?> entityClass = entity.entityClass();
        EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(entityClass);
        Map<String, String> fieldAliases = entity.fieldAliases();
        for (String attribute : fieldAliases.keySet()) {
            if (metadata.findProperty(attribute).isEmpty()) {
                throw new IllegalStateException("@SqlResultSetMapping '" + mappingName + "' @FieldResult refers to '"
                        + attribute + "' which is not a mapped attribute of " + entityClass.getName());
            }
        }
        List<PersistentProperty> columns = metadata.columnMappedProperties();
        Constructor<?> constructor;
        try {
            constructor = entityClass.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("@SqlResultSetMapping '" + mappingName + "' @EntityResult target "
                    + entityClass.getName() + " must declare a no-arg constructor for entity mapping", e);
        }
        return row -> {
            Object instance;
            try {
                instance = constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate @EntityResult target " + entityClass.getName(), e);
            }
            for (PersistentProperty property : columns) {
                String sourceColumn = fieldAliases.getOrDefault(property.propertyName(), property.columnName());
                Object columnValue = row.get(sourceColumn, property.columnType());
                property.write(instance, property.toPropertyValue(columnValue));
            }
            return instance;
        };
    }

    private Function<RowAccessor, Object> constructorMapper(
            String mappingName, SqlResultSetMappingDefinition.ConstructorMapping constructorMapping) {
        Class<?> target = constructorMapping.targetClass();
        List<SqlResultSetMappingDefinition.ColumnMapping> columns = constructorMapping.columns();
        int arity = columns.size();
        Constructor<?> match = null;
        for (Constructor<?> candidate : target.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == arity) {
                if (match != null) {
                    throw new IllegalStateException("@SqlResultSetMapping '" + mappingName
                            + "' @ConstructorResult: " + target.getName() + " has more than one constructor with "
                            + arity + " parameters and cannot be disambiguated");
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw new IllegalStateException("@SqlResultSetMapping '" + mappingName + "' @ConstructorResult: "
                    + target.getName() + " has no constructor accepting " + arity + " argument(s)");
        }
        Constructor<?> constructor = match;
        constructor.setAccessible(true);
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        return row -> {
            Object[] args = new Object[arity];
            for (int i = 0; i < arity; i++) {
                SqlResultSetMappingDefinition.ColumnMapping column = columns.get(i);
                Class<?> readType = column.type() != null ? column.type() : parameterTypes[i];
                Object raw = row.get(column.column(), readType);
                args[i] = coerce(raw, parameterTypes[i], target.getName(), i);
            }
            try {
                return constructor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate @ConstructorResult target " + target.getName(), e);
            }
        };
    }

    private Function<RowAccessor, Object> columnMapper(SqlResultSetMappingDefinition.ColumnMapping column) {
        Class<?> type = column.type() != null ? column.type() : Object.class;
        String alias = column.column();
        return row -> row.get(alias, type);
    }

    /** 스칼라 값을 생성자 파라미터 타입으로 강제 변환한다. 변환 불가면 fail-fast. */
    private static Object coerce(Object value, Class<?> target, String className, int index) {
        if (value == null) {
            if (target.isPrimitive()) {
                throw new IllegalStateException("@ConstructorResult " + className
                        + ": null cannot be assigned to primitive parameter " + index + " of type " + target.getName());
            }
            return null;
        }
        if (target.isInstance(value)) {
            return value;
        }
        if (value instanceof Number n) {
            if (target == int.class || target == Integer.class) {
                return n.intValue();
            }
            if (target == long.class || target == Long.class) {
                return n.longValue();
            }
            if (target == double.class || target == Double.class) {
                return n.doubleValue();
            }
            if (target == float.class || target == Float.class) {
                return n.floatValue();
            }
            if (target == short.class || target == Short.class) {
                return n.shortValue();
            }
            if (target == byte.class || target == Byte.class) {
                return n.byteValue();
            }
            if (target == BigDecimal.class) {
                return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
            }
            if (target == BigInteger.class) {
                return n instanceof BigInteger bi ? bi : BigInteger.valueOf(n.longValue());
            }
        }
        if ((target == boolean.class || target == Boolean.class) && value instanceof Boolean b) {
            return b;
        }
        if (target == String.class) {
            return value.toString();
        }
        throw new IllegalStateException("@ConstructorResult " + className + ": cannot convert value of type "
                + value.getClass().getName() + " to constructor parameter " + index + " of type " + target.getName());
    }
}
