package io.nova.query.jpql.ast;

import java.util.List;

/**
 * {@code SELECT NEW com.pkg.Dto(a.x, a.y, COUNT(a))} 생성자 표현식(DTO 프로젝션). {@code className}은
 * 완전 수식 클래스명이고 {@code arguments}는 각 생성자 인자에 대응하는 스칼라 투영식이다. 실행 계층은
 * 각 인자를 SQL 컬럼으로 투영한 뒤, 지정 클래스의 인자 개수/타입이 매칭되는 생성자를 리플렉션으로 찾아
 * 각 행을 인스턴스화한다.
 */
public record ConstructorCall(String className, List<Expression> arguments) {
    public ConstructorCall {
        arguments = List.copyOf(arguments);
    }
}
