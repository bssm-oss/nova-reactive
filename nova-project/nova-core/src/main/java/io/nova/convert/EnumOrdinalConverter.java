package io.nova.convert;

/**
 * {@code @Enumerated(ORDINAL)} 필드 값과 컬럼의 {@code INTEGER} 표현 사이를 변환한다.
 * {@code null}은 그대로 통과시켜 SQL {@code NULL}을 보존하고, ordinal이 enum constant 범위를
 * 벗어나면 발생 위치를 알 수 있는 메시지로 {@link IllegalArgumentException}을 던진다.
 * <p>
 * read 시 입력 타입은 {@link Number}로 받아 R2DBC 드라이버가 반환하는 {@code Integer}, {@code Long},
 * {@code Short} 어느 쪽이든 동일하게 처리한다.
 */
public final class EnumOrdinalConverter<E extends Enum<E>> implements AttributeConverter<E, Number> {
    private final Class<E> enumType;
    private final E[] constants;

    public EnumOrdinalConverter(Class<E> enumType) {
        this.enumType = enumType;
        this.constants = enumType.getEnumConstants();
    }

    @Override
    public Number write(E source) {
        if (source == null) {
            return null;
        }
        return source.ordinal();
    }

    @Override
    public E read(Number source) {
        if (source == null) {
            return null;
        }
        int ordinal = source.intValue();
        if (ordinal < 0 || ordinal >= constants.length) {
            throw new IllegalArgumentException(
                    "Cannot map ordinal " + ordinal + " to enum " + enumType.getName()
                            + "; valid range is [0, " + constants.length + ")");
        }
        return constants[ordinal];
    }
}
