package io.nova.query.jpql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpqlEntityResolverTest {

    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void resolvesOnlyTheExactCaseEffectiveEntityName() {
        JpqlEntityResolver resolver = new JpqlEntityResolver(metadataFactory, List.of(NamedEntity.class));

        assertEquals(NamedEntity.class, resolver.resolve("EffectiveName").entityType());
        assertThrows(JpqlException.class, () -> resolver.resolve("effectivename"));
        assertThrows(JpqlException.class, () -> resolver.resolve("NamedEntity"));
    }

    @Test
    void resolvesDefaultEntitySimpleName() {
        JpqlEntityResolver resolver = new JpqlEntityResolver(metadataFactory, List.of(DefaultEntity.class));

        assertEquals(DefaultEntity.class, resolver.resolve("DefaultEntity").entityType());
    }

    @Test
    void rejectsDuplicateEffectiveEntityNamesWithBothTypes() {
        JpqlException exception = assertThrows(JpqlException.class,
                () -> new JpqlEntityResolver(metadataFactory, List.of(FirstDuplicate.class, SecondDuplicate.class)));

        assertTrue(exception.getMessage().contains(FirstDuplicate.class.getName()));
        assertTrue(exception.getMessage().contains(SecondDuplicate.class.getName()));
    }

    @Entity(name = "EffectiveName")
    static class NamedEntity {
        @Id
        Long id;
    }

    @Entity
    static class DefaultEntity {
        @Id
        Long id;
    }

    @Entity(name = "DuplicateName")
    static class FirstDuplicate {
        @Id
        Long id;
    }

    @Entity(name = "DuplicateName")
    static class SecondDuplicate {
        @Id
        Long id;
    }
}
