package io.nova.query.storedprocedure;

import java.util.List;
import java.util.Objects;

/**
 * {@code @NamedStoredProcedureQuery}로 엔티티(또는 {@code @MappedSuperclass})에 선언된 명명 저장 프로시저
 * 한 건을 표현하는 immutable value type. {@link NamedStoredProcedureQueryReader}가 애너테이션을 파싱해 이
 * 정의들을 발행하고, {@link NamedStoredProcedureRegistry}가 이름으로 등록·조회한다.
 * <p>
 * {@link #resultClasses()}는 프로시저 결과 행을 매핑할 엔티티 타입들이고, {@link #resultSetMappings()}는
 * 참조하는 {@code @SqlResultSetMapping} 이름들이다. JPA 규약상 둘 중 하나만 선언해야 하며, 둘 다
 * 선언되면 reader가 fail-fast 한다.
 *
 * @param name              전역 고유 명명 프로시저 이름(대소문자 구분, JPA 규약)
 * @param procedureName     실제 데이터베이스 프로시저 이름
 * @param parameters        선언 순서를 보존한 파라미터 목록
 * @param resultClasses     결과 행 매핑 대상 엔티티 타입들(없으면 빈 목록)
 * @param resultSetMappings 참조하는 {@code @SqlResultSetMapping} 이름들(없으면 빈 목록)
 */
public record NamedStoredProcedureDefinition(
        String name,
        String procedureName,
        List<StoredProcedureParameterDefinition> parameters,
        List<Class<?>> resultClasses,
        List<String> resultSetMappings) {

    public NamedStoredProcedureDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(procedureName, "procedureName must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("@NamedStoredProcedureQuery name must not be blank");
        }
        if (procedureName.isBlank()) {
            throw new IllegalArgumentException(
                    "@NamedStoredProcedureQuery '" + name + "' must declare a non-blank procedureName");
        }
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));
        resultClasses = List.copyOf(Objects.requireNonNull(resultClasses, "resultClasses must not be null"));
        resultSetMappings = List.copyOf(Objects.requireNonNull(resultSetMappings, "resultSetMappings must not be null"));
        if (!resultClasses.isEmpty() && !resultSetMappings.isEmpty()) {
            throw new IllegalArgumentException("@NamedStoredProcedureQuery '" + name
                    + "' declares both resultClasses and resultSetMappings; use exactly one result mapping");
        }
    }
}
