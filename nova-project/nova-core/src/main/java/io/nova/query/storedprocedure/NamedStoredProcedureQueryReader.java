package io.nova.query.storedprocedure;

import jakarta.persistence.NamedStoredProcedureQueries;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.StoredProcedureParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 엔티티 클래스에서 {@code @NamedStoredProcedureQuery}/{@code @NamedStoredProcedureQueries} 애너테이션을 읽어
 * {@link NamedStoredProcedureDefinition} 목록으로 변환한다.
 *
 * <p>{@code @NamedStoredProcedureQuery}는 {@code @Inherited}가 아니므로(JPA 규약) 클래스 계층을
 * {@code getSuperclass}로 직접 walk하며 상위에 선언된 정의도 수집한다 — 같은 이름은 가장 하위(파생) 선언이
 * 우선한다({@link io.nova.graph.NamedEntityGraphReader}, generator 상속 해석과 동일한 패턴).
 *
 * <p>hub 파일을 건드리지 않는 정적 유틸리티다 — {@link io.nova.metadata.EntityMetadataFactory}를 수정하지
 * 않고 W7 저장 프로시저 서브시스템 전용 진입점으로 동작한다.
 *
 * <p>구조적 미지원 요소는 {@link StoredProcedureException}으로 fail-fast 한다: query hint, blank
 * procedureName, resultClasses와 resultSetMappings 동시 선언({@link NamedStoredProcedureDefinition}
 * 생성자가 검증).
 */
public final class NamedStoredProcedureQueryReader {

    private NamedStoredProcedureQueryReader() {
    }

    /** 주어진 엔티티 타입(및 상위 클래스)에 선언된 모든 명명 저장 프로시저를 선언 순서로 읽는다. */
    public static List<NamedStoredProcedureDefinition> read(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        // 하위 클래스가 우선하도록 type→superclass 순으로 훑되, 같은 이름은 먼저 본 것(더 하위)을 유지한다.
        Map<String, NamedStoredProcedureDefinition> byName = new LinkedHashMap<>();
        for (Class<?> current = entityType; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (NamedStoredProcedureQuery annotation : declared(current)) {
                NamedStoredProcedureDefinition definition = convert(annotation, current);
                byName.putIfAbsent(definition.name(), definition);
            }
        }
        return List.copyOf(byName.values());
    }

    /** 이름으로 명명 저장 프로시저를 찾는다. 미등록이면 empty. */
    public static Optional<NamedStoredProcedureDefinition> find(Class<?> entityType, String name) {
        Objects.requireNonNull(name, "name must not be null");
        return read(entityType).stream().filter(d -> d.name().equals(name)).findFirst();
    }

    private static List<NamedStoredProcedureQuery> declared(Class<?> type) {
        List<NamedStoredProcedureQuery> queries = new ArrayList<>();
        NamedStoredProcedureQueries container = type.getDeclaredAnnotation(NamedStoredProcedureQueries.class);
        if (container != null) {
            for (NamedStoredProcedureQuery query : container.value()) {
                queries.add(query);
            }
        }
        NamedStoredProcedureQuery single = type.getDeclaredAnnotation(NamedStoredProcedureQuery.class);
        if (single != null) {
            queries.add(single);
        }
        return queries;
    }

    private static NamedStoredProcedureDefinition convert(NamedStoredProcedureQuery annotation, Class<?> source) {
        if (annotation.hints().length > 0) {
            throw new StoredProcedureException("@NamedStoredProcedureQuery '" + annotation.name() + "' on "
                    + source.getName() + " declares query hints which are not supported");
        }
        List<StoredProcedureParameterDefinition> parameters = new ArrayList<>();
        for (StoredProcedureParameter parameter : annotation.parameters()) {
            Class<?> type = parameter.type() == void.class ? null : parameter.type();
            parameters.add(new StoredProcedureParameterDefinition(parameter.name(), parameter.mode(), type));
        }
        List<Class<?>> resultClasses = List.of(annotation.resultClasses());
        List<String> resultSetMappings = List.of(annotation.resultSetMappings());
        return new NamedStoredProcedureDefinition(
                annotation.name(), annotation.procedureName(), parameters, resultClasses, resultSetMappings);
    }
}
