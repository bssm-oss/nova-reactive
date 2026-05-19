package io.nova.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageRequestTest {

    @Test
    void rejects_negative_page() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.of(-1, 10));
        assertEquals("page must be at least 0", ex.getMessage());
    }

    @Test
    void rejects_zero_or_negative_size() {
        IllegalArgumentException zero = assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.of(0, 0));
        assertEquals("size must be greater than 0", zero.getMessage());

        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(0, -1));
    }

    @Test
    void factory_of_page_size_creates_with_null_sort() {
        PageRequest request = PageRequest.of(3, 25);

        assertEquals(3, request.page());
        assertEquals(25, request.size());
        assertNull(request.sort());
    }

    @Test
    void factory_of_page_size_sort_preserves_sort() {
        Sort sort = Sort.by(Sort.Order.asc("name"));

        PageRequest request = PageRequest.of(1, 20, sort);

        assertEquals(1, request.page());
        assertEquals(20, request.size());
        assertSame(sort, request.sort());
    }

    @Test
    void factory_of_page_size_sort_allows_null_sort() {
        PageRequest request = PageRequest.of(2, 15, null);

        assertNull(request.sort());
    }

    @Test
    void next_increments_page_and_preserves_size_and_sort() {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"));
        PageRequest request = PageRequest.of(4, 30, sort);

        PageRequest next = request.next();

        assertEquals(5, next.page());
        assertEquals(30, next.size());
        assertSame(sort, next.sort());
    }

    @Test
    void previous_decrements_page_and_preserves_size_and_sort() {
        Sort sort = Sort.by(Sort.Order.asc("id"));
        PageRequest request = PageRequest.of(4, 10, sort);

        PageRequest previous = request.previous();

        assertEquals(3, previous.page());
        assertEquals(10, previous.size());
        assertSame(sort, previous.sort());
    }

    @Test
    void previous_from_page_zero_returns_self() {
        PageRequest request = PageRequest.of(0, 10);

        PageRequest previous = request.previous();

        assertSame(request, previous);
        assertEquals(0, previous.page());
    }

    @Test
    void first_resets_page_to_zero_and_preserves_size_and_sort() {
        Sort sort = Sort.by(Sort.Order.asc("name"));
        PageRequest request = PageRequest.of(7, 50, sort);

        PageRequest first = request.first();

        assertEquals(0, first.page());
        assertEquals(50, first.size());
        assertSame(sort, first.sort());
    }

    @Test
    void next_previous_first_chain() {
        PageRequest start = PageRequest.of(0, 10);

        PageRequest afterChain = start.next().next().next().previous().first();

        assertEquals(0, afterChain.page());
        assertEquals(10, afterChain.size());
        assertNull(afterChain.sort());
    }

    @Test
    void null_sort_is_preserved_across_transitions() {
        PageRequest request = PageRequest.of(2, 10);

        assertNull(request.next().sort());
        assertNull(request.previous().sort());
        assertNull(request.first().sort());
    }
}
