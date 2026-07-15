package io.nova.metadata;

import jakarta.persistence.AttributeOverride;
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
    void mapsEmbeddableKeyToMultipleColumns() {
        ElementCollectionInfo info = info(EmbeddableKeyMap.class, "byLeg");

        assertTrue(info.map());
        assertTrue(info.mapKey().embeddableKey());
        assertEquals(Leg.class, info.mapKey().keyType());
        assertEquals(2, info.mapKey().embeddableKeyColumns().size());
        assertEquals("origin", info.mapKey().embeddableKeyColumns().get(0).columnName());
        assertEquals("dest", info.mapKey().embeddableKeyColumns().get(1).columnName());
        // 단일 컬럼 key 필드는 embeddable key에서 의미가 없다.
        assertEquals("", info.mapKey().keyColumn());
        assertNull(info.mapKey().keyEnumType());
        // value는 기본 타입(String) 단일 값 컬럼.
        assertFalse(info.embeddable());
        assertEquals("by_leg", info.valueColumn());
        assertEquals(String.class, info.valueType());
    }

    @Test
    void honorsAttributeOverrideOnEmbeddableKeyColumns() {
        ElementCollectionInfo info = info(OverriddenEmbeddableKeyMap.class, "byLeg");

        assertTrue(info.mapKey().embeddableKey());
        assertEquals("from_col", info.mapKey().embeddableKeyColumns().get(0).columnName());
        assertEquals("to_col", info.mapKey().embeddableKeyColumns().get(1).columnName());
    }

    @Test
    void mapsEmbeddableKeyWithEmbeddableValue() {
        ElementCollectionInfo info = info(EmbeddableKeyAndValueMap.class, "routes");

        assertTrue(info.map());
        assertTrue(info.mapKey().embeddableKey());
        assertEquals(2, info.mapKey().embeddableKeyColumns().size());
        assertTrue(info.embeddable());
        assertEquals(2, info.embeddableColumns().size());
    }

    @Test
    void overridesEmbeddableKeyColumnsWithEmbeddableValue() {
        ElementCollectionInfo info = info(OverriddenEmbeddableKeyAndValueMap.class, "routes");

        // key.* override는 key 컬럼에만 적용되고 value 확장 루프에는 걸리지 않아야 한다.
        assertTrue(info.mapKey().embeddableKey());
        assertEquals("from_col", info.mapKey().embeddableKeyColumns().get(0).columnName());
        assertEquals("to_col", info.mapKey().embeddableKeyColumns().get(1).columnName());
        // value 컬럼은 embeddable value의 기본 규약대로(override 미적용) 유지되고 key 컬럼과 충돌하지 않는다.
        assertTrue(info.embeddable());
        assertEquals(2, info.embeddableColumns().size());
        assertEquals("amount", info.embeddableColumns().get(0).columnName());
        assertEquals("currency", info.embeddableColumns().get(1).columnName());
    }

    @Test
    void mapsSingleIdEntityKeyToForeignKeyColumn() {
        ElementCollectionInfo info = info(EntityKeyMap.class, "byEntity");

        assertTrue(info.map());
        assertTrue(info.mapKey().entityKey());
        assertFalse(info.mapKey().embeddableKey());
        assertEquals(KeyEntity.class, info.mapKey().keyType());
        // 기본 이름: naming strategy 경유 <property>_key (@MapKeyJoinColumn 미지정).
        assertEquals("by_entity_key", info.mapKey().keyColumn());
        // KeyEntity.@Id는 컨버터 없는 순수 Long이므로 컬럼 타입도 Long 그대로다.
        assertEquals(Long.class, info.mapKey().keyColumnType());
        assertNull(info.mapKey().keyEnumType());
        assertFalse(info.mapKey().enumKey());
        // toCollectionTableDefinition도 단일 key 컬럼으로 반영돼야 한다(embeddable 다중 컬럼이 아님).
        var definition = info.toCollectionTableDefinition(Long.class);
        assertEquals("by_entity_key", definition.mapKey().columnName());
        assertEquals(Long.class, definition.mapKey().columnType());
        assertTrue(definition.mapKeyColumns().isEmpty());
    }

    @Test
    void honorsMapKeyJoinColumnNameOnEntityKey() {
        ElementCollectionInfo info = info(NamedEntityKeyMap.class, "byEntity");

        assertTrue(info.mapKey().entityKey());
        assertEquals("entity_ref", info.mapKey().keyColumn());
    }

    @Test
    void mapsUuidIdEntityKeyToConverterColumn() {
        ElementCollectionInfo info = info(UuidEntityKeyMap.class, "byUuidEntity");

        assertTrue(info.mapKey().entityKey());
        assertEquals(UuidKeyEntity.class, info.mapKey().keyType());
        // UUID @Id 저장 표현은 varchar(String) via UuidStringConverter — target @Id 저장 규칙과 대칭.
        assertEquals(String.class, info.mapKey().keyColumnType());
        java.util.UUID id = java.util.UUID.randomUUID();
        Object encoded = info.mapKey().encodeKey(id);
        assertEquals(id.toString(), encoded, "UUID entity key는 varchar 저장 표현으로 인코딩돼야 한다");
        assertEquals(id, info.mapKey().decodeKey(id.toString()),
                "varchar 저장 표현이 도메인 UUID로 대칭 디코딩돼야 한다(read-source-type 함정 회피)");
    }

    @Test
    void rejectsCompositeIdEntityKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CompositeIdEntityKeyMap.class));
        assertTrue(error.getMessage().contains("composite @Id"));
    }

    @Test
    void rejectsMapKeyColumnOnEntityKey() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MapKeyColumnOnEntityKeyMap.class));
        assertTrue(error.getMessage().contains("@MapKeyColumn is not valid on an entity map key"));
    }

    @Test
    void rejectsEmbeddableKeyColumnCollidingWithValueColumn() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CollidingEmbeddableKeyMap.class));
        assertTrue(error.getMessage().contains("collides with another collection table column"));
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

    @Embeddable
    static class Fare {
        int amount;
        String currency;
    }

    @Entity
    @Table(name = "key_entity")
    static class KeyEntity {
        @Id
        Long id;
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
    @Table(name = "overridden_embeddable_key_map")
    static class OverriddenEmbeddableKeyMap {
        @Id
        Long id;

        @ElementCollection
        @AttributeOverride(name = "key.origin", column = @Column(name = "from_col"))
        @AttributeOverride(name = "key.dest", column = @Column(name = "to_col"))
        Map<Leg, String> byLeg;
    }

    @Entity
    @Table(name = "embeddable_key_and_value_map")
    static class EmbeddableKeyAndValueMap {
        @Id
        Long id;

        @ElementCollection
        Map<Leg, Fare> routes;
    }

    @Entity
    @Table(name = "overridden_embeddable_key_and_value_map")
    static class OverriddenEmbeddableKeyAndValueMap {
        @Id
        Long id;

        // key.* override는 @Embeddable key(Leg) 컬럼용, value(Fare)는 기본 규약을 그대로 쓴다.
        @ElementCollection
        @AttributeOverride(name = "key.origin", column = @Column(name = "from_col"))
        @AttributeOverride(name = "key.dest", column = @Column(name = "to_col"))
        Map<Leg, Fare> routes;
    }

    @Entity
    @Table(name = "entity_key_map")
    static class EntityKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyClass(KeyEntity.class)
        Map<KeyEntity, String> byEntity;
    }

    @Entity
    @Table(name = "named_entity_key_map")
    static class NamedEntityKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyClass(KeyEntity.class)
        @jakarta.persistence.MapKeyJoinColumn(name = "entity_ref")
        Map<KeyEntity, String> byEntity;
    }

    @Entity
    @Table(name = "uuid_key_entity")
    static class UuidKeyEntity {
        @Id
        java.util.UUID id;
    }

    @Entity
    @Table(name = "uuid_entity_key_map")
    static class UuidEntityKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyClass(UuidKeyEntity.class)
        Map<UuidKeyEntity, String> byUuidEntity;
    }

    @Embeddable
    static class CompositeKeyEntityId {
        Long region;
        Long slot;
    }

    @Entity
    @Table(name = "composite_id_key_entity")
    static class CompositeIdKeyEntity {
        @jakarta.persistence.EmbeddedId
        CompositeKeyEntityId id;
    }

    @Entity
    @Table(name = "composite_id_entity_key_map")
    static class CompositeIdEntityKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyClass(CompositeIdKeyEntity.class)
        Map<CompositeIdKeyEntity, String> byEntity;
    }

    @Entity
    @Table(name = "map_key_column_on_entity_key_map")
    static class MapKeyColumnOnEntityKeyMap {
        @Id
        Long id;

        @ElementCollection
        @MapKeyClass(KeyEntity.class)
        @MapKeyColumn(name = "entity_ref")
        Map<KeyEntity, String> byEntity;
    }

    @Entity
    @Table(name = "colliding_embeddable_key_map")
    static class CollidingEmbeddableKeyMap {
        @Id
        Long id;

        // 값 컬럼 이름이 embeddable key 컬럼(origin)과 충돌한다.
        @ElementCollection
        @Column(name = "origin")
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
