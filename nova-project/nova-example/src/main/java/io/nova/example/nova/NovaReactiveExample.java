package io.nova.example.nova;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutor;
import io.nova.dialect.h2.H2Dialect;
import io.nova.json.JsonCodec;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

/**
 * Nova reactive example — User has @OneToMany Order. findById returns the user
 * with orders already hydrated by a single IN-query, no session boundary
 * required and no explicit fetch() call.
 *
 * Run: ./gradlew :nova-project:nova-example:runNova
 */
public final class NovaReactiveExample {

    public static void main(String[] args) {
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///nova-example?options=DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();

        EntityMetadataFactory metadataFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), JsonCodec.unconfigured());
        SqlExecutor executor = new R2dbcSqlExecutor(cf, dialect);
        ReactiveEntityOperations ops = new SimpleReactiveEntityOperations(
                metadataFactory,
                dialect,
                executor,
                new EntityStateDetector(),
                new R2dbcTransactionManager(cf));

        Mono<Void> demo = createSchema(executor)
                .then(saveSampleData(ops))
                .flatMap(userId ->
                        // ── The whole point ──────────────────────────────────────
                        // findById returns User with `orders` already populated
                        // by a single child IN-query. No session, no fetch() call,
                        // no lazy proxy. Just a Reactor Mono you can compose.
                        ops.findById(User.class, userId))
                .doOnNext(user -> {
                    BigDecimal total = user.getOrders().stream()
                            .map(Order::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    System.out.printf("[Nova] %s has %d orders, total=%s%n",
                            user.getName(), user.getOrders().size(), total);
                })
                .then();

        // demo-only blocking sink at the process boundary
        demo.block();
    }

    private static Mono<Void> createSchema(SqlExecutor executor) {
        // Inline H2 DDL for the demo. (Nova's SchemaGenerator can usually do this,
        // but is out of scope for the side-by-side runtime comparison.)
        return Flux.fromIterable(List.of(
                        """
                        create table "app_user" (
                            "id" bigint generated always as identity primary key,
                            "name" varchar(255) not null
                        )
                        """,
                        """
                        create table "app_order" (
                            "id" bigint generated always as identity primary key,
                            "description" varchar(255) not null,
                            "amount" numeric(12, 2) not null,
                            "user_id" bigint not null
                        )
                        """))
                .concatMap(ddl -> executor.execute(new SqlStatement(ddl, List.of())))
                .then();
    }

    private static Mono<Long> saveSampleData(ReactiveEntityOperations ops) {
        return ops.save(new User("Hyunwoo"))
                .flatMap(saved -> Flux.just(
                                new Order("MacBook", new BigDecimal("3200.00"), saved),
                                new Order("Keyboard", new BigDecimal("180.00"), saved),
                                new Order("Coffee",   new BigDecimal("4.50"),   saved))
                        .concatMap(ops::save)
                        .then(Mono.just(saved.getId())));
    }
}
