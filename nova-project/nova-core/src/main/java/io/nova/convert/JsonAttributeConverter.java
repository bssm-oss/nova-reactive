package io.nova.convert;

import io.nova.json.JsonCodec;

/**
 * {@code @Json} 필드 값과 컬럼의 JSON 문자열 표현 사이를 변환한다. 직렬화/역직렬화는 주입된
 * {@link JsonCodec}에 위임하며, {@code null}은 그대로 통과시켜 SQL {@code NULL}을 보존한다
 * ({@link EnumStringConverter}와 동일한 null-safe 규약).
 * <p>
 * {@link #write(Object)}는 직렬화된 JSON {@link String}을 반환하고, {@link #read(Object)}는 컬럼에서
 * 읽은 값을 {@link String}으로 보고 codec으로 {@code targetType} 인스턴스를 복원한다.
 */
public final class JsonAttributeConverter implements AttributeConverter<Object, Object> {
    private final JsonCodec codec;
    private final Class<?> targetType;

    public JsonAttributeConverter(JsonCodec codec, Class<?> targetType) {
        this.codec = codec;
        this.targetType = targetType;
    }

    @Override
    public Object write(Object source) {
        if (source == null) {
            return null;
        }
        return codec.encode(source);
    }

    @Override
    public Object read(Object source) {
        if (source == null) {
            return null;
        }
        return codec.decode((String) source, targetType);
    }
}
