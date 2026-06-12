package io.nova.convert;

import java.util.Objects;

/**
 * JPA 표준 {@link jakarta.persistence.AttributeConverter}를 Nova 내부 {@link AttributeConverter} SPI로
 * 감싸는 어댑터다. {@code @Convert(converter = X.class)}로 지정된 변환기가 {@code @Enumerated}/{@code @Json}과
 * 동일한 converter 파이프라인(toColumnValue/toPropertyValue → row 디코딩)을 그대로 타게 한다.
 *
 * @param <X> 엔티티 속성 타입(도메인 타입)
 * @param <Y> 데이터베이스 컬럼 타입(저장 표현)
 */
public final class JpaAttributeConverterAdapter<X, Y> implements AttributeConverter<X, Y> {
    private final jakarta.persistence.AttributeConverter<X, Y> delegate;

    public JpaAttributeConverterAdapter(jakarta.persistence.AttributeConverter<X, Y> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate converter must not be null");
    }

    @Override
    public Y write(X source) {
        return delegate.convertToDatabaseColumn(source);
    }

    @Override
    public X read(Y source) {
        return delegate.convertToEntityAttribute(source);
    }
}
