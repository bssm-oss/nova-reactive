package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyClass;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyTemporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ElementCollection Map<K,V>}의 temporal key({@code @MapKeyTemporal})와 raw {@code Map} key 타입 결정
 * ({@code @MapKeyClass})이 H2 in-memory R2DBC driver와 end-to-end로 round-trip 되는지 검증한다.
 * <ul>
 *   <li>{@code java.util.Date} key를 DATE/TIME/TIMESTAMP 정밀도로 각각 왕복(각 정밀도별 driver 실증).</li>
 *   <li>{@code java.util.Calendar} key를 TIMESTAMP 정밀도로 왕복.</li>
 *   <li>raw {@code Map} + {@code @MapKeyClass(String.class)}로 key 타입을 결정해 왕복.</li>
 * </ul>
 * SQL string unit test만으로는 java.time 저장 타입(date/time/timestamp) key 컬럼 DDL과 driver binding/decoding
 * 호환을 보장할 수 없으므로 통합 테스트로 보호한다.
 */
class MapKeyTemporalIntegrationTest {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Journal.class).block();
    }

    @Test
    void restoresDateKeyMapAtDatePrecision() {
        Date d1 = dateAt(LocalDate.of(2023, 6, 15).atStartOfDay());
        Date d2 = dateAt(LocalDate.of(2024, 1, 2).atStartOfDay());
        Journal journal = new Journal();
        journal.getByDate().put(d1, 11);
        journal.getByDate().put(d2, 22);
        Long id = support.operations().save(journal).map(Journal::getId).block();

        StepVerifier.create(support.operations().findById(Journal.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getByDate().size());
                    assertEquals(11, loaded.getByDate().get(d1));
                    assertEquals(22, loaded.getByDate().get(d2));
                })
                .verifyComplete();
    }

    @Test
    void restoresDateKeyMapAtTimePrecision() {
        Date t1 = dateAt(LocalTime.of(9, 30, 0).atDate(LocalDate.ofEpochDay(0)));
        Date t2 = dateAt(LocalTime.of(17, 45, 15).atDate(LocalDate.ofEpochDay(0)));
        Journal journal = new Journal();
        journal.getByTime().put(t1, 1);
        journal.getByTime().put(t2, 2);
        Long id = support.operations().save(journal).map(Journal::getId).block();

        StepVerifier.create(support.operations().findById(Journal.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getByTime().size());
                    assertEquals(1, loaded.getByTime().get(t1));
                    assertEquals(2, loaded.getByTime().get(t2));
                })
                .verifyComplete();
    }

    @Test
    void restoresDateKeyMapAtTimestampPrecision() {
        Date ts1 = dateAt(LocalDateTime.of(2023, 6, 15, 14, 30, 45));
        Date ts2 = dateAt(LocalDateTime.of(2024, 1, 2, 8, 0, 0));
        Journal journal = new Journal();
        journal.getByTimestamp().put(ts1, 100);
        journal.getByTimestamp().put(ts2, 200);
        Long id = support.operations().save(journal).map(Journal::getId).block();

        StepVerifier.create(support.operations().findById(Journal.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getByTimestamp().size());
                    assertEquals(100, loaded.getByTimestamp().get(ts1));
                    assertEquals(200, loaded.getByTimestamp().get(ts2));
                })
                .verifyComplete();
    }

    @Test
    void restoresCalendarKeyMapAtTimestampPrecision() {
        Calendar c1 = new GregorianCalendar();
        c1.setTime(dateAt(LocalDateTime.of(2022, 3, 4, 5, 6, 7)));
        Journal journal = new Journal();
        journal.getByCalendar().put(c1, 7);
        Long id = support.operations().save(journal).map(Journal::getId).block();

        StepVerifier.create(support.operations().findById(Journal.class, id))
                .assertNext(loaded -> {
                    assertEquals(1, loaded.getByCalendar().size());
                    // Calendar equals는 취약하므로 도메인 key 타입과 instant로 왕복을 확정한다.
                    Map.Entry<Calendar, Integer> entry = loaded.getByCalendar().entrySet().iterator().next();
                    assertTrue(entry.getKey() instanceof Calendar);
                    assertEquals(c1.getTime(), entry.getKey().getTime());
                    assertEquals(7, entry.getValue());
                })
                .verifyComplete();
    }

    @Test
    void restoresRawMapKeyResolvedByMapKeyClass() {
        Journal journal = new Journal();
        journal.getRawScores().put("alpha", 1);
        journal.getRawScores().put("beta", 2);
        Long id = support.operations().save(journal).map(Journal::getId).block();

        StepVerifier.create(support.operations().findById(Journal.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getRawScores().size());
                    assertEquals(1, loaded.getRawScores().get("alpha"));
                    assertEquals(2, loaded.getRawScores().get("beta"));
                })
                .verifyComplete();
    }

    private static Date dateAt(LocalDateTime moment) {
        return Date.from(moment.atZone(ZONE).toInstant());
    }

    @Entity
    @Table(name = "journal")
    public static class Journal {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        // 컬렉션만 있는 엔티티는 re-save UPDATE의 SET 절이 비어 부적합하므로 스칼라 컬럼을 하나 둔다.
        private String name = "journal";

        @ElementCollection
        @MapKeyColumn(name = "d_key")
        @MapKeyTemporal(TemporalType.DATE)
        private Map<Date, Integer> byDate = new HashMap<>();

        @ElementCollection
        @MapKeyColumn(name = "t_key")
        @MapKeyTemporal(TemporalType.TIME)
        private Map<Date, Integer> byTime = new HashMap<>();

        @ElementCollection
        @MapKeyColumn(name = "ts_key")
        @MapKeyTemporal(TemporalType.TIMESTAMP)
        private Map<Date, Integer> byTimestamp = new HashMap<>();

        @ElementCollection
        @MapKeyColumn(name = "cal_key")
        @MapKeyTemporal(TemporalType.TIMESTAMP)
        private Map<Calendar, Integer> byCalendar = new HashMap<>();

        // raw Map: value 타입은 targetClass, key 타입은 @MapKeyClass로 결정한다.
        @ElementCollection(targetClass = Integer.class)
        @MapKeyColumn(name = "raw_key")
        @Column(name = "raw_val")
        @MapKeyClass(String.class)
        @SuppressWarnings({"rawtypes", "unchecked"})
        private Map rawScores = new HashMap();

        public Journal() {
        }

        public Long getId() {
            return id;
        }

        public Map<Date, Integer> getByDate() {
            return byDate;
        }

        public Map<Date, Integer> getByTime() {
            return byTime;
        }

        public Map<Date, Integer> getByTimestamp() {
            return byTimestamp;
        }

        public Map<Calendar, Integer> getByCalendar() {
            return byCalendar;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        public Map<String, Integer> getRawScores() {
            return rawScores;
        }
    }
}
