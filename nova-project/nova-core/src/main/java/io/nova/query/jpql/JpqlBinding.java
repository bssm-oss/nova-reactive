package io.nova.query.jpql;

/**
 * 변환된 SQL의 bind marker 슬롯 하나에 대응하는 바인딩 계획. 파싱 시점에 값이 확정된 리터럴과, 실행
 * 시점에 사용자가 채우는 named/positional 파라미터를 구분한다. 실제 값 채우기는 {@link JpqlQuery}가 한다.
 */
public sealed interface JpqlBinding
        permits JpqlBinding.Literal, JpqlBinding.Named, JpqlBinding.Positional {

    /** 파싱 시점에 값이 확정된 리터럴 바인딩. */
    record Literal(Object value) implements JpqlBinding {
    }

    /** {@code :name} named 파라미터 슬롯. */
    record Named(String name) implements JpqlBinding {
    }

    /** {@code ?n} positional 파라미터 슬롯(1-기반). */
    record Positional(int position) implements JpqlBinding {
    }
}
