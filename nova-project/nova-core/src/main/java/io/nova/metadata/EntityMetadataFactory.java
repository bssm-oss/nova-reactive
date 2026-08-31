package io.nova.metadata;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AssociationOverride;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.EntityResult;
import jakarta.persistence.FieldResult;
import io.nova.annotation.CreatedAt;
import jakarta.persistence.Basic;
import jakarta.persistence.Convert;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.ExcludeDefaultListeners;
import jakarta.persistence.ExcludeSuperclassListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumeratedValue;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.Lob;
import io.nova.annotation.Json;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKey;
import jakarta.persistence.MapKeyClass;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.MapKeyJoinColumn;
import jakarta.persistence.MapKeyTemporal;
import jakarta.persistence.MapsId;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SecondaryTables;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Transient;
import io.nova.annotation.SoftDelete;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import io.nova.annotation.UpdatedAt;
import jakarta.persistence.Version;
import io.nova.convert.AttributeConverter;
import io.nova.convert.EnumOrdinalConverter;
import io.nova.convert.EnumStringConverter;
import io.nova.convert.EnumeratedValueConverter;
import io.nova.convert.JpaAttributeConverterAdapter;
import io.nova.convert.JsonAttributeConverter;
import io.nova.convert.TemporalAttributeConverter;
import io.nova.convert.UuidStringConverter;
import io.nova.json.JsonCodec;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Nova 매핑 어노테이션이 선언된 엔티티 클래스의 리플렉션 메타데이터를 생성하고 캐시한다.
 */
public final class EntityMetadataFactory {
    private static final Set<Class<?>> SUPPORTED_AUDIT_TYPES =
            Set.of(Instant.class, LocalDateTime.class, OffsetDateTime.class);

    private static final Set<Class<?>> SUPPORTED_SOFT_DELETE_TYPES =
            Set.of(Instant.class, LocalDateTime.class, OffsetDateTime.class);

    // @Version 지원 타입. 정수 타입(Long/Integer/Short)은 +1 증분, 시간 타입(LocalDateTime)은 update 시 현재
    // 시각으로 갱신하는 방식으로 낙관락을 건다. 시간 타입은 드라이버 실증(r2dbc-h2)으로 선별했다:
    //   - LocalDateTime → SQL timestamp 컬럼으로 bind/decode/WHERE-equality 왕복 성공 → 지원.
    //   - Instant → plain timestamp 컬럼 decode 실패("Cannot decode value of type java.time.Instant"),
    //     timestamp with time zone 컬럼에서만 왕복 성공하나 스키마 생성기가 그 컬럼 타입을 emit하지 않아 거부.
    //   - java.sql.Timestamp → 드라이버가 파라미터 인코딩 자체를 거부("Cannot encode parameter ...") → 거부.
    private static final Set<Class<?>> SUPPORTED_VERSION_TYPES =
            Set.of(Long.class, Integer.class, Short.class, LocalDateTime.class);

    private static final Set<Class<?>> SUPPORTED_UUID_ID_TYPES =
            Set.of(UUID.class, String.class);

    /**
     * {@code @GeneratedValue(TABLE)} 식별자가 가질 수 있는 타입. generator 테이블의 카운터는 정수
     * 시퀀스이므로 Long/Integer만 허용한다(primitive long/int는 wrap 후 비교).
     */
    private static final Set<Class<?>> SUPPORTED_TABLE_GENERATOR_ID_TYPES =
            Set.of(Long.class, Integer.class);

    /**
     * {@code @TableGenerator}가 지정하지 않았을 때 사용하는 기본 generator 테이블과 컬럼 이름. JPA 기본값과
     * 동일한 의미를 가지며(논리 sequence 행을 (pkColumn, valueColumn)으로 보관), Nova는 모든 식별자 컬럼을
     * snake_case로 다루는 관례에 맞춰 소문자 식별자를 쓴다.
     */
    private static final String DEFAULT_TABLE_GENERATOR_TABLE = "nova_sequences";
    private static final String DEFAULT_TABLE_GENERATOR_PK_COLUMN = "sequence_name";
    private static final String DEFAULT_TABLE_GENERATOR_VALUE_COLUMN = "next_val";

