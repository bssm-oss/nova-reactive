<!--
PR 작성 가이드: CONTRIBUTING.md "PR 규격" 섹션 참고.
빈 칸을 모두 작성하세요. 해당 없는 항목은 "N/A"로 남겨둡니다.
-->

## Summary
<!-- 1-3 줄로: 무엇을 / 왜 -->

## Related issue
<!-- 단일 이슈를 닫는 PR이면: Closes #N -->
<!-- 관련만 있는 경우: Refs #N -->


## Changes
<!-- 사용자 가시적 / 구조적 변경 항목 단위로 -->
-
-

## Test plan
- [ ] `./gradlew build` 통과
- [ ] 변경 모듈 narrow test (`./gradlew :nova-project:<module>:test`) 통과
- [ ] 새 기능 / 버그 fix는 회귀 테스트 추가
- [ ] (해당 시) 문서 갱신 — README / `docs/` / javadoc

## Breaking change
- [ ] No
- [ ] Yes — 영향 범위와 마이그레이션 경로:

## Checklist
- [ ] 커밋 메시지가 [Conventional Commits](https://www.conventionalcommits.org/) 형식
- [ ] PR이 단일 관심사에 집중 (무관 변경은 별도 PR로 분리)
- [ ] (해당 시) `docs/`에 사용자 가시적 변경 문서화
- [ ] (해당 시) 새 의존성 / 모듈 추가는 `AGENTS.md` "Boundaries" 사전 합의 완료
- [ ] AI agent를 `Co-Authored-By:`로 추가하지 않음
