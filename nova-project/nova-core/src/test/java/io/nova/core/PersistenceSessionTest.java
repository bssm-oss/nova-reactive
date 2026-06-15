package io.nova.core;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PersistenceSession}의 identity map(같은 PK = 같은 인스턴스)과 스냅샷 기반 dirty diff를 보호한다.
 */
class PersistenceSessionTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void sameIdReturnsSameManagedInstance() {
        EntityMetadata<Person> metadata = factory.getEntityMetadata(Person.class);
        PersistenceSession session = new PersistenceSession();

        Person first = person(1L, "ada", 30);
        Person again = person(1L, "ada", 30);

        Person managed = session.registerOnLoad(metadata, first);
        Person second = session.registerOnLoad(metadata, again);

        assertSame(first, managed);
        assertSame(first, second, "같은 PK 재로드는 기존 인스턴스를 반환해야 한다(identity 보장)");
        assertEquals(1, session.size());
    }

    @Test
    void distinctIdsAreManagedSeparately() {
        EntityMetadata<Person> metadata = factory.getEntityMetadata(Person.class);
        PersistenceSession session = new PersistenceSession();
        session.registerOnLoad(metadata, person(1L, "ada", 30));
        session.registerOnLoad(metadata, person(2L, "ben", 40));
        assertEquals(2, session.size());
    }

    @Test
    void nullIdIsNotManaged() {
        EntityMetadata<Person> metadata = factory.getEntityMetadata(Person.class);
        PersistenceSession session = new PersistenceSession();
        Person transientEntity = person(null, "ada", 30);
        Person result = session.registerOnLoad(metadata, transientEntity);
        assertSame(transientEntity, result);
        assertTrue(session.isEmpty());
    }

    @Test
    void dirtyDiffReportsOnlyChangedColumns() {
        EntityMetadata<Person> metadata = factory.getEntityMetadata(Person.class);
        PersistenceSession session = new PersistenceSession();
        Person person = person(1L, "ada", 30);
        session.registerOnLoad(metadata, person);

        person.name = "ada lovelace"; // mutate one column

        PersistenceSession.ManagedEntry entry = session.managedEntries().iterator().next();
        assertEquals(List.of("name"), entry.dirtyPropertyNames());
    }

    @Test
    void noMutationIsNoOp() {
        EntityMetadata<Person> metadata = factory.getEntityMetadata(Person.class);
        PersistenceSession session = new PersistenceSession();
        session.registerOnLoad(metadata, person(1L, "ada", 30));
        PersistenceSession.ManagedEntry entry = session.managedEntries().iterator().next();
        assertTrue(entry.dirtyPropertyNames().isEmpty());
    }

    @Test
    void refreshSnapshotClearsDirtyState() {
        EntityMetadata<Person> metadata = factory.getEntityMetadata(Person.class);
        PersistenceSession session = new PersistenceSession();
        Person person = person(1L, "ada", 30);
        session.registerOnLoad(metadata, person);
        person.age = 31;
        PersistenceSession.ManagedEntry entry = session.managedEntries().iterator().next();
        assertFalse(entry.dirtyPropertyNames().isEmpty());
        entry.refreshSnapshot();
        assertTrue(entry.dirtyPropertyNames().isEmpty());
    }

    @Test
    void enumeratedColumnComparesByStorageForm() {
        EntityMetadata<Task> metadata = factory.getEntityMetadata(Task.class);
        PersistenceSession session = new PersistenceSession();
        Task task = new Task();
        task.id = 1L;
        task.status = Status.OPEN;
        session.registerOnLoad(metadata, task);
        PersistenceSession.ManagedEntry entry = session.managedEntries().iterator().next();

        task.status = Status.OPEN; // same storage form -> not dirty
        assertTrue(entry.dirtyPropertyNames().isEmpty());

        task.status = Status.DONE; // different storage form -> dirty
        assertEquals(List.of("status"), entry.dirtyPropertyNames());
    }

    @Test
    void compositeKeyDedupesWithoutUserEquals() {
        EntityMetadata<OrderLine> metadata = factory.getEntityMetadata(OrderLine.class);
        PersistenceSession session = new PersistenceSession();

        OrderLine first = orderLine(10L, 1, 5);
        OrderLine again = orderLine(10L, 1, 5);

        OrderLine managed = session.registerOnLoad(metadata, first);
        OrderLine second = session.registerOnLoad(metadata, again);

        assertSame(first, managed);
        assertSame(first, second, "복합키 컴포넌트 값이 같으면 holder equals 없이도 dedupe돼야 한다");
        assertEquals(1, session.size());
    }

    private static Person person(Long id, String name, int age) {
        Person person = new Person();
        person.id = id;
        person.name = name;
        person.age = age;
        return person;
    }

    private static OrderLine orderLine(Long orderId, Integer lineNo, int quantity) {
        OrderLineId key = new OrderLineId();
        key.orderId = orderId;
        key.lineNo = lineNo;
        OrderLine line = new OrderLine();
        line.id = key;
        line.quantity = quantity;
        return line;
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "person")
    static class Person {
        @Id
        Long id;
        String name;
        int age;
    }

    enum Status {
        OPEN, DONE
    }

    @Entity
    @Table(name = "task")
    static class Task {
        @Id
        Long id;
        @Enumerated(EnumType.STRING)
        Status status;
    }

    @Embeddable
    static class OrderLineId {
        Long orderId;
        @Column(name = "line_no")
        Integer lineNo;
        // intentionally no equals/hashCode
    }

    @Entity
    @Table(name = "order_line")
    static class OrderLine {
        @EmbeddedId
        OrderLineId id;
        Integer quantity;
    }
}
