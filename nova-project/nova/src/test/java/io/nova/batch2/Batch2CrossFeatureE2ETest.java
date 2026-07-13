package io.nova.batch2;

import io.nova.cache.NovaCache;
import io.nova.cache.SimpleReactiveCacheProvider;
import io.nova.cache.SimpleReactiveQueryCache;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityManager;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutionListener;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.query.criteria.ReactiveCriteriaExecutor;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.query.resultset.SqlResultSetMappingRegistry;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityResult;
import jakarta.persistence.FieldResult;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch2 교차 기능(cross-feature) E2E. 개별 모듈 H2 통합 테스트가 이미 green인 상태에서, Batch2 신규 6기능이
 * 서로 그리고 기존 기능과 함께 <b>한 애플리케이션 흐름/한 DB</b> 위에서 실제 r2dbc-h2 driver로 정합하는지 고정한다.
 *
 * <p>대상 기능:
 * <ul>
 *   <li><b>A</b> 스칼라 타입 파리티(UUID/Float/Short scalar + UUID {@code @Id})</li>
 *   <li><b>B</b> JPQL 프로젝션/조인({@code SELECT NEW} DTO, 다세그먼트 묵시 조인, non-fetch JOIN, 혼합 JOIN FETCH)</li>
 *   <li><b>C</b> {@link ReactiveCriteriaExecutor} 조인/서브쿼리</li>
 *   <li><b>D</b> {@link ReactiveEntityManager} 잠금({@code find(LockModeType)})/FlushMode/temporal {@code @Version}</li>
 *   <li><b>E</b> {@code @SqlResultSetMapping} native 결과 → 엔티티/DTO 매핑</li>
 *   <li><b>F</b> 2차 쿼리 결과 캐시(read-through + write invalidation)</li>
 * </ul>
 *
 * <p>모든 협력자(base operations, 쿼리 캐시 데코레이터, EntityManager, JpqlExecutor, CriteriaExecutor,
 * SqlResultSetMappingRegistry)는 <em>동일한</em> {@link EntityMetadataFactory}와 동일한 H2 DB, 동일한 실행
 * {@link SqlExecutionListener}를 공유해 실제 상호작용/오염 여부를 관찰한다. base는 production {@code Nova.create}와
 * 동일하게 {@link R2dbcTransactionManager}를 transaction operations로 직접 받는 managed-transaction 배선이라
 * {@code inTransaction} 콜백에 커넥션이 Reactor Context로 전파되고, FOR UPDATE/세션 flush/롤백이 실제 경로를 탄다.
 */
