<!-- SPDX-License-Identifier: Apache-2.0 -->

# Contributing to Nova

Nova에 기여해주셔서 감사합니다. 이 문서는 **이슈 / PR / 커밋 메시지** 형식과 작업 흐름을 규격화합니다. 코드 컨벤션과 아키텍처 경계는 [`AGENTS.md`](AGENTS.md)를 참고하세요.

---

## 작업 흐름

1. **이슈 먼저 열기** — 버그 / 기능 / 문서 어느 쪽이든 작업을 시작하기 전 이슈로 합의합니다. 작은 오타 / 한 줄 fix는 예외.
2. **브랜치 분기** — `main`에서 분기해 단일 변경에 집중합니다. 브랜치명 권장: `<type>/<short-slug>` (예: `feat/cursor-pagination`, `fix/schema-onetomany-leak`, `docs/contributing-guide`).
3. **로컬 검증** — `./gradlew build`로 전체 테스트 통과 확인. 변경한 모듈만 빠르게 돌리려면 `./gradlew :nova-project:nova-core:test` 같은 narrow task를 먼저 돌리세요.
4. **PR 생성** — 아래 [PR 템플릿](#pr-템플릿)을 채워 발행. 연관 이슈는 `Closes #N` 으로 명시.
5. **리뷰 → 머지** — 1개 이상 approve 후 머지. 가능하면 squash-merge로 history를 깔끔히 유지.

---

## 커밋 메시지 규격

[Conventional Commits](https://www.conventionalcommits.org/) 형식을 따릅니다.

```
<type>(<scope>): <subject>

<body, 선택>

<footer, 선택>
```

### Type (필수)

| Type       | 사용처                                                                   |
|------------|-------------------------------------------------------------------------|
| `feat`     | 새 기능 (사용자 가시적인 동작 추가)                                          |
| `fix`      | 버그 수정                                                                 |
| `docs`     | 문서만 변경 (README, docs/, javadoc)                                       |
| `refactor` | 동작 변화 없는 내부 정리                                                    |
| `test`     | 테스트 추가/수정만                                                          |
| `perf`     | 성능 개선                                                                  |
| `build`    | 빌드 시스템 / 의존성 (gradle, dependency 버전)                              |
| `ci`       | CI 파이프라인 (`.github/workflows/`)                                       |
| `chore`    | 위 어디에도 속하지 않는 잡일 (버전 bump, gitignore 정리 등)                    |
| `style`    | 코드 포맷 / 공백 (로직 무관)                                                |

### Scope (선택, 권장)

가능하면 모듈명을 그대로:

`core`, `r2dbc`, `dialect-postgresql`, `dialect-mysql`, `dialect-mariadb`, `dialect-h2`, `dialect-oracle`, `spring-boot`, `spring-data`, `metrics`, `docs`, `build`, `ci`, `schema`, `sql`, `tx`, `nova` (aggregate)

### Subject (필수)

- 명령형 현재 시제 (`add`, `fix`, `update` — `added` / `adds` 아님)
- 첫 글자 소문자, 마침표 없음
- **50자** 이하 권장, 최대 **72자**

### Breaking change

타입 뒤 `!`를 붙이고 footer에 `BREAKING CHANGE: ...` 명시.

```
feat(core)!: drop blocking convenience method block()

BREAKING CHANGE: ReactiveEntityOperations.block(...) removed.
Use .subscribe() or upstream operators. 마이그레이션 가이드: ...
```

### 예시

좋은 예:

```
feat(dialect-h2): add @Json column mapping with text fallback
fix(schema): exclude @OneToMany inverse fields from createTable column list
docs(readme): split into focused docs/ pages and trim README
refactor(core): extract EntityListenerInvoker from SimpleReactiveEntityOperations
test(sql): cover pageable.totalElements with empty result set
build: bump default version to 1.0.2-SNAPSHOT
```

나쁜 예 (피하세요):

```
update README                          # type 없음, scope 없음, 무엇을 했는지 모호
fix bug                                # 어떤 버그인지 정보 없음
feat: Added new feature for users.     # 과거형 + 마침표 + 모호
chore: stuff                           # 의미 없음
WIP                                    # WIP 커밋은 squash 전제로만 허용
```

### AI Co-Author 금지

AI agent를 `Co-Authored-By:`로 추가하지 않습니다 (AGENTS.md 정책).

---

## 이슈 규격

이슈는 [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/)에 정의된 4가지 템플릿 중 하나를 선택합니다.

### 1. Bug report

- **환경** — Java 버전, Nova 버전, dialect, R2DBC driver 버전
- **재현 절차** — 최소 코드 스니펫. fixture entity가 필요하면 inline 포함
- **기대 동작** — 무엇이 일어났어야 했는지
- **실제 동작** — 무엇이 일어났는지 + 스택트레이스 (있으면)
- **이미 시도한 것** — 워크어라운드, 디버깅 흔적

### 2. Feature request

- **해결하려는 문제** — 왜 필요한지. 사용자 시나리오로 서술
- **제안** — 어떤 API / 동작을 추가/변경하고 싶은지
- **대안** — 검토한 다른 접근과 그것을 채택하지 않은 이유
- **스코프** — 새 dialect / 새 코어 API / 새 모듈 등 영향 범위

### 3. Documentation

- **위치** — README의 어떤 섹션 / `docs/`의 어떤 파일 / javadoc 어느 클래스
- **문제** — 누락 / 오류 / 모호함 / 오래됨 중 어느 것
- **개선안** — 어떻게 바뀌면 좋겠는지

### 4. Question

- **목표** — 무엇을 하고 싶은지
- **시도한 것** — 어떤 코드/문서를 봤고 무엇이 막혔는지

질문은 가능하면 GitHub Discussions를 먼저 고려하세요. 답변이 일반화되면 이슈 / 문서 패치로 승격.

### 좋은 이슈 제목

- `[bug] schema generation crashes on @OneToMany inverse field`
- `[feat] support Oracle dialect`
- `[docs] README install snippet uses unrunnable placeholder`

피해야 할 제목: `버그`, `안 됨`, `질문 있습니다`.

---

## PR 규격

PR은 [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)가 자동 채워줍니다. 빈 칸을 모두 작성하세요.

### PR 템플릿

```markdown
## Summary
<1-3 줄로 무엇을 / 왜>

## Related issue
Closes #<number>

## Changes
- <단위 변경 1>
- <단위 변경 2>

## Test plan
- [ ] `./gradlew build` 통과
- [ ] 변경 모듈 narrow test (`./gradlew :nova-project:<module>:test`) 통과
- [ ] 새 기능이라면 회귀 테스트 추가
- [ ] (해당 시) 문서 갱신 — README / docs/ / javadoc

## Breaking change
- [ ] No
- [ ] Yes — 영향 범위:

## Checklist
- [ ] 커밋 메시지가 Conventional Commits 형식
- [ ] PR은 단일 관심사에 집중
- [ ] (해당 시) `docs/`에 사용자 가시적 변경 문서화
- [ ] (해당 시) 새 의존성 / 모듈 추가는 AGENTS.md "Boundaries" 사전 합의 완료
```

### PR 크기

- 가능하면 **300줄 이내 diff**. 큰 작업은 incremental PR 시리즈로 쪼개세요.
- 변경 동기와 영향 범위가 명확하면 더 커도 무방하지만, 리뷰 시간을 고려해 미리 알리세요.
- **single concern** — README 트림 + CI 변경 + 새 기능을 한 PR에 묶지 말 것.

### 머지 정책

- 1+ approve 후 머지.
- `main`에 직접 push 금지.
- pre-commit / pre-push hook 우회(`--no-verify`) 금지. 훅이 실패하면 원인을 고칩니다.
- force-push는 본인 PR 브랜치에서만, `main` / 보호 브랜치에는 절대 금지.

---

## 코드 컨벤션

전 영역(아키텍처 경계, 테스트 표준, 코딩 컨벤션, 빌드 명령, 커밋 정책)은 [`AGENTS.md`](AGENTS.md)에 정리되어 있습니다. PR 전 한 번 훑어주세요.

핵심:
- `nova-core`는 Project Reactor + R2DBC SPI 외 의존 금지
- `Mono` / `Flux`만 노출. `block()` / `blockFirst()` 등 사용 금지
- 트랜잭션 상태는 Reactor `Context`로만 전파 (`ThreadLocal` 금지)
- DB별 동작은 dialect 모듈에 배치, 절대 `nova-core`에 침투 금지
- 테스트는 JUnit 5 + `StepVerifier`. AssertJ / Mockito 도입은 사전 합의

---

## 보안 이슈

보안 취약점은 공개 이슈가 아닌 메인테이너에게 직접 이메일로 보고하세요. 자세한 절차는 [`SECURITY.md`](SECURITY.md) (있을 경우)를 참고.

---

## License

기여한 코드는 [Apache License 2.0](LICENSE)에 따라 라이선스됩니다.
