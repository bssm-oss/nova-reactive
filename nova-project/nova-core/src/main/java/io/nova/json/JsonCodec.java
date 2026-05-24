package io.nova.json;

/**
 * {@code @Json} 필드 값과 JSON 문자열 사이의 직렬화/역직렬화를 담당하는 pluggable SPI다.
 * Nova 코어는 특정 JSON 라이브러리(Jackson, Gson 등)에 의존하지 않으므로, 사용자가 자신이 선택한
 * 라이브러리로 이 인터페이스를 구현해 {@link io.nova.metadata.EntityMetadataFactory}나
 * {@code io.nova.Nova} 팩토리에 주입한다.
 * <p>
 * 구현은 thread-safe해야 한다 — 단일 {@link io.nova.metadata.EntityMetadataFactory} 인스턴스가
 * 여러 reactive 파이프라인에서 동시에 공유될 수 있기 때문이다.
 */
public interface JsonCodec {

    /**
     * 주어진 값을 JSON 문자열로 직렬화한다. 호출자는 {@code null}이 아닌 값만 전달한다.
     */
    String encode(Object value);

    /**
     * JSON 문자열을 주어진 타입의 인스턴스로 역직렬화한다. 호출자는 {@code null}이 아닌 문자열만 전달한다.
     */
    <T> T decode(String json, Class<T> type);

    /**
     * 어떤 {@link JsonCodec}도 등록되지 않은 상태에서 사용되는 기본 구현을 반환한다.
     * {@code @Json} 필드가 없는 엔티티만 다루는 한 이 codec은 절대 호출되지 않으며, 호출되는 즉시
     * 명확한 메시지의 {@link IllegalStateException}을 던져 codec 미등록 사실을 알린다.
     */
    static JsonCodec unconfigured() {
        return UnconfiguredHolder.INSTANCE;
    }

    /**
     * lazy holder가 아니라 단순 상수 보관용 내부 클래스다. {@link #unconfigured()}가 매번 새 인스턴스를
     * 만들지 않도록 단일 stateless 구현을 공유한다.
     */
    final class UnconfiguredHolder {
        private static final JsonCodec INSTANCE = new JsonCodec() {
            @Override
            public String encode(Object value) {
                throw unconfigured();
            }

            @Override
            public <T> T decode(String json, Class<T> type) {
                throw unconfigured();
            }

            private IllegalStateException unconfigured() {
                return new IllegalStateException(
                        "No JsonCodec configured; register one to use @Json fields");
            }
        };

        private UnconfiguredHolder() {
        }
    }
}
