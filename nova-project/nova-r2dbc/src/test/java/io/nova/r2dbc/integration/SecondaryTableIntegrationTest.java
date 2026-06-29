package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.query.NativeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @SecondaryTable}이 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 — primary + 보조
 * 테이블 DDL 생성, save 시 primary INSERT 후 보조 테이블 INSERT, findById의 LEFT JOIN hydration, update/delete의
 * 멀티테이블 전파. 보조 테이블에 실제 행이 들어가는지를 직접 count로 확인한다.
 */
class SecondaryTableIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // member 테이블 + 보조 테이블(member_profile)을 생성한다(보조 테이블은 member PK를 FK로 참조).
        schema.create(Member.class).block();
    }

    private long secondaryRowCount(Object memberId) {
        // DDL이 식별자를 quote하므로(H2는 unquoted를 대문자로 fold) 검증 쿼리도 dialect로 quote한다.
        String sql = "select count(*) as cnt from " + support.dialect().quote("member_profile")
                + " where " + support.dialect().quote("member_id") + " = " + memberId;
        return support.operations().queryNativeOne(
                        NativeQuery.of(sql), row -> row.get("cnt", Long.class))
                .block();
    }

    @Test
    void savesPrimaryAndSecondaryRowsAndHydrates() {
        Member ada = new Member("ada");
        ada.setBio("hacker");
        ada.setCity("london");
        Long id = support.operations().save(ada).map(Member::getId).block();

        // 보조 테이블에 같은 PK로 행이 정확히 1건 들어간다.
        assertEquals(1L, secondaryRowCount(id));

        StepVerifier.create(support.operations().findById(Member.class, id))
                .assertNext(member -> {
                    assertEquals("ada", member.getName());
                    assertEquals("hacker", member.getBio());
                    assertEquals("london", member.getCity());
                })
                .verifyComplete();
    }

    @Test
    void updatePropagatesToSecondaryTable() {
        Member ada = new Member("ada");
        ada.setBio("hacker");
        ada.setCity("london");
        Long id = support.operations().save(ada).map(Member::getId).block();

        Member loaded = support.operations().findById(Member.class, id).block();
        loaded.setName("ada lovelace");
        loaded.setBio("first programmer");
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Member.class, id))
                .assertNext(member -> {
                    assertEquals("ada lovelace", member.getName());
                    assertEquals("first programmer", member.getBio());
                    assertEquals("london", member.getCity());
                })
                .verifyComplete();
        // 보조 행은 여전히 1건(중복 INSERT 없이 UPDATE).
        assertEquals(1L, secondaryRowCount(id));
    }

    @Test
    void deleteRemovesPrimaryAndSecondaryRows() {
        Member ada = new Member("ada");
        ada.setBio("hacker");
        Long id = support.operations().save(ada).map(Member::getId).block();
        assertEquals(1L, secondaryRowCount(id));

        Member loaded = support.operations().findById(Member.class, id).block();
        Long affected = support.operations().delete(loaded).block();
        assertEquals(1L, affected);

        // primary 행과 보조 행 모두 사라진다.
        assertEquals(0L, secondaryRowCount(id));
        StepVerifier.create(support.operations().findById(Member.class, id))
                .verifyComplete();
    }

    @Test
    void nullSecondaryColumnsRoundTrip() {
        Member bare = new Member("bare");
        Long id = support.operations().save(bare).map(Member::getId).block();
        // 보조 컬럼이 모두 null이어도 보조 행은 생성된다(이후 update/select 성립).
        assertEquals(1L, secondaryRowCount(id));

        StepVerifier.create(support.operations().findById(Member.class, id))
                .assertNext(member -> {
                    assertEquals("bare", member.getName());
                    assertNull(member.getBio());
                    assertNull(member.getCity());
                })
                .verifyComplete();
    }

    @Test
    void findAllJoinsSecondaryTable() {
        Member ada = new Member("ada");
        ada.setBio("a-bio");
        Member bob = new Member("bob");
        bob.setBio("b-bio");
        support.operations().save(ada).block();
        support.operations().save(bob).block();

        QuerySpec spec = QuerySpec.empty().orderBy(Sort.by(Sort.Order.asc("name")));
        List<Member> members = support.operations().findAll(Member.class, spec).collectList().block();
        assertEquals(2, members.size());
        assertEquals("ada", members.get(0).getName());
        assertEquals("a-bio", members.get(0).getBio());
        assertEquals("bob", members.get(1).getName());
        assertEquals("b-bio", members.get(1).getBio());
    }

    @Entity
    @Table(name = "member")
    @SecondaryTable(name = "member_profile",
            pkJoinColumns = @PrimaryKeyJoinColumn(name = "member_id"))
    public static class Member {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @Column(table = "member_profile")
        private String bio;
        @Column(table = "member_profile")
        private String city;

        public Member() {
        }

        public Member(String name) {
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
