package io.nova;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nova.dialect.h2.H2Dialect;
import io.nova.dialect.mariadb.MariaDbDialect;
import io.nova.dialect.mysql.MySqlDialect;
import io.nova.dialect.oracle.OracleDialect;
import io.nova.dialect.postgresql.PostgresqlDialect;
import io.nova.sql.Dialect;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

/**
 * {@link Nova#resolveDialect(ConnectionFactory)}가 R2DBC driver 이름을 올바른 dialect 구현으로
 * 매핑하는지 검증한다. 실제 DB 연결은 필요 없으며, {@code ConnectionFactoryMetadata.getName()}만
 * stub한 테스트 더블로 검증한다.
 */
class NovaResolveDialectTest {

    @Test
    void resolvesPostgresqlDriverToPostgresqlDialect() {
        Dialect dialect = Nova.resolveDialect(connectionFactoryNamed("PostgreSQL"));
        assertInstanceOf(PostgresqlDialect.class, dialect);
        assertEquals("postgresql", dialect.name());
    }

    @Test
    void resolvesMysqlDriverToMySqlDialect() {
        Dialect dialect = Nova.resolveDialect(connectionFactoryNamed("MySQL"));
        assertInstanceOf(MySqlDialect.class, dialect);
        assertEquals("mysql", dialect.name());
    }

    @Test
    void resolvesMariadbDriverToMariaDbDialect() {
        Dialect dialect = Nova.resolveDialect(connectionFactoryNamed("MariaDB"));
        assertInstanceOf(MariaDbDialect.class, dialect);
        assertEquals("mariadb", dialect.name());
    }

    @Test
    void resolvesH2DriverToH2Dialect() {
        Dialect dialect = Nova.resolveDialect(connectionFactoryNamed("H2"));
        assertInstanceOf(H2Dialect.class, dialect);
        assertEquals("h2", dialect.name());
    }

    @Test
    void resolvesOracleDatabaseDriverToOracleDialect() {
        // oracle-r2dbc의 ConnectionFactoryMetadata.getName()은 "Oracle Database"를 노출한다.
        Dialect dialect = Nova.resolveDialect(connectionFactoryNamed("Oracle Database"));
        assertInstanceOf(OracleDialect.class, dialect);
        assertEquals("oracle", dialect.name());
    }

    @Test
    void realH2DriverExposesNameMatchedByResolveDialect() {
        // stub이 아닌 실제 r2dbc-h2 driver가 노출하는 getName()이 우리가 매핑하는 "H2" 문자열과
        // 정확히 일치하는지 고정한다 — driver 이름 매핑 정확성을 실드라이버로 검증하는 회귀 가드.
        ConnectionFactory h2 = ConnectionFactories.get("r2dbc:h2:mem:///novaresolve");
        assertEquals("H2", h2.getMetadata().getName(),
                "r2dbc-h2 driver 이름이 resolveDialect의 case와 일치해야 한다");
        assertInstanceOf(H2Dialect.class, Nova.resolveDialect(h2));
    }

    @Test
    void throwsForUnknownDriverName() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> Nova.resolveDialect(connectionFactoryNamed("CockroachDB")));
        // 예외 메시지는 알 수 없는 driver 이름과 지원 목록을 모두 담아야 한다.
        String message = ex.getMessage();
        assertEquals(
                "No Nova dialect mapped for R2DBC driver: CockroachDB"
                        + " (supported: PostgreSQL, MySQL, MariaDB, H2, Oracle)",
                message);
    }

    /**
     * {@code getMetadata().getName()}만 의미 있게 stub한 최소 {@link ConnectionFactory} 더블.
     * {@link #create()}는 호출되지 않으므로 {@link UnsupportedOperationException}으로 방어한다.
     */
    private static ConnectionFactory connectionFactoryNamed(String driverName) {
        ConnectionFactoryMetadata metadata = () -> driverName;
        return new ConnectionFactory() {
            @Override
            public Publisher<? extends Connection> create() {
                throw new UnsupportedOperationException("test double does not open connections");
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return metadata;
            }
        };
    }
}
