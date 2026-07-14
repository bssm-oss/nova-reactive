package io.nova.query.storedprocedure;

/**
 * 저장 프로시저 서브시스템의 구성/실행 오류를 표현하는 unchecked 예외다. 미등록 명명 프로시저 조회,
 * 미지원 파라미터 모드(OUT/INOUT/REF_CURSOR), 바인딩 누락, 잘못된 결과 매핑 요청 등에서 fail-fast로 던진다.
 */
public class StoredProcedureException extends RuntimeException {

    public StoredProcedureException(String message) {
        super(message);
    }

    public StoredProcedureException(String message, Throwable cause) {
        super(message, cause);
    }
}
