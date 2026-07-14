package io.nova.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.MapKey;
import jakarta.persistence.MapKeyClass;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.MapKeyTemporal;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.TemporalType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ElementCollection Map<K,V>} 메타데이터 추출과 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryMapCollectionTest {
    private final DefaultNamingStrategy naming = new DefaultNamingStrategy();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(naming);

    @Test
    void mapsBasicStringKeyToValueColumn() {
        ElementCollectionInfo info = info(BasicMap.class, "scores");

        assertTrue(info.map());
        assertFalse(info.embeddable());
        assertFalse(info.ordered());
        assertEquals("scores_key", info.mapKey().keyColumn());
        assertEquals(String.class, info.mapKey().keyType());
        assertEquals(String.class, info.mapKey().keyColumnType());
        assertNull(info.mapKey().keyEnumType());
        assertEquals("scores", info.valueColumn());
        assertEquals(Integer.class, info.valueType());
    }

    @Test
    void honorsMapKeyColumnAndValueColumnNames() {
        ElementCollectionInfo info = info(NamedMap.class, "labels");

        assertEquals("label_key", info.mapKey().keyColumn());
        assertEquals("label_val", info.valueColumn());
        assertEquals(Long.class, info.mapKey().keyType());
        assertEquals(String.class, info.valueType());
    }

    @Test
    void defaultsEnumKeyToOrdinalStorage() {
        ElementCollectionInfo info = info(OrdinalEnumKeyMap.class, "byColor");

        assertTrue(info.map());
        assertEquals(Color.class, info.mapKey().keyType());
        assertEquals(Integer.class, info.mapKey().keyColumnType());
        assertEquals(EnumType.ORDINAL, info.mapKey().keyEnumType());
        assertTrue(info.mapKey().enumKey());
    }

    @Test
    void honorsStringEnumKey() {
        ElementCollectionInfo info = info(StringEnumKeyMap.class, "byColor");

        assertEquals(String.class, info.mapKey().keyColumnType());
        assertEquals(EnumType.STRING, info.mapKey().keyEnumType());
    }

    @Test
    void mapsEmbeddableValue() {
        ElementCollectionInfo info = info(EmbeddableValueMap.class, "legs");

        assertTrue(info.map());
        assertTrue(info.embeddable());
        assertEquals("legs_key", info.mapKey().keyColumn());
        assertEquals(2, info.embeddableColumns().size());
        assertEquals("origin", info.embeddableColumns().get(0).columnName());
        assertEquals("dest", info.embeddableColumns().get(1).columnName());
    }

    @Test
    void rejectsMapKeyAnnotation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MapKeyAnnotated.class));
        assertTrue(error.getMessage().contains("@MapKey"));
    }

    @Test
    void rejectsEmbeddableKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EmbeddableKeyMap.class));
        assertTrue(error.getMessage().contains("@Embeddable key type"));
    }

    @Test
    void rejectsMapKeyEnumeratedOnNonEnumKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EnumeratedNonEnumKeyMap.class));
        assertTrue(error.getMessage().contains("@MapKeyEnumerated is only valid on an enum map key"));
    }

    @Test
    void rejectsUnsupportedKeyType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(UnsupportedKeyMap.class));
        assertTrue(error.getMessage().contains("Map key type"));
    }

    @Test
    void rejectsOrderColumnOnMap() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(OrderedMap.class));
        assertTrue(error.getMessage().contains("@OrderColumn is not valid on a Map"));
    }

    @Test
    void rejectsMapKeyColumnCollidingWithValueColumn() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CollidingKeyMap.class));
        assertTrue(error.getMessage().contains("collides with another collection table column"));
    }

    @Test
    void mapsTemporalDateKeyToLocalDateStorage() {
        ElementCollectionInfo info = info(TemporalDateKeyMap.class, "byDay");

        assertTrue(info.map());
        assertEquals(Date.class, info.mapKey().keyType());
        assertEquals(LocalDate.class, info.mapKey().keyColumnType());
        assertNull(info.mapKey().keyEnumType());
        assertFalse(info.mapKey().enumKey());
        // temporal converter가 붙어 도메인 Date를 java.time 저장 표현으로 인코딩한다.
        Object encoded = info.mapKey().encodeKey(new Date(0L));
        assertTrue(encoded instanceof LocalDate, "expected LocalDate storage but got " + encoded.getClass());
    }

    @Test
    void mapsTemporalCalendarTimestampKeyToLocalDateTimeStorage() {
        ElementCollectionInfo info = info(TemporalCalendarKeyMap.class, "byMoment");

        assertEquals(Calendar.class, info.mapKey().keyType());
        assertEquals(LocalDateTime.class, info.mapKey().keyColumnType());
        Object encoded = info.mapKey().encodeKey(new GregorianCalendar(2020, Calendar.JANUARY, 2, 3, 4, 5));
        assertTrue(encoded instanceof LocalDateTime, "expected LocalDateTime storage but got " + encoded.getClass());
    }

    @Test
    void resolvesRawMapKeyTypeViaMapKeyClass() {
        ElementCollectionInfo info = info(MapKeyClassRawMap.class, "scores");

        assertTrue(info.map());
        assertEquals(String.class, info.mapKey().keyType());
        assertEquals(String.class, info.mapKey().keyColumnType());
    }

    @Test
    void rejectsDateKeyWithoutMapKeyTemporal() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(TemporalDateKeyMissingAnnotation.class));
        assertTrue(error.getMessage().contains("@MapKeyTemporal(TemporalType.DATE|TIME|TIMESTAMP)"));
    }

    @Test
    void rejectsMapKeyTemporalOnNonTemporalKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MapKeyTemporalOnNonTemporalKey.class));
        assertTrue(error.getMessage().contains("@MapKeyTemporal is only valid on a java.util.Date or java.util.Calendar"));
    }

    @Test
    void rejectsMapKeyClassMismatchingParameterizedKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MapKeyClassMismatch.class));
        assertTrue(error.getMessage().contains("does not match the parameterized Map key type"));
    }

    @Test
    void rejectsRawMapWithoutKeyTypeSource() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(RawMapNoKeyType.class));
        assertTrue(error.getMessage().contains("cannot infer the Map key type from a raw map"));
    }

    private ElementCollectionInfo info(Class<?> type, String property) {
        return factory.getEntityMetadata(type).findProperty(property).orElseThrow().elementCollectionInfo();
    }

    // --- fixtures -----------------------------------------------------------

    enum Color { RED, GREEN, BLUE }

    @Embeddable
    static class Leg {
        String origin;
        String dest;
    }

    @Entity
    @Table(name = "basic_map")
    static class BasicMap {
        @Id
        Long id;

        @ElementCollection
        Map<String, Integer> scores;
    }

    @Entity
    @Table(name = "named_map")
    static class NamedMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyColumn(name = "label_key")
        @Column(name = "label_val")
        Map<Long, String> labels;
    }

    @Entity
    @Table(name = "ordinal_enum_key_map")
    static class OrdinalEnumKeyMap {
        @Id
        Long id;

        @ElementCollection
        Map<Color, Integer> byColor;
    }

    @Entity
    @Table(name = "string_enum_key_map")
    static class StringEnumKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyEnumerated(EnumType.STRING)
        Map<Color, Integer> byColor;
    }

    @Entity
    @Table(name = "embeddable_value_map")
    static class EmbeddableValueMap {
        @Id
        Long id;

        @ElementCollection
        Map<String, Leg> legs;
    }

    @Entity
    @Table(name = "map_key_annotated")
    static class MapKeyAnnotated {
        @Id
        Long id;

        @ElementCollection
        @MapKey(name = "origin")
        Map<String, Leg> legs;
    }

    @Entity
    @Table(name = "embeddable_key_map")
    static class EmbeddableKeyMap {
        @Id
        Long id;

        @ElementCollection
        Map<Leg, String> byLeg;
    }

    @Entity
    @Table(name = "enumerated_non_enum_key_map")
    static class EnumeratedNonEnumKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyEnumerated(EnumType.STRING)
        Map<String, Integer> scores;
    }

    @Entity
    @Table(name = "unsupported_key_map")
    static class UnsupportedKeyMap {
        @Id
        Long id;

        @ElementCollection
        Map<Double, Integer> byWeight;
    }

    @Entity
    @Table(name = "ordered_map")
    static class OrderedMap {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn
        Map<String, Integer> scores;
    }

    @Entity
    @Table(name = "colliding_key_map")
    static class CollidingKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyColumn(name = "scores")
        Map<String, Integer> scores;
    }

    @Entity
    @Table(name = "temporal_date_key_map")
    static class TemporalDateKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyColumn(name = "day")
        @MapKeyTemporal(TemporalType.DATE)
        Map<Date, Integer> byDay;
    }

    @Entity
    @Table(name = "temporal_calendar_key_map")
    static class TemporalCalendarKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyColumn(name = "moment")
        @MapKeyTemporal(TemporalType.TIMESTAMP)
        Map<Calendar, Integer> byMoment;
    }

    @Entity
    @Table(name = "map_key_class_raw_map")
    @SuppressWarnings({"rawtypes", "unchecked"})
    static class MapKeyClassRawMap {
        @Id
        Long id;

        // raw Map: value 타입은 targetClass로, key 타입은 @MapKeyClass로 결정한다.
        @ElementCollection(targetClass = Integer.class)
        @MapKeyColumn(name = "score_key")
        @Column(name = "score_val")
        @MapKeyClass(String.class)
        Map scores;
    }

    @Entity
    @Table(name = "temporal_date_key_missing")
    static class TemporalDateKeyMissingAnnotation {
        @Id
        Long id;

        @ElementCollection
        Map<Date, Integer> byDay;
    }

    @Entity
    @Table(name = "map_key_temporal_non_temporal")
    static class MapKeyTemporalOnNonTemporalKey {
        @Id
        Long id;

        @ElementCollection
        @MapKeyTemporal(TemporalType.DATE)
        Map<String, Integer> scores;
    }

    @Entity
    @Table(name = "map_key_class_mismatch")
    static class MapKeyClassMismatch {
        @Id
        Long id;

        @ElementCollection
        @MapKeyClass(Long.class)
        Map<String, Integer> scores;
    }

    @Entity
    @Table(name = "raw_map_no_key_type")
    @SuppressWarnings({"rawtypes", "unchecked"})
    static class RawMapNoKeyType {
        @Id
        Long id;

        // value 타입은 targetClass로 해석되지만 raw Map + @MapKeyClass 부재라 key 타입은 결정할 수 없어야 한다.
        @ElementCollection(targetClass = Integer.class)
        @Column(name = "score_val")
        Map scores;
    }
}
