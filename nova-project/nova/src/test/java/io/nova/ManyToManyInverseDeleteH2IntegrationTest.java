package io.nova;

import io.nova.core.ReactiveEntityOperations;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 양방향 {@code @ManyToMany}에서 inverse-side 엔티티를 hard delete 할 때, 그 엔티티를 target 으로 가리키는 owner
 * 의 link 행이 정리되는지를 실제 R2DBC H2 driver 로 검증한다. link table 에 enforced {@code @ForeignKey}
 * (inverse 컬럼 → inverse 엔티티 PK)가 걸려 있으므로, inverse 정리가 owner 행 DELETE 보다 먼저 일어나지 않으면
 * driver 가 FK 위반으로 삭제를 거부한다 → 삭제가 성공한다는 사실 자체가 inverse link 정리와 순서를 증명한다.
 * versioned inverse 엔티티의 stale delete 가 link 를 먼저 지운 뒤 owner DELETE 에서 실패해 부분 커밋되는 것을
 * 막는지도 확인한다(비트랜잭션 autocommit). 데이터베이스는 메모리 H2(독립 DB per test).
 */
class ManyToManyInverseDeleteH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///m2minversedelete" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    private Mono<Long> countLinks(ReactiveEntityOperations operations, String table, String column, long value) {
        return operations.queryNativeOne(
                NativeQuery.of("select count(*) as c from \"" + table + "\" where \"" + column + "\" = " + value),
                row -> row.get("c", Long.class));
    }

    private Mono<Long> countRows(ReactiveEntityOperations operations, String table) {
        return operations.queryNativeOne(
                NativeQuery.of("select count(*) as c from \"" + table + "\""),
                row -> row.get("c", Long.class));
    }

    @Test
    void deletingInverseSideEntityCleansUpOwnerLinkRows() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // author 2건, book 1건, link 2건((10,1),(10,2))을 native 로 심는다. book(owning)의 @JoinTable 이
        // book_author 테이블과 양쪽 FK 를 생성한다.
        Mono<Long> setup = schema.create(List.of(Author.class, Book.class))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_author\" (\"id\", \"name\") values (1, 'a1')")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_author\" (\"id\", \"name\") values (2, 'a2')")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_book\" (\"id\", \"title\") values (10, 'b1')")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_book_author\" (\"book_id\", \"author_id\") values (10, 1)")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_book_author\" (\"book_id\", \"author_id\") values (10, 2)")));

        // inverse-side author(id=1) 삭제 → author_id=1 link 행이 먼저 정리되어야 FK 위반 없이 author 행이 지워진다.
        Author a1 = new Author(1L);
        StepVerifier.create(setup.then(operations.delete(a1)))
                .expectNext(1L).verifyComplete();

        // author 1 을 가리키던 link 행은 사라지고, author 2 의 link 행은 그대로다.
        StepVerifier.create(countLinks(operations, "m2m_book_author", "author_id", 1L))
                .expectNext(0L).verifyComplete();
        StepVerifier.create(countLinks(operations, "m2m_book_author", "author_id", 2L))
                .expectNext(1L).verifyComplete();
        // author 테이블은 1건(a2)만 남고, book 은 그대로 1건이다.
        StepVerifier.create(countRows(operations, "m2m_author")).expectNext(1L).verifyComplete();
        StepVerifier.create(countRows(operations, "m2m_book")).expectNext(1L).verifyComplete();
    }

    @Test
    void deletingInverseSideByIdCleansUpOwnerLinkRows() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        Mono<Long> setup = schema.create(List.of(Author.class, Book.class))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_author\" (\"id\", \"name\") values (1, 'a1')")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_book\" (\"id\", \"title\") values (10, 'b1')")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_book_author\" (\"book_id\", \"author_id\") values (10, 1)")));

        // deleteById 경로도 동일하게 inverse link 를 정리한다(FK 위반 없이 삭제 성공).
        StepVerifier.create(setup.then(operations.deleteById(Author.class, 1L)))
                .expectNext(1L).verifyComplete();

        StepVerifier.create(countLinks(operations, "m2m_book_author", "author_id", 1L))
                .expectNext(0L).verifyComplete();
        StepVerifier.create(countRows(operations, "m2m_author")).expectNext(0L).verifyComplete();
    }

    @Test
    void versionedInverseStaleDeleteDoesNotPartiallyCommitLinkCleanup() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // versioned inverse author(ver=0)와 그를 가리키는 link 행 1건을 심는다.
        Mono<Long> setup = schema.create(List.of(VersionedAuthor.class, VersionedBook.class))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_vauthor\" (\"id\", \"ver\") values (1, 0)")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_vbook\" (\"id\") values (10)")))
                .then(operations.executeNative(NativeQuery.of(
                        "insert into \"m2m_vbook_author\" (\"book_id\", \"author_id\") values (10, 1)")));

        // stale 버전(999999)으로 delete → inverse link 정리는 비가역 pre-owner 작업이므로, 먼저 (id, version)
        // 선검증이 실패해 어떤 DELETE 도 발행되지 않는다(부분 커밋 방지).
        VersionedAuthor stale = new VersionedAuthor(1L);
        stale.version = 999_999L;
        StepVerifier.create(setup.then(operations.delete(stale)))
                .verifyError(OptimisticLockingFailureException.class);

        // link 행과 author 행 모두 보존되어야 한다(정리 SQL 이 커밋된 뒤 owner DELETE 가 실패하는 부분 커밋이 아님).
        StepVerifier.create(countLinks(operations, "m2m_vbook_author", "author_id", 1L))
                .expectNext(1L).verifyComplete();
        StepVerifier.create(countRows(operations, "m2m_vauthor")).expectNext(1L).verifyComplete();
    }

    @Entity
    @Table(name = "m2m_author")
    public static class Author {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;

        @ManyToMany(mappedBy = "authors", targetEntity = Book.class)
        private Set<Book> books;

        public Author() {
        }

        public Author(Long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "m2m_book")
    public static class Book {
        @Id
        private Long id;
        @Column(name = "title")
        private String title;

        @ManyToMany(targetEntity = Author.class)
        @JoinTable(
                name = "m2m_book_author",
                joinColumns = @JoinColumn(name = "book_id"),
                inverseJoinColumns = @JoinColumn(name = "author_id"),
                foreignKey = @ForeignKey(name = "fk_ba_book"),
                inverseForeignKey = @ForeignKey(name = "fk_ba_author"))
        private Set<Author> authors;

        public Book() {
        }
    }

    @Entity
    @Table(name = "m2m_vauthor")
    public static class VersionedAuthor {
        @Id
        private Long id;
        @Version
        @Column(name = "ver")
        private Long version;

        @ManyToMany(mappedBy = "authors", targetEntity = VersionedBook.class)
        private Set<VersionedBook> books;

        public VersionedAuthor() {
        }

        public VersionedAuthor(Long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "m2m_vbook")
    public static class VersionedBook {
        @Id
        private Long id;

        @ManyToMany(targetEntity = VersionedAuthor.class)
        @JoinTable(
                name = "m2m_vbook_author",
                joinColumns = @JoinColumn(name = "book_id"),
                inverseJoinColumns = @JoinColumn(name = "author_id"),
                foreignKey = @ForeignKey(name = "fk_vba_book"),
                inverseForeignKey = @ForeignKey(name = "fk_vba_author"))
        private Set<VersionedAuthor> authors;

        public VersionedBook() {
        }
    }
}
