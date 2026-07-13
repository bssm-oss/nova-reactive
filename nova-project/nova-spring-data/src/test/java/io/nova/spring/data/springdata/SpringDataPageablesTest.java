package io.nova.spring.data.springdata;

import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Slice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Data {@code Pageable}/{@code Page}/{@code Slice} ↔ Nova {@link Pageable}/{@link Page}/
 * {@link Slice} 양방향 변환 정확성과 경계(unpaged 등)를 검증한다.
 */
class SpringDataPageablesTest {

    @Test
    @DisplayName("paged Spring Pageable은 pageSize→limit, offset→offset으로 Nova Pageable에 매핑")
    void pagedMapsToNova() {
        org.springframework.data.domain.Pageable spring =
                org.springframework.data.domain.PageRequest.of(2, 5);

        Pageable nova = SpringDataPageables.toNova(spring);

        assertEquals(5, nova.limit(), "pageSize → limit");
        assertEquals(10L, nova.offset(), "offset = page * size = 2*5");
    }

    @Test
    @DisplayName("첫 페이지(page 0, size 1) 경계는 Pageable.of(1, 0)으로 매핑")
    void firstPageBoundary() {
        Pageable nova = SpringDataPageables.toNova(
                org.springframework.data.domain.PageRequest.of(0, 1));
        assertEquals(1, nova.limit());
        assertEquals(0L, nova.offset());
    }

    @Test
    @DisplayName("unpaged / null Spring Pageable은 '페이지 제한 없음'을 뜻하는 Nova null로 매핑")
    void unpagedMapsToNull() {
        assertNull(SpringDataPageables.toNova(org.springframework.data.domain.Pageable.unpaged()));
        assertNull(SpringDataPageables.toNova(null));
    }

    @Test
    @DisplayName("Nova Page + 요청 Spring Pageable → Spring Page는 content/total/pageable 보존")
    void novaPageToSpringWithRequestPageable() {
        Pageable novaPageable = Pageable.of(3, 6L); // page index 2
        Page<String> novaPage = new Page<>(List.of("g", "h", "i"), 10L, novaPageable);
        org.springframework.data.domain.Pageable request =
                org.springframework.data.domain.PageRequest.of(2, 3,
                        org.springframework.data.domain.Sort.by("name"));

        org.springframework.data.domain.Page<String> spring =
                SpringDataPageables.toSpring(novaPage, request);

        assertEquals(List.of("g", "h", "i"), spring.getContent());
        assertEquals(10L, spring.getTotalElements());
        assertEquals(4, spring.getTotalPages(), "ceil(10/3)");
        assertEquals(2, spring.getNumber());
        assertEquals(3, spring.getSize());
        assertTrue(spring.hasNext());
        assertTrue(spring.hasPrevious());
        assertTrue(spring.getSort().getOrderFor("name").isAscending(),
                "요청 Pageable의 정렬이 반환 Page에 보존되어야 한다");
    }

    @Test
    @DisplayName("Nova Page 단일 인자 변환은 number/size로 Spring Pageable을 재구성")
    void novaPageToSpringReconstructsPageable() {
        Pageable novaPageable = Pageable.of(2, 4L); // page index 2
        Page<String> novaPage = new Page<>(List.of("e", "f"), 6L, novaPageable);

        org.springframework.data.domain.Page<String> spring = SpringDataPageables.toSpring(novaPage);

        assertEquals(2, spring.getNumber());
        assertEquals(2, spring.getSize());
        assertEquals(6L, spring.getTotalElements());
        assertEquals(3, spring.getTotalPages());
    }

    @Test
    @DisplayName("Nova Slice + 요청 Spring Pageable → Spring Slice는 content/hasNext 보존")
    void novaSliceToSpring() {
        Pageable novaPageable = Pageable.of(4, 0L);
        Slice<String> novaSlice = new Slice<>(List.of("a", "b", "c", "d"), novaPageable, true);
        org.springframework.data.domain.Pageable request =
                org.springframework.data.domain.PageRequest.of(0, 4);

        org.springframework.data.domain.Slice<String> spring =
                SpringDataPageables.toSpring(novaSlice, request);

        assertEquals(List.of("a", "b", "c", "d"), spring.getContent());
        assertTrue(spring.hasNext());
        assertEquals(0, spring.getNumber());
        assertFalse(spring.hasPrevious());
    }

    @Test
    @DisplayName("Nova Slice 단일 인자 변환은 pageable.limit/number로 Spring Pageable을 재구성")
    void novaSliceToSpringReconstructsPageable() {
        Pageable novaPageable = Pageable.of(5, 5L); // page index 1
        Slice<String> novaSlice = new Slice<>(List.of("x", "y"), novaPageable, false);

        org.springframework.data.domain.Slice<String> spring = SpringDataPageables.toSpring(novaSlice);

        assertEquals(1, spring.getNumber());
        assertEquals(5, spring.getSize());
        assertFalse(spring.hasNext());
        assertTrue(spring.hasPrevious());
    }
}
