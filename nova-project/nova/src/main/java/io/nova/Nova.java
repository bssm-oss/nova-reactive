package io.nova;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.mysql.MySqlDialect;
import io.nova.dialect.postgresql.PostgresqlDialect;
import io.nova.json.JsonCodec;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.sql.Dialect;
import io.r2dbc.spi.ConnectionFactory;

/**
 * 외부 사용자용 한 줄 진입점. ConnectionFactory만 주면 dialect를 자동 감지해
 * R2DBC 어댑터·트랜잭션 매니저까지 모두 묶은 ReactiveEntityOperations를 돌려준다.
 */
public final class Nova {

    private Nova() {
    }

    public static ReactiveEntityOperations create(ConnectionFactory connectionFactory) {
        return create(connectionFactory, resolveDialect(connectionFactory));
    }

    public static ReactiveEntityOperations create(ConnectionFactory connectionFactory, Dialect dialect) {
        return create(connectionFactory, dialect, JsonCodec.unconfigured());
    }

    /**
     * {@code @Json} 필드 직렬화에 사용할 {@link JsonCodec}을 명시해 operations를 조립한다.
     * codec은 {@link EntityMetadataFactory}로 전달돼 {@code @Json} 필드의 converter에 주입된다.
     */
    public static ReactiveEntityOperations create(
            ConnectionFactory connectionFactory, Dialect dialect, JsonCodec jsonCodec) {
        EntityMetadataFactory metadataFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), jsonCodec);
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);
        return new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
    }

    public static Dialect resolveDialect(ConnectionFactory connectionFactory) {
        String driverName = connectionFactory.getMetadata().getName();
        return switch (driverName) {
            case "PostgreSQL" -> new PostgresqlDialect();
            case "MySQL", "MariaDB" -> new MySqlDialect();
            case "H2" -> new MySqlDialect();
            default -> throw new IllegalStateException(
                    "No Nova dialect mapped for R2DBC driver: " + driverName
                            + " (supported: PostgreSQL, MySQL, MariaDB, H2 in MODE=MySQL)");
        };
    }
}
