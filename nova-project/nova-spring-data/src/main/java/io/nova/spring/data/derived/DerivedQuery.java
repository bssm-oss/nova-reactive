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
 * @param limit         {@code findTop<N>By}/{@code findFirst<N>By}(N &gt;= 2)에서 명시된 결과 행 수
 *                       상한. {@code null}이면 explicit limit 없음 — {@code subject}가 {@code FIND_ONE}인
 *                       경우는 이 필드와 무관하게 dispatcher가 항상 LIMIT 1을 적용한다.
 * @param pageableArgIndex {@link io.nova.query.Pageable} 파라미터의 메서드 인자 인덱스. 없으면 {@code -1}.
 *                       존재하면 항상 마지막 파라미터이며 predicate keyword 인자로 소비되지 않는다.
 * @param pagingResult  Pageable이 있을 때 결과를 감쌀 페이징 컨테이너 형태. Pageable이 없으면
 *                       {@link PagingResult#NONE}.
 */
record DerivedQuery(
        Subject subject,
        List<List<Part>> orGroups,
        List<Ordering> orderings,
        int expectedArgs,
        Integer limit,
        int pageableArgIndex,
        PagingResult pagingResult) {

    boolean hasPageable() {
        return pageableArgIndex >= 0;
    }
}
