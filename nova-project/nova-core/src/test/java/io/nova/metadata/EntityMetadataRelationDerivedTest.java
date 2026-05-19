package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityMetadata}의 derived stream getter가 properties 안에 추가된 관계 marker를 별도 캐시 없이
 * 그대로 노출하는지 검증한다 (memory feedback_metadata_stream_pattern.md).
 */
class EntityMetadataRelationDerivedTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void manyToOnePropertiesReturnsOnlyManyToOneMarkers() {
        EntityMetadata<BookWithAuthorAnnotated> metadata = factory.getEntityMetadata(BookWithAuthorAnnotated.class);
        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        assertSame(AuthorWithBooksAnnotated.class, relations.get(0).manyToOneTargetType());
    }

    @Test
    void oneToManyPropertiesReturnsOnlyOneToManyMarkers() {
        EntityMetadata<AuthorWithBooksAnnotated> metadata = factory.getEntityMetadata(AuthorWithBooksAnnotated.class);
        List<PersistentProperty> inverse = metadata.oneToManyProperties();
        assertEquals(1, inverse.size());
        assertEquals("author", inverse.get(0).oneToManyMappedBy());
    }

    @Test
    void hasRelationPropertiesIsTrueForBothSides() {
        assertTrue(factory.getEntityMetadata(AuthorWithBooksAnnotated.class).hasRelationProperties());
        assertTrue(factory.getEntityMetadata(BookWithAuthorAnnotated.class).hasRelationProperties());
    }

    @Test
    void hasRelationPropertiesIsFalseForPlainEntity() {
        assertFalse(factory.getEntityMetadata(SampleAccount.class).hasRelationProperties());
    }

    @Test
    void streamGettersAreNotCachedSoSubsequentCallsAreIndependentLists() {
        EntityMetadata<AuthorWithBooksAnnotated> metadata = factory.getEntityMetadata(AuthorWithBooksAnnotated.class);
        // 캐시되지 않아 호출마다 새 리스트가 만들어진다 — identity가 같지 않아야 한다.
        List<PersistentProperty> first = metadata.oneToManyProperties();
        List<PersistentProperty> second = metadata.oneToManyProperties();
        // 내용은 동일하지만 인스턴스는 다르다 (stream().toList()는 매번 새 인스턴스를 만든다).
        assertEquals(first, second);
        assertFalse(first == second, "stream toList()는 매 호출마다 새 인스턴스를 만들어야 한다");
    }
}
