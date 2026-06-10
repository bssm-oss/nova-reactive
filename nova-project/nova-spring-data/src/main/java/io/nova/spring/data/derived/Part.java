package io.nova.spring.data.derived;

/**
 * derived query method 이름에서 파싱된 single predicate 조각. {@code findByEmailLike}의
 * {@code "EmailLike"} 한 부분에 해당한다.
 *
 * @param propertyName lowerCamelCase 엔티티 프로퍼티 이름 (Criteria가 받는 형태와 동일).
 * @param keyword      이 part가 적용할 비교 keyword.
 */
record Part(String propertyName, Keyword keyword) {
}
