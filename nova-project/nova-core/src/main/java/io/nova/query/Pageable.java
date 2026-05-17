package io.nova.query;

public record Pageable(int limit, long offset) {
    public Pageable {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be at least 0");
        }
    }

    public static Pageable of(int limit, long offset) {
        return new Pageable(limit, offset);
    }
}
