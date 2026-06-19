package io.nova.metadata;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @OneToOne}(owning FK / inverse mappedBy) 메타데이터 추출과 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryOneToOneTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void owningSideHasUniqueForeignKeyColumnModeledAsToOne() {
        EntityMetadata<Person> person = factory.getEntityMetadata(Person.class);
        PersistentProperty passport = person.findProperty("passport").orElseThrow();

        // owning @OneToOne은 @ManyToOne과 동일하게 단건 참조(manyToOne=true)로 모델링되며 FK 컬럼을 가진다.
        assertTrue(passport.manyToOne());
        assertFalse(passport.inverseToOne());
        assertEquals("passport_id", passport.columnName());
        assertTrue(passport.unique(), "@OneToOne FK는 unique로 emit돼야 한다");
        assertEquals(Passport.class, passport.manyToOneTargetType());
        // FK 컬럼이 있으므로 column-mapped 뷰에 포함된다.
        assertTrue(person.columnMappedProperties().stream().anyMatch(p -> p.columnName().equals("passport_id")));
    }

    @Test
    void inverseSideIsColumnlessMarker() {
        EntityMetadata<Passport> passport = factory.getEntityMetadata(Passport.class);
        PersistentProperty holder = passport.findProperty("holder").orElseThrow();

        assertTrue(holder.inverseToOne());
        assertFalse(holder.manyToOne());
        assertEquals(Person.class, holder.oneToManyTargetType());
        assertEquals("passport", holder.oneToManyMappedBy());
        // 컬럼이 없으므로 column-mapped 뷰에서 제외되고 inverse 뷰에 포함된다.
        assertFalse(passport.columnMappedProperties().stream().anyMatch(p -> p.propertyName().equals("holder")));
        assertEquals(1, passport.oneToOneInverseProperties().size());
        assertTrue(passport.hasRelationProperties());
    }

    @Test
    void acceptsLazyFetchAsNoOp() {
        // Nova는 lazy proxy가 없어 EAGER/LAZY가 런타임 동일(관계는 FetchGroup으로만 populate).
        // 따라서 @OneToOne(fetch=LAZY)는 예외 없이 빌드되고 FK 컬럼은 EAGER와 동일해야 한다.
        EntityMetadata<LazyOneToOne> lazy = factory.getEntityMetadata(LazyOneToOne.class);
        PersistentProperty passport = lazy.findProperty("passport").orElseThrow();
        assertTrue(passport.manyToOne());
        assertFalse(passport.inverseToOne());
        assertEquals("passport_id", passport.columnName());
        assertTrue(passport.unique(), "@OneToOne FK는 unique로 emit돼야 한다");
        assertEquals(Passport.class, passport.manyToOneTargetType());
        assertTrue(lazy.columnMappedProperties().stream().anyMatch(p -> p.columnName().equals("passport_id")));
    }

    @Test
    void capturesCascadeMetadataOnOwningOneToOne() {
        EntityMetadata<CascadingOneToOne> metadata = factory.getEntityMetadata(CascadingOneToOne.class);
        PersistentProperty passport = metadata.findProperty("passport").orElseThrow();

        // owning @OneToOne(cascade=ALL)은 거부되지 않고 ToOneCascadeInfo로 캡처돼 persist/remove 전파를 켠다.
        assertTrue(passport.manyToOne());
        assertNotNull(passport.toOneCascadeInfo(), "cascade가 지정되면 ToOneCascadeInfo가 채워져야 한다");
        assertTrue(passport.cascadePersistReference(), "CascadeType.ALL은 persist 전파를 켠다");
        assertTrue(passport.cascadeRemoveReference(), "CascadeType.ALL은 remove 전파를 켠다");
    }

    @Test
    void markerOnlyOneToOneHasNullCascadeInfo() {
        EntityMetadata<Person> person = factory.getEntityMetadata(Person.class);
        PersistentProperty passport = person.findProperty("passport").orElseThrow();

        // cascade 미지정 owning @OneToOne은 기존 동작 보존을 위해 toOneCascadeInfo=null이어야 한다.
        assertNull(passport.toOneCascadeInfo(), "cascade 미지정이면 toOneCascadeInfo는 null");
        assertFalse(passport.cascadePersistReference());
        assertFalse(passport.cascadeRemoveReference());
    }

    @Test
    void rejectsCombiningOneToOneWithManyToOne() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(ConflictingRelations.class));
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "person")
    static class Person {
        @Id
        Long id;

        @OneToOne
        @JoinColumn(name = "passport_id")
        Passport passport;
    }

    @Entity
    @Table(name = "passport")
    static class Passport {
        @Id
        Long id;

        @OneToOne(mappedBy = "passport", targetEntity = Person.class)
        Person holder;
    }

    @Entity
    @Table(name = "lazy_one")
    static class LazyOneToOne {
        @Id
        Long id;

        @OneToOne(fetch = FetchType.LAZY)
        Passport passport;
    }

    @Entity
    @Table(name = "cascade_one")
    static class CascadingOneToOne {
        @Id
        Long id;

        @OneToOne(cascade = CascadeType.ALL)
        Passport passport;
    }

    @Entity
    @Table(name = "conflict_one")
    static class ConflictingRelations {
        @Id
        Long id;

        @OneToOne
        @ManyToOne
        Passport passport;
    }
}
