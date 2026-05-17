package io.nova.sql;

import java.util.List;

public record SqlStatement(String sql, List<Object> bindings) {
    public SqlStatement {
        bindings = List.copyOf(bindings);
    }
}
