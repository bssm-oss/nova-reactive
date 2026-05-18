package io.nova.r2dbc;

import io.nova.tx.TransactionContext;
import io.r2dbc.spi.Connection;

/**
 * R2DBC connection을 트랜잭션 리소스로 노출하는 {@link TransactionContext} 구현이다.
 *
 * <p>SUPPORTS/NOT_SUPPORTED/NEVER 같은 propagation 경로에서 활성 트랜잭션 없이
 * callback이 실행될 때는 {@link #connection()}이 {@code null}이고
 * {@link #hasActiveTransaction()}이 {@code false}를 반환한다.
 */
public final class R2dbcTransactionContext implements TransactionContext {
    private final Connection connection;

    public R2dbcTransactionContext(Connection connection) {
        this.connection = connection;
    }

    /**
     * 트랜잭션 리소스로서의 R2DBC connection. 활성 트랜잭션이 없으면 {@code null}이며,
     * 호출자는 먼저 {@link #hasActiveTransaction()}을 확인해야 한다.
     */
    @Override
    public Object resource() {
        return connection;
    }

    /**
     * 활성 트랜잭션의 R2DBC connection. 트랜잭션 없이 실행 중이면 {@code null}이다.
     */
    public Connection connection() {
        return connection;
    }

    @Override
    public boolean hasActiveTransaction() {
        return connection != null;
    }
}