class Batch2CrossFeatureE2ETest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    /** SELECT NEW 프로젝션 타깃 DTO의 FQN(중첩 클래스라 '$' 구분자). JPQL 파서가 lexer 식별자로 그대로 읽는다. */
    private static final String PRODUCT_VIEW_FQN =
            "io.nova.batch2.Batch2CrossFeatureE2ETest$ProductView";

    private record Wiring(
            SimpleReactiveEntityOperations base,
            ReactiveEntityOperations cached,
            ReactiveEntityManager entityManager,
            JpqlExecutor jpql,
            ReactiveCriteriaExecutor criteria,
            SqlResultSetMappingRegistry mappings,
            StatementListener listener) {
    }

    private Wiring wire() {
        int seq = DB_SEQ.incrementAndGet();
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///batch2e2e" + seq + "?options=DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        StatementListener listener = new StatementListener();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect, listener);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(cf);

        SimpleReactiveEntityOperations base = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        // 쿼리 결과 캐시 데코레이터(entity cache + query cache). JPQL/Criteria는 base에서 실행해 캐시를 우회하고,
        // findAll(QuerySpec)/write는 cached에서 실행해 read-through/무효화를 관찰한다.
        ReactiveEntityOperations cached = NovaCache.cachingWithQueryCache(
                base, new SimpleReactiveCacheProvider(), metadataFactory, new SimpleReactiveQueryCache());
        // 잠금/FlushMode/temporal version은 managed-transaction base 위에서 검증한다.
        ReactiveEntityManager em = new SimpleReactiveEntityManager(base, metadataFactory);
        JpqlExecutor jpql = new JpqlExecutor(base, dialect, metadataFactory,
                Category.class, Supplier.class, Product.class, Token.class, AuditDoc.class);
        ReactiveCriteriaExecutor criteria = new ReactiveCriteriaExecutor(base, dialect, metadataFactory);
        SqlResultSetMappingRegistry mappings =
                new SqlResultSetMappingRegistry(base, metadataFactory, Product.class, Token.class);

        SchemaInitializer schema = new SimpleSchemaInitializer(base, metadataFactory, dialect);
        schema.create(Category.class, Supplier.class, Product.class, Token.class, AuditDoc.class).block();

        return new Wiring(base, cached, em, jpql, criteria, mappings, listener);
    }

    /**
     * category(electronics/books) × supplier(acme/globex) 위에 4개 Product를 심는다.
     * <pre>
     *   phone : electronics, acme,   rating 5, weight 0.5, price 900
     *   laptop: electronics, globex, rating 4, weight 2.0, price 1500
     *   novel : books,       acme,   rating 3, weight 0.3, price 20
     *   manual: books,       globex, rating 2, weight 0.4, price 40
     * </pre>
     */
    private Shop seedShop(Wiring w) {
        Category electronics = w.base().save(new Category("electronics")).block();
        Category books = w.base().save(new Category("books")).block();
        Supplier acme = w.base().save(new Supplier("acme")).block();
        Supplier globex = w.base().save(new Supplier("globex")).block();

        w.base().save(product("phone", electronics, acme, (short) 5, 0.5f, "900")).block();
        w.base().save(product("laptop", electronics, globex, (short) 4, 2.0f, "1500")).block();
        w.base().save(product("novel", books, acme, (short) 3, 0.3f, "20")).block();
        w.base().save(product("manual", books, globex, (short) 2, 0.4f, "40")).block();
        return new Shop(electronics, books, acme, globex);
    }

    private static Product product(String name, Category category, Supplier supplier,
            short rating, float weight, String price) {
        Product p = new Product(name, UUID.randomUUID(), weight, rating, new BigDecimal(price).setScale(2));
        p.setCategory(category);
        p.setSupplier(supplier);
        return p;
    }

    private record Shop(Category electronics, Category books, Supplier acme, Supplier globex) {
    }

    // =============================================================================================
    // A — 스칼라 타입 파리티가 저장·조회 + JPQL/Criteria WHERE/결과에서 동작
    // =============================================================================================

    @Test
    void scalarTypesRoundTripAndDriveJpqlAndCriteriaFiltersAndResults() {
        Wiring w = wire();
        UUID sku = UUID.randomUUID();
        Product w0 = new Product("gadget", sku, 1.25f, (short) 7, new BigDecimal("50.00"));
        Long id = w.base().save(w0).map(Product::getId).block();

        // findById가 UUID/Float/Short 스칼라를 도메인 타입으로 복원한다.
        StepVerifier.create(w.base().findById(Product.class, id))
                .assertNext(loaded -> {
                    assertEquals(sku, loaded.getSku());
                    assertEquals(1.25f, loaded.getWeight());
                    assertEquals((short) 7, loaded.getRating());
                })
                .verifyComplete();

        // JPQL WHERE에 Short 파라미터 — rating >= 7 → gadget. 결과 엔티티의 UUID sku도 복원돼야 한다.
        StepVerifier.create(w.jpql()
                        .createQuery("SELECT p FROM Product p WHERE p.rating >= :min", Product.class)
                        .setParameter("min", (short) 7)
                        .getResultList())
                .assertNext(p -> {
                    assertEquals("gadget", p.getName());
                    assertEquals(sku, p.getSku(), "JPQL 엔티티 결과에서도 UUID 스칼라가 복원돼야 한다");
                })
                .verifyComplete();

        // Criteria WHERE에 Float 파라미터 — weight = 1.25 → gadget.
        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Product> cq = cb.createQuery(Product.class);
        Root<Product> root = cq.from(Product.class);
        cq.select(root).where(cb.equal(root.<Float>get("weight"), 1.25f));
        StepVerifier.create(w.criteria().createQuery(cq).getResultList().map(Product::getName))
                .expectNext("gadget")
                .verifyComplete();
    }

    // =============================================================================================
    // B — JPQL SELECT NEW DTO + 다세그먼트 묵시 조인이 실제 데이터로 정확
    // =============================================================================================

    @Test
    void jpqlSelectNewDtoWithMultiSegmentImplicitJoinProjectsAccurately() {
        Wiring w = wire();
        seedShop(w);

        // SELECT NEW DTO(p.name, p.category.name, p.price): p.category.name은 다세그먼트 묵시 조인.
        // WHERE p.category.name = 'electronics' → laptop/phone, 이름 오름차순.
        StepVerifier.create(w.jpql()
                        .<ProductView>createQuery(
                                "SELECT NEW " + PRODUCT_VIEW_FQN
                                        + "(p.name, p.category.name, p.price) FROM Product p "
                                        + "WHERE p.category.name = :cat ORDER BY p.name",
                                ProductView.class)
                        .setParameter("cat", "electronics")
                        .getResultList())
                .assertNext(v -> {
                    assertEquals("laptop", v.productName());
                    assertEquals("electronics", v.categoryName());
                    assertEquals(0, v.price().compareTo(new BigDecimal("1500.00")));
                })
                .assertNext(v -> {
                    assertEquals("phone", v.productName());
                    assertEquals("electronics", v.categoryName());
                    assertEquals(0, v.price().compareTo(new BigDecimal("900.00")));
                })
                .verifyComplete();
    }

    // =============================================================================================
    // C — Criteria 조인/서브쿼리 결과가 동등 JPQL 쿼리와 같은 데이터에서 일치
    // =============================================================================================

    @Test
    void criteriaInnerJoinAgreesWithEquivalentJpqlNonFetchJoin() {
        Wiring w = wire();
        seedShop(w);

        // Criteria: Product JOIN category WHERE category.name = 'electronics' ORDER BY name.
        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Product> cq = cb.createQuery(Product.class);
        Root<Product> root = cq.from(Product.class);
        Join<Product, Category> cat = root.join("category");
        cq.select(root).where(cb.equal(cat.<String>get("name"), "electronics"))
                .orderBy(cb.asc(root.<String>get("name")));
        List<String> viaCriteria = w.criteria().createQuery(cq).getResultList()
                .map(Product::getName).collectList().block();

        // 동등 JPQL non-fetch JOIN(2단계 id 투영 경로).
        List<String> viaJpql = w.jpql()
                .createQuery("SELECT p FROM Product p JOIN p.category c WHERE c.name = :cat ORDER BY p.name",
                        Product.class)
                .setParameter("cat", "electronics")
                .getResultList()
                .map(Product::getName)
                .collectList()
                .block();

        assertEquals(List.of("laptop", "phone"), viaCriteria, "Criteria inner join 결과");
        assertEquals(viaCriteria, viaJpql, "Criteria와 동등 JPQL non-fetch join이 같은 데이터에서 일치해야 한다");
    }

    @Test
    void criteriaScalarSubqueryFiltersAboveAveragePrice() {
        Wiring w = wire();
        seedShop(w); // avg price = (900+1500+20+40)/4 = 615 → price > avg: laptop(1500), phone(900)

        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Product> cq = cb.createQuery(Product.class);
        Root<Product> root = cq.from(Product.class);
        Subquery<Double> avg = cq.subquery(Double.class);
        Root<Product> sub = avg.from(Product.class);
        avg.select(cb.avg(sub.<BigDecimal>get("price")));
        cq.select(root).where(cb.gt(root.<BigDecimal>get("price"), avg))
                .orderBy(cb.asc(root.<String>get("name")));

        StepVerifier.create(w.criteria().createQuery(cq).getResultList().map(Product::getName))
                .expectNext("laptop", "phone")
                .verifyComplete();
    }

    // =============================================================================================
    // B(최종보완) — 혼합 JOIN FETCH + non-fetch JOIN: fetch join은 always-eager no-op, 필터는 정확
    // =============================================================================================

    @Test
    void mixedJoinFetchAndNonFetchJoinFiltersCorrectlyAndStillHydratesFetchedAssociation() {
        // [B 리뷰 LOW] JOIN FETCH p.category(항상 eager라 no-op) + non-fetch JOIN p.supplier(필터)가 함께 오면
        // 2단계 경로로 라우팅되며 fetch join은 id 투영에서 제외된다. 결과 정합: supplier로 필터되고 category는
        // always-eager로 여전히 hydrate돼야 한다(fetch join 누락이 결과 그래프를 바꾸지 않음).
        Wiring w = wire();
        seedShop(w); // supplier acme → phone, novel

        List<Product> result = w.jpql()
                .createQuery("SELECT p FROM Product p JOIN FETCH p.category c JOIN p.supplier s "
                        + "WHERE s.name = :sup ORDER BY p.name", Product.class)
                .setParameter("sup", "acme")
                .getResultList()
                .collectList()
                .block();

        assertNotNull(result);
        assertEquals(List.of("novel", "phone"), result.stream().map(Product::getName).toList(),
                "non-fetch JOIN supplier 필터가 정확해야 한다");
        for (Product p : result) {
            assertNotNull(p.getCategory(), "JOIN FETCH가 id 투영에서 빠져도 category는 always-eager로 hydrate돼야 한다");
            assertNotNull(p.getCategory().getName());
        }
    }

    // =============================================================================================
    // D — EntityManager LockModeType / FlushMode / temporal @Version
    // =============================================================================================

    @Test
    void findWithPessimisticWriteEmitsForUpdate() {
        Wiring w = wire();
        Long id = w.base().save(new AuditDoc("locked")).map(AuditDoc::getId).block();

        w.listener().clear();
        StepVerifier.create(w.entityManager().find(AuditDoc.class, id, LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> assertEquals("locked", found.getTitle()))
                .verifyComplete();

        assertTrue(w.listener().anyMatches("for update"),
                "PESSIMISTIC_WRITE find는 FOR UPDATE를 발행해야 한다. SQL=" + w.listener().statements());
    }

    @Test
    void flushModeCommitSuppressesPreQueryFlushWhileAutoDoesNot() {
        Wiring w = wire();
        Long id1 = w.base().save(new AuditDoc("one")).map(AuditDoc::getId).block();
        Long id2 = w.base().save(new AuditDoc("two")).map(AuditDoc::getId).block();

        // FlushMode.COMMIT: id1 수정 후 id2 쿼리 시 쿼리 전 auto-flush가 억제 → UPDATE는 모든 SELECT 뒤(commit).
        w.listener().clear();
        ReactiveEntityManager committing = w.entityManager().setFlushMode(FlushModeType.COMMIT);
        assertFalse(committing == w.entityManager(), "setFlushMode는 functional하게 새 매니저를 돌려준다");
        StepVerifier.create(committing.inTransaction(e ->
                        e.find(AuditDoc.class, id1)
                                .flatMap(doc -> {
                                    doc.setTitle("dirty");
                                    return e.find(AuditDoc.class, id2);
                                })))
                .expectNextCount(1)
                .verifyComplete();

        int firstUpdate = w.listener().firstIndexMatching("update ");
        int lastSelect = w.listener().lastIndexMatching("select ");
        assertTrue(firstUpdate >= 0, "COMMIT도 commit 시점엔 flush해야 한다(변경 유실 금지). SQL=" + w.listener().statements());
        assertTrue(firstUpdate > lastSelect,
                "COMMIT은 쿼리 전 auto-flush를 억제해 UPDATE가 모든 SELECT 뒤에 와야 한다. SQL=" + w.listener().statements());
    }

    @Test
    void temporalVersionSurvivesRepeatedSessionFlushWithoutFalseOptimisticFailure() {
        // temporal @Version(LocalDateTime)을 EM 세션에서 반복 수정/flush해도 single-read/monotonic 규칙 덕에
        // 거짓 낙관락 실패 없이 성공하고, 마지막 값이 남아야 한다(회귀 방지).
        Wiring w = wire();
        Long id = w.base().save(new AuditDoc("v0")).map(AuditDoc::getId).block();

        StepVerifier.create(w.entityManager().inTransaction(e ->
                        e.find(AuditDoc.class, id)
                                .flatMap(doc -> {
                                    doc.setTitle("v1");
                                    return e.flush().thenReturn(doc);
                                })
                                .flatMap(doc -> {
                                    doc.setTitle("v2");
                                    return e.flush().thenReturn(doc);
                                })))
                .assertNext(doc -> assertEquals("v2", doc.getTitle()))
                .verifyComplete();

        StepVerifier.create(w.base().findById(AuditDoc.class, id).map(AuditDoc::getTitle))
                .expectNext("v2")
                .verifyComplete();
        // JPQL read surface도 같은 최종 값을 봐야 한다.
        assertEquals("v2", w.jpql()
                .createQuery("SELECT d FROM AuditDoc d WHERE d.id = :id", AuditDoc.class)
                .setParameter("id", id)
                .getSingleResult()
                .map(AuditDoc::getTitle)
                .block());
    }

    // =============================================================================================
    // E — @SqlResultSetMapping native 결과 → DTO / 엔티티 (A와 교차: UUID 스칼라 hydration)
    // =============================================================================================

    @Test
    void constructorResultMapsNativeJoinToDto() {
        Wiring w = wire();
        seedShop(w);

        String sql = "SELECT p.\"name\" AS \"v_pname\", c.\"name\" AS \"v_cname\", p.\"price\" AS \"v_price\" "
                + "FROM \"b2_product\" p JOIN \"b2_category\" c ON p.\"category_id\" = c.\"id\" "
                + "WHERE c.\"name\" = 'electronics' ORDER BY p.\"name\"";
        StepVerifier.create(w.mappings().queryNative(sql, "productDto"))
                .assertNext(result -> {
                    ProductView v = (ProductView) result;
                    assertEquals("laptop", v.productName());
                    assertEquals("electronics", v.categoryName());
                    assertEquals(0, v.price().compareTo(new BigDecimal("1500.00")));
                })
                .assertNext(result -> assertEquals("phone", ((ProductView) result).productName()))
                .verifyComplete();
    }

    @Test
    void columnResultMapsScalarCount() {
        Wiring w = wire();
        seedShop(w);

        String sql = "SELECT COUNT(*) AS \"cnt\" FROM \"b2_product\" WHERE \"price\" >= 900";
        StepVerifier.create(w.mappings().queryNative(sql, "productCount"))
                .assertNext(count -> assertEquals(2L, count))
                .verifyComplete();
    }

    @Test
    void entityResultHydratesUuidScalarFromNativeQuery() {
        // @EntityResult가 native 결과를 엔티티로 매핑하며 UUID @Id + UUID 스칼라 컬럼을 varchar 저장타입에서
        // 도메인 UUID로 복원해야 한다(E × A 교차).
        Wiring w = wire();
        UUID secret = UUID.randomUUID();
        UUID id = w.base().save(new Token("admin", secret)).map(Token::getId).block();
        w.base().save(new Token("guest", UUID.randomUUID())).block();

        String sql = "SELECT \"id\", \"label\" AS \"t_label\", \"secret\" FROM \"b2_token\" "
                + "WHERE \"label\" = 'admin'";
        StepVerifier.create(w.mappings().queryNative(sql, "tokenEntity"))
                .assertNext(result -> {
                    Token t = (Token) result;
                    assertEquals(id, t.getId());
                    assertEquals("admin", t.getLabel());
                    assertEquals(secret, t.getSecret(), "native @EntityResult도 UUID 스칼라를 복원해야 한다");
                })
                .verifyComplete();
    }

    // =============================================================================================
    // F — 2차 쿼리 결과 캐시: read-through 히트 + write 무효화, 그리고 JPQL/Criteria와의 정합
    // =============================================================================================

    @Test
    void queryCacheServesSecondFindAllWithoutSqlAndJpqlSeesSameData() {
        Wiring w = wire();
        seedShop(w);

        QuerySpec all = QuerySpec.empty();
        long before = w.listener().selects();
        List<Product> first = w.cached().findAll(Product.class, all).collectList().block();
        long afterFirst = w.listener().selects();
        List<Product> second = w.cached().findAll(Product.class, all).collectList().block();
        long afterSecond = w.listener().selects();

        assertEquals(4, first.size());
        assertEquals(4, second.size());
        assertTrue(afterFirst > before, "첫 findAll은 DB SELECT를 발행해야 한다");
        assertEquals(afterFirst, afterSecond, "두 번째 동일 findAll은 쿼리 캐시 히트로 SELECT를 발행하지 않아야 한다");

        // base에서 실행하는 JPQL COUNT는 캐시에 오염되지 않고 같은 데이터(4)를 본다.
        assertEquals(4L, ((Number) w.jpql().createQuery("SELECT COUNT(p) FROM Product p")
                .getSingleResult().block()).longValue());
    }

    @Test
    void entityWriteInvalidatesQueryCacheAndAllReadSurfacesAgree() {
        Wiring w = wire();
        Shop shop = seedShop(w);

        QuerySpec all = QuerySpec.empty();
        w.cached().findAll(Product.class, all).collectList().block(); // 캐시 채움(4)
        w.cached().findAll(Product.class, all).collectList().block(); // 히트

        // cached를 통한 write → 쿼리 캐시 무효화.
        w.cached().save(product("tablet", shop.electronics(), shop.acme(),
                (short) 4, 0.7f, "600")).block();

        long beforeReload = w.listener().selects();
        List<Product> reloaded = w.cached().findAll(Product.class, all).collectList().block();
        assertTrue(w.listener().selects() > beforeReload, "write 후 findAll은 캐시 미스로 DB를 다시 조회해야 한다");
        assertEquals(5, reloaded.size(), "무효화 후 findAll은 갱신된 결과(5)를 반환해야 한다");

        // JPQL/Criteria read surface(base)도 갱신된 5를 봐야 한다(stale 없음).
        assertEquals(5L, ((Number) w.jpql().createQuery("SELECT COUNT(p) FROM Product p")
                .getSingleResult().block()).longValue());

        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Object> countQ = cb.createQuery(Object.class);
        Root<Product> root = countQ.from(Product.class);
        countQ.select(cb.count(root));
        assertEquals(5L, ((Number) w.criteria().createQuery(countQ).getSingleResult().block()).longValue());
    }

    // =============================================================================================
    // 조합 — UUID @Id 엔티티를 EM으로 저장 → JPQL 조회 + entity 캐시 히트
    // =============================================================================================

    @Test
    void uuidKeyedEntityPersistedViaEntityManagerIsVisibleToJpqlAndCache() {
        Wiring w = wire();
        UUID secret = UUID.randomUUID();

        // EM persist가 UUID @Id를 생성한다(GenerationType.UUID).
        UUID id = w.entityManager().persist(new Token("root", secret)).map(Token::getId).block();
        assertNotNull(id, "EM persist가 UUID @Id를 채워야 한다");

        // JPQL 엔티티 반환으로 label 조회 → 같은 토큰(UUID 스칼라 복원 포함).
        StepVerifier.create(w.jpql()
                        .createQuery("SELECT t FROM Token t WHERE t.label = :label", Token.class)
                        .setParameter("label", "root")
                        .getSingleResult())
                .assertNext(t -> {
                    assertEquals(id, t.getId());
                    assertEquals(secret, t.getSecret());
                })
                .verifyComplete();

        // cached.findById(UUID)는 첫 조회 DB SELECT, 두 번째는 entity 캐시 히트(0 SELECT).
        long before = w.listener().selects();
        assertEquals("root", w.cached().findById(Token.class, id).map(Token::getLabel).block());
        long afterFirst = w.listener().selects();
        assertEquals("root", w.cached().findById(Token.class, id).map(Token::getLabel).block());
        long afterSecond = w.listener().selects();
        assertTrue(afterFirst > before, "첫 캐시 findById는 DB SELECT를 발행");
        assertEquals(afterFirst, afterSecond, "UUID 키 엔티티도 두 번째 findById는 캐시 히트로 SELECT 없음");
    }

    // ---------------------------------------------------------------------------------------------
    // SQL 실행 관찰 리스너
    // ---------------------------------------------------------------------------------------------

    private static final class StatementListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();
        private final AtomicLong selectCount = new AtomicLong();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            String sql = statement.sql();
            statements.add(sql);
            if (sql.stripLeading().regionMatches(true, 0, "select", 0, "select".length())) {
                selectCount.incrementAndGet();
            }
        }

        void clear() {
            statements.clear();
        }

        long selects() {
            return selectCount.get();
        }

        List<String> statements() {
            return statements;
        }

        boolean anyMatches(String needle) {
            return statements.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(s -> s.contains(needle));
        }

        int firstIndexMatching(String needle) {
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                    return i;
                }
            }
            return -1;
        }

        int lastIndexMatching(String needle) {
            int found = -1;
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                    found = i;
                }
            }
            return found;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DTO
    // ---------------------------------------------------------------------------------------------

    public record ProductView(String productName, String categoryName, BigDecimal price) {
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    @Entity
    @Table(name = "b2_category")
    public static class Category {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;

        public Category() {
        }

        public Category(String name) {
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
    @Table(name = "b2_supplier")
    public static class Supplier {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;

        public Supplier() {
        }

        public Supplier(String name) {
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
    @Table(name = "b2_product")
    @Cacheable
    @SqlResultSetMapping(name = "productDto",
            classes = @ConstructorResult(targetClass = ProductView.class,
                    columns = {@ColumnResult(name = "v_pname"),
                            @ColumnResult(name = "v_cname"),
                            @ColumnResult(name = "v_price", type = BigDecimal.class)}))
    @SqlResultSetMapping(name = "productCount", columns = @ColumnResult(name = "cnt", type = Long.class))
    public static class Product {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "sku")
        private UUID sku;
        @Column(name = "weight")
        private Float weight;
        @Column(name = "rating")
        private Short rating;
        @Column(name = "price")
        private BigDecimal price;
        @ManyToOne(targetEntity = Category.class)
        @JoinColumn(name = "category_id")
        private Category category;
        @ManyToOne(targetEntity = Supplier.class)
        @JoinColumn(name = "supplier_id")
        private Supplier supplier;

        public Product() {
        }

        public Product(String name, UUID sku, Float weight, Short rating, BigDecimal price) {
            this.name = name;
            this.sku = sku;
            this.weight = weight;
            this.rating = rating;
            this.price = price;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public UUID getSku() {
            return sku;
        }

        public Float getWeight() {
            return weight;
        }

        public Short getRating() {
            return rating;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public Category getCategory() {
            return category;
        }

        public void setCategory(Category category) {
            this.category = category;
        }

        public Supplier getSupplier() {
            return supplier;
        }

        public void setSupplier(Supplier supplier) {
            this.supplier = supplier;
        }
    }

    @Entity
    @Table(name = "b2_token")
    @Cacheable
    @SqlResultSetMapping(name = "tokenEntity",
            entities = @EntityResult(entityClass = Token.class,
                    fields = @FieldResult(name = "label", column = "t_label")))
    public static class Token {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "id")
        private UUID id;
        @Column(name = "label")
        private String label;
        @Column(name = "secret")
        private UUID secret;

        public Token() {
        }

        public Token(String label, UUID secret) {
            this.label = label;
            this.secret = secret;
        }

        public UUID getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public UUID getSecret() {
            return secret;
        }
    }

    @Entity
    @Table(name = "b2_audit_doc")
    public static class AuditDoc {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "title")
        private String title;
        @Version
        @Column(name = "version")
        private LocalDateTime version;

        public AuditDoc() {
        }

        public AuditDoc(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public LocalDateTime getVersion() {
            return version;
        }
    }
}
