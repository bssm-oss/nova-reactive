package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import io.nova.core.SqlExecutionListener;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 세션(트랜잭션 바인딩) 안에서 owning {@code @ManyToMany} link 동기화가 save 즉시가 아니라 flush로 지연되고,
 * 컬렉션이 변하지 않았으면 flush가 link table SQL을 전혀 내지 않는지(change-detect skip) H2로 검증한다.
 * 세션 밖은 현행 즉시 full-replace이며 기존 {@code ManyToManyIntegrationTest}가 보호한다.
 */
class SessionCollectionFlushIntegrationTest {
    private CapturingListener listener;
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        listener = new CapturingListener();
        support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Post.class, Tag.class).block();
    }

    private Post seedPostWithTwoTags() {
        Tag a = support.operations().save(new Tag("a")).block();
        Tag b = support.operations().save(new Tag("b")).block();
        Post post = new Post("p");
        post.getTags().add(a);
        post.getTags().add(b);
        return support.operations().save(post).block();
    }

    @Test
    void unchangedCollectionInSessionEmitsNoLinkTableSql() {
        Post seeded = seedPostWithTwoTags();

        listener.clear();
        // 세션 안에서 로드만 하고 컬렉션을 바꾸지 않으면 flush가 link table을 건드리지 않아야 한다(skip).
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Post.class, seeded.getId())))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(0, listener.linkTableWriteCount(),
                "변경 없는 컬렉션은 flush에서 link table SQL을 내지 않아야 한다: " + listener.statements());
    }

    @Test
    void collectionMutationIsFlushedAtCommit() {
        Post seeded = seedPostWithTwoTags();
        Tag c = support.operations().save(new Tag("c")).block();

        // 세션 안에서 로드한 post에 이미 영속된 tag를 추가한다. save 호출 없이도 flush가 dirty 컬렉션을 동기화해야 한다.
        // save 호출 없이 dirty 컬렉션을 mutate하고, 결과 없이(empty) 완료하는 트랜잭션 — flush가 변경을 동기화하고
        // empty 완료 트랜잭션도 commit돼야 한다.
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Post.class, seeded.getId())
                                .doOnNext(post -> post.getTags().add(c))
                                .then()))
                .verifyComplete();

        // commit 후 fresh 조회: link table에 3개 태그가 반영돼야 한다.
        StepVerifier.create(support.operations().findById(Post.class, seeded.getId()))
                .assertNext(post -> assertEquals(Set.of("a", "b", "c"),
                        post.getTags().stream().map(Tag::getName).collect(Collectors.toSet())))
                .verifyComplete();
    }

    private static final class CapturingListener implements SqlExecutionListener {
        private final java.util.List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void clear() {
            statements.clear();
        }

        java.util.List<String> statements() {
            return statements;
        }

        long linkTableWriteCount() {
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.contains("post_tag")
                            && (sql.startsWith("insert") || sql.startsWith("delete")))
                    .count();
        }
    }

    @Entity
    @Table(name = "post")
    public static class Post {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;

        @ManyToMany
        @JoinTable(name = "post_tag",
                joinColumns = @JoinColumn(name = "post_id"),
                inverseJoinColumns = @JoinColumn(name = "tag_id"))
        private Set<Tag> tags = new LinkedHashSet<>();

        public Post() {
        }

        public Post(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public Set<Tag> getTags() {
            return tags;
        }
    }

    @Entity
    @Table(name = "tag")
    public static class Tag {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public Tag() {
        }

        public Tag(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
