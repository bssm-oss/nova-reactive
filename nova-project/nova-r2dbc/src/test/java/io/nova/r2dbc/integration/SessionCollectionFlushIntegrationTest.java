package io.nova.r2dbc.integration;

import jakarta.persistence.ElementCollection;
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
    void addingOneTagInSessionEmitsSingleInsertNoDelete() {
        Post seeded = seedPostWithTwoTags();
        Tag c = support.operations().save(new Tag("c")).block();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Post.class, seeded.getId())
                                .doOnNext(post -> post.getTags().add(c))
                                .then()))
                .verifyComplete();

        // Stage 2 최소 diff: 추가된 link 1건만 INSERT, DELETE 없음(full-replace라면 2 DELETE + 3 INSERT였을 것).
        assertEquals(1, listener.count("post_tag", "insert"),
                "추가된 link 1건만 INSERT 되어야 한다: " + listener.statements());
        assertEquals(0, listener.count("post_tag", "delete"),
                "추가만 했으므로 DELETE는 없어야 한다: " + listener.statements());
    }

    @Test
    void removingOneTagInSessionEmitsSingleDeleteNoInsert() {
        Post seeded = seedPostWithTwoTags();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Post.class, seeded.getId())
                                .doOnNext(post -> post.getTags().removeIf(tag -> tag.getName().equals("a")))
                                .then()))
                .verifyComplete();

        // Stage 2 최소 diff: 제거된 link 1건만 DELETE, INSERT 없음.
        assertEquals(1, listener.count("post_tag", "delete"),
                "제거된 link 1건만 DELETE 되어야 한다: " + listener.statements());
        assertEquals(0, listener.count("post_tag", "insert"),
                "제거만 했으므로 INSERT는 없어야 한다: " + listener.statements());

        // commit 후 fresh 조회: 남은 태그는 b 하나.
        StepVerifier.create(support.operations().findById(Post.class, seeded.getId()))
                .assertNext(post -> assertEquals(Set.of("b"),
                        post.getTags().stream().map(Tag::getName).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void swappingOneTagInSessionEmitsOneDeleteOneInsert() {
        Post seeded = seedPostWithTwoTags();
        Tag c = support.operations().save(new Tag("c")).block();

        listener.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Post.class, seeded.getId())
                                .doOnNext(post -> {
                                    post.getTags().removeIf(tag -> tag.getName().equals("a"));
                                    post.getTags().add(c);
                                })
                                .then()))
                .verifyComplete();

        // Stage 2 최소 diff: a 제거 1 DELETE + c 추가 1 INSERT(그대로 남은 b는 건드리지 않는다).
        assertEquals(1, listener.count("post_tag", "delete"),
                "제거된 link 1건만 DELETE 되어야 한다: " + listener.statements());
        assertEquals(1, listener.count("post_tag", "insert"),
                "추가된 link 1건만 INSERT 되어야 한다: " + listener.statements());

        StepVerifier.create(support.operations().findById(Post.class, seeded.getId()))
                .assertNext(post -> assertEquals(Set.of("b", "c"),
                        post.getTags().stream().map(Tag::getName).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void elementCollectionAddRemoveInSessionEmitsMinimalSql() {
        // 이 테스트만 쓰는 @ElementCollection Set 엔티티의 스키마를 추가로 만든다.
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Member.class).block();

        Member member = new Member("m");
        member.getNicknames().add("x");
        member.getNicknames().add("y");
        Long id = support.operations().save(member).map(Member::getId).block();

        listener.clear();
        // 세션 안에서 로드 후 x 제거 + z 추가 → Stage 3 최소 diff.
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Member.class, id)
                                .doOnNext(loaded -> {
                                    loaded.getNicknames().remove("x");
                                    loaded.getNicknames().add("z");
                                })
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("member_nicknames", "delete"),
                "제거된 값 1건만 DELETE 되어야 한다: " + listener.statements());
        assertEquals(1, listener.count("member_nicknames", "insert"),
                "추가된 값 1건만 INSERT 되어야 한다: " + listener.statements());

        // commit 후 fresh 조회: {y, z}.
        StepVerifier.create(support.operations().findById(Member.class, id))
                .assertNext(loaded -> assertEquals(Set.of("y", "z"), loaded.getNicknames()))
                .verifyComplete();
    }

    @Test
    void nonStringBasicElementCollectionAddRemoveInSessionEmitsMinimalSql() {
        // 컨버터 read-source-type 함정 방어: 기존 커버리지는 @ElementCollection delete를 String 원소로만
        // 검증했다. non-String basic(Integer) 원소로 in-session remove+add 시에도 세션 diff가 값 동등성으로
        // 최소 diff(1 DELETE + 1 INSERT)를 내고, commit 후 조회가 원소를 Integer로 정확히 복원(하이드레이션)하는지
        // 검증한다 — 저장 컬럼 타입(integer)에서 도메인 타입(Integer)로의 왕복이 diff/decode 모두에서 올바라야 한다.
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Scorecard.class).block();

        Scorecard card = new Scorecard("s");
        card.getScores().add(10);
        card.getScores().add(20);
        Long id = support.operations().save(card).map(Scorecard::getId).block();

        listener.clear();
        // 세션 안에서 10 제거 + 30 추가 → 최소 diff(그대로 남은 20은 건드리지 않는다).
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Scorecard.class, id)
                                .doOnNext(loaded -> {
                                    loaded.getScores().remove(Integer.valueOf(10));
                                    loaded.getScores().add(30);
                                })
                                .then()))
                .verifyComplete();

        assertEquals(1, listener.count("scorecard_scores", "delete"),
                "제거된 Integer 값 1건만 DELETE 되어야 한다: " + listener.statements());
        assertEquals(1, listener.count("scorecard_scores", "insert"),
                "추가된 Integer 값 1건만 INSERT 되어야 한다: " + listener.statements());

        // commit 후 fresh 조회: {20, 30} — 원소가 Integer로 정확히 복원돼야 한다.
        StepVerifier.create(support.operations().findById(Scorecard.class, id))
                .assertNext(loaded -> assertEquals(Set.of(20, 30), loaded.getScores()))
                .verifyComplete();
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

        /** 주어진 테이블 이름을 언급하며 {@code op}(insert/delete)로 시작하는 SQL 문 개수. */
        long count(String table, String op) {
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.contains(table) && sql.startsWith(op))
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

    @Entity
    @Table(name = "member")
    public static class Member {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @ElementCollection
        private Set<String> nicknames = new LinkedHashSet<>();

        public Member() {
        }

        public Member(String label) {
            this.label = label;
        }

        public Long getId() {
            return id;
        }

        public Set<String> getNicknames() {
            return nicknames;
        }
    }

    @Entity
    @Table(name = "scorecard")
    public static class Scorecard {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @ElementCollection
        private Set<Integer> scores = new LinkedHashSet<>();

        public Scorecard() {
        }

        public Scorecard(String label) {
            this.label = label;
        }

        public Long getId() {
            return id;
        }

        public Set<Integer> getScores() {
            return scores;
        }
    }
}
