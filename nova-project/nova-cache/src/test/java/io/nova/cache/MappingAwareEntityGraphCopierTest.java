package io.nova.cache;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MappingAwareEntityGraphCopierTest {

    private final MappingAwareEntityGraphCopier copier = new MappingAwareEntityGraphCopier(
            new EntityMetadataFactory(new DefaultNamingStrategy()));

    @Test
    void copiesPropertyAssociationsAndPreservesCyclesAndSharedIdentity() {
        GraphOwner source = new GraphOwner(1L, "owner");
        PropertyChild child = new PropertyChild(2L, "child");
        source.primaryChild = child;
        source.children.add(child);
        source.children.add(child);
        child.setOwner(source);

        GraphOwner copy = copier.copy(source);

        assertNotSame(source, copy);
        assertEquals("owner", copy.name);
        assertEquals(2, copy.children.size());
        assertSame(copy.primaryChild, copy.children.get(0),
                "a PROPERTY-access to-one association and collection reference must share one copied child");
        assertNotSame(child, copy.children.get(0));
        assertSame(copy.children.get(0), copy.children.get(1),
                "repeated references must remain shared in one copied graph");
        assertSame(copy, copy.children.get(0).getOwner(),
                "a PROPERTY-access association cycle must point at the copied owner");
    }

    @Test
    void reconstructsConvertedConstructorOnlyRecordsAndRecordElementCollections() {
        ConvertedRecordOwner source = new ConvertedRecordOwner(1L, new CacheCode("primary"));
        source.codes.add(new CacheCode("first"));

        ConvertedRecordOwner copy = copier.copy(source);

        assertNotSame(source, copy);
        assertEquals(new CacheCode("primary"), copy.code);
        assertNotSame(source.code, copy.code,
                "a converted record must be reconstructed through its canonical constructor");
        assertEquals(List.of(new CacheCode("first")), copy.codes);
        assertNotSame(source.codes, copy.codes);
        assertNotSame(source.codes.get(0), copy.codes.get(0),
                "record element-collection values must not be shared with the snapshot source");
    }

    @Entity
    @Table(name = "cache_copy_owner")
    @Cacheable
    static class GraphOwner {
        @Id
        private Long id;
        private String name;
        @ManyToOne
        private PropertyChild primaryChild;
        @OneToMany(mappedBy = "owner")
        private List<PropertyChild> children = new ArrayList<>();

        GraphOwner() {
        }

        GraphOwner(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    @Table(name = "cache_copy_child")
    @Access(AccessType.PROPERTY)
    static class PropertyChild {
        private Long id;
        private String name;
        private GraphOwner owner;

        PropertyChild() {
        }

        PropertyChild(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Id
        Long getId() {
            return id;
        }

        void setId(Long id) {
            this.id = id;
        }

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }

        @ManyToOne
        GraphOwner getOwner() {
            return owner;
        }

        void setOwner(GraphOwner owner) {
            this.owner = owner;
        }
    }

    @Entity
    @Table(name = "cache_copy_record_owner")
    @Cacheable
    static class ConvertedRecordOwner {
        @Id
        private Long id;
        @Convert(converter = CacheCodeConverter.class)
        private CacheCode code;
        @ElementCollection
        @Convert(converter = CacheCodeConverter.class)
        private List<CacheCode> codes = new ArrayList<>();

        ConvertedRecordOwner() {
        }

        ConvertedRecordOwner(Long id, CacheCode code) {
            this.id = id;
            this.code = code;
        }
    }

    record CacheCode(String value) {
    }

    @Converter
    public static class CacheCodeConverter implements jakarta.persistence.AttributeConverter<CacheCode, String> {
        @Override
        public String convertToDatabaseColumn(CacheCode attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public CacheCode convertToEntityAttribute(String databaseValue) {
            return databaseValue == null ? null : new CacheCode(databaseValue);
        }
    }
}
