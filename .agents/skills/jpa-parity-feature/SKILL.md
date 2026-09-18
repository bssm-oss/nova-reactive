# JPA Parity Feature

Nova가 아직 fail-fast로 거부하는 JPA(`jakarta.persistence`) 기능/조합 하나를 실지원으로 전환하는 **반복 5단계 템플릿**. 지난 ~10 PR에서 매번 같은 절차라 스킬로 고정한다. 이 스킬은 단일 feature 단위이며, 여러 feature를 묶으면 `parallel-cycle`이 각 worktree 안에서 이 절차를 돌린다.

## Trigger 조건

- "복합키 to-one을 X 위치에서 지원", "@MapKeyClass entity 키 지원" 등 **현재 fail-fast인 것을 실지원으로 전환**하는 요청
- backlog(`project_jpa_compat_backlog.md`)의 "남은 edge / 남은 backlog" 항목을 구현할 때

**Invoke하지 않는 경우**: 신규(현재 아예 미인식) 애너테이션 첫 도입(설계 비중이 커 `planner`/`architect` 먼저), 버그픽스, dialect 렌더링 수정(`dialect-implementer`).

## 핵심 원칙

- **fail-fast를 없애는 게 아니라 경계를 미는 것.** 지원 범위를 넓히되, 새로 도달 가능해진 조합 중 **이번에 구현 안 하는 것은 명확한 fail-fast로 유지**한다(조용한 오답 절대 금지). "지원 X → 지원 Y, 나머지는 여전히 loud reject"가 매 feature의 형태다.
- **미검증 매핑 미ship.** 드라이버(H2)로 실제 라운드트립 안 되면 지원 주장 금지 → fail-fast로 남긴다.

## 5단계

### ① 현재 fail-fast 위치 특정
- 대상 조합이 **어디서** 거부되는지 찾는다: metadata 빌드 시점(`EntityMetadataFactory`)인지, SQL 빌드 시점(`JpqlSqlBuilder`/`CriteriaSqlBuilder`)인지, 실행 시점(`SimpleReactiveEntityOperations`)인지. `grep -a` 로 거부 메시지 문자열을 찾아 choke-point를 특정(BSD grep 한글주석 binary 오판 회피).
- 그 fail-fast가 **여러 경로**에 흩어져 있는지 확인(예: TREAT는 treatColumn 경로 + plain columnOf 경로 둘 다 가드해야 silent-NULL 안 남). 한 곳만 고치면 나머지 경로가 조용히 샌다.

### ② 실지원 전환
- **기존 machinery 재사용 우선.** 새 SQL 생성보다 이미 있는 것(`ToOneForeignKey` component 순서, `addCompositeManyToOneSpec` OR-of-ANDs 배치, converter 저장타입 경로)을 재사용하면 저위험. 새 canonical 로직을 만들면 write/read/DDL/compare 네 경로가 **단일 순서 소스**를 공유해야 함(순서 drift = silent 손상).
- AGENTS.md 계약 준수: 보호 contract(`SqlRenderer`/`SchemaGenerator`/`SqlExecutor`/`Dialect` 등)는 **default method 추가만**, 시그니처 변경 금지. `PersistentProperty`/`EntityMetadata` 생성자는 **append-at-end**(단일키 경로 byte-identical 유지). 새 컬럼타입 분기는 `columnType()`(저장타입) 경유([[feedback_converter_read_source_type]]).
- 코어 새 의존성 금지, `block()`/`blockFirst()`/`blockLast()`/`toIterable()` 금지, `ThreadLocal` 금지(Reactor Context).

### ③ 잔여 fail-fast 유지
- 지원 후 새로 도달 가능해진 조합을 열거하고, 이번 범위 밖인 것에 **loud reject**(build-time이 최선, 못 하면 runtime `IllegalStateException`) + 각각 테스트로 teeth를 박는다. "fail-fast를 없앤 자리에 새 fail-fast가 정확히 서 있는지"가 리뷰 포인트.

### ④ H2 라운드트립 통합 테스트
- 단위 테스트(팩토리 honor/reject, SQL 문자열)만으론 부족 — **H2 in-memory 드라이버로 save→find 왕복**을 반드시 검증([[feedback_integration_test_surfaces_bugs]]). 통합 테스트가 SQL-string 단위 테스트가 못 잡는 드라이버 수용성 버그를 잡는다.
- 컨벤션: JUnit5 + `StepVerifier`, AssertJ/Mockito/Hamcrest 금지, 공유 fixture는 `io.nova.support.fixtures.FixtureEntities`. 잔여 fail-fast도 테스트(거부 메시지 assert).

### ⑤ 문서 + 메모리 갱신
- `docs/jpa-compatibility.md`: 해당 행을 ✅/⟳로 갱신, "Not yet supported (fail-fast)" 목록에서 지원된 항목 제거 + 여전히 남는 잔여 fail-fast는 정확히 서술.
- `project_jpa_compat_backlog.md`: 완료 항목을 현재-상태 요약에 반영, 남은 backlog에서 제거. 상세 PR 히스토리는 git/릴리즈노트에 있으므로 메모리는 **현재상태만** 유지(비대화 방지).

## 리뷰 관점 (병합 전, `senior-backend-code-reviewer`)

- 조용한 오답 회피: 지원 못 하는 조합이 **전부** loud reject되는가(모든 경로)?
- 단일 순서 소스: 다중컬럼 확장이 write/read/DDL/compare에서 같은 순서인가?
- 계약 additive: 보호 contract default-only, 생성자 append-at-end, 단일키 byte-identical?
- H2 통합 테스트가 실제 왕복을 커버하는가(SQL-string만 아님)?

## Memory 참조

- `project_jpa_compat_backlog.md` — 현재 파리티 상태 + 남은 backlog
- `feedback_integration_test_surfaces_bugs` — H2 통합 테스트 필수
- `feedback_converter_read_source_type` — 저장타입 디코드 함정
- `feedback_metadata_view_naming` — properties() raw vs view 메서드 분리
- `feedback_grep_binary_korean` — `grep -a` 필수
