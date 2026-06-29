package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.MissingTemporalEntity;
import io.nova.support.fixtures.FixtureEntities.TemporalEvent;
import io.nova.support.fixtures.FixtureEntities.TemporalOnJavaTimeEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 레거시 JPA {@code @Temporal}({@link java.util.Date}/{@link java.util.Calendar}) 소스 호환을 보호한다.
 * 컬럼 저장 타입 결정과 값 변환 방향, 그리고 fail-fast 거부 케이스를 검증한다.
 */
class EntityMetadataFactoryTemporalTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void mapsTemporalDateToLocalDateStorageType() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty property = metadata.findProperty("eventDate").orElseThrow();

        // 도메인 타입은 java.util.Date지만 저장/디코딩 타입(columnType)은 LocalDate여야 한다(read source type = columnType).
        assertEquals(Date.class, property.javaType());
        assertEquals(LocalDate.class, property.columnType());
        assertEquals(LocalDate.class, property.converterColumnType());
    }

    @Test
    void mapsTemporalTimeToLocalTimeStorageType() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty property = metadata.findProperty("eventTime").orElseThrow();

        assertEquals(LocalTime.class, property.columnType());
        assertEquals(LocalTime.class, property.converterColumnType());
    }

    @Test
    void mapsTemporalTimestampToLocalDateTimeStorageType() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty property = metadata.findProperty("eventTimestamp").orElseThrow();

        assertEquals(LocalDateTime.class, property.columnType());
        assertEquals(LocalDateTime.class, property.converterColumnType());
    }

    @Test
    void mapsCalendarTimestampToLocalDateTimeStorageType() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty property = metadata.findProperty("scheduledAt").orElseThrow();

        assertEquals(Calendar.class, property.javaType());
        assertEquals(LocalDateTime.class, property.columnType());
    }

    @Test
    void convertsDateToStorageTypeAndBack() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty timestamp = metadata.findProperty("eventTimestamp").orElseThrow();

        LocalDateTime base = LocalDateTime.of(2023, 6, 15, 14, 30, 45);
        Date input = Date.from(base.atZone(ZoneId.systemDefault()).toInstant());

        // write(엔티티→컬럼): Date → LocalDateTime 저장 타입.
        Object stored = timestamp.toColumnValue(input);
        assertEquals(base, stored);

        // read(컬럼→엔티티): LocalDateTime → Date 도메인 타입(round-trip 동치).
        Object restored = timestamp.toPropertyValue(base);
        assertInstanceOf(Date.class, restored);
        assertEquals(input, restored);
    }

    @Test
    void convertsDateTimeColumnToCalendarForCalendarField() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty scheduled = metadata.findProperty("scheduledAt").orElseThrow();

        LocalDateTime base = LocalDateTime.of(2024, 1, 2, 8, 0, 0);
        Object restored = scheduled.toPropertyValue(base);

        assertInstanceOf(Calendar.class, restored);
        Date expected = Date.from(base.atZone(ZoneId.systemDefault()).toInstant());
        assertEquals(expected, ((Calendar) restored).getTime());
    }

    @Test
    void truncatesTimeComponentForDateTemporalType() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty date = metadata.findProperty("eventDate").orElseThrow();

        LocalDate day = LocalDate.of(2023, 6, 15);
        Date withTime = Date.from(day.atTime(13, 45, 30).atZone(ZoneId.systemDefault()).toInstant());

        // DATE는 시각 성분을 버린다 → 저장 타입은 LocalDate, 복원은 자정으로 절단된 Date.
        assertEquals(day, date.toColumnValue(withTime));
        Date restored = (Date) date.toPropertyValue(day);
        Date midnight = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
        assertEquals(midnight, restored);
    }

    @Test
    void passesNullThroughConverter() {
        EntityMetadata<TemporalEvent> metadata = factory.getEntityMetadata(TemporalEvent.class);
        PersistentProperty timestamp = metadata.findProperty("eventTimestamp").orElseThrow();

        assertNull(timestamp.toColumnValue(null));
        assertNull(timestamp.toPropertyValue(null));
    }

    @Test
    void rejectsTemporalOnJavaTimeType() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(TemporalOnJavaTimeEntity.class));
        assertTrue(exception.getMessage().contains("@Temporal"),
                "message should mention @Temporal: " + exception.getMessage());
    }

    @Test
    void rejectsMissingTemporalOnDate() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MissingTemporalEntity.class));
        assertTrue(exception.getMessage().contains("@Temporal"),
                "message should mention missing @Temporal: " + exception.getMessage());
    }
}
