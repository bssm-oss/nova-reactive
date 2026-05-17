package io.nova.r2dbc;

import io.nova.tx.TransactionContext;
import io.r2dbc.spi.Connection;

public final class R2dbcTransactionContext implements TransactionContext {
    private final Connection connection;

    public R2dbcTransactionContext(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Object resource() {
        return connection;
    }

    public Connection connection() {
        return connection;
    }
}
