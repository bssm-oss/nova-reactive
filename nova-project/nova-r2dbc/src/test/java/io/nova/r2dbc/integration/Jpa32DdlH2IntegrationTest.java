package io.nova.r2dbc.integration;

import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises JPA 3.2 table/column DDL against the production r2dbc-h2 stack. */
class Jpa32DdlH2IntegrationTest {
    private H2IntegrationTestSupport support;
    private SchemaInitializer schema;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        schema = new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
    }

    @Test
    void checksCommentsSecondPrecisionAndUpdateRerunWorkEndToEnd() {
        StepVerifier.create(schema.create(Ddl32Event.class)).verifyComplete();
        // UPDATE/create-missing-tables mode must not attempt to converge comments or otherwise fail on an existing table.
        StepVerifier.create(schema.create(Ddl32Event.class)).verifyComplete();

        Ddl32Event valid = new Ddl32Event();
        valid.amount = 7;
        valid.occurredAt = LocalDateTime.of(2026, 8, 27, 12, 34, 56, 987_654_321);
        StepVerifier.create(support.operations().save(valid)).expectNextCount(1).verifyComplete();

        StepVerifier.create(support.operations().findById(Ddl32Event.class, valid.id))
                .assertNext(loaded -> assertEquals(
                        LocalDateTime.of(2026, 8, 27, 12, 34, 56, 988_000_000), loaded.occurredAt))
                .verifyComplete();

        Ddl32Event invalid = new Ddl32Event();
        invalid.amount = -1;
        invalid.occurredAt = LocalDateTime.now();
        StepVerifier.create(support.operations().save(invalid)).expectError().verify();

        StepVerifier.create(support.operations().queryNative(
                        NativeQuery.of("select datetime_precision from information_schema.columns "
                                + "where table_name = 'ddl32_event' and column_name = 'occurred_at'"),
                        row -> row.get("datetime_precision", Integer.class)))
                .expectNext(3)
                .verifyComplete();

        StepVerifier.create(support.operations().queryNative(
                        NativeQuery.of("select remarks from information_schema.tables where table_name = 'ddl32_event'"),
                        row -> row.get("remarks", String.class)))
                .expectNext("events' table")
                .verifyComplete();
        StepVerifier.create(support.operations().queryNative(
                        NativeQuery.of("select remarks from information_schema.columns "
                                + "where table_name = 'ddl32_event' and column_name = 'occurred_at'"),
                        row -> row.get("remarks", String.class)))
                .expectNext("event's instant")
                .verifyComplete();
    }

    @Entity
    @Table(name = "ddl32_event", check = @CheckConstraint(name = "chk_event_amount", constraint = "\"amount\" >= 0"),
            comment = "events' table")
    static class Ddl32Event {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        Integer amount;

        @Column(secondPrecision = 3, comment = "event's instant")
        LocalDateTime occurredAt;
    }
}
