package io.nova.support.fixtures;

import io.nova.annotation.Column;
import io.nova.annotation.CreatedAt;
import io.nova.annotation.Embeddable;
import io.nova.annotation.Embedded;
import io.nova.annotation.Entity;
import io.nova.annotation.EnumType;
import io.nova.annotation.Enumerated;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Index;
import io.nova.annotation.JoinColumn;
import io.nova.annotation.Json;
import io.nova.annotation.ManyToOne;
import io.nova.annotation.OneToMany;
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

        // BigDecimal은 이제 numeric으로 지원되므로, schema generator가 거부하는 타입을 검증하기 위해
        // converter도 sqlType 분기도 없는 임의 타입(Locale)을 사용한다.
        private java.util.Locale locale;

        public UnsupportedTypeEntity() {
        }
    }

    /**
     * {@code @Column}의 length/precision/scale 매핑을 검증하기 위한 픽스처다. id는 명시적으로 할당하며
     * (IDENTITY 아님) bigint primary key가 된다.
     * <ul>
     *   <li>{@code shortName}은 {@code @Column(length=64)}로 {@code varchar(64)}</li>
     *   <li>{@code description}은 {@code @Column} length 미지정으로 기본 {@code varchar(255)}</li>
     *   <li>{@code price}는 {@code @Column(precision=12, scale=2)}로 {@code numeric(12, 2)}</li>
     *   <li>{@code defaultDecimal}은 {@code @Column} precision 미지정으로 기본 {@code numeric(19, 2)}</li>
     * </ul>
     */
    @Entity
    @Table("column_typed")
    public static class ColumnTypedEntity {
        @Id
        private Long id;

        @Column(value = "short_name", length = 64)
        private String shortName;

        @Column("description")
        private String description;

        @Column(value = "price", precision = 12, scale = 2)
        private java.math.BigDecimal price;

        @Column("default_decimal")
        private java.math.BigDecimal defaultDecimal;

        public ColumnTypedEntity() {
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
        INACTIVE,
        PENDING
    }

    @Entity
    @Table("enum_string_accounts")
    public static class EnumStringAccount {
        @Id
        private Long id;

        @Enumerated(EnumType.STRING)
        private Status status;

        public EnumStringAccount() {
        }

        public EnumStringAccount(Long id, Status status) {
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
    @Table("enum_ordinal_accounts")
    public static class EnumOrdinalAccount {
        @Id
        private Long id;

        @Enumerated(EnumType.ORDINAL)
        private Status status;

        public EnumOrdinalAccount() {
        }

        public EnumOrdinalAccount(Long id, Status status) {
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
    @Table("enum_default_accounts")
    public static class EnumDefaultAccount {
        @Id
        private Long id;

        @Enumerated
        private Status status;

        public EnumDefaultAccount() {
        }
    }

    @Entity
    public static class EnumOnNonEnumFieldEntity {
        @Id
        private Long id;

        @Enumerated(EnumType.STRING)
        private String status;

        public EnumOnNonEnumFieldEntity() {
        }
    }

    @Entity
    public static class EnumWithConverterEntity {
        @Id
        private Long id;

        @Enumerated(EnumType.STRING)
        private Status status;

        public EnumWithConverterEntity() {
        }
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
    public static class CustomerWithNonEmbeddable {
        @Id
        private Long id;

        @Embedded
        private NotAnEmbeddable shipping;

        public CustomerWithNonEmbeddable() {
        }
    }

    /**
     * FetchGroup fixture: parent 측. {@code books}는 {@code transient}로 선언해 컬럼 매핑에서 제외되며,
     * FetchGroup setter로만 채워진다.
     */
    @Entity
    @Table("authors")
    public static class Author {
        @Id
        private Long id;

        private String name;

        private transient java.util.List<Book> books;

        public Author() {
        }

        public Author(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public java.util.List<Book> getBooks() {
            return books;
        }

        public void setBooks(java.util.List<Book> books) {
            this.books = books;
        }
    }

    /**
     * FetchGroup fixture: child 측. {@code authorId}가 parent {@link Author#id}에 대한 FK 컬럼이다.
     */
    @Entity
    @Table("books")
    public static class Book {
        @Id
        private Long id;

        private String title;

        @Column("author_id")
        private Long authorId;

        public Book() {
        }

        public Book(Long id, String title, Long authorId) {
            this.id = id;
            this.title = title;
            this.authorId = authorId;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public Long getAuthorId() {
            return authorId;
        }
    }

    /**
     * H4 fixture: 가장 안쪽 leaf-only embeddable. {@link NestedAddress}가 이를 다시 nested @Embedded로 포함한다.
     */
    @Embeddable
    public static class Geo {
        private String country;
        private String city;

        public Geo() {
        }

        public Geo(String country, String city) {
            this.country = country;
            this.city = city;
        }

        public String getCountry() {
            return country;
        }

        public String getCity() {
            return city;
        }
    }

    /**
     * H4 fixture: 자체 leaf field와 nested @Embedded {@link Geo}를 함께 갖는 중간 embeddable.
     * {@link Office}가 이를 @Embedded로 갖는다.
     */
    @Embeddable
    public static class NestedAddress {
        private String street;
        private String zip;

        @Embedded
        private Geo geo;

        public NestedAddress() {
        }

        public NestedAddress(String street, String zip, Geo geo) {
            this.street = street;
            this.zip = zip;
            this.geo = geo;
        }

        public String getStreet() {
            return street;
        }

        public String getZip() {
            return zip;
        }

        public Geo getGeo() {
            return geo;
        }
    }

    /**
     * H4 fixture: 2-level nested @Embedded entity. 컬럼은 address_street/address_zip/address_geo_country/address_geo_city.
     */
    @Entity
    @Table("office")
    public static class Office {
        @Id
        private Long id;

        private String name;

        @Embedded
        private NestedAddress address;

        public Office() {
        }

        public Office(Long id, String name, NestedAddress address) {
            this.id = id;
            this.name = name;
            this.address = address;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public NestedAddress getAddress() {
            return address;
        }
    }

    /**
     * H4 fixture: 순환 @Embedded 검출용. {@link CircularA}가 {@link CircularB}를 @Embedded로 갖고,
     * {@link CircularB}가 다시 {@link CircularA}를 @Embedded로 가져 무한 재귀가 발생한다.
     */
    @Embeddable
    public static class CircularA {
        @Embedded
        private CircularB b;

        public CircularA() {
        }
    }

    @Embeddable
    public static class CircularB {
        @Embedded
        private CircularA a;

        public CircularB() {
        }
    }

    @Entity
    public static class EntityWithCircularEmbedded {
        @Id
        private Long id;

        @Embedded
        private CircularA outer;

        public EntityWithCircularEmbedded() {
        }
    }

    /**
     * 어노테이션 기반 관계 fixture: parent 측. {@link BookWithAuthorAnnotated} child가 owning side에 @ManyToOne을
     * 선언하면 {@link AnnotationFetchGroupBuilderTest}/{@link io.nova.r2dbc.integration.AnnotationRelationIntegrationTest}
     * 등에서 자동 hydration 경로가 books에 child를 주입한다. 컬렉션 필드는 OneToMany inverse side이므로 부모
     * 테이블에 컬럼을 만들지 않는다.
     */
    @Entity
    @Table("annotated_authors")
    public static class AuthorWithBooksAnnotated {
        @Id
        private Long id;

        private String name;

        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "author")
        private java.util.List<BookWithAuthorAnnotated> books;

        public AuthorWithBooksAnnotated() {
        }

        public AuthorWithBooksAnnotated(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public java.util.List<BookWithAuthorAnnotated> getBooks() {
            return books;
        }

        public void setBooks(java.util.List<BookWithAuthorAnnotated> books) {
            this.books = books;
        }
    }

    /**
     * 어노테이션 기반 관계 fixture: child 측. @ManyToOne의 owning side로 author_id FK 컬럼을 통해 parent를 참조한다.
     * @JoinColumn은 명시 — 기본 naming도 같은 값을 만들어내지만, 명시 케이스를 함께 검증한다.
     */
    @Entity
    @Table("annotated_books")
    public static class BookWithAuthorAnnotated {
        @Id
        private Long id;

        private String title;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        @JoinColumn(name = "author_id")
        private AuthorWithBooksAnnotated author;

        public BookWithAuthorAnnotated() {
        }

        public BookWithAuthorAnnotated(Long id, String title, AuthorWithBooksAnnotated author) {
            this.id = id;
            this.title = title;
            this.author = author;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public AuthorWithBooksAnnotated getAuthor() {
            return author;
        }

        public void setAuthor(AuthorWithBooksAnnotated author) {
            this.author = author;
        }
    }

    /**
     * 어노테이션 기반 관계 fixture: @JoinColumn 이름이 일반 @Column 이름과 정면 충돌하는 entity. 같은 컬럼명을
     * 두 번 declare하면 EntityMetadataFactory의 column uniqueness 검증이 거부해야 한다 (silent dedupe 방지).
     */
    @Entity
    @Table("author_book_join_conflict")
    public static class AuthorBookJoinColumnConflict {
        @Id
        private Long id;

        @Column("author_id")
        private Long authorId;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        @JoinColumn(name = "author_id")
        private AuthorWithBooksAnnotated author;

        public AuthorBookJoinColumnConflict() {
        }
    }

    /**
     * {@code @Json} 컬럼 타입 검증과 라운드트립 테스트에 사용하는 entity. {@code prefs}는 단순한
     * value object 필드로, 등록된 {@link io.nova.json.JsonCodec}이 JSON 문자열로 직렬화한다.
     */
    @Entity
    @Table("json_accounts")
    public static class JsonAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @Json
        @Column("preferences")
        private Preferences prefs;

        public JsonAccount() {
        }

        public JsonAccount(Long id, String email, Preferences prefs) {
            this.id = id;
            this.email = email;
            this.prefs = prefs;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public Preferences getPrefs() {
            return prefs;
        }
    }

    /**
     * {@code @Json} 필드가 직렬화하는 작은 value object. JSON 라이브러리에 의존하지 않도록 테스트 codec이
     * 직접 직렬화/역직렬화한다.
     */
    public static final class Preferences {
        private String theme;
        private int fontSize;

        public Preferences() {
        }

        public Preferences(String theme, int fontSize) {
            this.theme = theme;
            this.fontSize = fontSize;
        }

        public String getTheme() {
            return theme;
        }

        public int getFontSize() {
            return fontSize;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Preferences that)) {
                return false;
            }
            return fontSize == that.fontSize && java.util.Objects.equals(theme, that.theme);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(theme, fontSize);
        }

        @Override
        public String toString() {
            return "Preferences{theme=" + theme + ", fontSize=" + fontSize + "}";
        }
    }

    /**
     * {@code @Json} + {@code @Enumerated}를 한 필드에 선언해 metadata 생성이 거부하는지 검증하는 invalid entity.
     */
    @Entity
    public static class JsonAndEnumeratedEntity {
        @Id
        private Long id;

        @Json
        @Enumerated(EnumType.STRING)
        private Status status;

        public JsonAndEnumeratedEntity() {
        }
    }

    /**
     * {@code @Json} + {@code @ManyToOne}을 한 필드에 선언해 metadata 생성이 거부하는지 검증하는 invalid entity.
     */
    @Entity
    public static class JsonAndRelationEntity {
        @Id
        private Long id;

        @Json
        @ManyToOne
        private SampleAccount account;

        public JsonAndRelationEntity() {
        }
    }

    /**
     * 등록된 user converter와 충돌하는 {@code @Json} 필드를 가진 invalid entity. {@code Status} 타입에 대해
     * converter를 등록한 factory에서 metadata 생성이 거부되는지 검증한다.
     */
    @Entity
    public static class JsonWithRegisteredConverterEntity {
        @Id
        private Long id;

        @Json
        private Status status;

        public JsonWithRegisteredConverterEntity() {
        }
    }
}
