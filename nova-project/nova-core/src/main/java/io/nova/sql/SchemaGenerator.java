package io.nova.sql;

import io.nova.metadata.EntityMetadata;

public interface SchemaGenerator {
    String createTable(EntityMetadata<?> metadata);
}
