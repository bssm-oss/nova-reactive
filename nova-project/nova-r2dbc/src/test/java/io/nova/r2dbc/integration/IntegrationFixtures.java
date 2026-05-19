package io.nova.r2dbc.integration;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.SoftDelete;
import io.nova.annotation.Table;
import io.nova.annotation.Version;

import java.time.Instant;

/**
 * H2 in-memory 통합 테스트에서 공용으로 사용하는 entity 정의 모음이다.
 *
 * <p>각 entity는 단일 테이블만 사용하며, H2가 그대로 받아들이는 컬럼 타입만 사용한다. 컬럼명은
 * snake_case로 명시해 dialect의 quoting과 충돌하지 않도록 한다.
 */
final class IntegrationFixtures {
    private IntegrationFixtures() {
    }

    /**
     * IDENTITY id 라운드트립 검증용 entity. id는 H2 {@code generated always as identity}로 생성되며
     * R2DBC {@code Statement.returnGeneratedValues(...)} 경로로 회수된다.
     *
     * <p>{@code active}는 boxed {@link Boolean}로 선언한다 — primitive {@code boolean.class}를
     * {@code row.get(name, boolean.class)}로 그대로 전달하면 r2dbc-h2 driver가
     * {@code Cannot decode value of type boolean}으로 거부한다. 이 호환성 격차는 별도 회귀
     * 테스트가 보호한다 ({@link H2PrimitiveBooleanDecodingIntegrationTest} 참고).
     */
    @Entity
    @Table("identity_accounts")
    static class IdentityAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email_address")
        private String email;

        @Column(nullable = false)
        private Boolean active;

        IdentityAccount() {
        }

        IdentityAccount(String email, Boolean active) {
            this.email = email;
            this.active = active;
        }

        Long getId() {
            return id;
        }

        String getEmail() {
            return email;
        }

        Boolean isActive() {
            return active;
        }
    }

    /**
     * Soft delete 라운드트립 검증용 entity. {@code deleted_at}이 NULL이면 살아있다는 가드 규약을 따른다.
     */
    @Entity
    @Table("soft_delete_accounts")
    static class SoftDeleteAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @SoftDelete
        @Column("deleted_at")
        private Instant deletedAt;

        SoftDeleteAccount() {
        }

        SoftDeleteAccount(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        Long getId() {
            return id;
        }

        String getEmail() {
            return email;
        }

        Instant getDeletedAt() {
            return deletedAt;
        }
    }

    /**
     * Pessimistic lock(FOR UPDATE) 검증용 entity. assigned id를 사용해 두 connection이 동일 row를
     * 지정해 락을 시도할 수 있다.
     *
     * <p>{@code balanceCents}는 boxed {@link Long}으로 선언한다 — primitive {@code long.class}를
     * {@code row.get(name, long.class)}로 그대로 전달하면 r2dbc-h2 driver가
     * {@code Cannot decode value of type long}으로 거부한다. {@code H2PrimitiveBooleanDecodingIntegrationTest}
     * 참고.
     */
    @Entity
    @Table("locked_accounts")
    static class LockedAccount {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @Column("balance_cents")
        private Long balanceCents;

        LockedAccount() {
        }

        LockedAccount(Long id, String email, Long balanceCents) {
            this.id = id;
            this.email = email;
            this.balanceCents = balanceCents;
        }

        Long getId() {
            return id;
        }

        String getEmail() {
            return email;
        }

        Long getBalanceCents() {
            return balanceCents;
        }
    }

    /**
     * Optimistic locking 검증용 entity. {@code @Version}으로 동시 업데이트 충돌을 감지한다.
     * IDENTITY id를 사용해 {@code save()}의 INSERT 분기를 그대로 검증할 수 있게 한다 —
     * assigned id는 {@code isNew==false}로 분류되어 항상 UPDATE 경로로 들어가기 때문이다.
     */
    @Entity
    @Table("versioned_accounts")
    static class VersionedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email_address")
        private String email;

        @Version
        private Long version;

        VersionedAccount() {
        }

        VersionedAccount(Long id, String email, Long version) {
            this.id = id;
            this.email = email;
            this.version = version;
        }

        VersionedAccount(String email) {
            this.email = email;
        }

        Long getId() {
            return id;
        }

        void setEmail(String email) {
            this.email = email;
        }

        String getEmail() {
            return email;
        }

        Long getVersion() {
            return version;
        }
    }
}
