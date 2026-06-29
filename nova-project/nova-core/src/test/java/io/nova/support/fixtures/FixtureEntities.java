package io.nova.support.fixtures;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import io.nova.annotation.CreatedAt;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import io.nova.annotation.Json;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import io.nova.annotation.SoftDelete;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import io.nova.annotation.UpdatedAt;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

public final class FixtureEntities {
    private FixtureEntities() {
    }

    @Entity(name = "account_entity")
    @Table(name = "accounts")
    public static class SampleAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "email_address")
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
    @Table(name = "orders")
    public static class SampleOrder {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "customer_email")
        private String customerEmail;

        @Column(name = "total_cents")
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
    @Table(name = "assigned_accounts")
    public static class AssignedIdAccount {
        @Id
        private Long id;

        @Column(name = "email_address")
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
     * 레거시 JPA {@code @Temporal} 매핑 픽스처다. {@code java.util.Date}를 DATE/TIME/TIMESTAMP로,
     * {@code java.util.Calendar}를 TIMESTAMP로 각각 매핑해 컬럼 타입 결정과 H2 round-trip을 검증한다.
     */
    @Entity
    @Table(name = "temporal_event")
    public static class TemporalEvent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Temporal(TemporalType.DATE)
        @Column(name = "event_date")
        private Date eventDate;

        @Temporal(TemporalType.TIME)
        @Column(name = "event_time")
        private Date eventTime;

        @Temporal(TemporalType.TIMESTAMP)
        @Column(name = "event_timestamp")
        private Date eventTimestamp;

        @Temporal(TemporalType.TIMESTAMP)
        @Column(name = "scheduled_at")
        private Calendar scheduledAt;

        public TemporalEvent() {
        }

        public Long getId() {
            return id;
        }

        public Date getEventDate() {
            return eventDate;
        }

        public void setEventDate(Date eventDate) {
            this.eventDate = eventDate;
        }

        public Date getEventTime() {
            return eventTime;
        }

        public void setEventTime(Date eventTime) {
            this.eventTime = eventTime;
        }

        public Date getEventTimestamp() {
            return eventTimestamp;
        }

        public void setEventTimestamp(Date eventTimestamp) {
            this.eventTimestamp = eventTimestamp;
        }

        public Calendar getScheduledAt() {
            return scheduledAt;
        }

        public void setScheduledAt(Calendar scheduledAt) {
            this.scheduledAt = scheduledAt;
        }
    }

    /** {@code @Temporal}을 java.time 타입에 잘못 단 거부 케이스 픽스처. */
    @Entity
    public static class TemporalOnJavaTimeEntity {
        @Id
        private Long id;

        @Temporal(TemporalType.TIMESTAMP)
        private LocalDateTime when;

        public TemporalOnJavaTimeEntity() {
        }
    }

    /** {@code @Temporal} 누락(java.util.Date) 거부 케이스 픽스처. */
    @Entity
    public static class MissingTemporalEntity {
        @Id
        private Long id;

        private Date createdOn;

        public MissingTemporalEntity() {
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
    @Table(name = "column_typed")
    public static class ColumnTypedEntity {
        @Id
        private Long id;

        @Column(name = "short_name", length = 64)
        private String shortName;

        @Column(name = "description")
        private String description;

        @Column(name = "price", precision = 12, scale = 2)
        private java.math.BigDecimal price;

        @Column(name = "default_decimal")
        private java.math.BigDecimal defaultDecimal;

        // scale을 생략하면 numeric(precision, 0)으로 emit되어 소수부가 잘린다(JPA @Column 관례와 동일).
        // 통화 컬럼에서 흔한 실수이므로 이 동작을 테스트로 명시 고정한다.
        @Column(name = "precision_only", precision = 10)
        private java.math.BigDecimal precisionOnly;

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
    @Table(name = "versioned_accounts")
    public static class VersionedAccount {
        @Id
        private Long id;

        @Column(name = "email_address")
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
    @Table(name = "generated_versioned_accounts")
    public static class GeneratedVersionedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "email_address")
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
    @Table(name = "int_versioned_accounts")
    public static class IntegerVersionedAccount {
        @Id
        private Long id;

        @Column(name = "email_address")
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
    @Table(name = "short_versioned_accounts")
    public static class ShortVersionedAccount {
        @Id
        private Long id;

        @Column(name = "email_address")
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
    @Table(name = "enum_string_accounts")
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
    @Table(name = "enum_ordinal_accounts")
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
    @Table(name = "enum_default_accounts")
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
    @Table(name = "audited_accounts")
    public static class AuditedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "email_address")
        private String email;

        @CreatedAt
        @Column(name = "created_at")
        private Instant createdAt;

        @UpdatedAt
        @Column(name = "updated_at")
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
    @Table(name = "soft_deletable_accounts")
    public static class SoftDeletableAccount {
        @Id
        private Long id;

        @Column(name = "email_address")
        private String email;

        @SoftDelete
        @Column(name = "deleted_at")
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
    @Table(name = "local_audited_accounts")
    public static class LocalDateTimeAuditedAccount {
        @Id
        private Long id;

        @UpdatedAt
        @Column(name = "updated_at")
        private LocalDateTime updatedAt;

        public LocalDateTimeAuditedAccount() {
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }
    }

    @Entity
    @Table(name = "soft_deletable_local")
    public static class SoftDeletableLocalAccount {
        @Id
        private Long id;

        @SoftDelete
        @Column(name = "deleted_at")
        private LocalDateTime deletedAt;

        public SoftDeletableLocalAccount() {
        }

        public LocalDateTime getDeletedAt() {
            return deletedAt;
        }
    }

    @Entity
    @Table(name = "offset_audited_accounts")
    public static class OffsetDateTimeAuditedAccount {
        @Id
        private Long id;

        @UpdatedAt
        @Column(name = "updated_at")
        private OffsetDateTime updatedAt;

        public OffsetDateTimeAuditedAccount() {
        }

        public OffsetDateTime getUpdatedAt() {
            return updatedAt;
        }
    }

    @Entity
    @Table(name = "versioned_soft_deletable_accounts")
    public static class VersionedSoftDeletableAccount {
        @Id
        private Long id;

        @Column(name = "email_address")
        private String email;

        @Version
        private Long version;

        @SoftDelete
        @Column(name = "deleted_at")
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
    @Table(name = "soft_deletable_offset")
    public static class SoftDeletableOffsetAccount {
        @Id
        private Long id;

        @SoftDelete
        @Column(name = "deleted_at")
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
    @Table(name = "sequenced_accounts")
    public static class SequencedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenced_accounts_seq")
        private Long id;

        @Column(name = "email_address")
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
    @Table(name = "uuid_accounts")
    public static class UuidAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "email_address")
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
    @Table(name = "string_uuid_accounts")
    public static class StringUuidAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private String id;

        @Column(name = "email_address")
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
    @Table(name = "callback_entities")
    public static class EntityWithCallbacks {
        public static final java.util.concurrent.atomic.AtomicInteger prePersistCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger postPersistCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger preUpdateCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger postUpdateCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger postLoadCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger preRemoveCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public static final java.util.concurrent.atomic.AtomicInteger postRemoveCount =
                new java.util.concurrent.atomic.AtomicInteger();

        public static void reset() {
            prePersistCount.set(0);
            postPersistCount.set(0);
            preUpdateCount.set(0);
            postUpdateCount.set(0);
            postLoadCount.set(0);
            preRemoveCount.set(0);
            postRemoveCount.set(0);
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "email_address")
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

        @PostPersist
        void onPostPersist() {
            postPersistCount.incrementAndGet();
        }

        @PreUpdate
        void onPreUpdate() {
            preUpdateCount.incrementAndGet();
            if (email != null) {
                email = email.toLowerCase();
            }
        }

        @PostUpdate
        void onPostUpdate() {
            postUpdateCount.incrementAndGet();
        }

        @PostLoad
        void onPostLoad() {
            postLoadCount.incrementAndGet();
        }

        @PreRemove
        void onPreRemove() {
            preRemoveCount.incrementAndGet();
        }

        @PostRemove
        void onPostRemove() {
            postRemoveCount.incrementAndGet();
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
    @Table(name = "indexed_accounts",
            indexes = @Index(name = "ix_indexed_email", columnList = "email"))
    public static class SingleIndexEntity {
        @Id
        private Long id;
        @Column(name = "email")
        private String email;
    }

    @Entity
    @Table(name = "multi_indexed_accounts",
            indexes = @Index(columnList = "first_name, last_name"))
    public static class AutoNamedIndexEntity {
        @Id
        private Long id;
        @Column(name = "first_name")
        private String firstName;
        @Column(name = "last_name")
        private String lastName;
    }

    @Entity
    @Table(name = "repeating_index_accounts",
            indexes = {
                    @Index(columnList = "email"),
                    @Index(columnList = "first_name, last_name")
            })
    public static class RepeatedIndexEntity {
        @Id
        private Long id;
        @Column(name = "email")
        private String email;
        @Column(name = "first_name")
        private String firstName;
        @Column(name = "last_name")
        private String lastName;
    }

    @Entity
    @Table(name = "unique_accounts",
            uniqueConstraints = @UniqueConstraint(name = "uk_email", columnNames = {"email"}))
    public static class SingleUniqueConstraintEntity {
        @Id
        private Long id;
        @Column(name = "email")
        private String email;
    }

    @Entity
    @Table(name = "composite_unique_accounts",
            uniqueConstraints = @UniqueConstraint(columnNames = {"first_name", "last_name"}))
    public static class AutoNamedUniqueConstraintEntity {
        @Id
        private Long id;
        @Column(name = "first_name")
        private String firstName;
        @Column(name = "last_name")
        private String lastName;
    }

    @Entity
    @Table(name = "repeating_unique_accounts",
            uniqueConstraints = {
                    @UniqueConstraint(columnNames = {"email"}),
                    @UniqueConstraint(columnNames = {"first_name", "last_name"})
            })
    public static class RepeatedUniqueConstraintEntity {
        @Id
        private Long id;
        @Column(name = "email")
        private String email;
        @Column(name = "first_name")
        private String firstName;
        @Column(name = "last_name")
        private String lastName;
    }

    @Entity
    @Table(name = "missing_column_indexed",
            indexes = @Index(columnList = "nonexistent"))
    public static class IndexWithUnknownColumnEntity {
        @Id
        private Long id;
        @Column(name = "email")
        private String email;
    }

    @Entity
    @Table(name = "missing_column_unique",
            uniqueConstraints = @UniqueConstraint(columnNames = {"nonexistent"}))
    public static class UniqueConstraintWithUnknownColumnEntity {
        @Id
        private Long id;
        @Column(name = "email")
        private String email;
    }

    @Entity
    @Table(name = "empty_columns_indexed",
            indexes = @Index(columnList = ""))
    public static class EmptyIndexColumnsEntity {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "empty_columns_unique",
            uniqueConstraints = @UniqueConstraint(columnNames = {}))
    public static class EmptyUniqueConstraintColumnsEntity {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "alter_target")
    public static class AlterTargetEntity {
        @Id
        private Long id;
        @Column(name = "email")
        private String email;
    }

    /**
     * 자동 생성된 이름이 63자 PostgreSQL identifier 한도를 초과해 hash fallback이 적용되는 경우.
     * 결과 fallback 이름은 한도 안에 들어와야 한다.
     */
    @Entity
    @Table(name = "medium_long_table_name_for_index_truncation_tests",
            indexes = @Index(columnList = "email_address"))
    public static class LongAutoNamedIndexEntity {
        @Id
        private Long id;
        @Column(name = "email_address")
        private String emailAddress;
    }

    /**
     * 자동 생성된 이름이 한도를 넘고, hash fallback 또한 한도를 초과해 truncate가 적용되는 경우.
     */
    @Entity
    @Table(name = "extremely_long_table_name_for_testing_identifier_limit_guard",
            uniqueConstraints = @UniqueConstraint(columnNames = {"email_address"}))
    public static class VeryLongAutoNamedUniqueConstraintEntity {
        @Id
        private Long id;
        @Column(name = "email_address")
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
    @Table(name = "overridden_address")
    public static class OverriddenAddressEntity {
        @Id
        private Long id;

        // city는 ship_city로 재정의되고, street/zip은 기본 합성 규칙(shipping_*)을 그대로 따른다.
        @Embedded
        @AttributeOverride(name = "city", column = @Column(name = "ship_city"))
        private Address shipping;

        public OverriddenAddressEntity() {
        }
    }

    @Entity
    @Table(name = "customer")
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
    @Table(name = "authors")
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
    @Table(name = "books")
    public static class Book {
        @Id
        private Long id;

        private String title;

        @Column(name = "author_id")
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
    @Table(name = "office")
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
    @Table(name = "annotated_authors")
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
    @Table(name = "annotated_books")
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
    @Table(name = "author_book_join_conflict")
    public static class AuthorBookJoinColumnConflict {
        @Id
        private Long id;

        @Column(name = "author_id")
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
    @Table(name = "json_accounts")
    public static class JsonAccount {
        @Id
        private Long id;

        @Column(name = "email_address")
        private String email;

        @Json
        @Column(name = "preferences")
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

    // --- fetch=LAZY는 no-op으로 수용되는 valid 엔티티 ---
    // Nova는 lazy proxy가 없어 EAGER/LAZY가 런타임 동일(관계는 FetchGroup으로만 populate, FK는 정상 persist).

    @Entity
    public static class ManyToOneLazyEntity {
        @Id
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY)
        private SampleAccount account;

        public ManyToOneLazyEntity() {
        }
    }

    // --- JPA 속성 중 Nova가 honor하지 않아 fail-fast 거부되는 invalid 엔티티들 ---

    @Entity
    public static class ManyToOneCascadeEntity {
        @Id
        private Long id;

        @ManyToOne(cascade = CascadeType.PERSIST)
        private SampleAccount account;

        public ManyToOneCascadeEntity() {
        }
    }

    @Entity
    public static class OneToManyOrphanRemovalEntity {
        @Id
        private Long id;

        @OneToMany(mappedBy = "parent", targetEntity = SampleAccount.class, orphanRemoval = true)
        private java.util.List<SampleAccount> children;

        public OneToManyOrphanRemovalEntity() {
        }
    }

    /**
     * {@code @OneToMany(cascade = CascadeType.ALL)} metadata 추출 검증용 fixture. ALL은 persist/remove/merge를
     * 모두 켠다. child 측 {@code @ManyToOne} property 이름은 {@code parent}로 mappedBy와 짝을 이룬다.
     */
    @Entity
    public static class OneToManyCascadeAllEntity {
        @Id
        private Long id;

        @OneToMany(mappedBy = "parent", targetEntity = SampleAccount.class, cascade = CascadeType.ALL)
        private java.util.List<SampleAccount> children;

        public OneToManyCascadeAllEntity() {
        }
    }

    @Entity
    public static class ColumnInsertableFalseEntity {
        @Id
        private Long id;

        @Column(insertable = false)
        private String name;

        public ColumnInsertableFalseEntity() {
        }
    }

    @Entity
    public static class ColumnUniqueEntity {
        @Id
        private Long id;

        @Column(unique = true)
        private String email;

        public ColumnUniqueEntity() {
        }
    }

    @Entity
    public static class ColumnUpdatableFalseEntity {
        @Id
        private Long id;

        @Column(updatable = false)
        private String name;

        public ColumnUpdatableFalseEntity() {
        }
    }

    @Entity
    public static class ColumnDefinitionEntity {
        @Id
        private Long id;

        @Column(columnDefinition = "text")
        private String note;

        public ColumnDefinitionEntity() {
        }
    }

    /**
     * {@code @GeneratedValue(TABLE)}만 선언하고 {@code @TableGenerator}는 생략한 엔티티. generator 테이블/컬럼은
     * Nova 기본값(nova_sequences / sequence_name / next_val)을, pkColumnValue는 필드 이름("id")을 쓴다.
     */
    @Entity
    @Table(name = "table_gen_default_accounts")
    public static class GeneratedValueTableEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE)
        private Long id;

        @Column(name = "email")
        private String email;

        public GeneratedValueTableEntity() {
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    /**
     * {@code @GeneratedValue(TABLE, generator="acct_gen")}이 같은 필드의 {@code @TableGenerator}를 가리키는
     * 엔티티. 모든 속성(table, pkColumnName, valueColumnName, pkColumnValue, initialValue, allocationSize)을
     * 명시적으로 지정한다.
     */
    @Entity
    @Table(name = "table_gen_explicit_accounts")
    public static class ExplicitTableGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE, generator = "acct_gen")
        @TableGenerator(
                name = "acct_gen",
                table = "id_generators",
                pkColumnName = "gen_name",
                valueColumnName = "gen_value",
                pkColumnValue = "account_id",
                initialValue = 100,
                allocationSize = 5)
        private Long id;

        @Column(name = "email")
        private String email;

        public ExplicitTableGeneratorEntity() {
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    /**
     * {@code @GeneratedValue(TABLE)}을 지원하지 않는 식별자 타입(String)에 단 엔티티. fail-fast 검증용.
     */
    @Entity
    public static class InvalidTableGeneratorIdTypeEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE)
        private String id;

        public InvalidTableGeneratorIdTypeEntity() {
        }
    }

    @Entity
    @Table(name = "transient_accounts")
    public static class TransientFieldEntity {
        @Id
        private Long id;

        @Column(name = "email")
        private String email;

        // JPA @Transient: 매핑에서 제외되어야 한다.
        @Transient
        private String cachedDisplay;

        public TransientFieldEntity() {
        }
    }

    /**
     * {@code @MappedSuperclass} 상속 매핑 검증용. id/createdAt를 base에서 상속받고 email은 자신이 선언한다.
     */
    @MappedSuperclass
    public abstract static class BaseAuditEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "created_at")
        private Instant createdAt;

        public Long getId() {
            return id;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }
    }

    @Entity
    @Table(name = "mapped_sub")
    public static class MappedSubEntity extends BaseAuditEntity {
        @Column(name = "email")
        private String email;

        public MappedSubEntity() {
        }

        public String getEmail() {
            return email;
        }
    }

    @Entity
    @Table(name = "lob_docs")
    public static class LobEntity {
        @Id
        private Long id;

        @Lob
        @Column(name = "content")
        private String content;

        @Lob
        @Column(name = "data")
        private byte[] data;

        public LobEntity() {
        }
    }

    @Entity
    @Table(name = "join_col_attrs")
    public static class JoinColumnAttributesEntity {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_id", insertable = false, unique = true)
        private SampleAccount owner;

        public JoinColumnAttributesEntity() {
        }
    }

    @Entity
    @Table(name = "seq_gen_accounts")
    @SequenceGenerator(name = "user_gen", sequenceName = "user_seq")
    public static class NamedSequenceGeneratorEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_gen")
        private Long id;

        public NamedSequenceGeneratorEntity() {
        }
    }

    @Entity
    @Table(name = "accounts", schema = "app")
    public static class SchemaQualifiedEntity {
        @Id
        private Long id;

        @Column(name = "email")
        private String email;

        public SchemaQualifiedEntity() {
        }
    }

    /**
     * SINGLE_TABLE 상속 루트 — 기본 discriminator 컬럼(dtype), STRING 타입.
     */
    @Entity
    @Table(name = "vehicles")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    public abstract static class Vehicle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        public Vehicle() {
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @DiscriminatorValue("CAR")
    public static class Car extends Vehicle {
        private int doors;

        public Car() {
        }

        public int getDoors() {
            return doors;
        }

        public void setDoors(int doors) {
            this.doors = doors;
        }
    }

    @Entity
    @DiscriminatorValue("TRUCK")
    public static class Truck extends Vehicle {
        private double payload;

        public Truck() {
        }

        public double getPayload() {
            return payload;
        }

        public void setPayload(double payload) {
            this.payload = payload;
        }
    }

    /**
     * 외부 {@code @EntityListeners} 리스너 클래스. 콜백은 entity를 단일 인자로 받고, 호출 이벤트를 정적
     * 리스트에 누적한다. {@code @PrePersist}는 entity 자체 콜백보다 먼저 발화하는지(리스너 우선) 검증용으로
     * audited 엔티티의 필드를 mutate한다. 테스트는 {@link #reset()}으로 이벤트를 비운다.
     */
    public static class AuditingListener {
        public static final java.util.List<String> events = new java.util.ArrayList<>();

        public static void reset() {
            events.clear();
        }

        @PrePersist
        public void onPrePersist(Object entity) {
            events.add("listener:prePersist");
            if (entity instanceof AuditedEntity audited) {
                audited.audit = "listener-was-here";
            }
        }

        @PostPersist
        public void onPostPersist(Object entity) {
            events.add("listener:postPersist");
        }

        @PreUpdate
        public void onPreUpdate(Object entity) {
            events.add("listener:preUpdate");
        }

        @PostUpdate
        public void onPostUpdate(Object entity) {
            events.add("listener:postUpdate");
        }

        @PostLoad
        public void onPostLoad(Object entity) {
            events.add("listener:postLoad");
        }

        @PreRemove
        public void onPreRemove(Object entity) {
            events.add("listener:preRemove");
        }

        @PostRemove
        public void onPostRemove(Object entity) {
            events.add("listener:postRemove");
        }
    }

    /**
     * 두 번째 리스너. 여러 {@code @EntityListeners}가 선언 순서대로 발화하는지 검증용.
     */
    public static class SecondListener {
        @PrePersist
        public void onPrePersist(AuditedEntity entity) {
            AuditingListener.events.add("second:prePersist");
        }
    }

    /**
     * {@code @EntityListeners}로 외부 리스너를 등록한 entity. 자체 {@code @PrePersist} 콜백도 가지며,
     * 리스너 콜백이 자체 콜백보다 먼저 발화하는지 검증한다.
     */
    @Entity
    @Table(name = "audited_entities")
    @EntityListeners({AuditingListener.class, SecondListener.class})
    public static class AuditedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "audit")
        String audit;

        public AuditedEntity() {
        }

        public AuditedEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        @PrePersist
        void onPrePersist() {
            AuditingListener.events.add("entity:prePersist");
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

    /**
     * 잘못된 리스너 콜백(인자 0개) — metadata 빌드 시 fail-fast 검증용.
     */
    public static class BadArityListener {
        @PrePersist
        public void onPrePersist() {
        }
    }

    @Entity
    @Table(name = "bad_listener_entities")
    @EntityListeners(BadArityListener.class)
    public static class EntityWithBadListener {
        @Id
        private Long id;

        public EntityWithBadListener() {
        }
    }
}
