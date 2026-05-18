package io.nova.support.fixtures;

import io.nova.annotation.Column;
import io.nova.annotation.CreatedAt;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.SoftDelete;
import io.nova.annotation.Table;
import io.nova.annotation.UpdatedAt;
import io.nova.annotation.Version;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class FixtureEntities {
    private FixtureEntities() {
    }

    @Entity(name = "account_entity")
    @Table("accounts")
    public static class SampleAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email_address")
        private String email;

        @Column(nullable = false)
        private boolean active;

        public SampleAccount() {
        }

        public SampleAccount(Long id, String email, boolean active) {
            this.id = id;
            this.email = email;
            this.active = active;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public boolean isActive() {
            return active;
        }
    }

    @Entity(name = "order_entity")
    @Table("orders")
    public static class SampleOrder {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("customer_email")
        private String customerEmail;

        @Column("total_cents")
        private long totalCents;

        public SampleOrder() {
        }

        public SampleOrder(Long id, String customerEmail, long totalCents) {
            this.id = id;
            this.customerEmail = customerEmail;
            this.totalCents = totalCents;
        }

        public Long getId() {
            return id;
        }

        public String getCustomerEmail() {
            return customerEmail;
        }

        public long getTotalCents() {
            return totalCents;
        }
    }

    @Entity
    @Table("assigned_accounts")
    public static class AssignedIdAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        public AssignedIdAccount() {
        }

        public AssignedIdAccount(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }
    }

    @Entity
    public static class DefaultNamedEntity {
        @Id
        private Long entityId;

        private String displayName;

        public DefaultNamedEntity() {
        }
    }

    @Entity
    public static class ConvertibleEntity {
        @Id
        private Long id;

        private Status status;

        public ConvertibleEntity() {
        }

        public ConvertibleEntity(Long id, Status status) {
            this.id = id;
            this.status = status;
        }

        public Long getId() {
            return id;
        }

        public Status getStatus() {
            return status;
        }
    }

    @Entity
    public static class UnsupportedTypeEntity {
        @Id
        private Long id;

        private java.math.BigDecimal total;

        public UnsupportedTypeEntity() {
        }
    }

    public static class MissingEntityAnnotation {
        @Id
        private Long id;
    }

    @Entity
    public static class MissingIdEntity {
        private Long id;
    }

    @Entity
    public static class DuplicateIdEntity {
        @Id
        private Long id;

        @Id
        private Long otherId;
    }

    @Entity
    public static class NoDefaultConstructorEntity {
        @Id
        private Long id;

        private String name;

        public NoDefaultConstructorEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    public static class StaticFieldEntity {
        static final String KIND = "account";

        @Id
        private Long id;

        private String displayName;

        public StaticFieldEntity() {
        }
    }

    @Entity
    @Table("versioned_accounts")
    public static class VersionedAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @Version
        private Long version;

        public VersionedAccount() {
        }

        public VersionedAccount(Long id, String email, Long version) {
            this.id = id;
            this.email = email;
            this.version = version;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Long getVersion() {
            return version;
        }
    }

    @Entity
    @Table("generated_versioned_accounts")
    public static class GeneratedVersionedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email_address")
        private String email;

        @Version
        private Long version;

        public GeneratedVersionedAccount() {
        }

        public GeneratedVersionedAccount(Long id, String email, Long version) {
            this.id = id;
            this.email = email;
            this.version = version;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Long getVersion() {
            return version;
        }
    }

    @Entity
    @Table("int_versioned_accounts")
    public static class IntegerVersionedAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @Version
        private Integer version;

        public IntegerVersionedAccount() {
        }

        public IntegerVersionedAccount(Long id, String email, Integer version) {
            this.id = id;
            this.email = email;
            this.version = version;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Integer getVersion() {
            return version;
        }
    }

    @Entity
    @Table("short_versioned_accounts")
    public static class ShortVersionedAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @Version
        private Short version;

        public ShortVersionedAccount() {
        }

        public ShortVersionedAccount(Long id, String email, Short version) {
            this.id = id;
            this.email = email;
            this.version = version;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Short getVersion() {
            return version;
        }
    }

    @Entity
    public static class DuplicateVersionEntity {
        @Id
        private Long id;

        @Version
        private Long version;

        @Version
        private Long otherVersion;

        public DuplicateVersionEntity() {
        }
    }

    @Entity
    public static class UnsupportedVersionTypeEntity {
        @Id
        private Long id;

        @Version
        private String version;

        public UnsupportedVersionTypeEntity() {
        }
    }

    @Entity
    public static class IdVersionConflictEntity {
        @Id
        @Version
        private Long id;

        public IdVersionConflictEntity() {
        }
    }

    public enum Status {
        ACTIVE,
        INACTIVE
    }

    @Entity
    @Table("audited_accounts")
    public static class AuditedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email_address")
        private String email;

        @CreatedAt
        @Column("created_at")
        private Instant createdAt;

        @UpdatedAt
        @Column("updated_at")
        private Instant updatedAt;

        public AuditedAccount() {
        }

        public AuditedAccount(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        public AuditedAccount(Long id, String email, Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.email = email;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }
    }

    @Entity
    @Table("soft_deletable_accounts")
    public static class SoftDeletableAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @SoftDelete
        @Column("deleted_at")
        private Instant deletedAt;

        public SoftDeletableAccount() {
        }

        public SoftDeletableAccount(Long id, String email, Instant deletedAt) {
            this.id = id;
            this.email = email;
            this.deletedAt = deletedAt;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Instant getDeletedAt() {
            return deletedAt;
        }
    }

    @Entity
    @Table("local_audited_accounts")
    public static class LocalDateTimeAuditedAccount {
        @Id
        private Long id;

        @UpdatedAt
        @Column("updated_at")
        private LocalDateTime updatedAt;

        public LocalDateTimeAuditedAccount() {
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }
    }

    @Entity
    @Table("soft_deletable_local")
    public static class SoftDeletableLocalAccount {
        @Id
        private Long id;

        @SoftDelete
        @Column("deleted_at")
        private LocalDateTime deletedAt;

        public SoftDeletableLocalAccount() {
        }

        public LocalDateTime getDeletedAt() {
            return deletedAt;
        }
    }

    @Entity
    @Table("offset_audited_accounts")
    public static class OffsetDateTimeAuditedAccount {
        @Id
        private Long id;

        @UpdatedAt
        @Column("updated_at")
        private OffsetDateTime updatedAt;

        public OffsetDateTimeAuditedAccount() {
        }

        public OffsetDateTime getUpdatedAt() {
            return updatedAt;
        }
    }

    @Entity
    @Table("versioned_soft_deletable_accounts")
    public static class VersionedSoftDeletableAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @Version
        private Long version;

        @SoftDelete
        @Column("deleted_at")
        private Instant deletedAt;

        public VersionedSoftDeletableAccount() {
        }

        public VersionedSoftDeletableAccount(Long id, String email, Long version, Instant deletedAt) {
            this.id = id;
            this.email = email;
            this.version = version;
            this.deletedAt = deletedAt;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Long getVersion() {
            return version;
        }

        public Instant getDeletedAt() {
            return deletedAt;
        }
    }

    @Entity
    @Table("soft_deletable_offset")
    public static class SoftDeletableOffsetAccount {
        @Id
        private Long id;

        @SoftDelete
        @Column("deleted_at")
        private OffsetDateTime deletedAt;

        public SoftDeletableOffsetAccount() {
        }

        public OffsetDateTime getDeletedAt() {
            return deletedAt;
        }
    }

    @Entity
    public static class UnsupportedAuditTypeEntity {
        @Id
        private Long id;

        @CreatedAt
        private Long createdAtEpoch;

        public UnsupportedAuditTypeEntity() {
        }
    }

    @Entity
    public static class DuplicateCreatedAtEntity {
        @Id
        private Long id;

        @CreatedAt
        private Instant first;

        @CreatedAt
        private Instant second;

        public DuplicateCreatedAtEntity() {
        }
    }

    @Entity
    public static class DuplicateSoftDeleteEntity {
        @Id
        private Long id;

        @SoftDelete
        private Instant deletedAt;

        @SoftDelete
        private Instant removedAt;
    }

    @Entity
    public static class UnsupportedSoftDeleteTypeEntity {
        @Id
        private Long id;

        @SoftDelete
        private String deletedAt;
    }

    @Entity
    public static class SoftDeleteOnIdEntity {
        @Id
        @SoftDelete
        private Instant id;
    }

    @Entity
    @Table("sequenced_accounts")
    public static class SequencedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenced_accounts_seq")
        private Long id;

        @Column("email_address")
        private String email;

        public SequencedAccount() {
        }

        public SequencedAccount(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }
    }

    @Entity
    @Table("uuid_accounts")
    public static class UuidAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column("email_address")
        private String email;

        public UuidAccount() {
        }

        public UuidAccount(UUID id, String email) {
            this.id = id;
            this.email = email;
        }

        public UUID getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }
    }

    @Entity
    @Table("string_uuid_accounts")
    public static class StringUuidAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private String id;

        @Column("email_address")
        private String email;

        public StringUuidAccount() {
        }

        public StringUuidAccount(String id, String email) {
            this.id = id;
            this.email = email;
        }

        public String getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }
    }

    @Entity
    public static class InvalidUuidTypeEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private Long id;

        public InvalidUuidTypeEntity() {
        }
    }

    @Entity
    public static class MissingSequenceGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        private Long id;

        public MissingSequenceGeneratorEntity() {
        }
    }

    @Entity
    public static class InjectingSequenceGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq'); drop table accounts; --")
        private Long id;

        public InjectingSequenceGeneratorEntity() {
        }
    }

    @Entity
    public static class SemicolonSequenceGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq; --")
        private Long id;

        public SemicolonSequenceGeneratorEntity() {
        }
    }

    @Entity
    public static class WhitespaceSequenceGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "two words")
        private Long id;

        public WhitespaceSequenceGeneratorEntity() {
        }
    }

    @Entity
    public static class HyphenSequenceGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq-name")
        private Long id;

        public HyphenSequenceGeneratorEntity() {
        }
    }

    @Entity
    public static class LeadingDigitSequenceGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "1seq")
        private Long id;

        public LeadingDigitSequenceGeneratorEntity() {
        }
    }
}
