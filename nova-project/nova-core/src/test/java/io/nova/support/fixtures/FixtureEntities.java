package io.nova.support.fixtures;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;

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

    public enum Status {
        ACTIVE,
        INACTIVE
    }
}
