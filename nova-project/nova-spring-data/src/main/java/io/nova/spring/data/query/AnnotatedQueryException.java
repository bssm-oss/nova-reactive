package io.nova.spring.data.query;

/**
 * {@code @Query} 메서드 정의/실행이 지원 범위를 벗어났을 때 던지는 fail-fast 예외다. 조용한 무시 대신
 * 명시적 메시지로 거부한다(spec: 미지원은 fail-fast).
 */
public final class AnnotatedQueryException extends RuntimeException {

    public AnnotatedQueryException(String message) {
        super(message);
    }
}
