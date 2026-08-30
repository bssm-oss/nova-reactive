package io.nova.schema;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutor;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.sql.AbstractSchemaGenerator;
import io.nova.sql.AbstractSqlRenderer;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.SingleIndexEntity;
import io.nova.tx.ReactiveTransactionOperations;
import io.nova.tx.TransactionContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SimpleSchemaInitializer가 SchemaGenerator와 ReactiveEntityOperations.executeNative를
 * 어떤 순서로 호출하는지 검증한다 — driver와 무관한 SQL string assertion.
 * H2 통합 검증은 nova-project/nova/의 SchemaInitializerH2IntegrationTest에서 수행.
 */
class SimpleSchemaInitializerTest {

    private final Dialect dialect = new TestDialect();
    private final EntityMetadataFactory metadataFactory =
            new EntityMetadataFactory(new DefaultNamingStrategy());
    private final RecordingExecutor executor = new RecordingExecutor();
    private final ReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
            metadataFactory,
            dialect,
            executor,
            new EntityStateDetector(),
            new NoopTransactions());
    private final SchemaInitializer initializer =
            new SimpleSchemaInitializer(operations, metadataFactory, dialect);

    @Test
    void createSingleEntityUsesCreateTableIfNotExistsByDefault() {
        StepVerifier.create(initializer.create(SampleAccount.class)).verifyComplete();

        assertEquals(1, executor.executed.size());
        assertEquals(
                "create table if not exists accounts (id bigint primary key, email_address varchar(255), active boolean not null)",
                executor.executed.get(0));
    }

    @Test
    void createWithIfNotExistsFalseUsesRawCreateTable() {
        StepVerifier.create(
                initializer.create(SampleAccount.class, SchemaOptions.defaults().withIfNotExists(false))
        ).verifyComplete();

        assertEquals(1, executor.executed.size());
        assertEquals(
                "create table accounts (id bigint primary key, email_address varchar(255), active boolean not null)",
                executor.executed.get(0));
    }

    @Test
    void createIncludesIndexesAfterCreateTableByDefault() {
        StepVerifier.create(initializer.create(SingleIndexEntity.class)).verifyComplete();

        assertEquals(2, executor.executed.size());
        // 첫 SQL은 CREATE TABLE IF NOT EXISTS, 두 번째는 인덱스 — 순서가 중요.
        assertEquals(
                "create table if not exists indexed_accounts (id bigint primary key, email varchar(255))",
                executor.executed.get(0));
        assertEquals(
                "create index ix_indexed_email on indexed_accounts (email)",
                executor.executed.get(1));
    }

    @Test
    void createWithIncludeIndexesFalseSkipsIndexDdl() {
        StepVerifier.create(
                initializer.create(SingleIndexEntity.class, SchemaOptions.defaults().withIncludeIndexes(false))
        ).verifyComplete();

        assertEquals(1, executor.executed.size());
        assertEquals(
                "create table if not exists indexed_accounts (id bigint primary key, email varchar(255))",
                executor.executed.get(0));
    }

    @Test
    void createVarargsRunsSequentiallyInGivenOrder() {
        StepVerifier.create(
                initializer.create(AuthorWithBooksAnnotated.class, BookWithAuthorAnnotated.class)
        ).verifyComplete();

        assertEquals(2, executor.executed.size());
        // parent 먼저, child 그 다음 — caller가 준 순서 보존.
        assertEquals(
                "create table if not exists annotated_authors (id bigint primary key, name varchar(255))",
                executor.executed.get(0));
        assertEquals(
                "create table if not exists annotated_books (id bigint primary key, title varchar(255), author_id bigint)",
                executor.executed.get(1));
    }

    @Test
    void dropSingleEntityUsesDropTableIfExistsByDefault() {
        StepVerifier.create(initializer.drop(SampleAccount.class)).verifyComplete();

        assertEquals(1, executor.executed.size());
        assertEquals("drop table if exists accounts", executor.executed.get(0));
    }

    @Test
    void dropWithIfNotExistsFalseUsesRawDropTable() {
        StepVerifier.create(
                initializer.drop(SampleAccount.class, SchemaOptions.defaults().withIfNotExists(false))
        ).verifyComplete();

        assertEquals(1, executor.executed.size());
        assertEquals("drop table accounts", executor.executed.get(0));
    }

    @Test
    void recreateDropsThenCreatesWithoutIfNotExistsOnCreate() {
        StepVerifier.create(initializer.recreate(SampleAccount.class)).verifyComplete();

        assertEquals(2, executor.executed.size());
        // 1) drop IF EXISTS (드롭은 idempotent이어야 stale state 정리 가능)
        assertEquals("drop table if exists accounts", executor.executed.get(0));
        // 2) raw CREATE TABLE — stale 누수가 있으면 실패하도록 명시적 raw 사용
        assertEquals(
                "create table accounts (id bigint primary key, email_address varchar(255), active boolean not null)",
                executor.executed.get(1));
    }

    @Test
    void recreateBatchDropsChildrenFirstAndCreatesParentsFirst() {
        StepVerifier.create(
                initializer.recreate(AuthorWithBooksAnnotated.class, BookWithAuthorAnnotated.class)
        ).verifyComplete();

        assertEquals(4, executor.executed.size());
        // FK 안전: drop은 child→parent 역순, create는 parent→child 정순.
        assertEquals("drop table if exists annotated_books", executor.executed.get(0));
        assertEquals("drop table if exists annotated_authors", executor.executed.get(1));
        assertEquals(
                "create table annotated_authors (id bigint primary key, name varchar(255))",
                executor.executed.get(2));
        assertEquals(
                "create table annotated_books (id bigint primary key, title varchar(255), author_id bigint)",
                executor.executed.get(3));
    }

    @Test
    void recreateJoinedHierarchyDropsAndCreatesCrossSchemaTablesWithQuotedForeignKey() {
        EntityMetadataFactory quotedFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        RecordingExecutor quotedExecutor = new RecordingExecutor();
        Dialect quotedDialect = new QuotedTestDialect();
        SchemaInitializer quotedInitializer = new SimpleSchemaInitializer(
                new SimpleReactiveEntityOperations(quotedFactory, quotedDialect, quotedExecutor,
                        new EntityStateDetector(), new NoopTransactions()),
                quotedFactory, quotedDialect);

        StepVerifier.create(quotedInitializer.recreate(SchemaJoinedChild.class)).verifyComplete();

        assertEquals(List.of(
                "drop table if exists \"child_schema\".\"schema_joined_child\"",
                "drop table if exists \"root_schema\".\"schema_joined_root\"",
                "create table \"root_schema\".\"schema_joined_root\" (\"id\" bigint primary key, \"dtype\" varchar(31) not null)",
                "create table \"child_schema\".\"schema_joined_child\" (\"id\" bigint not null primary key, \"child_value\" integer, foreign key (\"id\") references \"root_schema\".\"schema_joined_root\" (\"id\"))"),
                quotedExecutor.executed);
    }

    @Test
    void recreateJoinedHierarchyRetainsUnqualifiedRootAndForeignKeyWhenSchemaBlank() {
        StepVerifier.create(initializer.recreate(BlankSchemaJoinedChild.class)).verifyComplete();

        assertEquals(List.of(
                "drop table if exists blank_schema_joined_child",
                "drop table if exists blank_schema_joined_root",
                "create table blank_schema_joined_root (id bigint primary key, dtype varchar(31) not null)",
                "create table blank_schema_joined_child (id bigint not null primary key, child_value integer, foreign key (id) references blank_schema_joined_root (id))"),
                executor.executed);
    }

    @Test
    void emptyBatchIsRejected() {
        // 빈 입력은 의도 모호 — caller bug일 가능성이 높으므로 fail-fast.
        assertThrows(IllegalArgumentException.class, () ->
                initializer.create(List.<Class<?>>of()).block());
    }

    @Test
    void executorIsNotInvokedBeforeSubscription() {
        Mono<Void> create = initializer.create(SampleAccount.class);
        // cold Mono — subscribe 전에는 DDL이 실행되면 안 된다.
        assertEquals(0, executor.executed.size());
        StepVerifier.create(create).verifyComplete();
        assertEquals(1, executor.executed.size());
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "schema_joined_root", schema = "root_schema")
    @jakarta.persistence.Inheritance(strategy = jakarta.persistence.InheritanceType.JOINED)
    static class SchemaJoinedRoot {
        @jakarta.persistence.Id Long id;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "schema_joined_child", schema = "child_schema")
    static class SchemaJoinedChild extends SchemaJoinedRoot {
        Integer childValue;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "blank_schema_joined_root")
    @jakarta.persistence.Inheritance(strategy = jakarta.persistence.InheritanceType.JOINED)
    static class BlankSchemaJoinedRoot {
        @jakarta.persistence.Id Long id;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "blank_schema_joined_child")
    static class BlankSchemaJoinedChild extends BlankSchemaJoinedRoot {
        Integer childValue;
    }

    private static final class RecordingExecutor implements SqlExecutor {
        private final List<String> executed = new ArrayList<>();

        @Override
        public Mono<Long> execute(SqlStatement statement) {
            executed.add(statement.sql());
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<T> queryOne(SqlStatement statement, Function<io.nova.core.RowAccessor, T> mapper) {
            return Mono.empty();
        }

        @Override
        public <T> Flux<T> queryMany(SqlStatement statement, Function<io.nova.core.RowAccessor, T> mapper) {
            return Flux.empty();
        }
    }

    /**
     * Schema 작업은 inTransaction을 거치지 않으므로 호출되면 안 된다.
     * 실수로 호출되면 assertion이 잡아내도록 즉시 fail로 만든다.
     */
    private static final class NoopTransactions implements ReactiveTransactionOperations {
        @Override
        public <T> Mono<T> inTransaction(Function<TransactionContext, Mono<T>> callback) {
            return Mono.error(new AssertionError("SchemaInitializer should not open a transaction"));
        }
    }

    /**
     * 최소 dialect — quote는 no-op이고 schemaGenerator는 base AbstractSchemaGenerator를 그대로 사용한다.
     */
    private static class TestDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SqlRenderer renderer = new AbstractSqlRenderer(this) {};
        private final SchemaGenerator schemaGenerator = new AbstractSchemaGenerator(this) {};

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return identifier;
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            return schemaGenerator;
        }
    }

    private static final class QuotedTestDialect extends TestDialect {
        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }
    }
}
