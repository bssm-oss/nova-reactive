package io.nova.cache;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.annotation.Json;
import io.nova.core.ReactiveEntityOperations;
import io.nova.json.JsonCodec;
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
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        source.byCode.put(new CacheCode("key"), new CacheCode("value"));
        source.timestamp = new Date(1_725_000_123_456L);

        ConvertedRecordOwner copy = copier.copy(source);

        assertNotSame(source, copy);
        assertEquals(new CacheCode("primary"), copy.code);
        assertNotSame(source.code, copy.code,
                "a converted record must be reconstructed through its canonical constructor");
        assertEquals(List.of(new CacheCode("first")), copy.codes);
        assertNotSame(source.codes, copy.codes);
        assertNotSame(source.codes.get(0), copy.codes.get(0),
                "record element-collection values must not be shared with the snapshot source");
        assertEquals(Map.of(new CacheCode("key"), new CacheCode("value")), copy.byCode);
        Map.Entry<CacheCode, CacheCode> sourceEntry = source.byCode.entrySet().iterator().next();
        Map.Entry<CacheCode, CacheCode> copyEntry = copy.byCode.entrySet().iterator().next();
        assertNotSame(sourceEntry.getKey(), copyEntry.getKey(), "converted map keys must be reconstructed");
        assertNotSame(sourceEntry.getValue(), copyEntry.getValue(), "converted map values must be reconstructed");
        assertEquals(source.timestamp, copy.timestamp, "@Temporal TIMESTAMP must preserve the exact Date instant");
        assertNotSame(source.timestamp, copy.timestamp, "@Temporal conversion must not share mutable Date instances");
    }

    @Test
    void usesPropertySettersWithoutReflectingUnmanagedFinalFields() {
        SetterTrackedOwner source = new SetterTrackedOwner(4L, "mapped", "source-only");

        SetterTrackedOwner copy = copier.copy(source);

        assertEquals("mapped", copy.getName());
        assertEquals(1, copy.nameSetterCalls, "PROPERTY mapping must reconstruct through the JavaBean setter");
        assertEquals("new-instance", copy.unmanagedMarker,
                "unmanaged final state must not be reflectively copied into cache snapshots");
    }

    @Test
    void novaCacheFactoryUsesTheExactCustomJsonMappingForSnapshots() {
        EntityMetadataFactory jsonFactory = new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesCodec());
        CachingReactiveEntityOperationsTest.CountingOps delegate =
                new CachingReactiveEntityOperationsTest.CountingOps(jsonFactory);
        JsonCachedOwner source = new JsonCachedOwner(5L, new Preferences("dark", 14));
        delegate.seed(source);

        ReactiveEntityOperations cached = NovaCache.caching(delegate, new SimpleReactiveCacheProvider(), jsonFactory);
        JsonCachedOwner first = cached.findById(JsonCachedOwner.class, 5L).block();
        JsonCachedOwner hit = cached.findById(JsonCachedOwner.class, 5L).block();

        assertEquals(source.preferences, first.preferences);
        assertEquals(source.preferences, hit.preferences);
        assertNotSame(first.preferences, hit.preferences);
        assertEquals(1, delegate.findByIdCalls.get(), "the second lookup must use the snapshot built with the supplied codec");
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
        @ElementCollection
        private Map<CacheCode, CacheCode> byCode = new LinkedHashMap<>();
        @Temporal(TemporalType.TIMESTAMP)
        private Date timestamp;

        ConvertedRecordOwner() {
        }

        ConvertedRecordOwner(Long id, CacheCode code) {
            this.id = id;
            this.code = code;
        }
    }

    record CacheCode(String value) {
    }

    @Converter(autoApply = true)
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

    @Entity
    @Table(name = "cache_copy_setter_owner")
    @Cacheable
    @Access(AccessType.PROPERTY)
    static class SetterTrackedOwner {
        private Long id;
        private String name;
        private int nameSetterCalls;
        @Transient
        private final String unmanagedMarker;

        SetterTrackedOwner() {
            this.unmanagedMarker = "new-instance";
        }

        SetterTrackedOwner(Long id, String name, String unmanagedMarker) {
            this.id = id;
            this.name = name;
            this.unmanagedMarker = unmanagedMarker;
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
            nameSetterCalls++;
            this.name = name;
        }
    }

    @Entity
    @Table(name = "cache_copy_json_owner")
    @Cacheable
    static class JsonCachedOwner {
        @Id
        private Long id;
        @Json
        private Preferences preferences;

        JsonCachedOwner() {
        }

        JsonCachedOwner(Long id, Preferences preferences) {
            this.id = id;
            this.preferences = preferences;
        }
    }

    record Preferences(String theme, int fontSize) {
    }

    static final class PreferencesCodec implements JsonCodec {
        @Override
        public String encode(Object value) {
            Preferences preferences = (Preferences) value;
            return preferences.theme() + ":" + preferences.fontSize();
        }

        @Override
        public <T> T decode(String json, Class<T> targetType) {
            String[] parts = json.split(":");
            return targetType.cast(new Preferences(parts[0], Integer.parseInt(parts[1])));
        }
    }
}
