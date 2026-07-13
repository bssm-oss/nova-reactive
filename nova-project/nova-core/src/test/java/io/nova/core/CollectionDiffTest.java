package io.nova.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세션 컬렉션 flush 최소 diff(Stage 2/3)의 순수 계산 로직 — baseline 대비 제거/추가된 키 집합과 중복 감지 —
 * 을 SQL/DB 없이 검증한다. {@link SimpleReactiveEntityOperations}의 package-private static 헬퍼를 직접 호출한다.
 */
class CollectionDiffTest {

    @Test
    void removedKeysReturnsBaselineOnlyElementsInBaselineOrder() {
        List<Object> baseline = List.of(1L, 2L, 3L);
        List<Object> current = List.of(2L, 4L);
        assertEquals(List.of(1L, 3L), SimpleReactiveEntityOperations.removedKeys(baseline, current));
    }

    @Test
    void addedKeysReturnsCurrentOnlyElementsInCurrentOrder() {
        List<Object> baseline = List.of(1L, 2L, 3L);
        List<Object> current = List.of(2L, 4L, 5L);
        assertEquals(List.of(4L, 5L), SimpleReactiveEntityOperations.addedKeys(baseline, current));
    }

    @Test
    void noChangeYieldsEmptyRemovedAndAdded() {
        List<Object> baseline = List.of("a", "b");
        List<Object> current = List.of("b", "a");
        assertTrue(SimpleReactiveEntityOperations.removedKeys(baseline, current).isEmpty());
        assertTrue(SimpleReactiveEntityOperations.addedKeys(baseline, current).isEmpty());
    }

    @Test
    void singleAddYieldsOneAddedZeroRemoved() {
        List<Object> baseline = List.of("a", "b");
        List<Object> current = List.of("a", "b", "c");
        assertEquals(List.of(), SimpleReactiveEntityOperations.removedKeys(baseline, current));
        assertEquals(List.of("c"), SimpleReactiveEntityOperations.addedKeys(baseline, current));
    }

    @Test
    void singleRemoveYieldsOneRemovedZeroAdded() {
        List<Object> baseline = List.of("a", "b", "c");
        List<Object> current = List.of("a", "c");
        assertEquals(List.of("b"), SimpleReactiveEntityOperations.removedKeys(baseline, current));
        assertEquals(List.of(), SimpleReactiveEntityOperations.addedKeys(baseline, current));
    }

    @Test
    void emptyBaselineTreatsAllCurrentAsAdded() {
        List<Object> baseline = List.of();
        List<Object> current = List.of(10L, 20L);
        assertEquals(List.of(), SimpleReactiveEntityOperations.removedKeys(baseline, current));
        assertEquals(List.of(10L, 20L), SimpleReactiveEntityOperations.addedKeys(baseline, current));
    }

    @Test
    void emptyCurrentTreatsAllBaselineAsRemoved() {
        List<Object> baseline = List.of(10L, 20L);
        List<Object> current = List.of();
        assertEquals(List.of(10L, 20L), SimpleReactiveEntityOperations.removedKeys(baseline, current));
        assertEquals(List.of(), SimpleReactiveEntityOperations.addedKeys(baseline, current));
    }

    @Test
    void hasDuplicateKeysDetectsBagSemantics() {
        assertFalse(SimpleReactiveEntityOperations.hasDuplicateKeys(List.of(1L, 2L, 3L)));
        assertTrue(SimpleReactiveEntityOperations.hasDuplicateKeys(List.of(1L, 2L, 2L)));
        assertFalse(SimpleReactiveEntityOperations.hasDuplicateKeys(List.of()));
    }
}
