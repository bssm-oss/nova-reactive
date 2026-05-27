package io.nova;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.dialect.mariadb.MariaDbDialect;
import io.nova.dialect.mysql.MySqlDialect;
import io.nova.dialect.oracle.OracleDialect;
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
 *
 * <p>auto-detect는 R2DBC {@code ConnectionFactoryMetadata.getName()} 값을 기준으로 PostgreSQL,
 * MySQL, MariaDB, H2, Oracle dialect를 매핑한다. 매핑되지 않는 driver는 {@code create(cf, dialect)}로
 * dialect를 직접 주입해야 한다.
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
        // driver 이름은 R2DBC ConnectionFactoryMetadata.getName() 관례를 따른다:
        // r2dbc-postgresql -> "PostgreSQL", r2dbc-mysql -> "MySQL", r2dbc-mariadb -> "MariaDB",
        // r2dbc-h2 -> "H2", oracle-r2dbc -> "Oracle Database"(일부 버전은 "Oracle"로 노출하므로 둘 다 매핑).
        String driverName = connectionFactory.getMetadata().getName();
        return switch (driverName) {
            case "PostgreSQL" -> new PostgresqlDialect();
            case "MySQL" -> new MySqlDialect();
            case "MariaDB" -> new MariaDbDialect();
            case "H2" -> new H2Dialect();
            case "Oracle Database", "Oracle" -> new OracleDialect();
            default -> throw new IllegalStateException(
                    "No Nova dialect mapped for R2DBC driver: " + driverName
                            + " (supported: PostgreSQL, MySQL, MariaDB, H2, Oracle)");
        };
    }
}
