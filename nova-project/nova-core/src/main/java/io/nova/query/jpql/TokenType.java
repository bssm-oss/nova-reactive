package io.nova.query.jpql;

/**
 * {@link JpqlLexer}가 만들어내는 토큰의 종류. JPQL 문법의 렉시컬 카테고리를 표현한다.
 */
public enum TokenType {
    /** 식별자(엔티티명, 별칭, 필드명, 함수명). 키워드가 아닌 단어. */
    IDENTIFIER,
    /** 예약어(SELECT, FROM, WHERE 등). {@code text}는 원문 그대로이며 대소문자 비교는 파서가 uppercase로 한다. */
    KEYWORD,
    /** 문자열 리터럴. {@code text}는 따옴표를 제거하고 {@code ''} escape를 푼 값이다. */
    STRING,
    /** 숫자 리터럴(정수 또는 소수). {@code text}는 원문. */
    NUMBER,
    /** {@code :name} 형태의 named 파라미터. {@code text}는 {@code name}(콜론 제외). */
    NAMED_PARAM,
    /** {@code ?1} 형태의 positional 파라미터. {@code text}는 숫자 문자열(물음표 제외). */
    POSITIONAL_PARAM,
    /** 연산자/구두점(=, &lt;&gt;, &lt;, &gt;, &lt;=, &gt;=, +, -, *, /, (, ), comma, dot). */
    OPERATOR,
    /** 입력 끝. */
    EOF
}
