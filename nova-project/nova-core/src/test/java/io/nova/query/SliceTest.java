package io.nova.query;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliceTest {

    @Test
    void numberSizeAndHasPreviousAreDerivedFromPageable() {
        Pageable pageable = Pageable.of(5, 10L);

        Slice<String> slice = new Slice<>(List.of("a", "b", "c", "d", "e"), pageable, true);

        assertEquals(2, slice.number(), "offset / limit = 10 / 5 = 2");
        assertEquals(5, slice.size(), "content 실제 크기");
        assertTrue(slice.hasPrevious(), "number > 0 이면 hasPrevious=true");
        assertTrue(slice.hasNext());
    }

    @Test
    void firstPageHasNoPrevious() {
        Slice<String> slice = new Slice<>(List.of("a"), Pageable.of(10, 0L), false);

        assertEquals(0, slice.number());
        assertFalse(slice.hasPrevious(), "첫 페이지는 hasPrevious=false");
        assertFalse(slice.hasNext());
    }

    @Test
    void emptyContentIsAllowedAndIsEmptyReturnsTrue() {
        Slice<String> slice = new Slice<>(List.of(), Pageable.of(10, 0L), false);

        assertTrue(slice.isEmpty());
        assertEquals(0, slice.size());
    }

    @Test
    void sizeReflectsActualContentNotPageableLimit() {
        // 마지막 페이지에서는 content가 pageable.limit() 보다 작을 수 있다 — Slice.size()는 content 실제 크기.
        Slice<String> slice = new Slice<>(List.of("a", "b"), Pageable.of(10, 0L), false);

        assertEquals(2, slice.size(), "Slice.size()는 content.size()를 반환");
    }

    @Test
    void contentIsCopiedDefensivelyAndIsImmutable() {
        List<String> source = new ArrayList<>();
        source.add("a");

        Slice<String> slice = new Slice<>(source, Pageable.of(10, 0L), false);
        source.add("b");

        assertEquals(1, slice.content().size(), "방어 복사: 외부 추가가 slice에 반영되지 않아야 한다");
        assertThrows(UnsupportedOperationException.class, () -> slice.content().add("c"),
                "slice.content()는 불변이어야 한다");
    }

    @Test
    void nullContentIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new Slice<>(null, Pageable.of(10, 0L), false));
    }

    @Test
    void nullPageableIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new Slice<>(List.of("a"), null, false));
    }
}
