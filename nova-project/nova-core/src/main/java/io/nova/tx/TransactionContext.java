package io.nova.tx;

/**
 * 트랜잭션 경계 안에서 동작 중인 callback에 전달되는 컨텍스트다. 어댑터별로 underlying
 * connection이나 session 같은 {@link #resource()}를 노출한다.
 *
 * <p>{@code SUPPORTS}/{@code NOT_SUPPORTED}/{@code NEVER}처럼 활성 트랜잭션 없이
 * callback이 실행되는 propagation 경로에서는 {@link #resource()}가 {@code null}일 수 있다.
 * 그런 경우 {@link #hasActiveTransaction()}이 {@code false}를 반환하므로 호출자는 먼저
 * 확인 후 resource에 접근해야 한다.
 */
public interface TransactionContext {
    /**
     * 어댑터별 트랜잭션 리소스(예: R2DBC {@code Connection}). 활성 트랜잭션이 없을 때는
     * 구현체에 따라 {@code null}을 반환할 수 있다. 접근 전에
     * {@link #hasActiveTransaction()}을 확인할 것.
     */
    Object resource();

    /**
     * 이 컨텍스트가 활성 트랜잭션을 보유하는지 여부. 기본값은 {@code true}이며,
     * propagation 결과 트랜잭션 없이 callback이 실행되는 경로에서는 구현체가
     * {@code false}로 override한다.
     */
    default boolean hasActiveTransaction() {
        return true;
    }
}
