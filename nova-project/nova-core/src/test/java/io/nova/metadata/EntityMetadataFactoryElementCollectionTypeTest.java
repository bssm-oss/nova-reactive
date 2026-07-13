package io.nova.metadata;

import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ElementCollection} 기본 타입 원소의 저장 표현 타입({@link ElementCollectionInfo#valueColumnType()})과
 * encode/decode 경로(enum/UUID/@Convert/@Temporal)를 스칼라 프로퍼티와 대칭으로 보호한다.
 */
class EntityMetadataFactoryElementCollectionTypeTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    private ElementCollectionInfo info(Class<?> entity, String property) {
        return factory.getEntityMetadata(entity).findProperty(property).orElseThrow().elementCollectionInfo();
    }

    @Test
    void enumStringElementStoresAsVarchar() {
        ElementCollectionInfo info = info(Holder.class, "stringColors");
        assertEquals(Color.class, info.valueType());
        assertEquals(String.class, info.valueColumnType());
        assertEquals("RED", info.encodeElementValue(Color.RED));
        assertEquals(Color.GREEN, info.decodeElementValue("GREEN"));
    }

    @Test
    void enumOrdinalElementStoresAsInteger() {
        ElementCollectionInfo info = info(Holder.class, "ordinalColors");
        assertEquals(Color.class, info.valueType());
        assertEquals(Integer.class, info.valueColumnType());
        assertEquals(0, info.encodeElementValue(Color.RED));
        assertEquals(2, info.encodeElementValue(Color.BLUE));
        assertEquals(Color.BLUE, info.decodeElementValue(2));
    }

    @Test
    void enumDefaultElementIsOrdinal() {
        // @Enumerated 미지정(그러나 붙어있는) 기본은 JPA 기본 ORDINAL.
        ElementCollectionInfo info = info(Holder.class, "defaultColors");
        assertEquals(Integer.class, info.valueColumnType());
        assertEquals(1, info.encodeElementValue(Color.GREEN));
    }

    @Test
    void uuidElementStoresAsStringAndRoundTrips() {
        ElementCollectionInfo info = info(Holder.class, "refs");
        assertEquals(UUID.class, info.valueType());
        assertEquals(String.class, info.valueColumnType());
        UUID u = UUID.randomUUID();
        Object encoded = info.encodeElementValue(u);
        assertEquals(u.toString(), encoded);
        assertEquals(u, info.decodeElementValue(encoded));
    }

    @Test
    void plainBasicElementHasNoConverter() {
        ElementCollectionInfo decimals = info(Holder.class, "amounts");
        assertEquals(BigDecimal.class, decimals.valueColumnType());
        assertNull(decimals.valueConverter());
        assertEquals(new BigDecimal("1.50"), decimals.encodeElementValue(new BigDecimal("1.50")));

        ElementCollectionInfo dates = info(Holder.class, "dates");
        assertEquals(LocalDate.class, dates.valueColumnType());
        assertNull(dates.valueConverter());
    }

    @Test
    void convertElementUsesConverterStorageType() {
        ElementCollectionInfo info = info(Holder.class, "convertedColors");
        assertEquals(String.class, info.valueColumnType());
        assertEquals("R", info.encodeElementValue(Color.RED));
        assertEquals(Color.BLUE, info.decodeElementValue("B"));
    }

    @Test
    void temporalElementUsesLocalStorageType() {
        ElementCollectionInfo info = info(Holder.class, "legacyDates");
        assertEquals(LocalDate.class, info.valueColumnType());
        LocalDate ld = LocalDate.of(2020, 1, 2);
        Object encoded = info.encodeElementValue(new java.util.Date(120, 0, 2));
        assertEquals(ld, encoded);
    }

    @Test
    void rejectsEnumeratedOnNonEnumElement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EnumeratedNonEnum.class));
        assertTrue(error.getMessage().contains("is not an enum"));
    }

    // --- fixtures -----------------------------------------------------------

    enum Color { RED, GREEN, BLUE }

    static class ColorCodeConverter implements jakarta.persistence.AttributeConverter<Color, String> {
        @Override
        public String convertToDatabaseColumn(Color attribute) {
            return attribute == null ? null : attribute.name().substring(0, 1);
        }

        @Override
        public Color convertToEntityAttribute(String dbData) {
            if (dbData == null) {
                return null;
            }
            return switch (dbData) {
                case "R" -> Color.RED;
                case "G" -> Color.GREEN;
                case "B" -> Color.BLUE;
                default -> throw new IllegalArgumentException("unknown color code " + dbData);
            };
        }
    }

    @Entity
    @Table(name = "ec_type_holder")
    static class Holder {
        @Id
        Long id;

        @ElementCollection
        @Enumerated(EnumType.STRING)
        Set<Color> stringColors;

        @ElementCollection
        @Enumerated(EnumType.ORDINAL)
        Set<Color> ordinalColors;

        @ElementCollection
        @Enumerated
        Set<Color> defaultColors;

        @ElementCollection
        Set<UUID> refs;

        @ElementCollection
        List<BigDecimal> amounts;

        @ElementCollection
        List<LocalDate> dates;

        @ElementCollection
        @Convert(converter = ColorCodeConverter.class)
        List<Color> convertedColors;

        @ElementCollection
        @Temporal(TemporalType.DATE)
        List<java.util.Date> legacyDates;
    }

    @Entity
    @Table(name = "enumerated_non_enum")
    static class EnumeratedNonEnum {
        @Id
        Long id;

        @ElementCollection
        @Enumerated(EnumType.STRING)
        Set<String> notAnEnum;
    }
}
