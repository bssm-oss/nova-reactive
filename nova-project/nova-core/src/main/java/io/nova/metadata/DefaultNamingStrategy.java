package io.nova.metadata;

public final class DefaultNamingStrategy implements NamingStrategy {
    @Override
    public String tableName(Class<?> entityType) {
        return toSnakeCase(entityType.getSimpleName());
    }

    @Override
    public String columnName(String propertyName) {
        return toSnakeCase(propertyName);
    }

    private String toSnakeCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isUpperCase(current) && i > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }
}
