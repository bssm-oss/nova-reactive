package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import io.nova.core.SqlExecutionListener;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.query.NativeQuery;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cycle-A 리뷰가 남긴 {@code @SecondaryTable} 경계 결함 보강을 H2 end-to-end로 검증한다.
 *
 * <ul>
 *   <li>M2: {@code @Version} stale 엔티티로 hard delete 시 낙관락 실패가 보조 행을 <em>건드리기 전에</em>
 *       발생해, 비트랜잭션(autocommit)에서 보조 행과 primary 행이 모두 보존되는지.</li>
 *   <li>minor: primary 컬럼만 바뀐 partial update가 보조 테이블에 불필요한 UPDATE를 발행하지 않는지.</li>
 *   <li>minor: 보조 컬럼만 바뀐 partial update가 보조 테이블을 갱신하면서 {@code @Version}을 bump/검증하는지.</li>
 *   <li>minor: 비관락(FOR UPDATE) + {@code @SecondaryTable} 조합이 fail-fast로 거부되는지.</li>
 * </ul>
 */
class SecondaryTableHardeningTest {
    private H2IntegrationTestSupport support;
    private RecordingSqlListener listener;

    @BeforeEach
    void setUp() {
        listener = new RecordingSqlListener();
        support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // vmember 테이블 + 보조 테이블(vmember_profile, vmember PK를 FK로 참조)을 생성한다.
        schema.create(VersionedMember.class).block();
    }

    private long secondaryRowCount(Object memberId) {
        String sql = "select count(*) as cnt from " + support.dialect().quote("vmember_profile")
                + " where " + support.dialect().quote("vmember_id") + " = " + memberId;
        return support.operations().queryNativeOne(NativeQuery.of(sql), row -> row.get("cnt", Long.class)).block();
    }

    private long primaryRowCount(Object memberId) {
        String sql = "select count(*) as cnt from " + support.dialect().quote("vmember")
                + " where " + support.dialect().quote("id") + " = " + memberId;
        return support.operations().queryNativeOne(NativeQuery.of(sql), row -> row.get("cnt", Long.class)).block();
    }

    private String secondaryBio(Object memberId) {
        String sql = "select " + support.dialect().quote("bio") + " as bio from "
                + support.dialect().quote("vmember_profile")
                + " where " + support.dialect().quote("vmember_id") + " = " + memberId;
        return support.operations().queryNativeOne(NativeQuery.of(sql), row -> row.get("bio", String.class)).block();
    }

    @Test
    void staleVersionDeletePreservesSecondaryRows() {
        VersionedMember ada = new VersionedMember("ada");
        ada.setBio("hacker");
        ada.setCity("london");
        Long id = support.operations().save(ada).map(VersionedMember::getId).block();
        assertEquals(1L, secondaryRowCount(id));

        // stale: version 0을 가진 분리 인스턴스.
        VersionedMember stale = support.operations().findById(VersionedMember.class, id).block();
        assertEquals(0L, stale.getVersion());

        // 다른 인스턴스가 같은 row를 먼저 갱신해 DB version을 1로 올린다.
        VersionedMember fresh = support.operations().findById(VersionedMember.class, id).block();
        fresh.setName("ada lovelace");
        support.operations().save(fresh).block();

        // stale(version 0)로 delete → 낙관락 실패. 보조 행을 건드리기 전에 선검증에서 멈춰야 한다.
        StepVerifier.create(support.operations().delete(stale))
                .expectError(OptimisticLockingFailureException.class)
                .verify();

        // M2 핵심: primary 행과 보조 행이 모두 보존된다(비트랜잭션 무변경 계약).
        assertEquals(1L, primaryRowCount(id));
        assertEquals(1L, secondaryRowCount(id));
        // 낙관락 실패 경로에서는 어떤 보조 테이블 DELETE도 발행되지 않았다.
        assertFalse(listener.executed("delete", "vmember_profile"),
                "stale-version delete는 보조 테이블 DELETE를 발행하지 않아야 한다");
    }

    @Test
    void validVersionedDeleteRemovesPrimaryAndSecondaryRows() {
        VersionedMember ada = new VersionedMember("ada");
        ada.setBio("hacker");
        Long id = support.operations().save(ada).map(VersionedMember::getId).block();
        assertEquals(1L, secondaryRowCount(id));

        VersionedMember loaded = support.operations().findById(VersionedMember.class, id).block();
        StepVerifier.create(support.operations().delete(loaded))
                .expectNext(1L)
                .verifyComplete();

        assertEquals(0L, primaryRowCount(id));
        assertEquals(0L, secondaryRowCount(id));
    }

