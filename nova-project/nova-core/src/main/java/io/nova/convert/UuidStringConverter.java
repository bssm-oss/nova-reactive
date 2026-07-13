package io.nova.convert;

import java.util.UUID;

/**
 * {@link java.util.UUID} 값과 컬럼의 {@code VARCHAR} 표현 사이를 변환한다. R2DBC 드라이버(H2 등)는
 * {@code varchar} 컬럼을 {@link UUID}로 직접 디코딩하지 못하므로, 저장은 canonical 36자 문자열
 * ({@link UUID#toString()})로 하고 읽을 때 {@link UUID#fromString(String)}으로 복원한다.
 * {@code null}은 그대로 통과시켜 SQL {@code NULL}을 보존한다. 컬럼에서 읽은 문자열이 UUID 형식이
 * 아니면 발생 위치를 알 수 있는 메시지로 {@link IllegalArgumentException}을 던진다.
 */
public final class UuidStringConverter implements AttributeConverter<UUID, String> {
    @Override
    public String write(UUID source) {
        return source == null ? null : source.toString();
    }

    @Override
    public UUID read(String source) {
        if (source == null) {
            return null;
        }
        try {
            return UUID.fromString(source);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Cannot map value '" + source + "' to a java.util.UUID", exception);
        }
    }
}
