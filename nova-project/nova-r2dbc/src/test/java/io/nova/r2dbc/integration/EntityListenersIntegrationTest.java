package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @EntityListeners} 외부 리스너 콜백이 H2 in-memory R2DBC driver와 end-to-end로 발화하는지 검증한다 —
 * save 시 {@code @PrePersist}/{@code @PostPersist}, findById 시 {@code @PostLoad}. 리스너 콜백이 entity를
 * 단일 인자로 받고, 리스너가 mutate한 필드가 실제 컬럼으로 저장되는지도 확인한다.
 */
class EntityListenersIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        AuditListener.events.clear();
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Document.class).block();
    }

    @Test
    void firesPrePersistAndPostPersistListenerOnSave() {
        Document doc = new Document("nova");

        Long id = support.operations().save(doc).map(Document::getId).block();

        assertTrue(AuditListener.events.contains("prePersist"));
        assertTrue(AuditListener.events.contains("postPersist"));
        // 리스너가 prePersist에서 설정한 audit 필드가 컬럼으로 저장되어 다시 읽힌다.
        StepVerifier.create(support.operations().findById(Document.class, id))
                .assertNext(loaded -> assertEquals("audited", loaded.getAudit()))
                .verifyComplete();
    }

    @Test
    void firesPostLoadListenerOnFindById() {
        Long id = support.operations().save(new Document("nova")).map(Document::getId).block();
        AuditListener.events.clear();

        support.operations().findById(Document.class, id).block();

        assertTrue(AuditListener.events.contains("postLoad"));
    }

    public static class AuditListener {
        static final List<String> events = new ArrayList<>();

        @PrePersist
        public void onPrePersist(Document document) {
            events.add("prePersist");
            document.audit = "audited";
        }

        @PostPersist
        public void onPostPersist(Object entity) {
            events.add("postPersist");
        }

        @PostLoad
        public void onPostLoad(Object entity) {
            events.add("postLoad");
        }
    }

    @Entity
    @Table(name = "document")
    @EntityListeners(AuditListener.class)
    public static class Document {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        @Column(name = "audit")
        String audit;

        public Document() {
        }

        public Document(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getAudit() {
            return audit;
        }
    }
}
