package io.nova.query.jpql;

/**
 * 변환된 SQL의 bind marker 슬롯 하나에 대응하는 바인딩 계획. 파싱 시점에 값이 확정된 리터럴과, 실행
 * 시점에 사용자가 채우는 named/positional 파라미터를 구분한다. 실제 값 채우기는 {@link JpqlQuery}가 한다.
 */
public sealed interface JpqlBinding
        permits JpqlBinding.Literal, JpqlBinding.Named, JpqlBinding.Positional, JpqlBinding.Component {

    /** 파싱 시점에 값이 확정된 리터럴 바인딩. */
    record Literal(Object value) implements JpqlBinding {
    }

    /** {@code :name} named 파라미터 슬롯. */
    record Named(String name) implements JpqlBinding {
    }

    /** {@code ?n} positional 파라미터 슬롯(1-기반). */
    record Positional(int position) implements JpqlBinding {
    }

    /**
     * 복합키 타겟 to-one 비교({@code WHERE c.parent = :ref})의 한 FK 컴포넌트 슬롯. {@code source}가 해석한
     * 참조 엔티티에서 {@code column}에 대응하는 {@code @Id} 컴포넌트를 꺼내 저장 표현으로 인코딩한다. 참조가
     * {@code null}이면 이 컴포넌트도 {@code null}로 바인딩된다.
     */
    record Component(JpqlBinding source, io.nova.metadata.ToOneForeignKeyColumn column) implements JpqlBinding {
    }
}