    @Test
    void primaryOnlyPartialUpdateDoesNotWriteSecondaryTable() {
        VersionedMember ada = new VersionedMember("ada");
        ada.setBio("hacker");
        ada.setCity("london");
        Long id = support.operations().save(ada).map(VersionedMember::getId).block();

        listener.clear();
        VersionedMember loaded = support.operations().findById(VersionedMember.class, id).block();
        // 보조 컬럼을 in-memory로 바꿔도 primary 필드만 요청하면 보조 테이블은 갱신되지 않아야 한다.
        loaded.setName("ada lovelace");
        loaded.setBio("SHOULD-NOT-PERSIST");
        support.operations().update(loaded, List.of("name")).block();

        // 보조 테이블 UPDATE가 한 건도 발행되지 않았다.
        assertFalse(listener.executed("update", "vmember_profile"),
                "primary 컬럼만 바뀐 partial update는 보조 테이블을 갱신하지 않아야 한다");
        // 보조 행의 bio는 원래 값 그대로다(in-memory 변경이 새지 않았다).
        assertEquals("hacker", secondaryBio(id));

        StepVerifier.create(support.operations().findById(VersionedMember.class, id))
                .assertNext(member -> {
                    assertEquals("ada lovelace", member.getName());
                    assertEquals("hacker", member.getBio());
                    // primary 변경이므로 version은 bump된다.
                    assertEquals(1L, member.getVersion());
                })
                .verifyComplete();
    }

    @Test
    void secondaryOnlyPartialUpdateBumpsVersionAndWritesSecondaryTable() {
        VersionedMember ada = new VersionedMember("ada");
        ada.setBio("hacker");
        Long id = support.operations().save(ada).map(VersionedMember::getId).block();

        listener.clear();
        VersionedMember loaded = support.operations().findById(VersionedMember.class, id).block();
        loaded.setBio("first programmer");
        support.operations().update(loaded, List.of("bio")).block();

        // 보조 컬럼 변경은 보조 테이블 UPDATE를 발행한다("vmember_profile"은 보조 테이블에만 등장).
        assertTrue(listener.executed("update", "vmember_profile"),
                "보조 컬럼 변경은 보조 테이블을 갱신해야 한다");

        // @Version 보유 시 보조 컬럼만 바뀌어도 primary version-bump UPDATE가 함께 발행된다 — version이 0→1로
        // 증가한 사실이 primary UPDATE 발행을 증명한다(보조 테이블 UPDATE는 version을 건드리지 않는다).
        StepVerifier.create(support.operations().findById(VersionedMember.class, id))
                .assertNext(member -> {
                    assertEquals("first programmer", member.getBio());
                    assertEquals(1L, member.getVersion(), "secondary-only 변경도 version을 bump해야 한다");
                })
                .verifyComplete();
    }

    @Test
    void pessimisticLockWithSecondaryTableFailsFast() {
        VersionedMember ada = new VersionedMember("ada");
        support.operations().save(ada).block();

        QuerySpec locked = QuerySpec.empty().forUpdate();
        StepVerifier.create(support.operations().findAll(VersionedMember.class, locked))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }

    /**
     * 실행된 SQL 문장을 기록해 특정 키워드/테이블 조합의 발행 여부를 검사하는 테스트 listener.
     */
    private static final class RecordingSqlListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql().toLowerCase(Locale.ROOT));
        }

        void clear() {
            statements.clear();
        }

        boolean executed(String keyword, String table) {
            String kw = keyword.toLowerCase(Locale.ROOT);
            String tbl = table.toLowerCase(Locale.ROOT);
            return statements.stream().anyMatch(sql -> sql.contains(kw) && sql.contains(tbl));
        }
    }

    @Entity
    @Table(name = "vmember")
    @SecondaryTable(name = "vmember_profile",
            pkJoinColumns = @PrimaryKeyJoinColumn(name = "vmember_id"))
    public static class VersionedMember {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @Version
        private Long version;
        @Column(table = "vmember_profile")
        private String bio;
        @Column(table = "vmember_profile")
        private String city;

        public VersionedMember() {
        }

        public VersionedMember(String name) {
            this.name = name;
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

        public Long getVersion() {
            return version;
        }

        public String getBio() {
            return bio;
        }

        public void setBio(String bio) {
            this.bio = bio;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }
}
