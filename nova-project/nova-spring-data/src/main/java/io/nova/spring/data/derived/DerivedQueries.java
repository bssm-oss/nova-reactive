package io.nova.spring.data.derived;

import io.nova.core.ReactiveEntityOperations;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/**
 * derived query 지원의 외부 진입점. {@link DerivedQueryParser}와 {@link DerivedQueryDispatcher}를
 * 한 묶음으로 캡슐화해 호출자는 method/args만 넘기면 된다 — 내부 record/enum 표현은 패키지 밖으로
 * 새지 않는다.
 */
public final class DerivedQueries {

    private final DerivedQueryParser parser;
    private final DerivedQueryDispatcher dispatcher;

    public DerivedQueries(Class<?> entityType, ReactiveEntityOperations operations) {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(operations, "operations");
        this.parser = new DerivedQueryParser(entityType);
        this.dispatcher = new DerivedQueryDispatcher(entityType, operations);
    }

    /**
     * 메서드 이름이 derived query 문법에 맞으면 파싱·실행 결과를 {@link Optional}로 반환한다.
     * 인식하지 못하는 이름은 {@link Optional#empty()} — 호출자는 기존 dispatch 경로의 fallthrough로
     * 처리한다. 이름은 맞지만 의미가 잘못된 경우(unknown property, parameter 수 mismatch 등)는
     * {@link IllegalArgumentException}을 그대로 던진다.
     *
     * <p>반환된 객체는 항상 {@code Mono} 또는 {@code Flux}이며, 정확한 타입은 {@code subject}가 결정한다
     * — {@code findBy} → {@code Flux}, {@code findFirstBy}/{@code findOneBy} → {@code Mono},
     * {@code countBy} → {@code Mono<Long>}, {@code existsBy} → {@code Mono<Boolean>},
     * {@code deleteBy} → {@code Mono<Long>}.
     */
    public Optional<Object> tryDispatch(Method method, Object[] args) {
        return parser.tryParse(method).map(query -> dispatcher.dispatch(query, args));
    }
}
