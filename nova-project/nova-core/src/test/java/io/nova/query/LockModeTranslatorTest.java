package io.nova.query;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA {@link LockModeType} → Nova {@link LockMode} + 버전 의미 매핑의 전 조합을 검증한다.
 */
class LockModeTranslatorTest {

    private void assertMapping(LockModeType type, LockMode lockMode, boolean versionCheck, boolean forceIncrement) {
        LockModeTranslator.ResolvedLock resolved = LockModeTranslator.resolve(type);
        assertEquals(lockMode, resolved.lockMode(), () -> type + " lockMode");
        assertEquals(versionCheck, resolved.versionCheck(), () -> type + " versionCheck");
        assertEquals(forceIncrement, resolved.forceIncrement(), () -> type + " forceIncrement");
        assertEquals(lockMode, LockModeTranslator.toLockMode(type), () -> type + " toLockMode");
        assertEquals(versionCheck || forceIncrement, LockModeTranslator.requiresVersion(type),
                () -> type + " requiresVersion");
    }

    @Test
    void none() {
        assertMapping(LockModeType.NONE, LockMode.NONE, false, false);
    }

    @Test
    void optimisticAndReadAlias() {
        assertMapping(LockModeType.OPTIMISTIC, LockMode.NONE, true, false);
        assertMapping(LockModeType.READ, LockMode.NONE, true, false);
    }

    @Test
    void optimisticForceIncrementAndWriteAlias() {
        assertMapping(LockModeType.OPTIMISTIC_FORCE_INCREMENT, LockMode.NONE, true, true);
        assertMapping(LockModeType.WRITE, LockMode.NONE, true, true);
    }

    @Test
    void pessimisticRead() {
        assertMapping(LockModeType.PESSIMISTIC_READ, LockMode.FOR_SHARE, false, false);
    }

    @Test
    void pessimisticWrite() {
        assertMapping(LockModeType.PESSIMISTIC_WRITE, LockMode.FOR_UPDATE, false, false);
    }

    @Test
    void pessimisticForceIncrement() {
        assertMapping(LockModeType.PESSIMISTIC_FORCE_INCREMENT, LockMode.FOR_UPDATE, true, true);
    }

    @Test
    void allLockModeTypesAreMapped() {
        // 8개 LockModeType 값 전부가 총함수로 매핑돼야 한다(누락 시 switch가 컴파일 에러거나 이 루프가 실패).
        for (LockModeType type : LockModeType.values()) {
            LockModeTranslator.ResolvedLock resolved = LockModeTranslator.resolve(type);
            assertTrue(resolved.lockMode() != null, () -> type + " resolved lockMode");
        }
    }

    @Test
    void requiresVersionClassification() {
        assertFalse(LockModeTranslator.requiresVersion(LockModeType.NONE));
        assertFalse(LockModeTranslator.requiresVersion(LockModeType.PESSIMISTIC_READ));
        assertFalse(LockModeTranslator.requiresVersion(LockModeType.PESSIMISTIC_WRITE));
        assertTrue(LockModeTranslator.requiresVersion(LockModeType.OPTIMISTIC));
        assertTrue(LockModeTranslator.requiresVersion(LockModeType.READ));
        assertTrue(LockModeTranslator.requiresVersion(LockModeType.OPTIMISTIC_FORCE_INCREMENT));
        assertTrue(LockModeTranslator.requiresVersion(LockModeType.WRITE));
        assertTrue(LockModeTranslator.requiresVersion(LockModeType.PESSIMISTIC_FORCE_INCREMENT));
    }

    @Test
    void nullIsRejected() {
        assertThrows(NullPointerException.class, () -> LockModeTranslator.resolve(null));
        assertThrows(NullPointerException.class, () -> LockModeTranslator.toLockMode(null));
        assertThrows(NullPointerException.class, () -> LockModeTranslator.requiresVersion(null));
    }
}
