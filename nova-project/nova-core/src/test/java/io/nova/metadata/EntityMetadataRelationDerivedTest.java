package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityMetadata}의 derived getter가 properties 안에 추가된 관계 marker를 (생성 시 1회 계산·캐시해)
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
    void derivedGettersAreCachedAndImmutable() {
        EntityMetadata<AuthorWithBooksAnnotated> metadata = factory.getEntityMetadata(AuthorWithBooksAnnotated.class);
        // properties는 생성 후 불변이므로 derived 뷰는 생성 시 1회 계산해 캐시한다(핫패스 오버헤드 제거).
        // 매 호출 동일 immutable 인스턴스를 반환하며, 외부 변경은 불가능하다.
        List<PersistentProperty> first = metadata.oneToManyProperties();
        List<PersistentProperty> second = metadata.oneToManyProperties();
        assertEquals(first, second);
        assertSame(first, second, "캐시된 derived 뷰는 매 호출 동일 인스턴스를 반환한다");
        assertThrows(UnsupportedOperationException.class, () -> first.add(first.get(0)),
                "캐시 공유가 안전하도록 immutable이어야 한다");
    }
}
