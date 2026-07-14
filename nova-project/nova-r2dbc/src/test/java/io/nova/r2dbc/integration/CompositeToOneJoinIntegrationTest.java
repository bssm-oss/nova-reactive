package io.nova.r2dbc.integration;

import io.nova.query.criteria.ReactiveCriteriaExecutor;
import io.nova.query.jpql.JpqlExecutor;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 복합키({@code @EmbeddedId}) 부모를 {@code @ManyToOne}으로 가진 자식에서, JPQL/Criteria join과 terminal
 * 참조가 <b>모든 FK 컬럼</b>을 ON/비교에 넣는지(다중컬럼 ON)를 실제 H2 in-memory R2DBC driver로 end-to-end
 * 검증한다.
 *
 * <p>핵심 정확성 주장: 부모는 {@code (region, code)} 복합키다. 시드는 sibling 행 {@code (US,1)}/{@code (US,2)}
 * (같은 region)과 {@code (EU,1)}(같은 code)을 함께 넣어, ON/비교가 <b>한 컬럼만</b> 쓰면 잘못된 부모 행이 매칭돼
 * 결과가 달라지도록 설계됐다. 두 컬럼을 모두 {@code and}로 매칭해야만 기대 결과가 나온다.
 */
class CompositeToOneJoinIntegrationTest {

    private H2IntegrationTestSupport support;
    private JpqlExecutor jpql;
    private ReactiveCriteriaExecutor criteria;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        support.execute("create table \"wj_parent\" ("
                + "\"region\" varchar(16), \"code\" int, \"name\" varchar(255), "
                + "primary key (\"region\", \"code\"))");
        support.execute("create table \"wj_child\" ("
                + "\"id\" bigint primary key, \"label\" varchar(255), "
                + "\"parent_region\" varchar(16), \"parent_code\" int)");

        // 부모: (US,1)=Sales, (US,2)=Eng[region 겹침], (EU,1)=Other[code 겹침].
        support.execute("insert into \"wj_parent\" (\"region\", \"code\", \"name\") values ('US', 1, 'Sales')");
        support.execute("insert into \"wj_parent\" (\"region\", \"code\", \"name\") values ('US', 2, 'Eng')");
        support.execute("insert into \"wj_parent\" (\"region\", \"code\", \"name\") values ('EU', 1, 'Other')");

