package io.nova.query.jpql;

/**
 * 명명 쿼리({@code @NamedQuery}/{@code @NamedNativeQuery}) 등록·조회·실행 실패를 나타내는 예외. 이름 충돌,
 * 미등록 이름, JPQL/네이티브 오용 등 fail-fast 상황에서 던진다.
 */
public class NamedQueryException extends RuntimeException {

    public NamedQueryException(String message) {
        super(message);
    }

    public NamedQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
