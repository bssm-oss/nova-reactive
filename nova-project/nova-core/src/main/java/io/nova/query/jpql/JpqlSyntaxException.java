package io.nova.query.jpql;

/**
 * 렉싱/파싱 단계의 문법 오류 또는 파서가 인지했으나 v1에서 미지원으로 명시 거부하는 구문(JOIN FETCH,
 * TREAT, TYPE 등)에 대해 던진다.
 */
public final class JpqlSyntaxException extends JpqlException {
    public JpqlSyntaxException(String message) {
        super(message);
    }
}
