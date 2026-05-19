package io.nova.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LockModeTest {

    @Test
    void declaresThreeLockModesInExpectedOrder() {
        assertArrayEquals(
                new LockMode[]{LockMode.NONE, LockMode.FOR_UPDATE, LockMode.FOR_SHARE},
                LockMode.values()
        );
    }

    @Test
    void valueOfResolvesByName() {
        assertEquals(LockMode.NONE, LockMode.valueOf("NONE"));
        assertEquals(LockMode.FOR_UPDATE, LockMode.valueOf("FOR_UPDATE"));
        assertEquals(LockMode.FOR_SHARE, LockMode.valueOf("FOR_SHARE"));
    }

    @Test
    void enumNamesAreStableForExternalSerialization() {
        assertEquals("NONE", LockMode.NONE.name());
        assertEquals("FOR_UPDATE", LockMode.FOR_UPDATE.name());
        assertEquals("FOR_SHARE", LockMode.FOR_SHARE.name());
    }

    @Test
    void valuesArrayContainsNoNullEntries() {
        for (LockMode mode : LockMode.values()) {
            assertNotNull(mode);
        }
    }
}
