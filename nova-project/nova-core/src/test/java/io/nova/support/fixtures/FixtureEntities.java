package io.nova.support.fixtures;

import io.nova.annotation.Column;
import io.nova.annotation.CreatedAt;
import io.nova.annotation.Embeddable;
import io.nova.annotation.Embedded;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Index;
import io.nova.annotation.PostLoad;
import io.nova.annotation.PrePersist;
import io.nova.annotation.PreRemove;
import io.nova.annotation.PreUpdate;
import io.nova.annotation.SoftDelete;
import io.nova.annotation.Table;
import io.nova.annotation.UniqueConstraint;
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

    /**
     * lifecycle callback fixture: 각 phase의 호출 횟수를 static counter로 증가시키고,
     * @PrePersist/@PreUpdate에서는 email 필드를 mutate해 binding에 반영되는지 검증할 수 있게 한다.
     * 카운터/이벤트는 정적 상태이므로 테스트는 {@link #reset()}로 초기화한 뒤 사용한다.
     */
    @Entity
    @Table("callback_entities")
    public static class EntityWithCallbacks {
        public static final java.util.concurrent.atomic.AtomicInteger prePersistCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger preUpdateCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger postLoadCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger preRemoveCount =
                new java.util.concurrent.atomic.AtomicInteger();

        public static void reset() {
            prePersistCount.set(0);
            preUpdateCount.set(0);
            postLoadCount.set(0);
            preRemoveCount.set(0);
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email_address")
        private String email;

        public EntityWithCallbacks() {
        }

        public EntityWithCallbacks(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        @PrePersist
        void onPrePersist() {
            prePersistCount.incrementAndGet();
            if (email != null) {
                email = email.trim();
            }
        }

        @PreUpdate
        void onPreUpdate() {
            preUpdateCount.incrementAndGet();
            if (email != null) {
                email = email.toLowerCase();
            }
        }

        @PostLoad
        void onPostLoad() {
            postLoadCount.incrementAndGet();
        }

        @PreRemove
        void onPreRemove() {
            preRemoveCount.incrementAndGet();
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }
    }

    /**
     * declaration 순서 보존 검증용. 두 개의 @PrePersist 메서드가 순서대로 호출되어야 한다.
     * 호출 이벤트는 정적 리스트에 누적되며 테스트는 {@link #reset()}으로 비운다.
     */
    @Entity
    public static class MultipleCallbacksEntity {
        public static final java.util.List<String> events = new java.util.ArrayList<>();

        public static void reset() {
            events.clear();
        }

        @Id
        private Long id;

        public MultipleCallbacksEntity() {
        }

        @PrePersist
        void first() {
            events.add("first");
        }

        @PrePersist
        void second() {
            events.add("second");
        }
    }

    @Entity
    public static class CallbackThrowingEntity {
        @Id
        private Long id;

        public CallbackThrowingEntity() {
        }

        public CallbackThrowingEntity(Long id) {
            this.id = id;
        }

        @PrePersist
        void boom() {
            throw new IllegalArgumentException("callback boom");
        }
    }

    @Entity
    public static class StaticCallbackEntity {
        @Id
        private Long id;

        @PrePersist
        static void illegalStatic() {
        }
    }

    @Entity
    public static class ArgCallbackEntity {
        @Id
        private Long id;

        @PreUpdate
        void illegalArg(String value) {
        }
    }

    @Entity
    public static class ReturningCallbackEntity {
        @Id
        private Long id;

        @PostLoad
        String illegalReturn() {
            return "no";
        }
    }

    @Entity
    @Table("indexed_accounts")
    @Index(name = "ix_indexed_email", columns = {"email"})
    public static class SingleIndexEntity {
        @Id
        private Long id;
        @Column("email")
        private String email;
    }

    @Entity
    @Table("multi_indexed_accounts")
    @Index(columns = {"first_name", "last_name"})
    public static class AutoNamedIndexEntity {
        @Id
        private Long id;
        @Column("first_name")
        private String firstName;
        @Column("last_name")
        private String lastName;
    }

    @Entity
    @Table("repeating_index_accounts")
    @Index(columns = {"email"})
    @Index(columns = {"first_name", "last_name"})
    public static class RepeatedIndexEntity {
        @Id
        private Long id;
        @Column("email")
        private String email;
        @Column("first_name")
        private String firstName;
        @Column("last_name")
        private String lastName;
    }

    @Entity
    @Table("unique_accounts")
    @UniqueConstraint(name = "uk_email", columns = {"email"})
    public static class SingleUniqueConstraintEntity {
        @Id
        private Long id;
        @Column("email")
        private String email;
    }

    @Entity
    @Table("composite_unique_accounts")
    @UniqueConstraint(columns = {"first_name", "last_name"})
    public static class AutoNamedUniqueConstraintEntity {
        @Id
        private Long id;
        @Column("first_name")
        private String firstName;
        @Column("last_name")
        private String lastName;
    }

    @Entity
    @Table("repeating_unique_accounts")
    @UniqueConstraint(columns = {"email"})
    @UniqueConstraint(columns = {"first_name", "last_name"})
    public static class RepeatedUniqueConstraintEntity {
        @Id
        private Long id;
        @Column("email")
        private String email;
        @Column("first_name")
        private String firstName;
        @Column("last_name")
        private String lastName;
    }

    @Entity
    @Table("missing_column_indexed")
    @Index(columns = {"nonexistent"})
    public static class IndexWithUnknownColumnEntity {
        @Id
        private Long id;
        @Column("email")
        private String email;
    }

    @Entity
    @Table("missing_column_unique")
    @UniqueConstraint(columns = {"nonexistent"})
    public static class UniqueConstraintWithUnknownColumnEntity {
        @Id
        private Long id;
        @Column("email")
        private String email;
    }

    @Entity
    @Table("empty_columns_indexed")
    @Index(columns = {})
    public static class EmptyIndexColumnsEntity {
        @Id
        private Long id;
    }

    @Entity
    @Table("empty_columns_unique")
    @UniqueConstraint(columns = {})
    public static class EmptyUniqueConstraintColumnsEntity {
        @Id
        private Long id;
    }

    @Entity
    @Table("alter_target")
    public static class AlterTargetEntity {
        @Id
        private Long id;
        @Column("email")
        private String email;
    }

    /**
     * 자동 생성된 이름이 63자 PostgreSQL identifier 한도를 초과해 hash fallback이 적용되는 경우.
     * 결과 fallback 이름은 한도 안에 들어와야 한다.
     */
    @Entity
    @Table("medium_long_table_name_for_index_truncation_tests")
    @Index(columns = {"email_address"})
    public static class LongAutoNamedIndexEntity {
        @Id
        private Long id;
        @Column("email_address")
        private String emailAddress;
    }

    /**
     * 자동 생성된 이름이 한도를 넘고, hash fallback 또한 한도를 초과해 truncate가 적용되는 경우.
     */
    @Entity
    @Table("extremely_long_table_name_for_testing_identifier_limit_guard")
    @UniqueConstraint(columns = {"email_address"})
    public static class VeryLongAutoNamedUniqueConstraintEntity {
        @Id
        private Long id;
        @Column("email_address")
        private String emailAddress;
    }

    @Embeddable
    public static class Address {
        private String city;
        private String street;
        private String zip;

        public Address() {
        }

        public Address(String city, String street, String zip) {
            this.city = city;
            this.street = street;
            this.zip = zip;
        }

        public String getCity() {
            return city;
        }

        public String getStreet() {
            return street;
        }

        public String getZip() {
            return zip;
        }
    }

    @Entity
    @Table("customer")
    public static class Customer {
        @Id
        private Long id;

        private String name;

        @Embedded
        private Address shipping;

        public Customer() {
        }

        public Customer(Long id, String name, Address shipping) {
            this.id = id;
            this.name = name;
            this.shipping = shipping;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Address getShipping() {
            return shipping;
        }
    }

    @Embeddable
    public static class AddressWithId {
        @Id
        private Long id;

        private String city;

        public AddressWithId() {
        }
    }

    @Entity
    public static class CustomerWithEmbeddableIdEntity {
        @Id
        private Long id;

        @Embedded
        private AddressWithId shipping;

        public CustomerWithEmbeddableIdEntity() {
        }
    }

    @Embeddable
    public static class AddressWithIdSubField {
        @Id
        private String city;

        public AddressWithIdSubField() {
        }
    }

    @Embeddable
    public static class AddressWithVersionSubField {
        @Version
        private Long version;

        public AddressWithVersionSubField() {
        }
    }

    @Embeddable
    public static class AddressWithSoftDeleteSubField {
        @SoftDelete
        private Instant deletedAt;

        public AddressWithSoftDeleteSubField() {
        }
    }

    @Embeddable
    public static class AddressWithCreatedAtSubField {
        @CreatedAt
        private Instant createdAt;

        public AddressWithCreatedAtSubField() {
        }
    }

    @Embeddable
    public static class AddressWithUpdatedAtSubField {
        @UpdatedAt
        private Instant updatedAt;

        public AddressWithUpdatedAtSubField() {
        }
    }

    @Embeddable
    public static class NestedEmbeddedAddress {
        @Embedded
        private Address inner;

        public NestedEmbeddedAddress() {
        }
    }

    public static class NotAnEmbeddable {
        private String value;

        public NotAnEmbeddable() {
        }
    }

    @Entity
    public static class CustomerWithEmbeddedVersionSubField {
        @Id
        private Long id;

        @Embedded
        private AddressWithVersionSubField shipping;

        public CustomerWithEmbeddedVersionSubField() {
        }
    }

    @Entity
    public static class CustomerWithEmbeddedSoftDeleteSubField {
        @Id
        private Long id;

        @Embedded
        private AddressWithSoftDeleteSubField shipping;

        public CustomerWithEmbeddedSoftDeleteSubField() {
        }
    }

    @Entity
    public static class CustomerWithEmbeddedCreatedAtSubField {
        @Id
        private Long id;

        @Embedded
        private AddressWithCreatedAtSubField shipping;

        public CustomerWithEmbeddedCreatedAtSubField() {
        }
    }

    @Entity
    public static class CustomerWithEmbeddedUpdatedAtSubField {
        @Id
        private Long id;

        @Embedded
        private AddressWithUpdatedAtSubField shipping;

        public CustomerWithEmbeddedUpdatedAtSubField() {
        }
    }

    @Entity
    public static class CustomerWithEmbeddedIdSubField {
        @Id
        private Long id;

        @Embedded
        private AddressWithIdSubField shipping;

        public CustomerWithEmbeddedIdSubField() {
        }
    }

    @Entity
    public static class CustomerWithNestedEmbedded {
        @Id
        private Long id;

        @Embedded
        private NestedEmbeddedAddress shipping;

        public CustomerWithNestedEmbedded() {
        }
    }

    @Entity
    public static class CustomerWithNonEmbeddable {
        @Id
        private Long id;

        @Embedded
        private NotAnEmbeddable shipping;

        public CustomerWithNonEmbeddable() {
        }
    }
}
