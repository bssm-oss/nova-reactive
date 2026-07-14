package io.nova.metadata;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code @Access(AccessType.PROPERTY)} 지원 검증. 클래스 레벨 기본 access type, 멤버 레벨 override,
 * boolean {@code isX} getter, 그리고 getter/setter 부재 시 fail-fast 거부까지 PROPERTY-access
 * property가 getter/setter 경유로 read/write 되는지 본다.
 *
 * <p>access 전략은 생성자 시점에 확정되어 PP에 캐시되므로, 여기서는 metadata 단의 access 분기와
 * {@link PersistentProperty#read}/{@link PersistentProperty#write}가 getter/setter를 호출하는지를
 * (사이드이펙트 플래그로) 직접 관찰한다.
 */
class PersistentPropertyAccessTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void classLevelPropertyAccessReadsAndWritesThroughGetterAndSetter() {
        EntityMetadata<ClassLevelPropertyAccount> metadata =
                factory.getEntityMetadata(ClassLevelPropertyAccount.class);

        PersistentProperty nameProperty = property(metadata, "name");
        assertTrue(nameProperty.propertyAccess(), "클래스 레벨 @Access(PROPERTY)가 멤버에 적용되어야 한다");

        ClassLevelPropertyAccount account = new ClassLevelPropertyAccount();
        nameProperty.write(account, "alice");
        assertTrue(account.setterInvoked, "write는 setter를 거쳐야 한다");
        assertEquals("alice", nameProperty.read(account));
        assertTrue(account.getterInvoked, "read는 getter를 거쳐야 한다");
    }

    @Test
    void memberLevelPropertyAccessOverridesFieldDefault() {
        EntityMetadata<MemberOverrideAccount> metadata =
                factory.getEntityMetadata(MemberOverrideAccount.class);

        PersistentProperty fieldProp = property(metadata, "fieldMapped");
        PersistentProperty propProp = property(metadata, "propertyMapped");

        assertFalse(fieldProp.propertyAccess(), "@Access 없는 멤버는 FIELD 접근(기본)이어야 한다");
        assertNull(fieldProp.propertyAccessGetter());
        assertTrue(propProp.propertyAccess(), "멤버 레벨 @Access(PROPERTY) override가 적용되어야 한다");

        MemberOverrideAccount account = new MemberOverrideAccount();
        propProp.write(account, "via-setter");
        assertEquals("via-setter", propProp.read(account));
        assertTrue(account.setterInvoked && account.getterInvoked,
                "override된 멤버만 getter/setter 경유여야 한다");

        // FIELD-access 멤버는 setter/getter를 거치지 않고 필드를 직접 다룬다.
        fieldProp.write(account, "direct");
        assertEquals("direct", fieldProp.read(account));
    }

    @Test
    void booleanPropertyAccessUsesIsGetter() {
        EntityMetadata<BooleanPropertyAccount> metadata =
                factory.getEntityMetadata(BooleanPropertyAccount.class);

        PersistentProperty activeProp = property(metadata, "active");
        assertTrue(activeProp.propertyAccess());
        assertEquals("isActive", activeProp.propertyAccessGetter().getName(),
                "boolean property는 isX getter를 우선 사용해야 한다");

        BooleanPropertyAccount account = new BooleanPropertyAccount();
        activeProp.write(account, Boolean.TRUE);
        assertEquals(Boolean.TRUE, activeProp.read(account));
    }

    @Test
    void rejectsPropertyAccessWithoutGetter() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> factory.getEntityMetadata(MissingGetterAccount.class));
        assertTrue(exception.getMessage().contains("no JavaBean getter"),
                "getter 부재는 fail-fast로 거부되어야 한다: " + exception.getMessage());
    }

    @Test
    void rejectsPropertyAccessWithoutSetter() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> factory.getEntityMetadata(MissingSetterAccount.class));
        assertTrue(exception.getMessage().contains("no JavaBean setter"),
                "setter 부재는 fail-fast로 거부되어야 한다: " + exception.getMessage());
    }

    @Test
    void classLevelPropertyAccessAppliesToManyToOneRelation() {
        EntityMetadata<PropertyAccessPost> metadata = factory.getEntityMetadata(PropertyAccessPost.class);
        PersistentProperty authorProp = property(metadata, "author");

        assertTrue(authorProp.propertyAccess(),
                "클래스 레벨 @Access(PROPERTY)는 @ManyToOne 관계에도 적용되어야 한다(더 이상 항상 field 접근이 아님)");
        assertNotNull(authorProp.propertyAccessGetter());
        assertNotNull(authorProp.propertyAccessSetter());

        PropertyAccessPost post = new PropertyAccessPost();
        PropertyAccessAuthor author = new PropertyAccessAuthor();
        author.setId(7L);
        authorProp.writeReference(post, author);
        assertTrue(post.authorSetterInvoked, "관계 참조 write는 setter를 경유해야 한다");
        assertSame(author, authorProp.readReference(post));
        assertTrue(post.authorGetterInvoked, "관계 참조 read는 getter를 경유해야 한다");
        // read()는 FK 바인딩용으로 참조 대상의 @Id를 추출한다(getter 경유).
        assertEquals(7L, authorProp.read(post));
    }

    @Test
    void fieldAccessRelationKeepsFieldAccess() {
        EntityMetadata<FieldAccessPost> metadata = factory.getEntityMetadata(FieldAccessPost.class);
        PersistentProperty authorProp = property(metadata, "author");

        assertFalse(authorProp.propertyAccess(), "@Access 없는 관계는 FIELD 접근(기본)이어야 한다");
        assertNull(authorProp.propertyAccessGetter());
        assertNull(authorProp.propertyAccessSetter());
    }

    @Test
    void rejectsPropertyAccessRelationWithoutAccessors() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> factory.getEntityMetadata(MissingRelationAccessorPost.class));
        assertTrue(exception.getMessage().contains("no JavaBean"),
                "관계 getter/setter 부재도 basic과 동일하게 fail-fast여야 한다: " + exception.getMessage());
    }

    private static PersistentProperty property(EntityMetadata<?> metadata, String name) {
        return metadata.properties().stream()
                .filter(p -> p.propertyName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no property " + name));
    }

    @Entity
    @Table(name = "class_level_property_accounts")
    @Access(AccessType.PROPERTY)
    static class ClassLevelPropertyAccount {
        @Id
        private Long id;
        private String name;

        transient boolean getterInvoked;
        transient boolean setterInvoked;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            getterInvoked = true;
            return name;
        }

        public void setName(String name) {
            setterInvoked = true;
            this.name = name;
        }
    }

    @Entity
    @Table(name = "member_override_accounts")
    static class MemberOverrideAccount {
        @Id
        private Long id;

        // @Access 없음 → 엔티티 기본(FIELD).
        @Column(name = "field_mapped")
        private String fieldMapped;

        // 멤버 레벨 override → PROPERTY.
        @Access(AccessType.PROPERTY)
        @Column(name = "property_mapped")
        private String propertyMapped;

        transient boolean getterInvoked;
        transient boolean setterInvoked;

        public String getPropertyMapped() {
            getterInvoked = true;
            return propertyMapped;
        }

        public void setPropertyMapped(String propertyMapped) {
            setterInvoked = true;
            this.propertyMapped = propertyMapped;
        }
    }

    @Entity
    @Table(name = "boolean_property_accounts")
    @Access(AccessType.PROPERTY)
    static class BooleanPropertyAccount {
        @Id
        private Long id;
        private boolean active;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }

    @Entity
    @Table(name = "missing_getter_accounts")
    @Access(AccessType.PROPERTY)
    static class MissingGetterAccount {
        @Id
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        // getName 없음 → PROPERTY access resolve 실패.
        public void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "missing_setter_accounts")
    @Access(AccessType.PROPERTY)
    static class MissingSetterAccount {
        @Id
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }
        // setName 없음 → PROPERTY access resolve 실패.
    }

    // --- relation @Access(PROPERTY) fixtures --------------------------------

    @Entity
    @Table(name = "property_access_authors")
    static class PropertyAccessAuthor {
        @Id
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "property_access_posts")
    @Access(AccessType.PROPERTY)
    static class PropertyAccessPost {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "author_id")
        private PropertyAccessAuthor author;

        transient boolean authorGetterInvoked;
        transient boolean authorSetterInvoked;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public PropertyAccessAuthor getAuthor() {
            authorGetterInvoked = true;
            return author;
        }

        public void setAuthor(PropertyAccessAuthor author) {
            authorSetterInvoked = true;
            this.author = author;
        }
    }

    @Entity
    @Table(name = "field_access_posts")
    static class FieldAccessPost {
        @Id
        private Long id;

        // @Access 없음 → 엔티티 기본(FIELD) 접근.
        @ManyToOne
        @JoinColumn(name = "author_id")
        private PropertyAccessAuthor author;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "missing_relation_accessor_posts")
    @Access(AccessType.PROPERTY)
    static class MissingRelationAccessorPost {
        @Id
        private Long id;

        // getAuthor/setAuthor 없음 → PROPERTY access resolve 실패(fail-fast).
        @ManyToOne
        @JoinColumn(name = "author_id")
        private PropertyAccessAuthor author;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
