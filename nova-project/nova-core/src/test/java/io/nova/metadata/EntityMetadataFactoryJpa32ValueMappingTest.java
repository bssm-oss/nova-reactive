package io.nova.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumeratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyTemporal;
import jakarta.persistence.TemporalType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Date;

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

    @Test
    void excludesEmbeddedIdLeavesFromAutoApplyButKeepsEnumValueMapping() {
        EntityMetadataFactory factory = factory(CodeConverter.class, TextStatusConverter.class);
        EntityMetadata<CompositeIdEntity> metadata = factory.getEntityMetadata(CompositeIdEntity.class);

        PersistentProperty code = metadata.findProperty("id.code").orElseThrow();
        PersistentProperty status = metadata.findProperty("id.status").orElseThrow();
        assertTrue(code.id());
        assertEquals(Code.class, code.columnType());
        assertEquals(new Code("raw"), code.toColumnValue(new Code("raw")));
        assertEquals(String.class, status.columnType());
        assertEquals("closed-code", status.toColumnValue(TextStatus.CLOSED));
    }

    @Test
    void hostConvertOverridesFlatAndNestedEmbeddedLeavesAndCanDisableAutoApply() {
        EntityMetadataFactory factory = factory(CodeConverter.class, UpperCodeConverter.class);
        EntityMetadata<EmbeddedOverrideEntity> metadata = factory.getEntityMetadata(EmbeddedOverrideEntity.class);

        assertEquals("ABC", metadata.findProperty("flat.code").orElseThrow()
                .toColumnValue(new Code("abc")));
        assertEquals("XYZ", metadata.findProperty("nested.address.code").orElseThrow()
                .toColumnValue(new Code("xyz")));
        PersistentProperty disabled = metadata.findProperty("disabled.code").orElseThrow();
        assertEquals(Code.class, disabled.columnType());
    }

    @Test
    void rejectsUnknownAndDuplicateEmbeddedConvertPaths() {
        EntityMetadataFactory factory = factory(CodeConverter.class, UpperCodeConverter.class);
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(UnknownEmbeddedPathEntity.class));
        assertTrue(unknown.getMessage().contains("does not match an embedded leaf"));

        EntityMetadataFactory duplicateFactory = factory(CodeConverter.class, UpperCodeConverter.class);
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> duplicateFactory.getEntityMetadata(DuplicateEmbeddedPathEntity.class));
        assertTrue(duplicate.getMessage().contains("duplicate @Convert path"));
    }

    @Test
    void rejectsConverterRegistrationAfterMetadataBuildStarts() {
        EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
        factory.getEntityMetadata(EnumEntity.class);
        IllegalStateException direct = assertThrows(
                IllegalStateException.class, () -> factory.registerJpaConverter(CodeConverter.class));
        assertTrue(direct.getMessage().contains("before the first entity metadata"));
        assertThrows(IllegalStateException.class,
                () -> factory.registerManagedClasses(List.of(CodeConverter.class)));
    }

    @Test
    void resolvesConverterTypesAcrossGenericSuperclassHops() {
        EntityMetadataFactory factory = factory(ConcreteCodeConverter.class);
        PersistentProperty property = factory.getEntityMetadata(ValueEntity.class)
                .findProperty("code").orElseThrow();
        assertEquals(String.class, property.columnType());
        assertEquals("generic", property.toColumnValue(new Code("generic")));
    }

    @Test
    void rejectsMapKeyConvertCombinedWithMapKeyTemporal() {
        EntityMetadataFactory factory = factory(DateStringConverter.class);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.getEntityMetadata(InvalidTemporalMapEntity.class));
        assertTrue(error.getMessage().contains("@MapKeyTemporal"));
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

    abstract static class GenericBaseConverter<T>
            implements jakarta.persistence.AttributeConverter<T, String> {
        public String convertToDatabaseColumn(T value) { return value == null ? null : ((Code) value).value(); }
        @SuppressWarnings("unchecked")
        public T convertToEntityAttribute(String value) { return value == null ? null : (T) new Code(value); }
    }

    abstract static class IntermediateCodeConverter<T> extends GenericBaseConverter<T> {
    }

    @Converter(autoApply = true)
    public static class ConcreteCodeConverter extends IntermediateCodeConverter<Code> {
    }

    @Converter
    public static class DateStringConverter implements jakarta.persistence.AttributeConverter<Date, String> {
        public String convertToDatabaseColumn(Date value) { return value == null ? null : Long.toString(value.getTime()); }
        public Date convertToEntityAttribute(String value) { return value == null ? null : new Date(Long.parseLong(value)); }
    }

    @Embeddable
    static class Address {
        Code code;
    }

    @Embeddable
    static class NestedAddress {
        @Embedded Address address;
    }

    @Embeddable
    static class CompositeId {
        Code code;
        TextStatus status;
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
    @Entity static class CompositeIdEntity { @EmbeddedId CompositeId id; }

    @Entity static class EmbeddedOverrideEntity {
        @Id Long id;
        @Embedded @Convert(attributeName = "code", converter = UpperCodeConverter.class) Address flat;
        @Embedded @Convert(attributeName = "address.code", converter = UpperCodeConverter.class) NestedAddress nested;
        @Embedded @Convert(attributeName = "code", disableConversion = true) Address disabled;
    }

    @Entity static class UnknownEmbeddedPathEntity {
        @Id Long id;
        @Embedded @Convert(attributeName = "missing", converter = UpperCodeConverter.class) Address address;
    }

    @Entity static class DuplicateEmbeddedPathEntity {
        @Id Long id;
        @Embedded
        @Convert(attributeName = "code", converter = UpperCodeConverter.class)
        @Convert(attributeName = "code", disableConversion = true)
        Address address;
    }

    @Entity static class InvalidTemporalMapEntity {
        @Id Long id;
        @ElementCollection
        @MapKeyTemporal(TemporalType.TIMESTAMP)
        @Convert(attributeName = "key", converter = DateStringConverter.class)
        Map<Date, String> values;
    }
}
