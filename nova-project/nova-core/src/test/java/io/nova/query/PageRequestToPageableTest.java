package io.nova.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PageRequestToPageableTest {

    @Test
    void first_page_translates_to_zero_offset() {
        Pageable pageable = PageRequest.of(0, 10).toPageable();

        assertEquals(10, pageable.limit());
        assertEquals(0L, pageable.offset());
    }

    @Test
    void third_page_translates_to_size_times_two_offset() {
        Pageable pageable = PageRequest.of(2, 10).toPageable();

        assertEquals(10, pageable.limit());
        assertEquals(20L, pageable.offset());
    }

    @Test
    void large_page_and_size_avoids_int_overflow() {
        int page = 100_000_000;
        int size = 50;
        long expected = (long) page * (long) size;

        Pageable pageable = PageRequest.of(page, size).toPageable();

        assertEquals(size, pageable.limit());
        assertEquals(expected, pageable.offset());
        assertEquals(5_000_000_000L, pageable.offset());
    }

    @Test
    void integer_max_value_page_does_not_overflow() {
        int page = Integer.MAX_VALUE;
        int size = 2;
        long expected = (long) page * (long) size;

        Pageable pageable = PageRequest.of(page, size).toPageable();

        assertEquals(2, pageable.limit());
        assertEquals(expected, pageable.offset());
    }

    @Test
    void next_then_to_pageable_yields_advanced_offset() {
        Pageable pageable = PageRequest.of(0, 25).next().toPageable();

        assertEquals(25, pageable.limit());
        assertEquals(25L, pageable.offset());
    }

    @Test
    void chained_next_to_pageable_accumulates_offset() {
        Pageable pageable = PageRequest.of(0, 15).next().next().next().toPageable();

        assertEquals(15, pageable.limit());
        assertEquals(45L, pageable.offset());
    }

    @Test
    void to_pageable_preserves_size_as_limit_regardless_of_sort() {
        Sort sort = Sort.by(Sort.Order.asc("name"));

        Pageable pageable = PageRequest.of(4, 7, sort).toPageable();

        assertEquals(7, pageable.limit());
        assertEquals(28L, pageable.offset());
    }
}
