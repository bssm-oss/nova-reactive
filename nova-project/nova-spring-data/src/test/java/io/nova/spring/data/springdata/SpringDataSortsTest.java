package io.nova.spring.data.springdata;

import io.nova.query.Sort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Data {@code Sort} ↔ Nova {@link Sort} 양방향 변환 정확성과 미지원 옵션 fail-fast를 검증한다.
 */
class SpringDataSortsTest {

    @Test
    @DisplayName("unsorted / null Spring Sort는 Nova null(정렬 없음)로 매핑")
    void unsortedMapsToNull() {
        assertNull(SpringDataSorts.toNova(org.springframework.data.domain.Sort.unsorted()));
        assertNull(SpringDataSorts.toNova(null));
    }

    @Test
    @DisplayName("ASC/DESC 방향과 property가 순서 그대로 Nova Sort로 매핑")
    void directionsAndOrderPreserved() {
        org.springframework.data.domain.Sort springSort = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("name"),
                org.springframework.data.domain.Sort.Order.desc("createdAt"));

        Sort nova = SpringDataSorts.toNova(springSort);

        assertEquals(2, nova.orders().size());
        assertEquals("name", nova.orders().get(0).property());
        assertEquals(Sort.Direction.ASC, nova.orders().get(0).direction());
        assertEquals("createdAt", nova.orders().get(1).property());
        assertEquals(Sort.Direction.DESC, nova.orders().get(1).direction());
    }

    @Test
    @DisplayName("ignore-case 정렬은 조용히 무시하지 않고 IllegalArgumentException으로 fail-fast")
    void ignoreCaseFailsFast() {
        org.springframework.data.domain.Sort springSort = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("name").ignoreCase());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SpringDataSorts.toNova(springSort));
        assertTrue(ex.getMessage().contains("ignore-case"), "message must name the unsupported feature");
    }

    @Test
    @DisplayName("비-NATIVE null-handling(NULLS_FIRST/LAST)은 IllegalArgumentException으로 fail-fast")
    void nonNativeNullHandlingFailsFast() {
        org.springframework.data.domain.Sort nullsFirst = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("name").nullsFirst());
        org.springframework.data.domain.Sort nullsLast = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.desc("name").nullsLast());

        assertThrows(IllegalArgumentException.class, () -> SpringDataSorts.toNova(nullsFirst));
        assertThrows(IllegalArgumentException.class, () -> SpringDataSorts.toNova(nullsLast));
    }

    @Test
    @DisplayName("null / 빈 Nova Sort는 Spring unsorted로 매핑")
    void novaEmptyMapsToUnsorted() {
        assertTrue(SpringDataSorts.toSpring(null).isUnsorted());
        assertTrue(SpringDataSorts.toSpring(Sort.by()).isUnsorted());
    }

    @Test
    @DisplayName("Nova Sort → Spring Sort는 property/direction을 그대로 보존")
    void novaToSpringPreservesOrders() {
        Sort nova = Sort.by(Sort.Order.desc("age"), Sort.Order.asc("email"));

        org.springframework.data.domain.Sort spring = SpringDataSorts.toSpring(nova);

        org.springframework.data.domain.Sort.Order age = spring.getOrderFor("age");
        org.springframework.data.domain.Sort.Order email = spring.getOrderFor("email");
        assertTrue(age.isDescending());
        assertTrue(email.isAscending());
    }

    @Test
    @DisplayName("Spring → Nova → Spring 왕복은 방향/property 동등성 유지")
    void roundTrip() {
        org.springframework.data.domain.Sort original = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("a"),
                org.springframework.data.domain.Sort.Order.desc("b"));

        org.springframework.data.domain.Sort back = SpringDataSorts.toSpring(
                SpringDataSorts.toNova(original));

        assertTrue(back.getOrderFor("a").isAscending());
        assertTrue(back.getOrderFor("b").isDescending());
    }
}
