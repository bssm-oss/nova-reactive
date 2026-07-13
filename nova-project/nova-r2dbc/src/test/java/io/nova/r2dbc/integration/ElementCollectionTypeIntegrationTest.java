package io.nova.r2dbc.integration;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * enum(STRING/ORDINAL)·UUID·java.time 등 기본 스칼라와 대칭인 {@code @ElementCollection} 원소 타입이 H2
 * in-memory R2DBC driver와 end-to-end로 왕복하는지 검증한다 — collection table DDL, save 시 저장 표현 인코딩,
 * findById 하이드레이션의 도메인 타입 복원(converter read-source-type 함정 회피)까지 전 경로.
 */
class ElementCollectionTypeIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Palette.class).block();
    }

    @Test
    void enumStringElementsRoundTrip() {
        Palette p = new Palette();
        p.getStringColors().add(Color.RED);
        p.getStringColors().add(Color.BLUE);
        Long id = support.operations().save(p).map(Palette::getId).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(loaded -> assertEquals(Set.of(Color.RED, Color.BLUE), loaded.getStringColors()))
                .verifyComplete();
    }

    @Test
    void enumOrdinalElementsRoundTrip() {
        Palette p = new Palette();
        p.getOrdinalColors().add(Color.GREEN);
        p.getOrdinalColors().add(Color.BLUE);
        Long id = support.operations().save(p).map(Palette::getId).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(loaded -> assertEquals(Set.of(Color.GREEN, Color.BLUE), loaded.getOrdinalColors()))
                .verifyComplete();
    }

    @Test
    void uuidElementsRoundTrip() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Palette p = new Palette();
        p.getRefs().add(a);
        p.getRefs().add(b);
        Long id = support.operations().save(p).map(Palette::getId).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(loaded -> assertEquals(Set.of(a, b), loaded.getRefs()))
                .verifyComplete();
    }

    @Test
    void localDateElementsRoundTrip() {
        Palette p = new Palette();
        p.getDates().add(LocalDate.of(2020, 1, 2));
        p.getDates().add(LocalDate.of(2021, 3, 4));
        Long id = support.operations().save(p).map(Palette::getId).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(loaded -> assertEquals(
                        List.of(LocalDate.of(2020, 1, 2), LocalDate.of(2021, 3, 4)), loaded.getDates()))
                .verifyComplete();
    }

    @Test
    void floatElementsRoundTrip() {
        // 스칼라 sqlType이 지원하지 않는 타입이지만 H2 R2DBC driver가 real ↔ Float 왕복을 수용함을 실측으로 보증.
        Palette p = new Palette();
        p.getScales().add(1.5f);
        p.getScales().add(2.25f);
        Long id = support.operations().save(p).map(Palette::getId).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(loaded -> assertEquals(List.of(1.5f, 2.25f), loaded.getScales()))
                .verifyComplete();
    }

    @Test
    void localTimeElementsRoundTrip() {
        Palette p = new Palette();
        p.getTimes().add(LocalTime.of(1, 2, 3));
        p.getTimes().add(LocalTime.of(23, 59, 0));
        Long id = support.operations().save(p).map(Palette::getId).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(loaded -> assertEquals(
                        List.of(LocalTime.of(1, 2, 3), LocalTime.of(23, 59, 0)), loaded.getTimes()))
                .verifyComplete();
    }

    @Test
    void localDateTimeElementsRoundTrip() {
        Palette p = new Palette();
        p.getTimestamps().add(LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        Long id = support.operations().save(p).map(Palette::getId).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(loaded -> assertEquals(
                        List.of(LocalDateTime.of(2020, 1, 2, 3, 4, 5)), loaded.getTimestamps()))
                .verifyComplete();
    }

    @Test
    void reSaveFullReplacesEnumElements() {
        Palette p = new Palette();
        p.getStringColors().add(Color.RED);
        Long id = support.operations().save(p).map(Palette::getId).block();

        Palette loaded = support.operations().findById(Palette.class, id).block();
        loaded.getStringColors().clear();
        loaded.getStringColors().add(Color.GREEN);
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Palette.class, id))
                .assertNext(reloaded -> assertEquals(Set.of(Color.GREEN), reloaded.getStringColors()))
                .verifyComplete();
    }

    enum Color { RED, GREEN, BLUE }

    @Entity
    @Table(name = "palette")
    public static class Palette {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @ElementCollection
        @Enumerated(EnumType.STRING)
        private Set<Color> stringColors = new LinkedHashSet<>();

        @ElementCollection
        @Enumerated(EnumType.ORDINAL)
        private Set<Color> ordinalColors = new LinkedHashSet<>();

        @ElementCollection
        private Set<UUID> refs = new LinkedHashSet<>();

        @ElementCollection
        private List<LocalDate> dates = new java.util.ArrayList<>();

        @ElementCollection
        private List<Float> scales = new java.util.ArrayList<>();

        @ElementCollection
        private List<LocalTime> times = new java.util.ArrayList<>();

        @ElementCollection
        private List<LocalDateTime> timestamps = new java.util.ArrayList<>();

        public Palette() {
        }

        public Long getId() {
            return id;
        }

        public Set<Color> getStringColors() {
            return stringColors;
        }

        public Set<Color> getOrdinalColors() {
            return ordinalColors;
        }

        public Set<UUID> getRefs() {
            return refs;
        }

        public List<LocalDate> getDates() {
            return dates;
        }

        public List<Float> getScales() {
            return scales;
        }

        public List<LocalTime> getTimes() {
            return times;
        }

        public List<LocalDateTime> getTimestamps() {
            return timestamps;
        }
    }
}
