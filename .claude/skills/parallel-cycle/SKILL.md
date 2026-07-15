---
name: parallel-cycle
description: Nova의 표준 multi-feature 작업 패턴. 사용자가 한 번에 여러 feature를 추가/수정하는 요청(예 "관계 매핑 + Maven publication + ILIKE operator 추가", "다음 cycle 진행해", "Pack X 구현해줘") 또는 명시적 cycle/batch 키워드를 쓸 때 자동 invoke한다. 단일 isolated 변경(typo fix, 한 파일 refactor)은 invoke하지 않는다.
---

# Parallel Cycle Workflow

Nova의 multi-feature batch 작업을 다음 순서로 진행한다:

**스코프 분리 → 설계-우선 병렬 개발(opus 설계→sonnet 구현) → (위험도 차등 병렬 리뷰 ↔ 보완)\* → 수렴-즉시 스트리밍 병합 → (E2E 전용 opus agent ↔ 보완)\* → PR 생성·머지 → 다음 cycle confirm**

(↔)\*는 **문제(critical/major) 0이 될 때까지 반복하는 수렴 루프**다 — 보완 후 반드시 재리뷰/재E2E해서 새 결함이 없음을 확인해야 다음 단계로 간다. 리뷰는 **병합 전** worktree 브랜치에서 하고, 수렴 후 통합 브랜치로 병합한다. 통합 브랜치에서 E2E를 돌리고, 보완한 뒤 origin에 PR을 올려 squash-merge 한다. (현행처럼 로컬 main 직접 커밋이 아니라 PR 기반.)

## Trigger 조건

- 사용자가 한 번에 3개 이상 feature/follow-up을 묶어 요청
- "다음 cycle" / "Pack X" / "다음 batch" / "병렬로" 같은 keyword
- review에서 잡힌 critical/major 4건 이상을 묶어 처리할 때

**Invoke하지 않는 경우**: 단일 typo/rename, 1-file refactor, 사용자가 질문만 하거나 plan mode일 때.

## 다중 트랙 동시 진행 (multi-track)

한 cycle은 하나의 "트랙"(독립 `cycle/<topic>` 브랜치 + 자기 미니 파이프라인)이다. 서로 무관한 작업 요청이 여럿이면 **여러 트랙을 동시에** 굴릴 수 있다 — 각 트랙은 독립적으로 진행되고 **서로 기다리지 않고** 준비되는 대로 각자 main에 착지(PR)한다. 느린 트랙이 빠른 트랙을 인질로 잡지 않는다.

**hub-disjoint 분할 (필수 안전 규칙):** 두 트랙이 **동시에 같은 hub 파일**을 광범위하게 건드리면 각자 main 착지 시 병합 충돌이 난다. 오케스트레이터는 트랙을 **파일/서브시스템 소유권으로 분할**한다:
- 겹치지 않는 트랙(예: 코어 query 트랙 vs `nova-spring-data` 모듈 트랙 vs `docs/` 트랙 vs dialect 모듈 트랙) → **완전 병렬**.
- 같은 hub(`JpqlSqlBuilder`/`EntityMetadataFactory`/`SimpleReactiveEntityOperations`/`AbstractSqlRenderer` 등)를 다투는 트랙 → **동시 금지, 순차화**(한 트랙이 착지한 뒤 다음 트랙이 rebase). 이는 트랙-내부 marker-namespace 분리(Phase 2)를 **트랙 수준으로 올린 것**이다.

**동시 트랙 상한:** 조율 비용·토큰 폭증을 막기 위해 **동시 2~3 트랙 권장**. 그 이상은 사용자가 명시적으로 요청할 때만.

**트랙 착지 순서:** 먼저 착지한 트랙이 main을 전진시키므로, 뒤따르는 트랙과 그 worktree는 병합 직전 새 main으로 rebase한다(disjoint면 clean). 스코프 확정(Phase 1) 시 각 트랙이 어떤 hub를 소유하는지 먼저 매핑해 충돌 트랙을 같은 시간대에 두지 않는다.

## Phase 1 — 스코프 분리 & pre-cycle 검증

1. **스코프 분리**: 작업 요청을 독립 실행 가능한 단위로 분해한다. 기본 4 worktree, 단 결합도 높은 단일 서브시스템(예 collection diff-at-flush)은 병렬 부적합 → 순차 처리하거나 1-2 worktree로 축소. 각 단위가 hub를 얼마나 공유하는지 평가(Phase 4 abort 기준).
2. main HEAD hash 기록(`git log --oneline -1 main`), 작업 트리 깨끗 확인(`git status --short` 빈 결과), 기존 worktree 잔재 정리(`git worktree list` → cleanup).
3. scope를 사용자가 명시 안 했으면 후보 제시 후 `AskUserQuestion`으로 확정.
4. **통합 브랜치 생성**: `git switch -c cycle/<topic> main`. 모든 worktree는 이 브랜치(또는 main, 동일 base)로 ff-merge되고 최종 PR의 head가 된다.

