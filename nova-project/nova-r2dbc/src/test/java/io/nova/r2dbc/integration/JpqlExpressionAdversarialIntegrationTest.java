package io.nova.r2dbc.integration;

import io.nova.query.jpql.JpqlExecutor;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track B(JPQL 표현식 파리티)의 독립 adversarial 통합 테스트. H2 in-memory R2DBC로 실제 실행 결과를 검증한다.
 * 함수형 LEFT/RIGHT 와 조인형 LEFT JOIN 공존, ANY/ALL 빈 서브쿼리·NULL 3-값 논리, EXTRACT 필드, LOCAL
 * 시각 함수, TRIM 4형태, REPLACE 파라미터 바인딩을 저자 테스트와 무관하게 라운드트립으로 공격한다.
 *
 * <p>score를 nullable {@code Integer}로 두어 서브쿼리에 NULL을 주입할 수 있게 한다.
 */
class JpqlExpressionAdversarialIntegrationTest {

    private JpqlExecutor jpql;

    @BeforeEach
    void setUp() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Bin.class).block();
        schema.create(Widget.class).block();
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                Widget.class, Bin.class);

        Bin alpha = support.operations().save(new Bin("alpha")).block();
        Bin beta = support.operations().save(new Bin("beta")).block();
        support.operations().save(new Widget("Ada", 10, alpha)).block();
        support.operations().save(new Widget("Bob", 20, alpha)).block();
        support.operations().save(new Widget("Cara", null, beta)).block();
        support.operations().save(new Widget("  pad  ", 30, beta)).block();
    }

    // ---- Edge 1: 함수형 LEFT/RIGHT 와 조인형 LEFT JOIN 이 같은 쿼리에서 실제로 동작한다 ---------

    @Test
    void leftFunctionCoexistsWithLeftJoinRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT LEFT(w.name, 2) FROM Widget w LEFT JOIN w.bin b "
                                                + "WHERE b.name = 'alpha' ORDER BY w.name", String.class)
                                .getResultList())
                .expectNext("Ad", "Bo")
                .verifyComplete();
    }

    @Test
    void rightFunctionCoexistsWithLeftJoinRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT RIGHT(w.name, 2) FROM Widget w LEFT JOIN w.bin b "
                                                + "WHERE b.name = 'alpha' ORDER BY w.name", String.class)
                                .getResultList())
                .expectNext("da", "ob")
                .verifyComplete();
    }

    // ---- Edge 2: ANY/ALL 시맨틱 (빈 서브쿼리 + NULL 3-값 논리) -------------------------------

    @Test
    void equalAllOverEmptySubqueryIsTrueForEveryRowIncludingNullScore() {
        // = ALL(빈 집합)은 좌변이 NULL이어도 vacuously TRUE — 모든 4행(Cara의 NULL score 포함)이 통과해야 한다.
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT w.name FROM Widget w WHERE w.score = ALL "
                                                + "(SELECT x.score FROM Widget x WHERE x.score > 1000)", String.class)
                                .getResultList())
                .expectNextCount(4)
                .verifyComplete();
    }

    @Test
    void equalAnyOverEmptySubqueryIsFalseForEveryRow() {
        // = ANY(빈 집합)은 FALSE — 한 행도 통과하면 안 된다.
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT w.name FROM Widget w WHERE w.score = ANY "
                                                + "(SELECT x.score FROM Widget x WHERE x.score > 1000)", String.class)
                                .getResultList()
                                .collectList())
                .assertNext(rows -> assertTrue(rows.isEmpty(), "= ANY(empty) must match no rows, got " + rows))
                .verifyComplete();
    }

    @Test
    void equalAnyWithNullInSubqueryMatchesOnlyExactValueNotNull() {
        // 서브쿼리 = {20(Bob), NULL(Cara)}. Ada(10)/pad(30)는 unknown으로 제외, Cara(NULL)도 제외, Bob(20)만 매칭.
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT w.name FROM Widget w WHERE w.score = ANY "
                                                + "(SELECT x.score FROM Widget x WHERE x.name = 'Bob' OR x.name = 'Cara') "
                                                + "ORDER BY w.name", String.class)
                                .getResultList())
                .expectNext("Bob")
                .verifyComplete();
    }

    @Test
    void notEqualAllWithNullInSubqueryYieldsNoRows() {
        // <> ALL(NULL 포함)의 전형적 NULL 함정: 어떤 좌변도 NULL과의 <>가 unknown이라 ALL이 TRUE가 될 수 없다.
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT w.name FROM Widget w WHERE w.score <> ALL "
                                                + "(SELECT x.score FROM Widget x WHERE x.name = 'Cara')", String.class)
                                .getResultList()
                                .collectList())
                .assertNext(rows -> assertTrue(rows.isEmpty(), "<> ALL(subquery with NULL) must match no rows, got " + rows))
                .verifyComplete();
    }

    // ---- Edge 3: EXTRACT — portable 화이트리스트 필드가 H2에서 실제로 실행된다 -------------------

    @Test
    void extractQuarterAndWeekRoundTrip() {
        // 화이트리스트에 있는 덜 흔한 필드(QUARTER/WEEK)가 실제 H2에서 수용되는지 — portable 주장 검증.
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT EXTRACT(QUARTER FROM CURRENT_DATE) FROM Widget w WHERE w.name = 'Ada'",
                                        Object.class)
                                .getSingleResult())
                .assertNext(v -> {
                    int q = ((Number) v).intValue();
                    assertTrue(q >= 1 && q <= 4, "QUARTER out of range: " + q);
                })
                .verifyComplete();

        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT EXTRACT(WEEK FROM CURRENT_DATE) FROM Widget w WHERE w.name = 'Ada'",
                                        Object.class)
                                .getSingleResult())
                .assertNext(v -> {
                    int wk = ((Number) v).intValue();
                    assertTrue(wk >= 1 && wk <= 53, "WEEK out of range: " + wk);
                })
                .verifyComplete();
    }

    // ---- Edge 5: LOCAL TIME / LOCAL DATETIME 매핑이 실제 값을 돌려준다 --------------------------

    @Test
    void localTimeAndDatetimeReturnRealValues() {
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT EXTRACT(HOUR FROM LOCAL TIME) FROM Widget w WHERE w.name = 'Ada'",
                                        Object.class)
                                .getSingleResult())
                .assertNext(v -> {
                    int h = ((Number) v).intValue();
                    assertTrue(h >= 0 && h <= 23, "HOUR out of range: " + h);
                })
                .verifyComplete();

        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT EXTRACT(YEAR FROM LOCAL DATETIME) FROM Widget w WHERE w.name = 'Ada'",
                                        Object.class)
                                .getSingleResult())
                .assertNext(v -> assertTrue(((Number) v).intValue() >= 2024, "YEAR too small: " + v))
                .verifyComplete();
    }

    // ---- Edge 4: TRIM 4형태의 실제 실행 -------------------------------------------------------

    @Test
    void plainTrimRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery("SELECT TRIM(w.name) FROM Widget w WHERE w.name = '  pad  '", String.class)
                                .getSingleResult())
                .expectNext("pad")
                .verifyComplete();
    }

    @Test
    void trimBothFromRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery("SELECT TRIM(BOTH FROM w.name) FROM Widget w WHERE w.name = '  pad  '",
                                        String.class)
                                .getSingleResult())
                .expectNext("pad")
                .verifyComplete();
    }

    @Test
    void trimLeadingAndTrailingKeepOppositeSideRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery("SELECT TRIM(LEADING FROM w.name) FROM Widget w WHERE w.name = '  pad  '",
                                        String.class)
                                .getSingleResult())
                .expectNext("pad  ")
                .verifyComplete();

        StepVerifier.create(
                        jpql.createQuery("SELECT TRIM(TRAILING FROM w.name) FROM Widget w WHERE w.name = '  pad  '",
                                        String.class)
                                .getSingleResult())
                .expectNext("  pad")
                .verifyComplete();
    }

    @Test
    void trimBothCharFromRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery("SELECT TRIM(BOTH ' ' FROM w.name) FROM Widget w WHERE w.name = '  pad  '",
                                        String.class)
                                .getSingleResult())
                .expectNext("pad")
                .verifyComplete();
    }

    // ---- Edge 7: REPLACE 파라미터가 bind 경유로 실행된다 ---------------------------------------

    @Test
    void replaceWithParametersRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery("SELECT REPLACE(w.name, :a, :b) FROM Widget w WHERE w.name = 'Ada'",
                                        String.class)
                                .setParameter("a", "a")
                                .setParameter("b", "X")
                                .getSingleResult())
                .expectNext("AdX")
                .verifyComplete();
    }

    // ---- 확장 수치 함수: SIGN 음수 / FLOOR 라운드트립 -----------------------------------------

    @Test
    void signOfNegativeExpressionRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery("SELECT SIGN(w.score - 100) FROM Widget w WHERE w.name = 'Ada'", Object.class)
                                .getSingleResult())
                .assertNext(v -> assertEquals(-1, ((Number) v).intValue()))
                .verifyComplete();
    }

    @Test
    void floorRoundTrip() {
        StepVerifier.create(
                        jpql.createQuery("SELECT FLOOR(w.score) FROM Widget w WHERE w.name = 'Bob'", Object.class)
                                .getSingleResult())
                .assertNext(v -> assertEquals(20, ((Number) v).intValue()))
                .verifyComplete();
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "adv_bin")
    public static class Bin {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;

        public Bin() {
        }

        public Bin(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "adv_widget")
    public static class Widget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "score")
        private Integer score;
        @ManyToOne
        @JoinColumn(name = "bin_id")
        private Bin bin;

        public Widget() {
        }

        public Widget(String name, Integer score, Bin bin) {
            this.name = name;
            this.score = score;
            this.bin = bin;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Integer getScore() {
            return score;
        }

        public Bin getBin() {
            return bin;
        }
    }
}
