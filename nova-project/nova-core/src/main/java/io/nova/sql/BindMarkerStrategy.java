package io.nova.sql;

public interface BindMarkerStrategy {

    /**
     * Returns the bind marker for the given 1-based binding index.
     *
     * @param index binding position, starting at 1
     */
    String marker(int index);
}
