package io.nova.query.jpql;

import java.util.HashMap;
import java.util.Map;

/**
 * JPQL 쿼리에 바인딩된 named/positional 파라미터 값 저장소. 실행 시점에 {@link JpqlBinding} 슬롯을 실제
 * 값으로 해석한다. 미설정 파라미터 참조는 {@link JpqlException}으로 fail-fast한다.
 */
public final class JpqlParameters {
    private final Map<String, Object> named = new HashMap<>();
    private final Map<Integer, Object> positional = new HashMap<>();

    public void setNamed(String name, Object value) {
        named.put(name, value);
    }

    public void setPositional(int position, Object value) {
        positional.put(position, value);
    }

    /** {@link JpqlBinding}을 실제 바인딩 값으로 해석한다(리터럴은 그대로, 파라미터는 조회). */
    public Object resolve(JpqlBinding binding) {
        return switch (binding) {
            case JpqlBinding.Literal l -> l.value();
            case JpqlBinding.Named n -> resolveNamed(n.name());
            case JpqlBinding.Positional p -> resolvePositional(p.position());
            case JpqlBinding.Component c -> {
                // 참조 엔티티에서 이 FK 컴포넌트의 @Id 도메인 값을 꺼내 저장 표현으로 인코딩한다.
                Object reference = resolve(c.source());
                Object domain = reference == null ? null : c.column().readReferencedValue(reference);
                yield c.column().toColumnValue(domain);
            }
        };
    }

    public Object resolveNamed(String name) {
        if (!named.containsKey(name)) {
            throw new JpqlException("No value bound for named parameter ':" + name + "'");
        }
        return named.get(name);
    }

    public Object resolvePositional(int position) {
        if (!positional.containsKey(position)) {
            throw new JpqlException("No value bound for positional parameter '?" + position + "'");
        }
        return positional.get(position);
    }
}
