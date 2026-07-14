package io.nova.query.storedprocedure;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.sql.Dialect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code @NamedStoredProcedureQuery}로 선언된 명명 저장 프로시저 레지스트리. 구성 시 등록된 엔티티(및
 * {@code @MappedSuperclass}) 클래스들을 {@link NamedStoredProcedureQueryReader}로 스캔해 정의를 이름으로
 * 색인하고, 이름 충돌은 fail-fast로 거부한다(JPA persistence-unit의 전역 유일 명명 규약과 동일).
 * <p>
 * 조회 시 IN 파라미터를 바인딩해 {@link ReactiveStoredProcedureQuery}를 만든다. 기존 엔진의 hub 파일이나
 * 보호 계약 시그니처를 변경하지 않는 격리된 진입점으로,
 * {@link io.nova.query.jpql.NamedQueryRegistry}/{@link io.nova.query.resultset.SqlResultSetMappingRegistry}와
 * 같은 패턴을 따른다.
 */
public final class NamedStoredProcedureRegistry {

    private final ReactiveEntityOperations operations;
    private final Dialect dialect;
    private final EntityMetadataFactory metadataFactory;
    private final Map<String, NamedStoredProcedureDefinition> definitions;

    public NamedStoredProcedureRegistry(
            ReactiveEntityOperations operations,
            Dialect dialect,
            EntityMetadataFactory metadataFactory,
            Iterable<Class<?>> entityClasses) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        Objects.requireNonNull(entityClasses, "entityClasses must not be null");
        Map<String, NamedStoredProcedureDefinition> indexed = new LinkedHashMap<>();
        for (Class<?> type : entityClasses) {
            for (NamedStoredProcedureDefinition definition : NamedStoredProcedureQueryReader.read(type)) {
                NamedStoredProcedureDefinition existing = indexed.putIfAbsent(definition.name(), definition);
                if (existing != null && !existing.equals(definition)) {
                    throw new StoredProcedureException("Duplicate @NamedStoredProcedureQuery '" + definition.name()
                            + "' declared on " + type.getName()
                            + "; named stored procedures must have globally unique names");
                }
            }
        }
        this.definitions = Collections.unmodifiableMap(indexed);
    }

    public NamedStoredProcedureRegistry(
            ReactiveEntityOperations operations,
            Dialect dialect,
            EntityMetadataFactory metadataFactory,
            Class<?>... entityClasses) {
        this(operations, dialect, metadataFactory, List.of(entityClasses));
    }

    /** 명명 프로시저 이름이 등록돼 있는지 반환한다. */
    public boolean contains(String name) {
        return definitions.containsKey(name);
    }

    /** 등록된 명명 프로시저 이름 집합(수정 불가 뷰). */
    public Set<String> names() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    /** 등록된 명명 프로시저 정의를 반환한다. 미등록이면 fail-fast. */
    public NamedStoredProcedureDefinition definition(String name) {
        NamedStoredProcedureDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new StoredProcedureException("No @NamedStoredProcedureQuery registered for '" + name
                    + "'. Declare it with @NamedStoredProcedureQuery and register the entity class.");
        }
        return definition;
    }

    // ----------------------------------------------------------------------------------------
    // Named stored procedures
    // ----------------------------------------------------------------------------------------

    /**
     * 등록된 명명 저장 프로시저 핸들을 만든다. 정의가 단일 {@code resultClass}를 선언하면 그 엔티티로 result-set
     * 행을 매핑하고, 결과 매핑이 없으면 매퍼 없이(=={@code executeUpdate} 전용) 만든다.
     * <p>
     * fail-fast: 정의가 여러 {@code resultClass}를 선언하거나(다중 결과셋 미지원) {@code resultSetMappings}를
     * 참조하면(별도 매퍼 필요) {@link #createNamedStoredProcedureQuery(String, Function)}로 매퍼를 제공하도록
     * 안내한다.
     */
    public ReactiveStoredProcedureQuery<?> createNamedStoredProcedureQuery(String name) {
        NamedStoredProcedureDefinition definition = definition(name);
        Function<RowAccessor, ?> mapper = null;
        if (!definition.resultClasses().isEmpty()) {
            if (definition.resultClasses().size() > 1) {
                throw new StoredProcedureException("@NamedStoredProcedureQuery '" + name
                        + "' declares multiple resultClasses; multiple result sets are not supported");
            }
            mapper = StoredProcedureRowMappers.entity(metadataFactory, definition.resultClasses().get(0));
        } else if (!definition.resultSetMappings().isEmpty()) {
            throw new StoredProcedureException("@NamedStoredProcedureQuery '" + name
                    + "' references a @SqlResultSetMapping; use createNamedStoredProcedureQuery(name, mapper)"
                    + " with a mapper from SqlResultSetMappingRegistry to execute it");
        }
        return build(definition, mapper);
    }

    /**
     * 등록된 명명 저장 프로시저 핸들을 사용자 지정 row {@code mapper}로 만든다.
     * {@code @SqlResultSetMapping} 재사용({@code SqlResultSetMappingRegistry}가 만든 매퍼)이나 임의 투영
     * 매핑에 사용한다.
     */
    public <T> ReactiveStoredProcedureQuery<T> createNamedStoredProcedureQuery(
            String name, Function<RowAccessor, T> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return build(definition(name), mapper);
    }

    private <T> ReactiveStoredProcedureQuery<T> build(
            NamedStoredProcedureDefinition definition, Function<RowAccessor, T> mapper) {
        return new ReactiveStoredProcedureQuery<>(
                definition.procedureName(), definition.parameters(), mapper, operations, dialect);
    }
}
