package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.support.fixtures.FixtureEntities.AuditedAccount;
import io.nova.support.fixtures.FixtureEntities.LocalDateTimeAuditedAccount;
import io.nova.support.fixtures.FixtureEntities.OffsetDateTimeAuditedAccount;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditApplierTest {
    private static final Instant FIXED = Instant.parse("2026-05-18T09:30:00Z");
    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final AuditApplier applier = new AuditApplier(clock);

    @Test
    void appliesBothFieldsOnInsertForInstantType() {
        EntityMetadata<AuditedAccount> metadata = factory.getEntityMetadata(AuditedAccount.class);
        AuditedAccount account = new AuditedAccount(null, "x@nova.io");

        applier.applyOnInsert(account, metadata);

        assertEquals(FIXED, account.getCreatedAt());
        assertEquals(FIXED, account.getUpdatedAt());
    }

    @Test
    void preservesUserSetCreatedAtOnInsert() {
        EntityMetadata<AuditedAccount> metadata = factory.getEntityMetadata(AuditedAccount.class);
        Instant userSet = Instant.parse("2020-01-01T00:00:00Z");
        AuditedAccount account = new AuditedAccount(null, "x@nova.io", userSet, null);

        applier.applyOnInsert(account, metadata);

        assertEquals(userSet, account.getCreatedAt());
        assertEquals(FIXED, account.getUpdatedAt());
    }

    @Test
    void appliesOnlyUpdatedAtOnUpdate() {
        EntityMetadata<AuditedAccount> metadata = factory.getEntityMetadata(AuditedAccount.class);
        Instant originalCreatedAt = Instant.parse("2020-01-01T00:00:00Z");
        AuditedAccount account = new AuditedAccount(7L, "x@nova.io", originalCreatedAt, null);

        applier.applyOnUpdate(account, metadata);

        assertEquals(originalCreatedAt, account.getCreatedAt());
        assertEquals(FIXED, account.getUpdatedAt());
    }

    @Test
    void overwritesExistingUpdatedAtOnUpdate() {
        EntityMetadata<AuditedAccount> metadata = factory.getEntityMetadata(AuditedAccount.class);
        Instant stale = Instant.parse("2020-01-01T00:00:00Z");
        AuditedAccount account = new AuditedAccount(7L, "x@nova.io", stale, stale);

        applier.applyOnUpdate(account, metadata);

        assertEquals(FIXED, account.getUpdatedAt());
    }

    @Test
    void supportsLocalDateTimeAuditField() {
        EntityMetadata<LocalDateTimeAuditedAccount> metadata = factory.getEntityMetadata(LocalDateTimeAuditedAccount.class);
        LocalDateTimeAuditedAccount account = new LocalDateTimeAuditedAccount();

        applier.applyOnUpdate(account, metadata);

        assertEquals(LocalDateTime.ofInstant(FIXED, ZoneOffset.UTC), account.getUpdatedAt());
    }

    @Test
    void supportsOffsetDateTimeAuditField() {
        EntityMetadata<OffsetDateTimeAuditedAccount> metadata = factory.getEntityMetadata(OffsetDateTimeAuditedAccount.class);
        OffsetDateTimeAuditedAccount account = new OffsetDateTimeAuditedAccount();

        applier.applyOnUpdate(account, metadata);

        assertEquals(OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC), account.getUpdatedAt());
    }

    @Test
    void noOpForEntitiesWithoutAuditAnnotations() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);
        SampleAccount account = new SampleAccount(7L, "x@nova.io", true);

        applier.applyOnInsert(account, metadata);
        applier.applyOnUpdate(account, metadata);

        assertNotNull(account.getEmail());
        assertTrue(applier.updatedAtPropertyName(metadata).isEmpty());
    }

    @Test
    void reportsUpdatedAtPropertyName() {
        EntityMetadata<AuditedAccount> metadata = factory.getEntityMetadata(AuditedAccount.class);

        assertEquals("updatedAt", applier.updatedAtPropertyName(metadata).orElseThrow());
    }
}