        // 자식: alpha→(US,1), beta→(US,2), gamma→(EU,1), delta→참조 없음.
        support.execute("insert into \"wj_child\" (\"id\", \"label\", \"parent_region\", \"parent_code\") "
                + "values (1, 'alpha', 'US', 1)");
        support.execute("insert into \"wj_child\" (\"id\", \"label\", \"parent_region\", \"parent_code\") "
                + "values (2, 'beta', 'US', 2)");
        support.execute("insert into \"wj_child\" (\"id\", \"label\", \"parent_region\", \"parent_code\") "
                + "values (3, 'gamma', 'EU', 1)");
        support.execute("insert into \"wj_child\" (\"id\", \"label\", \"parent_region\", \"parent_code\") "
                + "values (4, 'delta', null, null)");

        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                WjChild.class, WjParent.class);
        criteria = new ReactiveCriteriaExecutor(support.operations(), support.dialect(), support.metadataFactory());
    }

    private static WjParent parentRef(String region, int code) {
        WjParent p = new WjParent();
        p.setId(new WjParentId(region, code));
        return p;
    }

    // ------------------------------------------------------------------------------------
    // JPQL
    // ------------------------------------------------------------------------------------

    @Test
    void jpqlScalarJoinMatchesOnAllForeignKeyColumns() {
        // JOIN c.parent p WHERE p.name='Sales'. 올바른 2-컬럼 ON은 (US,1)만 매칭 → alpha 한 건.
        // code-only ON이면 gamma(EU,1)도 (US,1)에 붙어 gamma까지 나온다. region-only ON이면 beta(US,2)가 새어 나온다.
        StepVerifier.create(
                        jpql.createQuery("SELECT c.label FROM WjChild c JOIN c.parent p "
                                + "WHERE p.name = 'Sales' ORDER BY c.label", String.class)
                                .getResultList())
                .expectNext("alpha")
                .verifyComplete();
    }

    @Test
    void jpqlEntityReturningJoinExcludesSiblingCompositeRows() {
        // 엔티티 반환 + 필터 JOIN(2단계 실행). p.name='Sales' → (US,1) 소속 자식만. gamma(EU,1)는 제외돼야 한다.
        StepVerifier.create(
                        jpql.createQuery("SELECT c FROM WjChild c JOIN c.parent p "
                                + "WHERE p.name = 'Sales' ORDER BY c.label", WjChild.class)
                                .getResultList())
                .assertNext(c -> assertEquals("alpha", c.getLabel()))
                .verifyComplete();
    }

    @Test
    void jpqlTerminalCompositeEqualityExpandsToAllComponents() {
        // WHERE c.parent = :ref. ref=(US,1). 두 컴포넌트 AND 매칭이면 alpha만.
        // code-only 확장이면 gamma(EU,1)까지, region-only면 beta(US,2)까지 매칭될 것이다.
        StepVerifier.create(
                        jpql.createQuery("SELECT c.label FROM WjChild c WHERE c.parent = :ref ORDER BY c.label",
                                        String.class)
                                .setParameter("ref", parentRef("US", 1))
                                .getResultList())
                .expectNext("alpha")
                .verifyComplete();
    }

    @Test
    void jpqlTerminalIsNullOnAllForeignKeyColumns() {
        StepVerifier.create(
                        jpql.createQuery("SELECT c.label FROM WjChild c WHERE c.parent IS NULL", String.class)
                                .getResultList())
                .expectNext("delta")
                .verifyComplete();

        StepVerifier.create(
                        jpql.createQuery("SELECT c.label FROM WjChild c WHERE c.parent IS NOT NULL ORDER BY c.label",
                                        String.class)
                                .getResultList())
                .expectNext("alpha", "beta", "gamma")
                .verifyComplete();
    }

    @Test
    void jpqlSelectCompositeToOneProjectionFailsFast() {
        // SELECT c.parent(복합 to-one을 단일 컬럼으로 투영)은 축약 불가 — 조용한 오답 대신 fail-fast.
        StepVerifier.create(
                        jpql.createQuery("SELECT c.parent FROM WjChild c", Object.class).getResultList())
                .expectError()
                .verify();
    }

    // ------------------------------------------------------------------------------------
    // Criteria (JPQL 동등)
    // ------------------------------------------------------------------------------------

    @Test
    void criteriaEntityJoinExcludesSiblingCompositeRows() {
        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<WjChild> cq = cb.createQuery(WjChild.class);
        Root<WjChild> c = cq.from(WjChild.class);
        Join<WjChild, WjParent> p = c.join("parent");
        cq.select(c).where(cb.equal(p.<String>get("name"), "Sales")).orderBy(cb.asc(c.<String>get("label")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("alpha", x.getLabel()))
                .verifyComplete();
    }

    @Test
    void criteriaTerminalCompositeEqualityExpandsToAllComponents() {
        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<WjChild> cq = cb.createQuery(WjChild.class);
        Root<WjChild> c = cq.from(WjChild.class);
        cq.select(c).where(cb.equal(c.get("parent"), parentRef("US", 1))).orderBy(cb.asc(c.<String>get("label")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("alpha", x.getLabel()))
                .verifyComplete();
    }

    @Test
    void criteriaTerminalIsNullOnAllForeignKeyColumns() {
        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<WjChild> cq = cb.createQuery(WjChild.class);
        Root<WjChild> c = cq.from(WjChild.class);
        cq.select(c).where(cb.isNull(c.get("parent")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("delta", x.getLabel()))
                .verifyComplete();
    }

    @Test
    void criteriaSelectCompositeToOneProjectionFailsFast() {
        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<WjChild> c = cq.from(WjChild.class);
        cq.select(c.get("parent"));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .expectError()
                .verify();
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Embeddable
    public static class WjParentId {
        @Column(name = "region")
        private String region;
        @Column(name = "code")
        private Integer code;

        public WjParentId() {
        }

        public WjParentId(String region, Integer code) {
            this.region = region;
            this.code = code;
        }

        public String getRegion() {
            return region;
        }

        public Integer getCode() {
            return code;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WjParentId that)) {
                return false;
            }
            return Objects.equals(region, that.region) && Objects.equals(code, that.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(region, code);
        }
    }

    @Entity
    @Table(name = "wj_parent")
    public static class WjParent {
        @EmbeddedId
        private WjParentId id;
        @Column(name = "name")
        private String name;

        public WjParent() {
        }

        public WjParentId getId() {
            return id;
        }

        public void setId(WjParentId id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "wj_child")
    public static class WjChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "label")
        private String label;
        @ManyToOne(targetEntity = WjParent.class)
        @JoinColumns({
                @JoinColumn(name = "parent_region", referencedColumnName = "region"),
                @JoinColumn(name = "parent_code", referencedColumnName = "code")
        })
        private WjParent parent;

        public WjChild() {
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public WjParent getParent() {
            return parent;
        }
    }
}
