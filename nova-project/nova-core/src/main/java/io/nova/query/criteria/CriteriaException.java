package io.nova.query.criteria;

/**
 * Criteria 조립/변환/실행 과정에서 지원하지 않는 구성이나 잘못된 사용을 만나면 던지는 예외.
 * 미지원 Criteria 구성은 조용히 무시하지 않고 이 예외로 fail-fast한다.
 */
public final class CriteriaException extends RuntimeException {

    public CriteriaException(String message) {
        super(message);
    }

    public CriteriaException(String message, Throwable cause) {
        super(message, cause);
    }
}
