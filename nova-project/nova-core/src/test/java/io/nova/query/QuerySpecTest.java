package io.nova.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuerySpecTest {

    @Test
    void emptyHasNoneLockModeByDefault() {
        QuerySpec spec = QuerySpec.empty();

        assertEquals(LockMode.NONE, spec.lockMode());
    }

    @Test
    void fourArgConstructorDefaultsLockModeToNone() {
        QuerySpec spec = new QuerySpec(null, null, null, null);

        assertEquals(LockMode.NONE, spec.lockMode());
    }

    @Test
    void compactConstructorNormalisesNullLockModeToNone() {
        QuerySpec spec = new QuerySpec(null, null, null, null, null);

        assertEquals(LockMode.NONE, spec.lockMode());
    }

    @Test
    void forUpdateProducesForUpdateLockMode() {
        QuerySpec spec = QuerySpec.empty().forUpdate();

        assertEquals(LockMode.FOR_UPDATE, spec.lockMode());
    }

    @Test
    void forShareProducesForShareLockMode() {
        QuerySpec spec = QuerySpec.empty().forShare();

        assertEquals(LockMode.FOR_SHARE, spec.lockMode());
    }

    @Test
    void lockModeReturnsNewInstanceAndDoesNotMutateOriginal() {
        QuerySpec original = QuerySpec.empty();
        QuerySpec withLock = original.lockMode(LockMode.FOR_UPDATE);

        assertNotSame(original, withLock);
        assertEquals(LockMode.NONE, original.lockMode());
        assertEquals(LockMode.FOR_UPDATE, withLock.lockMode());
    }

    @Test
    void lockModeRejectsNull() {
        assertThrows(NullPointerException.class, () -> QuerySpec.empty().lockMode(null));
    }

    @Test
    void lockModePreservesPredicateSortPageableAndCursor() {
        QuerySpec original = QuerySpec.empty()
                .where(Criteria.eq("email", "a@nova.io"))
                .orderBy(Sort.by(Sort.Order.asc("id")))
                .page(Pageable.of(10, 0L))
                .cursor(Cursor.of(CursorField.asc("id", 5L)));

        QuerySpec locked = original.forUpdate();

        assertEquals(LockMode.FOR_UPDATE, locked.lockMode());
        assertSame(original.predicate(), locked.predicate());
        assertSame(original.sort(), locked.sort());
        assertEquals(original.pageable().limit(), locked.pageable().limit());
        assertSame(original.cursor(), locked.cursor());
    }

    @Test
    void whereOrOrderByOrPagePreserveExistingLockMode() {
        QuerySpec base = QuerySpec.empty().forUpdate();

        assertEquals(LockMode.FOR_UPDATE, base.where(Criteria.eq("email", "a@nova.io")).lockMode());
        assertEquals(LockMode.FOR_UPDATE, base.orderBy(Sort.by(Sort.Order.asc("id"))).lockMode());
        assertEquals(LockMode.FOR_UPDATE, base.page(Pageable.of(5, 0L)).lockMode());
    }

    @Test
    void cursorWitherPreservesLockMode() {
        QuerySpec base = QuerySpec.empty()
                .page(Pageable.of(10, 0L))
                .forShare();

        QuerySpec withCursor = base.cursor(Cursor.of(CursorField.asc("id", 1L)));
        QuerySpec withCursorAndLimit = QuerySpec.empty().forShare()
                .cursor(Cursor.of(CursorField.asc("id", 1L)), 10);

        assertEquals(LockMode.FOR_SHARE, withCursor.lockMode());
        assertEquals(LockMode.FOR_SHARE, withCursorAndLimit.lockMode());
    }

    @Test
    void forUpdateThenForShareReplacesLockMode() {
        QuerySpec spec = QuerySpec.empty().forUpdate().forShare();

        assertEquals(LockMode.FOR_SHARE, spec.lockMode());
    }
}
