package io.nova.sql;

import java.util.List;

/**
 * 한 번 렌더링된 SQL과 그 SQL이 요구하는 parameter slot 개수를 캡슐화한 재실행 가능 쿼리다.
 * SQL 문자열을 매번 다시 렌더링하지 않고도 다른 binding 값으로 동일한 쿼리를 반복 실행할 수 있게 한다.
 * <p>
 * {@link #bind(Object...)}와 {@link #bindList(List)}는 호출 시점의 binding 값으로 새 {@link SqlStatement}을
 * 만들어 반환한다. 두 메서드 모두 {@code values}의 길이가 {@link #parameterCount()}와 일치하지 않으면
 * {@link IllegalArgumentException}을 던진다. {@code CompiledQuery} 구현체는 immutable해야 하며 thread-safe
 * 해야 한다 — 동일 인스턴스를 여러 스레드에서 동시에 {@code bind} 호출하는 것이 안전해야 한다.
 * <p>
 * 이 추상화는 향후 prepared statement caching 같은 최적화의 기반이 된다.
 */
public interface CompiledQuery {
    /**
     * 미리 렌더링된 SQL 문자열을 반환한다.
     */
    String sql();

    /**
     * 이 SQL이 요구하는 parameter slot의 개수를 반환한다. {@code bind(...)} 호출에 전달되는 값의 개수가
     * 이 값과 같아야 한다.
     */
    int parameterCount();

    /**
     * 주어진 값들을 binding으로 사용하는 새 {@link SqlStatement}을 반환한다. {@code values.length}가
     * {@link #parameterCount()}와 다르면 {@link IllegalArgumentException}을 던진다.
     */
    SqlStatement bind(Object... values);

    /**
     * 주어진 값들을 binding으로 사용하는 새 {@link SqlStatement}을 반환한다. {@code values.size()}가
     * {@link #parameterCount()}와 다르면 {@link IllegalArgumentException}을 던진다.
     */
    SqlStatement bindList(List<Object> values);
}
