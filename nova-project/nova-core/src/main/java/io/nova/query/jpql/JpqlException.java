package io.nova.query.jpql;

/**
 * JPQL 렉싱/파싱/변환/실행 단계에서 발생하는 모든 실패의 공통 예외. 미지원 문법은 조용히 무시하지
 * 않고 반드시 이 예외(또는 하위 타입)로 fail-fast한다.
 */
public class JpqlException extends RuntimeException {
    public JpqlException(String message) {
        super(message);
    }

    public JpqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
