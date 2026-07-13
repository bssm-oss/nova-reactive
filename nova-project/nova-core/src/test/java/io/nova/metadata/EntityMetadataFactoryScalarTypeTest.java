package io.nova.metadata;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스칼라 {@code @Column} 프로퍼티의 저장 표현 타입({@link PersistentProperty#columnType()})과 encode/decode
 * 경로를 {@code @ElementCollection} 원소와 대칭으로 보호한다 — UUID는 varchar(String)로 저장타입 분리(드라이버가
 * varchar→UUID 직접 디코드를 못 하는 함정 회피), Float/Short는 드라이버 네이티브라 converter 없이 도메인 타입 유지.
 * {@code Map} key의 non-String(UUID) 저장타입 분리도 함께 검증한다.
 */
class EntityMetadataFactoryScalarTypeTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    private PersistentProperty property(String name) {
        return factory.getEntityMetadata(ScalarHolder.class).findProperty(name).orElseThrow();
    }

    @Test
    void uuidScalarStoresAsVarcharViaConverter() {
        PersistentProperty uid = property("uid");
        assertEquals(UUID.class, uid.javaType());
        // 저장타입은 String(varchar), 도메인 타입이 아님 — schema DDL과 row 디코딩이 저장타입을 따른다.
        assertEquals(String.class, uid.columnType());
        assertEquals(String.class, uid.converterColumnType());

        UUID value = UUID.randomUUID();
        Object encoded = uid.toColumnValue(value);
        assertEquals(value.toString(), encoded);
        assertEquals(value, uid.toPropertyValue(encoded));
    }

    @Test
    void floatScalarKeepsDomainTypeWithoutConverter() {
        PersistentProperty ratio = property("ratio");
        assertEquals(Float.class, ratio.columnType());
        assertNull(ratio.converterColumnType());
        assertEquals(1.5f, ratio.toColumnValue(1.5f));
        assertEquals(1.5f, ratio.toPropertyValue(1.5f));
    }

    @Test
    void shortScalarKeepsDomainTypeWithoutConverter() {
        PersistentProperty level = property("level");
        assertEquals(Short.class, level.columnType());
        assertNull(level.converterColumnType());
        assertEquals((short) 7, level.toColumnValue((short) 7));
        assertEquals((short) 7, level.toPropertyValue((short) 7));
    }

    @Test
    void primitiveFloatAndShortKeepPrimitiveStorageTypesWithoutConverter() {
        // primitive 필드는 converter가 없어 columnType()이 primitive 그대로다 — sqlType이 float/short 양쪽을
        // 모두 real/smallint로 유도한다(기존 primitive double/boolean 컬럼과 동일 취급).
        assertEquals(float.class, property("primitiveRatio").columnType());
        assertNull(property("primitiveRatio").converterColumnType());
        assertEquals(short.class, property("primitiveLevel").columnType());
        assertNull(property("primitiveLevel").converterColumnType());
    }

    @Test
    void uuidMapKeyStoresAsVarcharViaKeyConverter() {
        ElementCollectionInfo info = factory.getEntityMetadata(ScalarHolder.class)
                .findProperty("byUuid").orElseThrow().elementCollectionInfo();
        ElementCollectionInfo.MapKeyInfo key = info.mapKey();
        assertEquals(UUID.class, key.keyType());
        // non-String map key도 저장타입 분리 — UUID → varchar(String) via keyConverter.
        assertEquals(String.class, key.keyColumnType());

        UUID k = UUID.randomUUID();
        Object encoded = key.encodeKey(k);
        assertEquals(k.toString(), encoded);
        assertEquals(k, key.decodeKey(encoded));
        assertTrue(encoded instanceof String);
    }

    @Test
    void basicMapKeyKeepsDomainTypeWithoutConverter() {
        ElementCollectionInfo info = factory.getEntityMetadata(ScalarHolder.class)
                .findProperty("byName").orElseThrow().elementCollectionInfo();
        ElementCollectionInfo.MapKeyInfo key = info.mapKey();
        assertEquals(String.class, key.keyColumnType());
        assertNull(key.keyConverter());
        assertEquals("a", key.encodeKey("a"));
        assertEquals("a", key.decodeKey("a"));
    }

    @Entity
    @Table(name = "scalar_holder")
    static class ScalarHolder {
        @Id
        Long id;
        UUID uid;
        Float ratio;
        Short level;
        float primitiveRatio;
        short primitiveLevel;

        @ElementCollection
        @MapKeyColumn(name = "k")
        Map<UUID, String> byUuid;

        @ElementCollection
        @MapKeyColumn(name = "n")
        Map<String, String> byName;
    }
}
