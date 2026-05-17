package io.nova.metadata;

public interface NamingStrategy {
    String tableName(Class<?> entityType);

    String columnName(String propertyName);
}
