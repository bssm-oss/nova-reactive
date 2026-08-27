package io.nova.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumeratedValue;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityMetadataFactoryJpa32ValueMappingTest {

    @Test
    void appliesManagedAutoConverterToBasicEmbeddedAndBasicCollections() {
        EntityMetadataFactory factory = factory(CodeConverter.class);
        EntityMetadata<ValueEntity> metadata = factory.getEntityMetadata(ValueEntity.class);

        PersistentProperty basic = metadata.findProperty("code").orElseThrow();
        PersistentProperty embedded = metadata.findProperty("address.code").orElseThrow();
        ElementCollectionInfo values = metadata.findProperty("codes").orElseThrow().elementCollectionInfo();
        ElementCollectionInfo map = metadata.findProperty("byCode").orElseThrow().elementCollectionInfo();

        assertEquals(String.class, basic.columnType());
        assertEquals("a", basic.toColumnValue(new Code("a")));
        assertEquals(new Code("b"), embedded.toPropertyValue("b"));
        assertEquals("c", values.encodeElementValue(new Code("c")));
        assertEquals(new Code("d"), map.decodeElementValue("d"));
        assertEquals("k", map.mapKey().encodeKey(new Code("k")));
    }

    @Test
    void explicitConverterOverridesAutoApplyAndDisableSuppressesIt() {
        EntityMetadataFactory factory = factory(CodeConverter.class, UpperCodeConverter.class);
        EntityMetadata<OverrideEntity> metadata = factory.getEntityMetadata(OverrideEntity.class);

        PersistentProperty explicit = metadata.findProperty("explicit").orElseThrow();
        PersistentProperty disabled = metadata.findProperty("disabled").orElseThrow();
        assertEquals("ABC", explicit.toColumnValue(new Code("abc")));
        assertEquals(Code.class, disabled.columnType());
        assertEquals(new Code("abc"), disabled.toColumnValue(new Code("abc")));
    }

    @Test
    void rejectsAmbiguousAutoApplyConverterAtTheMappedAttribute() {
        EntityMetadataFactory factory = factory(CodeConverter.class, SecondAutoCodeConverter.class);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> factory.getEntityMetadata(ValueEntity.class));
        assertTrue(error.getMessage().contains("multiple applicable JPA converters"));
    }

    @Test
    void autoApplyUsesPrimitiveWrapperEquivalenceButNeverAppliesToId() {
        EntityMetadataFactory factory = factory(LongStringConverter.class);
        EntityMetadata<LongEntity> metadata = factory.getEntityMetadata(LongEntity.class);

        assertEquals(long.class, metadata.idProperty().columnType());
        PersistentProperty count = metadata.findProperty("count").orElseThrow();
        assertEquals(String.class, count.columnType());
        assertEquals("7", count.toColumnValue(7L));
    }

    @Test
    void mapsImplicitAndExplicitEnumsThroughEnumeratedValue() {
        EntityMetadata<EnumEntity> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(EnumEntity.class);
        PersistentProperty implicit = metadata.findProperty("implicit").orElseThrow();
        PersistentProperty numeric = metadata.findProperty("numeric").orElseThrow();

        assertEquals(EnumType.STRING, implicit.enumType());
        assertEquals(String.class, implicit.columnType());
        assertEquals("open-code", implicit.toColumnValue(TextStatus.OPEN));
        assertEquals(TextStatus.CLOSED, implicit.toPropertyValue("closed-code"));
        assertEquals(Short.class, numeric.columnType());
        assertEquals((short) -1, numeric.toColumnValue(NumberStatus.CLOSED));
    }

    @Test
    void validatesEnumeratedValueShapeAndUniquenessAtMetadataBuild() {
        assertThrows(IllegalArgumentException.class, () -> metadataFor(BadMutableEntity.class));
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class, () -> metadataFor(BadDuplicateEntity.class));
        assertTrue(duplicate.getMessage().contains("duplicate value"));
    }

    @Test
    void appliedConverterTakesPrecedenceOverImplicitEnumeratedValue() {
        EntityMetadataFactory factory = factory(TextStatusConverter.class);
        PersistentProperty status = factory.getEntityMetadata(ConvertedEnumEntity.class)
                .findProperty("status").orElseThrow();
        assertFalse(status.enumerated());
        assertEquals(Integer.class, status.columnType());
        assertEquals(1, status.toColumnValue(TextStatus.CLOSED));
    }

    @Test
    void enumeratedValueStillMapsAnIdWhileAutoApplyRemainsExcluded() {
        PersistentProperty id = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(EnumIdEntity.class).idProperty();
        assertEquals(String.class, id.columnType());
        assertEquals("open-code", id.toColumnValue(TextStatus.OPEN));
    }

    private static EntityMetadata<?> metadataFor(Class<?> type) {
        return new EntityMetadataFactory(new DefaultNamingStrategy()).getEntityMetadata(type);
    }

    private static EntityMetadataFactory factory(Class<?>... converters) {
        EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
        factory.registerManagedClasses(List.of(converters));
        return factory;
    }

    record Code(String value) {
    }

    @Converter(autoApply = true)
    public static class CodeConverter implements jakarta.persistence.AttributeConverter<Code, String> {
        public String convertToDatabaseColumn(Code value) { return value == null ? null : value.value(); }
        public Code convertToEntityAttribute(String value) { return value == null ? null : new Code(value); }
    }

    @Converter
    public static class UpperCodeConverter implements jakarta.persistence.AttributeConverter<Code, String> {
        public String convertToDatabaseColumn(Code value) { return value == null ? null : value.value().toUpperCase(); }
        public Code convertToEntityAttribute(String value) { return value == null ? null : new Code(value.toLowerCase()); }
    }

    @Converter(autoApply = true)
    public static class SecondAutoCodeConverter extends UpperCodeConverter {
    }

    @Converter(autoApply = true)
    public static class LongStringConverter implements jakarta.persistence.AttributeConverter<Long, String> {
        public String convertToDatabaseColumn(Long value) { return value == null ? null : value.toString(); }
        public Long convertToEntityAttribute(String value) { return value == null ? null : Long.valueOf(value); }
    }

    @Converter(autoApply = true)
    public static class TextStatusConverter implements jakarta.persistence.AttributeConverter<TextStatus, Integer> {
        public Integer convertToDatabaseColumn(TextStatus value) { return value == null ? null : value.ordinal(); }
        public TextStatus convertToEntityAttribute(Integer value) { return value == null ? null : TextStatus.values()[value]; }
    }

    @Embeddable
    static class Address {
        Code code;
    }

    @Entity
    static class ValueEntity {
        @Id Long id;
        Code code;
        @Embedded Address address;
        @ElementCollection List<Code> codes;
        @ElementCollection Map<Code, Code> byCode;
    }

    @Entity
    static class OverrideEntity {
        @Id Long id;
        @Convert(converter = UpperCodeConverter.class) Code explicit;
        @Convert(disableConversion = true) Code disabled;
    }

    @Entity
    static class LongEntity {
        @Id long id;
        long count;
    }

    enum TextStatus {
        OPEN("open-code"), CLOSED("closed-code");
        @EnumeratedValue final String code;
        TextStatus(String code) { this.code = code; }
    }

    enum NumberStatus {
        OPEN((byte) 2), CLOSED((byte) -1);
        @EnumeratedValue final byte code;
        NumberStatus(byte code) { this.code = code; }
    }

    enum BadMutable {
        A("a");
        @EnumeratedValue String code;
        BadMutable(String code) { this.code = code; }
    }

    enum BadDuplicate {
        A("same"), B("same");
        @EnumeratedValue final String code;
        BadDuplicate(String code) { this.code = code; }
    }

    @Entity static class EnumEntity {
        @Id Long id;
        TextStatus implicit;
        @Enumerated(EnumType.ORDINAL) NumberStatus numeric;
    }
    @Entity static class BadMutableEntity { @Id Long id; BadMutable status; }
    @Entity static class BadDuplicateEntity { @Id Long id; BadDuplicate status; }
    @Entity static class ConvertedEnumEntity { @Id Long id; TextStatus status; }
    @Entity static class EnumIdEntity { @Id TextStatus id; }
}
