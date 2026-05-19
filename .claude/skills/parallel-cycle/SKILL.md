---
name: parallel-cycle
description: Nova의 표준 multi-feature 작업 패턴. 사용자가 한 번에 여러 feature를 추가/수정하는 요청(예 "관계 매핑 + Maven publication + ILIKE operator 추가", "다음 cycle 진행해", "Pack X 구현해줘") 또는 명시적 cycle/batch 키워드를 쓸 때 자동 invoke한다. 단일 isolated 변경(typo fix, 한 파일 refactor)은 invoke하지 않는다.
---

# Parallel Cycle Workflow

Nova의 multi-feature batch 작업을 4-worktree 병렬 → 4 reviewer 병렬 → 순차 ff-merge → cleanup → 다음 cycle 사용자 confirm 패턴으로 진행한다.

## Trigger 조건

- 사용자가 한 번에 3개 이상 feature/follow-up을 묶어 요청
- "다음 cycle" / "Pack X" / "다음 batch" / "병렬로" 같은 keyword
- review에서 잡힌 critical/major 4건 이상을 묶어 처리할 때

**Invoke하지 않는 경우**: 단일 typo/rename, 1-file refactor, 사용자가 질문만 하거나 plan mode일 때.

## Phase 1 — Pre-cycle 검증

1. main HEAD hash 기록 (`git log --oneline -1 main`)
2. 작업 트리 깨끗 확인 (`git status --short` 빈 결과)
3. 기존 worktree 잔재 정리 (`git worktree list` → cleanup)
4. cycle 4 feature scope 명확화 — 사용자가 명시 안 했으면 후보 3-4개 제시 후 AskUserQuestion으로 확정

## Phase 2 — 4 worktree 병렬 spawn

- 항상 **4 worktree 고정**. 사용자 scope가 3 feature면 4번째는 minor follow-up/docs/test로 채움
- 각 worktree는 `isolation: "worktree"` + `run_in_background: true`
- agent prompt 공통 헤더:

  ```
  ## 시작 시 필수
  git log --oneline -1
  git rebase main                     # LOCAL main, NOT origin/main (main HEAD: <hash>)
  git log --oneline -1                # base 검증
  ```

- AGENTS.md 룰 명시:
  - 룰 #6 보호 contract (`Dialect`/`SqlRenderer`/`SchemaGenerator`/`SqlExecutor`/`ReactiveEntityOperations`/`ReactiveTransactionOperations`/`ReactiveTransactionManager`) — **default method 추가만**, 시그니처 변경 금지
  - 룰 #1: nova-core/r2dbc production code dep 추가 금지 (별도 모듈만)
  - 룰 #4: blocking call 금지 (`block`/`blockFirst`/`blockLast`/`toIterable`/`Future.get`)
  - 룰 #5: `ThreadLocal` 금지
- 컨벤션: JUnit5 + StepVerifier, AssertJ/Mockito/Hamcrest 금지, constructor injection, `final` 필드, `Simple*`/`Abstract*` naming
- Commit: Conventional Commits, **Co-Authored-By Claude 절대 금지** (강조 1행 reserved)

### Marker namespace 분리 (hub 충돌 mitigation)

같은 metadata hub를 건드릴 두 feature를 동시 spawn할 때 (예 `@ManyToOne` + `@OneToMany`) 각 agent prompt에서 **PersistentProperty marker field 이름을 명시적으로 다르게** 지정한다. 예:
- A worktree: `manyToOne`, `manyToOneTargetType`, `manyToOneJoinColumn`
- B worktree: `oneToMany`, `oneToManyTargetType`, `oneToManyMappedBy`

이렇게 하면 PP 생성자가 자동 머지된다. 단 `SimpleReactiveEntityOperations.mapRow`처럼 둘 다 광범위 rewrite하는 hub는 marker 분리로도 충돌 해소 불가 — 그 경우 두 feature를 **단일 worktree로 통합 spawn**.

## Phase 3 — 머지 순서

격리도 ↑ → hub ↓ 순서:

1. 신규 모듈 추가 (완전 격리) → ff-merge
2. `*.gradle.kts` settings만 touch (격리적) → rebase + ff-merge
3. 신규 클래스만 (`io.nova.fetch/`, `io.nova.retry/` 같은) → rebase + ff-merge
4. metadata factory class-level scan (annotation 추가) → rebase + ff-merge
5. metadata factory field-level loop + ops hub (가장 충돌) → rebase + ff-merge (수동 해소 필요)

각 단계 후 `./gradlew build` 검증. 충돌 발견 시 즉시 해소.

## Phase 4 — Hub 충돌 abort criterion

다음 중 하나면 worktree rebase abort + 다음 cycle에서 main 위에 재spawn:

- `SimpleReactiveEntityOperations.mapRow`에 두 worktree가 광범위 rewrite 모두 적용 — 통합 불가능 수준
- 충돌 block 5+ 개가 한 파일에 누적 + 양쪽 모두 핵심 로직
- conflict 해소 시도가 30분 초과

abort 후 cycle 마무리 + 사용자에게 보류 사실 보고 + 다음 cycle에서 **단일 worktree 통합**으로 재시도.

## Phase 5 — 4 reviewer 병렬

`senior-backend-code-reviewer` agent 4개를 worktree 머지 후 commit range로 review:

```
git show <commit> --stat
git diff <base>..<head>
```

검토 포인트 매 cycle 공통:
- 보호 contract 시그니처 변경 여부 (룰 #6)
- blocking call (룰 #4)
- ThreadLocal (룰 #5)
- 룰 #1: nova-core/r2dbc dep 무변경
- silent dedupe/corruption 가능성
- Spring `@ConfigurationProperties`이면 dead config 위험 (실제 적용 효과까지 검증되는지)

## Phase 6 — Critical fix 처리

review에서 critical 1건 이상 → 즉시 follow-up commit으로 main에 fix (별도 cycle 안 만들고 직접).
major는 다음 cycle scope 후보로 분류.

## Phase 7 — Cleanup

- `git worktree remove <path> -f -f` (locked 우회)
- `git branch -D worktree-agent-<id>` 모두
- 최종 `git worktree list`에 main만 남아야 정상

## Phase 8 — 다음 cycle 사용자 confirm

`AskUserQuestion`으로 다음 cycle scope 결정. 자율 진행 금지. 옵션 3개 (Pack 후보) + "멈춤" + "다른 방향" 선택지.

## Memory 참조

- `feedback_parallel_cycle_workflow.md` — 기본 패턴
- `feedback_worktree_base_alignment.md` — `git rebase main` (LOCAL) 필수 + worktree isolation 실패 케이스
- `feedback_worktree_cwd_file_path.md` — Edit absolute path가 main repo로 가는 함정
- `feedback_metadata_stream_pattern.md` — marker property 추가 시 stream 자동 추출
- `feedback_column_uniqueness_check.md` — column space 확장 시 uniqueness 검증
- `feedback_spring_config_dead_test.md` — `@ConfigurationProperties` 실제 적용 효과 검증
- `feedback_integration_test_surfaces_bugs.md` — dialect 추가 시 in-memory driver integration test 필수
- `feedback_agent_sandbox_inconsistent.md` — agent별 sandbox 권한 불일치, orchestrator가 외부 verify
