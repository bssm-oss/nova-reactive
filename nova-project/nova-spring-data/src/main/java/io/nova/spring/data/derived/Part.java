package io.nova.spring.data.derived;

/**
 * derived query method 이름에서 파싱된 single predicate 조각. {@code findByEmailLike}의
 * {@code "EmailLike"} 한 부분에 해당한다.
 *
 * @param propertyName lowerCamelCase 엔티티 프로퍼티 이름 (Criteria가 받는 형태와 동일).
 * @param keyword      이 part가 적용할 비교 keyword.
 * @param ignoreCase   {@code IgnoreCase} suffix가 붙었는지. {@link DerivedQueryParser}가 지원하는
 *                      keyword({@code EQ}/{@code NOT}/{@code LIKE}/{@code STARTING_WITH}/
 *                      {@code ENDING_WITH}/{@code CONTAINING})에 대해서만 true일 수 있다 — 그 외
 *                      keyword와의 조합은 파싱 시점에 이미 거부된다.
 */
record Part(String propertyName, Keyword keyword, boolean ignoreCase) {
}
