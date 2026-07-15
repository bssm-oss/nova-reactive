package io.nova.r2dbc.integration;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 복합키({@code @EmbeddedId}) 부모를 {@code @ManyToOne}으로 가진 자식에서, JPQL {@code GROUP BY}/{@code ORDER BY}
 * 위치의 복합 to-one 참조가 <b>모든 FK 컬럼</b>으로 전개되는지 실제 H2 in-memory R2DBC driver로 end-to-end
 * 검증한다({@link CompositeToOneJoinIntegrationTest}의 join/WHERE 커버리지를 GROUP BY/ORDER BY로 확장).
 *
 * <p>핵심 정확성 주장: 부모는 {@code (region, code)} 복합키다. 자식은 {@code beta→(US,2)}, {@code alpha→(US,1)},
 * {@code gamma→(EU,1)} 순서로 삽입한다(물리/삽입 순서가 canonical 정렬 순서와 다르게 일부러 뒤섞음). 두 컬럼
 * 모두로 정렬/그룹핑해야만 기대 결과가 나온다 — region 컬럼 하나만 쓰면 같은 region인 alpha/beta의 상대
 * 순서가 삽입 순서(beta, alpha)로 새어 나오고(canonical 순서는 code 기준 alpha, beta), GROUP BY도 US 두
 * 행이 한 그룹으로 병합돼 3그룹이 아닌 2그룹이 된다.
 */
class CompositeToOneGroupOrderIntegrationTest {

    private H2IntegrationTestSupport support;
    private JpqlExecutor jpql;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        support.execute("create table \"wj_parent\" ("
                + "\"region\" varchar(16), \"code\" int, \"name\" varchar(255), "
                + "primary key (\"region\", \"code\"))");
        support.execute("create table \"wj_child\" ("
                + "\"id\" bigint primary key, \"label\" varchar(255), "
                + "\"parent_region\" varchar(16), \"parent_code\" int)");

        support.execute("insert into \"wj_parent\" (\"region\", \"code\", \"name\") values ('US', 1, 'Sales')");
        support.execute("insert into \"wj_parent\" (\"region\", \"code\", \"name\") values ('US', 2, 'Eng')");
        support.execute("insert into \"wj_parent\" (\"region\", \"code\", \"name\") values ('EU', 1, 'Other')");

        // 물리/삽입 순서를 canonical(region, code) 정렬 순서와 일부러 어긋나게 한다: beta(US,2) 먼저,
        // 그다음 alpha(US,1), 마지막 gamma(EU,1). region-only 정렬/그룹핑이면 이 어긋남이 새어 나온다.
        support.execute("insert into \"wj_child\" (\"id\", \"label\", \"parent_region\", \"parent_code\") "
                + "values (2, 'beta', 'US', 2)");
        support.execute("insert into \"wj_child\" (\"id\", \"label\", \"parent_region\", \"parent_code\") "
                + "values (1, 'alpha', 'US', 1)");
        support.execute("insert into \"wj_child\" (\"id\", \"label\", \"parent_region\", \"parent_code\") "
                + "values (3, 'gamma', 'EU', 1)");

        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                WgChild.class, WgParent.class);
    }

    @Test
    void orderByCompositeKeyToOneMatchesCanonicalLexicalOrderNotFirstColumnOnly() {
        // canonical (region, code) ascending: (EU,1) < (US,1) < (US,2) → gamma, alpha, beta.
        // region-only 정렬이면 같은 'US' 안에서 삽입 순서(beta, alpha)가 새어 나와 alpha/beta가 뒤바뀐다.
        StepVerifier.create(
                        jpql.createQuery("SELECT c.label FROM WgChild c ORDER BY c.parent ASC", String.class)
                                .getResultList())
                .expectNext("gamma", "alpha", "beta")
                .verifyComplete();
    }

    @Test
    void groupByCompositeKeyToOneProducesThreeDistinctGroupsNotMergedByFirstColumn() {
        // 세 자식이 서로 다른 (region, code) 조합을 참조하므로 두 컬럼 모두로 그룹핑하면 3그룹, 각 count=1.
        // region-only 그룹핑이면 같은 'US'인 alpha/beta가 한 그룹으로 병합돼 count=2인 그룹이 생기고 2그룹이 된다.
        StepVerifier.create(
                        jpql.createQuery("SELECT COUNT(c.id) FROM WgChild c GROUP BY c.parent", Long.class)
                                .getResultList()
                                .collectList())
                .assertNext(counts -> {
                    assertEquals(3, counts.size(), "expected 3 distinct composite-key groups, got " + counts);
                    assertTrue(counts.stream().allMatch(count -> count == 1L),
                            "each group must have exactly one child, got " + counts);
                })
                .verifyComplete();
    }

    @Embeddable
    public static class WgParentId {
        @Column(name = "region")
        private String region;
        @Column(name = "code")
        private Integer code;

        public WgParentId() {
        }

        public WgParentId(String region, Integer code) {
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
            if (!(other instanceof WgParentId that)) {
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
    public static class WgParent {
        @EmbeddedId
        private WgParentId id;
        @Column(name = "name")
        private String name;

        public WgParent() {
        }

        public WgParentId getId() {
            return id;
        }

        public void setId(WgParentId id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "wj_child")
    public static class WgChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "label")
        private String label;
        @ManyToOne(targetEntity = WgParent.class)
        @JoinColumns({
                @JoinColumn(name = "parent_region", referencedColumnName = "region"),
                @JoinColumn(name = "parent_code", referencedColumnName = "code")
        })
        private WgParent parent;

        public WgChild() {
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public WgParent getParent() {
            return parent;
        }
    }
}
