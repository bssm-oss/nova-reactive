package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 레거시 JPA {@code @Temporal}({@link java.util.Date}/{@link java.util.Calendar})이 H2 in-memory R2DBC
 * driver와 end-to-end로 round-trip 되는지 검증한다. DATE/TIME/TIMESTAMP 각각에 대해 save→findById로 값이
 * 보존되는지 본다. SQL string unit test만으로는 java.time 저장 타입(date/time/timestamp) 컬럼 DDL과 driver
 * binding/decoding 호환을 보장할 수 없으므로 통합 테스트로 보호한다.
 */
class TemporalColumnRoundTripIntegrationTest {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // 컬럼은 도메인 타입(java.util.Date)이 아니라 저장 타입(LocalDate/LocalTime/LocalDateTime)으로
        // date/time/timestamp 컬럼이 생성돼야 driver가 디코딩할 수 있다.
        schema.create(TemporalRow.class).block();
    }

    @Test
    void temporalDateTimeTimestampValuesRoundTripThroughDatabase() {
        LocalDate day = LocalDate.of(2023, 6, 15);
        LocalTime time = LocalTime.of(14, 30, 45);
        LocalDateTime timestamp = LocalDateTime.of(2023, 6, 15, 14, 30, 45);
        LocalDateTime scheduled = LocalDateTime.of(2024, 1, 2, 8, 0, 0);

        Date dateInput = Date.from(day.atStartOfDay(ZONE).toInstant());
        Date timeInput = Date.from(time.atDate(LocalDate.ofEpochDay(0)).atZone(ZONE).toInstant());
        Date timestampInput = Date.from(timestamp.atZone(ZONE).toInstant());
        Calendar scheduledInput = new GregorianCalendar();
        scheduledInput.setTime(Date.from(scheduled.atZone(ZONE).toInstant()));

        TemporalRow row = new TemporalRow(dateInput, timeInput, timestampInput, scheduledInput);

        Mono<TemporalRow> pipeline = support.operations().save(row)
                .flatMap(saved -> support.operations().findById(TemporalRow.class, saved.getId()));

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertNotNull(loaded.getEventDate(), "DATE 컬럼이 복원돼야 한다");
                    assertNotNull(loaded.getEventTime(), "TIME 컬럼이 복원돼야 한다");
                    assertNotNull(loaded.getEventTimestamp(), "TIMESTAMP 컬럼이 복원돼야 한다");
                    assertNotNull(loaded.getScheduledAt(), "Calendar TIMESTAMP 컬럼이 복원돼야 한다");

                    assertEquals(dateInput, loaded.getEventDate());
                    assertEquals(timeInput, loaded.getEventTime());
                    assertEquals(timestampInput, loaded.getEventTimestamp());
                    assertEquals(scheduledInput.getTime(), loaded.getScheduledAt().getTime());
                })
                .verifyComplete();
    }

    @Entity
    @Table(name = "temporal_row")
    static class TemporalRow {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Temporal(TemporalType.DATE)
        private Date eventDate;

        @Temporal(TemporalType.TIME)
        private Date eventTime;

        @Temporal(TemporalType.TIMESTAMP)
        private Date eventTimestamp;

        @Temporal(TemporalType.TIMESTAMP)
        private Calendar scheduledAt;

        TemporalRow() {
        }

        TemporalRow(Date eventDate, Date eventTime, Date eventTimestamp, Calendar scheduledAt) {
            this.eventDate = eventDate;
            this.eventTime = eventTime;
            this.eventTimestamp = eventTimestamp;
            this.scheduledAt = scheduledAt;
        }

        Long getId() {
            return id;
        }

        Date getEventDate() {
            return eventDate;
        }

        Date getEventTime() {
            return eventTime;
        }

        Date getEventTimestamp() {
            return eventTimestamp;
        }

        Calendar getScheduledAt() {
            return scheduledAt;
        }
    }
}
