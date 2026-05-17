package io.nova.core;

public interface RowAccessor {
    <T> T get(String columnName, Class<T> type);
}
