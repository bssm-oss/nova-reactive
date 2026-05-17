package io.nova.sql;

public interface BindMarkerStrategy {
    String marker(int index);
}
