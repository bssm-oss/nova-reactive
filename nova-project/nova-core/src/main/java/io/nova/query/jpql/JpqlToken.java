package io.nova.query.jpql;

/**
 * JPQL 소스에서 잘라낸 렉시컬 토큰. {@code position}은 원문 문자열에서의 0-기반 시작 오프셋으로,
 * 파싱 에러 메시지에서 위치를 가리키는 데 쓴다.
 *
 * @param type     토큰 종류
 * @param text     토큰의 정규화된 텍스트(문자열/파라미터는 접두 기호 제거됨)
 * @param position 원문에서의 시작 오프셋
 */
public record JpqlToken(TokenType type, String text, int position) {
    /** KEYWORD/IDENTIFIER를 대소문자 무시 비교하기 위한 uppercase 표현. */
    public String upper() {
        return text.toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return type + "('" + text + "'@" + position + ")";
    }
}
