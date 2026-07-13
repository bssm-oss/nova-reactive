package io.nova.metadata;

import io.nova.annotation.CreatedAt;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import io.nova.annotation.SoftDelete;
import io.nova.annotation.UpdatedAt;
import jakarta.persistence.Version;
import io.nova.support.fixtures.FixtureEntities.AuthorBookJoinColumnConflict;
import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
import io.nova.support.fixtures.FixtureEntities.MisplacedForeignKeyEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityMetadataFactory}가 {@code @ManyToOne}, {@code @OneToMany}, {@code @JoinColumn}을
 * 읽어 marker property를 만들고, 양립 불가능한 조합과 column 충돌을 거부하는지 검증한다.
 */
class EntityMetadataFactoryRelationTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void manyToOnePropertyExposesFkColumnAndTargetType() {
        EntityMetadata<BookWithAuthorAnnotated> metadata = factory.getEntityMetadata(BookWithAuthorAnnotated.class);

        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        PersistentProperty author = relations.get(0);
        assertEquals("author", author.propertyName());
        assertEquals("author_id", author.columnName(), "@JoinColumn.name overrides default naming");
        assertTrue(author.manyToOne());
        assertFalse(author.oneToMany());
        assertSame(AuthorWithBooksAnnotated.class, author.manyToOneTargetType());
        assertTrue(author.isRelation());
    }

    @Test
    void manyToOneWithoutJoinColumnFallsBackToDefaultNaming() {
        EntityMetadata<BookWithDefaultFkName> metadata = factory.getEntityMetadata(BookWithDefaultFkName.class);

        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        // default naming = snake_case(propertyName + "_id") => "owner_id"
        assertEquals("owner_id", relations.get(0).columnName());
    }

    @Test
    void oneToManyMarkerHasNoColumnAndCarriesMappedBy() {
        EntityMetadata<AuthorWithBooksAnnotated> metadata = factory.getEntityMetadata(AuthorWithBooksAnnotated.class);

        List<PersistentProperty> inverse = metadata.oneToManyProperties();
        assertEquals(1, inverse.size());
        PersistentProperty books = inverse.get(0);
        assertEquals("books", books.propertyName());
        assertEquals("author", books.oneToManyMappedBy());
        assertSame(BookWithAuthorAnnotated.class, books.oneToManyTargetType());
        assertTrue(books.oneToMany());
        assertFalse(books.manyToOne());
        // properties()는 raw list라 books도 포함되지만, columnMappedProperties()는 컬럼 없는
        // marker를 제외하므로 books는 그 결과에서 제외된다.
        assertTrue(metadata.properties().stream().anyMatch(p -> p.propertyName().equals("books")));
        assertFalse(metadata.columnMappedProperties().stream().anyMatch(p -> p.propertyName().equals("books")));
    }

    @Test
    void explicitForeignKeyOnNonRelationColumnIsRejected() {
        // @JoinColumn(foreignKey=@ForeignKey(name=...))가 @ManyToOne/@OneToOne 없는 일반 컬럼에 붙으면
        // 조용히 무시하지 않고 fail-fast로 거부해야 한다.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.getEntityMetadata(MisplacedForeignKeyEntity.class));
        assertTrue(error.getMessage().contains("@JoinColumn(foreignKey=...)"));
    }

    @Test
    void joinColumnConflictWithRegularColumnIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(AuthorBookJoinColumnConflict.class));
        assertTrue(error.getMessage().contains("duplicate column"));
        assertTrue(error.getMessage().contains("author_id"));
    }

    @Test
    void manyToOneAndOneToManyOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(BothRelationsEntity.class));
    }

    @Test
    void embeddedAndRelationOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(EmbeddedAndManyToOneEntity.class));
    }

    @Test
    void idAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(IdAndManyToOneEntity.class));
    }

    @Test
    void versionAndOneToManyOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(VersionAndOneToManyEntity.class));
    }

    @Test
    void softDeleteAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(SoftDeleteAndManyToOneEntity.class));
    }

    @Test
    void createdAtAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(CreatedAtAndManyToOneEntity.class));
    }

    @Test
    void updatedAtAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(UpdatedAtAndManyToOneEntity.class));
    }

    @Test
    void enumeratedAndOneToManyOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(EnumeratedAndOneToManyEntity.class));
    }

    @Test
    void oneToManyWithBlankMappedByRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(BlankMappedByEntity.class));
    }

    @Test
    void manyToOneNullabilityIsStrictWhenJoinColumnNullableIsFalse() {
        EntityMetadata<NonNullJoinColumnEntity> metadata =
                factory.getEntityMetadata(NonNullJoinColumnEntity.class);
        PersistentProperty author = metadata.manyToOneProperties().get(0);
        assertFalse(author.manyToOneNullable());
        assertFalse(author.nullable());
    }

    @Test
    void manyToOneLazyFetchBuildsIdenticalFkColumnAsEager() {
        // Nova는 lazy proxy가 없어 EAGER/LAZY가 런타임 동일(관계는 FetchGroup으로만 populate).
        // fetch=LAZY는 예외 없이 빌드되고, FK 컬럼 매핑은 EAGER와 byte-for-byte 동일해야 한다.
        PersistentProperty eager =
                factory.getEntityMetadata(EagerManyToOneEntity.class).manyToOneProperties().get(0);
        PersistentProperty lazy =
                factory.getEntityMetadata(LazyManyToOneEntity.class).manyToOneProperties().get(0);

        assertEquals("owner_id", eager.columnName());
        assertEquals(eager.columnName(), lazy.columnName());
        assertEquals(eager.manyToOne(), lazy.manyToOne());
        assertEquals(eager.nullable(), lazy.nullable());
        assertEquals(eager.manyToOneNullable(), lazy.manyToOneNullable());
        assertSame(eager.manyToOneTargetType(), lazy.manyToOneTargetType());
        assertTrue(lazy.manyToOne());
        assertFalse(lazy.oneToMany());
    }

    @Test
    void hasRelationPropertiesReflectsAnnotationsPresent() {
        assertTrue(factory.getEntityMetadata(AuthorWithBooksAnnotated.class).hasRelationProperties());
        assertTrue(factory.getEntityMetadata(BookWithAuthorAnnotated.class).hasRelationProperties());
    }

    @Test
    void manyToOneFkColumnTypeReflectsUuidTargetId() {
        PersistentProperty fk = factory.getEntityMetadata(RefToUuidKeyed.class).manyToOneProperties().get(0);
        // 도메인 타입은 참조 @Id 타입(UUID), 저장/컬럼 타입은 varchar(String) via UuidStringConverter.
        assertSame(UUID.class, fk.javaType());
        assertSame(String.class, fk.columnType());
        // 인코딩: FK 값(UUID)이 저장타입 String으로 변환되어 바인딩된다.
        UUID id = UUID.randomUUID();
        Object encoded = fk.toColumnValue(id);
        assertEquals(id.toString(), encoded);
        // 디코딩: 저장타입 String이 도메인 UUID로 복원된다(read-source-type 대칭).
        assertEquals(id, fk.toPropertyValue(id.toString()));
    }

    @Test
    void manyToOneFkColumnTypeReflectsStringTargetId() {
        PersistentProperty fk = factory.getEntityMetadata(RefToStringKeyed.class).manyToOneProperties().get(0);
        assertSame(String.class, fk.javaType());
        assertSame(String.class, fk.columnType());
        assertEquals("abc", fk.toColumnValue("abc"), "String FK는 변환 없이 그대로 바인딩된다");
    }

    @Test
    void manyToOneFkColumnTypeReflectsIntegerTargetId() {
        PersistentProperty fk = factory.getEntityMetadata(RefToIntegerKeyed.class).manyToOneProperties().get(0);
        assertSame(Integer.class, fk.javaType());
        assertSame(Integer.class, fk.columnType());
    }

    @Test
    void manyToOneFkColumnTypeReflectsShortTargetId() {
        PersistentProperty fk = factory.getEntityMetadata(RefToShortKeyed.class).manyToOneProperties().get(0);
        assertSame(Short.class, fk.javaType());
        assertSame(Short.class, fk.columnType());
    }

    @Test
    void manyToOneFkColumnTypeStaysLongForLongTargetIdWithoutRegression() {
        // 현행 다수 케이스(Long @Id): FK는 bigint(Long)를 그대로 유지하고 converter가 없다.
        PersistentProperty fk = factory.getEntityMetadata(BookWithAuthorAnnotated.class).manyToOneProperties().get(0);
        assertSame(Long.class, fk.javaType());
        assertSame(Long.class, fk.columnType());
        Long id = 7L;
        assertSame(id, fk.toColumnValue(id), "Long FK는 converter 없이 값을 그대로 바인딩한다");
    }

    @Test
    void owningOneToOneFkColumnTypeReflectsUuidTargetId() {
        PersistentProperty fk = factory.getEntityMetadata(OwningOneToOneToUuidKeyed.class).manyToOneProperties().get(0);
        assertTrue(fk.manyToOne(), "owning @OneToOne은 @ManyToOne과 동일하게 모델링된다");
        assertSame(UUID.class, fk.javaType());
        assertSame(String.class, fk.columnType());
    }

    @Entity
    static class BookWithDefaultFkName {
        @Id
        private Long id;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private AuthorWithBooksAnnotated owner;

        BookWithDefaultFkName() {
        }
    }

    @Entity
    static class EagerManyToOneEntity {
        @Id
        private Long id;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        @JoinColumn(name = "owner_id")
        private AuthorWithBooksAnnotated owner;

        EagerManyToOneEntity() {
        }
    }

    @Entity
    static class LazyManyToOneEntity {
        @Id
        private Long id;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class, fetch = FetchType.LAZY)
        @JoinColumn(name = "owner_id")
        private AuthorWithBooksAnnotated owner;

        LazyManyToOneEntity() {
        }
    }

    @Entity
    static class BothRelationsEntity {
        @Id
        private Long id;

        @ManyToOne
        @OneToMany(mappedBy = "x")
        private Object bad;

        BothRelationsEntity() {
        }
    }

    @Entity
    static class UuidKeyedTarget {
        @Id
        private UUID id;
        private String name;

        UuidKeyedTarget() {
        }
    }

    @Entity
    static class StringKeyedTarget {
        @Id
        private String code;

        StringKeyedTarget() {
        }
    }

    @Entity
    static class IntegerKeyedTarget {
        @Id
        private Integer id;

        IntegerKeyedTarget() {
        }
    }

    @Entity
    static class ShortKeyedTarget {
        @Id
        private Short id;

        ShortKeyedTarget() {
        }
    }

    @Entity
    static class RefToUuidKeyed {
        @Id
        private Long id;

        @ManyToOne(targetEntity = UuidKeyedTarget.class)
        @JoinColumn(name = "target_id")
        private UuidKeyedTarget target;

        RefToUuidKeyed() {
        }
    }

    @Entity
    static class RefToStringKeyed {
        @Id
        private Long id;

        @ManyToOne(targetEntity = StringKeyedTarget.class)
        @JoinColumn(name = "target_id")
        private StringKeyedTarget target;

        RefToStringKeyed() {
        }
    }

    @Entity
    static class RefToIntegerKeyed {
        @Id
        private Long id;

        @ManyToOne(targetEntity = IntegerKeyedTarget.class)
        @JoinColumn(name = "target_id")
        private IntegerKeyedTarget target;

        RefToIntegerKeyed() {
        }
    }

    @Entity
    static class RefToShortKeyed {
        @Id
        private Long id;

        @ManyToOne(targetEntity = ShortKeyedTarget.class)
        @JoinColumn(name = "target_id")
        private ShortKeyedTarget target;

        RefToShortKeyed() {
        }
    }

    @Entity
    static class OwningOneToOneToUuidKeyed {
        @Id
        private Long id;

        @OneToOne(targetEntity = UuidKeyedTarget.class)
        @JoinColumn(name = "target_id")
        private UuidKeyedTarget target;

        OwningOneToOneToUuidKeyed() {
        }
    }

    @Embeddable
    static class EmbeddedRelationAddress {
        private String city;

        EmbeddedRelationAddress() {
        }
    }

    @Entity
    static class EmbeddedAndManyToOneEntity {
        @Id
        private Long id;

        @Embedded
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private EmbeddedRelationAddress bad;

        EmbeddedAndManyToOneEntity() {
        }
    }

    @Entity
    static class IdAndManyToOneEntity {
        @Id
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private AuthorWithBooksAnnotated bad;

        IdAndManyToOneEntity() {
        }
    }

    @Entity
    static class VersionAndOneToManyEntity {
        @Id
        private Long id;

        @Version
        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "author")
        private Long bad;

        VersionAndOneToManyEntity() {
        }
    }

    @Entity
    static class SoftDeleteAndManyToOneEntity {
        @Id
        private Long id;

        @SoftDelete
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private AuthorWithBooksAnnotated bad;

        SoftDeleteAndManyToOneEntity() {
        }
    }

    @Entity
    static class CreatedAtAndManyToOneEntity {
        @Id
        private Long id;

        @CreatedAt
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private Instant bad;

        CreatedAtAndManyToOneEntity() {
        }
    }

    @Entity
    static class UpdatedAtAndManyToOneEntity {
        @Id
        private Long id;

        @UpdatedAt
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private Instant bad;

        UpdatedAtAndManyToOneEntity() {
        }
    }

    @Entity
    static class EnumeratedAndOneToManyEntity {
        @Id
        private Long id;

        @Enumerated
        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "author")
        private Status bad;

        EnumeratedAndOneToManyEntity() {
        }
    }

    enum Status {
        A, B
    }

    @Entity
    static class BlankMappedByEntity {
        @Id
        private Long id;

        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "")
        private java.util.List<BookWithAuthorAnnotated> children;

        BlankMappedByEntity() {
        }
    }

    @Entity
    static class NonNullJoinColumnEntity {
        @Id
        private Long id;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class, optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        private AuthorWithBooksAnnotated owner;

        NonNullJoinColumnEntity() {
        }
    }

}
