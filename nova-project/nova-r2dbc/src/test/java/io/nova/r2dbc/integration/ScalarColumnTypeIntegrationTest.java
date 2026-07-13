package io.nova.r2dbc.integration;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 스칼라 {@code @Column} 타입 UUID·Float·Short와 non-String({@code UUID}) map key가 H2 in-memory R2DBC driver와
 * end-to-end로 왕복하는지 검증한다 — 컬럼 DDL, save 시 저장 표현 인코딩, findById 하이드레이션의 도메인 타입
 * 복원(UUID는 varchar 경유 read-source-type 함정 회피)까지 전 경로. {@code @ElementCollection} 원소 타입 지원과
 * 대칭이다.
 */
class ScalarColumnTypeIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Widget.class).block();
    }

    @Test
    void uuidScalarColumnRoundTrips() {
        UUID uid = UUID.randomUUID();
        Widget w = new Widget();
        w.setUid(uid);
        Long id = support.operations().save(w).map(Widget::getId).block();

        StepVerifier.create(support.operations().findById(Widget.class, id))
                .assertNext(loaded -> assertEquals(uid, loaded.getUid()))
                .verifyComplete();
    }

    @Test
    void floatScalarColumnRoundTrips() {
        Widget w = new Widget();
        w.setRatio(2.25f);
        Long id = support.operations().save(w).map(Widget::getId).block();

        StepVerifier.create(support.operations().findById(Widget.class, id))
                .assertNext(loaded -> assertEquals(2.25f, loaded.getRatio()))
                .verifyComplete();
    }

    @Test
    void shortScalarColumnRoundTrips() {
        Widget w = new Widget();
        w.setLevel((short) 42);
        Long id = support.operations().save(w).map(Widget::getId).block();

        StepVerifier.create(support.operations().findById(Widget.class, id))
                .assertNext(loaded -> assertEquals((short) 42, loaded.getLevel()))
                .verifyComplete();
    }

    @Test
    void allScalarColumnsRoundTripTogether() {
        UUID uid = UUID.randomUUID();
        Widget w = new Widget();
        w.setUid(uid);
        w.setRatio(-1.5f);
        w.setLevel((short) -7);
        Long id = support.operations().save(w).map(Widget::getId).block();

        StepVerifier.create(support.operations().findById(Widget.class, id))
                .assertNext(loaded -> {
                    assertEquals(uid, loaded.getUid());
                    assertEquals(-1.5f, loaded.getRatio());
                    assertEquals((short) -7, loaded.getLevel());
                })
                .verifyComplete();
    }

    @Test
    void uuidMapKeyRoundTrips() {
        UUID k1 = UUID.randomUUID();
        UUID k2 = UUID.randomUUID();
        Widget w = new Widget();
        w.getTags().put(k1, "alpha");
        w.getTags().put(k2, "beta");
        Long id = support.operations().save(w).map(Widget::getId).block();

        StepVerifier.create(support.operations().findById(Widget.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getTags().size());
                    assertEquals("alpha", loaded.getTags().get(k1));
                    assertEquals("beta", loaded.getTags().get(k2));
                })
                .verifyComplete();
    }

    @Test
    void uuidGeneratedIdRoundTripsAsVarchar() {
        // @Id UUID도 스칼라 UUID 컬럼과 대칭으로 varchar(String) 저장타입을 공유한다 — 이전엔 sqlType(UUID)이
        // 미지원으로 던져 UUID @Id 스키마 생성 자체가 불가했다. 이제 uniform varchar로 왕복한다.
        H2IntegrationTestSupport uuidSupport = H2IntegrationTestSupport.create();
        new SimpleSchemaInitializer(uuidSupport.operations(), uuidSupport.metadataFactory(), uuidSupport.dialect())
                .create(UuidKeyed.class).block();

        UuidKeyed e = new UuidKeyed();
        e.setLabel("hello");
        UUID id = uuidSupport.operations().save(e).map(UuidKeyed::getId).block();
        org.junit.jupiter.api.Assertions.assertNotNull(id);

        StepVerifier.create(uuidSupport.operations().findById(UuidKeyed.class, id))
                .assertNext(loaded -> {
                    assertEquals(id, loaded.getId());
                    assertEquals("hello", loaded.getLabel());
                })
                .verifyComplete();
    }

    @Entity
    @Table(name = "uuid_keyed")
    public static class UuidKeyed {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String label;

        public UuidKeyed() {
        }

        public UUID getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    @Entity
    @Table(name = "widget")
    public static class Widget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private UUID uid;
        private Float ratio;
        private Short level;

        @ElementCollection
        @MapKeyColumn(name = "tag_key")
        private Map<UUID, String> tags = new LinkedHashMap<>();

        public Widget() {
        }

        public Long getId() {
            return id;
        }

        public UUID getUid() {
            return uid;
        }

        public void setUid(UUID uid) {
            this.uid = uid;
        }

        public Float getRatio() {
            return ratio;
        }

        public void setRatio(Float ratio) {
            this.ratio = ratio;
        }

        public Short getLevel() {
            return level;
        }

        public void setLevel(Short level) {
            this.level = level;
        }

        public Map<UUID, String> getTags() {
            return tags;
        }
    }
}
