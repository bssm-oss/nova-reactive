package io.nova.query.storedprocedure;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.query.NativeQuery;
import io.nova.sql.Dialect;
import jakarta.persistence.ParameterMode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 저장 프로시저 한 건에 IN 파라미터를 바인딩해 리액티브로 호출하는 핸들. JPA {@code StoredProcedureQuery}의
 * 리액티브 등가물로, {@code block()} 없이 {@link Flux}/{@link Mono}만 반환한다.
 *
 * <p><b>호출 SQL.</b> {@link Dialect#renderCall(String, int)}가 dialect별 CALL 구문(기본
 * {@code CALL proc(?, ?)})을 만든다 — 실제 CALL 문법은 dialect 모듈이 override로 정한다(nova-core에는
 * DB별 분기를 두지 않는다). IN 파라미터는 선언 순서대로 positional binding으로 채워
 * {@link ReactiveEntityOperations#queryNative}/{@link ReactiveEntityOperations#executeNative}에 위임한다.
 *
 * <p><b>파라미터 모드.</b> JPA SPI는 출력 파라미터 선언을 모델링할 수 있지만 Nova의 리액티브 R2DBC 경로와
 * r2dbc-h2 계약은 이를 이식성 있게 노출하지 않는다. 따라서 {@link ParameterMode#OUT}/
 * {@link ParameterMode#INOUT}/{@link ParameterMode#REF_CURSOR} 파라미터가 하나라도 선언되면 모든 실행
 * 경로에서 binding이나 native 작업 전에 {@link StoredProcedureException}으로 fail-fast 한다(조용한 무시
 * 금지). 결과가 필요하면 IN 파라미터 + result-set 을 반환하는 프로시저를 사용한다.
 *
 * <p><b>IN 바인딩 검증.</b> {@link #setParameter(String, Object)}와
 * {@link #setParameter(int, Object)}는 이름/1-based 위치가 선언된 파라미터를 가리키는지와 non-null 값이
 * 선언 Java 타입(primitive 선언은 wrapper 타입)과 호환되는지를 즉시 검증한다. {@code null}은 허용하며,
 * 값 coercion이나 선언되지 않은 기본 인자는 제공하지 않는다.
 *
 * <p><b>결과 매핑.</b> 생성 시 주입된 {@code mapper}(엔티티 {@code resultClass} 매핑, {@code @SqlResultSetMapping}
 * 재사용 매퍼, 또는 사용자 지정 row 매퍼)로 result-set 행을 변환한다. 매퍼가 없으면 {@link #executeUpdate()}로만
 * 실행할 수 있고, {@link #getResultList()}는 에러 신호를 낸다.
 *
 * @param <T> 결과 원소 타입
 */
public final class ReactiveStoredProcedureQuery<T> {

    private final String procedureName;
    private final List<StoredProcedureParameterDefinition> parameters;
    private final Function<RowAccessor, T> mapper;
    private final ReactiveEntityOperations operations;
    private final Dialect dialect;

    private final Map<String, Object> namedValues = new HashMap<>();
    private final Map<Integer, Object> positionalValues = new HashMap<>();

    /**
     * ad-hoc 저장 프로시저 핸들을 만든다. 명명 프로시저는 {@link NamedStoredProcedureRegistry}가, 결과 매핑은
     * {@code mapper}(null이면 {@code executeUpdate} 전용)가 담당한다.
     */
    public ReactiveStoredProcedureQuery(
            String procedureName,
            List<StoredProcedureParameterDefinition> parameters,
            Function<RowAccessor, T> mapper,
            ReactiveEntityOperations operations,
            Dialect dialect) {
        this.procedureName = Objects.requireNonNull(procedureName, "procedureName must not be null");
        this.parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));
        this.mapper = mapper;
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
    }

    /**
     * 이름으로 IN 파라미터 값을 바인딩한다.
     *
     * @throws StoredProcedureException 이름이 blank이거나 선언되지 않았거나, non-null 값이 선언 타입과
     *         호환되지 않을 때
     */
    public ReactiveStoredProcedureQuery<T> setParameter(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new StoredProcedureException("stored procedure parameter name must not be null or blank");
        }
        StoredProcedureParameterDefinition parameter = parameterByName(name);
        validateValue(parameter, value, "'" + name + "'");
        namedValues.put(name, value);
        return this;
    }

    /**
     * 1-based 위치로 IN 파라미터 값을 바인딩한다(JPA 규약과 동일하게 위치는 1부터 센다).
     * Named 선언도 선언 순서의 위치로 바인딩할 수 있다.
     *
     * @throws StoredProcedureException 위치가 선언되지 않았거나, non-null 값이 선언 타입과 호환되지 않을 때
     */
    public ReactiveStoredProcedureQuery<T> setParameter(int position, Object value) {
        StoredProcedureParameterDefinition parameter = parameterByPosition(position);
        validateValue(parameter, value, "at position " + position);
        positionalValues.put(position, value);
        return this;
    }

    // --------------------------------------------------------------------------------------------
    // Execution
    // --------------------------------------------------------------------------------------------

    /** 프로시저 result-set 을 매핑해 발행한다. 매퍼가 없으면 에러 신호. */
    public Flux<T> getResultList() {
        if (mapper == null) {
            return Flux.error(new StoredProcedureException("stored procedure '" + procedureName
                    + "' has no result mapping; declare resultClasses/resultSetMappings on"
                    + " @NamedStoredProcedureQuery or pass a row mapper, and use executeUpdate() for"
                    + " procedures without a result set"));
        }
        return Flux.defer(() -> operations.queryNative(toNativeQuery(), mapper));
    }

    /**
     * 정확히 한 건의 결과를 발행한다. 결과가 없으면 에러(JPA {@code NoResultException} 등가), 두 건 이상이면
     * 에러(JPA {@code NonUniqueResultException} 등가)를 낸다.
     */
    public Mono<T> getSingleResult() {
        return getResultList().take(2).collectList().flatMap(list -> {
            if (list.isEmpty()) {
                return Mono.error(new StoredProcedureException("getSingleResult() found no rows"));
            }
            if (list.size() > 1) {
                return Mono.error(new StoredProcedureException("getSingleResult() found more than one row"));
            }
            return Mono.just(list.get(0));
        });
    }

    /**
     * result-set 을 반환하지 않는 프로시저를 실행하고 영향 행 수를 발행한다(JPA {@code executeUpdate} 등가).
     */
    public Mono<Long> executeUpdate() {
        return Mono.defer(() -> operations.executeNative(toNativeQuery()));
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------

    private NativeQuery toNativeQuery() {
        for (int i = 0; i < parameters.size(); i++) {
            StoredProcedureParameterDefinition parameter = parameters.get(i);
            if (parameter.mode() != ParameterMode.IN) {
                throw new StoredProcedureException("stored procedure '" + procedureName + "' declares a "
                        + parameter.mode() + " parameter"
                        + (parameter.name() == null ? " at position " + (i + 1) : " '" + parameter.name() + "'")
                        + "; the reactive R2DBC path (and the r2dbc-h2 driver) does not support output"
                        + " parameters. Use IN parameters with a result-set procedure instead.");
            }
        }

        List<Object> bindings = new ArrayList<>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            bindings.add(resolveBinding(parameters.get(i), i + 1));
        }
        return new NativeQuery(dialect.renderCall(procedureName, parameters.size()), bindings);
    }

    private Object resolveBinding(StoredProcedureParameterDefinition parameter, int position) {
        if (parameter.name() != null) {
            if (namedValues.containsKey(parameter.name())) {
                return namedValues.get(parameter.name());
            }
            // named 파라미터도 1-based 위치로 바인딩할 수 있게 허용한다(JPA에서도 혼용 가능).
            if (positionalValues.containsKey(position)) {
                return positionalValues.get(position);
            }
            throw new StoredProcedureException("Missing binding for stored procedure parameter '"
                    + parameter.name() + "' (position " + position + ") of '" + procedureName + "'");
        }
        if (positionalValues.containsKey(position)) {
            return positionalValues.get(position);
        }
        throw new StoredProcedureException("Missing binding for stored procedure positional parameter "
                + position + " of '" + procedureName + "'");
    }

    private StoredProcedureParameterDefinition parameterByName(String name) {
        for (StoredProcedureParameterDefinition parameter : parameters) {
            if (name.equals(parameter.name())) {
                return parameter;
            }
        }
        throw new StoredProcedureException("Unknown stored procedure parameter '" + name + "' of '"
                + procedureName + "'");
    }

    private StoredProcedureParameterDefinition parameterByPosition(int position) {
        if (position < 1 || position > parameters.size()) {
            throw new StoredProcedureException("Unknown stored procedure parameter position " + position
                    + " of '" + procedureName + "' (positions are 1-based)");
        }
        return parameters.get(position - 1);
    }

    private void validateValue(StoredProcedureParameterDefinition parameter, Object value, String reference) {
        Class<?> declaredType = parameter.type();
        if (value != null && declaredType != null && !boxedType(declaredType).isInstance(value)) {
            throw new StoredProcedureException("Stored procedure parameter " + reference + " of '" + procedureName
                    + "' declares Java type " + declaredType.getName() + " but received "
                    + value.getClass().getName());
        }
    }

    private static Class<?> boxedType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == void.class) return Void.class;
        throw new IllegalArgumentException("Unknown primitive type: " + type);
    }
}
