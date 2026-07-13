package io.nova;

import io.nova.cache.NovaCache;
import io.nova.cache.SimpleReactiveCacheProvider;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityManager;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutionListener;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.nova.spring.data.SimpleReactiveRepository;
import io.nova.spring.data.SpringDataReactiveCrudRepository;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave1 교차 기능(cross-feature) E2E. 개별 모듈 H2 통합 테스트가 이미 green인 상태에서, 여러 신규 기능이
 * <b>한 애플리케이션 흐름/한 DB</b> 위에서 함께 동작하고 서로 오염시키지 않는지를 실제 r2dbc-h2 driver로 검증한다.
 *
 * <p>대상 기능: C2 {@link ReactiveEntityManager}, C1 JPQL({@link JpqlExecutor}), C4 세션 컬렉션 flush 최소 diff,
 * C5 2차 캐시({@link NovaCache}), C6b Spring Data 표준 {@code Pageable}/{@code Sort} 브릿지.
 *
 * <p>모든 협력자(base operations, cache 데코레이터, EntityManager, JpqlExecutor, repository)는 <em>동일한</em>
 * {@link ConnectionFactory}·{@link EntityMetadataFactory}를 공유하도록 배선해 실제 상호작용을 재현한다.
 */
class Wave1CrossFeatureE2ETest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    // ------------------------------------------------------------------------------------------
    // Wiring — 한 DB 위에 5개 기능을 모두 얹은 애플리케이션 배선
    // ------------------------------------------------------------------------------------------

    private record Wiring(
            SimpleReactiveEntityOperations base,
            ReactiveEntityOperations cached,
            ReactiveEntityManager entityManager,
            JpqlExecutor jpqlOnBase,
            JpqlExecutor jpqlOnCached,
            SchemaInitializer schema,
            EntityMetadataFactory metadataFactory,
            ProductRepository productRepository,
            StatementListener listener) {
    }

    private Wiring wire() {
        int seq = DB_SEQ.incrementAndGet();
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///wave1e2e" + seq + "?options=DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        StatementListener listener = new StatementListener();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect, listener);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(cf);

        // managed transaction 배선(5번째 인자로 tx manager 직접 주입) — 세션 identity map/dirty/flush 전파.
        SimpleReactiveEntityOperations base = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        // 2차 캐시 데코레이터는 base를 감싼다(순수 additive). id 추출용 factory는 base와 동일한 것을 넘긴다.
        ReactiveEntityOperations cached =
                NovaCache.caching(base, new SimpleReactiveCacheProvider(), metadataFactory);
        // EntityManager는 production Nova.entityManager와 동일하게 base(managed) 위에 얹는다.
        ReactiveEntityManager em = new SimpleReactiveEntityManager(base, metadataFactory);
        JpqlExecutor jpqlOnBase =
                new JpqlExecutor(base, dialect, metadataFactory, Product.class, Article.class, Tag.class);
        JpqlExecutor jpqlOnCached =
                new JpqlExecutor(cached, dialect, metadataFactory, Product.class, Article.class, Tag.class);
        SchemaInitializer schema = new SimpleSchemaInitializer(base, metadataFactory, dialect);
        ProductRepository repo = newProductRepository(base);
        return new Wiring(base, cached, em, jpqlOnBase, jpqlOnCached, schema, metadataFactory, repo, listener);
    }

    private static ProductRepository newProductRepository(ReactiveEntityOperations operations) {
        SimpleReactiveRepository handler = new SimpleReactiveRepository(Product.class, Long.class, operations);
        return (ProductRepository) Proxy.newProxyInstance(
                ProductRepository.class.getClassLoader(),
                new Class<?>[]{ProductRepository.class},
                handler);
    }

    private void seedProducts(Wiring w, int count) {
        // name = prod01..prodNN, active = 짝수만 true, price = i * 10.00
        Flux<Product> inserts = Flux.range(1, count)
                .map(i -> new Product(String.format("prod%02d", i),
                        new BigDecimal(i * 10).setScale(2), i % 2 == 0))
                .concatMap(w.base()::save);
        StepVerifier.create(inserts.then()).verifyComplete();
    }

    // ==========================================================================================
    // C2 — ReactiveEntityManager: persist -> (session) dirty flush -> find
    // ==========================================================================================

    @Test
    void entityManagerPersistThenSessionDirtyFlushIsReflectedOnReload() {
        Wiring w = wire();
        w.schema().create(Product.class).block();

        StepVerifier.create(
                w.entityManager().persist(new Product("alpha", new BigDecimal("10.00"), false))
                        .flatMap(saved -> {
                            assertNotNull(saved.getId(), "IDENTITY id가 채워져야 한다");
                            return w.entityManager().inTransaction(e ->
                                            e.find(Product.class, saved.getId())
                                                    .doOnNext(p -> p.setName("alpha-renamed")))
                                    .thenReturn(saved.getId());
                        })
                        .flatMap(id -> w.entityManager().find(Product.class, id))
        ).assertNext(found -> assertEquals("alpha-renamed", found.getName(),
                "세션 안 dirty 변경은 commit auto-flush로 반영돼야 한다")).verifyComplete();
    }

    @Test
    void entityManagerIdentityMapReturnsSameInstanceWithinSession() {
        Wiring w = wire();
        w.schema().create(Product.class).block();

        StepVerifier.create(
                w.entityManager().persist(new Product("beta", new BigDecimal("20.00"), true))
                        .flatMap(saved -> w.entityManager().inTransaction(e ->
                                e.find(Product.class, saved.getId())
                                        .flatMap(first -> e.find(Product.class, saved.getId())
                                                .flatMap(second -> e.contains(first)
                                                        .map(managed -> first == second && managed)))))
        ).expectNext(Boolean.TRUE).verifyComplete();
    }

    // ==========================================================================================
    // C1 — JPQL: aggregate / bulk update / entity SELECT over the same seeded data
    // ==========================================================================================

    @Test
    void jpqlCountAndSumAggregatesOverSameData() {
        Wiring w = wire();
        w.schema().create(Product.class).block();
        seedProducts(w, 10); // active=true 5건(prod02..prod10 짝수), price=10..100

        // COUNT(e) — active=true 인 행 수
        StepVerifier.create(
                w.jpqlOnBase().createQuery("SELECT COUNT(e) FROM Product e WHERE e.active = :flag")
                        .setParameter("flag", true)
                        .getSingleResult()
                        .map(v -> ((Number) v).longValue())
        ).expectNext(5L).verifyComplete();

        // SUM(e.price) — 전체 합 = 10+20+...+100 = 550
        StepVerifier.create(
                w.jpqlOnBase().createQuery("SELECT SUM(e.price) FROM Product e")
                        .getSingleResult()
                        .map(v -> ((Number) v).longValue())
        ).expectNext(550L).verifyComplete();
    }

    @Test
    void jpqlBulkUpdateAffectsRowsAndEntitySelectReflectsIt() {
        Wiring w = wire();
        w.schema().create(Product.class).block();
        seedProducts(w, 10); // active=false 5건(prod01..prod09 홀수)

        // 벌크 UPDATE: 비활성(홀수) 5건을 활성화
        StepVerifier.create(
                w.jpqlOnBase().createQuery("UPDATE Product e SET e.active = :flag WHERE e.active = :old")
                        .setParameter("flag", true)
                        .setParameter("old", false)
                        .executeUpdate()
        ).expectNext(5L).verifyComplete();

        // 엔티티 반환 SELECT: 이제 전 10건이 active=true 여야 한다
        StepVerifier.create(
                w.jpqlOnBase().createQuery("SELECT e FROM Product e WHERE e.active = :flag ORDER BY e.name", Product.class)
                        .setParameter("flag", true)
                        .getResultList()
                        .collectList()
        ).assertNext(list -> {
            assertEquals(10, list.size(), "벌크 업데이트 후 전 10건이 active");
            assertEquals("prod01", list.get(0).getName());
            assertEquals("prod10", list.get(9).getName());
        }).verifyComplete();
    }

    @Test
    void jpqlEntitySelectSupportsFirstAndMaxResults() {
        Wiring w = wire();
        w.schema().create(Product.class).block();
        seedProducts(w, 10);

        // JPA setFirstResult/setMaxResults 등가(엔티티 반환 SELECT 전용) — 이름 정렬 후 3번째부터 3건.
        StepVerifier.create(
                w.jpqlOnBase().createQuery("SELECT e FROM Product e ORDER BY e.name", Product.class)
                        .setFirstResult(2).setMaxResults(3)
                        .getResultList()
                        .map(Product::getName)
        ).expectNext("prod03", "prod04", "prod05").verifyComplete();
    }

    // ==========================================================================================
    // C4 — session collection flush 최소 diff (@ManyToMany / @ElementCollection)
    // ==========================================================================================

    @Test
    void sessionManyToManySwapEmitsMinimalDiff() {
        Wiring w = wire();
        w.schema().create(Article.class, Tag.class).block();
        Article seeded = seedArticleWithTwoTags(w);
        Tag c = w.base().save(new Tag("c")).block();

        w.listener().clear();
        // 세션 안에서 a 제거 + c 추가 → 최소 diff: 1 DELETE + 1 INSERT(그대로인 b는 건드리지 않는다).
        StepVerifier.create(w.base().inTransaction(ops ->
                        ops.findById(Article.class, seeded.getId())
                                .doOnNext(a -> {
                                    a.getTags().removeIf(t -> t.getName().equals("a"));
                                    a.getTags().add(c);
                                })
                                .then()))
                .verifyComplete();

        assertEquals(1, w.listener().count("article_tag", "delete"),
                "제거된 link 1건만 DELETE: " + w.listener().statements());
        assertEquals(1, w.listener().count("article_tag", "insert"),
                "추가된 link 1건만 INSERT: " + w.listener().statements());

        StepVerifier.create(w.base().findById(Article.class, seeded.getId()))
                .assertNext(a -> assertEquals(Set.of("b", "c"),
                        a.getTags().stream().map(Tag::getName).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void sessionElementCollectionChangeEmitsMinimalDiff() {
        Wiring w = wire();
        w.schema().create(Article.class, Tag.class).block();

        Article article = new Article("doc");
        article.getLabels().add("x");
        article.getLabels().add("y");
        Long id = w.base().save(article).map(Article::getId).block();

        w.listener().clear();
        // 세션 안에서 x 제거 + z 추가 → 최소 diff: 1 DELETE + 1 INSERT.
        StepVerifier.create(w.base().inTransaction(ops ->
                        ops.findById(Article.class, id)
                                .doOnNext(a -> {
                                    a.getLabels().remove("x");
                                    a.getLabels().add("z");
                                })
                                .then()))
                .verifyComplete();

        assertEquals(1, w.listener().count("article_labels", "delete"),
                "제거된 값 1건만 DELETE: " + w.listener().statements());
        assertEquals(1, w.listener().count("article_labels", "insert"),
                "추가된 값 1건만 INSERT: " + w.listener().statements());

        StepVerifier.create(w.base().findById(Article.class, id))
                .assertNext(a -> assertEquals(Set.of("y", "z"), a.getLabels()))
                .verifyComplete();
    }

    // ==========================================================================================
    // C5 — 2차 캐시: findById 히트(0 SQL) -> save evict -> DB 재조회
    // ==========================================================================================

    @Test
    void cacheHitServesFindByIdWithoutSqlThenSaveEvicts() {
        Wiring w = wire();
        w.schema().create(Product.class).block();
        Long id = w.cached().save(new Product("alpha", new BigDecimal("10.00"), true)).block().getId();

        long afterSave = w.listener().selects();
        Product first = w.cached().findById(Product.class, id).block();
        long afterFirst = w.listener().selects();
        Product second = w.cached().findById(Product.class, id).block();
        long afterSecond = w.listener().selects();

        assertEquals("alpha", first.getName());
        assertEquals("alpha", second.getName());
        assertTrue(afterFirst > afterSave, "첫 findById는 DB SELECT를 발행");
        assertEquals(afterFirst, afterSecond, "두 번째 findById는 캐시 히트로 SELECT 없음");

        // id 지정 save → UPDATE 경로 + evict
        w.cached().save(new Product(id, "beta", true)).block();
        long beforeReload = w.listener().selects();
        Product reloaded = w.cached().findById(Product.class, id).block();

        assertTrue(w.listener().selects() > beforeReload, "save 후 findById는 캐시 미스로 DB 재조회");
        assertEquals("beta", reloaded.getName(), "무효화 후 조회는 갱신 값을 반환");
    }

    // ==========================================================================================
    // C6b — Spring Data 표준 Pageable/Sort 브릿지
    // ==========================================================================================

    @Test
    void springDataPageableReturnsSortedSpringPage() {
        Wiring w = wire();
        w.schema().create(Product.class).block();
        seedProducts(w, 10);

        Pageable pageable = PageRequest.of(1, 3, Sort.by(Sort.Order.asc("name")));
        StepVerifier.create(w.productRepository().findAll(pageable))
                .assertNext(page -> {
                    assertEquals(3, page.getContent().size());
                    assertEquals(10L, page.getTotalElements());
                    assertEquals(4, page.getTotalPages(), "ceil(10/3)");
                    assertEquals(1, page.getNumber());
                    assertTrue(page.hasNext());
                    assertTrue(page.hasPrevious());
                    // page 1(0-based) asc name → prod04, prod05, prod06
                    assertEquals("prod04", page.getContent().get(0).getName());
                    assertEquals("prod06", page.getContent().get(2).getName());
                })
                .verifyComplete();
    }

    @Test
    void springDataSortReturnsSortedFlux() {
        Wiring w = wire();
        w.schema().create(Product.class).block();
        seedProducts(w, 10);

        StepVerifier.create(w.productRepository().findAll(Sort.by(Sort.Order.desc("name"))).map(Product::getName))
                .expectNext("prod10", "prod09", "prod08", "prod07", "prod06",
                        "prod05", "prod04", "prod03", "prod02", "prod01")
                .verifyComplete();
    }

    // ==========================================================================================
    // 기능 조합(interaction) — 신규 기능들이 서로 우회/오염하지 않는지
    // ==========================================================================================

    @Test
    void scalarJpqlThroughCachedOpsDoesNotPolluteIdCache() {
        // 캐시 데코레이터 위에서 스칼라 JPQL을 실행해도 id-cache를 채우지 않아야 한다(queryNative 우회).
        // 이후 findById가 여전히 캐시 미스로 SELECT를 발행하면 오염이 없다는 증거다.
        Wiring w = wire();
        w.schema().create(Product.class).block();
        Long id = w.base().save(new Product("alpha", new BigDecimal("10.00"), true)).block().getId();

        // 캐시 위에서 스칼라 SELECT(엔티티가 아닌 단일 컬럼) 실행
        StepVerifier.create(
                w.jpqlOnCached().createQuery("SELECT e.name FROM Product e WHERE e.id = :id")
                        .setParameter("id", id)
                        .getSingleResult()
                        .map(Object::toString)
        ).expectNext("alpha").verifyComplete();

        long before = w.listener().selects();
        Product loaded = w.cached().findById(Product.class, id).block();
        assertTrue(w.listener().selects() > before,
                "스칼라 JPQL이 id-cache를 채웠다면 이 findById가 히트해 SELECT가 없었을 것");
        assertEquals("alpha", loaded.getName());
    }

    @Test
    void jpqlBulkUpdateThroughCachedOpsEvictsSoNoStaleRead() {
        // 캐시를 채운 뒤 JPQL 벌크 UPDATE를 캐시 데코레이터 위에서 실행하면(executeNative→clearAll) 캐시가
        // 무효화돼, 이후 findById가 미스로 DB의 갱신 값을 읽어야 한다(stale 없음).
        Wiring w = wire();
        w.schema().create(Product.class).block();
        Long id = w.cached().save(new Product("alpha", new BigDecimal("10.00"), true)).block().getId();

        w.cached().findById(Product.class, id).block();                 // 캐시 채움
        long afterWarm = w.listener().selects();
        w.cached().findById(Product.class, id).block();                 // 히트 확인
        assertEquals(afterWarm, w.listener().selects(), "캐시가 채워졌는지 사전 확인(2번째는 히트)");

        // 캐시 위에서 벌크 UPDATE — executeNative는 clearAll로 이어진다.
        StepVerifier.create(
                w.jpqlOnCached().createQuery("UPDATE Product e SET e.name = :n WHERE e.id = :id")
                        .setParameter("n", "beta")
                        .setParameter("id", id)
                        .executeUpdate()
        ).expectNext(1L).verifyComplete();

        long beforeReload = w.listener().selects();
        Product reloaded = w.cached().findById(Product.class, id).block();
        assertTrue(w.listener().selects() > beforeReload,
                "벌크 UPDATE 후 findById는 캐시 미스로 DB 재조회해야 한다(stale 금지)");
        assertEquals("beta", reloaded.getName(), "무효화 후 갱신 값(beta)을 반환해야 한다");
    }

    @Test
    void entityManagerDirtyFlushAndCollectionDiffApplyTogether() {
        // 한 세션에서 스칼라 dirty(EntityManager)와 컬렉션 변경이 함께 발생할 때, commit auto-flush가
        // 스칼라 UPDATE와 최소 컬렉션 diff를 모두 올바르게 발행해야 한다.
        Wiring w = wire();
        w.schema().create(Article.class, Tag.class).block();
        Article seeded = seedArticleWithTwoTags(w);
        Tag c = w.base().save(new Tag("c")).block();

        w.listener().clear();
        StepVerifier.create(
                w.entityManager().inTransaction(e ->
                        e.find(Article.class, seeded.getId())
                                .doOnNext(a -> {
                                    a.setTitle("post-renamed");   // 스칼라 dirty
                                    a.getTags().add(c);           // 컬렉션 add
                                })
                                .then())
        ).verifyComplete();

        assertEquals(1, w.listener().count("article_tag", "insert"),
                "추가된 link 1건만 INSERT: " + w.listener().statements());
        assertEquals(0, w.listener().count("article_tag", "delete"),
                "추가만 했으므로 DELETE 없음: " + w.listener().statements());
        assertTrue(w.listener().count("article", "update") >= 1,
                "스칼라 dirty 변경으로 article UPDATE가 발행돼야 한다: " + w.listener().statements());

        StepVerifier.create(w.base().findById(Article.class, seeded.getId()))
                .assertNext(a -> {
                    assertEquals("post-renamed", a.getTitle(), "스칼라 변경 반영");
                    assertEquals(Set.of("a", "b", "c"),
                            a.getTags().stream().map(Tag::getName).collect(Collectors.toSet()),
                            "컬렉션 변경 반영");
                })
                .verifyComplete();
    }

    private Article seedArticleWithTwoTags(Wiring w) {
        Tag a = w.base().save(new Tag("a")).block();
        Tag b = w.base().save(new Tag("b")).block();
        Article article = new Article("doc");
        article.getTags().add(a);
        article.getTags().add(b);
        return w.base().save(article).block();
    }

    // ==========================================================================================
    // SQL 실행 관찰 리스너 — SELECT 카운트 + 테이블/op 별 문장 수
    // ==========================================================================================

    private static final class StatementListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();
        private final AtomicLong selectCount = new AtomicLong();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
            if (statement.sql().stripLeading().regionMatches(true, 0, "select", 0, "select".length())) {
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

        /** 주어진 테이블 이름을 언급하며 {@code op}(insert/delete/update)로 시작하는 SQL 문 개수. */
        long count(String table, String op) {
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.contains(table) && sql.startsWith(op))
                    .count();
        }
    }

    // ==========================================================================================
    // Fixtures
    // ==========================================================================================

    @Entity
    @Table(name = "e2e_product")
    @Cacheable
    public static class Product {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "price")
        private BigDecimal price;

        @Column(name = "active")
        private boolean active;

        public Product() {
        }

        public Product(String name, BigDecimal price, boolean active) {
            this.name = name;
            this.price = price;
            this.active = active;
        }

        public Product(Long id, String name, boolean active) {
            this.id = id;
            this.name = name;
            this.active = active;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }

    @Entity
    @Table(name = "e2e_article")
    public static class Article {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "title")
        private String title;

        @ManyToMany
        @JoinTable(name = "article_tag",
                joinColumns = @JoinColumn(name = "article_id"),
                inverseJoinColumns = @JoinColumn(name = "tag_id"))
        private Set<Tag> tags = new LinkedHashSet<>();

        @ElementCollection
        private Set<String> labels = new LinkedHashSet<>();

        public Article() {
        }

        public Article(String title) {
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

        public Set<Tag> getTags() {
            return tags;
        }

        public Set<String> getLabels() {
            return labels;
        }
    }

    @Entity
    @Table(name = "e2e_tag")
    public static class Tag {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        public Tag() {
        }

        public Tag(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    interface ProductRepository extends SpringDataReactiveCrudRepository<Product, Long> {
    }
}
