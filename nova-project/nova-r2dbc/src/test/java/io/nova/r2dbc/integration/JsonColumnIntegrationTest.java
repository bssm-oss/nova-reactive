package io.nova.r2dbc.integration;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Json;
import io.nova.annotation.Table;
import io.nova.json.JsonCodec;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @Json} 필드가 실제 r2dbc-h2 in-memory driver를 통해 production {@code save()}/{@code findById()}
 * 경로로 round-trip 되는지 검증한다. 직렬화/역직렬화는 손수 만든 {@link JsonCodec} 테스트 더블이 담당하며
 * (JSON 라이브러리 의존성 없음) Nova의 {@link io.nova.convert.JsonAttributeConverter} 경로
 * ({@code toColumnValue}/{@code toPropertyValue})를 ops hub가 그대로 탄다.
 *
 * <p>이 테스트는 ops hub를 우회하지 않는다 — {@code save()}가 converter로 직렬화한 JSON 문자열을 바인딩하고,
 * {@code findById()}의 {@code mapRow}가 {@link io.nova.metadata.PersistentProperty#columnType()}을 통해
 * 컬럼 값을 {@link String}으로 읽은 뒤 {@code toPropertyValue}로 원본 Map을 복원하는 전 과정을 검증한다.
 *
 * <h2>왜 JSON 컬럼을 {@code varchar}로 매핑하는가</h2>
 * H2 native {@code JSON} 타입은 plain {@link String} 바인딩을 그대로 받아들이지 않는다 — JSON 리터럴은
 * {@code ... FORMAT JSON} 또는 {@code JSON '...'} 형태를 요구하고, r2dbc-h2 1.0.0이 일반 String parameter를
 * JSON 컬럼에 바인딩하면 driver/엔진이 거부한다. {@code @Json}이 직렬화하는 것은 어차피 JSON 문자열이고
 * H2에서는 그 문자열을 문자 컬럼에 저장/조회하는 것으로 충분하므로, 이 테스트는 DDL에서 컬럼을
 * {@code varchar}(문자열)로 만들어 실제 driver round-trip을 검증한다. 컬럼의 SQL 타입은 dialect의
 * {@link io.nova.sql.Dialect#jsonColumnType()}이 결정하는 production 관심사이며(PostgreSQL은 {@code jsonb}),
 * 별도 schema-generator 단위 테스트가 그 부분을 보호한다.
 */
class JsonColumnIntegrationTest {

    @Test
    void jsonValueRoundTripsThroughSaveAndFindByIdViaConverterPath() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create(new MapJsonCodec());
        // JSON 컬럼을 varchar로 매핑하는 이유는 클래스 Javadoc 참고. id는 IDENTITY라 save()가 INSERT한다.
        support.execute("create table \"json_profiles\" ("
                + "\"id\" bigint generated always as identity primary key, "
                + "\"email_address\" varchar(255), "
                + "\"settings\" varchar(4000))");

        Map<String, String> original = new LinkedHashMap<>();
        original.put("theme", "dark");
        original.put("locale", "ko_KR");

        JsonProfile profile = new JsonProfile("json@nova.io", original);

        Mono<JsonProfile> pipeline = support.operations().save(profile)
                .flatMap(saved -> {
                    assertNotNull(saved.id, "save 이후 IDENTITY id가 채워져 있어야 한다");
                    return support.operations().findById(JsonProfile.class, saved.id);
                });

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertEquals("json@nova.io", loaded.email);
                    assertEquals(original, loaded.settings,
                            "@Json 필드는 save→findById round-trip 후 원본 Map과 동일해야 한다");
                })
                .verifyComplete();
    }

    /**
     * {@code @Json} 필드 round-trip 검증에 사용하는 entity. {@code settings}는 단순 String→String Map으로,
     * 테스트 codec이 {@code k=v&k=v} 형태의 결정적 문자열로 직렬화한다.
     */
    @Entity
    @Table(name = "json_profiles")
    static class JsonProfile {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @Column(name = "email_address")
        String email;

        @Json
        @Column(name = "settings")
        Map<String, String> settings;

        JsonProfile() {
        }

        JsonProfile(String email, Map<String, String> settings) {
            this.email = email;
            this.settings = settings;
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