    /**
     * SEQUENCE generator 이름이 SQL 식별자 형태를 따르도록 강제하는 정규식이다.
     * dialect가 {@code "'" + name + "'"} 같이 직접 concat할 가능성을 차단하기 위해
     * 따옴표, 세미콜론, 공백 등 식별자 외 문자는 모두 거부한다.
     */
    private static final Pattern SEQUENCE_GENERATOR_NAME_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_$.]*$");

    private final NamingStrategy namingStrategy;
    private final JsonCodec jsonCodec;
    private final Map<Class<?>, EntityMetadata<?>> cache = new ConcurrentHashMap<>();
    private final Map<Class<?>, AttributeConverter<?, ?>> converters = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<JpaConverterDescriptor>> jpaConverters = new ConcurrentHashMap<>();
    private volatile boolean metadataBuildStarted;
    /**
     * SINGLE_TABLE 상속 계층 레지스트리. root 클래스 → (discriminator 값 → 구체 서브타입 클래스).
     * 각 구체 멤버의 메타데이터가 빌드될 때 자기 자신을 등록한다 — JPA persistence-unit이 모든 엔티티를
     * 부트스트랩 시 알고 있는 것과 같은 방식으로, root 다형 조회 전에 전 서브타입 메타데이터가 빌드돼 있어야
     * 한다(Spring starter의 entity-packages eager preload가 이를 보장한다).
     */
    private final Map<Class<?>, Map<String, Class<?>>> hierarchies = new ConcurrentHashMap<>();
    /**
     * root 클래스 → 전 서브타입 컬럼을 union한 single-table 병합 메타데이터 캐시. select-list/DDL에서
     * 한 테이블이 모든 서브타입 컬럼을 담도록 만들 때 사용한다.
     */
    private final Map<Class<?>, EntityMetadata<?>> mergedHierarchyCache = new ConcurrentHashMap<>();
    /**
     * 클래스별 {@code @NamedQuery}/{@code @NamedNativeQuery} 정의 캐시. 애너테이션 파싱은 리플렉션이므로
     * 타입당 1회만 수행해 재사용한다. C1d(명명 쿼리) 전용 마커 네임스페이스({@code namedQuery*})로,
     * 다른 메타데이터 스캔 로직과 독립적이다.
     */
    private final Map<Class<?>, List<NamedQueryDefinition>> namedQueryDefinitionsCache = new ConcurrentHashMap<>();
    /**
     * Class별 named-query 정의와 물리적인 annotation 선언 클래스 캐시. 같은 mapped-superclass 선언이
     * 여러 sibling 엔티티를 통해 발견될 때 registry가 단일 선언으로 식별하는 데 사용한다.
     */
    private final Map<Class<?>, List<NamedQueryDeclaration>> namedQueryDeclarationsCache =
            new ConcurrentHashMap<>();
    /**
     * 클래스별 {@code @SqlResultSetMapping} 정의 캐시. 애너테이션 파싱은 리플렉션이므로 타입당 1회만 수행해
     * 재사용한다. Batch E(native 결과 매핑) 전용 마커 네임스페이스({@code resultSetMapping*})로, 다른
     * 메타데이터 스캔 로직 및 {@code namedQuery*} 마커와 독립적이다.
     */
    private final Map<Class<?>, List<SqlResultSetMappingDefinition>> resultSetMappingDefinitionsCache =
            new ConcurrentHashMap<>();

    /**
     * {@link JsonCodec} 없이 factory를 만든다 — {@code @Json} 필드가 없는 엔티티만 다룰 때 사용한다.
     * {@code @Json} 필드가 발견되면 {@link JsonCodec#unconfigured()}가 변환 시점에
     * {@link IllegalStateException}을 던진다.
     */
    public EntityMetadataFactory(NamingStrategy namingStrategy) {
        this(namingStrategy, JsonCodec.unconfigured());
    }

    /**
     * 주어진 {@link JsonCodec}을 {@code @Json} 필드 변환에 사용하는 factory를 만든다.
     */
    public EntityMetadataFactory(NamingStrategy namingStrategy, JsonCodec jsonCodec) {
        this.namingStrategy = namingStrategy;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 프로퍼티 타입용 converter를 등록해 컬럼 값과 프로퍼티 값 사이의 변환에 사용한다.
     */
    public synchronized <X, Y> void registerConverter(Class<X> propertyType, AttributeConverter<X, Y> converter) {
        ensureConverterRegistrationOpen();
        Objects.requireNonNull(propertyType, "propertyType must not be null");
        Objects.requireNonNull(converter, "converter must not be null");
        AttributeConverter<?, ?> previous = converters.putIfAbsent(propertyType, converter);
        if (previous != null && previous != converter) {
            throw new IllegalArgumentException("A converter is already registered for " + propertyType.getName());
        }
    }

    /** Registers a managed Jakarta converter class for explicit and auto-apply conversion. */
    public synchronized void registerJpaConverter(Class<?> converterClass) {
        ensureConverterRegistrationOpen();
        registerJpaConverterInternal(converterClass);
    }

    private void registerJpaConverterInternal(Class<?> converterClass) {
        Objects.requireNonNull(converterClass, "converterClass must not be null");
        jakarta.persistence.Converter annotation = converterClass.getAnnotation(jakarta.persistence.Converter.class);
        if (annotation == null) {
            throw new IllegalArgumentException("JPA converter " + converterClass.getName()
                    + " must be annotated with @Converter");
        }
        Class<?>[] types = resolveJpaConverterTypeArguments(converterClass);
        JpaConverterDescriptor descriptor = new JpaConverterDescriptor(
                converterClass, wrapPrimitiveType(types[0]), wrapPrimitiveType(types[1]), annotation.autoApply());
        jpaConverters.compute(descriptor.attributeType(), (ignored, existing) -> {
            List<JpaConverterDescriptor> result = new ArrayList<>(existing == null ? List.of() : existing);
            if (result.stream().noneMatch(item -> item.converterClass() == converterClass)) {
                result.add(descriptor);
            }
            return List.copyOf(result);
        });
    }

    /** Registers any converter classes present in an already-discovered managed-class set. */
    public synchronized void registerManagedClasses(Iterable<Class<?>> managedClasses) {
        ensureConverterRegistrationOpen();
        Objects.requireNonNull(managedClasses, "managedClasses must not be null");
        for (Class<?> managedClass : managedClasses) {
            if (managedClass.isAnnotationPresent(jakarta.persistence.Converter.class)) {
                registerJpaConverterInternal(managedClass);
            }
        }
    }

    private void ensureConverterRegistrationOpen() {
        if (metadataBuildStarted) {
            throw new IllegalStateException(
                    "JPA converters must be registered before the first entity metadata is requested");
        }
    }

    /**
     * 엔티티 타입의 메타데이터를 반환하며, 없으면 처음 접근 시 생성해 캐시한다.
     */
    @SuppressWarnings("unchecked")
    public <T> EntityMetadata<T> getEntityMetadata(Class<T> entityType) {
        synchronized (this) {
            metadataBuildStarted = true;
        }
        EntityMetadata<?> cached = cache.get(entityType);
        if (cached != null) {
            return (EntityMetadata<T>) cached;
        }
        EntityMetadata<T> created = createMetadata(entityType);
        cache.put(entityType, created);
        return created;
    }

    // ------------------------------------------------------------------------------------------
    // Named queries (C1d) — 마커 네임스페이스: namedQuery*
    // ------------------------------------------------------------------------------------------

    /**
     * 주어진 타입과 그 매핑 조상({@code @MappedSuperclass}/상위 {@code @Entity})에 선언된
     * {@code @NamedQuery}/{@code @NamedQueries}/{@code @NamedNativeQuery}/{@code @NamedNativeQueries}를
     * 파싱해 {@link NamedQueryDefinition} 목록으로 반환한다. 결과는 타입별로 캐시되며 immutable이다.
     * <p>
     * 기존 메타데이터 파싱 흐름과 독립적인 추가 스캔 진입점으로, {@link #createMetadata(Class)}를 변경하지
     * 않는다. 미지원 요소({@code lockMode != NONE}, query hint)는 조용히 무시하지 않고
     * {@link IllegalStateException}으로 fail-fast 한다. {@code @NamedNativeQuery(resultSetMapping=...)}는
     * {@link NamedQueryDefinition#resultSetMapping()}에 담기며, 실제 결과 매핑은
     * {@link #sqlResultSetMappings(Class)}가 노출하는 정의를 사용해 별도 registry가 실행한다.
     *
     * @param type 스캔 대상 엔티티 또는 {@code @MappedSuperclass}
     * @return 선언 순서를 보존한 명명 쿼리 정의 목록(없으면 빈 목록)
     */
    public List<NamedQueryDefinition> namedQueryDefinitions(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        List<NamedQueryDefinition> cached = namedQueryDefinitionsCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<NamedQueryDefinition> collected = namedQueryDeclarations(type).stream()
                .map(NamedQueryDeclaration::definition)
                .toList();
        List<NamedQueryDefinition> immutable = List.copyOf(collected);
        namedQueryDefinitionsCache.put(type, immutable);
        return immutable;
    }

    /**
     * Returns named-query definitions with their physical annotation declaration class.
     * This is used by registries that aggregate multiple entity hierarchies and must distinguish
     * a rediscovery of one inherited declaration from a same-name declaration on another class.
     */
    public List<NamedQueryDeclaration> namedQueryDeclarations(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        List<NamedQueryDeclaration> cached = namedQueryDeclarationsCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<NamedQueryDeclaration> immutable = List.copyOf(collectNamedQueryDeclarations(type));
        namedQueryDeclarationsCache.put(type, immutable);
        return immutable;
    }

    private List<NamedQueryDeclaration> collectNamedQueryDeclarations(Class<?> type) {
        // 타입 자신과 매핑에 기여하는 조상(@MappedSuperclass/상위 @Entity)까지 root-first로 스캔한다 —
        // 상위에 선언된 명명 쿼리가 먼저 등록되도록 mappedFields와 동일한 계층 순서를 따른다.
        List<Class<?>> chain = new ArrayList<>();
        chain.add(type);
        Class<?> ancestor = type.getSuperclass();
        while (ancestor != null && ancestor != Object.class
                && (ancestor.isAnnotationPresent(MappedSuperclass.class)
                || ancestor.isAnnotationPresent(Entity.class))) {
            chain.add(ancestor);
            ancestor = ancestor.getSuperclass();
        }
        List<NamedQueryDeclaration> definitions = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            Class<?> source = chain.get(i);
            NamedQuery[] namedQueries = source.getAnnotationsByType(NamedQuery.class);
            for (int queryIndex = 0; queryIndex < namedQueries.length; queryIndex++) {
                NamedQuery nq = namedQueries[queryIndex];
                if (nq.lockMode() != LockModeType.NONE) {
                    throw new IllegalStateException("@NamedQuery '" + nq.name() + "' on " + source.getName()
                            + " declares lockMode " + nq.lockMode() + " which is not supported");
                }
                if (nq.hints().length > 0) {
                    throw new IllegalStateException("@NamedQuery '" + nq.name() + "' on " + source.getName()
                            + " declares query hints which are not supported");
                }
                definitions.add(new NamedQueryDeclaration(
                        new NamedQueryDefinition(nq.name(), nq.query(), false, null), source, queryIndex));
            }
            NamedNativeQuery[] namedNativeQueries = source.getAnnotationsByType(NamedNativeQuery.class);
            for (int queryIndex = 0; queryIndex < namedNativeQueries.length; queryIndex++) {
                NamedNativeQuery nnq = namedNativeQueries[queryIndex];
                if (nnq.hints().length > 0) {
                    throw new IllegalStateException("@NamedNativeQuery '" + nnq.name() + "' on " + source.getName()
                            + " declares query hints which are not supported");
                }
                Class<?> resultClass = nnq.resultClass() == void.class ? null : nnq.resultClass();
                String resultSetMapping = nnq.resultSetMapping().isBlank() ? null : nnq.resultSetMapping();
                if (resultClass != null && resultSetMapping != null) {
                    throw new IllegalStateException("@NamedNativeQuery '" + nnq.name() + "' on " + source.getName()
                            + " declares both resultClass and resultSetMapping; use exactly one result mapping");
                }
                definitions.add(new NamedQueryDeclaration(new NamedQueryDefinition(
                        nnq.name(), nnq.query(), true, resultClass, resultSetMapping), source, queryIndex));
            }
        }
        return definitions;
    }

    // ------------------------------------------------------------------------------------------
    // SQL result set mappings (Batch E) — 마커 네임스페이스: resultSetMapping*
    // ------------------------------------------------------------------------------------------

    /**
     * 주어진 타입과 그 매핑 조상({@code @MappedSuperclass}/상위 {@code @Entity})에 선언된
     * {@code @SqlResultSetMapping}/{@code @SqlResultSetMappings}를 파싱해
     * {@link SqlResultSetMappingDefinition} 목록으로 반환한다. 결과는 타입별로 캐시되며 immutable이다.
     * <p>
     * {@link #createMetadata(Class)}와 독립적인 추가 스캔 진입점으로 hub 생성자를 건드리지 않는다. 구조적
     * 미지원 요소는 {@link IllegalStateException}으로 fail-fast 한다: {@code @EntityResult}의 non-blank
     * {@code discriminatorColumn}, blank {@code @FieldResult}/{@code @ColumnResult} name·column, 그리고
     * entities/classes/columns가 모두 비어 있는 매핑. {@code @EntityResult}의 {@code lockMode}는 native 결과
     * 매핑이 잠금을 적용하지 않는 detached read-only projection이므로 <b>의도적으로 무시</b>한다(jakarta 3.2
     * 기본값이 {@code OPTIMISTIC}이라 non-NONE fail-fast로 해석하면 모든 {@code @EntityResult}가 거부됨).
     * {@code @FieldResult} 속성명이 실제 엔티티 property인지, {@code @ConstructorResult} 인자 개수가 생성자와
     * 맞는지 같은 <b>의미적</b> 검증은 매핑을 실행하는 registry의 컴파일 단계에서 수행한다.
     *
     * @param type 스캔 대상 엔티티 또는 {@code @MappedSuperclass}
     * @return 선언 순서를 보존한 결과셋 매핑 정의 목록(없으면 빈 목록)
     */
    public List<SqlResultSetMappingDefinition> sqlResultSetMappings(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        List<SqlResultSetMappingDefinition> cached = resultSetMappingDefinitionsCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<SqlResultSetMappingDefinition> immutable = List.copyOf(collectSqlResultSetMappings(type));
        resultSetMappingDefinitionsCache.put(type, immutable);
        return immutable;
    }

    private List<SqlResultSetMappingDefinition> collectSqlResultSetMappings(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        chain.add(type);
        Class<?> ancestor = type.getSuperclass();
        while (ancestor != null && ancestor != Object.class
                && (ancestor.isAnnotationPresent(MappedSuperclass.class)
                || ancestor.isAnnotationPresent(Entity.class))) {
            chain.add(ancestor);
            ancestor = ancestor.getSuperclass();
        }
        List<SqlResultSetMappingDefinition> definitions = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            Class<?> source = chain.get(i);
            for (SqlResultSetMapping mapping : source.getAnnotationsByType(SqlResultSetMapping.class)) {
                definitions.add(parseSqlResultSetMapping(mapping, source));
            }
        }
        return definitions;
    }

    private static SqlResultSetMappingDefinition parseSqlResultSetMapping(SqlResultSetMapping mapping, Class<?> source) {
        String name = mapping.name();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("@SqlResultSetMapping on " + source.getName() + " must declare a name");
        }
        List<SqlResultSetMappingDefinition.EntityMapping> entities = new ArrayList<>();
        for (EntityResult entityResult : mapping.entities()) {
            // @EntityResult.lockMode()의 애너테이션 default는 OPTIMISTIC이지만 native 결과 매핑은 잠금을
            // 적용하지 않으므로(읽기 전용 매핑) lockMode는 의도적으로 무시한다.
            if (!entityResult.discriminatorColumn().isBlank()) {
                throw new IllegalStateException("@SqlResultSetMapping '" + name + "' on " + source.getName()
                        + " declares @EntityResult discriminatorColumn which is not supported");
            }
            Map<String, String> fieldAliases = new LinkedHashMap<>();
            for (FieldResult fieldResult : entityResult.fields()) {
                if (fieldResult.name().isBlank() || fieldResult.column().isBlank()) {
                    throw new IllegalStateException("@SqlResultSetMapping '" + name + "' on " + source.getName()
                            + " declares a @FieldResult with a blank name or column");
                }
                String previous = fieldAliases.put(fieldResult.name(), fieldResult.column());
                if (previous != null) {
                    throw new IllegalStateException("@SqlResultSetMapping '" + name + "' on " + source.getName()
                            + " declares duplicate @FieldResult for attribute '" + fieldResult.name() + "'");
                }
            }
            entities.add(new SqlResultSetMappingDefinition.EntityMapping(entityResult.entityClass(), fieldAliases));
        }
        List<SqlResultSetMappingDefinition.ConstructorMapping> classes = new ArrayList<>();
        for (ConstructorResult constructorResult : mapping.classes()) {
            List<SqlResultSetMappingDefinition.ColumnMapping> columns =
                    parseColumnResults(constructorResult.columns(), name, source);
            if (columns.isEmpty()) {
                throw new IllegalStateException("@SqlResultSetMapping '" + name + "' on " + source.getName()
                        + " declares a @ConstructorResult with no @ColumnResult columns");
            }
            classes.add(new SqlResultSetMappingDefinition.ConstructorMapping(
                    constructorResult.targetClass(), columns));
        }
        List<SqlResultSetMappingDefinition.ColumnMapping> columns =
                parseColumnResults(mapping.columns(), name, source);
        return new SqlResultSetMappingDefinition(name, entities, classes, columns);
    }

    private static List<SqlResultSetMappingDefinition.ColumnMapping> parseColumnResults(
            ColumnResult[] columnResults, String mappingName, Class<?> source) {
        List<SqlResultSetMappingDefinition.ColumnMapping> columns = new ArrayList<>();
        for (ColumnResult columnResult : columnResults) {
            if (columnResult.name().isBlank()) {
                throw new IllegalStateException("@SqlResultSetMapping '" + mappingName + "' on " + source.getName()
                        + " declares a @ColumnResult with a blank name");
            }
            Class<?> type = columnResult.type() == void.class ? null : columnResult.type();
            columns.add(new SqlResultSetMappingDefinition.ColumnMapping(columnResult.name(), type));
        }
        return columns;
    }

    /**
     * 엔티티 클래스(및 상위 클래스)에 선언된 {@code @NamedEntityGraph}/{@code @NamedEntityGraphs}를 읽어
     * {@code entityGraph*} 마커 네임스페이스의 선언 모델로 반환한다. C2 라운드의 {@code namedQuery*} 마커와
     * 충돌하지 않도록 hub 생성자를 건드리지 않고 클래스 애너테이션에서 on-demand로 파싱한다 —
     * {@link io.nova.graph.NamedEntityGraphReader}에 얇게 위임한다.
     */
    public java.util.List<io.nova.graph.NamedEntityGraphDefinition> entityGraphDefinitions(Class<?> entityType) {
        return io.nova.graph.NamedEntityGraphReader.read(entityType);
    }

    /**
     * SINGLE_TABLE 상속 루트의 모든 서브타입 컬럼을 union한 병합 메타데이터를 반환한다. 단일 테이블이
     * 모든 서브타입 컬럼을 담도록 select-list와 CREATE TABLE을 만들 때 사용한다. 서브타입 전용 컬럼은
     * 다른 서브타입 row에서 비어 있어야 하므로 nullable로 낮춘다. 루트가 아니거나 상속이 아니면 입력
     * 메타데이터를 그대로 돌려준다.
     *
     * <p>레지스트리에 등록된 서브타입만 union에 포함되므로, 다형 조회 전에 전 서브타입 메타데이터가
     * 빌드돼 있어야 한다(Spring starter의 entity-packages eager preload가 보장).
     */
    public EntityMetadata<?> mergedHierarchyMetadata(Class<?> rootClass) {
        EntityMetadata<?> cached = mergedHierarchyCache.get(rootClass);
        if (cached != null) {
            return cached;
        }
        EntityMetadata<?> merged = buildMergedHierarchyMetadata(getEntityMetadata(rootClass));
        mergedHierarchyCache.put(rootClass, merged);
        return merged;
    }

    private <T> EntityMetadata<T> buildMergedHierarchyMetadata(EntityMetadata<T> rootMeta) {
        if (!rootMeta.isInheritanceRoot()) {
            return rootMeta;
        }
        // 단일 테이블 union은 SINGLE_TABLE에서만 의미가 있다. JOINED/TABLE_PER_CLASS는 멀티테이블이므로
        // 루트 메타데이터를 그대로 돌려주고, 실제 다형 쿼리/DDL은 inheritanceLayout 경로가 처리한다.
        if (!rootMeta.inheritance().singleTable()) {
            return rootMeta;
        }
        LinkedHashMap<String, PersistentProperty> union = new LinkedHashMap<>();
        for (PersistentProperty property : rootMeta.columnMappedProperties()) {
            union.put(property.columnName(), property);
        }
        Map<String, Class<?>> members = hierarchies.getOrDefault(rootMeta.entityType(), Map.of());
        for (Class<?> member : members.values()) {
            if (member == rootMeta.entityType()) {
                continue;
            }
            for (PersistentProperty property : getEntityMetadata(member).columnMappedProperties()) {
                // 서브타입 전용 컬럼만 추가하고 nullable로 낮춘다. 루트가 이미 가진 컬럼은 건너뛴다.
                union.putIfAbsent(property.columnName(), property.withNullable(true));
            }
        }
        return new EntityMetadata<>(
                rootMeta.entityType(),
                rootMeta.entityName(),
                rootMeta.tableName(),
                rootMeta.schema(),
                new ArrayList<>(union.values()),
                rootMeta.idProperty(),
                rootMeta.prePersistCallbacks(),
                rootMeta.postPersistCallbacks(),
                rootMeta.preUpdateCallbacks(),
                rootMeta.postUpdateCallbacks(),
                rootMeta.postLoadCallbacks(),
                rootMeta.preRemoveCallbacks(),
                rootMeta.postRemoveCallbacks(),
                rootMeta.indexes(),
                rootMeta.uniqueConstraints(),
                rootMeta.inheritance(),
                rootMeta.listenerCallbacks(),
                rootMeta.excludeDefaultListeners(),
                rootMeta.secondaryTables(),
                rootMeta.tableDdlDefinition()
        );
    }

    /**
     * 루트 메타데이터와 row에서 읽은 discriminator 값으로 구체 서브타입 메타데이터를 해석한다. 매칭되는
     * 서브타입이 없으면 명확한 에러를 던진다 — 보통 해당 서브타입 메타데이터가 아직 빌드되지 않은 경우다.
     */
    public EntityMetadata<?> resolveSubtype(EntityMetadata<?> rootMetadata, Object rawDiscriminatorValue) {
        InheritanceInfo info = rootMetadata.inheritance();
        Map<String, Class<?>> members = hierarchies.getOrDefault(info.root(), Map.of());
        String key = rawDiscriminatorValue == null ? null : rawDiscriminatorValue.toString().trim();
        Class<?> concrete = key == null ? null : members.get(key);
        if (concrete != null) {
            return getEntityMetadata(concrete);
        }
        if (info.discriminatorValue().equals(key)) {
            return rootMetadata;
        }
        throw new IllegalStateException(
                "No @DiscriminatorValue '" + key + "' is registered for hierarchy "
                        + info.root().getName() + "; known values: " + members.keySet()
                        + ". Ensure every subtype's metadata is built before polymorphic queries"
                        + " (Spring resolves this automatically via entity-packages scanning).");
    }

    /**
     * 루트 클래스 → JOINED/TABLE_PER_CLASS 물리 테이블 배치 캐시. 새 서브타입이 등록되면 무효화된다.
     */
    private final Map<Class<?>, InheritanceLayout> inheritanceLayoutCache = new ConcurrentHashMap<>();

    /**
     * JOINED 또는 TABLE_PER_CLASS 계층의 물리 테이블 배치를 빌드/캐시해 반환한다. SINGLE_TABLE이나 비-상속
     * 루트에 대해서는 {@link IllegalArgumentException}을 던진다(해당 전략은 merged-metadata 경로를 쓴다).
     *
     * <p>등록된 구체 서브타입만 포함되므로, 다형 조회 전에 전 서브타입 메타데이터가 빌드돼 있어야 한다
     * (Spring starter의 entity-packages eager preload가 보장).
     */
    public InheritanceLayout inheritanceLayout(Class<?> rootClass) {
        InheritanceLayout cached = inheritanceLayoutCache.get(rootClass);
        if (cached != null) {
            return cached;
        }
        InheritanceLayout built = buildInheritanceLayout(rootClass);
        inheritanceLayoutCache.put(rootClass, built);
        return built;
    }

    private InheritanceLayout buildInheritanceLayout(Class<?> rootClass) {
        EntityMetadata<?> rootMeta = getEntityMetadata(rootClass);
        InheritanceInfo info = rootMeta.inheritance();
        if (!info.present() || !info.isRoot() || info.singleTable()) {
            throw new IllegalArgumentException(
                    rootClass.getName() + " is not a JOINED/TABLE_PER_CLASS inheritance root");
        }
        List<PersistentProperty> rootTableColumns = new ArrayList<>();
        if (info.joined()) {
            for (PersistentProperty property : rootMeta.columnMappedProperties()) {
                if (isRootTableColumn(property, rootClass)) {
                    rootTableColumns.add(property);
                }
            }
        }
        // 구체 서브타입을 모은다. hierarchies는 ConcurrentHashMap이라 iteration 순서가 비결정적이므로,
        // JOIN/UNION SQL 형태를 안정시키기 위해 discriminator 값 기준으로 정렬한다.
        Map<String, Class<?>> members = hierarchies.getOrDefault(rootClass, Map.of());
        List<String> orderedKeys = new ArrayList<>(members.keySet());
        Collections.sort(orderedKeys);
        List<InheritanceLayout.ConcreteSubtype> subtypes = new ArrayList<>();
        for (String key : orderedKeys) {
            Class<?> subClass = members.get(key);
            // 다단계 상속(루트와 구체 서브타입 사이에 중간 @Entity가 있는 경우)은 현재 단일 레벨로 flatten되어
            // JPA 의미(중간 타입의 자체 테이블/조인)를 위반한다 → fail-fast로 거부한다.
            for (Class<?> c = subClass.getSuperclass(); c != null && c != rootClass; c = c.getSuperclass()) {
                if (c.isAnnotationPresent(Entity.class)) {
                    throw new IllegalArgumentException(
                            rootClass.getName() + " inheritance hierarchy has an intermediate @Entity '" + c.getName()
                                    + "' between the root and '" + subClass.getName()
                                    + "'; Nova supports only a single level of JOINED/TABLE_PER_CLASS subtypes"
                                    + " (multi-level @Entity hierarchies are not supported)");
                }
            }
            EntityMetadata<?> subMeta = getEntityMetadata(subClass);
            List<PersistentProperty> ownColumns = info.joined()
                    ? joinedOwnTableColumns(subMeta, rootClass)
                    : subMeta.columnMappedProperties();
            subtypes.add(new InheritanceLayout.ConcreteSubtype(subMeta, ownColumns, key));
        }
        return new InheritanceLayout(info, rootMeta, rootTableColumns, subtypes);
    }

    /**
     * JOINED에서 한 서브타입의 자기 테이블 컬럼을 만든다 — 루트 PK 컬럼(FK로 공유, not-null PK)을 맨 앞에 두고,
     * 그 뒤에 이 서브타입(루트보다 아래 클래스)이 선언한 컬럼들을 잇는다. 루트 테이블에 이미 있는 공통 컬럼은
     * 서브타입 테이블에 중복하지 않는다.
     */
    private List<PersistentProperty> joinedOwnTableColumns(EntityMetadata<?> subMeta, Class<?> rootClass) {
        List<PersistentProperty> own = new ArrayList<>();
        own.add(subMeta.idProperty());
        for (PersistentProperty property : subMeta.columnMappedProperties()) {
            if (property.id()) {
                continue;
            }
            if (!isRootTableColumn(property, rootClass)) {
                own.add(property);
            }
        }
        return own;
    }

    /**
     * 한 컬럼이 JOINED 루트 테이블에 속하는지 판정한다 — 그 필드를 선언한 클래스가 루트이거나 루트의 상위
     * (@MappedSuperclass 조상)이면 루트 테이블 컬럼이다. 루트보다 아래(서브타입)에서 선언됐으면 서브타입 테이블 컬럼이다.
     * id는 루트가 선언하므로 루트 테이블 컬럼으로 분류되며, 서브타입 테이블에는 FK PK로 별도 복제된다.
     */
    private static boolean isRootTableColumn(PersistentProperty property, Class<?> rootClass) {
        Class<?> declaringClass = property.embedded()
                ? property.embeddedHostAccessPath().get(0).declaringType()
                : property.access().declaringType();
        // declaringClass가 root이거나 root의 상위면 루트 테이블. (root가 declaringClass의 하위이면)
        return declaringClass.isAssignableFrom(rootClass);
    }

    private <T> EntityMetadata<T> createMetadata(Class<T> entityType) {
        Entity entity = entityType.getAnnotation(Entity.class);
        if (entity == null) {
            throw new IllegalArgumentException(entityType.getName() + " is not annotated with @Entity");
        }

        String entityName = entity.name().isBlank() ? entityType.getSimpleName() : entity.name();
        // 상속 전략별 테이블 소스:
        //  - SINGLE_TABLE: 모든 멤버가 루트 테이블 하나를 공유 → tableSource = root.
        //  - JOINED / TABLE_PER_CLASS: 각 엔티티가 자기 테이블을 가진다 → tableSource = entityType.
        //  - 비-상속: tableSource = entityType.
        InheritanceInfo inheritance = resolveInheritance(entityType, entityName);
        Class<?> tableSource = inheritance.singleTable() && inheritance.present()
                ? inheritance.root()
                : entityType;
        Table table = tableSource.getAnnotation(Table.class);
        String tableName = table != null && !table.name().isBlank() ? table.name() : namingStrategy.tableName(tableSource);

        List<PersistentProperty> properties = new ArrayList<>();
        PersistentProperty idProperty = null;
        // @IdClass(SomeId.class): 엔티티가 개별 @Id 필드 여러 개를 선언하고 별도 미러 클래스로 복합키를
        // 표현하는 방식. 이 경우에만 multiple @Id를 허용한다.
        boolean hasIdClass = entityType.isAnnotationPresent(jakarta.persistence.IdClass.class);
        PersistentProperty createdAtProperty = null;
        PersistentProperty updatedAtProperty = null;
        PersistentProperty softDeleteProperty = null;
        PersistentProperty versionProperty = null;
        PersistentTypeAccess accessPlan = new PersistentAccessResolver().resolve(entityType);

        for (PersistentAttributeAccess attribute : accessPlan.attributes()) {
            Field field = attribute.field();
            if (field != null && isNotPersistable(field)) {
                continue;
            }
            rejectInactiveRelationAnnotations(entityType, attribute);
            rejectIncompatibleRelationAnnotations(entityType, attribute);
            rejectMisplacedForeignKey(entityType, attribute);
            if (attribute.isAnnotationPresent(OneToMany.class)) {
                // OneToMany는 parent 테이블 컬럼이 없는 marker-only property — column uniqueness 검증에서 제외된다.
                properties.add(createOneToManyProperty(entityType, attribute));
                continue;
            }
            if (attribute.isAnnotationPresent(ManyToOne.class)) {
                properties.add(createManyToOneProperty(entityType, attribute));
                continue;
            }
            if (attribute.isAnnotationPresent(OneToOne.class)) {
                // owning(@JoinColumn FK)은 컬럼이 있고, inverse(mappedBy)는 컬럼이 없는 마커다.
                properties.add(createOneToOneProperty(entityType, attribute));
                continue;
            }
            if (attribute.isAnnotationPresent(ManyToMany.class)) {
                // owning(@JoinTable) / inverse(mappedBy) 모두 컬럼이 없는 marker. link table은 별도 관리된다.
                properties.add(createManyToManyProperty(entityType, tableName, attribute));
                continue;
            }
            if (attribute.isAnnotationPresent(ElementCollection.class)) {
                // 값 컬렉션 — collection table에 별도 저장되는 컬럼 없는 marker.
                properties.add(createElementCollectionProperty(entityType, tableName, attribute));
                continue;
            }
            if (attribute.isAnnotationPresent(EmbeddedId.class)) {
                if (attribute.isAnnotationPresent(Id.class)) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + attribute.name()
                                    + " cannot declare both @Id and @EmbeddedId");
                }
                if (hasIdClass) {
                    throw new IllegalArgumentException(
                            entityType.getName() + " cannot combine @IdClass with @EmbeddedId");
                }
                if (idProperty != null) {
                    throw new IllegalArgumentException(
                            entityType.getName() + " declares multiple @Id/@EmbeddedId properties");
                }
                // @EmbeddedId는 @Embeddable holder를 컬럼들로 펼친 뒤 각 컴포넌트를 복합키 id로 표시한다.
                List<PersistentProperty> components = createEmbeddedIdProperties(entityType, attribute);
                for (PersistentProperty idComponent : components) {
                    properties.add(idComponent);
                    if (idProperty == null) {
                        idProperty = idComponent;
                    }
                }
                continue;
            }
            if (isEmbeddedAttribute(attribute)) {
                List<PersistentProperty> expanded = createEmbeddedProperties(
                        entityType, attribute, List.of(), "", new LinkedHashSet<>(), Map.of(), Map.of(), false);
                properties.addAll(expanded);
                continue;
            }
            PersistentProperty property = createProperty(entityType, attribute, List.of(), "");
            properties.add(property);
            if (property.id()) {
                if (idProperty != null && !hasIdClass) {
                    throw new IllegalArgumentException(
                            entityType.getName() + " declares multiple @Id properties;"
                                    + " use @IdClass or @EmbeddedId for composite keys");
                }
                if (idProperty == null) {
                    idProperty = property;
                }
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
            if (property.softDelete()) {
                if (softDeleteProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @SoftDelete properties");
                }
                softDeleteProperty = property;
            }
            if (property.version()) {
                if (versionProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @Version properties");
                }
                versionProperty = property;
            }
        }

        // @MappedSuperclass에서 상속한 to-one 관계의 join 컬럼을 서브클래스 @AssociationOverride로 재지정한다.
        // 관계 property는 이미 상속 필드 스캔으로 조립됐으므로, createManyToOneProperty 본문을 건드리지 않고
        // 조립된 property의 FK 컬럼명만 post-processing으로 갈아끼운다.
        applyAssociationOverrides(entityType, properties);

        if (idProperty == null) {
            throw new IllegalArgumentException(entityType.getName()
                    + " must declare an access member annotated with @Id or @EmbeddedId");
        }
        if (hasIdClass) {
            validateIdClass(entityType, properties);
        }

        List<Method> prePersistCallbacks = new ArrayList<>();
        List<Method> postPersistCallbacks = new ArrayList<>();
        List<Method> preUpdateCallbacks = new ArrayList<>();
        List<Method> postUpdateCallbacks = new ArrayList<>();
        List<Method> postLoadCallbacks = new ArrayList<>();
        List<Method> preRemoveCallbacks = new ArrayList<>();
        List<Method> postRemoveCallbacks = new ArrayList<>();
        // 콜백은 @MappedSuperclass와 SINGLE_TABLE 상속 상위 @Entity까지 포함해 수집한다 — 루트/베이스에
        // 선언된 audit 콜백이 서브타입에서도 발화하도록. Java override인 경우에만 superclass callback을
        // 가장 하위 정의로 대체한다; private/non-inherited same-signature callback은 각각 수집한다.
        for (Method method : mappedMethods(entityType)) {
            if (method.isSynthetic()) {
                continue;
            }
            collectCallback(entityType, method, PrePersist.class, prePersistCallbacks);
            collectCallback(entityType, method, PostPersist.class, postPersistCallbacks);
            collectCallback(entityType, method, PreUpdate.class, preUpdateCallbacks);
            collectCallback(entityType, method, PostUpdate.class, postUpdateCallbacks);
            collectCallback(entityType, method, PostLoad.class, postLoadCallbacks);
            collectCallback(entityType, method, PreRemove.class, preRemoveCallbacks);
            collectCallback(entityType, method, PostRemove.class, postRemoveCallbacks);
        }

        Set<String> columnNames = new LinkedHashSet<>();
        for (PersistentProperty property : properties) {
            if (property.oneToMany() || property.inverseToOne()
                    || property.manyToMany() || property.elementCollection()) {
                // 컬럼 없는 marker-only(@OneToMany / inverse @OneToOne / @ManyToMany / @ElementCollection)는
                // 빈 columnName을 가지므로 uniqueness 검증에서 제외한다(빈 문자열 false collision 방지).
                continue;
            }
            if (property.isCompositeToOne()) {
                // 복합키 타겟 to-one은 N개 FK 컬럼을 emit하므로 각 컬럼을 uniqueness 게이트에 넣는다
                // (첫 컬럼만 검사하면 나머지 FK 컬럼의 silent 충돌을 놓친다).
                for (ToOneForeignKeyColumn fkColumn : property.toOneForeignKey().columns()) {
                    if (!columnNames.add(fkColumn.columnName())) {
                        throw new IllegalArgumentException(
                                entityType.getName() + " declares duplicate column '" + fkColumn.columnName()
                                        + "'; check @JoinColumns and composite foreign-key column names");
                    }
                }
                continue;
            }
            if (!columnNames.add(property.columnName())) {
                throw new IllegalArgumentException(
                        entityType.getName() + " declares duplicate column '" + property.columnName()
                                + "'; check @Column overrides and @Embedded host field names");
            }
        }
        List<IndexDefinition> indexes = extractIndexes(
                entityType, tableName, columnNames,
                table == null ? new Index[0] : table.indexes());
        List<UniqueConstraintDefinition> uniqueConstraints = extractUniqueConstraints(
                entityType, tableName, columnNames,
                table == null ? new UniqueConstraint[0] : table.uniqueConstraints());
        List<SecondaryTableInfo> secondaryTables = resolveSecondaryTables(entityType, idProperty, properties, inheritance);

        EntityMetadata<T> metadata = new EntityMetadata<>(
                entityType,
                entityName,
                tableName,
                table != null ? table.schema() : "",
                properties,
                idProperty,
                prePersistCallbacks,
                postPersistCallbacks,
                preUpdateCallbacks,
                postUpdateCallbacks,
                postLoadCallbacks,
                preRemoveCallbacks,
                postRemoveCallbacks,
                indexes,
                uniqueConstraints,
                inheritance,
                collectEntityListeners(entityType),
                hasExcludeDefaultListeners(entityType),
                secondaryTables,
                tableDdlDefinition(table, entityType)
        );
        registerHierarchyMember(metadata);
        return metadata;
    }

    /**
     * The resolver intentionally omits unannotated members without a selected getter.  An entity
     * using PROPERTY access, however, cannot silently drop an ordinary persistent field: it would
     * produce a partial entity mapping.  Records are exempt because their component accessors are
     * not JavaBean getters and are handled by the descriptor resolver.
     */
    private static void validatePropertyAccessCompleteness(Class<?> entityType) {
        for (Class<?> type = entityType; type != null && type != Object.class; type = type.getSuperclass()) {
            if (type != entityType && !type.isAnnotationPresent(MappedSuperclass.class)
                    && !type.isAnnotationPresent(Entity.class)) {
                continue;
            }
            if (type.isRecord()) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                if (isNotPersistable(field) || !resolvePropertyAccess(field)) {
                    continue;
                }
                if (findPropertyGetter(field) == null) {
                    String capitalized = capitalize(field.getName());
                    String suffix = (field.getType() == boolean.class || field.getType() == Boolean.class)
                            ? " or is" + capitalized : "";
                    throw new IllegalArgumentException(type.getName() + "." + field.getName()
                            + " uses @Access(PROPERTY) but has no JavaBean getter (expected get"
                            + capitalized + suffix + ")");
                }
            }
        }
    }

    /**
     * {@code @SecondaryTable}/{@code @SecondaryTables}로 선언된 보조 테이블들을 해석하고 검증한다.
     * <p>
     * fail-fast 규칙(조용한 무시 금지):
     * <ul>
     *   <li>{@code @Column(table="...")}가 선언되지 않은 보조 테이블을 가리키면 거부.</li>
     *   <li>복합키({@code @EmbeddedId}/{@code @IdClass}) 엔티티에 보조 테이블을 달면 거부(v1 미지원).</li>
     *   <li>{@code @Id}/{@code @GeneratedValue} 컬럼을 보조 테이블로 라우팅하면 거부.</li>
     *   <li>보조 테이블당 PK 조인 컬럼이 둘 이상이면 거부(단일키만 지원).</li>
     * </ul>
     * PK 조인 컬럼/참조 컬럼이 미지정이면 primary {@code @Id} 컬럼 이름을 기본값으로 쓴다(JPA 기본).
     */
    private List<SecondaryTableInfo> resolveSecondaryTables(
            Class<?> entityType, PersistentProperty idProperty, List<PersistentProperty> properties,
            InheritanceInfo inheritance) {
        List<SecondaryTable> declarations = new ArrayList<>();
        SecondaryTables multi = entityType.getAnnotation(SecondaryTables.class);
        if (multi != null) {
            declarations.addAll(Arrays.asList(multi.value()));
        }
        SecondaryTable single = entityType.getAnnotation(SecondaryTable.class);
        if (single != null) {
            declarations.add(single);
        }
        boolean compositeId = idProperty.embedded()
                || entityType.isAnnotationPresent(jakarta.persistence.IdClass.class)
                || properties.stream().filter(PersistentProperty::id).count() > 1;
        if (declarations.isEmpty()) {
            // 보조 테이블 선언이 없는데 @Column(table=...)로 라우팅된 컬럼이 있으면 조용히 primary로 흡수하지
            // 않고 거부한다(거짓 매핑 방지).
            for (PersistentProperty property : properties) {
                if (property.secondary()) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + property.propertyName()
                                    + " @Column(table=\"" + property.secondaryTableName() + "\") references a"
                                    + " secondary table that is not declared via @SecondaryTable");
                }
            }
            return List.of();
        }
        if (compositeId) {
            throw new IllegalArgumentException(
                    entityType.getName() + " @SecondaryTable is not supported with a composite id"
                            + " (@EmbeddedId/@IdClass) in this version");
        }
        // @SecondaryTable과 @Inheritance(JOINED/SINGLE_TABLE/TABLE_PER_CLASS) 조합은 insert/read 경로가
        // 서로 다른 테이블 모델(다형 JOIN/discriminator vs 보조 테이블 LEFT JOIN)을 가정해 깨지므로 v1에서
        // 거부한다(조용한 거짓 매핑 방지).
        if (inheritance.present()) {
            throw new IllegalArgumentException(
                    entityType.getName() + " @SecondaryTable is not supported on an @Inheritance hierarchy"
                            + " in this version");
        }
        String primaryPkColumn = idProperty.columnName();
        LinkedHashMap<String, SecondaryTableInfo> byName = new LinkedHashMap<>();
        for (SecondaryTable declaration : declarations) {
            String name = declaration.name();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @SecondaryTable requires a non-blank name");
            }
            if (byName.containsKey(name)) {
                throw new IllegalArgumentException(
                        entityType.getName() + " declares duplicate @SecondaryTable name '" + name + "'");
            }
            PrimaryKeyJoinColumn[] pkJoinColumns = declaration.pkJoinColumns();
            String pkJoinColumn;
            String referencedColumn;
            if (pkJoinColumns.length == 0) {
                pkJoinColumn = primaryPkColumn;
                referencedColumn = primaryPkColumn;
            } else if (pkJoinColumns.length == 1) {
                pkJoinColumn = pkJoinColumns[0].name().isBlank() ? primaryPkColumn : pkJoinColumns[0].name();
                referencedColumn = pkJoinColumns[0].referencedColumnName().isBlank()
                        ? primaryPkColumn : pkJoinColumns[0].referencedColumnName();
            } else {
                throw new IllegalArgumentException(
                        entityType.getName() + " @SecondaryTable(\"" + name + "\") declares multiple"
                                + " pkJoinColumns (composite keys are not supported)");
            }
            ForeignKey tableForeignKey = declaration.foreignKey();
            ForeignKey primaryKeyJoinForeignKey =
                    pkJoinColumns.length == 0 ? null : pkJoinColumns[0].foreignKey();
            boolean tableForeignKeyExplicit = isExplicitForeignKey(tableForeignKey);
            boolean primaryKeyJoinForeignKeyExplicit = isExplicitForeignKey(primaryKeyJoinForeignKey);
            if (tableForeignKeyExplicit && primaryKeyJoinForeignKeyExplicit) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @SecondaryTable(\"" + name + "\") declares foreignKey"
                                + " both on @SecondaryTable and @PrimaryKeyJoinColumn");
            }
            ForeignKey foreignKey = primaryKeyJoinForeignKeyExplicit
                    ? primaryKeyJoinForeignKey
                    : tableForeignKeyExplicit ? tableForeignKey : null;
            String schema = declaration.schema() == null ? "" : declaration.schema();
            byName.put(name, new SecondaryTableInfo(
                    name,
                    schema,
                    pkJoinColumn,
                    referencedColumn,
                    foreignKey == null ? ConstraintMode.PROVIDER_DEFAULT : foreignKey.value(),
                    foreignKey == null ? "" : foreignKey.name()));
        }
        // 보조 테이블로 라우팅된 컬럼들을 검증: 선언된 테이블만 가리켜야 하고, id/생성키 컬럼은 보조 테이블에
        // 둘 수 없다(PK는 primary 테이블이 소유하고 보조 테이블은 그 PK를 FK로 공유한다).
        for (PersistentProperty property : properties) {
            if (!property.secondary()) {
                continue;
            }
            if (!byName.containsKey(property.secondaryTableName())) {
                throw new IllegalArgumentException(
                        entityType.getName() + "." + property.propertyName()
                                + " @Column(table=\"" + property.secondaryTableName() + "\") references an"
                                + " undeclared @SecondaryTable; declared: " + byName.keySet());
            }
            if (property.id()) {
                throw new IllegalArgumentException(
                        entityType.getName() + "." + property.propertyName()
                                + " an @Id column cannot be routed to a @SecondaryTable");
            }
            if (property.generated()) {
                throw new IllegalArgumentException(
                        entityType.getName() + "." + property.propertyName()
                                + " a @GeneratedValue column cannot be routed to a @SecondaryTable");
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 상속 계층에서 이 엔티티의 위치와 전략을 해석한다. 상속에 참여하지 않으면 {@link InheritanceInfo#NONE}.
     * 계층은 (a) 이 엔티티가 직접 {@link Inheritance}를 선언했거나 (b) 상위에 {@link Entity} 조상이 존재할 때
     * 성립한다(JPA 기본 전략이 SINGLE_TABLE). SINGLE_TABLE/JOINED/TABLE_PER_CLASS 세 전략을 모두 지원한다.
     */
    private InheritanceInfo resolveInheritance(Class<?> entityType, String entityName) {
        Class<?> root = entityType;
        Class<?> ancestor = entityType.getSuperclass();
        while (ancestor != null && ancestor != Object.class) {
            if (ancestor.isAnnotationPresent(Entity.class)) {
                root = ancestor;
            }
            ancestor = ancestor.getSuperclass();
        }
        boolean inHierarchy = root != entityType || entityType.isAnnotationPresent(Inheritance.class);
        if (!inHierarchy) {
            return InheritanceInfo.NONE;
        }
        Inheritance rootInheritance = root.getAnnotation(Inheritance.class);
        InheritanceType strategy = rootInheritance != null
                ? rootInheritance.strategy()
                : InheritanceType.SINGLE_TABLE;
        DiscriminatorColumn discriminatorColumn = root.getAnnotation(DiscriminatorColumn.class);
        String columnName = discriminatorColumn != null && !discriminatorColumn.name().isBlank()
                ? discriminatorColumn.name()
                : "dtype";
        DiscriminatorType discriminatorType = discriminatorColumn != null
                ? discriminatorColumn.discriminatorType()
                : DiscriminatorType.STRING;
        int discriminatorLength = discriminatorColumn != null ? discriminatorColumn.length() : 31;
        boolean abstractType = Modifier.isAbstract(entityType.getModifiers());
        // TABLE_PER_CLASS는 물리 discriminator 컬럼이 없지만, 다형 UNION 쿼리에서 각 브랜치 row가 어떤 구체
        // 타입인지 판별하려면 합성 discriminator 상수가 필요하다. SINGLE_TABLE/JOINED와 동일한 규칙으로 값을 정한다.
        String discriminatorValue = resolveDiscriminatorValue(
                entityType, entityName, discriminatorType, abstractType, strategy);
        String rootTableName = "";
        String rootTableSchema = "";
        String rootIdColumn = "";
        if (strategy == InheritanceType.JOINED) {
            // JOINED: 루트 물리 테이블과 루트 PK 컬럼은 모든 서브타입이 FK로 공유한다. 루트의 @Table/naming과
            // 루트의 @Id 컬럼으로 결정한다.
            Table rootTable = root.getAnnotation(Table.class);
            rootTableName = rootTable != null && !rootTable.name().isBlank()
                    ? rootTable.name()
                    : namingStrategy.tableName(root);
            rootTableSchema = rootTable != null ? rootTable.schema() : "";
            rootIdColumn = joinedRootIdColumn(root);
        }
        return new InheritanceInfo(
                root, strategy, root == entityType, abstractType,
                columnName, discriminatorType, discriminatorLength, discriminatorValue,
                rootTableName, rootTableSchema, rootIdColumn);
    }

    /**
     * JOINED 루트의 PK 컬럼 이름을 해석한다. 루트(또는 그 @MappedSuperclass 조상)에 선언된 단일 {@link Id}
     * 필드의 컬럼 이름을 namingStrategy/@Column override 규칙으로 결정한다. JOINED는 복합키를 아직 지원하지
     * 않으므로 첫 @Id 필드를 쓴다(서브타입 테이블이 같은 FK 컬럼으로 1:1 공유).
     */
    private String joinedRootIdColumn(Class<?> root) {
        for (Field field : mappedFields(root)) {
            if (isNotPersistable(field) || !memberPresent(field, Id.class)) {
                continue;
            }
            Column column = memberAnnotation(field, Column.class);
            if (column != null && !column.name().isBlank()) {
                return column.name();
            }
            return namingStrategy.columnName(field.getName());
        }
        throw new IllegalArgumentException(
                root.getName() + " is a @Inheritance(JOINED) root but declares no @Id field");
    }

    /**
     * 이 엔티티의 discriminator 값을 해석한다. {@link DiscriminatorValue}가 있으면 그 값을, 없으면
     * STRING 타입은 JPA 규약대로 entity 이름을 기본값으로 쓴다. CHAR/INTEGER는 기본값이 모호하므로
     * 구체 타입에서는 명시적 {@link DiscriminatorValue}를 요구한다(abstract 타입은 row로 인스턴스화되지
     * 않으므로 빈 값 허용).
     */
    private static String resolveDiscriminatorValue(
            Class<?> entityType, String entityName, DiscriminatorType type,
            boolean abstractType, InheritanceType strategy) {
        DiscriminatorValue annotation = entityType.getAnnotation(DiscriminatorValue.class);
        if (annotation != null && !annotation.value().isBlank()) {
            return annotation.value();
        }
        if (type == DiscriminatorType.STRING) {
            return entityName;
        }
        if (abstractType) {
            return "";
        }
        throw new IllegalArgumentException(
                entityType.getName() + " requires an explicit @DiscriminatorValue because its hierarchy uses"
                        + " DiscriminatorType." + type + " (only STRING has a default value)");
    }

    /**
     * 구체(비-abstract) 계층 멤버를 root 레지스트리에 등록한다. 같은 discriminator 값을 서로 다른 두
     * 타입이 선언하면 fail-fast로 거부한다. 등록 시 해당 root의 병합 메타데이터 캐시를 무효화해, 이후
     * 새 서브타입이 union DDL/select-list에 반영되도록 한다.
     */
    private void registerHierarchyMember(EntityMetadata<?> metadata) {
        InheritanceInfo info = metadata.inheritance();
        if (!info.present() || info.abstractType()) {
            return;
        }
        Map<String, Class<?>> members = hierarchies.computeIfAbsent(
                info.root(), ignored -> new ConcurrentHashMap<>());
        Class<?> previous = members.putIfAbsent(info.discriminatorValue(), metadata.entityType());
        if (previous != null && previous != metadata.entityType()) {
            throw new IllegalArgumentException(
                    "Duplicate @DiscriminatorValue '" + info.discriminatorValue() + "' in hierarchy "
                            + info.root().getName() + ": " + previous.getName() + " and "
                            + metadata.entityType().getName());
        }
        mergedHierarchyCache.remove(info.root());
        inheritanceLayoutCache.remove(info.root());
    }

    /**
     * {@link Table#indexes()}에 선언된 {@link Index}를 모아 검증 후 {@link IndexDefinition}으로 변환한다.
     * 이름이 비어있으면 {@code ix_{table}_{col1}_{col2}_...} 패턴으로 자동 생성한다.
     * {@link Index#columnList()}는 JPA와 동일하게 콤마로 구분한 컬럼 이름 목록이다.
     */
    private static List<IndexDefinition> extractIndexes(
            Class<?> entityType,
            String tableName,
            Set<String> columnNames,
            Index[] declarations
    ) {
        if (declarations.length == 0) {
            return List.of();
        }
        List<UnboundIndexDefinition> definitions = new ArrayList<>(declarations.length);
        for (Index declaration : declarations) {
            List<IndexDefinition.Column> indexColumns = parseIndexColumnList(
                    declaration.columnList(), entityType);
            if (indexColumns.isEmpty()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @Index must declare at least one column");
            }
            String[] columns = indexColumns.stream().map(IndexDefinition.Column::name).toArray(String[]::new);
            validateColumnsExist(entityType, "@Index", columns, columnNames);
            String options = indexOptions(declaration.options(), "@Index.options on " + entityType.getName());
            boolean generatedName = declaration.name().isBlank();
            String name = generatedName ? autoGenerateName("ix_", tableName, columns) : declaration.name();
            definitions.add(new UnboundIndexDefinition(name, generatedName, indexColumns, declaration.unique(), options));
        }
        Map<String, Integer> generatedNameCounts = new LinkedHashMap<>();
        for (UnboundIndexDefinition definition : definitions) {
            if (definition.generatedName()) {
                generatedNameCounts.merge(definition.name(), 1, Integer::sum);
            }
        }
        List<IndexDefinition> result = new ArrayList<>(declarations.length);
        Map<String, Integer> emittedNames = new LinkedHashMap<>();
        for (UnboundIndexDefinition definition : definitions) {
            String name = definition.name();
            if (definition.generatedName() && generatedNameCounts.get(name) > 1) {
                name = disambiguateGeneratedIndexName(name, definition);
            }
            int occurrence = emittedNames.merge(name, 1, Integer::sum);
            if (definition.generatedName() && occurrence > 1) {
                String ordinal = "_" + occurrence;
                int prefixLength = Math.min(name.length(), MAX_AUTO_GENERATED_NAME_LENGTH - ordinal.length());
                name = name.substring(0, prefixLength) + ordinal;
                emittedNames.put(name, 1);
            }
            result.add(new IndexDefinition(name, definition.columns(), definition.unique(), definition.options()));
        }
        return result;
    }

    /**
     * {@link Table#uniqueConstraints()}에 선언된 {@link UniqueConstraint}를 모아 검증 후
     * {@link UniqueConstraintDefinition}으로 변환한다. 이름이 비어있으면
     * {@code uk_{table}_{col1}_{col2}_...} 패턴으로 자동 생성한다.
     */
    private static List<UniqueConstraintDefinition> extractUniqueConstraints(
            Class<?> entityType,
            String tableName,
            Set<String> columnNames,
            UniqueConstraint[] declarations
    ) {
        if (declarations.length == 0) {
            return List.of();
        }
        List<UniqueConstraintDefinition> result = new ArrayList<>(declarations.length);
        for (UniqueConstraint declaration : declarations) {
            String[] columns = declaration.columnNames();
            if (columns == null || columns.length == 0) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @UniqueConstraint must declare at least one column");
            }
            validateColumnsExist(entityType, "@UniqueConstraint", columns, columnNames);
            String name = declaration.name().isBlank()
                    ? autoGenerateName("uk_", tableName, columns)
                    : declaration.name();
            result.add(new UniqueConstraintDefinition(name, List.of(columns)));
        }
        return result;
    }

    /**
     * JPA {@link Index#columnList()} terms are comma-separated column identifiers with an
     * optional ASC or DESC direction. Directions are parsed independently so the renderer can
     * quote identifiers without treating the complete term as an identifier.
     */
    private static List<IndexDefinition.Column> parseIndexColumnList(String columnList, Class<?> entityType) {
        if (columnList == null || columnList.isBlank()) {
            return List.of();
        }
        List<IndexDefinition.Column> columns = new ArrayList<>();
        for (String term : columnList.split(",", -1)) {
            String trimmed = term.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @Index contains a blank column term");
            }
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length > 2) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @Index has malformed column term '" + trimmed + "'");
            }
            IndexDefinition.Direction direction = null;
            if (tokens.length == 2) {
                try {
                    direction = IndexDefinition.Direction.valueOf(tokens[1].toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            entityType.getName() + " @Index has invalid direction '" + tokens[1]
                                    + "' in column term '" + trimmed + "'; expected ASC or DESC",
                            exception);
                }
            }
            columns.add(new IndexDefinition.Column(tokens[0], direction));
        }
        return List.copyOf(columns);
    }

    private static String indexOptions(String value, String location) {
        String options = fragment(value, location);
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        int parentheses = 0;
        for (int i = 0; i < options.length(); i++) {
            char character = options.charAt(i);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(location + " must not contain control characters");
            }
            if (character == '\'' && !doubleQuoted) {
                if (singleQuoted && i + 1 < options.length() && options.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (character == '"' && !singleQuoted) {
                if (doubleQuoted && i + 1 < options.length() && options.charAt(i + 1) == '"') {
                    i++;
                } else {
                    doubleQuoted = !doubleQuoted;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted) {
                if (character == ';' || (character == '-' && i + 1 < options.length()
                        && options.charAt(i + 1) == '-') || (character == '/' && i + 1 < options.length()
                        && options.charAt(i + 1) == '*') || (character == '*' && i + 1 < options.length()
                        && options.charAt(i + 1) == '/')) {
                    throw new IllegalArgumentException(
                            location + " must not contain SQL statement delimiters or comments");
                }
                if (character == '(') {
                    parentheses++;
                } else if (character == ')') {
                    if (parentheses == 0) {
                        throw new IllegalArgumentException(location + " has unbalanced parentheses");
                    }
                    parentheses--;
                }
            }
        }
        if (singleQuoted || doubleQuoted) {
            throw new IllegalArgumentException(location + " has an unbalanced quoted value");
        }
        if (parentheses != 0) {
            throw new IllegalArgumentException(location + " has unbalanced parentheses");
        }
        return options;
    }

    private static String disambiguateGeneratedIndexName(String baseName, UnboundIndexDefinition definition) {
        StringBuilder fingerprint = new StringBuilder();
        for (IndexDefinition.Column column : definition.columns()) {
            fingerprint.append(column.name()).append('\0');
            if (column.direction() != null) {
                fingerprint.append(column.direction().name());
            }
            fingerprint.append('\0');
        }
        fingerprint.append(definition.unique()).append('\0').append(definition.options());
        String suffix = "_" + Integer.toHexString(fingerprint.toString().hashCode());
        int prefixLength = Math.min(baseName.length(), MAX_AUTO_GENERATED_NAME_LENGTH - suffix.length());
        return baseName.substring(0, prefixLength) + suffix;
    }

    private record UnboundIndexDefinition(
            String name,
            boolean generatedName,
            List<IndexDefinition.Column> columns,
            boolean unique,
            String options
    ) {
    }

    /**
     * 엔티티 자신의 필드와, 매핑에 기여하는 조상들의 필드를 함께 반환한다. 조상은 {@link MappedSuperclass}
     * (id/audit를 가진 BaseEntity)와 SINGLE_TABLE 상속의 상위 {@link Entity}(루트/중간 엔티티)를 포함한다.
     * 상위 클래스의 필드(루트의 @Id 등)가 먼저 오도록 root-first로 정렬한다.
     */
    private static List<Field> mappedFields(Class<?> entityType) {
        List<Class<?>> chain = new ArrayList<>();
        chain.add(entityType);
        Class<?> ancestor = entityType.getSuperclass();
        while (ancestor != null && ancestor != Object.class
                && (ancestor.isAnnotationPresent(MappedSuperclass.class)
                || ancestor.isAnnotationPresent(Entity.class))) {
            chain.add(ancestor);
            ancestor = ancestor.getSuperclass();
        }
        List<Field> fields = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            fields.addAll(Arrays.asList(chain.get(i).getDeclaredFields()));
        }
        return fields;
    }

    /**
     * 엔티티 자신과 매핑에 기여하는 조상({@link MappedSuperclass} / 상위 {@link Entity})의 선언 메서드를
     * root-우선 순서로 반환한다. superclass method는 subclass가 Java language rules에 따라 실제 override한
     * 경우에만 제외한다. private method는 상속/override되지 않으며, package-private method는 같은 package
     * subclass만 override할 수 있다.
     */
    private static List<Method> mappedMethods(Class<?> entityType) {
        List<List<Method>> declaredByType = new ArrayList<>();
        Class<?> current = entityType;
        while (current != null && current != Object.class
                && (current == entityType
                || current.isAnnotationPresent(MappedSuperclass.class)
                || current.isAnnotationPresent(Entity.class))) {
            List<Method> declared = new ArrayList<>(Arrays.asList(current.getDeclaredMethods()));
            declared.sort(STABLE_METHOD_ORDER);
            declaredByType.add(declared);
            current = current.getSuperclass();
        }
        List<Method> methods = new ArrayList<>();
        for (int i = declaredByType.size() - 1; i >= 0; i--) {
            for (Method method : declaredByType.get(i)) {
                if (!isOverriddenByDescendant(method, declaredByType, i)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static boolean isOverriddenByDescendant(
            Method method, List<List<Method>> declaredByType, int methodTypeIndex) {
        for (int descendantIndex = methodTypeIndex - 1; descendantIndex >= 0; descendantIndex--) {
            for (Method descendant : declaredByType.get(descendantIndex)) {
                if (overrides(descendant, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean overrides(Method descendant, Method ancestor) {
        int descendantModifiers = descendant.getModifiers();
        int ancestorModifiers = ancestor.getModifiers();
        if (Modifier.isPrivate(descendantModifiers) || Modifier.isStatic(descendantModifiers)
                || Modifier.isPrivate(ancestorModifiers) || Modifier.isStatic(ancestorModifiers)
                || !ancestor.getDeclaringClass().isAssignableFrom(descendant.getDeclaringClass())
                || ancestor.getDeclaringClass() == descendant.getDeclaringClass()
                || !ancestor.getName().equals(descendant.getName())
                || !Arrays.equals(ancestor.getParameterTypes(), descendant.getParameterTypes())) {
            return false;
        }
        if (!Modifier.isPublic(ancestorModifiers) && !Modifier.isProtected(ancestorModifiers)
                && !ancestor.getDeclaringClass().getPackageName()
                .equals(descendant.getDeclaringClass().getPackageName())) {
            return false;
        }
        return ancestor.getReturnType().isAssignableFrom(descendant.getReturnType());
    }

    private static boolean hasExcludeDefaultListeners(Class<?> entityType) {
        Class<?> current = entityType;
        while (current != null && current != Object.class
                && (current == entityType
                || current.isAnnotationPresent(MappedSuperclass.class)
                || current.isAnnotationPresent(Entity.class))) {
            if (current.isAnnotationPresent(ExcludeDefaultListeners.class)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    /**
     * 리플렉션 메서드의 안정적(결정적) 정렬 기준 — 이름 → 파라미터 타입 시그니처. {@code getDeclaredMethods()}의
     * JVM 비결정 순서를 흡수해 라이프사이클 콜백 호출 순서를 재현 가능하게 한다.
     */
    private static final java.util.Comparator<Method> STABLE_METHOD_ORDER =
            java.util.Comparator.comparing(Method::getName)
                    .thenComparing(method -> Arrays.toString(method.getParameterTypes()));

    /**
     * override된 콜백을 한 번만 수집하기 위한 메서드 시그니처 키(이름 + 파라미터 타입). 콜백은 no-arg가
     * 강제되므로 사실상 이름만으로 충분하지만, 일반성을 위해 파라미터 타입까지 포함한다.
     */
    private static String callbackSignature(Method method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameterType : method.getParameterTypes()) {
            builder.append(parameterType.getName()).append(',');
        }
        return builder.append(')').toString();
    }

    /**
     * 영속 대상이 아닌 필드인지 판정한다. synthetic / static / Java {@code transient} 키워드뿐 아니라
     * JPA {@link Transient} 애너테이션이 붙은 필드도 매핑에서 제외한다. effective PROPERTY access에서는
     * JavaBean getter에 선언된 {@code @Transient}도 해당 property를 제외한다. FIELD access는 getter
     * 애너테이션을 매핑 신호로 사용하지 않는다.
     */
    private static boolean isNotPersistable(Field field) {
        return field.isSynthetic()
                || Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())
                || memberPresent(field, Transient.class);
    }

    /**
     * {@code @GeneratedValue(generator=...)}의 논리 이름을, 같은 필드 또는 엔티티 타입에 선언된
     * {@link SequenceGenerator}(이름이 일치하는 것)의 {@code sequenceName}으로 해석한다. 매칭되는
     * {@code @SequenceGenerator}가 없으면 generator 값을 그대로(시퀀스 이름으로) 반환한다.
     * {@code allocationSize}/{@code initialValue}는 Nova가 매 INSERT마다 nextval만 호출하므로 무시된다.
     */
    private static String resolveSequenceName(Class<?> declaringType, Field field, String generatorName) {
        SequenceGenerator sg = memberAnnotation(field, SequenceGenerator.class);
        if (sg == null || !sg.name().equals(generatorName)) {
            SequenceGenerator onType = declaringType.getAnnotation(SequenceGenerator.class);
            sg = onType != null && onType.name().equals(generatorName) ? onType : null;
        }
        if (sg == null) {
            return generatorName;
        }
        return sg.sequenceName().isBlank() ? sg.name() : sg.sequenceName();
    }

    private static String resolveSequenceName(Class<?> declaringType, Method getter, String generatorName) {
        SequenceGenerator sg = getter.getAnnotation(SequenceGenerator.class);
        if (sg == null || !sg.name().equals(generatorName)) {
            SequenceGenerator onType = declaringType.getAnnotation(SequenceGenerator.class);
            sg = onType != null && onType.name().equals(generatorName) ? onType : null;
        }
        return sg == null ? generatorName : (sg.sequenceName().isBlank() ? sg.name() : sg.sequenceName());
    }

    /**
     * {@code @GeneratedValue(strategy = TABLE, generator = "name")}을 같은 필드/엔티티에 선언된
     * {@link TableGenerator}(이름이 일치하는 것)로 해석해 {@link TableGeneratorInfo}를 만든다. 일치하는
     * {@code @TableGenerator}가 없으면 generator 논리 이름을 {@code pkColumnValue}로 쓰고 나머지는 JPA 기본값
     * (table/pk/value 컬럼)으로 채운다. table/컬럼/pkColumnValue 식별자는 dialect가 quote하지 않고 직접
     * concat할 가능성에 대비해 SEQUENCE와 동일한 식별자 패턴으로 검증한다.
     */
    private static TableGeneratorInfo resolveTableGeneratorInfo(
            Class<?> declaringType, Field field, String generatorName) {
        TableGenerator tg = memberAnnotation(field, TableGenerator.class);
        if (tg == null || !tg.name().equals(generatorName)) {
            // @TableGenerator는 @Inherited가 아니므로 getAnnotation은 superclass를 보지 않는다. 상속 매핑
            // (JOINED/TABLE_PER_CLASS)에서 @TableGenerator를 abstract root에 두고 @Id를 subtype이 상속하는
            // 경우를 위해, 엔티티 클래스 계층을 직접 거슬러 올라가며 이름이 일치하는 정의를 찾는다.
            tg = findTableGeneratorInHierarchy(declaringType, generatorName);
        }
        String table = DEFAULT_TABLE_GENERATOR_TABLE;
        String pkColumnName = DEFAULT_TABLE_GENERATOR_PK_COLUMN;
        String valueColumnName = DEFAULT_TABLE_GENERATOR_VALUE_COLUMN;
        // generator 논리 이름이 비어 있으면 컬럼 이름을 sequence-name fallback으로 쓴다(JPA 관행).
        String pkColumnValue = (generatorName == null || generatorName.isBlank())
                ? field.getName()
                : generatorName;
        long initialValue = 0L;
        int allocationSize = 1;
        if (tg != null) {
            if (!tg.table().isBlank()) {
                table = tg.table();
            }
            if (!tg.pkColumnName().isBlank()) {
                pkColumnName = tg.pkColumnName();
            }
            if (!tg.valueColumnName().isBlank()) {
                valueColumnName = tg.valueColumnName();
            }
            if (!tg.pkColumnValue().isBlank()) {
                pkColumnValue = tg.pkColumnValue();
            }
            initialValue = tg.initialValue();
            allocationSize = tg.allocationSize();
            if (allocationSize < 1) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " @TableGenerator(allocationSize=" + allocationSize + ") must be >= 1");
            }
        }
        validateGeneratorIdentifier(declaringType, field, "table", table);
        validateGeneratorIdentifier(declaringType, field, "pkColumnName", pkColumnName);
        validateGeneratorIdentifier(declaringType, field, "valueColumnName", valueColumnName);
        validateGeneratorIdentifier(declaringType, field, "pkColumnValue", pkColumnValue);
        return new TableGeneratorInfo(
                table, pkColumnName, valueColumnName, pkColumnValue, initialValue, allocationSize);
    }

    private static TableGeneratorInfo resolveTableGeneratorInfo(
            Class<?> declaringType, Method getter, String generatorName) {
        TableGenerator tg = getter.getAnnotation(TableGenerator.class);
        if (tg == null || !tg.name().equals(generatorName)) {
            tg = findTableGeneratorInHierarchy(declaringType, generatorName);
        }
        String table = DEFAULT_TABLE_GENERATOR_TABLE;
        String pkColumnName = DEFAULT_TABLE_GENERATOR_PK_COLUMN;
        String valueColumnName = DEFAULT_TABLE_GENERATOR_VALUE_COLUMN;
        String pkColumnValue = generatorName == null || generatorName.isBlank() ? getter.getName() : generatorName;
        long initialValue = 0L;
        int allocationSize = 1;
        if (tg != null) {
            table = tg.table().isBlank() ? table : tg.table();
            pkColumnName = tg.pkColumnName().isBlank() ? pkColumnName : tg.pkColumnName();
            valueColumnName = tg.valueColumnName().isBlank() ? valueColumnName : tg.valueColumnName();
            pkColumnValue = tg.pkColumnValue().isBlank() ? pkColumnValue : tg.pkColumnValue();
            initialValue = tg.initialValue();
            allocationSize = tg.allocationSize();
            if (allocationSize < 1) {
                throw new IllegalArgumentException(declaringType.getName() + "." + getter.getName()
                        + " @TableGenerator(allocationSize=" + allocationSize + ") must be >= 1");
            }
        }
        for (String value : List.of(table, pkColumnName, valueColumnName, pkColumnValue)) {
            if (!SEQUENCE_GENERATOR_NAME_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid @TableGenerator identifier '" + value + "' on "
                        + declaringType.getName() + "." + getter.getName() + " — must match identifier pattern "
                        + SEQUENCE_GENERATOR_NAME_PATTERN.pattern());
            }
        }
        return new TableGeneratorInfo(table, pkColumnName, valueColumnName, pkColumnValue, initialValue, allocationSize);
    }

    /**
     * 엔티티 클래스 계층(자신 → superclass …)을 거슬러 올라가며 {@code name}이 일치하는 {@code @TableGenerator}를
     * 찾는다. {@code @MappedSuperclass}/상속 root에 선언된 generator를 subtype에서 해석하기 위함이다.
     * 일치가 없으면 {@code null}(JPA 기본값으로 fallback).
     */
    private static TableGenerator findTableGeneratorInHierarchy(Class<?> type, String generatorName) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            TableGenerator candidate = current.getAnnotation(TableGenerator.class);
            if (candidate != null && candidate.name().equals(generatorName)) {
                return candidate;
            }
        }
        return null;
    }

    private static void validateGeneratorIdentifier(
            Class<?> declaringType, Field field, String attribute, String value) {
        if (!SEQUENCE_GENERATOR_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid @TableGenerator " + attribute + " '" + value + "' on "
                            + declaringType.getName() + "." + field.getName()
                            + " — must match identifier pattern " + SEQUENCE_GENERATOR_NAME_PATTERN.pattern());
        }
    }

    private static void validateColumnsExist(
            Class<?> entityType,
            String annotationLabel,
            String[] columns,
            Set<String> knownColumns
    ) {
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " " + annotationLabel
                                + " contains a blank column name");
            }
            if (!knownColumns.contains(column)) {
                throw new IllegalArgumentException(
                        entityType.getName() + " " + annotationLabel
                                + " references unknown column '" + column
                                + "'; known columns: " + knownColumns);
            }
        }
    }

    /**
     * 가장 좁은 PostgreSQL 식별자 한도(63자)에 맞춰, dialect별 별도 길이 분기 없이 단일 상한을
     * 사용한다. MySQL 한도(64자)도 자동으로 충족된다.
     */
    private static final int MAX_AUTO_GENERATED_NAME_LENGTH = 63;

    /**
     * {@code {prefix}{table}_{col1}_{col2}_...} 패턴으로 index/unique constraint 이름을 만든다.
     * 결과가 63자(PostgreSQL identifier 한도)를 초과하면 {@code _<hex hash>} suffix가 항상
     * 결과에 포함되도록 prefix 부분을 먼저 잘라서 hash 변별력을 보존한다 — 동일 table에서
     * columns만 다른 두 index가 같은 prefix 63자를 공유할 때도 충돌하지 않는다.
     */
    private static String autoGenerateName(String prefix, String tableName, String[] columns) {
        StringBuilder builder = new StringBuilder(prefix).append(tableName);
        for (String column : columns) {
            builder.append('_').append(column);
        }
        String full = builder.toString();
        if (full.length() <= MAX_AUTO_GENERATED_NAME_LENGTH) {
            return full;
        }
        int hash = Objects.hash(tableName, Arrays.hashCode(columns));
        String suffix = "_" + Integer.toHexString(hash);
        String prefixPart = prefix + tableName;
        int budget = MAX_AUTO_GENERATED_NAME_LENGTH - suffix.length();
        if (prefixPart.length() > budget) {
            prefixPart = prefixPart.substring(0, budget);
        }
        return prefixPart + suffix;
    }

    /**
     * {@code @Embedded} 필드를 호스트 엔티티 컬럼으로 펼친 {@link PersistentProperty} 목록을 만든다.
     * sub-property가 다시 {@code @Embedded}이면 재귀적으로 펼치며, 컬럼 이름은
     * {@code {outer host snake_case}_{inner host snake_case}_..._{leaf property columnName}}
     * 패턴으로 합성된다. sub-property는 {@code @Id}/{@code @Version}/{@code @SoftDelete}/
     * {@code @CreatedAt}/{@code @UpdatedAt}을 가질 수 없다.
     * <p>
     * cycle detection: outer @Embedded host type들의 stack({@code embeddableStack})에 현재 host 타입이
     * 이미 존재하면 무한 재귀를 의미하므로 즉시 {@link IllegalArgumentException}으로 거부한다.
     *
     * @param parentHostPath outer → inner 순서로 누적된 @Embedded host field chain
     * @param parentColumnPrefix 누적된 컬럼 prefix(끝에 {@code _} 포함)
     * @param embeddableStack 현재 재귀 경로에 있는 @Embeddable 타입 집합 (cycle 검출용)
     */
    private List<PersistentProperty> createEmbeddedIdProperties(
            Class<?> entityType, PersistentAttributeAccess attribute) {
        List<PersistentProperty> properties = createEmbeddedProperties(entityType, attribute, List.of(), "",
                new LinkedHashSet<>(), Map.of(), Map.of(), true);
        return properties.stream().map(PersistentProperty::withId).toList();
    }

    /**
     * {@code @IdClass} 복합키를 검증한다. 엔티티는 개별 {@code @Id} 속성을 2개 이상 선언해야 하고, IdClass는
     * 각 {@code @Id} 속성과 같은 논리 이름·호환 타입의 selected descriptor를 가져야 한다. 매핑은
     * top-level {@code @Id} 컬럼을 그대로 쓰므로 별도 컬럼 생성 없이 검증만 수행한다(분해/조립은 런타임에
     * {@link EntityMetadata#idColumnValue}/{@link EntityMetadata#readIdValue}가 처리한다).
     */
    private static void validateIdClass(Class<?> entityType, List<PersistentProperty> properties) {
        List<PersistentProperty> idProperties = properties.stream().filter(PersistentProperty::id).toList();
        for (PersistentProperty idProperty : idProperties) {
            if (idProperty.embedded()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " cannot combine @IdClass with @EmbeddedId");
            }
        }
        if (idProperties.size() < 2) {
            throw new IllegalArgumentException(
                    entityType.getName() + " uses @IdClass but declares fewer than two @Id fields;"
                            + " @IdClass models a composite key");
        }
        Class<?> idClass = entityType.getAnnotation(jakarta.persistence.IdClass.class).value();
        PersistentTypeAccess entityAccess = new PersistentAccessResolver().resolve(entityType);
        List<PersistentAttributeAccess> entityIds = entityAccess.attributes().stream()
                .filter(attribute -> attribute.isAnnotationPresent(Id.class)).toList();
        if (entityIds.size() != idProperties.size()) {
            throw new IllegalArgumentException(entityType.getName()
                    + " @IdClass validation could not resolve each selected @Id attribute");
        }
        AccessType idAccess = entityIds.get(0).accessType();
        if (entityIds.stream().anyMatch(attribute -> attribute.accessType() != idAccess)) {
            throw new IllegalArgumentException(entityType.getName()
                    + " @IdClass requires all selected @Id attributes to use one access strategy");
        }
        PersistentTypeAccess idClassAccess = new PersistentAccessResolver().resolve(idClass, idAccess);
        for (PersistentAttributeAccess entityId : entityIds) {
            String name = entityId.name();
            PersistentAttributeAccess idClassAttribute = idClassAccess.attribute(name);
            if (idClassAttribute == null) {
                throw new IllegalArgumentException(
                        "@IdClass " + idClass.getName() + " is missing property '" + name
                                + "' declared as @Id on " + entityType.getName());
            }
            Class<?> expected = wrapPrimitiveType(entityId.javaType());
            Class<?> actual = wrapPrimitiveType(idClassAttribute.javaType());
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "@IdClass " + idClass.getName() + " property '" + name + "' type " + actual.getName()
                                + " does not match @Id type " + expected.getName()
                                + " on " + entityType.getName());
            }
        }
    }

    private List<PersistentProperty> createEmbeddedProperties(
            Class<?> entityType,
            PersistentAttributeAccess host,
            List<PersistentAttributeAccess> parentHostPath,
            String parentColumnPrefix,
            LinkedHashSet<Class<?>> embeddableStack,
            Map<String, Column> inheritedColumnOverrides,
            Map<String, Convert> inheritedConversionOverrides,
            boolean embeddedId
    ) {
        Class<?> embeddableType = host.javaType();
        String location = entityType.getName() + "." + host.name();
        if (!embeddableType.isAnnotationPresent(Embeddable.class)) {
            throw new IllegalArgumentException(location + " is annotated with @Embedded but its type "
                    + embeddableType.getName() + " is not annotated with @Embeddable");
        }
        validateRecordComponentsMapped(embeddableType, location + " @Embedded");
        if (!embeddableStack.add(embeddableType)) {
            throw new IllegalArgumentException("circular @Embedded detected on " + entityType.getName()
                    + ": type " + embeddableType.getName() + " transitively embeds itself via "
                    + describeEmbeddableStack(embeddableStack, embeddableType));
        }
        try {
            if (embeddedComponentAttributes(embeddableType, host.accessType()).stream()
                    .anyMatch(component -> component.isAnnotationPresent(Id.class))) {
                throw new IllegalArgumentException("@Embeddable type " + embeddableType.getName()
                        + " must not declare @Id-annotated fields");
            }
            List<PersistentAttributeAccess> hostPath = new ArrayList<>(parentHostPath);
            hostPath.add(host);
            hostPath = List.copyOf(hostPath);
            String columnPrefix = embeddedId ? parentColumnPrefix
                    : parentColumnPrefix + namingStrategy.columnName(host.name()) + "_";
            Map<String, Column> columnOverrides = new LinkedHashMap<>();
            for (AttributeOverride override : host.annotationsByType(AttributeOverride.class)) {
                if (columnOverrides.putIfAbsent(override.name(), override.column()) != null) {
                    throw new IllegalArgumentException(location + " declares duplicate @AttributeOverride path '"
                            + override.name() + "'");
                }
            }
            // The reusable embeddable's own overrides establish its default mapping.  An outer
            // embedding site can override those defaults with a dotted path and therefore wins.
            columnOverrides.putAll(inheritedColumnOverrides);
            Map<String, Convert> conversionOverrides = new LinkedHashMap<>(inheritedConversionOverrides);
            for (Convert convert : host.annotationsByType(Convert.class)) {
                if (convert.attributeName().isBlank()) {
                    throw new IllegalArgumentException(location + " @Convert on an @Embedded attribute must specify attributeName");
                }
                if (conversionOverrides.putIfAbsent(convert.attributeName(), convert) != null) {
                    throw new IllegalArgumentException(location + " declares duplicate @Convert path '"
                            + convert.attributeName() + "'");
                }
            }
            List<PersistentProperty> result = new ArrayList<>();
            for (PersistentAttributeAccess component : embeddedComponentAttributes(embeddableType, host.accessType())) {
                rejectIllegalEmbeddedComponent(location, component);
                if (embeddedId && isEmbeddedAttribute(component)) {
                    throw new IllegalArgumentException(location + " @EmbeddedId component " + component.name()
                            + " must be a simple (non-embedded) field");
                }
                if (isEmbeddedAttribute(component)) {
                    String nestedPrefix = component.name() + ".";
                    Map<String, Column> nestedColumnOverrides = new LinkedHashMap<>();
                    columnOverrides.entrySet().removeIf(entry -> {
                        if (!entry.getKey().startsWith(nestedPrefix)) return false;
                        nestedColumnOverrides.put(entry.getKey().substring(nestedPrefix.length()), entry.getValue());
                        return true;
                    });
                    Map<String, Convert> nestedConversions = new LinkedHashMap<>();
                    conversionOverrides.entrySet().removeIf(entry -> {
                        if (!entry.getKey().startsWith(nestedPrefix)) return false;
                        nestedConversions.put(entry.getKey().substring(nestedPrefix.length()), entry.getValue());
                        return true;
                    });
                    result.addAll(createEmbeddedProperties(entityType, component, hostPath, columnPrefix,
                            embeddableStack, nestedColumnOverrides, nestedConversions, false));
                    continue;
                }
                Convert conversionOverride = conversionOverrides.remove(component.name());
                Column override = columnOverrides.remove(component.name());
                PersistentProperty property = createDescriptorProperty(component, conversionOverride, embeddedId, override);
                String column = override != null && !override.name().isBlank()
                        ? override.name() : columnPrefix + property.columnName();
                String propertyName = hostPath.stream().map(PersistentAttributeAccess::name)
                        .collect(java.util.stream.Collectors.joining(".")) + "." + component.name();
                PersistentProperty mapped = property.withPropertyName(propertyName).withColumnName(column)
                        .withEmbeddedHostAccessPath(hostPath);
                result.add(mapped);
            }
            if (!conversionOverrides.isEmpty()) {
                throw new IllegalArgumentException(location + " @Convert attributeName '"
                        + conversionOverrides.keySet().iterator().next() + "' does not match an embedded leaf property");
            }
            if (!columnOverrides.isEmpty()) {
                throw new IllegalArgumentException(location + " @AttributeOverride name '"
                        + columnOverrides.keySet().iterator().next() + "' does not match an embedded leaf property");
            }
            return result;
        } finally {
            embeddableStack.remove(embeddableType);
        }
    }

    private static void rejectIllegalEmbeddedComponent(String location, PersistentAttributeAccess component) {
        if (component.isAnnotationPresent(EmbeddedId.class)) {
            throw new IllegalArgumentException(location + " embedded component " + component.name()
                    + " must not declare @EmbeddedId");
        }
        if (component.isAnnotationPresent(Id.class)) {
            throw new IllegalArgumentException(location + " embedded component " + component.name() + " must not declare @Id");
        }
        if (component.isAnnotationPresent(Version.class)) {
            throw new IllegalArgumentException(location + " embedded component " + component.name() + " must not declare @Version");
        }
        if (component.isAnnotationPresent(SoftDelete.class)) {
            throw new IllegalArgumentException(location + " embedded component " + component.name() + " must not declare @SoftDelete");
        }
        if (component.isAnnotationPresent(CreatedAt.class)) {
            throw new IllegalArgumentException(location + " embedded component " + component.name() + " must not declare @CreatedAt");
        }
        if (component.isAnnotationPresent(UpdatedAt.class)) {
            throw new IllegalArgumentException(location + " embedded component " + component.name() + " must not declare @UpdatedAt");
        }
    }

    private static boolean isEmbeddedAttribute(PersistentAttributeAccess attribute) {
        if (attribute.isAnnotationPresent(Embedded.class) || attribute.isAnnotationPresent(EmbeddedId.class)) {
            return true;
        }
        return attribute.javaType().isAnnotationPresent(Embeddable.class)
                && !hasExplicitBasicMapping(attribute);
    }

    private static boolean hasExplicitBasicMapping(PersistentAttributeAccess attribute) {
        return attribute.isAnnotationPresent(Basic.class)
                || attribute.isAnnotationPresent(Column.class)
                || Arrays.stream(attribute.annotationsByType(Convert.class))
                .anyMatch(convert -> convert.attributeName().isBlank())
                || attribute.isAnnotationPresent(Json.class)
                || attribute.isAnnotationPresent(Enumerated.class)
                || attribute.isAnnotationPresent(Temporal.class)
                || attribute.isAnnotationPresent(Lob.class);
    }

    private static List<PersistentAttributeAccess> embeddedComponentAttributes(
            Class<?> embeddableType, AccessType inheritedAccess) {
        if (!embeddableType.isRecord()) {
            return new PersistentAccessResolver().resolve(embeddableType, inheritedAccess).attributes();
        }
        List<PersistentAttributeAccess> components = new ArrayList<>();
        for (java.lang.reflect.RecordComponent component : embeddableType.getRecordComponents()) {
            try {
                Field field = embeddableType.getDeclaredField(component.getName());
                if (!isNotPersistable(field)) {
                    components.add(new PersistentAttributeAccess(
                            component.getName(), component.getAccessor(), null, field));
                }
            } catch (NoSuchFieldException exception) {
                throw new IllegalStateException(embeddableType.getName() + " record component '"
                        + component.getName() + "' has no matching field metadata", exception);
            }
        }
        return components;
    }

    private static String describeEmbeddableStack(LinkedHashSet<Class<?>> stack, Class<?> repeated) {
        StringBuilder builder = new StringBuilder();
        for (Class<?> type : stack) {
            builder.append(type.getSimpleName()).append(" -> ");
        }
        builder.append(repeated.getSimpleName());
        return builder.toString();
    }

    private static boolean hasIdAnnotatedField(Class<?> embeddableType) {
        for (Field field : embeddableType.getDeclaredFields()) {
            if (isNotPersistable(field)) {
                continue;
            }
            if (memberPresent(field, Id.class)) {
                return true;
            }
        }
        return false;
    }

    private static void validateRecordComponentsMapped(Class<?> type, String location) {
        if (!type.isRecord()) {
            return;
        }
        for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
            try {
                Field field = type.getDeclaredField(component.getName());
                if (isNotPersistable(field)) {
                    throw new IllegalArgumentException(location + " record component '" + component.getName()
                            + "' is not persistent; every record component must participate in canonical construction");
                }
            } catch (NoSuchFieldException exception) {
                throw new IllegalStateException(location + " record component '" + component.getName()
                        + "' has no matching field metadata", exception);
            }
        }
    }

    private static void rejectIllegalSubFieldAnnotations(
            Class<?> entityType,
            Field hostField,
            Class<?> embeddableType,
            Field subField
    ) {
        String location = entityType.getName() + "." + hostField.getName()
                + " (@Embedded " + embeddableType.getSimpleName() + "." + subField.getName() + ")";
        if (subField.isAnnotationPresent(Id.class)) {
            throw new IllegalArgumentException(location + " must not declare @Id");
        }
        if (subField.isAnnotationPresent(Version.class)) {
            throw new IllegalArgumentException(location + " must not declare @Version");
        }
        if (subField.isAnnotationPresent(SoftDelete.class)) {
            throw new IllegalArgumentException(location + " must not declare @SoftDelete");
        }
        if (subField.isAnnotationPresent(CreatedAt.class)) {
            throw new IllegalArgumentException(location + " must not declare @CreatedAt");
        }
        if (subField.isAnnotationPresent(UpdatedAt.class)) {
            throw new IllegalArgumentException(location + " must not declare @UpdatedAt");
        }
    }

    /**
     * 콜백 어노테이션이 붙은 메서드의 시그니처를 검증한 뒤 컬렉터에 추가한다. 검증 실패 시
     * {@link IllegalArgumentException}을 던지며, 통과한 메서드는 {@code setAccessible(true)}로
     * 한 번만 열어 invoker가 매 호출마다 접근 검사를 반복하지 않게 한다.
     */
    private static <A extends Annotation> void collectCallback(
            Class<?> entityType,
            Method method,
            Class<A> annotationType,
            List<Method> target
    ) {
        if (!method.isAnnotationPresent(annotationType)) {
            return;
        }
        String label = "@" + annotationType.getSimpleName();
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    label + " method " + entityType.getName() + "." + method.getName()
                            + " must be non-static, no-arg, void-returning");
        }
        if (method.getParameterCount() != 0) {
            throw new IllegalArgumentException(
                    label + " method " + entityType.getName() + "." + method.getName()
                            + " must be non-static, no-arg, void-returning");
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException(
                    label + " method " + entityType.getName() + "." + method.getName()
                            + " must be non-static, no-arg, void-returning");
        }
        if (target.stream().anyMatch(existing -> existing.getDeclaringClass() == method.getDeclaringClass())) {
            throw new IllegalArgumentException(label + " declares multiple callback methods on "
                    + method.getDeclaringClass().getName());
        }
        method.setAccessible(true);
        target.add(method);
    }

    /**
     * {@code @EntityListeners}로 등록된 외부 리스너 클래스들을 읽어 phase별 콜백을 수집한다. JPA 규약을 따라
     * 슈퍼클래스({@code @MappedSuperclass}/상속 상위 {@code @Entity})에 선언된 리스너가 서브클래스 리스너보다
     * 먼저, 같은 {@code @EntityListeners} 안에서는 선언 순서대로 invoke되도록 정렬한다. 리스너 인스턴스는
     * 여기서 1회 생성해 재사용한다(stateless 가정). 리스너 콜백은 entity를 단일 인자로 받는다.
     */
    private EntityListenerCallbacks collectEntityListeners(Class<?> entityType) {
        List<Class<?>> listenerClasses = new ArrayList<>();
        for (Class<?> host : listenerHostChain(entityType)) {
            EntityListeners annotation = host.getAnnotation(EntityListeners.class);
            if (annotation == null) {
                continue;
            }
            for (Class<?> listenerClass : annotation.value()) {
                if (!listenerClasses.contains(listenerClass)) {
                    listenerClasses.add(listenerClass);
                }
            }
        }
        if (listenerClasses.isEmpty()) {
            return EntityListenerCallbacks.EMPTY;
        }
        List<ListenerCallback> prePersist = new ArrayList<>();
        List<ListenerCallback> postPersist = new ArrayList<>();
        List<ListenerCallback> preUpdate = new ArrayList<>();
        List<ListenerCallback> postUpdate = new ArrayList<>();
        List<ListenerCallback> postLoad = new ArrayList<>();
        List<ListenerCallback> preRemove = new ArrayList<>();
        List<ListenerCallback> postRemove = new ArrayList<>();
        for (Class<?> listenerClass : listenerClasses) {
            Object listener = instantiateListener(listenerClass);
            List<ListenerCallback> listenerPrePersist = new ArrayList<>();
            List<ListenerCallback> listenerPostPersist = new ArrayList<>();
            List<ListenerCallback> listenerPreUpdate = new ArrayList<>();
            List<ListenerCallback> listenerPostUpdate = new ArrayList<>();
            List<ListenerCallback> listenerPostLoad = new ArrayList<>();
            List<ListenerCallback> listenerPreRemove = new ArrayList<>();
            List<ListenerCallback> listenerPostRemove = new ArrayList<>();
            // JPA 규약: 리스너 클래스의 lifecycle callback은 root-to-child 순서다. Java language rules에
            // 따른 실제 override만 superclass method를 대체하며, private same-signature method는 각각 유지한다.
            for (Method method : listenerCallbackMethods(listenerClass)) {
                if (method.isSynthetic()) {
                    continue;
                }
                collectListenerCallback(entityType, listenerClass, listener, method, PrePersist.class, listenerPrePersist);
                collectListenerCallback(entityType, listenerClass, listener, method, PostPersist.class, listenerPostPersist);
                collectListenerCallback(entityType, listenerClass, listener, method, PreUpdate.class, listenerPreUpdate);
                collectListenerCallback(entityType, listenerClass, listener, method, PostUpdate.class, listenerPostUpdate);
                collectListenerCallback(entityType, listenerClass, listener, method, PostLoad.class, listenerPostLoad);
                collectListenerCallback(entityType, listenerClass, listener, method, PreRemove.class, listenerPreRemove);
                collectListenerCallback(entityType, listenerClass, listener, method, PostRemove.class, listenerPostRemove);
            }
            prePersist.addAll(listenerPrePersist);
            postPersist.addAll(listenerPostPersist);
            preUpdate.addAll(listenerPreUpdate);
            postUpdate.addAll(listenerPostUpdate);
            postLoad.addAll(listenerPostLoad);
            preRemove.addAll(listenerPreRemove);
            postRemove.addAll(listenerPostRemove);
        }
        return new EntityListenerCallbacks(
                prePersist, postPersist, preUpdate, postUpdate, postLoad, preRemove, postRemove);
    }

    /**
     * 리스너 클래스의 콜백 후보 메서드를 root-to-child 순서로 반환한다. superclass callback은 Java
     * language rules에 따른 실제 override일 때만 제외한다.
     */
    private static List<Method> listenerCallbackMethods(Class<?> listenerClass) {
        List<List<Method>> declaredByType = new ArrayList<>();
        Class<?> current = listenerClass;
        while (current != null && current != Object.class) {
            List<Method> declared = new ArrayList<>(Arrays.asList(current.getDeclaredMethods()));
            declared.sort(STABLE_METHOD_ORDER);
            declaredByType.add(declared);
            current = current.getSuperclass();
        }
        List<Method> methods = new ArrayList<>();
        for (int i = declaredByType.size() - 1; i >= 0; i--) {
            for (Method method : declaredByType.get(i)) {
                if (!isOverriddenByDescendant(method, declaredByType, i)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    /**
     * {@code @EntityListeners}를 선언할 수 있는 호스트 체인(자신 + {@code @MappedSuperclass}/상속 상위
     * {@code @Entity})을 루트-우선 순서로 반환한다 — 슈퍼클래스 리스너가 먼저 invoke되도록.
     *
     * <p>호스트에 {@code @ExcludeSuperclassListeners}(jakarta.persistence)가 선언되면, 그 호스트보다
     * 상위가 기여하는 리스너를 제외한다. 따라서 중간 {@code @MappedSuperclass}의 선언도 그 아래 entity에
     * 적용되는 listener-host hierarchy cutoff가 된다. entity 자체 lifecycle callback 상속은 이 체인과
     * 독립적으로 {@link #mappedMethods(Class)}가 처리한다.
     */
    private static List<Class<?>> listenerHostChain(Class<?> entityType) {
        List<Class<?>> chain = new ArrayList<>();
        Class<?> current = entityType;
        while (current != null && current != Object.class
                && (current == entityType
                || current.isAnnotationPresent(MappedSuperclass.class)
                || current.isAnnotationPresent(Entity.class))) {
            chain.add(current);
            if (current.isAnnotationPresent(ExcludeSuperclassListeners.class)) {
                break;
            }
            current = current.getSuperclass();
        }
        Collections.reverse(chain);
        return chain;
    }

    private static Object instantiateListener(Class<?> listenerClass) {
        try {
            var constructor = listenerClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "@EntityListeners class " + listenerClass.getName()
                            + " must have an accessible no-arg constructor", e);
        }
    }

    private static <A extends Annotation> void collectListenerCallback(
            Class<?> entityType,
            Class<?> listenerClass,
            Object listener,
            Method method,
            Class<A> annotationType,
            List<ListenerCallback> target
    ) {
        if (!method.isAnnotationPresent(annotationType)) {
            return;
        }
        String label = "@" + annotationType.getSimpleName();
        String where = listenerClass.getName() + "." + method.getName();
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    label + " listener method " + where
                            + " must be non-static, take a single entity argument, and return void");
        }
        if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isAssignableFrom(entityType)) {
            throw new IllegalArgumentException(
                    label + " listener method " + where
                            + " must take a single argument assignable from " + entityType.getName());
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException(
                    label + " listener method " + where
                            + " must be non-static, take a single entity argument, and return void");
        }
        if (target.stream().anyMatch(existing -> existing.method().getDeclaringClass() == method.getDeclaringClass())) {
            throw new IllegalArgumentException(label + " listener " + listenerClass.getName()
                    + " declares multiple callback methods on " + method.getDeclaringClass().getName());
        }
        method.setAccessible(true);
        target.add(new ListenerCallback(listener, method));
    }

    /**
     * Builds a PROPERTY-access mapping without assuming a Java field exists.  A property descriptor
     * is the selected persistent member in this case; keeping a reduced "no field" mapper here
     * caused legal JPA annotations to be silently ignored.
     */
    private PersistentProperty createDescriptorProperty(PersistentAttributeAccess attribute) {
        return createDescriptorProperty(attribute, null, false, null);
    }

    private PersistentProperty createDescriptorProperty(
            PersistentAttributeAccess attribute, Convert conversionOverride) {
        return createDescriptorProperty(attribute, conversionOverride, false, null);
    }

    private PersistentProperty createDescriptorProperty(
            PersistentAttributeAccess attribute, Convert conversionOverride, boolean suppressAutoApply) {
        return createDescriptorProperty(attribute, conversionOverride, suppressAutoApply, null);
    }

    private PersistentProperty createDescriptorProperty(
            PersistentAttributeAccess attribute, Convert conversionOverride, boolean suppressAutoApply,
            Column effectiveColumn) {
        ManyToOne manyToOne = attribute.annotation(ManyToOne.class);
        if (manyToOne != null) {
            JoinColumn join = attribute.annotation(JoinColumn.class);
            Class<?> target = manyToOne.targetEntity() == void.class ? attribute.javaType() : manyToOne.targetEntity();
            ForeignKeyStorage storage = resolveToOneForeignKeyStorage(target);
            ToOneForeignKey compositeForeignKey = storage == null
                    ? resolveCompositeToOneForeignKey(attribute.declaringType(), target, attribute) : null;
            String columnName = compositeForeignKey != null
                    ? compositeForeignKey.columns().get(0).columnName()
                    : join != null && !join.name().isBlank()
                            ? join.name()
                            : namingStrategy.columnName(attribute.name() + "_id");
            return new PersistentProperty(attribute.field(), attribute.name(),
                    columnName,
                    storage == null ? Long.class : storage.javaType(), false, false,
                    manyToOne.optional() && (join == null || join.nullable()), storage == null ? 255 : storage.length(),
                    0, 0, null, "", storage == null ? null : storage.converter(), false, false, false,
                    false, List.of(), false, null, false, true, target,
                    manyToOne.optional() && (join == null || join.nullable()), false, null, "",
                    join == null || join.insertable(), join == null || join.updatable(), join != null && join.unique(),
                    join == null ? "" : join.columnDefinition(), false,
                    storage == null ? null : storage.converterColumnType(), false, null, null, null, null,
                    false, "",
                    attribute.accessType() == AccessType.PROPERTY,
                    attribute.getter(), attribute.setter(),
                    manyToOne.cascade().length == 0 ? null : new ToOneCascadeInfo(Set.of(manyToOne.cascade())),
                    "", compositeForeignKey);
        }
        Column column = effectiveColumn != null ? effectiveColumn : attribute.annotation(Column.class);
        GeneratedValue generated = attribute.annotation(GeneratedValue.class);
        boolean id = attribute.isAnnotationPresent(Id.class);
        boolean version = attribute.isAnnotationPresent(Version.class);
        Class<?> javaType = attribute.javaType();
        String location = attribute.declaringType().getName() + "." + attribute.name();
        if (attribute.isAnnotationPresent(SoftDelete.class)) {
            if (id) {
                throw new IllegalArgumentException(location + " cannot be annotated with both @Id and @SoftDelete");
            }
            if (!SUPPORTED_SOFT_DELETE_TYPES.contains(javaType)) {
                throw new IllegalArgumentException(location + " has unsupported @SoftDelete type " + javaType.getName()
                        + "; supported types are java.time.Instant, java.time.LocalDateTime, java.time.OffsetDateTime");
            }
        }
        if (version) {
            if (id) {
                throw new IllegalArgumentException(location + " cannot be both @Id and @Version");
            }
            if (!SUPPORTED_VERSION_TYPES.contains(javaType)) {
                throw new IllegalArgumentException("Unsupported version type " + javaType.getName() + " on " + location
                        + "; supported types are Long, Integer, Short, java.time.LocalDateTime");
            }
        }
        String columnName = column != null && !column.name().isBlank()
                ? column.name() : namingStrategy.columnName(attribute.name());
        Basic basic = attribute.annotation(Basic.class);
        boolean nullable = (column == null || column.nullable()) && (basic == null || basic.optional());
        if (generated != null && !id) {
            throw new IllegalArgumentException(location + " uses @GeneratedValue but is not annotated with @Id");
        }
        GenerationType generationType = generated == null ? null : generated.strategy();
        String generator = generated == null ? "" : generated.generator();
        TableGeneratorInfo tableGeneratorInfo = null;
        if (generationType == GenerationType.SEQUENCE) {
            if (generator.isBlank()) {
                throw new IllegalArgumentException(location
                        + " uses @GeneratedValue(SEQUENCE) but does not specify generator (sequence name)");
            }
            generator = attribute.getter() != null
                    ? resolveSequenceName(attribute.declaringType(), attribute.getter(), generator)
                    : resolveSequenceName(attribute.declaringType(), attribute.field(), generator);
            if (!SEQUENCE_GENERATOR_NAME_PATTERN.matcher(generator).matches()) {
                throw new IllegalArgumentException("Invalid sequence generator name: '" + generator + "' on " + location
                        + " — must match identifier pattern " + SEQUENCE_GENERATOR_NAME_PATTERN.pattern());
            }
        }
        if (generationType == GenerationType.UUID && !SUPPORTED_UUID_ID_TYPES.contains(javaType)) {
            throw new IllegalArgumentException("Unsupported UUID id type " + javaType.getName() + " on " + location
                    + "; supported types are java.util.UUID, java.lang.String");
        }
        if (generationType == GenerationType.TABLE) {
            if (!SUPPORTED_TABLE_GENERATOR_ID_TYPES.contains(wrapPrimitiveType(javaType))) {
                throw new IllegalArgumentException("Unsupported @GeneratedValue(TABLE) id type " + javaType.getName()
                        + " on " + location + "; supported types are Long, Integer");
            }
            tableGeneratorInfo = attribute.getter() != null
                    ? resolveTableGeneratorInfo(attribute.declaringType(), attribute.getter(), generator)
                    : resolveTableGeneratorInfo(attribute.declaringType(), attribute.field(), generator);
        }
        Enumerated enumerated = attribute.annotation(Enumerated.class);
        boolean json = attribute.isAnnotationPresent(Json.class);
        AttributeConverter<?, ?> userConverter = converters.get(javaType);
        AttributeConverter<?, ?> converter = userConverter;
        Class<?> converterColumnType = null;
        boolean enumeratedMapping = false;
        EnumType enumType = null;
        Convert convert = conversionOverride != null ? conversionOverride : Arrays.stream(attribute.annotationsByType(Convert.class))
                .filter(candidate -> candidate.attributeName().isBlank()).findFirst().orElse(null);
        JpaConverterDescriptor explicitConverter = attribute.getter() != null
                ? explicitJpaConverter(attribute.getter(), javaType, null, location, convert)
                : explicitJpaConverter(attribute.field(), javaType, null, location, convert);
        if (enumerated != null) {
            if (json) {
                throw new IllegalStateException(location + " cannot declare both @Json and @Enumerated");
            }
            if (!javaType.isEnum()) {
                throw new IllegalArgumentException(location + " is annotated with @Enumerated but its type "
                        + javaType.getName() + " is not an enum");
            }
            if (userConverter != null) {
                throw new IllegalArgumentException(location
                        + " cannot use both @Enumerated and a registered AttributeConverter for " + javaType.getName());
            }
            enumeratedMapping = true;
            enumType = enumerated.value();
            EnumMapping mapping = resolveEnumMapping(javaType, enumType);
            converter = mapping.converter();
            converterColumnType = mapping.customColumnType();
        }
        if (json) {
            if (userConverter != null) {
                throw new IllegalStateException(location + " cannot use both @Json and a registered AttributeConverter for "
                        + javaType.getName());
            }
            converter = new JsonAttributeConverter(jsonCodec, javaType);
        }
        if (explicitConverter != null) {
            if (enumeratedMapping || json) {
                throw new IllegalStateException(location + " cannot combine @Convert with @Enumerated/@Json");
            }
            if (userConverter != null) {
                throw new IllegalStateException(location + " cannot combine @Convert with a registered AttributeConverter for "
                        + javaType.getName());
            }
            converter = explicitConverter.instantiate();
            converterColumnType = explicitConverter.columnType();
        }
        Temporal temporal = attribute.annotation(Temporal.class);
        boolean utilDate = javaType == java.util.Date.class;
        boolean calendar = java.util.Calendar.class.isAssignableFrom(javaType);
        if (temporal != null) {
            if (enumeratedMapping || json) {
                throw new IllegalStateException(location + " cannot combine @Temporal with @Enumerated/@Json");
            }
            if (convert != null && !convert.disableConversion()) {
                throw new IllegalStateException(location + " cannot combine @Temporal with @Convert");
            }
            if (userConverter != null) {
                throw new IllegalStateException(location + " cannot combine @Temporal with a registered AttributeConverter for "
                        + javaType.getName());
            }
            if (!utilDate && !calendar) {
                if (java.util.Date.class.isAssignableFrom(javaType)) {
                    throw new IllegalArgumentException(location + " is annotated with @Temporal but its type "
                            + javaType.getName() + " is a java.sql.* type whose temporal precision is already fixed by the type;"
                            + " map java.sql.Date/Time/Timestamp without @Temporal");
                }
                throw new IllegalArgumentException(location + " is annotated with @Temporal but its type "
                        + javaType.getName() + " is not java.util.Date or java.util.Calendar; java.time types must not use @Temporal");
            }
            converter = new TemporalAttributeConverter(javaType, temporal.value());
            converterColumnType = switch (temporal.value()) {
                case DATE -> java.time.LocalDate.class;
                case TIME -> java.time.LocalTime.class;
                case TIMESTAMP -> java.time.LocalDateTime.class;
            };
        } else if ((utilDate || calendar) && converter == null) {
            throw new IllegalArgumentException(location + " maps " + javaType.getName()
                    + " but is missing @Temporal(TemporalType.DATE|TIME|TIMESTAMP); java.util.Date/Calendar mapping is ambiguous without it");
        }
        boolean conversionDisabled = convert != null ? convert.disableConversion() : attribute.getter() != null
                ? conversionDisabled(attribute.getter(), null, null)
                : conversionDisabled(attribute.field(), null, null);
        if (converter == null && !id && !version && enumerated == null && temporal == null && !json
                && !conversionDisabled && !suppressAutoApply) {
            JpaConverterDescriptor automatic = uniqueJpaConverter(javaType, true, location);
            if (automatic != null) {
                converter = automatic.instantiate();
                converterColumnType = automatic.columnType();
            }
        }
        if (converter == null && javaType.isEnum()) {
            enumeratedMapping = true;
            enumType = inferredEnumType(javaType);
            EnumMapping mapping = resolveEnumMapping(javaType, enumType);
            converter = mapping.converter();
            converterColumnType = mapping.customColumnType();
        }
        if (converter == null) {
            ElementValueMapping storage = resolveBasicStorageMapping(wrapPrimitiveType(javaType));
            if (storage.converter() != null) {
                converter = storage.converter();
                converterColumnType = storage.columnType();
            }
        }
        return new PersistentProperty(attribute.field(), attribute.name(), columnName, javaType, id,
                version, nullable,
                column == null ? 255 : column.length(), column == null ? 0 : column.precision(),
                column == null ? 0 : column.scale(), generationType,
                generator, converter,
                attribute.isAnnotationPresent(CreatedAt.class), attribute.isAnnotationPresent(UpdatedAt.class),
                attribute.isAnnotationPresent(SoftDelete.class), false, List.of(), enumeratedMapping, enumType, json,
                false, null, true, false, null, "", column == null || column.insertable(),
                column == null || column.updatable(), column != null && column.unique(),
                column == null ? "" : column.columnDefinition(), attribute.isAnnotationPresent(Lob.class), converterColumnType,
                false, null, null, null, tableGeneratorInfo, false, "",
                attribute.accessType() == AccessType.PROPERTY, attribute.getter(), attribute.setter(),
                null, column == null ? "" : column.table(), null,
                columnDdlDefinition(column, attribute));
    }

    private static Class<?> collectionElementType(
            Class<?> entityType, String name, Type genericType, Class<? extends Annotation> relation) {
        if (genericType instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> elementType) {
            return elementType;
        }
        throw new IllegalArgumentException(entityType.getName() + "." + name + " @"
                + relation.getSimpleName() + " cannot infer the target entity from a raw collection; specify targetEntity");
    }

    private PersistentProperty createDescriptorToOneProperty(
            Class<?> entityType, PersistentAttributeAccess attribute, boolean oneToOne) {
        OneToOne annotation = attribute.annotation(OneToOne.class);
        Class<?> target = annotation.targetEntity() == void.class ? attribute.javaType() : annotation.targetEntity();
        String mappedBy = annotation.mappedBy();
        if (!mappedBy.isBlank()) {
            rejectUnsupportedInverseOneToOne(entityType, attribute.name(), annotation);
            if (attribute.isAnnotationPresent(MapsId.class)) {
                throw new IllegalArgumentException(entityType.getName() + "." + attribute.name()
                        + " @MapsId is only valid on the owning side of a to-one relationship;"
                        + " it cannot be placed on an inverse @OneToOne(mappedBy)");
            }
            return new PersistentProperty(attribute.field(), attribute.name(), "", attribute.javaType(),
                    false, false, true,
                    255, 0, 0, null, "", null, false, false, false, false, List.of(), false, null, false,
                    false, null, false, false, target, mappedBy, true, true, false, "", false, null, true,
                    null, null, null, null, false, "", attribute.accessType() == AccessType.PROPERTY,
                    attribute.getter(), attribute.setter(), null, "", null,
                    ColumnDdlDefinition.EMPTY);
        }
        JoinColumn join = attribute.annotation(JoinColumn.class);
        rejectUnsupportedOwningOneToOne(entityType, attribute.name(), annotation, join,
                attribute.annotation(JoinColumns.class), attribute.isAnnotationPresent(MapsId.class));
        ForeignKeyStorage storage = resolveToOneForeignKeyStorage(target);
        ToOneForeignKey compositeForeignKey = storage == null
                ? resolveCompositeToOneForeignKey(entityType, target, attribute) : null;
        String columnName = compositeForeignKey != null
                ? compositeForeignKey.columns().get(0).columnName()
                : join != null && !join.name().isBlank()
                        ? join.name()
                        : namingStrategy.columnName(attribute.name() + "_id");
        String mapsId = resolveMapsIdMarker(entityType, attribute);
        return new PersistentProperty(attribute.field(), attribute.name(),
                columnName,
                storage == null ? Long.class : storage.javaType(), false, false,
                annotation.optional() && (join == null || join.nullable()), storage == null ? 255 : storage.length(),
                0, 0, null, "", storage == null ? null : storage.converter(), false, false, false,
                false, List.of(), false, null, false, true, target,
                annotation.optional() && (join == null || join.nullable()), false, null, "",
                join == null || join.insertable(), join == null || join.updatable(), oneToOne || (join != null && join.unique()),
                join == null ? "" : join.columnDefinition(), false,
                storage == null ? null : storage.converterColumnType(), false, null, null, null, null,
                mapsId != null, mapsId == null ? "" : mapsId,
                attribute.accessType() == AccessType.PROPERTY,
                attribute.getter(), attribute.setter(),
                annotation.cascade().length == 0 ? null : new ToOneCascadeInfo(Set.of(annotation.cascade())),
                "", compositeForeignKey).withOneToOneLifecycle(oneToOne, annotation.orphanRemoval());
    }

    private PersistentProperty createDescriptorCollectionMarker(PersistentAttributeAccess attribute) {
        return new PersistentProperty(attribute.field(), attribute.name(), "", attribute.javaType(),
                false, false, true,
                255, 0, 0, null, "", null, false, false, false, false, List.of(), false, null, false,
                false, null, true, false, null, "", true, true, false, "", false, null, false,
                null, null, null, null, false, "", attribute.accessType() == AccessType.PROPERTY,
                attribute.getter(), attribute.setter(), null, "", null,
                ColumnDdlDefinition.EMPTY);
    }

    private static void rejectUnsupportedInverseOneToOne(
            Class<?> entityType, String propertyName, OneToOne annotation) {
        if (annotation.orphanRemoval()) {
            throw new IllegalArgumentException(entityType.getName() + "." + propertyName
                    + " orphanRemoval is supported only on the owning @OneToOne side");
        }
        for (CascadeType cascade : annotation.cascade()) {
            if (cascade == CascadeType.PERSIST || cascade == CascadeType.MERGE
                    || cascade == CascadeType.REMOVE || cascade == CascadeType.ALL) {
                throw new IllegalArgumentException(entityType.getName() + "." + propertyName
                        + " inverse @OneToOne cascade " + cascade + " is not supported");
            }
        }
    }

    private static void rejectUnsupportedOwningOneToOne(
            Class<?> entityType, String propertyName, OneToOne annotation, JoinColumn joinColumn,
            JoinColumns joinColumns, boolean mapsId) {
        if (!annotation.orphanRemoval()) {
            return;
        }
        if (mapsId) {
            throw new IllegalArgumentException(entityType.getName() + "." + propertyName
                    + " orphanRemoval cannot be combined with @MapsId");
        }
        if (joinColumn != null && !joinColumn.updatable()) {
            throw new IllegalArgumentException(entityType.getName() + "." + propertyName
                    + " orphanRemoval requires an updatable @JoinColumn");
        }
        if (joinColumns != null) {
            for (JoinColumn column : joinColumns.value()) {
                if (!column.updatable()) {
                    throw new IllegalArgumentException(entityType.getName() + "." + propertyName
                            + " orphanRemoval requires every @JoinColumn to be updatable");
                }
            }
        }
    }

    /**
     * 단일 field로부터 {@link PersistentProperty}를 만든다. {@code hostPath}가 비어있지 않으면
     * 이 property는 {@code @Embedded} 필드(들) 안에 있는 sub-field이며 column 이름에 prefix가 붙고
     * property name은 호스트 필드 이름들을 dot으로 join한 prefix를 갖는다.
     */
    private PersistentProperty createProperty(
            Class<?> declaringType,
            Field field,
            List<Field> hostPath,
            String columnPrefix
    ) {
        return createProperty(declaringType, field, hostPath, columnPrefix, null,
                memberPresent(field, Id.class), null);
    }

    private PersistentProperty createProperty(
            Class<?> declaringType,
            PersistentAttributeAccess attribute,
            List<Field> hostPath,
            String columnPrefix
    ) {
        // Top-level metadata is always descriptor-driven, regardless of whether a conventional
        // backing field happens to exist. Embedded legacy paths still carry their column prefix
        // until composed host descriptors replace List<Field>.
        if (hostPath.isEmpty()) {
            return createDescriptorProperty(attribute);
        }
        return createProperty(declaringType, attribute.field(), hostPath, columnPrefix);
    }

    /**
     * {@code columnNameOverride}가 비어있지 않으면 prefix/naming을 무시하고 그 이름을 컬럼 이름으로 쓴다.
     * {@code @Embedded} 호스트 필드의 {@code @AttributeOverride}가 sub-property 컬럼명을 재정의할 때 사용된다.
     */
    private PersistentProperty createProperty(
            Class<?> declaringType,
            Field field,
            List<Field> hostPath,
            String columnPrefix,
            Column columnOverride
    ) {
        return createProperty(declaringType, field, hostPath, columnPrefix, columnOverride,
                memberPresent(field, Id.class), null);
    }

    private PersistentProperty createProperty(
            Class<?> declaringType,
            Field field,
            List<Field> hostPath,
            String columnPrefix,
            Column columnOverride,
            boolean effectiveId,
            Convert conversionOverride
    ) {
        Column column = columnOverride != null ? columnOverride : memberAnnotation(field, Column.class);
        GeneratedValue generatedValue = memberAnnotation(field, GeneratedValue.class);
        boolean isId = effectiveId || memberPresent(field, Id.class);
        boolean isSoftDelete = memberPresent(field, SoftDelete.class);
        if (isSoftDelete) {
            if (isId) {
                throw new IllegalArgumentException(
                        declaringType.getName() + " field " + field.getName()
                                + " cannot be annotated with both @Id and @SoftDelete");
            }
            if (!SUPPORTED_SOFT_DELETE_TYPES.contains(field.getType())) {
                throw new IllegalArgumentException(
                        declaringType.getName() + " field " + field.getName()
                                + " has unsupported @SoftDelete type " + field.getType().getName()
                                + "; supported types are java.time.Instant, java.time.LocalDateTime, java.time.OffsetDateTime");
            }
        }
        boolean isVersion = memberPresent(field, Version.class);
        if (isVersion) {
            if (isId) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName() + " cannot be both @Id and @Version");
            }
            if (!SUPPORTED_VERSION_TYPES.contains(field.getType())) {
                throw new IllegalArgumentException(
                        "Unsupported version type " + field.getType().getName() + " on "
                                + declaringType.getName() + "." + field.getName()
                                + "; supported types are Long, Integer, Short, java.time.LocalDateTime");
            }
        }
        GenerationType generationType = generatedValue == null ? null : generatedValue.strategy();
        String generator = generatedValue == null ? "" : generatedValue.generator();
        TableGeneratorInfo tableGeneratorInfo = null;
        if (generatedValue != null) {
            if (generationType == GenerationType.TABLE) {
                if (!isId) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(TABLE) but is not annotated with @Id");
                }
                if (!SUPPORTED_TABLE_GENERATOR_ID_TYPES.contains(wrapPrimitiveType(field.getType()))) {
                    throw new IllegalArgumentException(
                            "Unsupported @GeneratedValue(TABLE) id type " + field.getType().getName() + " on "
                                    + declaringType.getName() + "." + field.getName()
                                    + "; supported types are Long, Integer");
                }
                tableGeneratorInfo = resolveTableGeneratorInfo(declaringType, field, generator);
            }
            if (generationType == GenerationType.SEQUENCE) {
                if (!isId) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(SEQUENCE) but is not annotated with @Id");
                }
                if (generator == null || generator.isBlank()) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(SEQUENCE) but does not specify generator (sequence name)");
                }
                // JPA: @GeneratedValue(generator="name")가 @SequenceGenerator(name="name", sequenceName=...)를
                // 가리키면 그 sequenceName으로 해석한다. 매칭되는 @SequenceGenerator가 없으면 generator 값을
                // 시퀀스 이름으로 그대로 사용한다(shorthand).
                generator = resolveSequenceName(declaringType, field, generator);
                if (!SEQUENCE_GENERATOR_NAME_PATTERN.matcher(generator).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid sequence generator name: '" + generator + "' on "
                                    + declaringType.getName() + "." + field.getName()
                                    + " — must match identifier pattern "
                                    + SEQUENCE_GENERATOR_NAME_PATTERN.pattern());
                }
            }
            if (generationType == GenerationType.UUID) {
                if (!isId) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(UUID) but is not annotated with @Id");
                }
                if (!SUPPORTED_UUID_ID_TYPES.contains(field.getType())) {
                    throw new IllegalArgumentException(
                            "Unsupported UUID id type " + field.getType().getName() + " on "
                                    + declaringType.getName() + "." + field.getName()
                                    + "; supported types are java.util.UUID, java.lang.String");
                }
            }
        }
        String baseColumnName = column != null && !column.name().isBlank()
                ? column.name()
                : namingStrategy.columnName(field.getName());
        String columnName = columnOverride != null && !columnOverride.name().isBlank()
                ? columnOverride.name()
                : columnPrefix + baseColumnName;
        String propertyName;
        if (hostPath == null || hostPath.isEmpty()) {
            propertyName = field.getName();
        } else {
            StringBuilder builder = new StringBuilder();
            for (Field hostField : hostPath) {
                builder.append(hostField.getName()).append('.');
            }
            builder.append(field.getName());
            propertyName = builder.toString();
        }

        Enumerated enumerated = memberAnnotation(field, Enumerated.class);
        boolean isJson = memberPresent(field, Json.class);
        AttributeConverter<?, ?> userConverter = converters.get(field.getType());
        boolean isEnumerated = false;
        EnumType enumType = null;
        AttributeConverter<?, ?> converter = userConverter;
        Class<?> converterColumnType = null;
        String mappingLocation = declaringType.getName() + "." + field.getName();
        JpaConverterDescriptor explicitConverter = explicitJpaConverter(
                field, field.getType(), null, mappingLocation, conversionOverride);
        if (enumerated != null) {
            if (isJson) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot declare both @Json and @Enumerated");
            }
            if (!field.getType().isEnum()) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " is annotated with @Enumerated but its type "
                                + field.getType().getName() + " is not an enum");
            }
            if (userConverter != null) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot use both @Enumerated and a registered AttributeConverter for "
                                + field.getType().getName());
            }
            isEnumerated = true;
            enumType = enumerated.value();
            EnumMapping enumMapping = resolveEnumMapping(field.getType(), enumType);
            converter = enumMapping.converter();
            converterColumnType = enumMapping.customColumnType();
        }
        if (isJson) {
            if (userConverter != null) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot use both @Json and a registered AttributeConverter for "
                                + field.getType().getName());
            }
            // @Json은 dialect의 jsonColumnType()으로 컬럼 타입을 받고, 값 변환은 주입된 JsonCodec을 감싼
            // JsonAttributeConverter로 일반 converter 경로(toColumnValue/toPropertyValue)를 그대로 탄다.
            converter = new JsonAttributeConverter(jsonCodec, field.getType());
        }

        // @Convert(converter=X.class): JPA 표준 AttributeConverter를 어댑터로 감싸 일반 converter 경로에 태운다.
        // 저장 표현 타입(Y)을 columnType()/schema 컬럼 타입의 근거로 보관한다.
        Convert convert = conversionOverride != null
                ? conversionOverride
                : Arrays.stream(memberAnnotations(field, Convert.class))
                        .filter(candidate -> candidate.attributeName().isBlank()).findFirst().orElse(null);
        if (explicitConverter != null) {
            if (isEnumerated || isJson) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot combine @Convert with @Enumerated/@Json");
            }
            if (userConverter != null) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot combine @Convert with a registered AttributeConverter for "
                                + field.getType().getName());
            }
            converter = explicitConverter.instantiate();
            converterColumnType = explicitConverter.columnType();
        }

        // @Temporal: 레거시 java.util.Date/Calendar 필드를 java.time 저장 표현으로 매핑한다. Nova는 java.time
        // 네이티브이므로 @Temporal이 붙은 Date/Calendar에 TemporalAttributeConverter를 달고, 저장 타입
        // (DATE→LocalDate, TIME→LocalTime, TIMESTAMP→LocalDateTime)을 converterColumnType으로 보관해
        // schema 컬럼 타입(date/time/timestamp)과 row 디코딩이 도메인 타입이 아닌 저장 타입을 따르게 한다.
        Temporal temporal = memberAnnotation(field, Temporal.class);
        boolean isUtilDate = field.getType() == java.util.Date.class;
        boolean isCalendar = java.util.Calendar.class.isAssignableFrom(field.getType());
        if (temporal != null) {
            if (isEnumerated || isJson) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot combine @Temporal with @Enumerated/@Json");
            }
            if (convert != null && !convert.disableConversion()) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot combine @Temporal with @Convert");
            }
            if (userConverter != null) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot combine @Temporal with a registered AttributeConverter for "
                                + field.getType().getName());
            }
            if (!isUtilDate && !isCalendar) {
                // java.sql.Date/Time/Timestamp는 java.util.Date의 하위 타입이라 정확 비교(== java.util.Date.class)에는
                // 걸리지 않지만 "is not java.util.Date" 메시지는 오해를 부른다. 이들은 SQL date/time/timestamp 의미가
                // 타입 자체에 이미 확정돼 @Temporal이 불필요하므로(드라이버 네이티브 매핑 대상), 별도의 명확한 사유로 거부한다.
                if (java.util.Date.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " is annotated with @Temporal but its type " + field.getType().getName()
                                    + " is a java.sql.* type whose temporal precision is already fixed by the type;"
                                    + " map java.sql.Date/Time/Timestamp without @Temporal");
                }
                // JPA도 java.time 타입에 @Temporal을 금지한다(불필요/모호). java.time은 그 자체로 정밀도가
                // 확정되므로 @Temporal 없이 매핑돼야 한다.
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " is annotated with @Temporal but its type " + field.getType().getName()
                                + " is not java.util.Date or java.util.Calendar; java.time types must not use @Temporal");
            }
            TemporalType temporalType = temporal.value();
            converter = new TemporalAttributeConverter(field.getType(), temporalType);
            converterColumnType = switch (temporalType) {
                case DATE -> java.time.LocalDate.class;
                case TIME -> java.time.LocalTime.class;
                case TIMESTAMP -> java.time.LocalDateTime.class;
            };
        } else if ((isUtilDate || isCalendar) && converter == null) {
            // java.util.Date/Calendar는 SQL 매핑이 date/time/timestamp 중 무엇인지 본질적으로 모호하다.
            // 등록된 converter나 @Convert가 책임지지 않는 한, JPA와 동일하게 @Temporal을 요구한다(조용한 기본값 금지).
            throw new IllegalArgumentException(
                    declaringType.getName() + "." + field.getName()
                            + " maps " + field.getType().getName()
                            + " but is missing @Temporal(TemporalType.DATE|TIME|TIMESTAMP);"
                            + " java.util.Date/Calendar mapping is ambiguous without it");
        }

        // Jakarta auto-apply never reaches ids, versions, explicit enum/temporal mappings, or disabled attributes.
        if (converter == null && !isId && !isVersion && enumerated == null && temporal == null && !isJson
                && !conversionDisabled(field, null, conversionOverride)) {
            JpaConverterDescriptor automatic = uniqueJpaConverter(field.getType(), true, mappingLocation);
            if (automatic != null) {
                converter = automatic.instantiate();
                converterColumnType = automatic.columnType();
            }
        }
        // An unannotated enum is still an enumerated basic attribute. A unique applied converter wins and makes
        // @EnumeratedValue irrelevant; otherwise Jakarta 3.2 infers STRING from a String marker and ORDINAL otherwise.
        if (converter == null && field.getType().isEnum()) {
            isEnumerated = true;
            enumType = inferredEnumType(field.getType());
            EnumMapping enumMapping = resolveEnumMapping(field.getType(), enumType);
            converter = enumMapping.converter();
            converterColumnType = enumMapping.customColumnType();
        }

        // @Enumerated/@Json/@Convert/@Temporal/등록 converter가 없는 순수 스칼라 타입의 저장타입 분리를 EC 원소와
        // 대칭으로 적용한다 — 현재 UUID만 해당(varchar(String) via UuidStringConverter). 드라이버가 varchar→UUID를
        // 직접 디코딩하지 못하므로 columnType()을 String으로 분리해 schema DDL과 row 디코딩을 저장타입에 통일한다.
        // Float/Short 등 드라이버 네이티브 기본 타입은 converter 없이 sqlType이 도메인 타입에서 직접 유도한다.
        if (converter == null) {
            ElementValueMapping basicStorage = resolveBasicStorageMapping(wrapPrimitiveType(field.getType()));
            if (basicStorage.converter() != null) {
                converter = basicStorage.converter();
                converterColumnType = basicStorage.columnType();
            }
        }

        boolean embedded = hostPath != null && !hostPath.isEmpty();
        // @Column(table="...")는 이 컬럼을 @SecondaryTable 보조 테이블 행으로 라우팅한다. 보조 테이블 이름이
        // 실제로 선언됐는지는 resolveSecondaryTables가 검증한다(조용한 무시 금지). v1은 top-level 컬럼만 보조
        // 테이블로 보내며, @Embedded/@EmbeddedId 하위 필드의 table=은 미지원으로 fail-fast 거부한다.
        String secondaryTableName = column == null ? "" : column.table();
        if (!secondaryTableName.isBlank() && embedded) {
            throw new IllegalArgumentException(
                    declaringType.getName() + "." + field.getName()
                            + " @Column(table=...) on an @Embedded/@EmbeddedId sub-field is not supported");
        }
        int length = column != null ? column.length() : 255;
        int precision = column != null ? column.precision() : 0;
        int scale = column != null ? column.scale() : 0;
        boolean insertable = column == null || column.insertable();
        boolean updatable = column == null || column.updatable();
        boolean unique = column != null && column.unique();
        String columnDefinition = column == null ? "" : column.columnDefinition();
        boolean lob = memberPresent(field, Lob.class);
        // @Basic(optional=false)은 @Column(nullable=false)과 동일하게 NOT NULL 제약으로 반영한다. JPA에서 basic
        // 속성의 nullability는 @Column.nullable / @Basic.optional 중 하나라도 false면 NOT NULL이다. (@Basic.fetch는
        // Nova에 lazy basic 개념이 없어 no-op으로 수용 — 관계 fetch=LAZY와 동일 정책.)
        Basic basic = memberAnnotation(field, Basic.class);
        boolean nullable = (column == null || column.nullable()) && (basic == null || basic.optional());
        // @Access(AccessType.PROPERTY): 클래스 레벨 기본 access type + 멤버 레벨 override를 해석하고,
        // PROPERTY access이면 JavaBean getter/setter를 resolve해 PP에 캐시한다(resolve 실패 시 fail-fast).
        boolean recordComponent = field.getDeclaringClass().isRecord();
        boolean propertyAccess = recordComponent || resolvePropertyAccess(field);
        Method propertyAccessGetter = null;
        Method propertyAccessSetter = null;
        if (propertyAccess) {
            propertyAccessGetter = resolvePropertyGetter(field);
            propertyAccessSetter = recordComponent ? null : resolvePropertySetter(field);
        }
        return new PersistentProperty(
                field,
                propertyName,
                columnName,
                field.getType(),
                isId,
                isVersion,
                nullable,
                length,
                precision,
                scale,
                generationType,
                generator,
                converter,
                memberPresent(field, CreatedAt.class),
                memberPresent(field, UpdatedAt.class),
                isSoftDelete,
                embedded,
                embedded ? hostPath : List.of(),
                isEnumerated,
                enumType,
                isJson,
                false,
                null,
                true,
                false,
                null,
                "",
                insertable,
                updatable,
                unique,
                columnDefinition,
                lob,
                converterColumnType,
                false,
                null,
                null,
                null,
                tableGeneratorInfo,
                false,
                "",
                propertyAccess,
                propertyAccessGetter,
                propertyAccessSetter,
                null,
                secondaryTableName,
                null,
                columnDdlDefinition(column, field)
        );
    }

    private static ColumnDdlDefinition columnDdlDefinition(Column column, Field field) {
        if (column == null) {
            return ColumnDdlDefinition.EMPTY;
        }
        int secondPrecision = column.secondPrecision();
        if (secondPrecision < -1) {
            throw new IllegalArgumentException("@Column.secondPrecision on " + field.getDeclaringClass().getName()
                    + "." + field.getName() + " must be -1 (unspecified) or non-negative");
        }
        if (secondPrecision >= 0 && !isSecondPrecisionCapable(field)) {
            throw new IllegalArgumentException("@Column.secondPrecision on " + field.getDeclaringClass().getName()
                    + "." + field.getName() + " requires a TIME or TIMESTAMP property");
        }
        List<CheckConstraintDefinition> checks = new ArrayList<>();
        for (CheckConstraint check : column.check()) {
            CheckConstraintDefinition definition = checkConstraint(check.name(), check.constraint(), check.options(),
                    "@Column.check on " + field.getDeclaringClass().getName() + "." + field.getName());
            if (definition != null) {
                checks.add(definition);
            }
        }
        String comment = column.comment();
        String options = fragment(column.options(), "@Column.options on " + field.getDeclaringClass().getName()
                + "." + field.getName());
        if (!column.columnDefinition().isBlank() && !options.isEmpty()) {
            throw new IllegalArgumentException("@Column.columnDefinition and @Column.options cannot both be non-blank on "
                    + field.getDeclaringClass().getName() + "." + field.getName());
        }
        validateNoNul(comment, "@Column.comment on " + field.getDeclaringClass().getName() + "." + field.getName());
        return new ColumnDdlDefinition(checks, comment, options, secondPrecision);
    }

    private static ColumnDdlDefinition columnDdlDefinition(
            Column column, PersistentAttributeAccess attribute) {
        if (column == null) {
            return ColumnDdlDefinition.EMPTY;
        }
        String location = attribute.declaringType().getName() + "." + attribute.name();
        int secondPrecision = column.secondPrecision();
        if (secondPrecision < -1) {
            throw new IllegalArgumentException("@Column.secondPrecision on " + location
                    + " must be -1 (unspecified) or non-negative");
        }
        Temporal temporal = attribute.annotation(Temporal.class);
        boolean secondPrecisionCapable = attribute.javaType() == java.time.LocalTime.class
                || attribute.javaType() == java.time.LocalDateTime.class
                || (temporal != null
                && (temporal.value() == TemporalType.TIME || temporal.value() == TemporalType.TIMESTAMP));
        if (secondPrecision >= 0 && !secondPrecisionCapable) {
            throw new IllegalArgumentException("@Column.secondPrecision on " + location
                    + " requires a TIME or TIMESTAMP property");
        }
        List<CheckConstraintDefinition> checks = new ArrayList<>();
        for (CheckConstraint check : column.check()) {
            CheckConstraintDefinition definition = checkConstraint(
                    check.name(), check.constraint(), check.options(), "@Column.check on " + location);
            if (definition != null) {
                checks.add(definition);
            }
        }
        String comment = column.comment();
        String options = fragment(column.options(), "@Column.options on " + location);
        if (!column.columnDefinition().isBlank() && !options.isEmpty()) {
            throw new IllegalArgumentException("@Column.columnDefinition and @Column.options"
                    + " cannot both be non-blank on " + location);
        }
        validateNoNul(comment, "@Column.comment on " + location);
        return new ColumnDdlDefinition(checks, comment, options, secondPrecision);
    }

    private static boolean isSecondPrecisionCapable(Field field) {
        if (field.getType() == java.time.LocalTime.class || field.getType() == java.time.LocalDateTime.class) {
            return true;
        }
        Temporal temporal = memberAnnotation(field, Temporal.class);
        return temporal != null && (temporal.value() == TemporalType.TIME || temporal.value() == TemporalType.TIMESTAMP);
    }

    private static CheckConstraintDefinition checkConstraint(
            String name, String constraint, String options, String location) {
        String trimmedConstraint = fragment(constraint, location + " constraint");
        String trimmedName = fragment(name, location + " name");
        String trimmedOptions = fragment(options, location + " options");
        if (trimmedConstraint.isEmpty()) {
            throw new IllegalArgumentException(location + " must declare a non-blank constraint");
        }
        return new CheckConstraintDefinition(trimmedName, trimmedConstraint, trimmedOptions);
    }

    private static String fragment(String value, String location) {
        validateNoNul(value, location);
        return value == null ? "" : value.trim();
    }

    private static void validateNoNul(String value, String location) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(location + " must not contain NUL");
        }
    }

    private static TableDdlDefinition tableDdlDefinition(Table table, Class<?> entityType) {
        if (table == null) {
            return TableDdlDefinition.EMPTY;
        }
        List<CheckConstraintDefinition> checks = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (CheckConstraint check : table.check()) {
            CheckConstraintDefinition definition = checkConstraint(
                    check.name(), check.constraint(), check.options(), "@Table.check on " + entityType.getName());
            if (definition == null) {
                continue;
            }
            if (!definition.name().isEmpty() && !names.add(definition.name())) {
                throw new IllegalArgumentException(entityType.getName()
                        + " declares duplicate @Table check constraint name '" + definition.name() + "'");
            }
            checks.add(definition);
        }
        String comment = table.comment();
        validateNoNul(comment, "@Table.comment on " + entityType.getName());
        return new TableDdlDefinition(checks, comment,
                fragment(table.options(), "@Table.options on " + entityType.getName()));
    }

    /**
     * 이 field의 effective access type이 {@link AccessType#PROPERTY}인지 해석한다. 우선순위는
     * 멤버 레벨 {@code @Access}(field에 직접) → 클래스 레벨 기본 {@code @Access}(field 선언 클래스 계층) →
     * JPA 기본값 FIELD 순이다. PROPERTY이면 {@code true}.
     */
    private static boolean resolvePropertyAccess(Field field) {
        Access memberAccess = field.getAnnotation(Access.class);
        if (memberAccess != null) {
            return memberAccess.value() == AccessType.PROPERTY;
        }
        Method getter = findPropertyGetter(field);
        if (getter != null) {
            memberAccess = getter.getAnnotation(Access.class);
            if (memberAccess != null) {
                return memberAccess.value() == AccessType.PROPERTY;
            }
        }
        for (Class<?> type = field.getDeclaringClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            Access classAccess = type.getAnnotation(Access.class);
            if (classAccess != null) {
                return classAccess.value() == AccessType.PROPERTY;
            }
        }
        boolean fieldIdentifier = false;
        boolean propertyIdentifier = false;
        for (Class<?> type = field.getDeclaringClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            if (type != field.getDeclaringClass()
                    && !type.isAnnotationPresent(Entity.class)
                    && !type.isAnnotationPresent(MappedSuperclass.class)) {
                continue;
            }
            for (Field candidate : type.getDeclaredFields()) {
                fieldIdentifier |= candidate.isAnnotationPresent(Id.class)
                        || candidate.isAnnotationPresent(EmbeddedId.class);
            }
            for (Method candidate : type.getDeclaredMethods()) {
                propertyIdentifier |= candidate.isAnnotationPresent(Id.class)
                        || candidate.isAnnotationPresent(EmbeddedId.class);
            }
        }
        if (fieldIdentifier && propertyIdentifier) {
            throw new IllegalArgumentException(field.getDeclaringClass().getName()
                    + " mixes field and property identifier placement; declare @Access explicitly");
        }
        return propertyIdentifier;
    }

    /** Returns the selected descriptor for the effective JPA access strategy. */
    private static PersistentAttributeAccess selectedAttribute(Field field) {
        if (!resolvePropertyAccess(field)) {
            return new PersistentAttributeAccess(field.getName(), field);
        }
        return new PersistentAttributeAccess(
                field.getName(), resolvePropertyGetter(field), resolvePropertySetter(field), field);
    }

    private static <A extends Annotation> A memberAnnotation(Field field, Class<A> annotationType) {
        if (!resolvePropertyAccess(field)) {
            return field.getAnnotation(annotationType);
        }
        Method getter = findPropertyGetter(field);
        return getter == null ? null : getter.getAnnotation(annotationType);
    }

    private static boolean memberPresent(Field field, Class<? extends Annotation> annotationType) {
        if (!resolvePropertyAccess(field)) {
            return field.isAnnotationPresent(annotationType);
        }
        Method getter = findPropertyGetter(field);
        return getter != null && getter.isAnnotationPresent(annotationType);
    }

    private static AnnotatedElement selectedMember(Field field) {
        PersistentAttributeAccess attribute = selectedAttribute(field);
        return attribute.accessType() == AccessType.PROPERTY ? attribute.getter() : attribute.field();
    }

    private static <A extends Annotation> A[] memberAnnotations(Field field, Class<A> annotationType) {
        return selectedMember(field).getAnnotationsByType(annotationType);
    }

    private static Type selectedGenericType(Field field) {
        return selectedAttribute(field).genericType();
    }

    private static Method findPropertyGetter(Field field) {
        Class<?> owner = field.getDeclaringClass();
        String capitalized = capitalize(field.getName());
        Class<?> type = field.getType();
        if (type == boolean.class || type == Boolean.class) {
            Method isGetter = findZeroArgMethod(owner, "is" + capitalized);
            if (isGetter != null && (isGetter.getReturnType() == boolean.class
                    || isGetter.getReturnType() == Boolean.class)) {
                return isGetter;
            }
        }
        Method getter = findZeroArgMethod(owner, "get" + capitalized);
        return getter != null && getter.getReturnType() != void.class ? getter : null;
    }

    /**
     * PROPERTY access property의 JavaBean getter를 해석한다. boolean/Boolean 타입은 {@code isX}를 먼저,
     * 그 외에는 {@code getX}를 찾는다. 시그니처가 맞는 getter가 없으면 fail-fast로 거부한다(조용한 무시 금지).
     */
    private static Method resolvePropertyGetter(Field field) {
        Class<?> owner = field.getDeclaringClass();
        if (owner.isRecord()) {
            Method accessor = findZeroArgMethod(owner, field.getName());
            if (accessor != null && accessor.getReturnType() == field.getType()) {
                return accessor;
            }
            throw new IllegalArgumentException("Record component " + owner.getName() + "." + field.getName()
                    + " has no matching component accessor");
        }
        Method getter = findPropertyGetter(field);
        if (getter != null) {
            return getter;
        }
        String capitalized = capitalize(field.getName());
        Class<?> type = field.getType();
        throw new IllegalArgumentException(
                owner.getName() + "." + field.getName()
                        + " uses @Access(PROPERTY) but has no JavaBean getter"
                        + " (expected get" + capitalized
                        + ((type == boolean.class || type == Boolean.class) ? " or is" + capitalized : "") + ")");
    }

    /**
     * PROPERTY access property의 JavaBean setter({@code setX(type)})를 해석한다. 매칭되는 setter가 없으면
     * fail-fast로 거부한다(조용한 무시 금지).
     */
    private static Method resolvePropertySetter(Field field) {
        Class<?> owner = field.getDeclaringClass();
        // Record components are immutable and are populated through their canonical constructor.
        // Their component accessor is the selected PROPERTY reader; requiring a JavaBean setter
        // here would reject otherwise valid record embeddables before construction can occur.
        if (owner.isRecord()) {
            return null;
        }
        String setterName = "set" + capitalize(field.getName());
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(setterName)) {
                    continue;
                }
                if (method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> param = method.getParameterTypes()[0];
                if (param.isAssignableFrom(field.getType()) || param == field.getType()
                        || wrapPrimitiveType(param) == wrapPrimitiveType(field.getType())) {
                    return method;
                }
            }
        }
        throw new IllegalArgumentException(
                owner.getName() + "." + field.getName()
                        + " uses @Access(PROPERTY) but has no JavaBean setter"
                        + " (expected " + setterName + "(" + field.getType().getSimpleName() + "))");
    }

    /**
     * 이름이 일치하는 zero-arg 메서드를 선언 클래스 계층에서 찾는다. 없으면 {@code null}.
     */
    private static Method findZeroArgMethod(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * {@code @Convert}로 지정된 {@link jakarta.persistence.AttributeConverter} 구현의 type argument
     * {@code [X(엔티티 속성 타입), Y(컬럼 저장 타입)]}을 reflection으로 해석한다. 구체 타입이 아니거나
     * (raw/제네릭) AttributeConverter 구현이 발견되지 않으면 fail-fast로 거부한다.
     */
    private static Class<?>[] resolveJpaConverterTypeArguments(
            Class<?> declaringType, Field field, Class<?> converterClass) {
        try {
            return resolveJpaConverterTypeArguments(converterClass);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    declaringType.getName() + "." + field.getName() + " @Convert converter "
                            + converterClass.getName()
                            + " must implement jakarta.persistence.AttributeConverter with concrete type arguments",
                    exception);
        }
    }

    private static Class<?>[] resolveJpaConverterTypeArguments(Class<?> converterClass) {
        Class<?>[] resolved = resolveJpaConverterTypeArguments(converterClass, Map.of(), new LinkedHashSet<>());
        if (resolved != null) {
            return resolved;
        }
        throw new IllegalArgumentException(
                "JPA converter " + converterClass.getName()
                        + " must implement jakarta.persistence.AttributeConverter with concrete type arguments");
    }

    private static Class<?>[] resolveJpaConverterTypeArguments(
            Type current, Map<TypeVariable<?>, Type> inheritedBindings, Set<Type> visited) {
        if (!visited.add(current)) {
            return null;
        }
        Class<?> raw;
        Map<TypeVariable<?>, Type> bindings = new LinkedHashMap<>(inheritedBindings);
        if (current instanceof ParameterizedType parameterized) {
            if (!(parameterized.getRawType() instanceof Class<?> parameterizedRaw)) {
                return null;
            }
            raw = parameterizedRaw;
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int index = 0; index < variables.length; index++) {
                bindings.put(variables[index], substituteType(arguments[index], inheritedBindings));
            }
        } else if (current instanceof Class<?> currentClass) {
            raw = currentClass;
        } else {
            return null;
        }
        if (raw == jakarta.persistence.AttributeConverter.class) {
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Class<?> attributeType = rawClass(substituteType(bindings.get(variables[0]), bindings));
            Class<?> columnType = rawClass(substituteType(bindings.get(variables[1]), bindings));
            return attributeType == null || columnType == null ? null : new Class<?>[]{attributeType, columnType};
        }
        for (Type interfaceType : raw.getGenericInterfaces()) {
            Class<?>[] resolved = resolveJpaConverterTypeArguments(interfaceType, bindings, visited);
            if (resolved != null) {
                return resolved;
            }
        }
        Type superclass = raw.getGenericSuperclass();
        return superclass == null ? null : resolveJpaConverterTypeArguments(superclass, bindings, visited);
    }

    private static Type substituteType(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type current = type;
        Set<Type> seen = new LinkedHashSet<>();
        while (current instanceof TypeVariable<?> variable && seen.add(current)) {
            Type replacement = bindings.get(variable);
            if (replacement == null) {
                return current;
            }
            current = replacement;
        }
        return current;
    }

    private record JpaConverterDescriptor(
            Class<?> converterClass, Class<?> attributeType, Class<?> columnType, boolean autoApply) {
        AttributeConverter<Object, Object> instantiate() {
            return castConverter(new JpaAttributeConverterAdapter<>(instantiateJpaConverter(converterClass)));
        }
    }

    private JpaConverterDescriptor explicitJpaConverter(
            Field field, Class<?> attributeType, String selector, String location) {
        return explicitJpaConverter(field, attributeType, selector, location, null);
    }

    private JpaConverterDescriptor explicitJpaConverter(
            Field field, Class<?> attributeType, String selector, String location, Convert override) {
        Convert selected = null;
        Convert[] candidates = override == null ? memberAnnotations(field, Convert.class) : new Convert[]{override};
        for (Convert candidate : candidates) {
            String name = candidate.attributeName();
            boolean matches = override != null || (selector == null
                    ? name.isBlank()
                    : name.equals(selector) || (selector.equals("value") && name.isBlank()));
            if (!matches) {
                continue;
            }
            if (selected != null) {
                throw new IllegalArgumentException(location + " declares multiple @Convert mappings for "
                        + (selector == null ? "the attribute" : "map " + selector));
            }
            selected = candidate;
        }
        if (selected == null || selected.disableConversion()) {
            return null;
        }
        if (selected.converter() != jakarta.persistence.AttributeConverter.class) {
            Class<?>[] types = resolveJpaConverterTypeArguments(field.getDeclaringClass(), field, selected.converter());
            Class<?> target = wrapPrimitiveType(types[0]);
            if (target != wrapPrimitiveType(attributeType)) {
                throw new IllegalArgumentException(location + " @Convert converter " + selected.converter().getName()
                        + " targets " + target.getName() + " but the mapped type is " + attributeType.getName());
            }
            return new JpaConverterDescriptor(selected.converter(), target, wrapPrimitiveType(types[1]), false);
        }
        return uniqueJpaConverter(attributeType, false, location);
    }

    private JpaConverterDescriptor explicitJpaConverter(
            Method getter, Class<?> attributeType, String selector, String location, Convert override) {
        Convert selected = null;
        Convert[] candidates = override == null ? getter.getAnnotationsByType(Convert.class) : new Convert[]{override};
        for (Convert candidate : candidates) {
            String name = candidate.attributeName();
            boolean matches = override != null || (selector == null
                    ? name.isBlank()
                    : name.equals(selector) || (selector.equals("value") && name.isBlank()));
            if (!matches) {
                continue;
            }
            if (selected != null) {
                throw new IllegalArgumentException(location + " declares multiple @Convert mappings for "
                        + (selector == null ? "the attribute" : "map " + selector));
            }
            selected = candidate;
        }
        if (selected == null || selected.disableConversion()) {
            return null;
        }
        if (selected.converter() != jakarta.persistence.AttributeConverter.class) {
            Class<?>[] types = resolveJpaConverterTypeArguments(selected.converter());
            Class<?> target = wrapPrimitiveType(types[0]);
            if (target != wrapPrimitiveType(attributeType)) {
                throw new IllegalArgumentException(location + " @Convert converter " + selected.converter().getName()
                        + " targets " + target.getName() + " but the mapped type is " + attributeType.getName());
            }
            return new JpaConverterDescriptor(selected.converter(), target, wrapPrimitiveType(types[1]), false);
        }
        return uniqueJpaConverter(attributeType, false, location);
    }

    private boolean conversionDisabled(Field field, String selector) {
        return conversionDisabled(field, selector, null);
    }

    private boolean conversionDisabled(Field field, String selector, Convert override) {
        Convert[] candidates = override == null ? memberAnnotations(field, Convert.class) : new Convert[]{override};
        for (Convert convert : candidates) {
            String name = convert.attributeName();
            boolean matches = override != null || (selector == null
                    ? name.isBlank()
                    : name.equals(selector) || (selector.equals("value") && name.isBlank()));
            if (matches && convert.disableConversion()) {
                return true;
            }
        }
        return false;
    }

    private static boolean conversionDisabled(Method getter, String selector, Convert override) {
        Convert[] candidates = override == null ? getter.getAnnotationsByType(Convert.class) : new Convert[]{override};
        for (Convert convert : candidates) {
            String name = convert.attributeName();
            boolean matches = override != null || (selector == null
                    ? name.isBlank()
                    : name.equals(selector) || (selector.equals("value") && name.isBlank()));
            if (matches && convert.disableConversion()) {
                return true;
            }
        }
        return false;
    }

    private JpaConverterDescriptor uniqueJpaConverter(
            Class<?> attributeType, boolean autoOnly, String location) {
        List<JpaConverterDescriptor> candidates = jpaConverters
                .getOrDefault(wrapPrimitiveType(attributeType), List.of()).stream()
                .filter(candidate -> !autoOnly || candidate.autoApply())
                .toList();
        if (candidates.size() > 1) {
            throw new IllegalArgumentException(location + " has multiple applicable JPA converters for "
                    + attributeType.getName() + "; use @Convert(converter=...) to select one");
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static jakarta.persistence.AttributeConverter<Object, Object> instantiateJpaConverter(
            Class<?> converterClass) {
        try {
            var constructor = converterClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (jakarta.persistence.AttributeConverter<Object, Object>) constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                    "@Convert converter " + converterClass.getName()
                            + " must have an accessible no-arg constructor", exception);
        }
    }

    /**
     * primitive 타입을 대응하는 boxed wrapper로 바꾼다(@Convert 속성 타입 호환 비교용). primitive가 아니면
     * 그대로 반환한다.
     */
    private static Class<?> wrapPrimitiveType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    /**
     * 같은 필드에 관계 어노테이션과 양립 불가능한 다른 어노테이션이 함께 선언된 경우를 거부한다.
     * 검증은 {@link OneToMany}/{@link ManyToOne} 한쪽이라도 존재할 때만 수행한다.
     */
    private static void rejectIncompatibleRelationAnnotations(Class<?> entityType, Field field) {
        boolean isManyToOne = memberPresent(field, ManyToOne.class);
        boolean isOneToMany = memberPresent(field, OneToMany.class);
        boolean isOneToOne = memberPresent(field, OneToOne.class);
        boolean isManyToMany = memberPresent(field, ManyToMany.class);
        int relationCount = (isManyToOne ? 1 : 0) + (isOneToMany ? 1 : 0)
                + (isOneToOne ? 1 : 0) + (isManyToMany ? 1 : 0);
        if (relationCount == 0) {
            return;
        }
        String location = entityType.getName() + "." + field.getName();
        if (relationCount > 1) {
            throw new IllegalStateException(
                    location + " cannot declare more than one of @ManyToOne / @OneToMany / @OneToOne / @ManyToMany");
        }
        if (memberPresent(field, Embedded.class)) {
            throw new IllegalStateException(location + " cannot declare @Embedded together with a relation annotation");
        }
        if (memberPresent(field, Id.class)) {
            throw new IllegalStateException(location + " cannot declare @Id together with a relation annotation");
        }
        if (memberPresent(field, Version.class)) {
            throw new IllegalStateException(location + " cannot declare @Version together with a relation annotation");
        }
        if (memberPresent(field, SoftDelete.class)) {
            throw new IllegalStateException(location + " cannot declare @SoftDelete together with a relation annotation");
        }
        if (memberPresent(field, CreatedAt.class)) {
            throw new IllegalStateException(location + " cannot declare @CreatedAt together with a relation annotation");
        }
        if (memberPresent(field, UpdatedAt.class)) {
            throw new IllegalStateException(location + " cannot declare @UpdatedAt together with a relation annotation");
        }
        if (memberPresent(field, Enumerated.class)) {
            throw new IllegalStateException(location + " cannot declare @Enumerated together with a relation annotation");
        }
        if (memberPresent(field, Json.class)) {
            throw new IllegalStateException(location + " cannot declare @Json together with a relation annotation");
        }
        if (memberPresent(field, MapsId.class) && (isOneToMany || isManyToMany)) {
            throw new IllegalStateException(
                    location + " @MapsId is only valid on a to-one relationship (@OneToOne/@ManyToOne),"
                            + " not on @OneToMany/@ManyToMany");
        }
        if (memberPresent(field, jakarta.persistence.OrderColumn.class)) {
            if (isManyToOne || isOneToOne) {
                throw new IllegalStateException(
                        location + " @OrderColumn is only valid on an ordered List collection,"
                                + " not on a single-valued @ManyToOne/@OneToOne relationship");
            }
            // @ManyToMany의 순서 컬럼은 link 테이블에 위치하며, 그 테이블은 양측 ownership과 별도 DDL 경로를
            // 가지므로 아직 지원하지 않는다. @OneToMany(mappedBy)는 child 테이블에 순서 컬럼을 두는 지원 경로로
            // 흐른다(createOneToManyProperty). @ElementCollection List는 collection table에 둔다.
            if (isManyToMany) {
                throw new IllegalStateException(
                        location + " @OrderColumn on @ManyToMany is not supported;"
                                + " @OrderColumn is supported on @ElementCollection List and @OneToMany(mappedBy) List");
            }
        }
    }

    private static void rejectIncompatibleRelationAnnotations(
            Class<?> entityType, PersistentAttributeAccess attribute) {
        boolean manyToOne = attribute.isAnnotationPresent(ManyToOne.class);
        boolean oneToMany = attribute.isAnnotationPresent(OneToMany.class);
        boolean oneToOne = attribute.isAnnotationPresent(OneToOne.class);
        boolean manyToMany = attribute.isAnnotationPresent(ManyToMany.class);
        int count = (manyToOne ? 1 : 0) + (oneToMany ? 1 : 0) + (oneToOne ? 1 : 0) + (manyToMany ? 1 : 0);
        if (count == 0) return;
        String location = entityType.getName() + "." + attribute.name();
        if (count > 1) throw new IllegalStateException(location + " cannot declare more than one relation annotation");
        for (Class<? extends Annotation> incompatible : List.of(
                Embedded.class, Id.class, Version.class, SoftDelete.class, CreatedAt.class, UpdatedAt.class,
                Enumerated.class, Json.class)) {
            if (attribute.isAnnotationPresent(incompatible)) {
                throw new IllegalStateException(location + " cannot declare @" + incompatible.getSimpleName()
                        + " together with a relation annotation");
            }
        }
        if (attribute.isAnnotationPresent(MapsId.class) && (oneToMany || manyToMany)) {
            throw new IllegalStateException(location + " @MapsId is only valid on a to-one relationship");
        }
        if (attribute.isAnnotationPresent(jakarta.persistence.OrderColumn.class)) {
            if (manyToOne || oneToOne) {
                throw new IllegalStateException(
                        location + " @OrderColumn is only valid on an ordered List collection,"
                                + " not on a single-valued @ManyToOne/@OneToOne relationship");
            }
            if (manyToMany) {
                throw new IllegalStateException(
                        location + " @OrderColumn on @ManyToMany is not supported;"
                                + " @OrderColumn is supported on @ElementCollection List"
                                + " and @OneToMany(mappedBy) List");
            }
        }
    }

    /**
     * Relationship annotations cannot be harmlessly ignored on the inactive access side: doing so
     * would silently map an entity reference as a basic column. Basic annotations on the inactive
     * side are deliberately ignored, but relations are rejected with an actionable error.
     */
    private static void rejectInactiveRelationAnnotations(Class<?> entityType, Field field) {
        AnnotatedElement inactive = resolvePropertyAccess(field) ? field : findPropertyGetter(field);
        if (inactive == null) {
            return;
        }
        for (Class<? extends Annotation> relation : List.of(
                OneToMany.class, ManyToOne.class, OneToOne.class, ManyToMany.class, ElementCollection.class)) {
            if (inactive.isAnnotationPresent(relation)) {
                throw new IllegalArgumentException(entityType.getName() + "." + field.getName()
                        + " declares @" + relation.getSimpleName() + " on the inactive "
                        + (resolvePropertyAccess(field) ? "field" : "getter") + " access side");
            }
        }
    }

    private static void rejectInactiveRelationAnnotations(Class<?> entityType, PersistentAttributeAccess attribute) {
        // The resolver has already selected the active member.  Inactive members are validated while
        // constructing that plan; this factory must not reselect a field/getter pair.
    }

    /**
     * FK 제약이 의미를 가질 수 없는 위치에 명시적 {@code @ForeignKey}(= {@code value != PROVIDER_DEFAULT})가
     * 붙은 경우를 fail-fast로 거부한다 — 조용히 무시하면 사용자가 FK 커스터마이즈가 적용된 것으로 오인한다.
     * <ul>
     *   <li>{@code @JoinColumn(foreignKey=...)}는 owning {@code @ManyToOne}/{@code @OneToOne}에서만 honor.</li>
     *   <li>{@code @JoinTable}의 {@code foreignKey}/{@code inverseForeignKey}는 {@code @ManyToMany}에서만 honor.</li>
     *   <li>{@code @CollectionTable(foreignKey=...)}는 {@code @ElementCollection}에서만 honor.</li>
     * </ul>
     */
    private static void rejectMisplacedForeignKey(Class<?> entityType, Field field) {
        String location = entityType.getName() + "." + field.getName();
        boolean toOne = memberPresent(field, ManyToOne.class) || memberPresent(field, OneToOne.class);
        JoinColumn joinColumn = memberAnnotation(field, JoinColumn.class);
        if (joinColumn != null && isExplicitForeignKey(joinColumn.foreignKey()) && !toOne) {
            throw new IllegalStateException(
                    location + " @JoinColumn(foreignKey=...) is only honored on an owning"
                            + " @ManyToOne/@OneToOne relationship");
        }
        JoinTable joinTable = memberAnnotation(field, JoinTable.class);
        if (joinTable != null
                && (isExplicitForeignKey(joinTable.foreignKey())
                        || isExplicitForeignKey(joinTable.inverseForeignKey()))
                && !memberPresent(field, ManyToMany.class)) {
            throw new IllegalStateException(
                    location + " @JoinTable foreign key customization is only honored on a @ManyToMany relationship");
        }
        CollectionTable collectionTable = memberAnnotation(field, CollectionTable.class);
        if (collectionTable != null && isExplicitForeignKey(collectionTable.foreignKey())
                && !memberPresent(field, ElementCollection.class)) {
            throw new IllegalStateException(
                    location + " @CollectionTable(foreignKey=...) is only honored on an @ElementCollection");
        }
    }

    private static void rejectMisplacedForeignKey(Class<?> entityType, PersistentAttributeAccess attribute) {
        String location = entityType.getName() + "." + attribute.name();
        boolean toOne = attribute.isAnnotationPresent(ManyToOne.class) || attribute.isAnnotationPresent(OneToOne.class);
        JoinColumn joinColumn = attribute.annotation(JoinColumn.class);
        if (joinColumn != null && isExplicitForeignKey(joinColumn.foreignKey()) && !toOne) {
            throw new IllegalStateException(location + " @JoinColumn(foreignKey=...) is only honored on an owning to-one");
        }
        JoinTable joinTable = attribute.annotation(JoinTable.class);
        if (joinTable != null && (isExplicitForeignKey(joinTable.foreignKey())
                || isExplicitForeignKey(joinTable.inverseForeignKey()))
                && !attribute.isAnnotationPresent(ManyToMany.class)) {
            throw new IllegalStateException(location + " @JoinTable foreign key customization is only honored on @ManyToMany");
        }
        CollectionTable collectionTable = attribute.annotation(CollectionTable.class);
        if (collectionTable != null && isExplicitForeignKey(collectionTable.foreignKey())
                && !attribute.isAnnotationPresent(ElementCollection.class)) {
            throw new IllegalStateException(location + " @CollectionTable foreign key customization is only honored on @ElementCollection");
        }
    }

    /**
     * 사용자가 {@code @ForeignKey}로 FK 동작을 명시했는지 — {@code value()}가 {@code PROVIDER_DEFAULT}가
     * 아니면(= {@code CONSTRAINT}/{@code NO_CONSTRAINT}) 명시로 본다.
     */
    private static boolean isExplicitForeignKey(ForeignKey foreignKey) {
        return foreignKey != null && foreignKey.value() != ConstraintMode.PROVIDER_DEFAULT;
    }

    /**
     * {@link OneToMany} property를 만든다. parent 테이블 컬럼이 없으므로 column-related 메타데이터는 비워두고,
     * mappedBy와 target type을 보존한다. cascade나 orphanRemoval이 지정되면 {@link OneToManyInfo}로 캡처해
     * save/delete/flush 시 child 전파를 구동하고, 둘 다 없으면 {@code null}로 두어 기존 marker-only 동작을 보존한다.
     */
    private PersistentProperty createOneToManyProperty(Class<?> entityType, Field field) {
        RelationAccess access = resolveRelationAccess(field);
        OneToMany annotation = memberAnnotation(field, OneToMany.class);
        OrderColumnInfo orderColumn = resolveOneToManyOrderColumn(entityType, field);
        OneToManyInfo oneToManyInfo;
        if (annotation.cascade().length > 0 || annotation.orphanRemoval() || orderColumn != null) {
            oneToManyInfo = new OneToManyInfo(
                    Set.of(annotation.cascade()),
                    annotation.orphanRemoval(),
                    orderColumn);
        } else {
            oneToManyInfo = null;
        }
        String mappedBy = annotation.mappedBy();
        if (mappedBy == null || mappedBy.isBlank()) {
            throw new IllegalStateException(
                    entityType.getName() + "." + field.getName()
                            + " @OneToMany requires non-blank mappedBy");
        }
        Class<?> targetType = annotation.targetEntity();
        if (targetType == void.class) {
            targetType = collectionElementType(
                    entityType, field.getName(), selectedGenericType(field), OneToMany.class);
        }
        return new PersistentProperty(
                field,
                field.getName(),
                "", // no column for inverse side
                selectedAttribute(field).javaType(),
                false,
                false,
                true,
                255,
                0,
                0,
                null,
                "",
                null,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                null,
                false,
                false,
                null,
                true,
                true,
                targetType,
                mappedBy,
                true,
                true,
                false,
                "",
                false,
                null,
                false,
                null,
                null,
                oneToManyInfo,
                null,
                false,
                "",
                access.propertyAccess(),
                access.getter(),
                access.setter(),
                null,
                "",
                null,
                ColumnDdlDefinition.EMPTY
        );
    }

    private PersistentProperty createOneToManyProperty(
            Class<?> entityType, PersistentAttributeAccess attribute) {
        if (attribute.accessType() == AccessType.FIELD) {
            return createOneToManyProperty(entityType, attribute.field());
        }
        OneToMany annotation = attribute.annotation(OneToMany.class);
        String mappedBy = annotation.mappedBy();
        if (mappedBy == null || mappedBy.isBlank()) {
            throw new IllegalStateException(entityType.getName() + "." + attribute.name()
                    + " @OneToMany requires non-blank mappedBy");
        }
        Class<?> target = annotation.targetEntity() == void.class
                ? collectionElementType(entityType, attribute.name(), attribute.genericType(), OneToMany.class)
                : annotation.targetEntity();
        OrderColumnInfo orderColumn = resolveOneToManyOrderColumn(entityType, attribute.getter());
        OneToManyInfo info = annotation.cascade().length > 0 || annotation.orphanRemoval() || orderColumn != null
                ? new OneToManyInfo(Set.of(annotation.cascade()), annotation.orphanRemoval(), orderColumn)
                : null;
        return new PersistentProperty(attribute.field(), attribute.name(), "", attribute.javaType(),
                false, false, true,
                255, 0, 0, null, "", null, false, false, false, false, List.of(), false, null, false,
                false, null, true, true, target, mappedBy, true, true, false, "", false, null, false,
                null, null, info, null, false, "", attribute.accessType() == AccessType.PROPERTY,
                attribute.getter(), attribute.setter(), null, "", null,
                ColumnDdlDefinition.EMPTY);
    }

    /**
     * {@link ManyToOne} owning property를 만든다. FK 컬럼 이름은 {@link JoinColumn#name()} 또는
     * 기본 naming strategy로 {@code <propertyName>_id} 형태가 된다. javaType은 FK 컬럼이 보관하는
     * 식별자 타입이지만 target entity 메타데이터에 의존하지 않기 위해 일단 {@link Long}으로 fallback한다 —
     * mapRow는 이 property를 직접 read/write하지 않으므로(관계는 FetchGroup이 채워준다) javaType 정확도가
     * row decoding에 영향을 주지 않는다.
     */
    /**
     * owning to-one 관계 필드({@code @ManyToOne}/owning {@code @OneToOne})의 {@code @MapsId} 마커를 해석한다.
     * {@code @MapsId}가 없으면 {@code null}(비파생). 두 형태를 지원한다:
     * <ul>
     *   <li>단순 {@code @MapsId}(빈 value): 파생 대상은 owner의 단일 {@code @Id} 전체(shared primary key).
     *       마커는 빈 문자열({@code ""})이다.</li>
     *   <li>{@code @MapsId("component")}: owner가 복합 {@code @Id}({@code @EmbeddedId}/{@code @IdClass})일 때
     *       named 컴포넌트를 연관 엔티티 PK에서 파생한다. 마커는 컴포넌트 이름이다. 복합키 relation target
     *       (다중컬럼 FK)이 착지한 뒤이므로 이제 컴포넌트↔FK 매핑을 활용해 파생할 수 있다.</li>
     * </ul>
     * 다음은 fail-fast로 거부한다(조용한 무시 금지): {@code @MapsId("component")}인데 owner가 복합 {@code @Id}가
     * 아니거나 named 컴포넌트가 존재하지 않는 경우, 단순 {@code @MapsId}인데 owner가 단일 {@code @Id}가 아닌 경우,
     * 파생 대상 {@code @Id}(또는 named 컴포넌트)가 {@code @GeneratedValue}로 생성되는 경우(파생 식별자는
     * application/연관-PK가 채우므로 양립 불가).
     */
    private static String resolveMapsIdMarker(Class<?> entityType, Field field) {
        return resolveMapsIdMarker(entityType, selectedAttribute(field));
    }

    private static String resolveMapsIdMarker(Class<?> entityType, PersistentAttributeAccess relation) {
        MapsId mapsId = relation.annotation(MapsId.class);
        if (mapsId == null) {
            return null;
        }
        String location = entityType.getName() + "." + relation.name();
        Class<?> relationTarget = relation.javaType();
        ManyToOne manyToOne = relation.annotation(ManyToOne.class);
        if (manyToOne != null && manyToOne.targetEntity() != void.class) {
            relationTarget = manyToOne.targetEntity();
        }
        OneToOne oneToOne = relation.annotation(OneToOne.class);
        if (oneToOne != null && oneToOne.targetEntity() != void.class) {
            relationTarget = oneToOne.targetEntity();
        }
        String value = mapsId.value();
        // 파생 대상 @Id 구조 파악: top-level @Id 필드들과 @EmbeddedId holder를 수집한다.
        List<PersistentAttributeAccess> identifiers = selectedIdentifierAttributes(entityType);
        PersistentAttributeAccess embeddedId = identifiers.stream()
                .filter(attribute -> attribute.isAnnotationPresent(EmbeddedId.class))
                .findFirst().orElse(null);
        List<PersistentAttributeAccess> idFields = identifiers.stream()
                .filter(attribute -> attribute.isAnnotationPresent(Id.class)).toList();
        boolean composite = embeddedId != null
                || entityType.isAnnotationPresent(jakarta.persistence.IdClass.class)
                || idFields.size() > 1;
        if (embeddedId != null && embeddedId.javaType().isRecord()) {
            throw new IllegalArgumentException(location
                    + " uses @MapsId with record @EmbeddedId " + embeddedId.javaType().getName()
                    + "; immutable record identifiers cannot be derived by mutation");
        }
        if (value != null && !value.isBlank()) {
            // @MapsId("component"): owner는 복합 @Id여야 하고 named 컴포넌트가 존재해야 한다.
            if (!composite) {
                throw new IllegalArgumentException(
                        location
                                + " @MapsId(\"" + value + "\") names an id component but the entity does not declare a"
                                + " composite @Id (@EmbeddedId/@IdClass); use a simple @MapsId to derive the single @Id");
            }
            if (!compositeIdComponentExists(embeddedId, idFields, value)) {
                throw new IllegalArgumentException(
                        location
                                + " @MapsId(\"" + value + "\") does not match any component of the composite @Id of "
                                + entityType.getName());
            }
            if (compositeIdComponentGenerated(embeddedId, idFields, value)) {
                throw new IllegalArgumentException(
                        location
                                + " @MapsId(\"" + value + "\") cannot be combined with @GeneratedValue on the id"
                                + " component; a derived identifier is supplied by the associated entity's primary key");
            }
            // 파생 값은 연관 엔티티의 PK 전체(readIdValue)에서 온다. 그 타겟이 복합 @Id면 어느 컴포넌트를
            // owner의 어느 컴포넌트로 매핑할지 모호하고 holder 객체를 스칼라 컬럼에 쓰려다 런타임에 던진다.
            // 조용한 런타임 실패 대신 build 시점에 명확히 거부한다(단일 @Id 타겟만 지원).
            if (declaresCompositeId(relationTarget)) {
                throw new IllegalArgumentException(
                        location
                                + " @MapsId(\"" + value + "\") derives an id component from "
                                + relationTarget.getName() + ", but deriving from a composite-key associated"
                                + " entity is not supported; the associated entity must declare a single @Id");
            }
            return value;
        }
        // 단순 @MapsId: 정확히 하나의 단일 @Id 필드여야 하고 @GeneratedValue가 없어야 한다.
        if (composite) {
            throw new IllegalArgumentException(
                    location
                            + " @MapsId requires the owning entity to declare exactly one single @Id"
                            + " (a composite @Id requires @MapsId(\"component\") to derive one named component)");
        }
        if (idFields.size() != 1) {
            throw new IllegalArgumentException(
                    location
                            + " @MapsId requires the owning entity to declare exactly one single @Id"
                            + " (composite keys are not supported)");
        }
        if (idFields.get(0).isAnnotationPresent(GeneratedValue.class)) {
            throw new IllegalArgumentException(
                    location
                            + " @MapsId cannot be combined with @GeneratedValue on the @Id;"
                            + " a derived identifier is supplied by the associated entity's primary key");
        }
        return "";
    }

    /**
     * {@code type}이 복합 {@code @Id}({@code @EmbeddedId}/{@code @IdClass}/다중 top-level {@code @Id})를
     * 선언하는지 검사한다. {@code @MapsId("component")}의 파생 대상(연관 엔티티)이 단일 {@code @Id}인지
     * build 시점에 확인하는 데 쓴다.
     */
    private static boolean declaresCompositeId(Class<?> type) {
        if (type.isAnnotationPresent(jakarta.persistence.IdClass.class)) {
            return true;
        }
        List<PersistentAttributeAccess> identifiers = selectedIdentifierAttributes(type);
        return identifiers.stream().anyMatch(attribute -> attribute.isAnnotationPresent(EmbeddedId.class))
                || identifiers.stream().filter(attribute -> attribute.isAnnotationPresent(Id.class)).count() > 1;
    }

    /**
     * 복합 {@code @Id}에 {@code name} 컴포넌트가 존재하는지 검사한다. {@code @EmbeddedId}는 {@code @Embeddable}의
     * leaf 필드 이름을, {@code @IdClass}(또는 top-level 다중 {@code @Id})는 top-level {@code @Id} 필드 이름을 본다.
     */
    private static boolean compositeIdComponentExists(
            PersistentAttributeAccess embeddedId, List<PersistentAttributeAccess> idFields, String name) {
        if (embeddedId != null) {
            for (PersistentAttributeAccess component
                    : embeddedComponentAttributes(embeddedId.javaType(), embeddedId.accessType())) {
                if (component.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }
        for (PersistentAttributeAccess idField : idFields) {
            if (idField.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 복합 {@code @Id}의 {@code name} 컴포넌트가 {@code @GeneratedValue}로 표시되어 있으면 {@code true}.
     */
    private static boolean compositeIdComponentGenerated(
            PersistentAttributeAccess embeddedId, List<PersistentAttributeAccess> idFields, String name) {
        if (embeddedId != null) {
            for (PersistentAttributeAccess component
                    : embeddedComponentAttributes(embeddedId.javaType(), embeddedId.accessType())) {
                if (component.name().equals(name)) {
                    return component.isAnnotationPresent(GeneratedValue.class);
                }
            }
            return false;
        }
        for (PersistentAttributeAccess idField : idFields) {
            if (idField.name().equals(name)) {
                return idField.isAnnotationPresent(GeneratedValue.class);
            }
        }
        return false;
    }

    /**
     * 관계 필드({@code @ManyToOne}/{@code @OneToOne})의 effective access 전략을 해석한다. 클래스레벨/멤버레벨
     * {@code @Access(PROPERTY)}면 basic property와 동일하게 JavaBean getter/setter를 resolve하고(없으면 fail-fast),
     * FIELD면 accessor 없이 field 접근을 유지한다. basic property가 쓰는
     * {@link #resolvePropertyAccess}/{@link #resolvePropertyGetter}/{@link #resolvePropertySetter}를 그대로 재사용해
     * 관계와 basic의 access 규칙을 대칭으로 만든다.
     */
    private static RelationAccess resolveRelationAccess(Field field) {
        if (!resolvePropertyAccess(field)) {
            return RelationAccess.FIELD;
        }
        return new RelationAccess(true, resolvePropertyGetter(field), resolvePropertySetter(field));
    }

    /**
     * 관계 property의 access 전략 해석 결과. {@code propertyAccess}가 {@code false}이면 getter/setter는 {@code null}
     * (FIELD 접근), {@code true}이면 둘 다 non-null(PROPERTY 접근)이다.
     */
    private record RelationAccess(boolean propertyAccess, Method getter, Method setter) {
        static final RelationAccess FIELD = new RelationAccess(false, null, null);
    }

    /**
     * to-one FK({@code @ManyToOne} / owning {@code @OneToOne}) 컬럼이 참조 대상의 <em>단일 {@code @Id} 저장 표현</em>을
     * 그대로 반영하도록 FK property의 javaType/converter/converterColumnType/length를 해석한다. 전체 대상
     * 메타데이터를 빌드하지 않고(빌드 순서/순환 회피) 대상 클래스에서 단일 {@code @Id} 필드를 리플렉션으로
     * 찾아 스칼라 저장타입 규칙을 그대로 재사용한다:
     * <ul>
     *   <li>{@code @Id}에 {@code @Convert}가 있으면 그 converter/저장타입(Y)을 반영</li>
     *   <li>{@code @Id}에 {@code @Enumerated}가 있으면 STRING→{@link String}/ORDINAL→{@link Integer} 저장타입 반영</li>
     *   <li>그 외 순수 기본 타입은 {@link #resolveBasicStorageMapping}과 동일 규칙
     *       (UUID→varchar(String) via {@link UuidStringConverter}, Integer→integer, String→varchar, Short→smallint 등)</li>
     * </ul>
     * 단일 {@code @Id}가 아니면(복합키 {@code @EmbeddedId}/{@code @IdClass}) {@code null}을 돌려주고 호출부가 기존
     * {@link Long} 기본값을 유지한다 — 복합키 to-one 참조는 확장하지 않고 기존 동작/실패 경로를 보존한다.
     * <p>
     * {@code @Id} 탐색은 read/write 경로({@link PersistentProperty#writeManyToOneStub}의 {@code findIdField},
     * {@code extractReferencedId})와 <b>동일하게</b> 대상 자신의 필드를 본 뒤 {@code @MappedSuperclass}/상위
     * {@code @Entity} 조상 체인을 walk한다({@link #mappedFields} 규칙). 이렇게 해야 helper가 non-Long으로 해석한
     * FK 타입을 read-path가 반드시 바인딩/디코드할 수 있고, {@code @Id}가 {@code @MappedSuperclass}에서 상속된
     * 단일 키여도 양쪽이 함께 그 상속 {@code @Id}를 찾아 저장 표현이 일치한다. 조상까지 walk해도 단일 {@code @Id}가
     * 아니면(복합키 {@code @EmbeddedId}/{@code @IdClass}) {@code null}로 떨어져 {@code Long} 폴백을 유지한다.
     */
    private static ForeignKeyStorage resolveToOneForeignKeyStorage(Class<?> targetType) {
        List<PersistentAttributeAccess> ids = selectedIdentifierAttributes(targetType).stream()
                .filter(attribute -> attribute.isAnnotationPresent(Id.class)).toList();
        if (ids.size() != 1) {
            return null;
        }
        return resolveScalarStorage(ids.get(0));
    }

    private static List<PersistentAttributeAccess> selectedIdentifierAttributes(Class<?> type) {
        return new PersistentAccessResolver().resolve(type).attributes().stream()
                .filter(attribute -> attribute.isAnnotationPresent(Id.class)
                        || attribute.isAnnotationPresent(EmbeddedId.class))
                .toList();
    }

    private static ForeignKeyStorage resolveScalarStorage(PersistentAttributeAccess id) {
        Class<?> domainType = wrapPrimitiveType(id.javaType());
        Column column = id.annotation(Column.class);
        int length = column == null ? 255 : column.length();
        Convert convert = id.annotation(Convert.class);
        if (convert != null && !convert.disableConversion()) {
            Class<?>[] attributeAndColumn = resolveJpaConverterTypeArguments(convert.converter());
            AttributeConverter<Object, Object> converter =
                    new JpaAttributeConverterAdapter<>(instantiateJpaConverter(convert.converter()));
            return new ForeignKeyStorage(domainType, converter, attributeAndColumn[1], length);
        }
        Enumerated enumerated = id.annotation(Enumerated.class);
        if (enumerated != null || id.javaType().isEnum()) {
            EnumType enumType = enumerated == null ? inferredEnumType(id.javaType()) : enumerated.value();
            EnumMapping mapping = resolveEnumMapping(id.javaType(), enumType);
            Class<?> storageType = mapping.customColumnType() != null ? mapping.customColumnType()
                    : enumType == EnumType.STRING ? String.class : Integer.class;
            return new ForeignKeyStorage(domainType, mapping.converter(), storageType, length);
        }
        ElementValueMapping basic = resolveBasicStorageMapping(domainType);
        return new ForeignKeyStorage(domainType, basic.converter(),
                basic.converter() == null ? null : basic.columnType(), length);
    }

    /**
     * 단일 스칼라 {@code @Id} 컴포넌트 필드의 FK 저장 표현을 해석한다. 단일키 타겟과 복합키 타겟의 각 컴포넌트가
     * 이 한 자리를 공유해 {@code @Convert}/{@code @Enumerated}/UUID 등 저장타입 규칙이 대칭이 되게 한다.
     * {@code owner}는 {@code @Convert} 제네릭 인자 해석에 쓰이는 필드 선언 타입(단일키는 target 엔티티,
     * {@code @EmbeddedId} 컴포넌트는 {@code @Embeddable} 타입)이다.
     */
    /**
     * to-one FK property의 저장 표현 해석 결과. {@code javaType}은 참조 {@code @Id}의 도메인 타입,
     * {@code converter}/{@code converterColumnType}은 저장 표현(널이면 도메인 타입을 그대로 저장), {@code length}는
     * varchar 계열 컬럼 길이(참조 {@code @Id}의 {@code @Column(length)}, 기본 255)다.
     */
    private record ForeignKeyStorage(Class<?> javaType, AttributeConverter<Object, Object> converter,
            Class<?> converterColumnType, int length) {
    }

    /**
     * 참조 엔티티의 복합키({@code @EmbeddedId}/{@code @IdClass}) 컴포넌트 하나에 대응하는 서술자. FK 컬럼명 해석,
     * DDL/read/write에 필요한 저장타입/converter/길이/참조 컬럼명/참조 경로를 담는다.
     */
    private record ReferencedIdComponent(String referencedColumnName, Class<?> domainType,
            AttributeConverter<Object, Object> converter, Class<?> converterColumnType, int length,
            List<PersistentAttributeAccess> referencedPath) {
    }

    /**
     * to-one({@code @ManyToOne}/owning {@code @OneToOne})이 복합키 엔티티를 참조할 때의 다중컬럼 FK 모델을
     * 해석한다. 참조 엔티티의 각 {@code @Id} 컴포넌트마다 FK 컬럼 1개를 <b>참조 컴포넌트 순서대로</b> 만든다.
     * 참조가 복합키가 아니거나(단일키/키 없음) 해석 불가하면 {@code null}을 반환해 호출부가 기존 단일 FK/Long
     * 폴백을 유지하게 한다.
     *
     * <p>FK 컬럼명은 {@code @JoinColumns}(복수 {@code @JoinColumn})가 있으면 그것을 honor하고(전부
     * {@code referencedColumnName} 지정 시 참조 컬럼명 매칭, 전부 미지정 시 위치 매칭), 없으면 기본
     * {@code <property>_<referencedColumn>} 규칙으로 만든다.
     */
    private ToOneForeignKey resolveCompositeToOneForeignKey(
            Class<?> entityType, Class<?> targetType, PersistentAttributeAccess attribute) {
        List<ReferencedIdComponent> components = resolveReferencedIdComponents(targetType);
        if (components == null || components.size() < 2) {
            return null;
        }
        JoinColumn[] perComponent = alignJoinColumns(entityType, attribute, components);
        ManyToOne manyToOne = attribute.annotation(ManyToOne.class);
        OneToOne oneToOne = attribute.annotation(OneToOne.class);
        boolean relationOptional = manyToOne != null ? manyToOne.optional() : (oneToOne == null || oneToOne.optional());
        List<ToOneForeignKeyColumn> fkColumns = new ArrayList<>(components.size());
        for (int i = 0; i < components.size(); i++) {
            ReferencedIdComponent component = components.get(i);
            JoinColumn joinColumn = perComponent[i];
            String fkColumnName = joinColumn != null && !joinColumn.name().isBlank()
                    ? joinColumn.name()
                    : namingStrategy.columnName(attribute.name() + "_" + component.referencedColumnName());
            boolean nullable = relationOptional && (joinColumn == null || joinColumn.nullable());
            Class<?> columnType = component.converterColumnType() != null
                    ? component.converterColumnType() : component.domainType();
            fkColumns.add(new ToOneForeignKeyColumn(
                    fkColumnName,
                    component.referencedColumnName(),
                    columnType,
                    component.length(),
                    nullable,
                    component.converter(),
                    component.referencedPath()));
        }
        return new ToOneForeignKey(fkColumns, targetType);
    }

    /**
     * 참조 엔티티 타입의 복합키 {@code @Id} 컴포넌트들을 참조 순서대로 서술한다. {@code @EmbeddedId}는
     * {@code @Embeddable} leaf 필드들을(host 필드의 {@code @AttributeOverride} 컬럼명 재정의 반영),
     * {@code @IdClass}는 top-level {@code @Id} 필드들을 매핑한다. 컬럼명/순서/저장타입은 참조 엔티티가 그 자신의
     * 메타데이터를 만들 때와 동일 규칙({@code mappedFields} root-first, {@code getDeclaredFields} 순서)을 써서
     * read/write/DDL/FK가 참조 테이블의 {@code @Id} 컬럼과 정확히 정렬되게 한다. 복합키가 아니거나 nested
     * embedded 컴포넌트 등 미지원이면 {@code null}.
     */
    private List<ReferencedIdComponent> resolveReferencedIdComponents(Class<?> targetType) {
        List<PersistentAttributeAccess> identifiers = selectedIdentifierAttributes(targetType);
        PersistentAttributeAccess embeddedId = identifiers.stream()
                .filter(attribute -> attribute.isAnnotationPresent(EmbeddedId.class))
                .findFirst().orElse(null);
        List<PersistentAttributeAccess> topLevelIds = identifiers.stream()
                .filter(attribute -> attribute.isAnnotationPresent(Id.class)).toList();
        if (embeddedId != null) {
            Class<?> embeddableType = embeddedId.javaType();
            if (!embeddableType.isAnnotationPresent(Embeddable.class)) {
                return null;
            }
            Map<String, String> columnOverrides = new java.util.HashMap<>();
            for (AttributeOverride override : embeddedId.annotationsByType(AttributeOverride.class)) {
                columnOverrides.put(override.name(), override.column().name());
            }
            List<ReferencedIdComponent> result = new ArrayList<>();
            for (PersistentAttributeAccess component
                    : embeddedComponentAttributes(embeddableType, embeddedId.accessType())) {
                if (component.isAnnotationPresent(Embedded.class) || component.isAnnotationPresent(EmbeddedId.class)) {
                    // nested embedded @EmbeddedId 컴포넌트는 미지원 — 조용한 오매핑 대신 확장하지 않는다.
                    return null;
                }
                String override = columnOverrides.get(component.name());
                String columnName = override != null && !override.isBlank() ? override : columnNameOf(component);
                ForeignKeyStorage storage = resolveScalarStorage(component);
                result.add(new ReferencedIdComponent(columnName, storage.javaType(), storage.converter(),
                        storage.converterColumnType(), storage.length(), List.of(embeddedId, component)));
            }
            return result.isEmpty() ? null : result;
        }
        if (topLevelIds.size() >= 2) {
            List<ReferencedIdComponent> result = new ArrayList<>(topLevelIds.size());
            for (PersistentAttributeAccess id : topLevelIds) {
                ForeignKeyStorage storage = resolveScalarStorage(id);
                result.add(new ReferencedIdComponent(columnNameOf(id), storage.javaType(), storage.converter(),
                        storage.converterColumnType(), storage.length(), List.of(id)));
            }
            return result;
        }
        return null;
    }

    /**
     * 스칼라 {@code @Id}/{@code @EmbeddedId} 컴포넌트 필드의 컬럼명을 해석한다({@code createProperty}와 동일 규칙:
     * {@code @Column(name)}이 있으면 그 이름, 없으면 naming strategy).
     */
    private String columnNameOf(PersistentAttributeAccess attribute) {
        Column column = attribute.annotation(Column.class);
        return column != null && !column.name().isBlank()
                ? column.name()
                : namingStrategy.columnName(attribute.name());
    }

    /**
     * 복합키 to-one의 {@code @JoinColumns}(또는 단일 {@code @JoinColumn})를 참조 컴포넌트 순서에 맞춰 정렬한다.
     * join 컬럼이 없으면 전부 {@code null}(기본 이름 규칙 사용), 있으면 개수가 컴포넌트 수와 일치해야 하며
     * {@code referencedColumnName}은 전부 지정(참조명 매칭)하거나 전부 생략(위치 매칭)해야 한다. 위반 시 fail-fast.
     */
    private JoinColumn[] alignJoinColumns(
            Class<?> entityType, PersistentAttributeAccess attribute, List<ReferencedIdComponent> components) {
        JoinColumn[] result = new JoinColumn[components.size()];
        JoinColumn[] declared;
        JoinColumns container = attribute.annotation(JoinColumns.class);
        if (container != null) {
            declared = container.value();
        } else {
            JoinColumn single = attribute.annotation(JoinColumn.class);
            declared = single != null ? new JoinColumn[] {single} : new JoinColumn[0];
        }
        if (declared.length == 0) {
            return result;
        }
        String location = entityType.getName() + "." + attribute.name();
        if (declared.length != components.size()) {
            throw new IllegalArgumentException(location + " references composite-key entity with "
                    + components.size() + " id columns but declares " + declared.length
                    + " join column(s); one @JoinColumn per referenced @Id column is required");
        }
        for (JoinColumn joinColumn : declared) {
            if (joinColumn.name().isBlank()) {
                throw new IllegalArgumentException(location
                        + " composite-key @JoinColumn must set a non-blank name");
            }
        }
        int withReferenced = 0;
        for (JoinColumn joinColumn : declared) {
            if (!joinColumn.referencedColumnName().isBlank()) {
                withReferenced++;
            }
        }
        if (withReferenced == 0) {
            System.arraycopy(declared, 0, result, 0, declared.length);
            return result;
        }
        if (withReferenced != declared.length) {
            throw new IllegalArgumentException(location
                    + " composite-key @JoinColumn referencedColumnName must be either all specified or all omitted");
        }
        for (int i = 0; i < components.size(); i++) {
            String referenced = components.get(i).referencedColumnName();
            JoinColumn match = null;
            for (JoinColumn joinColumn : declared) {
                if (joinColumn.referencedColumnName().equals(referenced)) {
                    match = joinColumn;
                    break;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException(location
                        + " @JoinColumn referencedColumnName does not cover target @Id column \""
                        + referenced + "\"");
            }
            result[i] = match;
        }
        return result;
    }

    /**
     * concrete 엔티티 자신뿐 아니라 매핑에 기여하는 조상({@code @MappedSuperclass} / 상속 상위 {@code @Entity})에
     * 선언된 {@link AssociationOverride}(반복 애너테이션, 컨테이너 {@code @AssociationOverrides})까지 계층을
     * walk하여 수집한 뒤, {@code @MappedSuperclass}에서 상속한 이름이 일치하는 owning
     * to-one({@code @ManyToOne}/owning {@code @OneToOne}) property의 FK 컬럼명을 재지정한다. 관계 property는
     * 상속 필드 스캔으로 이미 {@code properties}에 조립돼 있으므로, {@code createManyToOneProperty} 본문을
     * 건드리지 않고 조립된 property를 {@link PersistentProperty#withColumnName(String)}로 교체하는
     * post-processing이다.
     *
     * <p>우선순위: 같은 {@code name}에 대해 더 파생된(서브클래스) 선언이 상위(중간 {@code @MappedSuperclass} 또는
     * 상속 상위 {@code @Entity}) 선언을 override한다. entityType부터 위로 올라가며 most-derived-first로 수집하고
     * {@code putIfAbsent}로 먼저 본(더 하위) 선언을 남긴다.
     *
     * <p>fail-fast: override가 존재하지 않는 property를 지목하거나 owning to-one이 아닌 property를 지목하면,
     * 또는 embedded association(dot 표기, 미지원)이나 다중/공백 join 컬럼을 지정하면 명확한 예외를 던진다.
     */
    private void applyAssociationOverrides(Class<?> entityType, List<PersistentProperty> properties) {
        // entityType 자신 + 매핑 기여 조상(@MappedSuperclass / 상속 상위 @Entity)을 most-derived-first로 walk한다.
        // 같은 name 충돌 시 먼저 본(더 하위) 선언이 이기도록 putIfAbsent로 수집한다.
        Map<String, AssociationOverride> collected = new LinkedHashMap<>();
        Class<?> current = entityType;
        while (current != null && current != Object.class
                && (current == entityType
                || current.isAnnotationPresent(MappedSuperclass.class)
                || current.isAnnotationPresent(Entity.class))) {
            for (AssociationOverride override : current.getDeclaredAnnotationsByType(AssociationOverride.class)) {
                String name = override.name();
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException(
                            current.getName() + " declares an @AssociationOverride with a blank name");
                }
                collected.putIfAbsent(name, override);
            }
            current = current.getSuperclass();
        }
        if (collected.isEmpty()) {
            return;
        }
        for (AssociationOverride override : collected.values()) {
            String name = override.name();
            // embedded association override(예: name="address.country")는 dot 표기를 쓴다. Nova는
            // embedded 안의 association을 매핑하지 않으므로 조용히 무시하지 않고 명확히 거부한다.
            if (name.contains(".")) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @AssociationOverride(name=\"" + name + "\") targets an embedded"
                                + " association path, which Nova does not support; only join columns of inherited"
                                + " @ManyToOne/@OneToOne associations can be overridden");
            }
            int index = -1;
            for (int i = 0; i < properties.size(); i++) {
                if (properties.get(i).propertyName().equals(name)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @AssociationOverride(name=\"" + name + "\") does not match any"
                                + " property; it must name an inherited @ManyToOne/@OneToOne association");
            }
            PersistentProperty target = properties.get(index);
            if (!target.manyToOne() || target.inverseToOne()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @AssociationOverride(name=\"" + name + "\") must target an owning"
                                + " @ManyToOne or @OneToOne association with a foreign-key column");
            }
            JoinColumn[] joinColumns = override.joinColumns();
            if (joinColumns.length == 0) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @AssociationOverride(name=\"" + name + "\") must declare a"
                                + " @JoinColumn to remap the foreign-key column");
            }
            if (joinColumns.length > 1) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @AssociationOverride(name=\"" + name + "\") declares multiple join"
                                + " columns; Nova supports single-column to-one foreign keys only");
            }
            String newColumnName = joinColumns[0].name();
            if (newColumnName == null || newColumnName.isBlank()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @AssociationOverride(name=\"" + name + "\") @JoinColumn must set a"
                                + " non-blank column name");
            }
            properties.set(index, target.withColumnName(newColumnName));
        }
    }

    private PersistentProperty createManyToOneProperty(Class<?> entityType, Field field) {
        // 관계 property의 effective access 전략을 basic property와 동일 규칙으로 해석한다. 클래스/멤버 레벨
        // @Access(PROPERTY)면 JavaBean getter/setter로 read/write하고(없으면 fail-fast), FIELD면 field 접근을 유지한다.
        RelationAccess access = resolveRelationAccess(field);
        ManyToOne manyToOne = memberAnnotation(field, ManyToOne.class);
        String mapsIdMarker = resolveMapsIdMarker(entityType, field);
        // fetch=LAZY는 그대로 수용한다(no-op): Nova는 lazy proxy가 없어 관계는 findById에서
        // 자동 fetch되지 않고 FetchGroup을 명시 구동할 때만 hydration된다. 따라서 EAGER와 LAZY는
        // 런타임에서 동일하게 동작한다(둘 다 구동 전엔 null, FK 컬럼은 정상 persist).
        ToOneCascadeInfo toOneCascadeInfo = manyToOne.cascade().length > 0
                ? new ToOneCascadeInfo(Set.of(manyToOne.cascade()))
                : null;
        JoinColumn joinColumn = memberAnnotation(field, JoinColumn.class);
        Class<?> targetType = manyToOne.targetEntity();
        if (targetType == void.class) {
            targetType = field.getType();
        }
        String columnName;
        if (joinColumn != null && !joinColumn.name().isBlank()) {
            columnName = joinColumn.name();
        } else {
            columnName = namingStrategy.columnName(field.getName() + "_id");
        }
        boolean nullable = manyToOne.optional() && (joinColumn == null || joinColumn.nullable());
        boolean fkInsertable = joinColumn == null || joinColumn.insertable();
        boolean fkUpdatable = joinColumn == null || joinColumn.updatable();
        boolean fkUnique = joinColumn != null && joinColumn.unique();
        String fkColumnDefinition = joinColumn == null ? "" : joinColumn.columnDefinition();
        // FK 컬럼 타입/값 인코딩을 참조 대상의 단일 @Id 저장 표현에 맞춘다. 해석 불가(복합키 등)면 기존 Long 기본값.
        ForeignKeyStorage fkStorage = resolveToOneForeignKeyStorage(targetType);
        Class<?> fkJavaType = fkStorage != null ? fkStorage.javaType() : Long.class;
        AttributeConverter<Object, Object> fkConverter = fkStorage != null ? fkStorage.converter() : null;
        Class<?> fkConverterColumnType = fkStorage != null ? fkStorage.converterColumnType() : null;
        int fkLength = fkStorage != null ? fkStorage.length() : 255;
        // 단일 @Id 해석 불가(복합키 @EmbeddedId/@IdClass)면 다중컬럼 FK 모델로 확장한다. 복합 FK가 있으면
        // 단일 columnName 대신 그 첫 FK 컬럼명을 대표로 두고(단일컬럼 접근자/uniqueness 표기 결정성 유지),
        // 실제 N개 컬럼 emit/바인딩/디코드는 toOneForeignKey가 담당한다. 단일키 타겟은 compositeForeignKey=null로
        // 기존 단일 FK 경로를 byte-identical하게 유지한다.
        ToOneForeignKey compositeForeignKey = fkStorage == null
                ? resolveCompositeToOneForeignKey(entityType, targetType, selectedAttribute(field)) : null;
        if (compositeForeignKey != null) {
            columnName = compositeForeignKey.columns().get(0).columnName();
        }
        return new PersistentProperty(
                field,
                field.getName(),
                columnName,
                fkJavaType,
                false,
                false,
                nullable,
                fkLength,
                0,
                0,
                null,
                "",
                fkConverter,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                null,
                false,
                true,
                targetType,
                nullable,
                false,
                null,
                "",
                fkInsertable,
                fkUpdatable,
                fkUnique,
                fkColumnDefinition,
                false,
                fkConverterColumnType,
                false,
                null,
                null,
                null,
                null,
                mapsIdMarker != null,
                mapsIdMarker == null ? "" : mapsIdMarker,
                access.propertyAccess(),
                access.getter(),
                access.setter(),
                toOneCascadeInfo,
                "",
                compositeForeignKey,
                ColumnDdlDefinition.EMPTY);
    }

    private PersistentProperty createManyToOneProperty(
            Class<?> entityType, PersistentAttributeAccess attribute) {
        if (attribute.accessType() == AccessType.FIELD) {
            return createManyToOneProperty(entityType, attribute.field());
        }
        return createDescriptorManyToOneProperty(entityType, attribute);
    }

    private PersistentProperty createDescriptorManyToOneProperty(
            Class<?> entityType, PersistentAttributeAccess attribute) {
        ManyToOne relation = attribute.annotation(ManyToOne.class);
        Class<?> target = relation.targetEntity() == void.class ? attribute.javaType() : relation.targetEntity();
        JoinColumn join = attribute.annotation(JoinColumn.class);
        ForeignKeyStorage storage = resolveToOneForeignKeyStorage(target);
        ToOneForeignKey composite = storage == null ? resolveCompositeToOneForeignKey(entityType, target, attribute) : null;
        String column = composite != null ? composite.columns().get(0).columnName()
                : join != null && !join.name().isBlank() ? join.name() : namingStrategy.columnName(attribute.name() + "_id");
        String mapsId = resolveMapsIdMarker(entityType, attribute);
        boolean nullable = relation.optional() && (join == null || join.nullable());
        return new PersistentProperty(null, attribute.name(), column, storage == null ? Long.class : storage.javaType(),
                false, false, nullable, storage == null ? 255 : storage.length(), 0, 0, null, "",
                storage == null ? null : storage.converter(), false, false, false, false, List.of(), false, null, false,
                true, target, nullable, false, null, "", join == null || join.insertable(), join == null || join.updatable(),
                join != null && join.unique(), join == null ? "" : join.columnDefinition(), false,
                storage == null ? null : storage.converterColumnType(), false, null, null, null, null,
                mapsId != null, mapsId == null ? "" : mapsId, true, attribute.getter(), attribute.setter(),
                relation.cascade().length == 0 ? null : new ToOneCascadeInfo(Set.of(relation.cascade())), "", composite,
                ColumnDdlDefinition.EMPTY);
    }

    /**
     * {@link OneToOne} property를 만든다. {@code mappedBy}가 없으면 owning side로 FK 컬럼을 가지며
     * {@code @ManyToOne}과 동일한 단건 참조 메커니즘({@code manyToOne=true})으로 모델링한다(FK는 unique 기본).
     * {@code mappedBy}가 있으면 inverse side로 컬럼 없는 {@code inverseToOne} 마커가 되고, 소유 측 FK로
     * 단건 child가 hydration된다. fetch=LAZY는 no-op으로 수용하고(Nova는 lazy proxy가 없어
     * 관계는 FetchGroup으로만 populate되므로 EAGER와 LAZY가 런타임 동일), cascade(PERSIST/MERGE/REMOVE)는
     * {@link ToOneCascadeInfo}로 캡처해 save/delete에서 참조 엔티티로 전파한다(REFRESH/DETACH는 no-op).
     */
    private PersistentProperty createOneToOneProperty(Class<?> entityType, Field field) {
        // 관계 property의 effective access 전략을 basic property와 동일 규칙으로 해석한다(owning/inverse 공통).
        RelationAccess access = resolveRelationAccess(field);
        OneToOne oneToOne = memberAnnotation(field, OneToOne.class);
        // fetch=LAZY는 그대로 수용한다(no-op): Nova는 lazy proxy가 없어 EAGER/LAZY가 런타임에서
        // 동일하게 동작하며 관계는 FetchGroup을 명시 구동할 때만 populate된다. FK 컬럼은 정상 persist.
        ToOneCascadeInfo toOneCascadeInfo = oneToOne.cascade().length > 0
                ? new ToOneCascadeInfo(Set.of(oneToOne.cascade()))
                : null;
        Class<?> targetType = oneToOne.targetEntity();
        if (targetType == void.class) {
            targetType = field.getType();
        }
        String mappedBy = oneToOne.mappedBy();
        if (mappedBy != null && !mappedBy.isBlank()) {
            rejectUnsupportedInverseOneToOne(entityType, field.getName(), oneToOne);
            // @MapsId는 FK를 소유한 owning side에서만 식별자를 파생할 수 있다. inverse(mappedBy) side에
            // @MapsId가 붙으면 조용히 무시되지 않도록 fail-fast로 거부한다.
            if (memberPresent(field, MapsId.class)) {
                throw new IllegalArgumentException(
                        entityType.getName() + "." + field.getName()
                                + " @MapsId is only valid on the owning side of a to-one relationship;"
                                + " it cannot be placed on an inverse @OneToOne(mappedBy)");
            }
            // inverse side — 컬럼 없는 마커. target/mappedBy는 oneToMany 필드 자리에 보관하고 inverseToOne로 구분한다.
            return new PersistentProperty(
                    field,
                    field.getName(),
                    "",
                    field.getType(),
                    false,
                    false,
                    true,
                    255,
                    0,
                    0,
                    null,
                    "",
                    null,
                    false,
                    false,
                    false,
                    false,
                    List.of(),
                    false,
                    null,
                    false,
                    false,
                    null,
                    false,
                    false,
                    targetType,
                    mappedBy,
                    true,
                    true,
                    false,
                    "",
                    false,
                    null,
                    true,
                    null,
                    null,
                    null,
                    null,
                    false,
                    "",
                    access.propertyAccess(),
                    access.getter(),
                    access.setter(),
                    null,
                    "",
                    null,
                    ColumnDdlDefinition.EMPTY
            );
        }
        // owning side — FK 컬럼을 가지는 단건 참조. @ManyToOne과 동일하게 모델링하되 FK는 unique 기본.
        JoinColumn joinColumn = memberAnnotation(field, JoinColumn.class);
        rejectUnsupportedOwningOneToOne(entityType, field.getName(), oneToOne, joinColumn,
                memberAnnotation(field, JoinColumns.class), memberPresent(field, MapsId.class));
        String columnName;
        if (joinColumn != null && !joinColumn.name().isBlank()) {
            columnName = joinColumn.name();
        } else {
            columnName = namingStrategy.columnName(field.getName() + "_id");
        }
        boolean nullable = oneToOne.optional() && (joinColumn == null || joinColumn.nullable());
        boolean fkInsertable = joinColumn == null || joinColumn.insertable();
        boolean fkUpdatable = joinColumn == null || joinColumn.updatable();
        // @OneToOne의 FK는 일대일을 강제하기 위해 unique로 emit한다(@JoinColumn(unique=false)는 무시).
        boolean fkUnique = true;
        String fkColumnDefinition = joinColumn == null ? "" : joinColumn.columnDefinition();
        String mapsIdMarker = resolveMapsIdMarker(entityType, field);
        // owning @OneToOne FK도 @ManyToOne과 동일하게 참조 대상의 단일 @Id 저장 표현에 맞춘다(해석 불가면 Long).
        ForeignKeyStorage fkStorage = resolveToOneForeignKeyStorage(targetType);
        Class<?> fkJavaType = fkStorage != null ? fkStorage.javaType() : Long.class;
        AttributeConverter<Object, Object> fkConverter = fkStorage != null ? fkStorage.converter() : null;
        Class<?> fkConverterColumnType = fkStorage != null ? fkStorage.converterColumnType() : null;
        int fkLength = fkStorage != null ? fkStorage.length() : 255;
        // 단일 @Id 해석 불가(복합키 @EmbeddedId/@IdClass)면 다중컬럼 FK 모델로 확장한다. 복합 FK가 있으면
        // 단일 columnName 대신 그 첫 FK 컬럼명을 대표로 두고(단일컬럼 접근자/uniqueness 표기 결정성 유지),
        // 실제 N개 컬럼 emit/바인딩/디코드는 toOneForeignKey가 담당한다. 단일키 타겟은 compositeForeignKey=null로
        // 기존 단일 FK 경로를 byte-identical하게 유지한다.
        ToOneForeignKey compositeForeignKey = fkStorage == null
                ? resolveCompositeToOneForeignKey(entityType, targetType, selectedAttribute(field)) : null;
        if (compositeForeignKey != null) {
            columnName = compositeForeignKey.columns().get(0).columnName();
        }
        return new PersistentProperty(
                field,
                field.getName(),
                columnName,
                fkJavaType,
                false,
                false,
                nullable,
                fkLength,
                0,
                0,
                null,
                "",
                fkConverter,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                null,
                false,
                true,
                targetType,
                nullable,
                false,
                null,
                "",
                fkInsertable,
                fkUpdatable,
                fkUnique,
                fkColumnDefinition,
                false,
                fkConverterColumnType,
                false,
                null,
                null,
                null,
                null,
                mapsIdMarker != null,
                mapsIdMarker == null ? "" : mapsIdMarker,
                access.propertyAccess(),
                access.getter(),
                access.setter(),
                toOneCascadeInfo,
                "",
                compositeForeignKey,
                ColumnDdlDefinition.EMPTY).withOneToOneLifecycle(true, oneToOne.orphanRemoval());
    }

    private PersistentProperty createOneToOneProperty(
            Class<?> entityType, PersistentAttributeAccess attribute) {
        if (attribute.accessType() == AccessType.FIELD) {
            return createOneToOneProperty(entityType, attribute.field());
        }
        return createDescriptorToOneProperty(entityType, attribute, true);
    }

    /**
     * {@code @ManyToMany} property를 만든다. owning({@code mappedBy} 없음, {@code @JoinTable})과
     * inverse({@code mappedBy}) 모두 컬럼 없는 marker이며 link table 매핑을 {@link ManyToManyInfo}에 담는다.
     * owning side의 cascade(PERSIST/MERGE/ALL)는 honor하고(inverse side cascade는 거부), fetch=LAZY는 허용한다
     * (Nova는 {@code @OneToMany}처럼 eager-hydrate). 복합키 owner/target, 다중 join 컬럼, 잘못된 {@code mappedBy},
     * raw/non-collection 필드는 fail-fast로 거부한다.
     */
    private PersistentProperty createManyToManyProperty(Class<?> entityType, String ownerTableName, Field field) {
        RelationAccess access = resolveRelationAccess(field);
        ManyToMany annotation = memberAnnotation(field, ManyToMany.class);
        Class<?> fieldType = selectedAttribute(field).javaType();
        if (!List.class.isAssignableFrom(fieldType) && !Set.class.isAssignableFrom(fieldType)) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @ManyToMany must map to a List or Set; got " + fieldType.getName());
        }
        boolean usesSet = Set.class.isAssignableFrom(fieldType);
        Class<?> target = resolveManyToManyTarget(entityType, field, annotation);
        String mappedBy = annotation.mappedBy();
        boolean owning = mappedBy == null || mappedBy.isBlank();
        Set<CascadeType> cascadeTypes = Set.of(annotation.cascade());
        if (!owning && !cascadeTypes.isEmpty()) {
            // link 동기화·cascade 전파는 owning side에서만 일어난다. inverse(mappedBy)에 cascade를 선언하면
            // 조용히 무시되어 오해를 부르므로 fail-fast로 거부하고 owning side에 선언하도록 안내한다.
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @ManyToMany cascade must be declared on the owning side (the @JoinTable side),"
                            + " not the mappedBy side");
        }
        ManyToManyInfo info = owning
                ? resolveOwningManyToManyInfo(entityType, ownerTableName, field, target, usesSet).withCascade(cascadeTypes)
                : resolveInverseManyToManyInfo(entityType, field, target, mappedBy, usesSet);
        return new PersistentProperty(
                field,
                field.getName(),
                "",
                fieldType,
                false,
                false,
                true,
                255,
                0,
                0,
                null,
                "",
                null,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                null,
                false,
                false,
                null,
                true,
                false,
                null,
                "",
                true,
                true,
                false,
                "",
                false,
                null,
                false,
                info, null, null, null, false, "", access.propertyAccess(), access.getter(), access.setter(), null, "", null,
                ColumnDdlDefinition.EMPTY);
    }

    private PersistentProperty createManyToManyProperty(
            Class<?> entityType, String ownerTableName, PersistentAttributeAccess attribute) {
        if (attribute.accessType() == AccessType.FIELD) {
            return createManyToManyProperty(entityType, ownerTableName, attribute.field());
        }
        Class<?> collectionType = attribute.javaType();
        String location = entityType.getName() + "." + attribute.name();
        if (!List.class.isAssignableFrom(collectionType) && !Set.class.isAssignableFrom(collectionType)) {
            throw new IllegalArgumentException(location + " @ManyToMany must map to a List or Set; got "
                    + collectionType.getName());
        }
        ManyToMany annotation = attribute.annotation(ManyToMany.class);
        Class<?> target = annotation.targetEntity() == void.class
                ? collectionElementType(entityType, attribute.name(), attribute.genericType(), ManyToMany.class)
                : annotation.targetEntity();
        boolean usesSet = Set.class.isAssignableFrom(collectionType);
        String mappedBy = annotation.mappedBy();
        boolean owning = mappedBy == null || mappedBy.isBlank();
        Set<CascadeType> cascades = Set.of(annotation.cascade());
        if (!owning && !cascades.isEmpty()) {
            throw new IllegalArgumentException(location + " @ManyToMany cascade must be declared on the owning side"
                    + " (the @JoinTable side), not the mappedBy side");
        }
        ManyToManyInfo info = owning
                ? resolveDescriptorOwningManyToManyInfo(entityType, ownerTableName, attribute, target, usesSet)
                        .withCascade(cascades)
                : resolveDescriptorInverseManyToManyInfo(entityType, attribute, target, mappedBy, usesSet);
        return new PersistentProperty(null, attribute.name(), "", collectionType, false, false, true,
                255, 0, 0, null, "", null, false, false, false, false, List.of(), false, null, false,
                false, null, true, false, null, "", true, true, false, "", false, null, false,
                info, null, null, null, false, "", true, attribute.getter(), attribute.setter(), null, "", null,
                ColumnDdlDefinition.EMPTY);
    }

    private ManyToManyInfo resolveDescriptorOwningManyToManyInfo(
            Class<?> ownerType, String ownerTable, PersistentAttributeAccess attribute, Class<?> target, boolean usesSet) {
        JoinTable joinTable = attribute.annotation(JoinTable.class);
        String location = ownerType.getName() + "." + attribute.name();
        String tableName = joinTable != null && !joinTable.name().isBlank()
                ? joinTable.name() : namingStrategy.joinTableName(ownerTable, resolveTableName(target));
        return new ManyToManyInfo(true, target, tableName,
                resolveManyToManyJoinColumnRefs(ownerType, joinTable == null ? null : joinTable.joinColumns(),
                        ownerType.getSimpleName(), location + " @JoinTable.joinColumns"),
                resolveManyToManyJoinColumnRefs(target, joinTable == null ? null : joinTable.inverseJoinColumns(),
                        target.getSimpleName(), location + " @JoinTable.inverseJoinColumns"),
                "", usesSet);
    }

    private ManyToManyInfo resolveDescriptorInverseManyToManyInfo(
            Class<?> entityType, PersistentAttributeAccess attribute, Class<?> target, String mappedBy, boolean usesSet) {
        PersistentAttributeAccess owning = new PersistentAccessResolver().resolve(target).attribute(mappedBy);
        String location = entityType.getName() + "." + attribute.name();
        if (owning == null || !owning.isAnnotationPresent(ManyToMany.class)) {
            throw new IllegalArgumentException(location + " @ManyToMany(mappedBy=\"" + mappedBy
                    + "\") must point to an owning @ManyToMany on " + target.getName());
        }
        ManyToManyInfo owner = resolveDescriptorOwningManyToManyInfo(
                target, resolveTableName(target), owning, entityType, usesSet);
        return new ManyToManyInfo(false, target, owner.joinTableName(), owner.targetForeignKeyColumns(),
                owner.ownerForeignKeyColumns(), mappedBy, usesSet);
    }

    /**
     * {@code @ManyToMany} 컬렉션의 원소(대상 엔티티) 타입을 해석한다. {@code targetEntity}가 명시되면 그것을,
     * 아니면 제네릭 {@code List<T>}/{@code Set<T>}의 단일 타입 인자를 사용한다. raw 컬렉션이면 fail-fast.
     */
    private static Class<?> resolveManyToManyTarget(Class<?> entityType, Field field, ManyToMany annotation) {
        if (annotation.targetEntity() != void.class) {
            return annotation.targetEntity();
        }
        Type generic = selectedGenericType(field);
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> elementType) {
                return elementType;
            }
        }
        throw new IllegalArgumentException(
                entityType.getName() + "." + field.getName()
                        + " @ManyToMany cannot infer the target entity from a raw collection; specify targetEntity");
    }

    /**
     * owning side({@code @JoinTable})의 link table 매핑을 해석한다. 테이블/컬럼 이름은 {@code @JoinTable}/
     * {@code @JoinColumn}이 있으면 그 값을, 없으면 JPA 기본 규약을 따른다. owner/target {@code @Id}(단일 또는
     * 복합키)의 각 컴포넌트마다 FK 컬럼 1개를 <b>참조 컴포넌트 순서대로</b> 만들어 write/read/DDL/FK가 같은 순서를
     * 공유하게 한다. id 컬럼은 재진입 메타 빌드를 피하려 경량 reflection으로 해석한다.
     */
    private ManyToManyInfo resolveOwningManyToManyInfo(
            Class<?> ownerType, String ownerTable, Field field, Class<?> target, boolean usesSet) {
        JoinTable joinTable = memberAnnotation(field, JoinTable.class);
        String location = ownerType.getName() + "." + field.getName();
        String targetTable = resolveTableName(target);
        String tableName = joinTable != null && !joinTable.name().isBlank()
                ? joinTable.name()
                : namingStrategy.joinTableName(ownerTable, targetTable);
        List<ManyToManyInfo.JoinColumnRef> ownerColumns = resolveManyToManyJoinColumnRefs(
                ownerType,
                joinTable == null ? null : joinTable.joinColumns(),
                ownerType.getSimpleName(),
                location + " @JoinTable.joinColumns");
        List<ManyToManyInfo.JoinColumnRef> targetColumns = resolveManyToManyJoinColumnRefs(
                target,
                joinTable == null ? null : joinTable.inverseJoinColumns(),
                target.getSimpleName(),
                location + " @JoinTable.inverseJoinColumns");
        return new ManyToManyInfo(true, target, tableName, ownerColumns, targetColumns, "", usesSet);
    }

    /**
     * {@code @ManyToMany} link table의 한 측(owner 또는 target) FK 컬럼들을 참조 {@code @Id} 컴포넌트 순서대로
     * 해석한다. 단일키는 컬럼 1개, 복합키({@code @EmbeddedId}/{@code @IdClass})는 컴포넌트마다 1개다. 컬럼명은
     * {@code @JoinTable}의 {@code joinColumns}/{@code inverseJoinColumns}가 있으면 honor하고(전부
     * {@code referencedColumnName} 지정 시 참조명 매칭, 전부 미지정 시 위치 매칭), 없으면 기본
     * {@code <entity>_<referencedColumn>} 규칙으로 만든다. 각 컬럼은 그 참조 {@code @Id} 컬럼명과 짝지어 저장된다.
     */
    private List<ManyToManyInfo.JoinColumnRef> resolveManyToManyJoinColumnRefs(
            Class<?> type, JoinColumn[] declared, String entitySimpleName, String location) {
        List<String> referencedColumns = resolveManyToManyReferencedColumns(type, location);
        JoinColumn[] aligned = alignManyToManyJoinColumns(declared, referencedColumns, location);
        List<ManyToManyInfo.JoinColumnRef> refs = new ArrayList<>(referencedColumns.size());
        for (int i = 0; i < referencedColumns.size(); i++) {
            String referenced = referencedColumns.get(i);
            JoinColumn joinColumn = aligned[i];
            String columnName = joinColumn != null && !joinColumn.name().isBlank()
                    ? joinColumn.name()
                    : namingStrategy.joinColumnName(entitySimpleName, referenced);
            refs.add(new ManyToManyInfo.JoinColumnRef(columnName, referenced));
        }
        return refs;
    }

    /**
     * {@code @ManyToMany} 대상/소유 엔티티의 {@code @Id} 컬럼명들을 참조 순서대로 경량 reflection으로 해석한다.
     * 단일 {@code @Id}는 컬럼 1개, 복합키({@code @EmbeddedId}/{@code @IdClass})는
     * {@link #resolveReferencedIdComponents}로 컴포넌트 컬럼들을 얻는다. {@code @Id}가 없거나 nested embedded
     * {@code @EmbeddedId}처럼 미지원 복합키면 fail-fast로 거부한다(조용한 오매핑 방지).
     */
    private List<String> resolveManyToManyReferencedColumns(Class<?> type, String location) {
        List<PersistentAttributeAccess> identifiers = selectedIdentifierAttributes(type);
        boolean hasEmbeddedId = identifiers.stream().anyMatch(attribute -> attribute.isAnnotationPresent(EmbeddedId.class));
        List<PersistentAttributeAccess> idFields = identifiers.stream()
                .filter(attribute -> attribute.isAnnotationPresent(Id.class)).toList();
        if (hasEmbeddedId || idFields.size() >= 2) {
            List<ReferencedIdComponent> components = resolveReferencedIdComponents(type);
            if (components == null) {
                throw new IllegalArgumentException(location
                        + " @ManyToMany with composite-keyed entity " + type.getName()
                        + " is not supported (nested @EmbeddedId component or unmappable id)");
            }
            return components.stream().map(ReferencedIdComponent::referencedColumnName).toList();
        }
        if (idFields.size() == 1) {
            return List.of(columnNameOf(idFields.get(0)));
        }
        throw new IllegalArgumentException(
                location + " @ManyToMany references entity " + type.getName() + " with no @Id");
    }

    /**
     * {@code @JoinTable}의 join/inverseJoin 컬럼 배열을 참조 {@code @Id} 컬럼 순서에 맞춰 정렬한다. 단일키는
     * 기존 규약을 그대로 보존한다(컬럼 미지정→기본 이름, 1개→그대로, 2개 이상→fail-fast). 복합키는 컬럼 수가
     * 컴포넌트 수와 일치해야 하며 {@code referencedColumnName}은 전부 지정(참조명 매칭)하거나 전부 생략(위치 매칭)
     * 해야 한다. 위반 시 fail-fast.
     */
    private static JoinColumn[] alignManyToManyJoinColumns(
            JoinColumn[] declared, List<String> referencedColumns, String location) {
        JoinColumn[] result = new JoinColumn[referencedColumns.size()];
        if (declared == null || declared.length == 0) {
            return result;
        }
        if (referencedColumns.size() == 1) {
            // 단일키: 레거시 동작 보존 — referencedColumnName은 여기서 검증하지 않는다(FK 해석에서 별도로 검증).
            if (declared.length > 1) {
                throw new IllegalArgumentException(
                        location + " with multiple columns (composite keys) is not supported");
            }
            result[0] = declared[0];
            return result;
        }
        if (declared.length != referencedColumns.size()) {
            throw new IllegalArgumentException(location + " declares " + declared.length
                    + " join column(s) but the referenced entity has " + referencedColumns.size()
                    + " @Id column(s); one @JoinColumn per referenced @Id column is required");
        }
        int withReferenced = 0;
        for (JoinColumn joinColumn : declared) {
            if (!joinColumn.referencedColumnName().isBlank()) {
                withReferenced++;
            }
        }
        if (withReferenced == 0) {
            System.arraycopy(declared, 0, result, 0, declared.length);
            return result;
        }
        if (withReferenced != declared.length) {
            throw new IllegalArgumentException(location
                    + " @JoinColumn referencedColumnName must be either all specified or all omitted");
        }
        for (int i = 0; i < referencedColumns.size(); i++) {
            String referenced = referencedColumns.get(i);
            JoinColumn match = null;
            for (JoinColumn joinColumn : declared) {
                if (joinColumn.referencedColumnName().equals(referenced)) {
                    match = joinColumn;
                    break;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException(location
                        + " @JoinColumn referencedColumnName does not cover referenced @Id column \""
                        + referenced + "\"");
            }
            result[i] = match;
        }
        return result;
    }

    /**
     * inverse side({@code mappedBy})의 매핑을 해석한다. 대상 엔티티의 owning 필드를 reflect해 owning 매핑을
     * 복원한 뒤 owner/target 컬럼을 swap해 "owner = 이 inverse 엔티티" 규약을 맞춘다(물리 테이블은 동일).
     */
    private ManyToManyInfo resolveInverseManyToManyInfo(
            Class<?> entityType, Field field, Class<?> target, String mappedBy, boolean usesSet) {
        Field owningField;
        try {
            owningField = target.getDeclaredField(mappedBy);
        } catch (NoSuchFieldException exception) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @ManyToMany(mappedBy=\"" + mappedBy + "\") references no field on " + target.getName(),
                    exception);
        }
        if (!owningField.isAnnotationPresent(ManyToMany.class)) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @ManyToMany(mappedBy=\"" + mappedBy + "\") must point to an owning @ManyToMany on "
                            + target.getName());
        }
        ManyToManyInfo owning = resolveOwningManyToManyInfo(
                target, resolveTableName(target), owningField, entityType, usesSet);
        return new ManyToManyInfo(
                false, target, owning.joinTableName(),
                owning.targetForeignKeyColumns(), owning.ownerForeignKeyColumns(), mappedBy, usesSet);
    }

    /**
     * 엔티티의 단일 {@code @Id} 컬럼 이름을 경량 reflection으로 해석한다(전체 메타데이터 빌드 회피). 복합키
     * ({@code @EmbeddedId} 또는 {@code @Id} 2개 이상)는 v1에서 {@code @ManyToMany} 대상으로 지원하지 않으므로
     * fail-fast로 거부한다.
     */
    private String resolveSingleIdColumn(Class<?> type, String location) {
        List<PersistentAttributeAccess> identifiers = selectedIdentifierAttributes(type);
        boolean hasEmbeddedId = identifiers.stream().anyMatch(attribute -> attribute.isAnnotationPresent(EmbeddedId.class));
        List<PersistentAttributeAccess> idFields = identifiers.stream()
                .filter(attribute -> attribute.isAnnotationPresent(Id.class)).toList();
        if (hasEmbeddedId || idFields.size() != 1) {
            throw new IllegalArgumentException(
                    location + " @ManyToMany with composite-keyed entity " + type.getName()
                            + " is not supported");
        }
        return columnNameOf(idFields.get(0));
    }

    private String resolveTableName(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        return table != null && !table.name().isBlank() ? table.name() : namingStrategy.tableName(type);
    }

    /**
     * {@code @JoinTable}의 join/inverseJoin 컬럼 1개를 해석한다. 미지정이면 JPA 기본 이름을, 비어 있으면
     * 기본 이름을 쓴다. 2개 이상(복합키)이면 v1 미지원으로 fail-fast.
     */
    private static String resolveSingleJoinColumn(JoinColumn[] columns, String defaultName, String location) {
        if (columns == null || columns.length == 0) {
            return defaultName;
        }
        if (columns.length > 1) {
            throw new IllegalArgumentException(location + " with multiple columns (composite keys) is not supported");
        }
        String name = columns[0].name();
        return name == null || name.isBlank() ? defaultName : name;
    }

    /**
     * {@code @ElementCollection} 값 컬렉션 property를 만든다. 기본 타입 원소를 collection table {@code (owner FK,
     * value)}에 저장하는 컬럼 없는 marker다. {@code Map<K,V>}는 추가로 key 컬럼을 둔다(owner FK, key, value).
     * {@code @CollectionTable}/{@code @JoinColumn}/{@code @Column}/{@code @MapKeyColumn}이 있으면 그 이름을, 없으면
     * JPA 기본 규약을 따른다. {@code @MapKey}/{@code @Embeddable} key/복합키 owner/non-collection 필드는 fail-fast로 거부한다.
     */
    private PersistentProperty createElementCollectionProperty(Class<?> entityType, String ownerTableName, Field field) {
        RelationAccess access = resolveRelationAccess(field);
        Class<?> fieldType = selectedAttribute(field).javaType();
        boolean isMap = Map.class.isAssignableFrom(fieldType);
        if (!isMap && !List.class.isAssignableFrom(fieldType) && !Set.class.isAssignableFrom(fieldType)) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @ElementCollection must map to a List, Set, or Map; got " + fieldType.getName());
        }
        String location = entityType.getName() + "." + field.getName();
        boolean usesSet = Set.class.isAssignableFrom(fieldType);
        Class<?> elementType = isMap
                ? resolveMapValueType(entityType, field, location)
                : resolveElementCollectionElementType(entityType, field);
        String ownerIdColumn = resolveSingleIdColumn(entityType, location);
        CollectionTable collectionTable = memberAnnotation(field, CollectionTable.class);
        String tableName = collectionTable != null && !collectionTable.name().isBlank()
                ? collectionTable.name()
                : namingStrategy.joinTableName(ownerTableName, namingStrategy.columnName(field.getName()));
        String ownerForeignKeyColumn = resolveSingleJoinColumn(
                collectionTable == null ? null : collectionTable.joinColumns(),
                namingStrategy.joinColumnName(entityType.getSimpleName(), ownerIdColumn),
                location + " @CollectionTable.joinColumns");
        ElementCollectionInfo.MapKeyInfo mapKey =
                isMap ? resolveMapKeyInfo(entityType, field, location) : null;
        OrderColumnInfo orderColumn;
        if (isMap) {
            // Map은 정렬 의미가 없다 — @OrderColumn은 거부한다(조용한 무시 금지).
            if (memberPresent(field, jakarta.persistence.OrderColumn.class)) {
                throw new IllegalArgumentException(
                        location + " @OrderColumn is not valid on a Map @ElementCollection (maps are unordered)");
            }
            orderColumn = null;
        } else {
            orderColumn = resolveElementCollectionOrderColumn(field, usesSet, location);
        }
        ElementCollectionInfo info;
        if (elementType.isAnnotationPresent(Embeddable.class)) {
            // @Embeddable 원소: 원소 타입의 영속 필드들을 collection table 다중 컬럼으로 펼친다. owner FK는 단일
            // 컬럼으로 유지하고, value 컬럼은 의미가 없으므로 빈 문자열을 둔다.
            List<ElementCollectionInfo.EmbeddableColumn> embeddableColumns =
                    expandEmbeddableElementColumns(elementType, field, location, ownerForeignKeyColumn);
            List<String> valueColumns =
                    embeddableColumns.stream().map(ElementCollectionInfo.EmbeddableColumn::columnName).toList();
            rejectOrderColumnCollision(orderColumn, ownerForeignKeyColumn, valueColumns, location);
            rejectMapKeyCollision(mapKey, ownerForeignKeyColumn, valueColumns, location);
            info = new ElementCollectionInfo(
                    tableName, ownerForeignKeyColumn, "", elementType, usesSet, embeddableColumns, orderColumn, mapKey);
        } else {
            Column column = memberAnnotation(field, Column.class);
            String valueColumn = column != null && !column.name().isBlank()
                    ? column.name()
                    : namingStrategy.columnName(field.getName());
            rejectOrderColumnCollision(orderColumn, ownerForeignKeyColumn, List.of(valueColumn), location);
            rejectMapKeyCollision(mapKey, ownerForeignKeyColumn, List.of(valueColumn), location);
            // 기본 타입 원소(List/Set 원소 또는 Map value)의 저장타입/컨버터를 스칼라 프로퍼티와 동일한 규칙으로
            // 해석한다 — enum(@Enumerated)/UUID/@Convert/@Temporal은 저장 표현으로, 순수 기본 타입은 그대로.
            ElementValueMapping valueMapping = resolveBasicElementValueMapping(
                    entityType, field, elementType, location, isMap ? "value" : null);
            info = new ElementCollectionInfo(
                    tableName, ownerForeignKeyColumn, valueColumn, wrapPrimitiveType(elementType), usesSet,
                    List.of(), orderColumn, mapKey, valueMapping.columnType(), valueMapping.converter());
        }
        return new PersistentProperty(
                field, field.getName(), "", fieldType,
                false, false, true, 255, 0, 0,
                null, "", null,
                false, false, false, false, List.of(),
                false, null, false,
                false, null, true,
                false, null, "",
                true, true, false, "", false, null,
                false,
                null,
                info,
                null,
                null,
                false,
                "",
                access.propertyAccess(),
                access.getter(),
                access.setter(),
                null,
                "",
                null,
                ColumnDdlDefinition.EMPTY);
    }

    private PersistentProperty createElementCollectionProperty(
            Class<?> entityType, String ownerTableName, PersistentAttributeAccess attribute) {
        if (attribute.accessType() == AccessType.FIELD) {
            return createElementCollectionProperty(entityType, ownerTableName, attribute.field());
        }
        Class<?> collectionType = attribute.javaType();
        String location = entityType.getName() + "." + attribute.name();
        boolean map = Map.class.isAssignableFrom(collectionType);
        if (!map && !List.class.isAssignableFrom(collectionType) && !Set.class.isAssignableFrom(collectionType)) {
            throw new IllegalArgumentException(location + " @ElementCollection must map to a List, Set, or Map; got "
                    + collectionType.getName());
        }
        Class<?> valueType = descriptorCollectionValueType(attribute, map, location);
        CollectionTable table = attribute.annotation(CollectionTable.class);
        String ownerId = resolveSingleIdColumn(entityType, location);
        String tableName = table != null && !table.name().isBlank() ? table.name()
                : namingStrategy.joinTableName(ownerTableName, namingStrategy.columnName(attribute.name()));
        String ownerColumn = resolveSingleJoinColumn(table == null ? null : table.joinColumns(),
                namingStrategy.joinColumnName(entityType.getSimpleName(), ownerId), location + " @CollectionTable.joinColumns");
        boolean usesSet = Set.class.isAssignableFrom(collectionType);
        OrderColumnInfo order = null;
        if (map) {
            if (attribute.isAnnotationPresent(jakarta.persistence.OrderColumn.class)) {
                throw new IllegalArgumentException(location + " @OrderColumn is not valid on a Map @ElementCollection (maps are unordered)");
            }
        } else {
            order = resolveDescriptorElementCollectionOrderColumn(attribute, usesSet, location);
        }
        ElementCollectionInfo.MapKeyInfo key = map ? resolveDescriptorMapKeyInfo(attribute, location) : null;
        if (valueType.isAnnotationPresent(Embeddable.class)) {
            List<ElementCollectionInfo.EmbeddableColumn> columns =
                    expandEmbeddableCollectionColumns(valueType, attribute, location, ownerColumn, false);
            List<String> valueColumns = columns.stream().map(ElementCollectionInfo.EmbeddableColumn::columnName).toList();
            rejectOrderColumnCollision(order, ownerColumn, valueColumns, location);
            rejectMapKeyCollision(key, ownerColumn, valueColumns, location);
            ElementCollectionInfo info = new ElementCollectionInfo(tableName, ownerColumn, "", valueType,
                    usesSet, columns, order, key);
            return new PersistentProperty(null, attribute.name(), "", collectionType, false, false, true,
                    255, 0, 0, null, "", null, false, false, false, false, List.of(), false, null, false,
                    false, null, true, false, null, "", true, true, false, "", false, null, false,
                    null, info, null, null, false, "", true, attribute.getter(), attribute.setter(), null, "", null,
                    ColumnDdlDefinition.EMPTY);
        }
        Column column = attribute.annotation(Column.class);
        String valueColumn = column != null && !column.name().isBlank() ? column.name()
                : namingStrategy.columnName(attribute.name());
        ElementValueMapping mapping = resolveDescriptorElementValueMapping(attribute, valueType, map ? "value" : null, location);
        rejectOrderColumnCollision(order, ownerColumn, List.of(valueColumn), location);
        rejectMapKeyCollision(key, ownerColumn, List.of(valueColumn), location);
        ElementCollectionInfo info = new ElementCollectionInfo(tableName, ownerColumn, valueColumn,
                wrapPrimitiveType(valueType), usesSet, List.of(), order, key, mapping.columnType(), mapping.converter());
        return new PersistentProperty(null, attribute.name(), "", collectionType, false, false, true,
                255, 0, 0, null, "", null, false, false, false, false, List.of(), false, null, false,
                false, null, true, false, null, "", true, true, false, "", false, null, false,
                null, info, null, null, false, "", true, attribute.getter(), attribute.setter(), null, "", null,
                ColumnDdlDefinition.EMPTY);
    }

    private static Class<?> descriptorCollectionValueType(
            PersistentAttributeAccess attribute, boolean map, String location) {
        ElementCollection annotation = attribute.annotation(ElementCollection.class);
        if (annotation.targetClass() != void.class) {
            return annotation.targetClass();
        }
        if (attribute.genericType() instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            int valueIndex = map ? 1 : 0;
            if (arguments.length == (map ? 2 : 1) && arguments[valueIndex] instanceof Class<?> valueType) {
                return valueType;
            }
        }
        throw new IllegalArgumentException(location + " @ElementCollection cannot infer the "
                + (map ? "Map value type from a raw map; specify targetClass" : "element type from a raw collection; specify targetClass"));
    }

    private OrderColumnInfo resolveDescriptorElementCollectionOrderColumn(
            PersistentAttributeAccess attribute, boolean usesSet, String location) {
        jakarta.persistence.OrderColumn order = attribute.annotation(jakarta.persistence.OrderColumn.class);
        if (order == null) return null;
        if (usesSet) {
            throw new IllegalArgumentException(location + " @OrderColumn is only valid on an ordered List, not on a Set @ElementCollection");
        }
        if (attribute.isAnnotationPresent(jakarta.persistence.OrderBy.class)) {
            throw new IllegalArgumentException(location + " cannot declare both @OrderColumn and @OrderBy; the two ordering strategies conflict");
        }
        return new OrderColumnInfo(order.name().isBlank() ? namingStrategy.columnName(attribute.name()) + "_order" : order.name());
    }

    private ElementCollectionInfo.MapKeyInfo resolveDescriptorMapKeyInfo(
            PersistentAttributeAccess attribute, String location) {
        if (attribute.isAnnotationPresent(MapKey.class)) {
            throw new IllegalArgumentException(location + " @MapKey (using an associated entity property as the map key) is not supported;"
                    + " use a basic or enum map key with @MapKeyColumn");
        }
        Class<?> keyType = null;
        if (attribute.genericType() instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 2
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> type) keyType = type;
        MapKeyClass declared = attribute.annotation(MapKeyClass.class);
        if (declared != null) {
            if (declared.value() == void.class || declared.value() == Void.class) {
                throw new IllegalArgumentException(location + " @MapKeyClass requires a key class");
            }
            if (keyType != null && keyType != declared.value()) {
                throw new IllegalArgumentException(location + " @MapKeyClass " + declared.value().getName()
                        + " does not match the parameterized Map key type " + keyType.getName());
            }
            keyType = declared.value();
        }
        if (keyType == null) throw new IllegalArgumentException(location + " @ElementCollection cannot infer the Map key type from a raw map;"
                + " use a parameterized Map<K,V> or specify @MapKeyClass");
        MapKeyTemporal temporal = attribute.annotation(MapKeyTemporal.class);
        boolean utilDate = keyType == java.util.Date.class;
        boolean calendar = java.util.Calendar.class.isAssignableFrom(keyType);
        if (temporal != null && !utilDate && !calendar) {
            throw new IllegalArgumentException(location + " @MapKeyTemporal is only valid on a java.util.Date or"
                    + " java.util.Calendar map key, but the key type is " + keyType.getName());
        }
        if (keyType.isAnnotationPresent(Embeddable.class)) {
            return ElementCollectionInfo.MapKeyInfo.embeddable(keyType,
                    expandEmbeddableCollectionColumns(keyType, attribute, location, null, true));
        }
        if (keyType.isAnnotationPresent(Entity.class)) {
            ForeignKeyStorage storage = resolveToOneForeignKeyStorage(keyType);
            if (storage == null) {
                throw new IllegalArgumentException(location + " @MapKeyClass naming an entity key class "
                        + keyType.getName() + " with a composite @Id (@EmbeddedId/@IdClass) is not supported;"
                        + " only a single-@Id entity map key is supported");
            }
            if (attribute.isAnnotationPresent(MapKeyColumn.class)) {
                throw new IllegalArgumentException(location
                        + " @MapKeyColumn is not valid on an entity map key; use @MapKeyJoinColumn instead");
            }
            MapKeyJoinColumn join = attribute.annotation(MapKeyJoinColumn.class);
            String name = join != null && !join.name().isBlank() ? join.name()
                    : namingStrategy.columnName(attribute.name()) + "_key";
            return ElementCollectionInfo.MapKeyInfo.entity(name, keyType,
                    storage.converterColumnType() != null ? storage.converterColumnType() : storage.javaType(),
                    storage.converter());
        }
        MapKeyColumn column = attribute.annotation(MapKeyColumn.class);
        String name = column != null && !column.name().isBlank() ? column.name() : namingStrategy.columnName(attribute.name()) + "_key";
        JpaConverterDescriptor explicit = attribute.getter() != null
                ? explicitJpaConverter(attribute.getter(), keyType, "key", location, null)
                : explicitJpaConverter(attribute.field(), keyType, "key", location, null);
        MapKeyEnumerated enumerated = attribute.annotation(MapKeyEnumerated.class);
        boolean disabled = attribute.getter() != null
                ? conversionDisabled(attribute.getter(), "key", null)
                : conversionDisabled(attribute.field(), "key", null);
        if (keyType.isEnum()) {
            if (explicit != null) {
                if (enumerated != null) {
                    throw new IllegalStateException(location
                            + " cannot combine @Convert(attributeName=\"key\") with @MapKeyEnumerated");
                }
                return new ElementCollectionInfo.MapKeyInfo(name, keyType, explicit.columnType(), null, explicit.instantiate());
            }
            if (enumerated == null && !disabled) {
                JpaConverterDescriptor automatic = uniqueJpaConverter(keyType, true, location);
                if (automatic != null) {
                    return new ElementCollectionInfo.MapKeyInfo(
                            name, keyType, automatic.columnType(), null, automatic.instantiate());
                }
            }
            EnumType type = enumerated == null ? inferredEnumType(keyType) : enumerated.value();
            EnumMapping mapping = resolveEnumMapping(keyType, type);
            return new ElementCollectionInfo.MapKeyInfo(name, keyType,
                    mapping.customColumnType() != null ? mapping.customColumnType() : type == EnumType.STRING ? String.class : Integer.class,
                    type, mapping.converter());
        }
        if (explicit != null) {
            if (temporal != null) {
                throw new IllegalStateException(location
                        + " cannot combine @Convert(attributeName=\"key\") with @MapKeyTemporal");
            }
            return new ElementCollectionInfo.MapKeyInfo(name, keyType, explicit.columnType(), null, explicit.instantiate());
        }
        if (enumerated != null) {
            throw new IllegalArgumentException(location
                    + " @MapKeyEnumerated is only valid on an enum map key, but the key type is "
                    + keyType.getName());
        }
        if (temporal != null) {
            Class<?> stored = switch (temporal.value()) {
                case DATE -> java.time.LocalDate.class;
                case TIME -> java.time.LocalTime.class;
                case TIMESTAMP -> java.time.LocalDateTime.class;
            };
            return new ElementCollectionInfo.MapKeyInfo(name, keyType, stored, null,
                    castConverter(new TemporalAttributeConverter(keyType, temporal.value())));
        }
        if (utilDate || calendar) {
            throw new IllegalArgumentException(location + " maps a java.util.Date/Calendar map key but is missing"
                    + " @MapKeyTemporal(TemporalType.DATE|TIME|TIMESTAMP); the mapping is ambiguous without it");
        }
        if (!disabled) {
            JpaConverterDescriptor automatic = uniqueJpaConverter(wrapPrimitiveType(keyType), true, location);
            if (automatic != null) {
                return new ElementCollectionInfo.MapKeyInfo(name, wrapPrimitiveType(keyType), automatic.columnType(),
                        null, automatic.instantiate());
            }
        }
        ElementValueMapping storage = resolveBasicStorageMapping(wrapPrimitiveType(keyType));
        if (!SUPPORTED_MAP_KEY_BASIC_TYPES.contains(wrapPrimitiveType(keyType))) {
            throw new IllegalArgumentException(location + " @ElementCollection Map key type " + keyType.getName()
                    + " is not supported; supported key types: String, Integer, Long, Short, Boolean, UUID, or an enum");
        }
        return new ElementCollectionInfo.MapKeyInfo(name, wrapPrimitiveType(keyType), storage.columnType(), null, storage.converter());
    }

    private List<ElementCollectionInfo.EmbeddableColumn> expandEmbeddableCollectionColumns(
            Class<?> embeddableType, PersistentAttributeAccess collection, String location,
            String ownerForeignKeyColumn, boolean key) {
        List<PersistentAttributeAccess> attributes = embeddedComponentAttributes(embeddableType, collection.accessType());
        Map<String, String> overrides = new java.util.HashMap<>();
        String prefix = key ? "key." : "";
        for (AttributeOverride override : collection.annotationsByType(AttributeOverride.class)) {
            boolean keyOverride = override.name().startsWith("key.");
            if ((key && keyOverride) || (!key && !keyOverride)) {
                overrides.put(key ? override.name().substring("key.".length()) : override.name(), override.column().name());
            }
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> columns = new java.util.HashSet<>();
        if (ownerForeignKeyColumn != null) columns.add(ownerForeignKeyColumn);
        List<ElementCollectionInfo.EmbeddableColumn> result = new ArrayList<>();
        for (PersistentAttributeAccess component : attributes) {
            names.add(component.name());
            if (component.isAnnotationPresent(Embedded.class) || component.isAnnotationPresent(EmbeddedId.class)
                    || component.isAnnotationPresent(Id.class) || component.isAnnotationPresent(OneToMany.class)
                    || component.isAnnotationPresent(ManyToOne.class) || component.isAnnotationPresent(OneToOne.class)
                    || component.isAnnotationPresent(ManyToMany.class) || component.isAnnotationPresent(ElementCollection.class)
                    || component.isAnnotationPresent(Version.class) || component.isAnnotationPresent(SoftDelete.class)
                    || component.isAnnotationPresent(CreatedAt.class) || component.isAnnotationPresent(UpdatedAt.class)) {
                throw new IllegalArgumentException(location + " @Embeddable component " + component.name()
                        + " must be a simple value field");
            }
            Column column = component.annotation(Column.class);
            String columnName = overrides.containsKey(component.name()) && !overrides.get(component.name()).isBlank()
                    ? overrides.get(component.name())
                    : column != null && !column.name().isBlank() ? column.name()
                    : namingStrategy.columnName(component.name());
            if (!columns.add(columnName)) {
                throw new IllegalArgumentException(location + " @Embeddable component " + component.name()
                        + " produces duplicate collection column '" + columnName + "'");
            }
            if (component.isAnnotationPresent(Json.class)) {
                result.add(new ElementCollectionInfo.EmbeddableColumn(component, columnName, String.class,
                        castConverter(new JsonAttributeConverter(jsonCodec, component.javaType())), true));
            } else {
                ElementValueMapping mapping = resolveDescriptorElementValueMapping(component, component.javaType(), null,
                        location + " component " + component.name());
                result.add(new ElementCollectionInfo.EmbeddableColumn(component, columnName, mapping.columnType(),
                        mapping.converter(), false));
            }
        }
        for (String override : overrides.keySet()) {
            if (!names.contains(override)) {
                throw new IllegalArgumentException(location + " @AttributeOverride(name='" + prefix + override
                        + "') does not match an @Embeddable component");
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(location + " @Embeddable " + embeddableType.getName()
                    + " has no persistent fields to map as collection columns");
        }
        return result;
    }

    private ElementValueMapping resolveDescriptorElementValueMapping(
            PersistentAttributeAccess attribute, Class<?> type, String selector, String location) {
        Class<?> wrapped = wrapPrimitiveType(type);
        JpaConverterDescriptor explicit = attribute.getter() != null
                ? explicitJpaConverter(attribute.getter(), wrapped, selector, location, null)
                : explicitJpaConverter(attribute.field(), wrapped, selector, location, null);
        Enumerated enumerated = attribute.annotation(Enumerated.class);
        Temporal temporal = attribute.annotation(Temporal.class);
        boolean json = attribute.isAnnotationPresent(Json.class);
        AttributeConverter<?, ?> registered = converters.get(type);
        if (json) {
            if (enumerated != null || temporal != null || explicit != null || registered != null) {
                throw new IllegalStateException(location + " cannot combine @Json with @Enumerated/@Temporal/@Convert");
            }
            return new ElementValueMapping(String.class,
                    castConverter(new JsonAttributeConverter(jsonCodec, type)));
        }
        if (enumerated != null) {
            if (!type.isEnum()) {
                throw new IllegalArgumentException(location + " is annotated with @Enumerated but its type "
                        + type.getName() + " is not an enum");
            }
            if (explicit != null || temporal != null || registered != null) {
                throw new IllegalStateException(location + " cannot combine @Enumerated with @Convert/@Temporal");
            }
            EnumMapping mapping = resolveEnumMapping(type, enumerated.value());
            return new ElementValueMapping(mapping.customColumnType() != null ? mapping.customColumnType()
                    : enumerated.value() == EnumType.STRING ? String.class : Integer.class, mapping.converter());
        }
        if (explicit != null) {
            if (temporal != null || registered != null) {
                throw new IllegalStateException(location + " cannot combine @Convert with @Temporal or a registered AttributeConverter");
            }
            return new ElementValueMapping(explicit.columnType(), explicit.instantiate());
        }
        boolean utilDate = type == java.util.Date.class;
        boolean calendar = java.util.Calendar.class.isAssignableFrom(type);
        if (temporal != null) {
            if (registered != null) {
                throw new IllegalStateException(location + " cannot combine @Temporal with a registered AttributeConverter");
            }
            if (!utilDate && !calendar) {
                throw new IllegalArgumentException(location + " is annotated with @Temporal but its type "
                        + type.getName() + " is not java.util.Date or java.util.Calendar");
            }
            Class<?> stored = switch (temporal.value()) {
                case DATE -> java.time.LocalDate.class;
                case TIME -> java.time.LocalTime.class;
                case TIMESTAMP -> java.time.LocalDateTime.class;
            };
            return new ElementValueMapping(stored, castConverter(new TemporalAttributeConverter(type, temporal.value())));
        }
        if (utilDate || calendar) {
            throw new IllegalArgumentException(location + " maps a java.util.Date/Calendar @ElementCollection element but is missing @Temporal");
        }
        if (registered != null) {
            return new ElementValueMapping(wrapped, castConverter(registered));
        }
        boolean disabled = attribute.getter() != null
                ? conversionDisabled(attribute.getter(), selector, null)
                : conversionDisabled(attribute.field(), selector, null);
        if (!disabled) {
            JpaConverterDescriptor automatic = uniqueJpaConverter(wrapped, true, location);
            if (automatic != null) {
                return new ElementValueMapping(automatic.columnType(), automatic.instantiate());
            }
        }
        if (type.isEnum()) {
            EnumType enumType = inferredEnumType(type);
            EnumMapping mapping = resolveEnumMapping(type, enumType);
            return new ElementValueMapping(mapping.customColumnType() != null ? mapping.customColumnType()
                    : enumType == EnumType.STRING ? String.class : Integer.class, mapping.converter());
        }
        return resolveBasicStorageMapping(wrapped);
    }

    /**
     * {@code @ElementCollection} {@code List} 필드의 {@code @OrderColumn}을 {@link OrderColumnInfo}로 해석한다.
     * {@code @OrderColumn}이 없으면 {@code null}(순서 컬럼 없음). 선언되어 있으면:
     * <ul>
     *   <li>{@code Set}에 달리면 거부한다 — {@code Set}은 순서 의미가 없다(JPA도 {@code List}에만 허용).</li>
     *   <li>{@code @OrderBy}와 동시에 달리면 거부한다 — 두 정렬 전략은 모순이다(JPA도 금지).</li>
     * </ul>
     * 컬럼 이름은 {@code @OrderColumn(name=...)}이 비어 있으면 naming strategy를 경유한 기본 규약
     * {@code <property>_order}(snake_case 일관성)를 쓴다.
     */
    private OrderColumnInfo resolveElementCollectionOrderColumn(Field field, boolean usesSet, String location) {
        jakarta.persistence.OrderColumn orderColumn = memberAnnotation(field, jakarta.persistence.OrderColumn.class);
        if (orderColumn == null) {
            return null;
        }
        if (usesSet) {
            throw new IllegalArgumentException(
                    location + " @OrderColumn is only valid on an ordered List, not on a Set @ElementCollection");
        }
        if (memberPresent(field, jakarta.persistence.OrderBy.class)) {
            throw new IllegalArgumentException(
                    location + " cannot declare both @OrderColumn and @OrderBy; the two ordering strategies conflict");
        }
        String name = orderColumn.name().isBlank() ? defaultOrderColumnName(field) : orderColumn.name();
        return new OrderColumnInfo(name);
    }

    /**
     * {@code @OneToMany(mappedBy)} List 필드의 {@code @OrderColumn}을 {@link OrderColumnInfo}로 해석한다.
     * {@code @OrderColumn}이 없으면 {@code null}. 순서 컬럼은 child 테이블에 위치한다. {@code Set} @OneToMany나
     * {@code @OrderBy}와의 동시 선언은 거부한다(두 정렬 전략 모순). 컬럼 이름 기본값은 naming strategy 경유
     * {@code <property>_order}이다.
     */
    private OrderColumnInfo resolveOneToManyOrderColumn(Class<?> entityType, Field field) {
        jakarta.persistence.OrderColumn orderColumn = memberAnnotation(field, jakarta.persistence.OrderColumn.class);
        if (orderColumn == null) {
            return null;
        }
        String location = entityType.getName() + "." + field.getName();
        if (!List.class.isAssignableFrom(field.getType())) {
            throw new IllegalArgumentException(
                    location + " @OrderColumn is only valid on an ordered List @OneToMany, not on "
                            + field.getType().getName());
        }
        if (memberPresent(field, jakarta.persistence.OrderBy.class)) {
            throw new IllegalArgumentException(
                    location + " cannot declare both @OrderColumn and @OrderBy; the two ordering strategies conflict");
        }
        String name = orderColumn.name().isBlank() ? defaultOrderColumnName(field) : orderColumn.name();
        return new OrderColumnInfo(name);
    }

    private OrderColumnInfo resolveOneToManyOrderColumn(Class<?> entityType, Method getter) {
        jakarta.persistence.OrderColumn orderColumn = getter.getAnnotation(jakarta.persistence.OrderColumn.class);
        if (orderColumn == null) {
            return null;
        }
        String location = entityType.getName() + "." + getter.getName();
        if (!List.class.isAssignableFrom(getter.getReturnType())) {
            throw new IllegalArgumentException(location + " @OrderColumn is only valid on an ordered List @OneToMany, not on "
                    + getter.getReturnType().getName());
        }
        if (getter.isAnnotationPresent(jakarta.persistence.OrderBy.class)) {
            throw new IllegalArgumentException(location
                    + " cannot declare both @OrderColumn and @OrderBy; the two ordering strategies conflict");
        }
        String getterName = getter.getName();
        String propertyName = getterName.startsWith("is") ? getterName.substring(2) : getterName.substring(3);
        propertyName = Character.toLowerCase(propertyName.charAt(0)) + propertyName.substring(1);
        return new OrderColumnInfo(orderColumn.name().isBlank()
                ? namingStrategy.columnName(propertyName) + "_order" : orderColumn.name());
    }

    /**
     * {@code @OrderColumn(name)}이 비어 있을 때의 기본 순서 컬럼 이름을 naming strategy로 만든다 —
     * {@code <property>_order}(snake_case). raw {@code field.getName()+"_ORDER"} 대신 naming strategy를
     * 경유해 다른 컬럼 이름들과 표기 일관성을 맞춘다.
     */
    private String defaultOrderColumnName(Field field) {
        return namingStrategy.columnName(field.getName()) + "_order";
    }

    /**
     * 순서 컬럼 이름이 owner FK 컬럼이나 값/펼침 컬럼과 충돌하면 거부한다 — silent 컬럼 덮어쓰기로 데이터가
     * 손상되지 않도록 collection table을 만드는 한 자리에서 검증한다. {@code @OrderColumn}이 없으면 no-op.
     */
    private static void rejectOrderColumnCollision(
            OrderColumnInfo orderColumn, String ownerForeignKeyColumn, List<String> valueColumns, String location) {
        if (orderColumn == null) {
            return;
        }
        String orderColumnName = orderColumn.columnName();
        if (orderColumnName.equals(ownerForeignKeyColumn) || valueColumns.contains(orderColumnName)) {
            throw new IllegalArgumentException(
                    location + " @OrderColumn name '" + orderColumnName
                            + "' collides with another collection table column");
        }
    }

    /**
     * {@code @ElementCollection Map<K,V>}의 key가 가질 수 있는 기본 타입. enum key는 별도 경로로 처리하므로
     * 여기엔 포함하지 않는다(임의 {@code @Embeddable}/엔티티 key는 거부).
     */
    private static final Set<Class<?>> SUPPORTED_MAP_KEY_BASIC_TYPES =
            Set.of(String.class, Integer.class, Long.class, Short.class, Boolean.class, UUID.class);

    /**
     * {@code @ElementCollection Map} key 컬럼 이름이 owner FK나 값/펼침 컬럼과 충돌하면 거부한다 — silent
     * 컬럼 덮어쓰기로 데이터가 손상되지 않도록 한 자리에서 검증한다. map이 아니면 no-op.
     */
    private static void rejectMapKeyCollision(
            ElementCollectionInfo.MapKeyInfo mapKey, String ownerForeignKeyColumn,
            List<String> valueColumns, String location) {
        if (mapKey == null) {
            return;
        }
        if (mapKey.embeddableKey()) {
            // @Embeddable(다중 컬럼) key: 각 펼친 key 컬럼이 owner FK나 값 컬럼과 충돌하는지 개별 검증한다.
            for (ElementCollectionInfo.EmbeddableColumn keyColumn : mapKey.embeddableKeyColumns()) {
                String keyColumnName = keyColumn.columnName();
                if (keyColumnName.equals(ownerForeignKeyColumn) || valueColumns.contains(keyColumnName)) {
                    throw new IllegalArgumentException(
                            location + " @MapKeyClass @Embeddable key column '" + keyColumnName
                                    + "' collides with another collection table column");
                }
            }
            return;
        }
        String keyColumnName = mapKey.keyColumn();
        if (keyColumnName.equals(ownerForeignKeyColumn) || valueColumns.contains(keyColumnName)) {
            throw new IllegalArgumentException(
                    location + " @MapKeyColumn name '" + keyColumnName
                            + "' collides with another collection table column");
        }
    }

    /**
     * {@code @ElementCollection Map}의 value 타입을 해석한다. {@code @ElementCollection(targetClass=...)}이
     * 지정되면 그 타입을, 아니면 generic {@code Map<K,V>}의 두 번째 타입 인자를 쓴다. raw {@code Map}이면 거부한다.
     */
    private static Class<?> resolveMapValueType(Class<?> entityType, Field field, String location) {
        ElementCollection annotation = memberAnnotation(field, ElementCollection.class);
        if (annotation.targetClass() != void.class) {
            return annotation.targetClass();
        }
        Type generic = selectedGenericType(field);
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 2 && arguments[1] instanceof Class<?> valueType) {
                return valueType;
            }
        }
        throw new IllegalArgumentException(
                location + " @ElementCollection cannot infer the Map value type from a raw map; specify targetClass");
    }

    /**
     * {@code @ElementCollection Map}의 key 타입을 해석한다. 우선순위는:
     * <ul>
     *   <li>generic {@code Map<K,V>}의 첫 번째 타입 인자(reflectable {@code Class})가 있으면 그것을 후보로 삼는다.</li>
     *   <li>{@code @MapKeyClass}가 있으면 그 클래스를 명시적 key 타입으로 쓴다. 파라미터화 타입 인자도 함께
     *       reflectable하면 둘은 일치해야 하며(불일치 fail-fast), raw/generic {@code Map}처럼 인자를
     *       reflect할 수 없을 때 key 타입을 결정하는 근거가 된다.</li>
     * </ul>
     * 둘 다 없으면(raw {@code Map} + {@code @MapKeyClass} 부재) fail-fast로 거부한다.
     */
    private static Class<?> resolveMapKeyType(Class<?> entityType, Field field, String location) {
        Class<?> parameterizedKeyType = null;
        Type generic = selectedGenericType(field);
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 2 && arguments[0] instanceof Class<?> keyType) {
                parameterizedKeyType = keyType;
            }
        }
        MapKeyClass mapKeyClass = memberAnnotation(field, MapKeyClass.class);
        if (mapKeyClass != null) {
            Class<?> declaredKeyType = mapKeyClass.value();
            if (declaredKeyType == void.class || declaredKeyType == Void.class) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass requires a key class");
            }
            if (parameterizedKeyType != null && parameterizedKeyType != declaredKeyType) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass " + declaredKeyType.getName()
                                + " does not match the parameterized Map key type "
                                + parameterizedKeyType.getName());
            }
            return declaredKeyType;
        }
        if (parameterizedKeyType != null) {
            return parameterizedKeyType;
        }
        throw new IllegalArgumentException(
                location + " @ElementCollection cannot infer the Map key type from a raw map;"
                        + " use a parameterized Map<K,V> or specify @MapKeyClass");
    }

    /**
     * {@code @ElementCollection Map}의 key 매핑({@link ElementCollectionInfo.MapKeyInfo})을 해석한다.
     * <ul>
     *   <li>{@code @MapKey}(엔티티 property를 key로) — v1 미지원, fail-fast 거부.</li>
     *   <li>{@code @MapKeyClass}가 가리키는 {@code @Embeddable} key 타입 — key를 다중 컬럼으로 펼쳐 저장한다
     *       ({@link #expandEmbeddableMapKeyColumns}).</li>
     *   <li>{@code @MapKeyClass}가 가리키는 단일 {@code @Id} entity key 클래스 — key 컬럼은 참조 {@code @Id}와 동일한
     *       단일컬럼 FK 저장 규칙({@link #resolveToOneForeignKeyStorage})을 재사용한다({@link #expandEmbeddableMapKeyColumns}
     *       가 아니라 단일 컬럼). 복합 {@code @Id}(@EmbeddedId/@IdClass) entity key는 v1 미지원, fail-fast 거부.
     *       {@code @MapKeyColumn}은 entity key에 유효하지 않다(fail-fast, {@code @MapKeyJoinColumn} 사용).</li>
     *   <li>enum key — {@code @MapKeyEnumerated}로 STRING/ORDINAL 결정(미지정 시 JPA 기본 ORDINAL).</li>
     *   <li>temporal key({@code java.util.Date}/{@code Calendar}) — {@code @MapKeyTemporal}(TemporalType)로 저장
     *       정밀도(DATE/TIME/TIMESTAMP)를 정하고 {@code TemporalAttributeConverter}로 java.time 저장 표현에 왕복.
     *       {@code @MapKeyTemporal} 없는 Date/Calendar key는 fail-fast 거부.</li>
     *   <li>기본 타입 key(String/Integer/Long/Short/Boolean/UUID) — 자기 자신(wrapper 정규화)으로 저장.</li>
     *   <li>그 외 key 타입 / 비-enum에 {@code @MapKeyEnumerated} / 비-temporal에 {@code @MapKeyTemporal} — fail-fast 거부.</li>
     * </ul>
     * key 컬럼 이름은 {@code @MapKeyColumn(name=...)} → naming strategy 기본 {@code <property>_key} 순으로 정한다.
     */
    private ElementCollectionInfo.MapKeyInfo resolveMapKeyInfo(Class<?> entityType, Field field, String location) {
        if (memberPresent(field, MapKey.class)) {
            throw new IllegalArgumentException(
                    location + " @MapKey (using an associated entity property as the map key) is not supported;"
                            + " use a basic or enum map key with @MapKeyColumn");
        }
        Class<?> keyType = resolveMapKeyType(entityType, field, location);
        MapKeyTemporal mapKeyTemporal = memberAnnotation(field, MapKeyTemporal.class);
        boolean isUtilDate = keyType == java.util.Date.class;
        boolean isCalendar = java.util.Calendar.class.isAssignableFrom(keyType);
        if (mapKeyTemporal != null && !isUtilDate && !isCalendar) {
            throw new IllegalArgumentException(
                    location + " @MapKeyTemporal is only valid on a java.util.Date or java.util.Calendar"
                            + " map key, but the key type is " + keyType.getName());
        }
        if (keyType.isAnnotationPresent(Embeddable.class)) {
            // @MapKeyClass가 @Embeddable를 가리키면 key를 다중 컬럼으로 펼쳐 저장한다. 펼침 규칙(단순 필드만,
            // @Id/관계/중첩 @Embedded/상속 금지)은 @Embeddable value 원소 펼침과 동일하게 재사용한다.
            List<ElementCollectionInfo.EmbeddableColumn> keyColumns =
                    expandEmbeddableMapKeyColumns(keyType, field, location);
            return ElementCollectionInfo.MapKeyInfo.embeddable(keyType, keyColumns);
        }
        if (keyType.isAnnotationPresent(Entity.class)) {
            // 단일 @Id entity key: 단일컬럼 FK와 저장 규칙이 동일하므로 to-one FK 해석을 재사용한다(reflective,
            // 타겟 metadata를 build하지 않음 — build-order 함정 회피: mid-build 타겟에 getEntityMetadata를 호출하면
            // 순환 참조가 깨진다). 복합 @Id(@EmbeddedId/@IdClass) 타겟은 다중컬럼 키 모델이 필요해 v1 범위 밖이다.
            ForeignKeyStorage idStorage = resolveToOneForeignKeyStorage(keyType);
            if (idStorage == null) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass naming an entity key class " + keyType.getName()
                                + " with a composite @Id (@EmbeddedId/@IdClass) is not supported;"
                                + " only a single-@Id entity map key is supported");
            }
            if (memberPresent(field, MapKeyColumn.class)) {
                throw new IllegalArgumentException(
                        location + " @MapKeyColumn is not valid on an entity map key; use @MapKeyJoinColumn instead");
            }
            MapKeyJoinColumn mapKeyJoinColumn = memberAnnotation(field, MapKeyJoinColumn.class);
            String entityKeyColumnName = mapKeyJoinColumn != null && !mapKeyJoinColumn.name().isBlank()
                    ? mapKeyJoinColumn.name()
                    : namingStrategy.columnName(field.getName()) + "_key";
            Class<?> entityKeyColumnType = idStorage.converterColumnType() != null
                    ? idStorage.converterColumnType()
                    : idStorage.javaType();
            return ElementCollectionInfo.MapKeyInfo.entity(
                    entityKeyColumnName, keyType, entityKeyColumnType, idStorage.converter());
        }
        MapKeyColumn mapKeyColumn = memberAnnotation(field, MapKeyColumn.class);
        String keyColumnName = mapKeyColumn != null && !mapKeyColumn.name().isBlank()
                ? mapKeyColumn.name()
                : namingStrategy.columnName(field.getName()) + "_key";
        MapKeyEnumerated mapKeyEnumerated = memberAnnotation(field, MapKeyEnumerated.class);
        JpaConverterDescriptor explicitKeyConverter = explicitJpaConverter(field, keyType, "key", location);
        if (keyType.isEnum()) {
            EnumType enumType = mapKeyEnumerated != null ? mapKeyEnumerated.value() : EnumType.ORDINAL;
            if (explicitKeyConverter != null) {
                if (mapKeyEnumerated != null) {
                    throw new IllegalStateException(location
                            + " cannot combine @Convert(attributeName=\"key\") with @MapKeyEnumerated");
                }
                return new ElementCollectionInfo.MapKeyInfo(keyColumnName, keyType,
                        explicitKeyConverter.columnType(), null, explicitKeyConverter.instantiate());
            }
            if (mapKeyEnumerated == null && !conversionDisabled(field, "key")) {
                JpaConverterDescriptor automatic = uniqueJpaConverter(keyType, true, location);
                if (automatic != null) {
                    return new ElementCollectionInfo.MapKeyInfo(
                            keyColumnName, keyType, automatic.columnType(), null, automatic.instantiate());
                }
            }
            if (mapKeyEnumerated == null) {
                enumType = inferredEnumType(keyType);
            }
            EnumMapping mapping = resolveEnumMapping(keyType, enumType);
            Class<?> keyColumnType = mapping.customColumnType() != null ? mapping.customColumnType()
                    : enumType == EnumType.STRING ? String.class : Integer.class;
            return new ElementCollectionInfo.MapKeyInfo(
                    keyColumnName, keyType, keyColumnType, enumType, mapping.converter());
        }
        if (explicitKeyConverter != null) {
            if (mapKeyTemporal != null) {
                throw new IllegalStateException(location
                        + " cannot combine @Convert(attributeName=\"key\") with @MapKeyTemporal");
            }
            return new ElementCollectionInfo.MapKeyInfo(keyColumnName, keyType,
                    explicitKeyConverter.columnType(), null, explicitKeyConverter.instantiate());
        }
        if (mapKeyEnumerated != null) {
            throw new IllegalArgumentException(
                    location + " @MapKeyEnumerated is only valid on an enum map key, but the key type is "
                            + keyType.getName());
        }
        // 레거시 temporal map key(java.util.Date/Calendar)는 @MapKeyTemporal(TemporalType)로 저장 정밀도를
        // 결정하고, 스칼라/EC 원소와 동일한 TemporalAttributeConverter 경로를 재사용해 java.time 저장 표현
        // (LocalDate/LocalTime/LocalDateTime)으로 왕복한다. @MapKeyTemporal 없이 Date/Calendar key를 쓰면
        // 매핑이 모호하므로(조용한 기본값 금지) fail-fast로 거부한다.
        if (mapKeyTemporal != null) {
            TemporalType temporalType = mapKeyTemporal.value();
            Class<?> keyColumnType = switch (temporalType) {
                case DATE -> java.time.LocalDate.class;
                case TIME -> java.time.LocalTime.class;
                case TIMESTAMP -> java.time.LocalDateTime.class;
            };
            return new ElementCollectionInfo.MapKeyInfo(
                    keyColumnName, keyType, keyColumnType, null,
                    castConverter(new TemporalAttributeConverter(keyType, temporalType)));
        }
        if (isUtilDate || isCalendar) {
            throw new IllegalArgumentException(
                    location + " maps a java.util.Date/Calendar map key but is missing"
                            + " @MapKeyTemporal(TemporalType.DATE|TIME|TIMESTAMP); the mapping is ambiguous without it");
        }
        Class<?> wrapped = wrapPrimitiveType(keyType);
        if (!conversionDisabled(field, "key")) {
            JpaConverterDescriptor automatic = uniqueJpaConverter(wrapped, true, location);
            if (automatic != null) {
                return new ElementCollectionInfo.MapKeyInfo(
                        keyColumnName, wrapped, automatic.columnType(), null, automatic.instantiate());
            }
        }
        if (!SUPPORTED_MAP_KEY_BASIC_TYPES.contains(wrapped)) {
            throw new IllegalArgumentException(
                    location + " @ElementCollection Map key type " + keyType.getName()
                            + " is not supported; supported key types: String, Integer, Long, Short, Boolean, UUID,"
                            + " or an enum");
        }
        // 기본 타입 key의 저장타입 분리를 스칼라/EC value와 동일 규칙으로 해석한다 — UUID key는 varchar(String) +
        // UuidStringConverter로 저장타입을 분리해 non-String map key 디코딩 함정(varchar→UUID 직접 디코드 불가)을 피한다.
        ElementValueMapping keyStorage = resolveBasicStorageMapping(wrapped);
        return new ElementCollectionInfo.MapKeyInfo(
                keyColumnName, wrapped, keyStorage.columnType(), null, keyStorage.converter());
    }

    /**
     * {@code @ElementCollection}의 {@code @Embeddable} 원소 타입을 collection table 컬럼들로 펼친다. 각 영속
     * 필드 1개당 컬럼 1개를 만들며, 컬럼 이름은 {@code @Column(name=...)} → {@code @AttributeOverride}(이
     * {@code @ElementCollection} 필드에 선언된 것) → naming strategy 순으로 결정한다. v1은 평평한 {@code @Embeddable}만
     * 지원하므로 중첩 {@code @Embedded}/{@code @EmbeddedId}, {@code @Id}, 관계 어노테이션, {@code @ElementCollection}을
     * 가진 컴포넌트는 fail-fast로 거부한다.
     * <p>
     * column uniqueness: 펼친 컬럼들 사이의 중복과 owner FK 컬럼과의 충돌을 한 자리에서 검증해 silent dedupe로
     * 인한 데이터 손상을 막는다.
     */
    private List<ElementCollectionInfo.EmbeddableColumn> expandEmbeddableElementColumns(
            Class<?> elementType, Field collectionField, String location, String ownerForeignKeyColumn) {
        validateRecordComponentsMapped(elementType, location + " @ElementCollection");
        if (hasIdAnnotatedField(elementType)) {
            throw new IllegalArgumentException(
                    "@Embeddable type " + elementType.getName()
                            + " used as @ElementCollection element on " + location
                            + " must not declare @Id-annotated fields");
        }
        // 컬럼 펼침은 getDeclaredFields()만 보므로 superclass(@MappedSuperclass 포함)에서 상속한 필드는
        // 조용히 누락된다. silent 데이터 손실을 막기 위해 상속 구조를 가진 @Embeddable 원소는 fail-fast로 거부한다.
        Class<?> elementSuperclass = elementType.getSuperclass();
        if (elementSuperclass != null && elementSuperclass != Object.class
                && elementSuperclass != java.lang.Record.class) {
            throw new IllegalArgumentException(
                    location + " @ElementCollection of @Embeddable " + elementType.getName()
                            + " must not extend a superclass (" + elementSuperclass.getName()
                            + "); inherited fields would be silently dropped from the collection table");
        }
        // 이 @ElementCollection 필드의 @AttributeOverride(name=field, column=@Column(name=...))를 모은다.
        // "key." 접두사 override는 map key(@Embeddable key) 컬럼용이므로 value 확장에서는 skip한다(키 확장부가
        // 처리한다) — 그러지 않으면 유효한 key override가 value 컴포넌트 필드에 안 맞아 fail-fast로 오거부된다.
        Map<String, String> columnOverrides = new java.util.HashMap<>();
        for (AttributeOverride override : collectionField.getAnnotationsByType(AttributeOverride.class)) {
            if (override.name().startsWith("key.")) {
                continue;
            }
            columnOverrides.put(override.name(), override.column().name());
        }
        // 오타/존재하지 않는 필드를 가리키는 @AttributeOverride는 조용히 무시되면 의도한 컬럼명이 적용되지
        // 않으므로 fail-fast로 거부한다.
        java.util.Set<String> persistableFieldNames = new java.util.HashSet<>();
        for (Field subField : elementType.getDeclaredFields()) {
            if (!isNotPersistable(subField)) {
                persistableFieldNames.add(subField.getName());
            }
        }
        for (String overrideName : columnOverrides.keySet()) {
            if (!persistableFieldNames.contains(overrideName)) {
                throw new IllegalArgumentException(
                        location + " @ElementCollection of @Embeddable " + elementType.getName()
                                + " has @AttributeOverride(name=\"" + overrideName
                                + "\") that does not match any persistent component field");
            }
        }
        List<ElementCollectionInfo.EmbeddableColumn> columns = new ArrayList<>();
        java.util.Set<String> seenColumnNames = new java.util.HashSet<>();
        seenColumnNames.add(ownerForeignKeyColumn);
        for (Field subField : elementType.getDeclaredFields()) {
            if (isNotPersistable(subField)) {
                continue;
            }
            if (subField.isAnnotationPresent(Embedded.class) || subField.isAnnotationPresent(EmbeddedId.class)) {
                throw new IllegalArgumentException(
                        location + " @ElementCollection of @Embeddable " + elementType.getName()
                                + " component " + subField.getName()
                                + " must be a simple field; nested @Embedded is not supported");
            }
            if (subField.isAnnotationPresent(Id.class)
                    || subField.isAnnotationPresent(OneToMany.class)
                    || subField.isAnnotationPresent(ManyToOne.class)
                    || subField.isAnnotationPresent(OneToOne.class)
                    || subField.isAnnotationPresent(ManyToMany.class)
                    || subField.isAnnotationPresent(ElementCollection.class)) {
                throw new IllegalArgumentException(
                        location + " @ElementCollection of @Embeddable " + elementType.getName()
                                + " component " + subField.getName()
                                + " must be a simple value field (no @Id/relationship/@ElementCollection)");
            }
            // @Embedded 서브필드와 동일하게 entity-level 마커(@Version/@SoftDelete/@CreatedAt/@UpdatedAt)는
            // 값 컬렉션 원소에서 의미가 없으므로 거부한다(@Embedded 경로 rejectIllegalSubFieldAnnotations와 정합).
            if (subField.isAnnotationPresent(Version.class)
                    || subField.isAnnotationPresent(SoftDelete.class)
                    || subField.isAnnotationPresent(CreatedAt.class)
                    || subField.isAnnotationPresent(UpdatedAt.class)) {
                throw new IllegalArgumentException(
                        location + " @ElementCollection of @Embeddable " + elementType.getName()
                                + " component " + subField.getName()
                                + " must not declare @Version/@SoftDelete/@CreatedAt/@UpdatedAt");
            }
            String overridden = columnOverrides.get(subField.getName());
            Column column = subField.getAnnotation(Column.class);
            String columnName;
            if (overridden != null && !overridden.isBlank()) {
                columnName = overridden;
            } else if (column != null && !column.name().isBlank()) {
                columnName = column.name();
            } else {
                columnName = namingStrategy.columnName(subField.getName());
            }
            if (!seenColumnNames.add(columnName)) {
                throw new IllegalArgumentException(
                        location + " @ElementCollection of @Embeddable " + elementType.getName()
                                + " produces duplicate column '" + columnName
                                + "'; use @AttributeOverride or @Column to disambiguate");
            }
            subField.setAccessible(true);
            columns.add(resolveEmbeddableCollectionColumn(
                    elementType, subField, columnName, location + " component " + subField.getName()));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                    location + " @ElementCollection of @Embeddable " + elementType.getName()
                            + " has no persistent fields to map as collection columns");
        }
        return columns;
    }

    /**
     * {@code @MapKeyClass}가 가리키는 {@code @Embeddable} map key 타입을 collection table의 key 컬럼들로 펼친다.
     * 각 영속 필드 1개당 컬럼 1개를 만들며, 컬럼 이름은 {@code @AttributeOverride(name="key.<field>", column=@Column(name=...))}
     * → {@code @Column(name=...)} → naming strategy 순으로 정한다({@code key.} 접두사는 JPA map key override 규약이라
     * value 펼침 override와 구분된다). v1은 평평한 {@code @Embeddable}만 지원하므로 중첩 {@code @Embedded}/{@code @EmbeddedId},
     * {@code @Id}, 관계 어노테이션, 상속 계층, entity-level 마커를 가진 key 컴포넌트는 fail-fast로 거부한다. key 컬럼
     * 사이의 중복 이름도 거부한다(owner FK/value 컬럼과의 충돌은 {@link #rejectMapKeyCollision}이 별도 검증).
     */
    private List<ElementCollectionInfo.EmbeddableColumn> expandEmbeddableMapKeyColumns(
            Class<?> keyType, Field collectionField, String location) {
        validateRecordComponentsMapped(keyType, location + " @MapKeyClass");
        if (hasIdAnnotatedField(keyType)) {
            throw new IllegalArgumentException(
                    location + " @MapKeyClass @Embeddable key " + keyType.getName()
                            + " must not declare @Id-annotated fields");
        }
        Class<?> keySuperclass = keyType.getSuperclass();
        if (keySuperclass != null && keySuperclass != Object.class
                && keySuperclass != java.lang.Record.class) {
            throw new IllegalArgumentException(
                    location + " @MapKeyClass @Embeddable key " + keyType.getName()
                            + " must not extend a superclass (" + keySuperclass.getName()
                            + "); inherited fields would be silently dropped from the key columns");
        }
        // 이 컬렉션 필드의 @AttributeOverride 중 "key." 접두사를 가진 것만 map key 컬럼 override로 모은다.
        Map<String, String> keyOverrides = new java.util.HashMap<>();
        for (AttributeOverride override : collectionField.getAnnotationsByType(AttributeOverride.class)) {
            if (override.name().startsWith("key.")) {
                keyOverrides.put(override.name().substring("key.".length()), override.column().name());
            }
        }
        java.util.Set<String> persistableFieldNames = new java.util.HashSet<>();
        for (Field subField : keyType.getDeclaredFields()) {
            if (!isNotPersistable(subField)) {
                persistableFieldNames.add(subField.getName());
            }
        }
        for (String overrideName : keyOverrides.keySet()) {
            if (!persistableFieldNames.contains(overrideName)) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass @Embeddable key " + keyType.getName()
                                + " has @AttributeOverride(name=\"key." + overrideName
                                + "\") that does not match any persistent key component field");
            }
        }
        List<ElementCollectionInfo.EmbeddableColumn> columns = new ArrayList<>();
        java.util.Set<String> seenColumnNames = new java.util.HashSet<>();
        for (Field subField : keyType.getDeclaredFields()) {
            if (isNotPersistable(subField)) {
                continue;
            }
            if (subField.isAnnotationPresent(Embedded.class) || subField.isAnnotationPresent(EmbeddedId.class)) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass @Embeddable key " + keyType.getName()
                                + " component " + subField.getName()
                                + " must be a simple field; nested @Embedded is not supported");
            }
            if (subField.isAnnotationPresent(Id.class)
                    || subField.isAnnotationPresent(OneToMany.class)
                    || subField.isAnnotationPresent(ManyToOne.class)
                    || subField.isAnnotationPresent(OneToOne.class)
                    || subField.isAnnotationPresent(ManyToMany.class)
                    || subField.isAnnotationPresent(ElementCollection.class)) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass @Embeddable key " + keyType.getName()
                                + " component " + subField.getName()
                                + " must be a simple value field (no @Id/relationship/@ElementCollection)");
            }
            if (subField.isAnnotationPresent(Version.class)
                    || subField.isAnnotationPresent(SoftDelete.class)
                    || subField.isAnnotationPresent(CreatedAt.class)
                    || subField.isAnnotationPresent(UpdatedAt.class)) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass @Embeddable key " + keyType.getName()
                                + " component " + subField.getName()
                                + " must not declare @Version/@SoftDelete/@CreatedAt/@UpdatedAt");
            }
            String overridden = keyOverrides.get(subField.getName());
            Column column = subField.getAnnotation(Column.class);
            String columnName;
            if (overridden != null && !overridden.isBlank()) {
                columnName = overridden;
            } else if (column != null && !column.name().isBlank()) {
                columnName = column.name();
            } else {
                columnName = namingStrategy.columnName(subField.getName());
            }
            if (!seenColumnNames.add(columnName)) {
                throw new IllegalArgumentException(
                        location + " @MapKeyClass @Embeddable key " + keyType.getName()
                                + " produces duplicate key column '" + columnName
                                + "'; use @AttributeOverride(name=\"key.<field>\") or @Column to disambiguate");
            }
            subField.setAccessible(true);
            columns.add(resolveEmbeddableCollectionColumn(
                    keyType, subField, columnName, location + " key component " + subField.getName()));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                    location + " @MapKeyClass @Embeddable key " + keyType.getName()
                            + " has no persistent fields to map as key columns");
        }
        return columns;
    }

    private ElementCollectionInfo.EmbeddableColumn resolveEmbeddableCollectionColumn(
            Class<?> embeddableType, Field field, String columnName, String location) {
        if (memberPresent(field, Json.class)) {
            if (memberPresent(field, Enumerated.class) || memberPresent(field, Temporal.class)) {
                throw new IllegalStateException(location + " cannot combine @Json with @Enumerated/@Temporal");
            }
            JpaConverterDescriptor explicit = explicitJpaConverter(
                    field, wrapPrimitiveType(field.getType()), null, location);
            if (explicit != null || converters.containsKey(field.getType())) {
                throw new IllegalStateException(location + " cannot combine @Json with an AttributeConverter");
            }
            return new ElementCollectionInfo.EmbeddableColumn(
                    selectedAttribute(field), columnName, String.class,
                    castConverter(new JsonAttributeConverter(jsonCodec, field.getType())), true);
        }
        ElementValueMapping mapping = resolveBasicElementValueMapping(
                embeddableType, field, field.getType(), location, null);
        return new ElementCollectionInfo.EmbeddableColumn(
                selectedAttribute(field), columnName, mapping.columnType(), mapping.converter(), false);
    }

    private static Class<?> resolveElementCollectionElementType(Class<?> entityType, Field field) {
        ElementCollection annotation = memberAnnotation(field, ElementCollection.class);
        if (annotation.targetClass() != void.class) {
            return annotation.targetClass();
        }
        Type generic = selectedGenericType(field);
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> elementType) {
                return elementType;
            }
        }
        throw new IllegalArgumentException(
                entityType.getName() + "." + field.getName()
                        + " @ElementCollection cannot infer the element type from a raw collection; specify targetClass");
    }

    /**
     * 기본 타입 {@code @ElementCollection} 원소(List/Set 원소 또는 Map value)의 저장 표현 타입과 converter를
     * 스칼라 프로퍼티와 동일한 규칙으로 해석한다. {@code @Enumerated} enum, {@code java.util.UUID},
     * {@code @Convert}, {@code @Temporal}은 저장 표현으로 매핑하고, 그 외 순수 기본 타입은 저장타입=도메인타입
     * 이며 converter가 없다. 조합 불가 어노테이션(@Enumerated+@Convert 등)은 fail-fast로 거부한다. 임의 POJO
     * 등 진짜 미지원 타입은 converter 없이 통과시켜 schema 컬럼 타입 유도({@code elementColumnType})의
     * fail-fast에 맡긴다.
     */
    private ElementValueMapping resolveBasicElementValueMapping(
            Class<?> entityType, Field field, Class<?> elementType, String location, String selector) {
        Class<?> wrapped = wrapPrimitiveType(elementType);
        Enumerated enumerated = memberAnnotation(field, Enumerated.class);
        Temporal temporal = memberAnnotation(field, Temporal.class);
        JpaConverterDescriptor explicit = explicitJpaConverter(field, wrapped, selector, location);
        boolean hasConvert = explicit != null;

        if (enumerated != null) {
            if (!elementType.isEnum()) {
                throw new IllegalArgumentException(location
                        + " is annotated with @Enumerated but its @ElementCollection element type "
                        + elementType.getName() + " is not an enum");
            }
            if (hasConvert || temporal != null) {
                throw new IllegalStateException(location
                        + " cannot combine @Enumerated with @Convert/@Temporal on an @ElementCollection element");
            }
            EnumType enumType = enumerated.value();
            EnumMapping mapping = resolveEnumMapping(elementType, enumType);
            Class<?> columnType = mapping.customColumnType() != null ? mapping.customColumnType()
                    : enumType == EnumType.STRING ? String.class : Integer.class;
            return new ElementValueMapping(columnType, mapping.converter());
        }
        if (hasConvert) {
            if (temporal != null) {
                throw new IllegalStateException(location
                        + " cannot combine @Convert with @Temporal on an @ElementCollection element");
            }
            return new ElementValueMapping(explicit.columnType(), explicit.instantiate());
        }
        boolean isUtilDate = elementType == java.util.Date.class;
        boolean isCalendar = java.util.Calendar.class.isAssignableFrom(elementType);
        if (temporal != null) {
            if (!isUtilDate && !isCalendar) {
                throw new IllegalArgumentException(location
                        + " is annotated with @Temporal but its @ElementCollection element type "
                        + elementType.getName() + " is not java.util.Date or java.util.Calendar");
            }
            TemporalType temporalType = temporal.value();
            Class<?> columnType = switch (temporalType) {
                case DATE -> java.time.LocalDate.class;
                case TIME -> java.time.LocalTime.class;
                case TIMESTAMP -> java.time.LocalDateTime.class;
            };
            return new ElementValueMapping(columnType,
                    castConverter(new TemporalAttributeConverter(elementType, temporalType)));
        }
        if (isUtilDate || isCalendar) {
            throw new IllegalArgumentException(location
                    + " maps a java.util.Date/Calendar @ElementCollection element but is missing"
                    + " @Temporal(TemporalType.DATE|TIME|TIMESTAMP); the mapping is ambiguous without it");
        }
        if (!conversionDisabled(field, selector)) {
            JpaConverterDescriptor automatic = uniqueJpaConverter(wrapped, true, location);
            if (automatic != null) {
                return new ElementValueMapping(automatic.columnType(), automatic.instantiate());
            }
        }
        if (elementType.isEnum()) {
            EnumType enumType = inferredEnumType(elementType);
            EnumMapping mapping = resolveEnumMapping(elementType, enumType);
            Class<?> columnType = mapping.customColumnType() != null ? mapping.customColumnType()
                    : enumType == EnumType.STRING ? String.class : Integer.class;
            return new ElementValueMapping(columnType, mapping.converter());
        }
        // @Enumerated/@Convert/@Temporal이 없는 순수 기본 타입(UUID 포함)은 스칼라 프로퍼티/ map key와 동일한
        // 저장타입 분리 규칙을 공유한다(중복 구현 금지).
        return resolveBasicStorageMapping(wrapped);
    }

    /**
     * {@code @Enumerated}/{@code @Convert}/{@code @Temporal}/등록 converter가 없는 순수 기본 타입의 저장 표현
     * 컬럼 타입과 (선택적) converter를 결정한다 — 스칼라 {@code @Column} 프로퍼티, {@code @ElementCollection}
     * 원소, {@code Map} key가 모두 이 한 자리를 공유해 대칭을 보장한다.
     * <ul>
     *   <li>{@code UUID} — 저장타입 {@link String}(varchar) + {@link UuidStringConverter}. R2DBC 드라이버가
     *       {@code varchar}→{@code UUID} 직접 디코딩을 못 하므로(H2 등) 문자열 경유로 인코드/디코드해
     *       converter read-source-type 함정을 피한다.</li>
     *   <li>그 외 순수 기본 타입(String/Long/Integer/Short/Float/Double/Boolean/BigDecimal 등) — 저장타입=
     *       도메인 타입, converter 없음({@code null}). 실제 SQL 컬럼 타입은 스칼라 {@code sqlType}/EC
     *       {@code elementColumnType}이 그 타입에서 유도한다(Short→smallint, Float→real 등).</li>
     * </ul>
     * {@code wrappedType}은 primitive가 wrapper로 정규화된 타입이어야 한다.
     */
    private static ElementValueMapping resolveBasicStorageMapping(Class<?> wrappedType) {
        if (wrappedType == UUID.class) {
            return new ElementValueMapping(String.class, castConverter(new UuidStringConverter()));
        }
        return new ElementValueMapping(wrappedType, null);
    }

    @SuppressWarnings("unchecked")
    private static AttributeConverter<Object, Object> castConverter(AttributeConverter<?, ?> converter) {
        return (AttributeConverter<Object, Object>) converter;
    }

    /**
     * 기본 타입 {@code @ElementCollection} 원소의 저장 표현 컬럼 타입과 (선택적) converter. converter가
     * {@code null}이면 순수 기본 타입으로 값을 그대로 저장/복원한다.
     */
    private record ElementValueMapping(Class<?> columnType, AttributeConverter<Object, Object> converter) {
    }

    private record EnumMapping(AttributeConverter<Object, Object> converter, Class<?> customColumnType) {
    }

    private static EnumType inferredEnumType(Class<?> enumClass) {
        Field marker = enumeratedValueField(enumClass);
        return marker != null && marker.getType() == String.class ? EnumType.STRING : EnumType.ORDINAL;
    }

    private static EnumMapping resolveEnumMapping(Class<?> enumClass, EnumType enumType) {
        if (enumeratedValueField(enumClass) != null) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            EnumeratedValueConverter<?> converter = new EnumeratedValueConverter(enumClass, enumType);
            return new EnumMapping(castConverter(converter), converter.columnType());
        }
        Class<?> columnType = enumType == EnumType.STRING ? String.class : Integer.class;
        return new EnumMapping(castConverter(createEnumConverter(enumClass, enumType)), null);
    }

    private static Field enumeratedValueField(Class<?> enumClass) {
        Field marker = null;
        for (Field field : enumClass.getDeclaredFields()) {
            if (memberPresent(field, EnumeratedValue.class)) {
                if (marker != null) {
                    throw new IllegalArgumentException("Enum " + enumClass.getName()
                            + " declares more than one @EnumeratedValue field");
                }
                marker = field;
            }
        }
        return marker;
    }

    private static AttributeConverter<?, ?> createEnumConverter(Class<?> enumClass, EnumType enumType) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends Enum> raw = (Class<? extends Enum>) enumClass;
        return createEnumConverterTyped(raw, enumType);
    }

    private static <E extends Enum<E>> AttributeConverter<E, ?> createEnumConverterTyped(
            Class<E> enumClass, EnumType enumType) {
        return switch (enumType) {
            case STRING -> new EnumStringConverter<>(enumClass);
            case ORDINAL -> new EnumOrdinalConverter<>(enumClass);
        };
    }
}
