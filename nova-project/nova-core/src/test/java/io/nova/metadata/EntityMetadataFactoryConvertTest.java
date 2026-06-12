package io.nova.metadata;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JPA 표준 {@code @Convert} + {@link jakarta.persistence.AttributeConverter} 지원을 보호한다.
 */
class EntityMetadataFactoryConvertTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void appliesConverterAndExposesStorageColumnType() {
        EntityMetadata<Swatch> metadata = factory.getEntityMetadata(Swatch.class);
        PersistentProperty color = metadata.findProperty("color").orElseThrow();

        // 저장 표현 타입(Y=Integer)이 컬럼 타입으로 노출된다 — 도메인 타입(X=Rgb)이 아니라.
        assertEquals(Integer.class, color.columnType());
        assertEquals(Integer.class, color.converterColumnType());
        // 변환 방향: 엔티티→컬럼(write), 컬럼→엔티티(read).
        assertEquals(5, color.toColumnValue(new Rgb(5)));
        assertEquals(9, ((Rgb) color.toPropertyValue(9)).value());
    }

    @Test
    void disableConversionLeavesFieldTypeUnconverted() {
        // disableConversion=true면 변환기를 적용하지 않는다 → converterColumnType 없음, columnType==도메인 타입.
        EntityMetadata<DisabledConvert> metadata = factory.getEntityMetadata(DisabledConvert.class);
        PersistentProperty color = metadata.findProperty("color").orElseThrow();
        assertNull(color.converterColumnType());
        assertEquals(Rgb.class, color.columnType());
    }

    @Test
    void rejectsCombiningConvertWithEnumerated() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(ConvertPlusEnumerated.class));
    }

    @Test
    void rejectsConverterWithoutNoArgConstructor() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(BadConverterEntity.class));
    }

    @Test
    void convertOnUnconvertedFieldHasNullStorageType() {
        // 변환기가 없는 일반 컬럼은 converterColumnType이 null이고 columnType==javaType.
        EntityMetadata<Swatch> metadata = factory.getEntityMetadata(Swatch.class);
        PersistentProperty id = metadata.idProperty();
        assertNull(id.converterColumnType());
        assertEquals(Long.class, id.columnType());
    }

    // --- fixtures -----------------------------------------------------------

    record Rgb(int value) {
    }

    public static class RgbConverter implements jakarta.persistence.AttributeConverter<Rgb, Integer> {
        @Override
        public Integer convertToDatabaseColumn(Rgb attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public Rgb convertToEntityAttribute(Integer dbData) {
            return dbData == null ? null : new Rgb(dbData);
        }
    }

    @Entity
    @Table(name = "swatch")
    static class Swatch {
        @Id
        private Long id;

        @Convert(converter = RgbConverter.class)
        private Rgb color;

        Swatch() {
        }
    }

    @Entity
    @Table(name = "disabled")
    static class DisabledConvert {
        @Id
        private Long id;

        @Convert(converter = RgbConverter.class, disableConversion = true)
        private Rgb color;

        DisabledConvert() {
        }
    }

    enum Size {SMALL, LARGE}

    @Entity
    @Table(name = "conflict")
    static class ConvertPlusEnumerated {
        @Id
        private Long id;

        @Convert(converter = SizeConverter.class)
        @Enumerated(EnumType.STRING)
        private Size size;

        ConvertPlusEnumerated() {
        }
    }

    public static class SizeConverter implements jakarta.persistence.AttributeConverter<Size, String> {
        @Override
        public String convertToDatabaseColumn(Size attribute) {
            return attribute == null ? null : attribute.name();
        }

        @Override
        public Size convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Size.valueOf(dbData);
        }
    }

    @Entity
    @Table(name = "bad")
    static class BadConverterEntity {
        @Id
        private Long id;

        @Convert(converter = NoNoArgConverter.class)
        private Rgb color;

        BadConverterEntity() {
        }
    }

    public static class NoNoArgConverter implements jakarta.persistence.AttributeConverter<Rgb, Integer> {
        private final int seed;

        public NoNoArgConverter(int seed) {
            this.seed = seed;
        }

        @Override
        public Integer convertToDatabaseColumn(Rgb attribute) {
            return attribute == null ? null : attribute.value() + seed;
        }

        @Override
        public Rgb convertToEntityAttribute(Integer dbData) {
            return dbData == null ? null : new Rgb(dbData);
        }
    }
}
