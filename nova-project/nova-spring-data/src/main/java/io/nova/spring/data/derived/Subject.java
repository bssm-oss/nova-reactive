package io.nova.spring.data.derived;

/**
 * derived query method 이름의 동사 부분이 식별하는 작업. 각 값은 분리된 method-name prefix 한 집합에
 * 대응하며 {@link DerivedQueryDispatcher}가 어떤 {@link io.nova.core.ReactiveEntityOperations} 메서드로
 * 위임할지를 결정한다.
 */
enum Subject {
    /** {@code find...By}, {@code findAll...By} — predicate에 일치하는 모든 행. */
    FIND_ALL,
    /** {@code findFirst...By}, {@code findTop...By}, {@code findOne...By} — LIMIT 1. */
    FIND_ONE,
    /** {@code count...By} — predicate에 일치하는 행 수. */
    COUNT,
    /** {@code exists...By} — 일치하는 행이 한 건이라도 있는지. */
    EXISTS,
    /** {@code delete...By}, {@code remove...By} — predicate에 일치하는 모든 행 삭제. */
    DELETE
}
