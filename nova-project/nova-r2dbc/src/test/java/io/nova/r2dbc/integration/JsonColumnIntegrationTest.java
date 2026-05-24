package io.nova.r2dbc.integration;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.Id;
import io.nova.annotation.Json;
import io.nova.annotation.Table;
import io.nova.dialect.h2.H2Dialect;
import io.nova.json.JsonCodec;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Json} 필드가 실제 r2dbc-h2 in-memory driver를 통해 JSON 문자열로 저장됐다가 원본 value object로
 * round-trip 되는지 검증한다 — 직렬화/역직렬화는 손수 만든 {@link JsonCodec} 테스트 더블이 담당하며
 * (JSON 라이브러리 의존성 없음) Nova의 {@link io.nova.convert.JsonAttributeConverter} 경로
 * ({@code toColumnValue}/{@code toPropertyValue})를 그대로 탄다.
 *
 * <h2>왜 JSON 컬럼을 {@code varchar}로 매핑하는가</h2>
 * H2 native {@code JSON} 타입은 plain {@link String} 바인딩을 그대로 받아들이지 않는다 — JSON 리터럴은
 * {@code ... FORMAT JSON} 또는 {@code JSON '...'} 형태를 요구하고, r2dbc-h2 1.0.0이 일반 String parameter를
 * JSON 컬럼에 바인딩하면 driver/엔진이 거부한다. {@code @Json}이 직렬화하는 것은 어차피 JSON 문자열이고
 * H2에서는 그 문자열을 문자 컬럼에 저장/조회하는 것으로 충분하므로, 이 테스트는 DDL에서 컬럼을
 * {@code varchar}(문자열)로 만들어 실제 driver round-trip을 검증한다 — {@code clob}은 r2dbc-h2가
 * {@code io.r2dbc.spi.Clob}으로 디코딩해 {@code row.get(col, String.class)}가 직접 String을 돌려주지
 * 않을 수 있어 회피한다. 컬럼의 SQL 타입은 dialect의 {@link Dialect#jsonColumnType()}이 결정하는
 * production 관심사이며(PostgreSQL은 {@code jsonb}), 별도 schema-generator 단위 테스트가 그 부분을 보호한다.
 *
 * <h2>왜 SqlExecutor로 직접 바인딩/조회하는가</h2>
 * core operations의 {@code mapRow}는 {@code row.get(column, property.javaType())}로 디코딩하므로 JSON
 * 컬럼을 user POJO 타입으로 직접 디코딩하려 시도한다 — 이는 {@code @Enumerated}에도 동일하게 적용되는
 * 기존 제약이며 driver가 JSON/문자 컬럼을 임의 POJO로 변환할 수 없다. 이 통합 테스트의 목적은 {@code @Json}
 * converter 경로가 실제 driver와 round-trip 되는지 검증하는 것이므로, converter가 만든 JSON String을 명시적으로
 * 바인딩하고 다시 {@code String}으로 읽어 {@code toPropertyValue}로 복원한다 — ops hub를 건드리지 않는다.
 */
class JsonColumnIntegrationTest {

    @Test
    void jsonValueRoundTripsThroughRealH2DriverViaConverterPath() {
        String dbName = "novajson_" + UUID.randomUUID().toString().replace("-", "");
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect);

        EntityMetadataFactory metadataFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new MapJsonCodec());
        EntityMetadata<JsonProfile> metadata = metadataFactory.getEntityMetadata(JsonProfile.class);
        PersistentProperty settings = metadata.findProperty("settings").orElseThrow();
        assertTrue(settings.json(), "settings는 @Json marker여야 한다");

        // JSON 컬럼을 varchar로 매핑하는 이유는 클래스 Javadoc 참고. id/email은 평범한 컬럼.
        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table \"json_profiles\" ("
                                + "\"id\" bigint primary key, "
                                + "\"email_address\" varchar(255), "
                                + "\"settings\" varchar(4000))",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();

        Map<String, String> original = new LinkedHashMap<>();
        original.put("theme", "dark");
        original.put("locale", "ko_KR");

        // @Json converter로 직렬화한 JSON String을 컬럼에 바인딩한다 — production save() 경로가
        // toColumnValue로 만드는 것과 동일한 값이다.
        Object encoded = settings.toColumnValue(original);
        assertEquals("theme=dark&locale=ko_KR", encoded, "codec이 만든 결정적 JSON 문자열이어야 한다");

        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into \"json_profiles\" (\"id\", \"email_address\", \"settings\") values (?, ?, ?)",
                        List.of(1L, "json@nova.io", encoded))))
                .expectNext(1L)
                .verifyComplete();

        // 컬럼을 String으로 읽어 @Json converter로 복원한다 — mapRow가 String 컬럼에 대해 하는 것과 동일.
        StepVerifier.create(executor.queryOne(
                        new SqlStatement(
                                "select \"settings\" from \"json_profiles\" where \"id\" = ?",
                                List.of(1L)),
                        row -> settings.toPropertyValue(row.get("settings", String.class))))
                .assertNext(value -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> restored = (Map<String, String>) value;
                    assertEquals(original, restored,
                            "@Json 필드는 driver round-trip 후 원본 Map과 동일해야 한다");
                })
                .verifyComplete();
    }

    /**
     * {@code @Json} 필드와 round-trip 라운드트립 검증에 사용하는 entity. {@code settings}는 단순 String→String
     * Map으로, 테스트 codec이 {@code k=v&k=v} 형태의 결정적 문자열로 직렬화한다.
     */
    @Entity
    @Table("json_profiles")
    static class JsonProfile {
        @Id
        private Long id;

        @Column("email_address")
        private String email;

        @Json
        @Column("settings")
        private Map<String, String> settings;

        JsonProfile() {
        }
    }

    /**
     * JSON 라이브러리 없이 String→String Map만 직렬화/역직렬화하는 손수 만든 {@link JsonCodec} 테스트 더블.
     * {@code key=value&key=value} 형태의 결정적 문자열을 사용해 round-trip 동일성을 단정할 수 있게 한다.
     */
    private static final class MapJsonCodec implements JsonCodec {
        @Override
        public String encode(Object value) {
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) value;
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (builder.length() > 0) {
                    builder.append('&');
                }
                builder.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return builder.toString();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(String json, Class<T> type) {
            Map<String, String> map = new LinkedHashMap<>();
            if (!json.isEmpty()) {
                for (String pair : json.split("&")) {
                    int eq = pair.indexOf('=');
                    map.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            return (T) map;
        }
    }
}
