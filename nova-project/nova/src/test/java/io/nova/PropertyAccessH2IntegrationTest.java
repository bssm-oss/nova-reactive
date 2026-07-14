package io.nova;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import io.nova.core.ReactiveEntityOperations;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Access(AccessType.PROPERTY)} 엔티티가 실제 r2dbc-h2 driver 위에서 save/findById full
 * round-trip 되는지 검증한다 — 상태 read/write가 field가 아니라 JavaBean getter/setter를 경유하는지까지.
 *
 * <p>엔티티는 private 필드를 직접 노출하지 않고(state는 getter/setter로만 도달 가능) sentinel 필드로
 * getter/setter 호출 여부를 기록해, Nova의 binding/hydration이 PROPERTY access 경로를 탔는지 고정한다.
 */
class PropertyAccessH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///propaccess" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    @Test
    void classLevelPropertyAccessEntitySavesAndLoadsViaAccessors() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        PropertyAccessAccount toSave = new PropertyAccessAccount("alice@example.com");

        StepVerifier.create(
                schema.create(PropertyAccessAccount.class)
                        .then(operations.save(toSave))
                        .flatMap(saved -> {
                            // IDENTITY id는 INSERT 후 setter로 다시 주입된다.
                            assertNotNull(saved.getId(), "save 후 IDENTITY id가 채워져 있어야 한다");
                            return operations.findById(PropertyAccessAccount.class, saved.getId());
                        })
        ).assertNext(loaded -> {
            assertNotNull(loaded);
            assertNotNull(loaded.getId());
            assertEquals("alice@example.com", loaded.getEmail());
            // findById의 row hydration이 setter를 거쳐 상태를 채웠어야 한다.
            assertTrue(loaded.emailSetterInvoked, "findById hydration은 setter를 경유해야 한다");
        }).verifyComplete();
    }

    @Test
    void memberLevelPropertyOverrideRoundTrips() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        MixedAccessAccount toSave = new MixedAccessAccount("field-val", "prop-val");

        StepVerifier.create(
                schema.create(MixedAccessAccount.class)
                        .then(operations.save(toSave))
                        .flatMap(saved -> {
                            assertNotNull(saved.getId());
                            return operations.findById(MixedAccessAccount.class, saved.getId());
                        })
        ).assertNext(loaded -> {
            assertNotNull(loaded.getId());
            assertEquals("field-val", loaded.fieldMappedDirect());
            assertEquals("prop-val", loaded.getPropertyMapped());
            assertTrue(loaded.propertySetterInvoked,
                    "PROPERTY override 컬럼은 setter를 경유해 채워져야 한다");
        }).verifyComplete();
    }

    @Test
    void classLevelPropertyAccessManyToOneRelationRoundTripsViaAccessors() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        Blog blog = new Blog("nova-blog");
        PropertyAccessArticle article = new PropertyAccessArticle("hello");

        StepVerifier.create(
                schema.create(Blog.class)
                        .then(schema.create(PropertyAccessArticle.class))
                        .then(operations.save(blog))
                        .flatMap(savedBlog -> {
                            assertNotNull(savedBlog.getId());
                            article.setBlog(savedBlog);
                            return operations.save(article);
                        })
                        .flatMap(savedArticle -> {
                            // owner INSERT는 FK 값을 관계 getter로 읽어 바인딩해야 한다(field 직접접근 아님).
                            assertTrue(article.blogGetterInvoked,
                                    "save의 FK 바인딩은 관계 getter를 경유해야 한다");
                            return operations.findById(PropertyAccessArticle.class, savedArticle.getId());
                        })
        ).assertNext(loaded -> {
            assertNotNull(loaded.getId());
            assertEquals("hello", loaded.getTitle());
            assertNotNull(loaded.getBlog(), "@ManyToOne는 findById에서 hydrate되어야 한다");
            assertEquals(blog.getId(), loaded.getBlog().getId());
            // row 디코딩/hydration이 관계 setter를 경유해야 한다(field 직접접근 아님).
            assertTrue(loaded.blogSetterInvoked, "관계 hydration은 setter를 경유해야 한다");
        }).verifyComplete();
    }

    @Entity
    @Table(name = "property_access_accounts")
    @Access(AccessType.PROPERTY)
    public static class PropertyAccessAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "email")
        private String email;

        public transient boolean emailSetterInvoked;

        public PropertyAccessAccount() {
        }

        public PropertyAccessAccount(String email) {
            this.email = email;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.emailSetterInvoked = true;
            this.email = email;
        }
    }

    @Entity
    @Table(name = "mixed_access_accounts")
    public static class MixedAccessAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        // @Access 없음 → 엔티티 기본(FIELD) 접근.
        @Column(name = "field_mapped")
        private String fieldMapped;

        // 멤버 레벨 override → PROPERTY 접근.
        @Access(AccessType.PROPERTY)
        @Column(name = "property_mapped")
        private String propertyMapped;

        public transient boolean propertySetterInvoked;

        public MixedAccessAccount() {
        }

        public MixedAccessAccount(String fieldMapped, String propertyMapped) {
            this.fieldMapped = fieldMapped;
            this.propertyMapped = propertyMapped;
        }

        public Long getId() {
            return id;
        }

        // FIELD-access 컬럼은 getter/setter가 없어도 동작해야 한다(테스트 검증용 reader만 둔다).
        String fieldMappedDirect() {
            return fieldMapped;
        }

        public String getPropertyMapped() {
            return propertyMapped;
        }

        public void setPropertyMapped(String propertyMapped) {
            this.propertySetterInvoked = true;
            this.propertyMapped = propertyMapped;
        }
    }

    // --- relation @Access(PROPERTY) fixtures --------------------------------

    @Entity
    @Table(name = "property_access_blogs")
    public static class Blog {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        public Blog() {
        }

        public Blog(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "property_access_articles")
    @Access(AccessType.PROPERTY)
    public static class PropertyAccessArticle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "title")
        private String title;

        @ManyToOne
        @JoinColumn(name = "blog_id")
        private Blog blog;

        public transient boolean blogGetterInvoked;
        public transient boolean blogSetterInvoked;

        public PropertyAccessArticle() {
        }

        public PropertyAccessArticle(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Blog getBlog() {
            this.blogGetterInvoked = true;
            return blog;
        }

        public void setBlog(Blog blog) {
            this.blogSetterInvoked = true;
            this.blog = blog;
        }
    }
}
