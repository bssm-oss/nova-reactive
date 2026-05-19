package io.nova.query;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageTest {

    @Test
    void numberSizeAndTotalPagesUseRequestedPageable() {
        Pageable pageable = Pageable.of(10, 20L);

        Page<String> page = new Page<>(List.of("a", "b", "c"), 23L, pageable);

        assertEquals(2, page.number(), "offset / limit = 20 / 10 = 2");
        assertEquals(10, page.size(), "size는 pageable.limit() 그대로");
        assertEquals(3, page.totalPages(), "ceil(23 / 10) = 3");
    }

    @Test
    void hasNextAndHasPreviousReflectPagePositionWithinTotal() {
        Page<String> firstPage = new Page<>(List.of("a"), 30L, Pageable.of(10, 0L));
        Page<String> middlePage = new Page<>(List.of("a"), 30L, Pageable.of(10, 10L));
        Page<String> lastPage = new Page<>(List.of("a"), 30L, Pageable.of(10, 20L));

        assertFalse(firstPage.hasPrevious());
        assertTrue(firstPage.hasNext());
        assertTrue(middlePage.hasPrevious());
        assertTrue(middlePage.hasNext());
        assertTrue(lastPage.hasPrevious());
        assertFalse(lastPage.hasNext(), "마지막 페이지는 hasNext=false");
    }

    @Test
    void nullPageableYieldsZerothPageWithContentSize() {
        Page<String> page = new Page<>(List.of("a", "b"), 2L, null);

        assertEquals(0, page.number(), "pageable이 null이면 number는 0");
        assertEquals(2, page.size(), "pageable이 null이면 size는 content.size()");
        assertEquals(1, page.totalPages(), "ceil(2 / 2) = 1");
        assertFalse(page.hasNext(), "pageable이 null이면 hasNext는 false");
        assertFalse(page.hasPrevious(), "pageable이 null이면 hasPrevious는 false");
    }

    @Test
    void emptyContentWithZeroTotalReportsNoPages() {
        Page<String> page = new Page<>(List.of(), 0L, Pageable.of(10, 0L));

        assertTrue(page.isEmpty());
        assertEquals(0, page.totalPages());
        assertFalse(page.hasNext());
        assertFalse(page.hasPrevious());
    }

    @Test
    void totalPagesIsZeroWhenSizeIsZero() {
        // pageable이 null이고 content가 비어 있으면 size()=0이므로 division-by-zero 방지 가드가 동작해야 한다.
        Page<String> page = new Page<>(List.of(), 0L, null);

        assertEquals(0, page.size());
        assertEquals(0, page.totalPages(), "size가 0이면 totalPages도 0 (NaN/divByZero 방지)");
    }

    @Test
    void contentLargerThanTotalIsTreatedAsSinglePage() {
        // 비정상적인 입력이라도 IllegalState로 throw하지 않고 정의된 산술 결과를 따라야 한다.
        Page<String> page = new Page<>(List.of("a", "b", "c"), 1L, Pageable.of(10, 0L));

        assertEquals(1, page.totalPages(), "ceil(1 / 10) = 1");
        assertFalse(page.hasNext(), "현재 페이지가 마지막이므로 hasNext=false");
    }

    @Test
    void contentIsCopiedDefensivelyAndIsImmutable() {
        List<String> source = new ArrayList<>();
        source.add("a");

        Page<String> page = new Page<>(source, 1L, Pageable.of(10, 0L));
        source.add("b");

        assertEquals(1, page.content().size(), "방어 복사: 외부 추가가 page에 반영되지 않아야 한다");
        assertThrows(UnsupportedOperationException.class, () -> page.content().add("c"),
                "page.content()는 불변이어야 한다");
    }

    @Test
    void nullContentIsRejected() {
        assertThrows(NullPointerException.class, () -> new Page<>(null, 0L, Pageable.of(10, 0L)));
    }

    @Test
    void negativeTotalElementsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Page<>(List.of(), -1L, Pageable.of(10, 0L)));
    }
}
