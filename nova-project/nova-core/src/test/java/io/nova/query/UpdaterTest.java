package io.nova.query;

import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {
    @Test
    void ofRequiresEntityType() {
        assertThrows(NullPointerException.class, () -> Updater.of(null));
    }

    @Test
    void freshUpdaterHasNoFieldsAndNoWhere() {
        Updater<SampleAccount> updater = Updater.of(SampleAccount.class);

        assertEquals(SampleAccount.class, updater.entityType());
        assertTrue(updater.fields().isEmpty());
        assertNull(updater.where());
    }

    @Test
    void setRequiresNonBlankFieldName() {
        Updater<SampleAccount> updater = Updater.of(SampleAccount.class);

        assertThrows(NullPointerException.class, () -> updater.set(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> updater.set("", "x"));
        assertThrows(IllegalArgumentException.class, () -> updater.set("   ", "x"));
    }

    @Test
    void whereRequiresNonNullPredicate() {
        Updater<SampleAccount> updater = Updater.of(SampleAccount.class).set("email", "x@nova.io");

        assertThrows(NullPointerException.class, () -> updater.where(null));
    }

    @Test
    void setReturnsNewInstanceAndKeepsInsertionOrder() {
        Updater<SampleAccount> base = Updater.of(SampleAccount.class);
        Updater<SampleAccount> withEmail = base.set("email", "x@nova.io");
        Updater<SampleAccount> withActive = withEmail.set("active", true);

        assertNotSame(base, withEmail);
        assertNotSame(withEmail, withActive);
        assertTrue(base.fields().isEmpty());
        assertEquals(List.of("email"), List.copyOf(withEmail.fields().keySet()));
        assertEquals(List.of("email", "active"), List.copyOf(withActive.fields().keySet()));
        assertEquals("x@nova.io", withActive.fields().get("email"));
        assertEquals(true, withActive.fields().get("active"));
    }

    @Test
    void setOverridesPreviousValueAndMovesToEnd() {
        Updater<SampleAccount> updater = Updater.of(SampleAccount.class)
                .set("email", "first@nova.io")
                .set("active", true)
                .set("email", "second@nova.io");

        LinkedHashMap<String, Object> fields = updater.fields();
        assertEquals(List.of("active", "email"), List.copyOf(fields.keySet()));
        assertEquals("second@nova.io", fields.get("email"));
    }

    @Test
    void whereReturnsNewInstanceWithoutMutatingOriginal() {
        Updater<SampleAccount> base = Updater.of(SampleAccount.class).set("email", "x@nova.io");
        Predicate predicate = Criteria.eq("id", 5L);
        Updater<SampleAccount> withWhere = base.where(predicate);

        assertNotSame(base, withWhere);
        assertNull(base.where());
        assertEquals(predicate, withWhere.where());
        assertEquals(base.fields(), withWhere.fields());
    }

    @Test
    void fieldsViewIsDefensiveCopy() {
        Updater<SampleAccount> updater = Updater.of(SampleAccount.class).set("email", "x@nova.io");
        LinkedHashMap<String, Object> snapshot = updater.fields();
        snapshot.put("active", true);

        assertEquals(List.of("email"), List.copyOf(updater.fields().keySet()));
    }
}
