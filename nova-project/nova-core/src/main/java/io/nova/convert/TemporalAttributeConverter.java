package io.nova.convert;

import jakarta.persistence.TemporalType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

/**
 * 레거시 JPA {@code @Temporal} 매핑({@link java.util.Date} / {@link java.util.Calendar})을 Nova의 java.time
 * 네이티브 저장 표현으로 왕복 변환한다. Nova의 일반 converter 경로({@code toColumnValue}/{@code toPropertyValue})에
 * 그대로 태우며, 저장 표현 타입(컬럼이 디코딩되는 타입)은 {@link TemporalType}에 따라
 * {@link LocalDate}/{@link LocalTime}/{@link LocalDateTime} 중 하나다 —
 * {@code EntityMetadataFactory}가 이 타입을 {@code converterColumnType}으로 보관해 schema 컬럼 타입과
 * row 디코딩의 근거로 삼는다(read source type = columnType).
 *
 * <p>변환 방향:
 * <ul>
 *   <li>{@code write}(엔티티→컬럼): {@code Date}/{@code Calendar} → java.time 저장 타입.</li>
 *   <li>{@code read}(컬럼→엔티티): java.time 저장 타입 → 필드 선언 타입과 동일한 {@code Date} 또는
 *       {@code Calendar}.</li>
 * </ul>
 *
 * <p>변환은 시스템 기본 시간대({@link ZoneId#systemDefault()})를 기준으로 한 instant↔calendar-date 환산을
 * 사용한다(Hibernate의 기본 calendar 동작과 동치). {@code DATE}는 시각 성분을, {@code TIME}은 날짜 성분을
 * 버린다(SQL {@code date}/{@code time} 의미와 일치). {@code null}은 그대로 통과시켜 SQL {@code NULL}을 보존한다.
 */
public final class TemporalAttributeConverter implements AttributeConverter<Object, Object> {
    /**
     * TIME 저장 타입을 {@code Date}/{@code Calendar}로 복원할 때 사용하는 고정 기준 날짜. java.sql.Time과
     * 동일하게 1970-01-01(epoch)을 날짜 성분으로 둔다.
     */
    private static final LocalDate TIME_EPOCH_DATE = LocalDate.ofEpochDay(0);

    private final TemporalType temporalType;
    /**
     * 필드 선언 타입이 {@link Calendar}(또는 그 하위 타입)이면 {@code true}이며 {@code read}가 {@code Calendar}를
     * 반환한다. 아니면 {@code java.util.Date}를 반환한다.
     */
    private final boolean calendarAttribute;

    public TemporalAttributeConverter(Class<?> attributeType, TemporalType temporalType) {
        this.temporalType = temporalType;
        this.calendarAttribute = Calendar.class.isAssignableFrom(attributeType);
    }

    @Override
    public Object write(Object source) {
        if (source == null) {
            return null;
        }
        Date date = toDate(source);
        ZoneId zone = ZoneId.systemDefault();
        return switch (temporalType) {
            case DATE -> date.toInstant().atZone(zone).toLocalDate();
            case TIME -> date.toInstant().atZone(zone).toLocalTime();
            case TIMESTAMP -> date.toInstant().atZone(zone).toLocalDateTime();
        };
    }

    @Override
    public Object read(Object source) {
        if (source == null) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        Date date = switch (temporalType) {
            case DATE -> Date.from(asLocalDate(source).atStartOfDay(zone).toInstant());
            case TIME -> Date.from(asLocalTime(source).atDate(TIME_EPOCH_DATE).atZone(zone).toInstant());
            case TIMESTAMP -> Date.from(asLocalDateTime(source).atZone(zone).toInstant());
        };
        if (calendarAttribute) {
            Calendar calendar = new GregorianCalendar();
            calendar.setTime(date);
            return calendar;
        }
        return date;
    }

    private static Date toDate(Object source) {
        if (source instanceof Date date) {
            return date;
        }
        if (source instanceof Calendar calendar) {
            return calendar.getTime();
        }
        throw new IllegalArgumentException(
                "@Temporal converter expects java.util.Date or java.util.Calendar but got "
                        + source.getClass().getName());
    }

    private static LocalDate asLocalDate(Object source) {
        if (source instanceof LocalDate localDate) {
            return localDate;
        }
        if (source instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        throw decodeMismatch(source, "java.time.LocalDate");
    }

    private static LocalTime asLocalTime(Object source) {
        if (source instanceof LocalTime localTime) {
            return localTime;
        }
        if (source instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalTime();
        }
        throw decodeMismatch(source, "java.time.LocalTime");
    }

    private static LocalDateTime asLocalDateTime(Object source) {
        if (source instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (source instanceof LocalDate localDate) {
            return localDate.atStartOfDay();
        }
        throw decodeMismatch(source, "java.time.LocalDateTime");
    }

    private static IllegalArgumentException decodeMismatch(Object source, String expected) {
        return new IllegalArgumentException(
                "@Temporal converter expected the driver to decode the column as " + expected
                        + " but got " + source.getClass().getName());
    }
}
