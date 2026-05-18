package io.nova.tx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionDefinitionTest {
    @Test
    void defaultIsRequiredAndDefaultIsolationAndNotReadOnly() {
        TransactionDefinition d = TransactionDefinition.DEFAULT;

        assertEquals(Propagation.REQUIRED, d.propagation());
        assertEquals(IsolationLevel.DEFAULT, d.isolation());
        assertFalse(d.readOnly());
    }

    @Test
    void requiresNewFactoryReturnsRequiresNewPropagation() {
        TransactionDefinition d = TransactionDefinition.requiresNew();

        assertEquals(Propagation.REQUIRES_NEW, d.propagation());
        assertEquals(IsolationLevel.DEFAULT, d.isolation());
        assertFalse(d.readOnly());
    }

    @Test
    void asReadOnlyFactoryReturnsReadOnlyDefinition() {
        TransactionDefinition d = TransactionDefinition.asReadOnly();

        assertEquals(Propagation.REQUIRED, d.propagation());
        assertEquals(IsolationLevel.DEFAULT, d.isolation());
        assertTrue(d.readOnly());
    }

    @Test
    void withPropagationCopiesOtherFields() {
        TransactionDefinition base = new TransactionDefinition(
                Propagation.REQUIRED, IsolationLevel.SERIALIZABLE, true);

        TransactionDefinition copy = base.with(Propagation.NESTED);

        assertEquals(Propagation.NESTED, copy.propagation());
        assertEquals(IsolationLevel.SERIALIZABLE, copy.isolation());
        assertTrue(copy.readOnly());
        assertNotSame(base, copy);
    }

    @Test
    void withIsolationCopiesOtherFields() {
        TransactionDefinition base = new TransactionDefinition(
                Propagation.REQUIRES_NEW, IsolationLevel.READ_COMMITTED, true);

        TransactionDefinition copy = base.with(IsolationLevel.REPEATABLE_READ);

        assertEquals(Propagation.REQUIRES_NEW, copy.propagation());
        assertEquals(IsolationLevel.REPEATABLE_READ, copy.isolation());
        assertTrue(copy.readOnly());
    }

    @Test
    void withReadOnlyCopiesOtherFields() {
        TransactionDefinition base = new TransactionDefinition(
                Propagation.MANDATORY, IsolationLevel.SERIALIZABLE, false);

        TransactionDefinition copy = base.withReadOnly(true);

        assertEquals(Propagation.MANDATORY, copy.propagation());
        assertEquals(IsolationLevel.SERIALIZABLE, copy.isolation());
        assertTrue(copy.readOnly());
    }

    @Test
    void constructorRejectsNullPropagation() {
        assertThrows(NullPointerException.class,
                () -> new TransactionDefinition(null, IsolationLevel.DEFAULT, false));
    }

    @Test
    void constructorRejectsNullIsolation() {
        assertThrows(NullPointerException.class,
                () -> new TransactionDefinition(Propagation.REQUIRED, null, false));
    }

    @Test
    void defaultConstantIsCanonical() {
        // DEFAULT는 정적 상수이므로 같은 인스턴스를 재사용해야 한다 (alloc 회피).
        assertSame(TransactionDefinition.DEFAULT, TransactionDefinition.DEFAULT);
    }
}
