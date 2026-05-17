package io.nova.r2dbc;

import io.nova.core.RowAccessor;
import io.r2dbc.spi.Row;

public final class R2dbcRowAccessor implements RowAccessor {
    private final Row row;

    public R2dbcRowAccessor(Row row) {
        this.row = row;
    }

    @Override
    public <T> T get(String columnName, Class<T> type) {
        return row.get(columnName, type);
    }
}