## Phase 2 — 설계-우선 병렬 개발 (worktree spawn)

- 분리한 단위 수만큼 worktree spawn(기본 4, 결합도에 따라 조정). 각 worktree는 `isolation: "worktree"` + `run_in_background: true`.
- **설계-우선(2-agent)**: 각 worktree에서 먼저 **opus 시니어 아키텍트**가 설계(접근/변경 파일/함정/테스트 계획)를 산출한 뒤, **sonnet 구현 agent**가 그 설계대로 구현한다(architect→executor). 단순 fail-fast 제거 등 저난도 feature는 설계를 경량화하거나 단일 구현 agent로 축소.
- **모델 라우팅 기본 = 난도별 분기** (opus 일색 금지): 기계적/재사용 위주 → sonnet, 신규 알고리즘·복잡 SQL·hub 광범위 → opus. 참조 [[feedback_pipeline_track_streaming]].
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
- 컨벤션: JUnit5 + StepVerifier, AssertJ/Mockito/Hamcrest 금지, constructor injection, `final` 필드, `Simple*`/`Abstract*` naming. 미지원 속성은 fail-fast 거부 + H2 통합 라운드트립 테스트 필수.
- Commit: Conventional Commits, **Co-Authored-By Claude 절대 금지** (강조 1행 reserved). push 금지(오케스트레이터가 PR에서 처리).

### Marker namespace 분리 (hub 충돌 mitigation)

같은 metadata hub를 건드릴 두 feature 동시 spawn 시 각 agent prompt에서 **PersistentProperty marker field 이름을 명시적으로 다르게** 지정한다(예 A=`manyToOne*`, B=`oneToMany*`). 그러면 PP 생성자가 자동 머지된다. 단 `SimpleReactiveEntityOperations.mapRow`처럼 둘 다 광범위 rewrite하는 hub는 marker 분리로도 해소 불가 → 두 feature를 **단일 worktree로 통합 spawn**. PP/EntityMetadata 생성자에 trailing field append는 한 worktree만 하도록 배정(나머지는 Info record 내부 흡수)해 trailing-param auto-merge 손실을 피한다.

## Phase 3 — 위험도 차등 병렬 리뷰 (병합 전)

각 worktree agent 완료 시, **머지 전에** 그 worktree 브랜치 commit range로 병렬 review. **리뷰어 모델은 위험도 차등 라우팅**: 저위험(fail-fast 제거 + 기존 machinery 재사용) → **sonnet**; 고위험(신규 SQL 생성·silent-corruption 위험·hub 광범위 rewrite·보호 contract 인접) → **opus**.

```
git -C <worktree path> log --oneline main..HEAD
git -C <worktree path> diff main..HEAD
```

