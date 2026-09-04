package io.nova.query.storedprocedure;

import jakarta.persistence.ParameterMode;

import java.util.Objects;

/**
 * 저장 프로시저 파라미터 한 개의 선언을 표현하는 immutable value type. {@code @StoredProcedureParameter}
 * (또는 ad-hoc 등록)로 만들어지며, 선언 순서가 곧 프로시저 호출 시 marker 순서다.
 * <p>
 * {@link #name()}은 named 파라미터면 non-blank, positional 파라미터면 {@code null}이다.
 * {@link #mode()}는 {@link ParameterMode}다. R2DBC SPI 1.0은 OUT/INOUT 선언을 모델링하지만
 * REF_CURSOR에는 portable {@code R2dbcType}이 없고 Nova executor/result API와 H2 baseline은 이식적인
 * 출력 지원을 제공하지 않는다. 따라서 Nova는 {@code IN}만 실행하며
 * {@code OUT}/{@code INOUT}/{@code REF_CURSOR} 선언은 native 작업 전에 fail-fast 한다.
 *
 * @param name 파라미터 이름(positional이면 {@code null})
 * @param mode 파라미터 모드(IN/OUT/INOUT/REF_CURSOR)
 * @param type 파라미터 Java 타입(선언된 타입은 null binding의 R2DBC type에도 사용됨; 선언되지 않았으면
 *             {@code null})
 */
public record StoredProcedureParameterDefinition(String name, ParameterMode mode, Class<?> type) {

    public StoredProcedureParameterDefinition {
        Objects.requireNonNull(mode, "parameter mode must not be null");
        if (name != null && name.isBlank()) {
            // blank 이름은 positional 의도로 간주해 null로 정규화한다(빈 문자열 이름으로 조회 실패 방지).
            name = null;
        }
    }

    /** IN 모드면 {@code true}. */
    public boolean isInput() {
        return mode == ParameterMode.IN;
    }
}
