package io.nova.convert;

/**
 * {@code @Enumerated(STRING)} 필드 값과 컬럼의 {@code VARCHAR} 표현 사이를 변환한다.
 * {@code null}은 그대로 통과시켜 SQL {@code NULL}을 보존한다. 컬럼에서 읽은 문자열이 enum
 * constant와 일치하지 않으면 발생 위치를 알 수 있는 메시지로 {@link IllegalArgumentException}을
 * 던진다.
 */
public final class EnumStringConverter<E extends Enum<E>> implements AttributeConverter<E, String> {
    private final Class<E> enumType;

    public EnumStringConverter(Class<E> enumType) {
        this.enumType = enumType;
    }

    @Override
    public String write(E source) {
        if (source == null) {
            return null;
        }
        return source.name();
    }

    @Override
    public E read(String source) {
        if (source == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, source);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Cannot map value '" + source + "' to enum " + enumType.getName(), exception);
        }
    }
}
