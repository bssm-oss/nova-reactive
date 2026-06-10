package io.nova.spring.data.derived;

import java.util.List;

/**
 * derived query method 이름에서 파싱된 불변 representation. {@link DerivedQueryParser}가 생성하고
 * {@link DerivedQueryDispatcher}가 실행한다.
 *
 * @param subject       동사 부분이 식별하는 작업.
 * @param orGroups      AND-conjunction을 외부 OR로 묶은 predicate tree. 한 개 conjunction은 단일 OR-group에
 *                       들어 있다. 빈 리스트는 predicate-less subject(예: {@code findAll()}을 derived 경로로
 *                       파싱한 경우)를 의미하지만, 본 구현은 {@code By}를 필수로 요구하므로 실제로는
 *                       항상 비어 있지 않다.
 * @param orderings     {@code OrderBy} 절의 정렬 항목 목록. 비어 있으면 정렬 절을 생략한다.
 * @param expectedArgs  메서드 파라미터에서 소비되어야 하는 총 인자 개수
 *                       — 모든 keyword의 parameterCount 합.
 */
record DerivedQuery(
        Subject subject,
        List<List<Part>> orGroups,
        List<Ordering> orderings,
        int expectedArgs) {
}