검토 포인트 매 cycle 공통:
- 보호 contract 시그니처 변경 여부 (룰 #6), blocking call (룰 #4), ThreadLocal (룰 #5), 룰 #1 dep 무변경
- silent dedupe/corruption, 멱등성(ddl-auto=UPDATE 재시작 시 비멱등 DDL/ALTER → 크래시), 비트랜잭션 원자성(다중 statement)
- Spring `@ConfigurationProperties`이면 dead config 위험 (실제 적용 효과까지 검증되는지)
- agent별 sandbox 권한 불일치 가능 → 오케스트레이터가 build를 외부에서 직접 verify

## Phase 4 — 보완 ↔ 재리뷰 루프 (병합 전, 문제 0까지 반복)

review의 critical/major를 **머지 전에** 해당 worktree에서 해소한다: 같은 agent에 `SendMessage`로 수정 요청하거나, 작은 수정은 worktree에서 직접 edit 후 amend/추가 commit.

**핵심: 보완은 단발이 아니라 수렴 루프다.** 보완할 때마다 **반드시 그 변경분을 재리뷰**하고(같은 reviewer 또는 새 reviewer에 변경 commit range 전달), 새 critical/major가 없을 때까지 보완→재리뷰를 반복한다. 종료 조건: 리뷰어가 critical/major **0건**으로 확인(보완이 새 결함을 만들지 않았음까지 검증). minor는 기록만 하고 넘어갈 수 있으나, 누적 minor가 많으면 한 번 더 정리. 설계 결정이 필요해 이 cycle에서 못 닫는 major는 명시적으로 backlog 분류하고 사용자에게 보고(무한 루프 방지) — 단 "문제 없음" 선언은 미해소 critical/major가 0일 때만.

## Phase 5 — 스트리밍 병합 (통합 브랜치로)

**트랙-내부 스트리밍**: feature는 **자신의 리뷰가 수렴(critical/major 0)되는 즉시** 통합 브랜치로 병합한다 — 전체 feature가 다 끝나길 기다리는 배리어를 두지 않는다(제일 느린 feature가 나머지를 인질로 잡지 않게). 단 **hub 충돌 위험 feature는 여전히 격리도 ↑ → hub ↓ 순서로 직렬화**하고, 먼저 병합된 것 위로 rebase한다. 병합 순서 원칙:

1. 신규 모듈 추가 (완전 격리) → ff-merge
2. `*.gradle.kts` settings만 touch → rebase + ff-merge
3. 신규 클래스만 (`io.nova.fetch/` 등) → rebase + ff-merge
4. metadata factory class-level scan → rebase + ff-merge
5. metadata factory field-level loop + ops hub (가장 충돌) → rebase + ff-merge (수동 해소)

각 단계 후 `./gradlew build`. **BSD grep이 한글 주석 파일을 binary로 오판**하므로 머지 손실 검증은 `grep -a` 필수(false alarm 주의). textual auto-merge가 clean해도 semantic 검증은 전체 build로.

### Hub 충돌 abort criterion

다음 중 하나면 worktree rebase abort + 단일 worktree 통합으로 재시도:
- `SimpleReactiveEntityOperations.mapRow`에 두 worktree 광범위 rewrite 모두 적용 — 통합 불가
- 충돌 block 5+ 개가 한 파일에 누적 + 양쪽 핵심 로직
- conflict 해소 30분 초과

## Phase 6 — E2E 테스트 (통합 브랜치, 전용 opus agent)

통합 브랜치 머지 결과 전체의 end-to-end 검증은 **opus 전용 E2E agent**에 위임한다(오케스트레이터 인라인 대신 별도 컨텍스트). 검증 항목:
1. `./gradlew build` (전 모듈 컴파일 + 단위/통합 테스트).
2. **실제 앱 구동**: `nova-example`의 `NovaReactiveExample`(필요 시 `HibernateReactiveExample` 대조)을 실행해 ORM이 실 driver로 동작하는지 확인 — schema 생성, save/find, 관계/컬렉션, 트랜잭션·flush까지 한 흐름으로.
3. 관련 dialect 통합(H2) + (해당 시) `nova-spring-boot-starter`/`nova-spring-data` 통합.

## Phase 7 — 보완 ↔ E2E 재실행 루프 (문제 0까지 반복)

E2E에서 드러난 결함을 통합 브랜치에서 직접 fix-commit + 회귀 테스트 추가. **보완 후 반드시 Phase 6을 재실행**하고, 새 결함이 안 나올 때까지 보완→E2E 재실행을 반복한다. 코드 변경이 컸으면 Phase 3 리뷰도 변경분에 대해 한 번 더 돌려 재검증. 종료 조건: E2E green + 미해소 critical/major 0건. 그때만 Phase 8로 진행한다.

## Phase 8 — PR 생성 및 머지

1. 통합 브랜치 push: `git push -u origin cycle/<topic>`.
2. PR 생성: `gh pr create --base main --head cycle/<topic> --title "<conventional title>" --body "<요약 + feature별 커밋 + 리뷰/E2E 결과>"`. PR body 말미 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
3. CI 있으면 통과 확인. repo는 **squash-only** → `gh pr merge --squash --admin`(브랜치 보호 우회). merge commit/rebase 금지.
4. 로컬 main 동기화: `git switch main && git pull --ff-only`.

## Phase 9 — Cleanup & 다음 cycle confirm

- `git worktree remove <path> -f -f`, `git branch -D worktree-agent-<id>` 모두, 머지된 `cycle/<topic>` 로컬 브랜치 삭제. `git worktree list`에 main만.
- `AskUserQuestion`으로 다음 cycle scope 결정(자율 진행 금지). 옵션 3개 + "멈춤" + "다른 방향".

## Memory 참조

- `feedback_parallel_cycle_workflow.md` — 기본 패턴
- `feedback_pipeline_track_streaming.md` — 이 파이프라인 변경(설계-우선·위험도 차등 리뷰·스트리밍 병합·E2E 전용 agent)의 근거
- `feedback_worktree_base_alignment.md` — `git rebase main` (LOCAL) 필수
- `feedback_worktree_cwd_file_path.md` — Edit absolute path가 main repo로 가는 함정
- `feedback_grep_binary_korean.md` — BSD grep 한글파일 binary 오판 → `grep -a`
- `feedback_parallel_trailing_param_merge.md` — trailing-param auto-merge 손실
- `feedback_metadata_stream_pattern.md` — marker property stream 자동 추출
- `feedback_column_uniqueness_check.md` — column space 확장 시 uniqueness 검증
- `feedback_spring_config_dead_test.md` — `@ConfigurationProperties` 실제 효과 검증
- `feedback_integration_test_surfaces_bugs.md` — dialect 추가 시 in-memory driver 통합 테스트 필수
- `feedback_agent_sandbox_inconsistent.md` — agent별 sandbox 권한 불일치, 외부 verify
- `reference_maven_central_publish.md` — 발행 절차(태그 push → GH Actions)
